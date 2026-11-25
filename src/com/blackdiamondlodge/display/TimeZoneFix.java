package com.blackdiamondlodge.display;

/**
 * Keeps the display's clock in step with the hub's time zone rules.
 *
 * The WebView bundles its own (old) time zone data: after BC moved to permanent
 * UTC-7 it still applied Pacific standard time, so every page and app read an
 * hour slow. The hub's zoneinfo is current, so the display compares the hub's
 * UTC offset (hub/api/clock/) with what JavaScript reports and, when they
 * differ, switches the system time zone to a fixed-offset zone the old data
 * can't get wrong. Pages keep using plain Date. Pure Java for unit tests.
 */
final class TimeZoneFix {
    private TimeZoneFix() {}

    /** Fixed-offset zone id for minutes east of UTC, or null if no Etc zone has it. */
    static String zoneFor(int offsetMinutes) {
        if (offsetMinutes % 60 != 0) return null;
        int h = offsetMinutes / 60;
        if (h < -12 || h > 14) return null;
        if (h == 0) return "Etc/UTC";
        // POSIX sign: Etc/GMT+7 is seven hours *behind* UTC.
        return "Etc/GMT" + (h < 0 ? "+" : "-") + Math.abs(h);
    }

    /** The "offset" field of the hub's clock JSON, or null if absent or not a whole number. */
    static Integer parseHubOffset(String json) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"offset\"\\s*:\\s*(-?\\d+)\\s*[,}]").matcher(json);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Zone to switch to, or null when the page already shows the hub's time (or can't be fixed). */
    static String needed(Integer hubOffset, Integer pageOffset) {
        if (hubOffset == null || pageOffset == null || hubOffset.equals(pageOffset)) return null;
        return zoneFor(hubOffset);
    }

    /** Hub clock endpoint for a normalized hub URL ("http://host:8000/"). */
    static String clockUrl(String hubUrl) {
        java.net.URI u = java.net.URI.create(hubUrl);
        return u.getScheme() + "://" + u.getRawAuthority() + "/hub/api/clock/";
    }
}
