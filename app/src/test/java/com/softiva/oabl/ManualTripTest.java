package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class ManualTripTest {
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long START = 1_700_000_000_000L - 1_700_000_000_000L % DAY + 8 * HOUR;

    private static CarEnergyTracker.Event drive(long start, double km, double seconds, double before, double after) {
        CarEnergyTracker.Event e = new CarEnergyTracker.Event();
        e.kind = "drive";
        e.start = start;
        e.end = start + (long) (seconds * 1000);
        e.km = km;
        e.movingSeconds = seconds;
        e.before = before;
        e.after = after;
        e.capacity = 50;
        e.peakSpeed = 60;
        e.regenKwh = 0.4;
        return e;
    }

    private static CarEnergyTracker.Event of(String kind, long start) {
        CarEnergyTracker.Event e = new CarEnergyTracker.Event();
        e.kind = kind;
        e.start = start;
        e.end = start + 10 * MIN;
        return e;
    }

    private static TripComputer.Trip gps(long start, long end, double km, double minutes, float peak) {
        TripComputer.Trip t = new TripComputer.Trip();
        t.start = start; t.end = end; t.kilometers = km; t.minutes = minutes; t.peakKph = peak;
        return t;
    }

    @Test public void twoDrivesAddUpAndStopsAndChargesAreCounted() {
        CarEnergyTracker.Event stop = of("stop", START + 40 * MIN);
        stop.modelKwh = 0.3;
        stop.stoppedSeconds = 1800;
        CarEnergyTracker.Event charge = of("charge", START + 3 * HOUR);
        charge.before = 40; charge.after = 60;
        List<CarEnergyTracker.Event> events = Arrays.asList(
                drive(START + 10 * MIN, 10, 1200, 80, 77), drive(START + 2 * HOUR, 20, 2400, 77, 70), stop, charge);
        ManualTrip.Summary s = ManualTrip.compute(events, Collections.emptyList(), START, 0, START + 5 * HOUR, 50, 80, 55, 91, 0.5, "LKR");
        assertTrue(s.active);
        assertEquals(30, s.km, 1e-9);
        assertEquals(2, s.drives);
        assertEquals(5.0, s.kwh, 1e-9);
        assertEquals(5.0 / 30 * 100, s.economy, 1e-9);
        assertEquals(0.8, s.regenKwh, 1e-9);
        assertEquals(0.3, s.idleKwh, 1e-9);
        assertEquals(1, s.charges);
        assertEquals(10, s.chargedKwh, 1e-9);
        assertEquals(60, s.peakKph, 1e-9);
        assertEquals(3600, s.movingSeconds, 1e-9);
        assertEquals(30.0, s.averageKph, 1e-9);
        assertEquals(5 * 3600 - 3600, s.stoppedSeconds, 1e-9);
        assertEquals(5 * 3600, s.elapsedSeconds(START + 5 * HOUR), 1e-9);
        assertEquals(5.0 * 91, s.cost, 1e-6);
    }

    @Test public void aDriveThatStartedBeforeTheTripIsLeftOut() {
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(drive(START - HOUR, 50, 3000, 90, 80)), Collections.emptyList(),
                START, 0, START + HOUR, 50, 80, 80, Double.NaN, Double.NaN, "");
        assertEquals(0, s.drives);
        assertTrue(Double.isNaN(s.kwh) || s.kwh == 0);
    }

    @Test public void gpsGivesDistanceAndTimeWhenTheCarLogIsEmpty() {
        ManualTrip.Summary s = ManualTrip.compute(Collections.emptyList(),
                Arrays.asList(gps(START + 5 * MIN, START + 65 * MIN, 42, 60, 88f), gps(START - DAY, START - DAY + HOUR, 99, 60, 120f)),
                START, START + 2 * HOUR, START + 3 * HOUR, 50, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "");
        assertEquals(42, s.km, 1e-9);
        assertEquals(3600, s.movingSeconds, 1e-9);
        assertEquals(42, s.averageKph, 1e-9);
        assertEquals(88, s.peakKph, 1e-9);
        assertEquals(2 * 3600, s.elapsedSeconds(START + 5 * HOUR), 1e-9);
        assertTrue(!s.active);
    }

    @Test public void theBatteryLevelsGiveEnergyWhenNothingElseDoes() {
        ManualTrip.Summary s = ManualTrip.compute(Collections.emptyList(),
                Arrays.asList(gps(START + MIN, 0, 20, 30, 70f)), START, 0, START + HOUR, 50, 90, 80, Double.NaN, Double.NaN, "");
        assertEquals(5.0, s.kwh, 1e-9);
        assertEquals(25.0, s.economy, 1e-9);
    }

    @Test public void aTripWithNothingHasNoDistanceYet() {
        ManualTrip.Summary s = ManualTrip.compute(Collections.emptyList(), Collections.emptyList(), START, 0, START + 10 * MIN, 50, 80, 80, Double.NaN, Double.NaN, "");
        assertTrue(Double.isNaN(s.km));
        assertTrue(Double.isNaN(s.economy));
        assertEquals(0, s.drives);
    }

    @Test public void shortTripsChartByTheHourAndLongOnesByTheDay() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        List<TripComputer.Trip> gps = new ArrayList<>();
        gps.add(gps(START + 30 * MIN, START + 150 * MIN, 60, 120, 80f));
        ManualTrip.Chart hours = ManualTrip.chart(gps, START, 0, START + 4 * HOUR, utc);
        assertTrue(hours.hourly);
        assertEquals(4, hours.km.length);
        assertEquals(15, hours.km[0], 1e-9);
        assertEquals(30, hours.km[1], 1e-9);
        assertEquals(15, hours.km[2], 1e-9);
        assertEquals(0, hours.km[3], 1e-9);
        assertEquals(60, hours.km[0] + hours.km[1] + hours.km[2], 1e-9);

        gps.clear();
        gps.add(gps(START + DAY, START + DAY + HOUR, 10, 60, 60f));
        gps.add(gps(START + 3 * DAY, START + 3 * DAY + HOUR, 20, 60, 60f));
        ManualTrip.Chart days = ManualTrip.chart(gps, START, START + 4 * DAY, START + 5 * DAY, utc);
        assertTrue(!days.hourly);
        assertTrue(days.km.length >= 4 && days.km.length <= 6);
        double total = 0;
        for (double km : days.km) total += km;
        assertEquals(30, total, 1e-9);
        assertEquals(days.km.length, days.names.length);
    }
}
