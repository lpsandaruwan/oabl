package com.softiva.oabl;

/**
 * The head unit stamps readings with its own clock, which needn't match the box's. The smallest recent
 * gap between receiving a live snapshot and its stamp is the clock offset plus the shortest transfer;
 * adding it converts head-unit times to box time, so ages are measured on one clock.
 */
final class CarClockOffset {
    static final long WINDOW_MS = 300_000;
    private long offsetMs, observedAt;
    private boolean known;

    /** A live snapshot stamped {@code remoteMs} by the head unit, received at box time {@code localMs}. */
    synchronized void observe(long remoteMs, long localMs) {
        if (remoteMs <= 0) return;
        long offset = localMs - remoteMs;
        if (!known || offset < offsetMs || localMs - observedAt > WINDOW_MS) { offsetMs = offset; observedAt = localMs; known = true; }
    }

    /** Head-unit time on the box clock; unchanged until a live snapshot has been seen. */
    synchronized long toLocal(long remoteMs) { return remoteMs <= 0 || !known ? remoteMs : remoteMs + offsetMs; }

    synchronized long offsetMs() { return known ? offsetMs : 0; }
}
