package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BatteryFactsTest {
    @Test public void zeroMeansTheCarDoesNotWireIt() {
        assertTrue(Double.isNaN(BatteryFacts.reported(0)));
        assertTrue(Double.isNaN(BatteryFacts.reported(Double.NaN)));
        assertEquals(312, BatteryFacts.reported(312), 0);
    }

    @Test public void timeToFullIsMinutesAndUnknownMarkersAreDropped() {
        assertEquals(293, BatteryFacts.minutes(293), 0);
        assertTrue(Double.isNaN(BatteryFacts.minutes(0)));
        assertTrue(Double.isNaN(BatteryFacts.minutes(4095)));
    }

    @Test public void healthIsTenthsOfAPercent() {
        assertEquals(100, BatteryFacts.health(1000), 1e-9);
        assertEquals(97.5, BatteryFacts.health(975), 1e-9);
        assertTrue(Double.isNaN(BatteryFacts.health(0)));
        assertTrue(Double.isNaN(BatteryFacts.health(1001)));
    }

    @Test public void efficiencyNeedsACapacityAndLearnedChargesAndStaysBelievable() {
        BatteryFacts f = new BatteryFacts();
        f.usableKwh = 42; f.wallKwhPerPoint = 0.47;
        assertEquals(0.894, f.chargingEfficiency(), 0.001);
        f.wallKwhPerPoint = Double.NaN;
        assertTrue(Double.isNaN(f.chargingEfficiency()));
        f.wallKwhPerPoint = 0.2;   // would be 210 %: a wrong capacity, not an efficiency
        assertTrue(Double.isNaN(f.chargingEfficiency()));
    }
}
