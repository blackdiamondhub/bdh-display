package com.blackdiamondlodge.display;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class TimeZoneFixTest {
    private static int passed;

    public static void main(String[] args) {
        // Sun Peaks: hub says UTC-7, the WebView's old rules say UTC-8.
        eq("BC permanent UTC-7", TimeZoneFix.needed(-420, -480), "Etc/GMT+7");
        eq("in step: leave it alone", TimeZoneFix.needed(-480, -480), null);
        eq("no hub answer: leave it alone", TimeZoneFix.needed(null, -480), null);
        eq("no page answer: leave it alone", TimeZoneFix.needed(-420, null), null);
        eq("east of UTC", TimeZoneFix.zoneFor(120), "Etc/GMT-2");
        eq("UTC", TimeZoneFix.zoneFor(0), "Etc/UTC");
        eq("half-hour zones have no Etc id", TimeZoneFix.zoneFor(330), null);
        eq("out of range", TimeZoneFix.zoneFor(-15 * 60), null);
        // Real zone check: the id must exist and have the offset it claims.
        for (int h = -12; h <= 14; h++) {
            String id = TimeZoneFix.zoneFor(h * 60);
            eq("offset of " + id, java.util.TimeZone.getTimeZone(id).getRawOffset() / 60000, h * 60);
        }
        eq("parse", TimeZoneFix.parseHubOffset("{\"offset\": -420, \"build\": \"c02ad27c6d41\"}"), -420);
        eq("parse last field", TimeZoneFix.parseHubOffset("{\"build\":\"x\",\"offset\":60}"), 60);
        eq("parse garbage", TimeZoneFix.parseHubOffset("<html>"), null);
        eq("parse null", TimeZoneFix.parseHubOffset(null), null);
        eq("clock url", TimeZoneFix.clockUrl("http://192.168.10.216:8000/"), "http://192.168.10.216:8000/hub/api/clock/");
        eq("clock url with path", TimeZoneFix.clockUrl("https://hub.example:8443/apps/x/"), "https://hub.example:8443/hub/api/clock/");
        System.out.println("TimeZoneFixTest: " + passed + " checks passed");
    }

    private static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) {
            System.err.println("FAIL " + what + ": got " + got + ", want " + want);
            System.exit(1);
        }
        passed++;
    }
}
