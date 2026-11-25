package com.blackdiamondlodge.display;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class TimeZoneCheckTest {
    private static int passed;

    public static void main(String[] args) {
        // Sun Peaks: hub says UTC-7, the WebView's old rules said UTC-8.
        eq("BC permanent UTC-7 vs stale rules", TimeZoneCheck.verdict(-420, -480), "MISMATCH");
        eq("in step", TimeZoneCheck.verdict(-420, -420), "ok");
        eq("no hub answer", TimeZoneCheck.verdict(null, -480), "unknown");
        eq("no page answer", TimeZoneCheck.verdict(-420, null), "unknown");
        eq("boxed equality, not identity", TimeZoneCheck.verdict(Integer.valueOf(-420), Integer.valueOf(-420)), "ok");
        eq("parse", TimeZoneCheck.parseHubOffset("{\"offset\": -420, \"build\": \"c02ad27c6d41\"}"), -420);
        eq("parse last field", TimeZoneCheck.parseHubOffset("{\"build\":\"x\",\"offset\":60}"), 60);
        eq("parse garbage", TimeZoneCheck.parseHubOffset("<html>"), null);
        eq("parse null", TimeZoneCheck.parseHubOffset(null), null);
        eq("clock url", TimeZoneCheck.clockUrl("http://192.168.10.216:8000/"), "http://192.168.10.216:8000/hub/api/clock/");
        eq("clock url with path", TimeZoneCheck.clockUrl("https://hub.example:8443/apps/x/"), "https://hub.example:8443/hub/api/clock/");
        System.out.println("TimeZoneCheckTest: " + passed + " checks passed");
    }

    private static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) {
            System.err.println("FAIL " + what + ": got " + got + ", want " + want);
            System.exit(1);
        }
        passed++;
    }
}
