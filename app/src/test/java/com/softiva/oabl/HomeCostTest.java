package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

/** Home's header money: today's cost and kWh/100 km, and the car-on session's cost. */
public class HomeCostTest {
    private static CarEnergySummary today(double km, double tripKwh) {
        CarEnergySummary s = new CarEnergySummary();
        s.km = km; s.tripKwh = tripKwh; s.tripEnergyKm = km; s.tripDrives = 1; s.energyDrives = 1; s.drives = 1;
        return s;
    }
    private static CarEnergyTracker.Event drive(boolean open, long start, double km, double kwh) {
        CarEnergyTracker.Event e = new CarEnergyTracker.Event();
        e.kind = "drive"; e.state = open ? "open" : "closed"; e.start = start; e.km = km;
        e.tripStartKm = 0; e.tripStartKwh100 = 0; e.tripKm = km; e.tripKwh100 = kwh / km * 100;
        return e;
    }

    @Test public void dayEconomyAndCostFollowTheEnergyPageMaths() {
        HomeCost c = HomeCost.of(today(40, 6), null, 0, 40, 30, Double.NaN, "LKR");
        assertEquals(15, c.dayPer100, 1e-9);
        assertEquals(6, c.dayKwh, 1e-9);
        assertEquals(180, c.dayCost, 1e-9);
        assertEquals("LKR 180", c.dayCostText());
        assertEquals("15.0 kWh/100 km", c.dayEconomyText());
        assertFalse(c.hasSession);
    }

    @Test public void chargingLossesRaiseTheCostNotTheEconomy() {
        // 40 kWh pack, 0.5 kWh per battery % from the wall = 20 kWh grid per 40 % -> factor 1.25
        HomeCost c = HomeCost.of(today(40, 6), null, 0, 40, 30, 0.5, "LKR");
        assertEquals(15, c.dayPer100, 1e-9);
        assertEquals(7.5, c.dayKwh, 1e-9);
        assertEquals(225, c.dayCost, 1e-9);
    }

    @Test public void unknownPriceOrNoDrivingShowsDashesNotZero() {
        HomeCost noPrice = HomeCost.of(today(40, 6), null, 0, 40, Double.NaN, Double.NaN, "LKR");
        assertEquals("—", noPrice.dayCostText());
        assertEquals("15.0 kWh/100 km", noPrice.dayEconomyText());
        HomeCost nothing = HomeCost.of(new CarEnergySummary(), null, 0, 40, 30, Double.NaN, "LKR");
        assertEquals("—", nothing.dayCostText());
        assertEquals("—", nothing.dayEconomyText());
        assertEquals("—", nothing.sessionCostText());
    }

    @Test public void shortDistanceDoesNotInventAnEconomy() {
        assertEquals("—", HomeCost.of(today(0.4, 0.2), null, 0, 40, 30, Double.NaN, "LKR").dayEconomyText());
        // 3 km on 0.12 kWh would read 4 kWh/100 km: not shown, but the cost of that energy still is.
        HomeCost threeKm = HomeCost.of(today(3, 0.12), null, 0, 40, 30, Double.NaN, "LKR");
        assertEquals("—", threeKm.dayEconomyText());
        assertEquals("LKR 4", threeKm.dayCostText());
        assertEquals("5 km is enough", "15.0 kWh/100 km", HomeCost.of(today(5, 0.75), null, 0, 40, 30, Double.NaN, "LKR").dayEconomyText());
    }

    @Test public void openSessionCostsItsOwnEnergy() {
        HomeCost c = HomeCost.of(today(40, 6), drive(true, 1000, 10, 1.5), 0, 40, 30, Double.NaN, "LKR");
        assertTrue(c.hasSession); assertTrue(c.sessionOpen);
        assertEquals(10, c.sessionKm, 1e-9);
        assertEquals(1.5, c.sessionKwh, 1e-9);
        assertEquals(45, c.sessionCost, 1e-9);
        assertEquals("LKR 45", c.sessionCostText());
        assertEquals("1.5 kWh", c.sessionEnergyText());
    }

    @Test public void finishedSessionFromTodayIsKeptButYesterdaysIsNot() {
        long dayStart = 100_000;
        HomeCost last = HomeCost.of(today(40, 6), drive(false, dayStart + 5, 10, 1.5), dayStart, 40, 30, Double.NaN, "LKR");
        assertTrue(last.hasSession); assertFalse(last.sessionOpen);
        HomeCost old = HomeCost.of(today(40, 6), drive(false, dayStart - 5, 10, 1.5), dayStart, 40, 30, Double.NaN, "LKR");
        assertFalse(old.hasSession);
        // A drive still open across midnight is the current session whatever day it began.
        assertTrue(HomeCost.of(today(40, 6), drive(true, dayStart - 5, 10, 1.5), dayStart, 40, 30, Double.NaN, "LKR").hasSession);
    }

    @Test public void linkDotIsOnlineOrOffline() {
        HomeEnergySnapshot s = new HomeEnergySnapshot();
        assertEquals(HomeEnergySnapshot.LINK_OFFLINE, s.linkStatus());
        s.connected = true;
        assertEquals("connected but no data yet", HomeEnergySnapshot.LINK_ONLINE, s.linkStatus());
        s.live = true; s.linkState = "Connected · Bluetooth · encrypted · syncing history";
        assertEquals(HomeEnergySnapshot.LINK_ONLINE, s.linkStatus());
        s.connected = false;
        assertEquals(HomeEnergySnapshot.LINK_OFFLINE, s.linkStatus());
    }
}
