package com.blackdiamondlodge.display;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds hubs announcing themselves over mDNS as _bdh-hub._tcp ("Black Diamond Hub"). Callbacks run on the main thread. */
final class HubDiscovery {
    static final String TYPE = "_bdh-hub._tcp";

    static final class Hub {
        final String service, name, location, url;

        Hub(String service, String name, String location, String url) {
            this.service = service; this.name = name; this.location = location; this.url = url;
        }

        String label() {
            String n = name.isEmpty() ? service : name;
            return location.isEmpty() ? n : n + " \u2014 " + location;
        }
    }

    interface Listener { void onHubs(List<Hub> hubs); }

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private final LinkedHashMap<String, Hub> hubs = new LinkedHashMap<>();
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, stopped;

    HubDiscovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    void start() {
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) { DebugLog.i("mdns: browsing " + t); }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int code) { DebugLog.w("mdns: browse failed " + code); }
            @Override public void onStopDiscoveryFailed(String t, int code) { }
            @Override public void onServiceFound(NsdServiceInfo s) { main.post(() -> { toResolve.add(s); resolveNext(); }); }
            @Override public void onServiceLost(NsdServiceInfo s) {
                main.post(() -> { if (hubs.remove(s.getServiceName()) != null) publish(); });
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            DebugLog.w("mdns: " + e);
            discovery = null;
        }
    }

    void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
            discovery = null;
        }
    }

    // NsdManager on Android 9 resolves one service at a time (FAILURE_ALREADY_ACTIVE otherwise).
    private void resolveNext() {
        if (stopped || resolving || toResolve.isEmpty()) return;
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo s, int code) {
                main.post(() -> {
                    resolving = false;
                    if (code == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed(() -> { toResolve.add(s); resolveNext(); }, 500);
                    else DebugLog.w("mdns: resolve " + s.getServiceName() + " failed " + code);
                    resolveNext();
                });
            }
            @Override public void onServiceResolved(NsdServiceInfo s) {
                main.post(() -> {
                    resolving = false;
                    Hub hub = toHub(s);
                    if (hub != null && !stopped) {
                        DebugLog.i("mdns: found " + hub.label() + " at " + hub.url);
                        hubs.put(hub.service, hub);
                        publish();
                    }
                    resolveNext();
                });
            }
        });
    }

    private void publish() {
        if (!stopped) listener.onHubs(new ArrayList<>(hubs.values()));
    }

    private static Hub toHub(NsdServiceInfo s) {
        InetAddress a = s.getHost();
        if (a == null || s.getPort() <= 0) return null;
        Map<String, byte[]> txt = s.getAttributes();
        String path = txt(txt, "path");
        if (!path.startsWith("/")) path = "/" + path;
        String host = a instanceof Inet4Address ? a.getHostAddress() : "[" + a.getHostAddress() + "]";
        return new Hub(s.getServiceName(), txt(txt, "name"), txt(txt, "location"),
                "http://" + host + ":" + s.getPort() + path);
    }

    private static String txt(Map<String, byte[]> txt, String key) {
        byte[] v = txt == null ? null : txt.get(key);
        return v == null ? "" : new String(v, StandardCharsets.UTF_8).trim();
    }
}
