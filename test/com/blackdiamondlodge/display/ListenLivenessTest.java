package com.blackdiamondlodge.display;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class ListenLivenessTest {
    private static int passed;

    private static final String HELLO_B4 = "{\"type\": \"hello\", \"words\": [\"hey_jarvis\"], \"threshold\": 0.5, \"assistant\": true}";
    private static final String HELLO_B5 = "{\"type\": \"hello\", \"words\": [\"hey_jarvis\"], \"threshold\": 0.5, \"assistant\": true, \"ping\": 15}";
    private static final String PING = "{\"type\": \"ping\"}";

    public static void main(String[] args) {
        ListenLiveness l = new ListenLiveness();
        eq("not open yet: never stale", l.stale(1_000_000), null);
        eq("not open: no rx age", l.sinceRxMs(5), -1L);

        // No hello: dropped after the hello timeout (a proxy holding a socket to nobody).
        l = new ListenLiveness();
        l.opened(1_000);
        eq("waiting for hello", l.stale(1_000 + ListenLiveness.HELLO_TIMEOUT_MS - 1), null);
        eq("no hello", l.stale(1_000 + ListenLiveness.HELLO_TIMEOUT_MS), "no hello in 15s");
        l.frame(2_000);
        eq("audio isn't a hello", l.stale(1_000 + ListenLiveness.HELLO_TIMEOUT_MS) != null, true);

        // Old voice app (0.2.0b4): hello without ping, never pings. Never dropped for silence.
        l = new ListenLiveness();
        l.opened(0);
        l.message(500, HELLO_B4);
        eq("b4 hello seen", l.helloSeen(), true);
        eq("b4 no idle limit", l.idleLimitMs(), 0L);
        eq("b4 quiet for an hour is fine", l.stale(3_600_000), null);

        // New voice app: hello promises pings every 15 s; dead after 3 missed (45 s).
        l = new ListenLiveness();
        l.opened(0);
        l.message(100, HELLO_B5);
        eq("b5 ping", l.pingMs(), 15_000L);
        eq("b5 idle limit", l.idleLimitMs(), 45_000L);
        eq("b5 fresh", l.stale(30_000), null);
        l.message(15_100, PING);
        l.message(30_100, PING);
        eq("pings keep it alive", l.stale(70_000), null);
        eq("since rx", l.sinceRxMs(70_000), 39_900L);
        eq("silent 45 s", l.stale(75_100), "voice app silent for 45s");
        l.frame(75_200);
        eq("reply audio counts", l.stale(75_300), null);

        // A ping without the hello field (or before it) also turns the limit on.
        l = new ListenLiveness();
        l.opened(0);
        l.message(10, HELLO_B4);
        l.message(15_000, PING);
        eq("ping seen: default interval", l.idleLimitMs(), 45_000L);
        eq("ping seen: enforced", l.stale(60_000), "voice app silent for 45s");

        // Slow pings stretch the limit to 3 intervals; fast ones never go below 45 s.
        l = new ListenLiveness();
        l.opened(0);
        l.message(0, "{\"type\":\"hello\",\"ping\":30}");
        eq("slow pings", l.idleLimitMs(), 90_000L);
        l = new ListenLiveness();
        l.opened(0);
        l.message(0, "{\"type\":\"hello\",\"ping\":0.5}");
        eq("fast pings floor", l.idleLimitMs(), 45_000L);

        // A repeated hello (settings changed) doesn't reset the connection's age.
        l = new ListenLiveness();
        l.opened(0);
        l.message(100, HELLO_B5);
        eq("not healthy yet", l.healthy(10_000, 30_000), false);
        l.message(20_000, HELLO_B5);
        eq("healthy after 30 s", l.healthy(30_000, 30_000), true);
        eq("messages counted", l.messages(), 2);
        l = new ListenLiveness();
        l.opened(0);
        eq("never greeted: not healthy", l.healthy(600_000, 30_000), false);

        // Other messages (wake, assistant) are traffic but don't enable the limit.
        l = new ListenLiveness();
        l.opened(0);
        l.message(10, HELLO_B4);
        l.message(20, "{\"type\":\"wake\",\"word\":\"hey_jarvis\",\"score\":0.9}");
        eq("wake: still no limit", l.idleLimitMs(), 0L);
        System.out.println("ListenLivenessTest: " + passed + " checks passed");
    }

    private static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) {
            System.err.println("FAIL " + what + ": got " + got + ", want " + want);
            System.exit(1);
        }
        passed++;
    }
}
