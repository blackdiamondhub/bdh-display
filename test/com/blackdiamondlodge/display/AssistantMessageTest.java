package com.blackdiamondlodge.display;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class AssistantMessageTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        AssistantMessage m = AssistantMessage.parse("{\"type\": \"state\", \"state\": \"thinking\"}");
        eq("state type", m.type, "state");
        eq("state value", m.str("state"), "thinking");
        eq("state is assistant", m.isAssistant(), true);

        // Python's json.dumps escapes quotes and non-ASCII.
        m = AssistantMessage.parse("{\"type\": \"transcript\", \"role\": \"assistant\", "
                + "\"text\": \"It\\u2019s 1:38 PM, \\\"Pacific\\\".\\nOK \\\\ done\", \"final\": true}");
        eq("transcript role", m.str("role"), "assistant");
        eq("transcript unescaped", m.str("text"), "It\u2019s 1:38 PM, \"Pacific\".\nOK \\ done");
        eq("final", m.bool("final"), true);
        eq("partial", AssistantMessage.parse("{\"type\":\"transcript\",\"text\":\"hi\",\"final\":false}").bool("final"), false);
        eq("missing bool", AssistantMessage.parse("{\"type\":\"transcript\"}").bool("final"), false);
        eq("raw utf-8", AssistantMessage.parse("{\"type\":\"transcript\",\"text\":\"caf\u00e9\"}").str("text"), "caf\u00e9");

        for (String t : new String[]{"speak_start", "speak_end", "speak_stop"}) {
            eq(t, AssistantMessage.parse("{\"type\":\"" + t + "\"}").isAssistant(), true);
        }
        m = AssistantMessage.parse("{\"type\": \"conversation_end\", \"reason\": \"not_configured\", "
                + "\"message\": \"The assistant isn't set up yet.\"}");
        eq("end reason", m.str("reason"), "not_configured");
        eq("end message", m.str("message"), "The assistant isn't set up yet.");
        eq("end no message", AssistantMessage.parse("{\"type\":\"conversation_end\",\"reason\":\"idle\"}").str("message"), null);

        m = AssistantMessage.parse("{\"type\": \"hello\", \"words\": [\"hey_jarvis\", \"x\"], \"threshold\": 0.5, "
                + "\"assistant\": true, \"extra\": {\"a\": [1, {\"b\": null}]}}");
        eq("hello not assistant", m.isAssistant(), false);
        eq("hello skips nested", m.bool("assistant"), true);
        eq("nested not a string", m.str("extra"), null);
        eq("wake not assistant", AssistantMessage.parse("{\"type\":\"wake\",\"word\":\"hey_jarvis\",\"score\":0.91}").isAssistant(), false);
        eq("empty object", AssistantMessage.parse("{}").type, "");

        eq("garbage", AssistantMessage.parse("<html>"), null);
        eq("truncated", AssistantMessage.parse("{\"type\":\"state\",\"state\":\"thin"), null);
        eq("trailing junk", AssistantMessage.parse("{\"type\":\"state\"} x"), null);
        eq("null", AssistantMessage.parse(null), null);

        pcmQueue();
        System.out.println("AssistantMessageTest: " + passed + " checks passed");
    }

    private static void pcmQueue() throws Exception {
        PcmQueue q = new PcmQueue(10);
        byte[] out = new byte[16];
        eq("accepts", q.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6), 6);
        eq("prebuffer not met", q.awaitStart(8, 10), false);
        q.end();
        eq("ended segment starts early", q.awaitStart(8, 10), true);
        eq("read whole samples", q.read(out, 5, 10), 4);
        eq("read order", out[0] + "," + out[3], "1,4");
        eq("overflow dropped", q.write(new byte[]{7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 0, 10), 8);
        eq("dropped count", q.dropped(), 2L);
        eq("write reopens segment", q.drained(), false);
        eq("wraps around", q.read(out, 16, 10), 10);
        eq("wrap order", out[0] + "," + out[2] + "," + out[9], "5,7,14");
        eq("timeout empty", q.read(out, 16, 5), 0);
        q.end();
        eq("drained", q.drained(), true);

        q.write(new byte[]{1, 2, 3, 4}, 0, 4);
        eq("speak_stop clears", q.clear(), 4);
        eq("cleared is drained", q.drained(), true);
        eq("nothing left", q.read(out, 16, 5), 0);

        // A blocked reader wakes up for new audio and for close.
        PcmQueue w = new PcmQueue(64);
        int[] got = new int[1];
        Thread t = new Thread(() -> {
            try { got[0] = w.read(new byte[64], 64, 2000); } catch (InterruptedException ignored) { }
        });
        t.start();
        Thread.sleep(50);
        w.write(new byte[6], 0, 6);
        t.join(2000);
        eq("reader woken by write", got[0], 6);
        t = new Thread(() -> {
            try { got[0] = w.read(new byte[64], 64, 2000); } catch (InterruptedException ignored) { }
        });
        t.start();
        Thread.sleep(50);
        w.close();
        t.join(2000);
        eq("reader woken by close", got[0], -1);
        eq("closed accepts nothing", w.write(new byte[2], 0, 2), 0);
    }

    private static void eq(String what, Object got, Object want) {
        if (want == null ? got != null : !want.equals(got)) {
            System.err.println("FAIL " + what + ": got " + got + ", want " + want);
            System.exit(1);
        }
        passed++;
    }
}
