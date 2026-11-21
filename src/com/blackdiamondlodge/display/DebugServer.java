package com.blackdiamondlodge.display;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.0 server for remote diagnostics (the displays have no adb).
 * Only private-network clients are served; {@link Request#authorized()} checks the
 * X-Debug-Token header against a SHA-256 baked in at build time.
 */
final class DebugServer extends Thread {
    static final int PORT = 8787;
    private static final int MAX_BODY = 1 << 20;

    static final class Request {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        String q(String k, String def) { String v = query.get(k); return v == null || v.isEmpty() ? def : v; }

        boolean authorized() {
            String tok = headers.get("x-debug-token");
            if (tok == null) tok = query.get("token");
            if (tok == null || BuildInfo.DEBUG_TOKEN_SHA256.isEmpty()) return false;
            return MessageDigest.isEqual(sha256(tok).getBytes(StandardCharsets.US_ASCII),
                    BuildInfo.DEBUG_TOKEN_SHA256.getBytes(StandardCharsets.US_ASCII));
        }
    }

    static final class Response {
        final int status;
        final String type;
        final byte[] body;
        Response(int status, String type, byte[] body) { this.status = status; this.type = type; this.body = body; }
        static Response text(int status, String s) {
            return new Response(status, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        }
        static Response json(String s) {
            return new Response(200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        }
    }

    interface Router { Response handle(Request r) throws Exception; }

    private final Router router;
    private volatile ServerSocket ss;

    DebugServer(Router router) {
        super("bdh-debug-http");
        this.router = router;
        setDaemon(true);
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try (ServerSocket s = new ServerSocket()) {
                ss = s;
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                DebugLog.i("debug server listening on :" + PORT);
                while (!isInterrupted()) {
                    final Socket c = s.accept();
                    Thread t = new Thread(() -> serve(c), "bdh-debug-conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (isInterrupted()) return;
                DebugLog.w("debug server error: " + e + ", restarting in 10s");
                try { Thread.sleep(10_000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    void shutdown() {
        interrupt();
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    private void serve(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(30_000);
            InetAddress peer = sock.getInetAddress();
            Response resp;
            Request req = null;
            if (!isPrivate(peer)) {
                resp = Response.text(403, "forbidden\n");
            } else {
                try {
                    req = parse(new BufferedInputStream(sock.getInputStream()));
                    resp = req == null ? Response.text(400, "bad request\n") : router.handle(req);
                } catch (Throwable t) {
                    resp = Response.text(500, "error: " + t + "\n");
                }
            }
            if (req != null && !"/logs".equals(req.path) && !"/status".equals(req.path)) {
                DebugLog.i("debug " + req.method + " " + req.path + " from " + peer.getHostAddress() + " -> " + resp.status);
            }
            OutputStream o = sock.getOutputStream();
            String head = "HTTP/1.0 " + resp.status + " " + reason(resp.status) + "\r\n"
                    + "Content-Type: " + resp.type + "\r\n"
                    + "Content-Length: " + resp.body.length + "\r\n"
                    + "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
            o.write(head.getBytes(StandardCharsets.US_ASCII));
            o.write(resp.body);
            o.flush();
        } catch (IOException ignored) { }
    }

    private static Request parse(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        String[] p = line.split(" ");
        if (p.length < 2) return null;
        Request r = new Request();
        r.method = p[0].toUpperCase(Locale.US);
        String target = p[1];
        int qi = target.indexOf('?');
        r.path = qi < 0 ? target : target.substring(0, qi);
        if (qi >= 0) {
            for (String kv : target.substring(qi + 1).split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                r.query.put(k, v);
            }
        }
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        int len = 0;
        try { len = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
        if (len > MAX_BODY) throw new IOException("body too large");
        if (len > 0) {
            byte[] b = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(b, off, len - off);
                if (n < 0) break;
                off += n;
            }
            r.body = new String(b, 0, off, StandardCharsets.UTF_8);
        }
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16_384) throw new IOException("line too long");
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet4Address) return false;
        byte[] b = a.getAddress();
        // IPv4-mapped IPv6 (::ffff:a.b.c.d)
        if (b.length == 16 && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
            try {
                return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 504: return "Gateway Timeout";
            default: return s >= 500 ? "Server Error" : "OK";
        }
    }
}
