package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The drive-mode → accent map the car-reactive theme uses: Eco green, Comfort blue, Sport red. */
public class DriveModeTest {
    @Test public void mapsEachConfirmedCodeToItsModeAndColour() {
        assertEquals(DriveMode.COMFORT, DriveMode.byCode(0));
        assertEquals(DriveMode.ECO, DriveMode.byCode(1));
        assertEquals(DriveMode.SPORT, DriveMode.byCode(2));
        // The driver's map: green / blue / red. Red is the most saturated, green the least blue-ish.
        assertEquals(0xff2e9e5b, DriveMode.ECO.accent);
        assertEquals(0xff2f6fd0, DriveMode.COMFORT.accent);
        assertEquals(0xffd23b3b, DriveMode.SPORT.accent);
    }

    @Test public void leavesAnUnknownCodeUnmappedRatherThanGuessing() {
        assertNull("3 is not a confirmed mode", DriveMode.byCode(3));
        assertNull("4 is not a confirmed mode", DriveMode.byCode(4));
        assertNull("the car's -1 no-value marker is not a mode", DriveMode.byCode(-1));
    }
}
