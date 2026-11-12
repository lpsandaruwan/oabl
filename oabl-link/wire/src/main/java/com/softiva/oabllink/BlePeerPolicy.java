package com.softiva.oabllink;

/**
 * Whether a new Bluetooth connection may take over from the head unit's current reader.
 * The AI box is not bonded, so Android gives it a fresh random address for every connection; a
 * link that dropped without a clean disconnect still holds the old address as the peer. Holding
 * the reader by address then refused the box's own reconnect until the radio timed out. A peer
 * that never proved the code, or has gone silent, gives way; a working, authenticated reader does
 * not. Timestamps are monotonic milliseconds.
 */
public final class BlePeerPolicy {
    /** The box asks for speed every 0.5 s while driving and 1.5 s otherwise; 6 s of silence means gone. */
    public static final long SILENT_MS = 6000;

    private BlePeerPolicy() { }

    public static boolean replaceable(boolean authenticated, long lastHeard, long now) {
        return !authenticated || now - lastHeard >= SILENT_MS;
    }
}
