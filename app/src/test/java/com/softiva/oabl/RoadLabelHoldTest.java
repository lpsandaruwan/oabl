package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoadLabelHoldTest {
    private static final double LAT = 6.9, LON = 79.9;
    // 0.001 degrees of latitude is about 110 m.

    @Test public void theLastNameStaysUntilAnotherArrives() {
        RoadLabelHold hold = new RoadLabelHold();
        assertNull(hold.current(LAT, LON, 0));
        hold.remember("Galle Road", LAT, LON, 1_000);
        assertEquals("Galle Road", hold.current(LAT + 0.002, LON, 6_000));
    }

    @Test public void itIsDimmedOnceTheCarHasMovedOn() {
        RoadLabelHold hold = new RoadLabelHold();
        hold.remember("Galle Road", LAT, LON, 0);
        assertFalse(hold.stale(LAT + 0.0005, LON));   // about 55 m
        assertTrue(hold.stale(LAT + 0.0020, LON));    // about 220 m
    }

    @Test public void itIsDroppedAfterAKilometreOrNinetySeconds() {
        RoadLabelHold hold = new RoadLabelHold();
        hold.remember("Galle Road", LAT, LON, 0);
        assertNull("1.2 km away", hold.current(LAT + 0.011, LON, 10_000));
        assertEquals("Galle Road", hold.current(LAT, LON, 89_000));
        assertNull("91 s later", hold.current(LAT, LON, 91_000));
    }
}
