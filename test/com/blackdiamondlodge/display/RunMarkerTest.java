package com.blackdiamondlodge.display;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Plain-JVM tests (no JUnit); run by build.sh. Exits non-zero on the first failure. */
public final class RunMarkerTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        markerRoundTrip();
        classifyCases();
        logcatFilter();
        System.out.println("RunMarkerTest: " + passed + " checks passed");
    }

    static void markerRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("runmarker").toFile();
        File f = new File(dir, "run.marker");
        RunMarker m = new RunMarker(f);
        check("no marker -> null", m.readPrevious() == null);

        Map<String, String> s = new LinkedHashMap<>();
        s.put("pid", "1234");
        s.put("url", "http://192.168.1.179:8000/cameras/?a=b&c=d#x");
        s.put("page", "videos=4 playing=3 heap=88MB");
        s.put("skipped", null);
        m.write(s);
        Properties p = m.readPrevious();
        check("round trip pid", "1234".equals(p.getProperty("pid")));
        check("round trip special chars", s.get("url").equals(p.getProperty("url")));
        check("round trip spaces", s.get("page").equals(p.getProperty("page")));
        check("null values skipped", !p.containsKey("skipped"));
        check("tmp file cleaned up", !new File(f.getPath() + ".tmp").exists());

        s.put("pid", "5678");
        m.write(s);
        check("overwrite", "5678".equals(m.readPrevious().getProperty("pid")));

        m.clear();
        check("clear -> null", m.readPrevious() == null);
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    static Properties prev(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    static void classifyCases() {
        Properties base = prev("boot_id", "A", "version", "17", "started_wall_ms", "1000", "elapsed_ms", "50000");
        check("same boot/version -> died",
                RunMarker.EXIT_DIED.equals(RunMarker.classify(base, "A", 60000, "17", 500)));
        check("boot id changed -> reboot",
                RunMarker.EXIT_REBOOT.equals(RunMarker.classify(base, "B", 60000, "17", 500)));
        check("reboot wins over version change",
                RunMarker.EXIT_REBOOT.equals(RunMarker.classify(base, "B", 60000, "18", 5000)));

        Properties noBoot = prev("version", "17", "started_wall_ms", "1000", "elapsed_ms", "50000");
        check("no boot id, uptime went backwards -> reboot",
                RunMarker.EXIT_REBOOT.equals(RunMarker.classify(noBoot, "", 1000, "17", 500)));
        check("no boot id, uptime forward -> died",
                RunMarker.EXIT_DIED.equals(RunMarker.classify(noBoot, "", 60000, "17", 500)));
        check("prev had boot id, now unreadable, uptime forward -> died",
                RunMarker.EXIT_DIED.equals(RunMarker.classify(base, "", 60000, "17", 500)));

        check("version changed -> upgrade",
                RunMarker.EXIT_UPGRADE.equals(RunMarker.classify(base, "A", 60000, "18", 500)));
        check("same version reinstalled after start -> upgrade",
                RunMarker.EXIT_UPGRADE.equals(RunMarker.classify(base, "A", 60000, "17", 2000)));

        Properties stopped = prev("boot_id", "A", "version", "17", "started_wall_ms", "1000", "stopped", "1");
        check("stopped -> background kill",
                RunMarker.EXIT_BACKGROUND.equals(RunMarker.classify(stopped, "A", 60000, "17", 500)));
        check("upgrade wins over stopped",
                RunMarker.EXIT_UPGRADE.equals(RunMarker.classify(stopped, "A", 60000, "18", 500)));

        Properties garbage = prev("boot_id", "A", "elapsed_ms", "abc", "started_wall_ms", "x");
        check("garbage numbers -> died, no exception",
                RunMarker.EXIT_DIED.equals(RunMarker.classify(garbage, "A", 60000, "17", 500)));
        check("empty marker -> died",
                RunMarker.EXIT_DIED.equals(RunMarker.classify(new Properties(), "A", 60000, "17", 500)));
    }

    static void logcatFilter() {
        List<String> lines = Arrays.asList(
                "--------- beginning of main",
                "11-20 16:01:02.123  1234  1250 I chromium: [INFO:CONSOLE] hello",
                "11-20 16:01:02.200 12345 12345 I other   : pid-prefix lookalike",
                "11-20 16:01:03.000   812   812 I ActivityManager: Process com.blackdiamondlodge.display (pid 1234) has died",
                "11-20 16:01:02.500  1234  1301 E chromium: [ERROR:gpu_process_host.cc] GPU process exited",
                "11-20 16:01:02.600  1234  1301 F libc    : Fatal signal 6 (SIGABRT), code -6 in tid 1301 (GpuThread)",
                "11-20 16:01:02.700  4321  4321 F DEBUG   : backtrace:",
                "not a logcat line",
                "11-20 16:01:02.800  4321  4321 F DEBUG   :     #00 pc 0001c0e0  /system/lib/libc.so (abort+63)");
        List<String> out = RunMarker.filterLogcat(lines, "1234", 100);
        check("keeps pid + DEBUG lines (5)", out.size() == 5);
        check("excludes pid-prefix lookalike", out.stream().noneMatch(l -> l.contains("lookalike")));
        check("excludes other pids", out.stream().noneMatch(l -> l.contains("ActivityManager")));
        check("keeps fatal signal", out.stream().anyMatch(l -> l.contains("Fatal signal")));

        List<String> capped = RunMarker.filterLogcat(lines, "1234", 2);
        check("cap keeps most recent", capped.size() == 2 && capped.get(1).contains("abort+63")
                && capped.get(0).contains("backtrace"));
        check("other pid -> only DEBUG tombstone lines",
                RunMarker.filterLogcat(lines, "999", 10).stream().allMatch(l -> l.contains(" DEBUG ")));
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            System.err.println("FAIL: " + name);
            System.exit(1);
        }
        passed++;
    }
}
