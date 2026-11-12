package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedReadingTest {
    @Test public void carZeroWinsOverMovingGps() {
        SpeedReading r = SpeedReading.select(true, 0, 72, true);
        assertTrue(r.fromCar); assertTrue(r.known()); assertEquals(0, r.kph);
    }
    @Test public void freshCarDoesNotRequireGpsPermissionOrFix() {
        SpeedReading r = SpeedReading.select(true, 42.6, 0, false);
        assertTrue(r.fromCar); assertEquals(43, r.kph);
    }
    @Test public void missingOrInvalidCarNeverFallsBackToGps() {
        for (double value : new double[]{Double.NaN, -1, 221, Double.POSITIVE_INFINITY}) {
            SpeedReading r = SpeedReading.select(true, value, 65, true);
            assertTrue(r.fromCar); assertFalse(r.known());
        }
    }
    @Test public void manualModeUsesOnlyReliableGps() {
        SpeedReading r = SpeedReading.select(false, 88, 23, true);
        assertFalse(r.fromCar); assertEquals(23, r.kph);
        assertFalse(SpeedReading.select(false, 88, 23, false).known());
    }

    @Test public void finerSpeedShowsOneDecimalAndStandingStillShowsNone() {
        assertEquals("43.6", SpeedReading.tenths(43.62, true));
        assertEquals("43.0", SpeedReading.tenths(43, true));
        assertNull("standing still", SpeedReading.tenths(0, true));
        assertNull("a Link that sends whole numbers", SpeedReading.tenths(43, false));
        assertNull(SpeedReading.tenths(Double.NaN, true));
    }

    @Test public void theWholeValueRoundsTheFinerSpeed() {
        assertEquals(44, SpeedReading.select(true, 43.62, 0, false).kph);
        assertEquals(43, SpeedReading.select(true, 43.38, 0, false).kph);
    }
}
