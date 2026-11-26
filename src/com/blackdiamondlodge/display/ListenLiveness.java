package com.blackdiamondlodge.display;

/**
 * Whether a listen socket still reaches the voice app. Websocket pings only
 * reach the hub's proxy, which kept the kiosk's socket open after the app
 * behind it went away (BlackDiamondHub before #399); so liveness is judged
 * on the app's own messages:
 * <ul>
 * <li>its {@code hello} must arrive within {@link #HELLO_TIMEOUT_MS} of the socket opening;</li>
 * <li>once the app has shown it sends keepalives (a {@code "ping": N} in hello,
 * voice 0.2.0b5+, or a {@code ping} message) it must not go quiet for more
 * than 3 intervals (at least {@link #MIN_IDLE_MS}). Older apps never ping and
 * are only held to the hello rule.</li>
 * </ul>
 * Times are a monotonic clock in ms passed in by the caller. Pure Java (no
 * Android APIs) so it runs in the JVM tests; safe to use from several threads.
 */
final class ListenLiveness {
    static final long HELLO_TIMEOUT_MS = 15_000;
    static final long MIN_IDLE_MS = 45_000;
    static final long DEFAULT_PING_MS = 15_000;
    static final int MISSED_PINGS = 3;

    private long openedAt = -1, helloAt = -1, lastRx = -1;
    private long pingMs;
    private int messages;

    /** The websocket upgrade succeeded. */
    synchronized void opened(long now) {
        openedAt = now;
        lastRx = now;
    }

    /** A binary frame from the app (assistant reply audio). */
    synchronized void frame(long now) {
        lastRx = now;
    }

    /** A text message from the app. */
    synchronized void message(long now, String json) {
        lastRx = now;
        messages++;
        String type = ListenTarget.field(json, "type");
        if ("hello".equals(type)) {
            if (helloAt < 0) helloAt = now;
            Double ping = ListenTarget.num(json, "ping");
            if (ping != null && ping > 0) pingMs = Math.max(1_000, (long) (ping * 1000));
        } else if ("ping".equals(type) && pingMs == 0) {
            pingMs = DEFAULT_PING_MS;
        }
    }

    /** How long the app may stay silent before the socket counts as dead; 0 = no limit. */
    synchronized long idleLimitMs() {
        return pingMs == 0 ? 0 : Math.max(MIN_IDLE_MS, MISSED_PINGS * pingMs);
    }

    /** Why the socket should be dropped, or null while it looks alive. */
    synchronized String stale(long now) {
        if (openedAt < 0) return null;
        if (helloAt < 0) {
            return now - openedAt >= HELLO_TIMEOUT_MS ? "no hello in " + (now - openedAt) / 1000 + "s" : null;
        }
        long limit = idleLimitMs();
        if (limit > 0 && now - lastRx >= limit) return "voice app silent for " + (now - lastRx) / 1000 + "s";
        return null;
    }

    /** Whether the app greeted us and the socket lived {@code healthyMs}: a good connection. */
    synchronized boolean healthy(long now, long healthyMs) {
        return helloAt >= 0 && now - openedAt >= healthyMs;
    }

    synchronized boolean helloSeen() {
        return helloAt >= 0;
    }

    /** Ms since the app's last message or frame (since opening before any), or -1 if not open. */
    synchronized long sinceRxMs(long now) {
        return lastRx < 0 ? -1 : now - lastRx;
    }

    synchronized long pingMs() {
        return pingMs;
    }

    synchronized int messages() {
        return messages;
    }
}
