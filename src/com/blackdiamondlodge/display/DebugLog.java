package com.blackdiamondlodge.display;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Thread-safe ring buffer of log lines, mirrored to a rotating file so history survives restarts. */
final class DebugLog {
    private static final String TAG = "BDHDisplay";
    private static final int MAX_LINES = 3000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    static final class Entry {
        final long seq;
        final String line;
        Entry(long seq, String line) { this.seq = seq; this.line = line; }
    }

    private static final ArrayDeque<Entry> buf = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US);
    private static File file, oldFile;
    private static OutputStream out;
    private static long seq;
    private static String lastMsg;
    private static int repeats;

    private DebugLog() { }

    static synchronized void init(File dir) {
        file = new File(dir, "bdh.log");
        oldFile = new File(dir, "bdh.log.1");
        openFile();
    }

    static void i(String m) { add("I", m); }
    static void w(String m) { add("W", m); }
    static void e(String m) { add("E", m); }

    static synchronized void add(String level, String m) {
        String key = level + m;
        if (key.equals(lastMsg)) { repeats++; return; }
        if (repeats > 0) append("I", "(previous message repeated " + repeats + " more times)");
        lastMsg = key;
        repeats = 0;
        append(level, m);
    }

    private static void append(String level, String m) {
        if ("E".equals(level)) Log.e(TAG, m); else if ("W".equals(level)) Log.w(TAG, m); else Log.i(TAG, m);
        String line = fmt.format(new Date()) + " " + level + " " + m;
        buf.addLast(new Entry(++seq, line));
        while (buf.size() > MAX_LINES) buf.removeFirst();
        if (out == null) return;
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (file.length() > MAX_FILE_BYTES) {
                out.close();
                oldFile.delete();
                file.renameTo(oldFile);
                openFile();
            }
        } catch (IOException ex) {
            out = null;
            Log.w(TAG, "log file write failed: " + ex);
        }
    }

    private static void openFile() {
        try {
            out = new FileOutputStream(file, true);
        } catch (IOException ex) {
            out = null;
            Log.w(TAG, "log file open failed: " + ex);
        }
    }

    static synchronized long lastSeq() { return seq; }

    /** Lines with seq &gt; since, newest last, at most n. */
    static synchronized List<Entry> since(long since, int n) {
        List<Entry> r = new ArrayList<>();
        for (Entry e : buf) if (e.seq > since) r.add(e);
        return r.size() > n ? new ArrayList<>(r.subList(r.size() - n, r.size())) : r;
    }

    /** Newest-first lines for the on-screen dialog. */
    static synchronized List<String> recent(int n) {
        List<String> r = new ArrayList<>();
        java.util.Iterator<Entry> it = buf.descendingIterator();
        while (it.hasNext() && r.size() < n) r.add(it.next().line);
        return r;
    }

    /** The last {@code bytes} of the persisted log (previous + current file), including earlier runs. */
    static synchronized byte[] fileTail(int bytes) {
        try {
            byte[] cur = readTail(file, bytes);
            byte[] old = cur.length < bytes ? readTail(oldFile, bytes - cur.length) : new byte[0];
            byte[] all = new byte[old.length + cur.length];
            System.arraycopy(old, 0, all, 0, old.length);
            System.arraycopy(cur, 0, all, old.length, cur.length);
            return all;
        } catch (IOException ex) {
            return ("read failed: " + ex).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] readTail(File f, int bytes) throws IOException {
        if (f == null || !f.exists() || bytes <= 0) return new byte[0];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int n = (int) Math.min(len, bytes);
            byte[] b = new byte[n];
            raf.seek(len - n);
            raf.readFully(b);
            return b;
        }
    }
}
