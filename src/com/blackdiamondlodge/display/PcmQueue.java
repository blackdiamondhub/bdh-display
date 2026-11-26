package com.blackdiamondlodge.display;

/**
 * Bounded byte queue between the listen socket's reader (reply audio) and the
 * playback thread. Pure Java so it runs in the JVM tests. Audio beyond the
 * capacity is dropped (counted), never blocking the socket reader, which also
 * has to see {@code speak_stop} promptly.
 *
 * <p>A segment runs from {@code speak_start} to {@code speak_end}: playback
 * starts once {@code prebuffer} bytes are queued or the segment has ended, to
 * smooth network jitter.
 */
final class PcmQueue {
    private final byte[] buf;
    private int head, size;
    private boolean ended, closed;
    private long dropped;

    PcmQueue(int capacity) {
        buf = new byte[capacity];
    }

    /** Appends what fits; returns the number of bytes accepted. */
    synchronized int write(byte[] b, int off, int len) {
        if (closed) return 0;
        int n = Math.min(len, buf.length - size);
        int tail = (head + size) % buf.length;
        int first = Math.min(n, buf.length - tail);
        System.arraycopy(b, off, buf, tail, first);
        System.arraycopy(b, off + first, buf, 0, n - first);
        size += n;
        dropped += len - n;
        ended = false;
        notifyAll();
        return n;
    }

    /**
     * Up to {@code max} bytes (an even count, whole samples) into {@code dst};
     * waits up to {@code timeoutMs} for data. Returns 0 on timeout, -1 once closed.
     */
    synchronized int read(byte[] dst, int max, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (size < 2 && !closed) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return 0;
            wait(left);
        }
        if (closed) return -1;
        int n = Math.min(max, size) & ~1;
        int first = Math.min(n, buf.length - head);
        System.arraycopy(buf, head, dst, 0, first);
        System.arraycopy(buf, 0, dst, first, n - first);
        head = (head + n) % buf.length;
        size -= n;
        return n;
    }

    /**
     * Waits up to {@code timeoutMs} until a segment can start playing:
     * {@code prebuffer} bytes queued, or some audio queued and the segment ended.
     */
    synchronized boolean awaitStart(int prebuffer, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (!closed && !(size >= prebuffer || (ended && size >= 2))) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return !closed;
    }

    /** speak_end: the segment's audio is complete. */
    synchronized void end() {
        ended = true;
        notifyAll();
    }

    /** True when the segment ended and everything was read. */
    synchronized boolean drained() {
        return ended && size < 2;
    }

    /** speak_stop: drops everything queued; returns the bytes dropped. */
    synchronized int clear() {
        int n = size;
        head = size = 0;
        ended = true;
        notifyAll();
        return n;
    }

    synchronized void close() {
        closed = true;
        head = size = 0;
        notifyAll();
    }

    synchronized boolean closed() {
        return closed;
    }

    synchronized int size() {
        return size;
    }

    synchronized long dropped() {
        return dropped;
    }
}
