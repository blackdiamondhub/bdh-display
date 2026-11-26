package com.blackdiamondlodge.display;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import org.json.JSONObject;

/**
 * Plays the voice assistant's reply audio (24 kHz s16le mono) from the listen
 * socket. Uses a voice-communication AudioTrack so the platform echo canceller
 * on the VOICE_COMMUNICATION mic takes it as its reference and the assistant
 * doesn't hear itself; the mic keeps streaming during playback for barge-in.
 * Thread-safe: audio and speak_* arrive on the socket reader thread, release
 * on the UI thread.
 */
final class AssistantPlayer {
    static final int RATE = 24000;
    /** Queue bound: 30 s of audio; replies are a sentence or two. */
    private static final int CAPACITY = RATE * 2 * 30;
    /** Jitter buffer before a segment starts: 150 ms. */
    private static final int PREBUFFER = RATE * 2 * 150 / 1000;
    private static final int CHUNK = RATE * 2 / 20;

    private final PcmQueue queue = new PcmQueue(CAPACITY);
    private volatile AudioTrack track;
    private volatile boolean released;
    private Thread thread;
    private long segments, bytesIn, bytesPlayed, flushes, flushedBytes;
    private int underruns;
    private volatile boolean playing;

    /** speak_start: lazily opens the track and the playback thread. */
    synchronized void begin() {
        if (released) return;
        segments++;
        if (thread == null) {
            thread = new Thread(this::run, "assistant-play");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Binary frame: queue reply audio (never blocks). */
    void write(byte[] pcm) {
        if (released) return;
        queue.write(pcm, 0, pcm.length);
        synchronized (this) { bytesIn += pcm.length; }
    }

    /** speak_end: play out what is queued. */
    void end() {
        queue.end();
    }

    /** speak_stop: the user interrupted, drop all queued and buffered audio now. */
    void flush() {
        int n = queue.clear();
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
        synchronized (this) { flushes++; flushedBytes += n; }
    }

    /** conversation_end / idle: stop and free the track. */
    void release() {
        released = true;
        queue.close();
        Thread th;
        synchronized (this) { th = thread; }
        if (th != null) th.interrupt();
    }

    boolean released() {
        return released;
    }

    private void run() {
        byte[] buf = new byte[CHUNK];
        try {
            while (!released) {
                if (!queue.awaitStart(PREBUFFER, 500)) continue;
                AudioTrack t = open();
                if (t == null) { queue.clear(); continue; }
                playing = true;
                while (!released) {
                    int n = queue.read(buf, buf.length, 100);
                    if (n < 0) break;
                    if (n == 0) {
                        if (queue.drained()) break;
                        continue;
                    }
                    if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
                    int w = t.write(buf, 0, n);
                    if (w > 0) synchronized (this) { bytesPlayed += w; }
                }
                // Plays out what the track still holds, then stops (no underrun noise between replies).
                try { t.stop(); } catch (Exception ignored) { }
                synchronized (this) { underruns = t.getUnderrunCount(); }
                playing = false;
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            DebugLog.w("assistant playback failed: " + e);
        } finally {
            playing = false;
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) { }
                t.release();
            }
        }
    }

    private AudioTrack open() {
        AudioTrack t = track;
        if (t != null) return t;
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, CHUNK * 4))
                    .build();
        } catch (Exception e) {
            DebugLog.w("assistant AudioTrack failed: " + e);
            return null;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            DebugLog.w("assistant AudioTrack not initialized");
            return null;
        }
        track = t;
        return t;
    }

    synchronized JSONObject stats() throws Exception {
        AudioTrack t = track;
        if (t != null) try { underruns = t.getUnderrunCount(); } catch (Exception ignored) { }
        return new JSONObject().put("track", t == null ? "closed" : "open").put("playing", playing)
                .put("segments", segments).put("received_ms", bytesIn * 1000 / (RATE * 2))
                .put("played_ms", bytesPlayed * 1000 / (RATE * 2)).put("queued_ms", queue.size() * 1000L / (RATE * 2))
                .put("dropped_ms", queue.dropped() * 1000 / (RATE * 2)).put("flushes", flushes)
                .put("flushed_ms", flushedBytes * 1000 / (RATE * 2)).put("underruns", underruns);
    }
}
