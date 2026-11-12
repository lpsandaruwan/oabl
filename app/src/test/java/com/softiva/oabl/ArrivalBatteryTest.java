package com.softiva.oabl;

import org.junit.Test;

import static org.junit.Assert.*;

public class ArrivalBatteryTest {
    @Test public void estimatesFromLearnedConsumptionAndCapacity() {
        ArrivalBattery.Result result = ArrivalBattery.estimate(68, 52, .14, 31.4, 10);
        assertNotNull(result);
        assertEquals(44.8, result.percent, .1);
        assertFalse(result.belowReserve);
    }

    @Test public void identifiesAChargeStopBelowReserve() {
        ArrivalBattery.Result result = ArrivalBattery.estimate(24, 40, .15, 31.4, 10);
        assertNotNull(result);
        assertEquals(4.9, result.percent, .1);
        assertTrue(result.belowReserve);
    }

    @Test public void missingOrImplausibleInputsNeverInventAnArrival() {
        assertNull(ArrivalBattery.estimate(Double.NaN, 20, .14, 31.4, 10));
        assertNull(ArrivalBattery.estimate(60, Double.NaN, .14, 31.4, 10));
        assertNull(ArrivalBattery.estimate(60, 20, 0, 31.4, 10));
        assertNull(ArrivalBattery.estimate(60, 20, .14, Double.NaN, 10));
        assertNull(ArrivalBattery.estimate(60, 20, 1.2, 31.4, 10));
    }

    @Test public void parsesRouteDistanceSeparatelyFromTurnDistance() {
        NavigationGuidance guidance = NavigationGuidance.read(
                new NavigationHintParser.Text("300 m", "Turn right onto Galle Road", "14.2 km · 18 min · 20:15 ETA"));
        assertEquals(300, guidance.meters, .01);
        assertEquals(14.2, guidance.remainingKilometers(), .01);
    }
}
