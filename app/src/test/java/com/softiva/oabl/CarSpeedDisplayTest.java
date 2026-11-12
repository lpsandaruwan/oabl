package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Home and the overlay show the speedometer's figure when Link sends one, else the real speed. */
public class CarSpeedDisplayTest {
    private static final long NOW = 1_000_000;

    @Test public void theSpeedometerFigureWinsWhileFresh() {
        CarLinkController.SpeedReading shown = CarLinkController.shownSpeed(63, NOW - 500, 60, NOW - 500, NOW);
        assertEquals(63, shown.current(NOW), 0);
    }

    @Test public void theRealSpeedIsKeptWhenLinkSendsNoFigure() {
        // Older Link, or a figure Link withheld: only the real speed arrives.
        assertEquals(60, CarLinkController.shownSpeed(Double.NaN, 0, 60, NOW - 500, NOW).current(NOW), 0);
        // A figure older than the latest real speed was withheld since; it is not reused.
        assertEquals(60, CarLinkController.shownSpeed(63, NOW - 1500, 60, NOW - 500, NOW).current(NOW), 0);
    }

    @Test public void aStaleFigureFallsBackAndStaysUnknownWhenBothAreOld() {
        assertEquals(60, CarLinkController.shownSpeed(63, NOW - 5000, 60, NOW - 500, NOW).current(NOW), 0);
        assertTrue(Double.isNaN(CarLinkController.shownSpeed(63, NOW - 5000, 60, NOW - 5000, NOW).current(NOW)));
    }

    @Test public void aZeroFigureNeverHidesALowRealSpeed() {
        // The cluster held 0 while the car crept at 8 km/h: show 8, not 0.
        assertEquals(8, CarLinkController.shownSpeed(0, NOW - 500, 8, NOW - 500, NOW).current(NOW), 0);
        assertEquals(12, CarLinkController.shownSpeed(3, NOW - 500, 12, NOW - 500, NOW).current(NOW), 0);
        // Standing still: both zero, the figure is fine.
        assertEquals(0, CarLinkController.shownSpeed(0, NOW - 500, 0, NOW - 500, NOW).current(NOW), 0);
        // A speedometer slightly over the real speed still wins.
        assertEquals(9, CarLinkController.shownSpeed(9, NOW - 500, 8, NOW - 500, NOW).current(NOW), 0);
    }
}
