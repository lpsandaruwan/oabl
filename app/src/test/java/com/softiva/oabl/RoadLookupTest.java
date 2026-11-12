package com.softiva.oabl;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoadLookupTest {
    @Test public void aheadPointIsTheRightDistanceInTheRightDirection() {
        double[] north = RoadLookup.projectAhead(6.9, 79.9, 0, 100);
        assertEquals(100, GpsMotion.distance(6.9, 79.9, north[0], north[1]), 1);
        assertTrue(north[0] > 6.9); assertEquals(79.9, north[1], 1e-6);
        double[] east = RoadLookup.projectAhead(6.9, 79.9, 90, 100);
        assertEquals(100, GpsMotion.distance(6.9, 79.9, east[0], east[1]), 1);
        assertTrue(east[1] > 79.9); assertEquals(6.9, east[0], 1e-5);
    }

    @Test public void lookAheadIsSixSecondsOfTravelWithinLimits() {
        assertEquals(60, RoadLookup.lookAheadMeters(2), 0);       // walking pace: not less than 60 m
        assertEquals(84, RoadLookup.lookAheadMeters(14), 0);      // 50 km/h
        assertEquals(240, RoadLookup.lookAheadMeters(40), 0);     // 144 km/h
        assertEquals(250, RoadLookup.lookAheadMeters(60), 0);     // never more than 250 m
    }

    @Test public void retryBacksOffAndStops() {
        assertEquals(0, RoadLookup.retryDelayMs(0));
        assertEquals(3_000, RoadLookup.retryDelayMs(1));
        assertEquals(6_000, RoadLookup.retryDelayMs(2));
        assertEquals(12_000, RoadLookup.retryDelayMs(3));
        assertEquals(60_000, RoadLookup.retryDelayMs(6));
        assertEquals(60_000, RoadLookup.retryDelayMs(50));
    }

    @Test public void oneRoadFillsTheCellsWithinRangeOnly() {
        // A straight road running north through 6.9, 79.9 for about 2 km.
        double[][] road = {{6.89, 79.9}, {6.91, 79.9}};
        Set<String> cells = RoadLookup.cellsAlong(road, 6.9, 79.9, 250);
        assertTrue(cells.contains(RoadLookup.cell(6.9, 79.9)));
        assertTrue(cells.contains(RoadLookup.cell(6.9 + 0.0018, 79.9)));   // about 200 m ahead
        assertTrue(cells.contains(RoadLookup.cell(6.9 - 0.0018, 79.9)));   // about 200 m behind
        assertFalse(cells.contains(RoadLookup.cell(6.9 + 0.01, 79.9)));    // 1.1 km away
        assertTrue("about 9 cells each way", cells.size() >= 8 && cells.size() <= 14);
    }

    @Test public void noShapeFillsNothing() {
        assertTrue(RoadLookup.cellsAlong(null, 6.9, 79.9, 250).isEmpty());
        assertTrue(RoadLookup.cellsAlong(new double[0][], 6.9, 79.9, 250).isEmpty());
    }

    @Test public void aLimitOnlyHoldsNearWhereItWasConfirmedAndNotForLong() {
        assertTrue(RoadLookup.limitStillHolds(1000, 100));
        assertFalse("on down the road", RoadLookup.limitStillHolds(1000, 151));
        assertFalse("too old, even standing still", RoadLookup.limitStillHolds(RoadLookup.LIMIT_FRESH_MS, 0));
    }

    @Test public void aCrossingRoadDoesNotSuitTheCarsHeading() {
        assertTrue(RoadLookup.headingFits(90, 90));
        assertTrue("either way along the road", RoadLookup.headingFits(90, 270));
        assertTrue("wraps around north", RoadLookup.headingFits(5, 355));
        assertFalse(RoadLookup.headingFits(0, 90));
    }

    @Test public void cacheEntriesCarryTheirDirectionAndOldOnesHaveNone() {
        assertEquals(-1, RoadLookup.savedDirection("50|0|Galle Road|mapped"));
        assertEquals(87, RoadLookup.savedDirection("50|0|Galle Road|mapped|87"));
        assertEquals(-1, RoadLookup.savedDirection("50|0|Galle Road|mapped|x"));
    }

    @Test public void eachSquareKnowsTheWayTheRoadRunsThroughIt() {
        double[][] east = {{6.9, 79.85}, {6.9, 79.853}};
        java.util.Map<String, Integer> cells = RoadLookup.directionsAlong(east, 6.9, 79.85, 250);
        assertFalse(cells.isEmpty());
        for (int direction : cells.values()) assertEquals(90, direction);
        double[][] north = {{6.9, 79.85}, {6.903, 79.85}};
        for (int direction : RoadLookup.directionsAlong(north, 6.9, 79.85, 250).values()) assertEquals(0, direction);
    }
}
