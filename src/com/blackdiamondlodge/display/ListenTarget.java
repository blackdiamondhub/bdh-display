package com.blackdiamondlodge.display;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the kiosk streams its mic for wake-word detection: the voice app's
 * listen WebSocket. Pure Java (no Android APIs) so it runs in the JVM tests.
 */
final class ListenTarget {
    static final String APP = "voice";
    static final String PATH = "/voice/api/listen";

    final String host;
    final int port;
    final String path;

    ListenTarget(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path;
    }

    /** {@code http://hub:8000/...} -> {@code http://hub:8000/display/api/kiosk/}. */
    static String kioskUrl(String hubUrl) {
        try {
            URI u = URI.create(hubUrl);
            String port = u.getPort() < 0 ? "" : ":" + u.getPort();
            return u.getScheme() + "://" + u.getHost() + port + "/display/api/kiosk/";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The voice app's origin from {@code /display/api/kiosk/}, e.g.
     * {@code {"apps": {"voice": "http://192.168.10.216:9110"}}}; null when the
     * app isn't installed or the reply is unreadable.
     */
    static String voiceOrigin(String json) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + APP + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Listen target for an app origin (plain http only), or null. */
    static ListenTarget of(String origin, String displayIp, String version) {
        if (origin == null) return null;
        try {
            URI u = URI.create(origin);
            if (!"http".equals(u.getScheme()) || u.getHost() == null) return null;
            int port = u.getPort() < 0 ? 80 : u.getPort();
            String q = "?display=" + enc(displayIp == null ? "" : displayIp) + "&v=" + enc(version == null ? "" : version);
            return new ListenTarget(u.getHost(), port, PATH + q);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reconnect delay after {@code failures} consecutive failures: 2 s doubling, capped at 60 s. */
    static long backoffMs(int failures) {
        if (failures <= 0) return 2_000;
        return Math.min(60_000, 2_000L << Math.min(failures, 5));
    }

    /** Value of a string field in a flat server message, e.g. {@code type}. */
    static String field(String json, String name) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Value of a boolean field, or null when absent. */
    static Boolean bool(String json, String name) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    /** Value of a numeric field, or null when absent. */
    static Double num(String json, String name) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)").matcher(json);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    private static String enc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_') b.append(c);
            else b.append('%').append(String.format("%02X", (int) c & 0xff));
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return "ws://" + host + ":" + port + path;
    }
}
