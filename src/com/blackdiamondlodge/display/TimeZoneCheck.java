package com.blackdiamondlodge.display;

/**
 * Detects a display clock that disagrees with the hub's time zone rules.
 *
 * The WebView bundles its own time zone data. When BC moved to permanent UTC-7
 * that data still applied Pacific standard time, so every page read an hour slow.
 * A kiosk APK can't change the system zone (SET_TIME_ZONE is privileged), so the
 * fix lives on the UniFi console; this only compares the hub's UTC offset
 * (hub/api/clock/) with what JavaScript reports and surfaces it in /status.
 * Pure Java for unit tests.
 */
final class TimeZoneCheck {
    private TimeZoneCheck() {}

    /** The "offset" field of the hub's clock JSON, or null if absent or not a whole number. */
    static Integer parseHubOffset(String json) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"offset\"\\s*:\\s*(-?\\d+)\\s*[,}]").matcher(json);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** "ok", "MISMATCH", or "unknown" when either side couldn't be read. */
    static String verdict(Integer hubOffset, Integer pageOffset) {
        if (hubOffset == null || pageOffset == null) return "unknown";
        return hubOffset.equals(pageOffset) ? "ok" : "MISMATCH";
    }

    /** Hub clock endpoint for a normalized hub URL ("http://host:8000/"). */
    static String clockUrl(String hubUrl) {
        java.net.URI u = java.net.URI.create(hubUrl);
        return u.getScheme() + "://" + u.getRawAuthority() + "/hub/api/clock/";
    }
}
