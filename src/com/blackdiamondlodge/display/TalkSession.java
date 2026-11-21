package com.blackdiamondlodge.display;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.SystemClock;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Streams the display's microphone to go2rtc's /api/talk WebSocket
 * (go2rtc-nack talkback patch): binary frames of 20 ms 16 kHz s16le mono.
 */
final class TalkSession {
    interface Listener {
        void onState(TalkSession s, String state, String detail);
        void onLevel(TalkSession s, double dbfs);
    }

    static final int RATE = 16000;
    static final int FRAME_BYTES = 640;
    static final long MAX_MS = 5 * 60_000;
    static final long SILENCE_MS = 60_000;
    static final double SILENCE_DBFS = -50;
    private static final long LEVEL_MS = 100;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    final String id;
    private final String host, stream;
    private final int port;
    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean stopped;
    private volatile Socket sock;
    private volatile AudioRecord rec;
    private final Object sendLock = new Object();

    TalkSession(String id, String host, String stream, int port, Listener listener) {
        this.id = id;
        this.host = host;
        this.stream = stream;
        this.port = port;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(this::run, "talk-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** Ends the session from any thread; no further callbacks are sent. */
    void stop() {
        if (stopped) return;
        stopped = true;
        closeQuietly();
    }

    private void run() {
        String end = "stopped", detail = "";
        try {
            connect();
            openMic();
            if (stopped) return;
            listener.onState(this, "talking", "");
            detail = pump();
        } catch (Exception e) {
            if (stopped) return;
            end = "error";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
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
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(5000);
        byte[] nonce = new byte[16];
        rnd.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String req = "GET /api/talk?src=" + stream + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String head = readHead(s.getInputStream());
        String status = head.split("\r\n", 2)[0];
        if (!status.contains(" 101 ")) throw new IOException("upgrade refused: " + status);
        if (!head.toLowerCase().contains(("sec-websocket-accept: " + accept(key)).toLowerCase())) {
            throw new IOException("bad Sec-WebSocket-Accept");
        }
        s.setSoTimeout(0);
        Thread r = new Thread(this::readLoop, "talk-rd-" + id);
        r.setDaemon(true);
        r.start();
    }

    static String accept(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /** Server frames: answer pings, end on close/EOF. go2rtc sends nothing else. */
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
                if (op == 0x9) send(0xA, p, p.length);
            }
        } catch (IOException ignored) {
        }
        if (!stopped) closeQuietly();
    }

    private void openMic() throws IOException {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 8));
        rec = r;
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("mic unavailable");
        int sid = r.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) enable(AcousticEchoCanceler.create(sid));
        if (NoiseSuppressor.isAvailable()) enable(NoiseSuppressor.create(sid));
        if (AutomaticGainControl.isAvailable()) enable(AutomaticGainControl.create(sid));
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("mic busy");
    }

    private static void enable(android.media.audiofx.AudioEffect fx) {
        if (fx != null) try { fx.setEnabled(true); } catch (Exception ignored) { }
    }

    /** Mic -> socket until stopped, the socket drops, or a cap is hit. Returns the end reason. */
    private String pump() throws IOException {
        byte[] buf = new byte[FRAME_BYTES];
        long start = SystemClock.elapsedRealtime(), lastLoud = start, lastLevel = 0;
        while (!stopped) {
            int n = 0;
            while (n < buf.length) {
                int k = rec.read(buf, n, buf.length - n);
                if (k <= 0) throw new IOException("mic read " + k);
                n += k;
            }
            send(0x2, buf, n);
            long now = SystemClock.elapsedRealtime();
            double db = dbfs(buf, n);
            if (db > SILENCE_DBFS) lastLoud = now;
            if (now - lastLevel >= LEVEL_MS) {
                lastLevel = now;
                listener.onLevel(this, db);
            }
            if (now - start >= MAX_MS) return "limit";
            if (now - lastLoud >= SILENCE_MS) return "silence";
        }
        return "";
    }

    static double dbfs(byte[] b, int n) {
        double sum = 0;
        int samples = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (double) v * v;
        }
        if (samples == 0 || sum == 0) return -90;
        return Math.max(-90, 20 * Math.log10(Math.sqrt(sum / samples) / 32768.0));
    }

    /** Writes one masked client frame (RFC 6455 5.3). */
    private void send(int opcode, byte[] payload, int len) throws IOException {
        byte[] f = frame(opcode, payload, len, rnd);
        Socket s = sock;
        if (s == null) throw new IOException("closed");
        synchronized (sendLock) {
            s.getOutputStream().write(f);
        }
    }

    static byte[] frame(int opcode, byte[] payload, int len, java.util.Random rnd) {
        int hdr = len < 126 ? 2 : 4;
        byte[] f = new byte[hdr + 4 + len];
        f[0] = (byte) (0x80 | opcode);
        if (len < 126) {
            f[1] = (byte) (0x80 | len);
        } else {
            f[1] = (byte) (0x80 | 126);
            f[2] = (byte) (len >> 8);
            f[3] = (byte) len;
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        System.arraycopy(mask, 0, f, hdr, 4);
        for (int i = 0; i < len; i++) f[hdr + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        return f;
    }

    private void closeQuietly() {
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) { }
            r.release();
        }
        Socket s = sock;
        if (s != null) {
            try { send(0x8, new byte[]{0x03, (byte) 0xe8}, 2); } catch (Exception ignored) { }
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
