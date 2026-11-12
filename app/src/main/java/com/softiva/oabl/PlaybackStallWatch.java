package com.softiva.oabl;

/**
 * Notices a song that should be playing but is not moving: a damaged file can leave the player buffering
 * (or starting and stopping) at one spot for good, with no error to react to. Pure, so it unit-tests on the JVM.
 */
final class PlaybackStallWatch {
    /** How long the position may stand still while playback is wanted before the song counts as stuck. */
    static final long STALL_MS = 6_000;
    private long lastPosition = -1, since;

    /** Call about once a second. True once the position has stood still for {@link #STALL_MS} while playback is wanted. */
    boolean stalled(long nowMs, long positionMs, boolean wantedPlaying) {
        // Moving, or not asked to play: fine, and the clock starts over.
        if (!wantedPlaying || positionMs != lastPosition) { lastPosition = positionMs; since = nowMs; return false; }
        // Same spot: real playback advances the position, so this is stuck whatever state the player claims.
        return nowMs - since >= STALL_MS;
    }

    void reset() { lastPosition = -1; since = 0; }
}
