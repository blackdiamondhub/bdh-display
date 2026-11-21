package com.blackdiamondlodge.display;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.os.Debug;
import android.view.PixelCopy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Kiosk wrapper for the BlackDiamondHub UI on UniFi Connect displays.
 * Recovers from renderer crashes/hangs, retries when the hub is unreachable,
 * recycles the WebView nightly, and grants the hub page microphone access.
 */
public class MainActivity extends Activity {
    private static final String TAG = "BDHDisplay";

    private static final long WATCHDOG_INTERVAL_MS = 60_000;
    private static final long WATCHDOG_TIMEOUT_MS = 20_000;
    private static final int RECYCLE_HOUR = 3, RECYCLE_MINUTE = 30;

    private final Handler h = new Handler(Looper.getMainLooper());
    private static final long HEARTBEAT_MS = 5 * 60_000;

    private final ArrayDeque<Long> crashTimes = new ArrayDeque<>();
    private final long startedAt = SystemClock.elapsedRealtime();

    private FrameLayout root;
    private WebView web;
    private TextView overlay;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private SharedPreferences prefs;
    private String hubUrl;
    private boolean mainFrameError;
    private long retryDelayMs = 5_000;
    private int crashes, hangs, recycles, loadErrors;
    private long pingSeq, pongSeq;
    private int cornerTaps;
    private long firstCornerTap;
    private volatile String currentUrl = "";
    private DebugServer debugServer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DebugLog.init(getFilesDir());
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            DebugLog.e("UNCAUGHT in " + t.getName() + ": " + android.util.Log.getStackTraceString(ex));
            if (prev != null) prev.uncaughtException(t, ex);
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(true);
        prefs = getSharedPreferences("bdh", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        overlay = new TextView(this);
        overlay.setTextColor(0xffdddddd);
        overlay.setTextSize(22);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xcc000000);
        overlay.setVisibility(View.GONE);
        setContentView(root);

        log("start v" + versionName() + " webview " + webViewVersion() + " android " + android.os.Build.VERSION.RELEASE
                + " " + android.os.Build.MODEL + " ip " + localIp());
        debugServer = new DebugServer(this::route);
        debugServer.start();
        h.postDelayed(heartbeat, HEARTBEAT_MS);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        createWebView();
        loadHub();
        h.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        scheduleRecycle();
    }

    // ---- WebView lifecycle -------------------------------------------------

    private void createWebView() {
        if (web != null) destroyWebView();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " BDHDisplay/" + versionName());
        web.addJavascriptInterface(new Bridge(), "BDHDisplay");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        if (overlay.getParent() == null) root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        pongSeq = pingSeq;
    }

    private void destroyWebView() {
        stopTalk("webview");
        exitFullscreen();
        WebView w = web;
        web = null;
        if (w == null) return;
        root.removeView(w);
        try {
            w.stopLoading();
            w.setWebChromeClient(null);
            w.setWebViewClient(null);
            w.destroy();
        } catch (Throwable t) {
            log("destroy error: " + t);
        }
    }

    private void recreate(String why, long delayMs) {
        log("recreating webview: " + why);
        destroyWebView();
        h.removeCallbacks(retryLoad);
        h.postDelayed(() -> { createWebView(); loadHub(); }, delayMs);
    }

    private void loadHub() {
        hubUrl = resolveHubUrl();
        if (web == null) return;
        if (hubUrl.isEmpty()) {
            log("no hub configured; prompting");
            showSettings();
            return;
        }
        mainFrameError = false;
        log("loading " + hubUrl);
        web.loadUrl(hubUrl);
    }

    private final Runnable retryLoad = this::loadHub;

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return false;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            mainFrameError = false;
            currentUrl = url;
            log("page started " + url);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            log("page finished " + url + (mainFrameError ? " (with error)" : ""));
            if (!mainFrameError) {
                overlay.setVisibility(View.GONE);
                retryDelayMs = 5_000;
            }
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) mainFrameFailed("error " + e.getErrorCode() + " " + e.getDescription());
            else DebugLog.w("net error " + e.getErrorCode() + " " + e.getDescription() + " " + r.getUrl());
        }

        @Override
        public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
            DebugLog.w("http " + resp.getStatusCode() + " " + r.getMethod() + " " + r.getUrl()
                    + (r.isForMainFrame() ? " (main frame)" : ""));
            if (r.isForMainFrame() && resp.getStatusCode() >= 500) {
                mainFrameFailed("HTTP " + resp.getStatusCode());
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            crashes++;
            long now = SystemClock.elapsedRealtime();
            crashTimes.addLast(now);
            while (!crashTimes.isEmpty() && now - crashTimes.peekFirst() > 10 * 60_000) crashTimes.removeFirst();
            boolean loop = crashTimes.size() >= 5;
            showOverlay(loop ? "Display recovering (repeated crashes)…" : "Reloading…");
            recreate("renderer gone (crashed=" + d.didCrash() + ", priority=" + d.rendererPriorityAtExit()
                            + ", url=" + currentUrl + ", total " + crashes + ")",
                    loop ? 60_000 : 1_000);
            return true;
        }
    }

    private void mainFrameFailed(String why) {
        mainFrameError = true;
        loadErrors++;
        log("hub load failed: " + why + ", retry in " + retryDelayMs / 1000 + "s");
        showOverlay("Hub unreachable – retrying in " + retryDelayMs / 1000 + "s\n" + hubUrl);
        h.removeCallbacks(retryLoad);
        h.postDelayed(retryLoad, retryDelayMs);
        retryDelayMs = Math.min(retryDelayMs * 2, 60_000);
    }

    // Element fullscreen (requestFullscreen) only works if the host app shows
    // the view WebView hands us; without this every request is rejected.
    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        WebChromeClient.CustomViewCallback cb = fullscreenCallback;
        fullscreenCallback = null;
        if (cb != null) {
            try { cb.onCustomViewHidden(); } catch (Throwable t) { log("fullscreen hide error: " + t); }
        }
        log("fullscreen exit");
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View v, CustomViewCallback cb) {
            if (fullscreenView != null) {
                cb.onCustomViewHidden();
                return;
            }
            fullscreenView = v;
            fullscreenCallback = cb;
            v.setBackgroundColor(Color.BLACK);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
            if (overlay.getParent() == root) overlay.bringToFront();
            log("fullscreen enter");
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            runOnUiThread(() -> {
                Uri o = r.getOrigin();
                Uri hub = Uri.parse(hubUrl);
                if (o != null && o.getHost() != null && o.getHost().equals(hub.getHost())) {
                    log("granted " + String.join(",", r.getResources()) + " to " + o);
                    r.grant(r.getResources());
                } else {
                    log("denied permission request from " + o);
                    r.deny();
                }
            });
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            String lvl;
            switch (m.messageLevel()) {
                case ERROR: lvl = "E"; break;
                case WARNING: lvl = "W"; break;
                default: lvl = "I";
            }
            String src = m.sourceId() == null ? "" : m.sourceId();
            DebugLog.add(lvl, "js " + m.messageLevel().name().toLowerCase(Locale.US) + ": " + m.message()
                    + (src.isEmpty() ? "" : " @" + src + ":" + m.lineNumber()));
            return true;
        }
    }

    // ---- Watchdog / maintenance ---------------------------------------------

    /** Detects a hung renderer: evaluateJavascript callbacks stop arriving. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            h.postDelayed(this, WATCHDOG_INTERVAL_MS);
            if (web == null || mainFrameError) return;
            final long seq = ++pingSeq;
            web.evaluateJavascript("1", v -> pongSeq = seq);
            h.postDelayed(() -> {
                if (web != null && pingSeq == seq && pongSeq < seq) {
                    hangs++;
                    showOverlay("Reloading…");
                    recreate("renderer unresponsive (total " + hangs + ")", 1_000);
                }
            }, WATCHDOG_TIMEOUT_MS);
        }
    };

    private final Runnable recycle = new Runnable() {
        @Override
        public void run() {
            recycles++;
            recreate("nightly recycle", 500);
            scheduleRecycle();
        }
    };

    private void scheduleRecycle() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, RECYCLE_HOUR);
        c.set(Calendar.MINUTE, RECYCLE_MINUTE);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_MONTH, 1);
        h.removeCallbacks(recycle);
        h.postDelayed(recycle, c.getTimeInMillis() - System.currentTimeMillis());
    }

    // ---- Hub URL ------------------------------------------------------------

    private String resolveHubUrl() {
        String u = normalizeHubUrl(prefs.getString("hub_url", ""));
        return u == null ? "" : u;
    }

    /** "host:8000" -> "http://host:8000/". Returns null for anything that isn't an http(s) URL with a host. */
    static String normalizeHubUrl(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        if (u.getHost() == null || u.getHost().isEmpty()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
        String path = u.getEncodedPath();
        return (path == null || path.isEmpty()) ? s + "/" : s;
    }

    private void saveHubUrl(String url) {
        prefs.edit().putString("hub_url", url).apply();
        log("hub set to " + url);
        recreate("hub changed", 200);
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---- Hidden settings: tap the top-left corner 5 times within 3 s --------

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getX() < 100 && e.getY() < 100) {
            long now = SystemClock.elapsedRealtime();
            if (now - firstCornerTap > 3_000) { firstCornerTap = now; cornerTaps = 0; }
            if (++cornerTaps >= 5) { cornerTaps = 0; showSettings(); }
        }
        return super.dispatchTouchEvent(e);
    }

    private AlertDialog settingsDialog;

    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        final boolean firstRun = resolveHubUrl().isEmpty();
        final EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("hub address, e.g. http://hub-host:8000");
        url.setText(prefs.getString("hub_url", ""));
        final TextView err = new TextView(this);
        err.setTextColor(0xffe8243a);
        err.setVisibility(View.GONE);
        final TextView found = new TextView(this);
        found.setText("Searching for Black Diamond Hub\u2026");
        final android.widget.LinearLayout hubList = new android.widget.LinearLayout(this);
        hubList.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView st = new TextView(this);
        st.setTextSize(12);
        st.setText((firstRun ? "Pick your hub, or enter its address.\n\n" : "")
                + status() + "\ndebug http://" + localIp() + ":" + DebugServer.PORT + "/\n\n"
                + String.join("\n", DebugLog.recent(80)));
        ScrollView sv = new ScrollView(this);
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding(32, 16, 32, 16);
        ll.addView(found);
        ll.addView(hubList);
        ll.addView(url);
        ll.addView(err);
        ll.addView(st);
        sv.addView(ll);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "BDH Display setup" : "BDH Display " + versionName())
                .setView(sv)
                .setCancelable(!firstRun)
                .setPositiveButton("Save & connect", null);
        if (!firstRun) {
            b.setNeutralButton("Reload", (d, w) -> recreate("manual reload", 200))
             .setNegativeButton("Close", null);
        }
        settingsDialog = b.create();
        settingsDialog.setOnShowListener(d -> settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = normalizeHubUrl(url.getText().toString());
            if (n == null) {
                err.setText("Enter a hub address like http://hub-host:8000");
                err.setVisibility(View.VISIBLE);
                return;
            }
            settingsDialog.dismiss();
            saveHubUrl(n);
        }));

        final String initialText = url.getText().toString();
        final List<HubDiscovery.Hub> latest = new java.util.ArrayList<>();
        // First run: connect by itself when exactly one hub answers and nobody has started typing.
        final Runnable autoPick = () -> {
            if (!firstRun || latest.size() != 1 || !settingsDialog.isShowing()
                    || !url.getText().toString().equals(initialText)) return;
            log("auto-selecting the only hub: " + latest.get(0).label());
            settingsDialog.dismiss();
            saveHubUrl(latest.get(0).url);
        };
        final Runnable nothingFound = () -> {
            if (latest.isEmpty()) found.setText("No hub found on this network. Enter its address below.");
        };
        final HubDiscovery disc = new HubDiscovery(this, hubs -> {
            latest.clear();
            latest.addAll(hubs);
            hubList.removeAllViews();
            found.setText(hubs.isEmpty() ? "Searching for Black Diamond Hub\u2026"
                    : hubs.size() == 1 ? "Found a hub:" : "Found " + hubs.size() + " hubs. Pick one:");
            String current = resolveHubUrl();
            for (HubDiscovery.Hub hub : hubs) {
                android.widget.Button btn = new android.widget.Button(this);
                btn.setAllCaps(false);
                btn.setText(hub.label() + "\n" + hub.url + (hub.url.equals(current) ? "  (current)" : ""));
                btn.setOnClickListener(v -> { settingsDialog.dismiss(); saveHubUrl(hub.url); });
                hubList.addView(btn);
            }
            h.removeCallbacks(autoPick);
            // Short settle so a second hub on the network gets a chance to show up.
            if (hubs.size() == 1) h.postDelayed(autoPick, 2_500);
        });
        settingsDialog.setOnDismissListener(d -> {
            disc.stop();
            h.removeCallbacks(autoPick);
            h.removeCallbacks(nothingFound);
        });
        settingsDialog.show();
        disc.start();
        h.postDelayed(nothingFound, 10_000);
    }

    // ---- Misc ---------------------------------------------------------------

    private String status() {
        long up = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        return String.format(Locale.US,
                "hub %s\nip %s | webview %s\nuptime %dh%02dm | crashes %d | hangs %d | load errors %d | recycles %d",
                hubUrl, localIp(), webViewVersion(), up / 3600, (up / 60) % 60, crashes, hangs, loadErrors, recycles);
    }

    /** Exposed to the hub page as window.BDHDisplay. */
    private class Bridge {
        @JavascriptInterface
        public String info() {
            long up = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            return String.format(Locale.US,
                    "{\"app\":\"bdh-display\",\"version\":\"%s\",\"webview\":\"%s\",\"uptime_s\":%d,"
                            + "\"crashes\":%d,\"hangs\":%d,\"load_errors\":%d,\"recycles\":%d}",
                    versionName(), webViewVersion(), up, crashes, hangs, loadErrors, recycles);
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> recreate("requested by page", 200));
        }

        @JavascriptInterface
        public boolean talkAvailable() {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        /** Streams the mic to go2rtc talk stream `stream` on the hub. Returns a session id, or "" if refused. */
        @JavascriptInterface
        public String talkStart(String stream, int port) {
            String id = Integer.toString(++talkSeq);
            String host = hubUrl == null ? null : Uri.parse(hubUrl).getHost();
            if (host == null || stream == null || !stream.matches("[A-Za-z0-9_]{1,64}") || port < 1024 || port > 65535) {
                DebugLog.w("talk refused: stream=" + stream + " port=" + port);
                h.post(() -> talkJs("state", "'error','refused','" + id + "'"));
                return id;
            }
            if (!talkAvailable()) {
                h.post(() -> talkJs("state", "'error','no mic permission','" + id + "'"));
                return id;
            }
            TalkSession old;
            synchronized (talkLock) {
                old = talk;
                if (old != null) old.stop();
                talk = new TalkSession(id, host, stream, port, talkListener);
                talk.start();
            }
            if (old != null) {
                String oldId = old.id;
                h.post(() -> talkJs("state", "'stopped','replaced','" + oldId + "'"));
            }
            log("talk " + id + " start " + stream + " @" + host + ":" + port);
            return id;
        }

        @JavascriptInterface
        public void talkStop() {
            stopTalk("page");
        }
    }

    private final Object talkLock = new Object();
    private TalkSession talk;
    private int talkSeq;

    private final TalkSession.Listener talkListener = new TalkSession.Listener() {
        @Override
        public void onState(TalkSession s, String state, String detail) {
            log("talk " + s.id + " " + state + (detail.isEmpty() ? "" : " " + detail));
            if (!"talking".equals(state)) synchronized (talkLock) { if (talk == s) talk = null; }
            String d = JSONObject.quote(detail);
            h.post(() -> talkJs("state", "'" + state + "'," + d + ",'" + s.id + "'"));
        }

        @Override
        public void onLevel(TalkSession s, double dbfs) {
            String v = String.format(Locale.US, "%.1f", dbfs);
            h.post(() -> talkJs("level", v));
        }
    };

    /**
     * evaluateJavascript only runs in the top frame, but the talking page is
     * usually an app iframe inside the hub shell, so callbacks are also
     * posted to every nested frame as {bdhTalk:{fn,args}}.
     */
    private void talkJs(String fn, String args) {
        if (web == null) return;
        web.evaluateJavascript("(function(fn,a){"
                + "try{var t=window.bdhTalk;if(t&&t[fn])t[fn].apply(t,a)}catch(e){}"
                + "(function p(w){var n=0;try{n=w.frames.length}catch(e){}"
                + "for(var i=0;i<n;i++){try{var f=w.frames[i];f.postMessage({bdhTalk:{fn:fn,args:a}},'*');p(f)}catch(e){}}"
                + "})(window)})('" + fn + "',[" + args + "])", null);
    }

    private void stopTalk(String why) {
        TalkSession s;
        synchronized (talkLock) { s = talk; talk = null; }
        if (s != null) {
            s.stop();
            log("talk " + s.id + " stop (" + why + ")");
            if (!"page".equals(why)) talkJs("state", "'stopped','" + why + "','" + s.id + "'");
        }
    }

    private void showOverlay(String msg) {
        overlay.setText(msg);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void log(String m) {
        DebugLog.i(m);
    }

    // ---- Debug HTTP API (see README) -----------------------------------------

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            h.postDelayed(this, HEARTBEAT_MS);
            try {
                JSONObject m = memory();
                log("heartbeat url=" + currentUrl + " mem=" + m);
            } catch (Exception e) {
                log("heartbeat failed: " + e);
            }
        }
    };

    private JSONObject memory() throws Exception {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        Debug.MemoryInfo dm = new Debug.MemoryInfo();
        Debug.getMemoryInfo(dm);
        Runtime rt = Runtime.getRuntime();
        return new JSONObject()
                .put("sys_avail_mb", mi.availMem >> 20).put("sys_total_mb", mi.totalMem >> 20)
                .put("sys_low", mi.lowMemory).put("app_pss_mb", dm.getTotalPss() >> 10)
                .put("java_used_mb", (rt.totalMemory() - rt.freeMemory()) >> 20)
                .put("native_heap_mb", Debug.getNativeHeapAllocatedSize() >> 20);
    }

    private JSONObject statusJson() throws Exception {
        long up = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        return new JSONObject()
                .put("app", "bdh-display").put("version", versionName()).put("webview", webViewVersion())
                .put("android", android.os.Build.VERSION.RELEASE).put("model", android.os.Build.MODEL)
                .put("ip", localIp()).put("hub", hubUrl).put("url", currentUrl)
                .put("uptime_s", up).put("crashes", crashes).put("hangs", hangs)
                .put("load_errors", loadErrors).put("recycles", recycles)
                .put("main_frame_error", mainFrameError)
                .put("overlay", overlay.getVisibility() == View.VISIBLE ? String.valueOf(overlay.getText()) : null)
                .put("mic_permission", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                .put("log_seq", DebugLog.lastSeq())
                .put("memory", memory())
                .put("token_configured", !BuildInfo.DEBUG_TOKEN_SHA256.isEmpty());
    }

    private DebugServer.Response route(DebugServer.Request r) throws Exception {
        switch (r.path) {
            case "/":
                return DebugServer.Response.text(200, "BDH Display " + versionName() + " debug API\n"
                        + "GET  /status                 app/webview/memory state\n"
                        + "GET  /logs?since=SEQ&n=500   app log (ring buffer); X-Log-Seq = last seq\n"
                        + "GET  /logs/file?kb=256       persisted log incl. previous runs\n"
                        + "GET  /logcat?n=500           this process's logcat\n"
                        + "GET  /screenshot?scale=0.5   JPEG of the window (includes video)\n"
                        + "GET  /discover?secs=6        browse mDNS for hubs (_bdh-hub._tcp)\n"
                        + "POST /eval   body=JS         [token] run JS in the main frame, returns JSON\n"
                        + "POST /reload                 [token] recreate the WebView\n"
                        + "POST /navigate?url=URL       [token] load a URL\n"
                        + "POST /hub?url=URL            [token] set the hub address and reconnect\n"
                        + "token: X-Debug-Token header\n");
            case "/status":
                return DebugServer.Response.json(statusJson().toString(2));
            case "/logs": {
                long since = Long.parseLong(r.q("since", "0"));
                int n = Integer.parseInt(r.q("n", "500"));
                List<DebugLog.Entry> es = DebugLog.since(since, n);
                if ("json".equals(r.q("format", ""))) {
                    JSONArray a = new JSONArray();
                    for (DebugLog.Entry e : es) a.put(new JSONObject().put("seq", e.seq).put("line", e.line));
                    return DebugServer.Response.json(new JSONObject().put("last", DebugLog.lastSeq()).put("lines", a).toString());
                }
                StringBuilder sb = new StringBuilder();
                for (DebugLog.Entry e : es) sb.append(e.seq).append(' ').append(e.line).append('\n');
                return DebugServer.Response.text(200, sb.toString());
            }
            case "/logs/file":
                return new DebugServer.Response(200, "text/plain; charset=utf-8",
                        DebugLog.fileTail(Integer.parseInt(r.q("kb", "256")) * 1024));
            case "/logcat":
                return DebugServer.Response.text(200, logcat(Integer.parseInt(r.q("n", "500"))));
            case "/screenshot":
                return screenshot(Float.parseFloat(r.q("scale", "0.5")));
            case "/discover": {
                final AtomicReference<List<HubDiscovery.Hub>> res = new AtomicReference<>(Collections.emptyList());
                final AtomicReference<HubDiscovery> d = new AtomicReference<>();
                runOnUiThread(() -> { d.set(new HubDiscovery(this, res::set)); d.get().start(); });
                Thread.sleep(Math.min(20, Long.parseLong(r.q("secs", "6"))) * 1000);
                runOnUiThread(() -> { if (d.get() != null) d.get().stop(); });
                JSONArray a = new JSONArray();
                for (HubDiscovery.Hub hub : res.get())
                    a.put(new JSONObject().put("service", hub.service).put("name", hub.name)
                            .put("location", hub.location).put("url", hub.url));
                return DebugServer.Response.json(new JSONObject().put("type", HubDiscovery.TYPE).put("hubs", a).toString(2));
            }
            case "/eval":
            case "/reload":
            case "/navigate":
            case "/hub":
                if (!"POST".equals(r.method)) return DebugServer.Response.text(400, "POST only\n");
                if (!r.authorized()) return DebugServer.Response.text(401, "missing/invalid X-Debug-Token\n");
                return control(r);
            default:
                return DebugServer.Response.text(404, "not found\n");
        }
    }

    private DebugServer.Response control(DebugServer.Request r) throws Exception {
        if ("/reload".equals(r.path)) {
            runOnUiThread(() -> recreate("debug API reload", 200));
            return DebugServer.Response.json("{\"ok\":true}");
        }
        if ("/navigate".equals(r.path)) {
            final String url = r.q("url", r.body.trim());
            if (url.isEmpty()) return DebugServer.Response.text(400, "url required\n");
            runOnUiThread(() -> { log("debug API navigate " + url); if (web != null) web.loadUrl(url); });
            return DebugServer.Response.json("{\"ok\":true}");
        }
        if ("/hub".equals(r.path)) {
            final String n = normalizeHubUrl(r.q("url", r.body.trim()));
            if (n == null) return DebugServer.Response.text(400, "valid http(s) url required\n");
            runOnUiThread(() -> {
                if (settingsDialog != null && settingsDialog.isShowing()) settingsDialog.dismiss();
                saveHubUrl(n);
            });
            return DebugServer.Response.json(new JSONObject().put("ok", true).put("hub", n).toString());
        }
        final String code = r.body;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> res = new AtomicReference<>();
        runOnUiThread(() -> {
            if (web == null) { res.set("null"); done.countDown(); return; }
            web.evaluateJavascript(code, v -> { res.set(v); done.countDown(); });
        });
        if (!done.await(Long.parseLong(r.q("timeout", "10")), TimeUnit.SECONDS)) {
            return DebugServer.Response.text(504, "eval timed out (renderer busy or hung?)\n");
        }
        return DebugServer.Response.json(res.get() == null ? "null" : res.get());
    }

    private DebugServer.Response screenshot(float scale) throws Exception {
        final View dv = getWindow().getDecorView();
        final int w = dv.getWidth(), ht = dv.getHeight();
        if (w <= 0 || ht <= 0) return DebugServer.Response.text(503, "window not laid out\n");
        final Bitmap full = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888);
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = {-1};
        h.post(() -> PixelCopy.request(getWindow(), full, rc -> { result[0] = rc; done.countDown(); }, h));
        if (!done.await(10, TimeUnit.SECONDS)) return DebugServer.Response.text(504, "screenshot timed out\n");
        if (result[0] != PixelCopy.SUCCESS) return DebugServer.Response.text(500, "PixelCopy failed: " + result[0] + "\n");
        scale = Math.max(0.1f, Math.min(1f, scale));
        Bitmap out = scale < 1f ? Bitmap.createScaledBitmap(full, (int) (w * scale), (int) (ht * scale), true) : full;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 80, bos);
        return new DebugServer.Response(200, "image/jpeg", bos.toByteArray());
    }

    private static String logcat(int n) {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(Math.max(1, n)))
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "logcat failed: " + e + "\n";
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        DebugLog.w("onTrimMemory level=" + level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DebugLog.w("onLowMemory");
    }

    @Override
    protected void onStop() {
        log("onStop");
        super.onStop();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String webViewVersion() {
        PackageInfo p = WebView.getCurrentWebViewPackage();
        return p == null ? "?" : p.versionName;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) exitFullscreen();
        else if (web != null && web.canGoBack()) web.goBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("onResume");
        if (web != null) { web.onResume(); web.resumeTimers(); }
    }

    @Override
    protected void onPause() {
        // Keep the hub running even if Connect briefly overlays something.
        log("onPause");
        stopTalk("pause");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        log("onDestroy");
        h.removeCallbacksAndMessages(null);
        if (debugServer != null) debugServer.shutdown();
        destroyWebView();
        super.onDestroy();
    }
}
