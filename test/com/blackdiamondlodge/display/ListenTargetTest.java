package com.blackdiamondlodge.display;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class ListenTargetTest {
    private static int passed;

    public static void main(String[] args) {
        eq("kiosk url", ListenTarget.kioskUrl("http://192.168.10.216:8000/"), "http://192.168.10.216:8000/display/api/kiosk/");
        eq("kiosk url drops path", ListenTarget.kioskUrl("http://hub.bdl:8000/display/?x=1"), "http://hub.bdl:8000/display/api/kiosk/");
        eq("kiosk url no port", ListenTarget.kioskUrl("http://hub/"), "http://hub/display/api/kiosk/");

        String reply = "{\"apps\": {\"cams\": \"http://192.168.10.216:9101\", \"voice\": \"http://192.168.10.216:9110\"}}";
        eq("voice origin", ListenTarget.voiceOrigin(reply), "http://192.168.10.216:9110");
        eq("no voice app (MI hub)", ListenTarget.voiceOrigin("{\"apps\": {\"cams\": \"http://h:9101\"}}"), null);
        eq("empty", ListenTarget.voiceOrigin("{\"apps\": {}}"), null);
        eq("garbage", ListenTarget.voiceOrigin("<html>"), null);
        eq("null", ListenTarget.voiceOrigin(null), null);

        ListenTarget t = ListenTarget.of("http://192.168.10.216:9110", "192.168.10.195", "1.11");
        eq("host", t.host, "192.168.10.216");
        eq("port", t.port, 9110);
        eq("path", t.path, "/voice/api/listen?display=192.168.10.195&v=1.11");
        eq("ws url", t.toString(), "ws://192.168.10.216:9110/voice/api/listen?display=192.168.10.195&v=1.11");
        eq("query escaped", ListenTarget.of("http://h:1", "a b&c", null).path, "/voice/api/listen?display=a%20b%26c&v=");
        eq("default port", ListenTarget.of("http://h", "x", "1").port, 80);
        eq("https refused (plain socket only)", ListenTarget.of("https://h:443", "x", "1"), null);
        eq("null origin", ListenTarget.of(null, "x", "1"), null);

        eq("backoff first", ListenTarget.backoffMs(0), 2_000L);
        eq("backoff doubles", ListenTarget.backoffMs(1), 4_000L);
        eq("backoff 3", ListenTarget.backoffMs(3), 16_000L);
        eq("backoff capped", ListenTarget.backoffMs(5), 60_000L);
        eq("backoff stays capped", ListenTarget.backoffMs(500), 60_000L);

        String wake = "{\"type\": \"wake\", \"word\": \"hey_jarvis\", \"score\": 0.91, \"at\": \"2026-11-26T10:00:00Z\"}";
        eq("type", ListenTarget.field(wake, "type"), "wake");
        eq("word", ListenTarget.field(wake, "word"), "hey_jarvis");
        eq("score", ListenTarget.num(wake, "score"), 0.91);
        eq("config off", ListenTarget.bool("{\"type\":\"config\",\"listen\":false}", "listen"), Boolean.FALSE);
        eq("config absent", ListenTarget.bool("{\"type\":\"hello\"}", "listen"), null);
        eq("threshold", ListenTarget.num("{\"type\":\"hello\",\"words\":[\"hey_jarvis\"],\"threshold\":0.5}", "threshold"), 0.5);
        System.out.println("ListenTargetTest: " + passed + " checks passed");
    }

    private static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) {
            System.err.println("FAIL " + what + ": got " + got + ", want " + want);
            System.exit(1);
        }
        passed++;
    }
}
