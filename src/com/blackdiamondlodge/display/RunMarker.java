package com.blackdiamondlodge.display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects how the previous run ended. Android 9 has no ApplicationExitInfo, and a native/GPU crash
 * kills the process without running any Java code, so the app keeps a marker file that is rewritten
 * with the latest state while running and deleted on a clean shutdown. A marker found at startup
 * means the previous process died. Pure Java (no Android APIs) so it can be unit-tested on a JVM.
 */
final class RunMarker {
    static final String EXIT_REBOOT = "device rebooted";
    static final String EXIT_UPGRADE = "app upgraded";
    static final String EXIT_BACKGROUND = "killed in background";
    static final String EXIT_DIED = "process died";

    private final File file;

    RunMarker(File file) {
        this.file = file;
    }

    /** The previous run's last recorded state, or null if it shut down cleanly (or never ran). */
    Properties readPrevious() {
        if (!file.exists()) return null;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            p.setProperty("read_error", e.toString());
        }
        return p;
    }

    /** Atomically replaces the marker (a crash mid-write must not leave a truncated file). */
    void write(Map<String, String> state) throws IOException {
        Properties p = new Properties();
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (e.getValue() != null) p.setProperty(e.getKey(), e.getValue());
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
        if (!tmp.renameTo(file)) throw new IOException("rename " + tmp + " failed");
    }

    void clear() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * Why the previous run ended without a clean shutdown.
     *
     * @param bootId        current /proc/sys/kernel/random/boot_id ("" if unreadable)
     * @param elapsedNowMs  current SystemClock.elapsedRealtime() (fallback reboot check)
     * @param version       current versionCode
     * @param pkgUpdatedMs  PackageInfo.lastUpdateTime (wall clock)
     */
    static String classify(Properties prev, String bootId, long elapsedNowMs, String version, long pkgUpdatedMs) {
        String prevBoot = prev.getProperty("boot_id", "");
        if (!prevBoot.isEmpty() && !bootId.isEmpty()) {
            if (!prevBoot.equals(bootId)) return EXIT_REBOOT;
        } else if (elapsedNowMs < num(prev, "elapsed_ms")) {
            return EXIT_REBOOT;
        }
        String prevVersion = prev.getProperty("version", version);
        long started = num(prev, "started_wall_ms");
        if (!prevVersion.equals(version) || (started > 0 && pkgUpdatedMs > started)) return EXIT_UPGRADE;
        if ("1".equals(prev.getProperty("stopped"))) return EXIT_BACKGROUND;
        return EXIT_DIED;
    }

    private static final Pattern THREADTIME =
            Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s+([^:]*?)\\s*:");

    /**
     * Picks a dead process's lines out of `logcat -v threadtime` output: everything it logged
     * (incl. "F libc: Fatal signal" and chromium GPU errors) plus crash_dump's DEBUG tombstone lines.
     */
    static List<String> filterLogcat(List<String> lines, String pid, int max) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            Matcher m = THREADTIME.matcher(l);
            if (!m.find()) continue;
            if (m.group(1).equals(pid) || m.group(2).equals("DEBUG")) out.add(l);
        }
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    static long num(Properties p, String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
