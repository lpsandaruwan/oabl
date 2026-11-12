package com.softiva.oabllink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The rolling store: change-only recording, sequence numbers, catch-up, latest values and retention. */
public class SampleBufferTest {
    private static Reading reading(long t, Signal signal, double value) {
        return new Reading(t).set(signal, value);
    }

    @Test public void recordsOnlyChangesAndNumbersThem() {
        SampleBuffer buffer = new SampleBuffer();
        assertEquals(1, buffer.record(reading(100, Signal.SOC, 50)).seq());
        assertNull("an unchanged value adds nothing", buffer.record(reading(200, Signal.SOC, 50)));
        Sample second = buffer.record(reading(300, Signal.SOC, 49));
        assertEquals(2, second.seq());
        assertEquals(1, second.values().size()); // only the changed signal
        assertEquals(49.0, second.values().get(Signal.SOC), 0);
        assertEquals(2, buffer.size());
        assertEquals(2, buffer.latestSeq());
    }

    @Test public void onlyChangedSignalsInEachSample() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(100).set(Signal.SOC, 50).set(Signal.SPEED, 0));
        Sample sample = buffer.record(new Reading(200).set(Signal.SOC, 50).set(Signal.SPEED, 12));
        assertEquals(1, sample.values().size());
        assertTrue(sample.values().containsKey(Signal.SPEED));
        assertFalse(sample.values().containsKey(Signal.SOC));
    }

    @Test public void catchUpReturnsEverythingAfterASeq() {
        SampleBuffer buffer = new SampleBuffer();
        for (int i = 0; i < 5; i++) buffer.record(reading(100 + i, Signal.SOC, 50 - i));
        assertEquals(5, buffer.after(0, 100).size());
        List<Sample> rest = buffer.after(3, 100);
        assertEquals(2, rest.size());
        assertEquals(4, rest.get(0).seq());
        assertEquals(2, buffer.after(0, 2).size()); // limit respected
    }

    @Test public void latestValueAndTimePerSignal() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(reading(100, Signal.SOC, 62));
        buffer.record(reading(150, Signal.ODOMETER, 3851));
        buffer.record(reading(200, Signal.SOC, 61));
        assertEquals(61, buffer.latest(Signal.SOC), 0);
        assertEquals(200, buffer.latestTime(Signal.SOC));
        assertEquals(3851, buffer.latest(Signal.ODOMETER), 0);
        assertEquals(150, buffer.latestTime(Signal.ODOMETER));
        assertFalse(buffer.has(Signal.SPEED));
        assertTrue(Double.isNaN(buffer.latest(Signal.SPEED)));
    }

    @Test public void dropsSamplesOlderThanRetention() {
        SampleBuffer buffer = new SampleBuffer(1000); // 1 s window
        buffer.record(reading(0, Signal.SOC, 50));
        buffer.record(reading(500, Signal.SOC, 49));
        buffer.record(reading(1600, Signal.SOC, 48)); // older than 1600 - 1000 = 600 is pruned
        assertEquals(1, buffer.size());
        assertEquals(48, buffer.latest(Signal.SOC), 0); // the latest value survives pruning
        assertEquals(3, buffer.latestSeq()); // sequence numbers never reused
        assertEquals(1, buffer.after(0, 100).size());
    }

    @Test public void outOfOrderAndEmptyReadingsAreIgnored() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(reading(100, Signal.SOC, 50));
        assertNull(buffer.record(reading(50, Signal.SOC, 40))) ;
        assertNull(buffer.record(new Reading(300)));
        assertEquals(1, buffer.size());
    }

    @Test public void finerSpeedWritesARowPerKmhWhileTheLatestFollowsEveryRead() {
        SampleBuffer buffer = new SampleBuffer();
        assertNotNull(buffer.record(new Reading(1000).set(Signal.SPEED, 40.1)));
        assertNull(buffer.record(new Reading(2000).set(Signal.SPEED, 40.6)));
        assertEquals("no row, but the latest is current", 40.6, buffer.latest(Signal.SPEED), 1e-9);
        assertEquals(2000, buffer.latestTime(Signal.SPEED));
        assertNull(buffer.record(new Reading(3000).set(Signal.SPEED, 40.9)));
        assertNotNull("1 km/h from the last row", buffer.record(new Reading(4000).set(Signal.SPEED, 41.1)));
    }

    @Test public void stoppingAndPullingAwayAlwaysWriteARow() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(1000).set(Signal.SPEED, 0.5));
        assertNotNull(buffer.record(new Reading(2000).set(Signal.SPEED, 0)));
        assertNotNull(buffer.record(new Reading(3000).set(Signal.SPEED, 0.3)));
    }
}
