package com.blackdiamondlodge.display;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Streams the display's microphone, nonstop, to the voice app's listen
 * WebSocket for wake-word detection on the hub: binary frames of 20 ms
 * 16 kHz s16le mono, like {@link TalkSession}. The server's text frames
 * (JSON: hello, wake, config and the assistant's messages) go to
 * {@link Listener#onMessage}; its binary frames (assistant reply audio,
 * 24 kHz s16le mono) to {@link Listener#onAudio}. Both are called on the
 * reader thread, in order.
 */
final class ListenSession {
    interface Listener {
        void onState(ListenSession s, String state, String detail);
        void onMessage(ListenSession s, String json);
        void onAudio(ListenSession s, byte[] pcm);
        void onLevel(ListenSession s, double dbfs);
    }

    private static final long LEVEL_MS = 1000;

    final ListenTarget target;
    final long startedAt = SystemClock.elapsedRealtime();
    /** What the app has sent and when, for the owner's dead-socket check. */
    final ListenLiveness liveness = new ListenLiveness();
    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean stopped;
    private volatile String abortReason;
    private volatile Socket sock;
    private volatile AudioRecord rec;
    private final Object sendLock = new Object();

    ListenSession(ListenTarget target, Listener listener) {
        this.target = target;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(this::run, "listen");
        t.setDaemon(true);
        t.start();
    }

    /** Ends the session from any thread; no further callbacks are sent. */
    void stop() {
        if (stopped) return;
        stopped = true;
        closeQuietly();
    }

    boolean stopped() {
        return stopped;
    }

    /**
     * Drops a socket that looks dead; the session then ends with state
     * "error" and {@code why} like any other failure, so the owner retries.
     * Doesn't block (no close frame: the socket may be wedged mid-write) and
     * only acts once; false if the session already ended or was aborted.
     */
    boolean abort(String why) {
        if (stopped || abortReason != null) return false;
        abortReason = why;
        releaseMic();
        Socket s = sock;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
        return true;
    }

    private void run() {
        String end = "closed", detail = "";
        try {
            connect();
            openMic();
            if (stopped) return;
            listener.onState(this, "listening", "");
            pump();
        } catch (Exception e) {
            if (stopped) return;
            end = "error";
            String why = abortReason;
            detail = why != null ? why : e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            boolean wasStopped = stopped;
            stopped = true;
            closeQuietly();
            if (!wasStopped) listener.onState(this, end, detail);
        }
    }

    private void connect() throws IOException {
        Socket s = new Socket();
        sock = s;
        s.connect(new InetSocketAddress(target.host, target.port), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(5000);
        byte[] nonce = new byte[16];
        rnd.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String req = "GET " + target.path + " HTTP/1.1\r\n"
                + "Host: " + target.host + ":" + target.port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String head = readHead(s.getInputStream());
        String status = head.split("\r\n", 2)[0];
        if (!status.contains(" 101 ")) throw new IOException("upgrade refused: " + status);
        if (!head.toLowerCase().contains(("sec-websocket-accept: " + TalkSession.accept(key)).toLowerCase())) {
            throw new IOException("bad Sec-WebSocket-Accept");
        }
        s.setSoTimeout(0);
        liveness.opened(SystemClock.elapsedRealtime());
        Thread r = new Thread(this::readLoop, "listen-rd");
        r.setDaemon(true);
        r.start();
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 8192) {
            int c = in.read();
            if (c < 0) throw new IOException("closed during upgrade");
            sb.append((char) c);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) return sb.toString();
        }
        throw new IOException("upgrade reply too long");
    }

    /** Server frames: text messages to the listener, answer pings, end on close/EOF. */
    private void readLoop() {
        try {
            InputStream in = sock.getInputStream();
            while (!stopped) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                if (len > 65536) break;
                byte[] p = new byte[(int) len];
                for (int n = 0; n < len; ) {
                    int k = in.read(p, n, (int) len - n);
                    if (k < 0) throw new IOException("eof");
                    n += k;
                }
                int op = b0 & 0x0f;
                if (op == 0x8) break;
                // Websocket pings come from the hub's proxy, not the app: they don't count as alive.
                if (op == 0x9) send(0xA, p, p.length);
                else if (op == 0x1 && !stopped) {
                    String json = new String(p, StandardCharsets.UTF_8);
                    liveness.message(SystemClock.elapsedRealtime(), json);
                    listener.onMessage(this, json);
                } else if (op == 0x2 && !stopped) {
                    liveness.frame(SystemClock.elapsedRealtime());
                    listener.onAudio(this, p);
                }
            }
        } catch (IOException ignored) {
        }
        if (!stopped) closeQuietly();
    }

    /**
     * Mic sources in order of preference. On UniFi Connect displays
     * VOICE_RECOGNITION yields all-zero samples, while VOICE_COMMUNICATION
     * (the doorbell talk-back source) carries real audio.
     */
    private static final int[] SOURCES = {MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION};
    private static final String[] SOURCE_NAMES = {"voice_communication", "mic", "voice_recognition"};
    /** Frames (20 ms) of exact digital silence before trying the next source: 3 s. */
    private static final int DEAD_FRAMES = 150;

    private volatile int sourceIdx;
    private volatile boolean aecOn;

    /** Whether the platform echo canceller is on for the mic. */
    boolean aec() {
        return aecOn;
    }

    /** Sends a JSON text message, e.g. {@code {"type":"end"}}; false if the socket is gone. */
    boolean sendText(String json) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            send(0x1, b, b.length);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String source() {
        return SOURCE_NAMES[sourceIdx];
    }

    private void openMic() throws IOException {
        int min = AudioRecord.getMinBufferSize(TalkSession.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(SOURCES[sourceIdx], TalkSession.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, TalkSession.FRAME_BYTES * 16));
        rec = r;
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("mic unavailable");
        if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            android.media.audiofx.AutomaticGainControl agc =
                    android.media.audiofx.AutomaticGainControl.create(r.getAudioSessionId());
            if (agc != null) try { agc.setEnabled(true); } catch (Exception ignored) { }
        }
        // Echo cancellation against the assistant's voice-communication playback (barge-in).
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            android.media.audiofx.AcousticEchoCanceler aec =
                    android.media.audiofx.AcousticEchoCanceler.create(r.getAudioSessionId());
            if (aec != null) try { aec.setEnabled(true); aecOn = aec.getEnabled(); } catch (Exception ignored) { }
        }
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("mic busy");
    }

    private void releaseMic() {
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
            r.release();
        }
    }

    private static boolean allZero(byte[] b, int n) {
        for (int i = 0; i < n; i++) if (b[i] != 0) return false;
        return true;
    }

    private void pump() throws IOException {
        byte[] buf = new byte[TalkSession.FRAME_BYTES];
        long lastLevel = 0;
        int deadFrames = 0;
        boolean live = false;
        while (!stopped) {
            int n = 0;
            while (n < buf.length) {
                AudioRecord r = rec;
                if (r == null) throw new IOException("mic closed");
                int k = r.read(buf, n, buf.length - n);
                if (k <= 0) throw new IOException("mic read " + k);
                n += k;
            }
            send(0x2, buf, n);
            if (!live) {
                if (!allZero(buf, n)) {
                    live = true;
                } else if (++deadFrames >= DEAD_FRAMES && sourceIdx + 1 < SOURCES.length) {
                    String dead = source();
                    releaseMic();
                    sourceIdx++;
                    deadFrames = 0;
                    listener.onState(this, "listening", "source " + dead + " silent, trying " + source());
                    openMic();
                    if (stopped) releaseMic();
                    continue;
                }
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastLevel >= LEVEL_MS) {
                lastLevel = now;
                listener.onLevel(this, TalkSession.dbfs(buf, n));
            }
        }
    }

    private void send(int opcode, byte[] payload, int len) throws IOException {
        byte[] f = TalkSession.frame(opcode, payload, len, rnd);
        Socket s = sock;
        if (s == null) throw new IOException("closed");
        synchronized (sendLock) {
            s.getOutputStream().write(f);
        }
    }

    private void closeQuietly() {
        releaseMic();
        Socket s = sock;
        if (s != null) {
            try { send(0x8, new byte[]{0x03, (byte) 0xe8}, 2); } catch (Exception ignored) { }
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
