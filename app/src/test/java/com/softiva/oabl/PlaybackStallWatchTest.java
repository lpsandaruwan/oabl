package com.softiva.oabl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackStallWatchTest {
    @Test public void aMovingSongIsNeverStuck() {
        PlaybackStallWatch watch = new PlaybackStallWatch();
        for (int s = 0; s < 30; s++) assertFalse(watch.stalled(s * 1000L, 5_000L + s * 1000L, true));
    }

    @Test public void aSongFrozenAtOneSpotIsStuckAfterSixSeconds() {
        PlaybackStallWatch watch = new PlaybackStallWatch();
        assertFalse(watch.stalled(0, 130_580, true));
        assertFalse(watch.stalled(3_000, 130_580, true));
        assertFalse(watch.stalled(5_900, 130_580, true));
        assertTrue(watch.stalled(6_000, 130_580, true));
    }

    @Test public void pausedByTheUserIsNotStuck() {
        PlaybackStallWatch watch = new PlaybackStallWatch();
        for (int s = 0; s < 30; s++) assertFalse(watch.stalled(s * 1000L, 90_000, false));
    }

    @Test public void aSeekRestartsTheClock() {
        PlaybackStallWatch watch = new PlaybackStallWatch();
        watch.stalled(0, 10_000, true);
        assertFalse(watch.stalled(5_000, 10_000, true));
        assertFalse(watch.stalled(5_500, 60_000, true));
        assertFalse(watch.stalled(11_000, 60_000, true));
        assertTrue(watch.stalled(11_500, 60_000, true));
    }
}
