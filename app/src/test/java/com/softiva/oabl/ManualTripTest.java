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

    @Test public void aDriveAlreadyUnderWayWhenTheTripStartedCountsForThePartInsideIt() {
        // The 2026-11-18 trip: START TRIP pressed 94 s into a 4236 s, 20 km drive.
        CarEnergyTracker.Event running = drive(START - 94_000, 20, 4236, 83, 77);
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(running), Collections.emptyList(),
                START, START + 4142_000, START + 4142_000, 42.3, 82, 77, Double.NaN, Double.NaN, "");
        assertEquals(1, s.drives);
        assertEquals(20 * 4142.0 / 4236, s.km, 1e-6);
        assertEquals(4236 * 4142.0 / 4236, s.movingSeconds, 1e-6);
        assertTrue(s.economy > 0);
    }

    @Test public void aDriveStillGoingWhenTheTripEndedCountsForThePartInsideIt() {
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(drive(START + 30 * MIN, 20, 3600, 80, 70)), Collections.emptyList(),
                START, START + 60 * MIN, START + 60 * MIN, 50, 80, 75, Double.NaN, Double.NaN, "");
        assertEquals(1, s.drives);
        assertEquals(10, s.km, 1e-9);
    }

    @Test public void aDriveThatFinishedAfterTheTripEndedIsLeftOutIfItStartedAfterToo() {
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(drive(START + 2 * HOUR, 20, 3600, 80, 70)), Collections.emptyList(),
                START, START + HOUR, START + HOUR, 50, 80, 80, Double.NaN, Double.NaN, "");
        assertEquals(0, s.drives);
    }

    @Test public void aGpsDriveUnderWayAtTheStartIsSharedToo() {
        ManualTrip.Summary s = ManualTrip.compute(Collections.emptyList(), Arrays.asList(gps(START - 10 * MIN, START + 50 * MIN, 12, 60, 70f)),
                START, 0, START + HOUR, 50, 80, 80, Double.NaN, Double.NaN, "");
        assertEquals(10, s.kmGps, 1e-9);
        assertEquals(10, s.km, 1e-9);
        ManualTrip.Chart chart = ManualTrip.chart(Arrays.asList(gps(START - 10 * MIN, START + 50 * MIN, 12, 60, 70f)), START, 0, START + HOUR, TimeZone.getTimeZone("UTC"));
        double total = 0; for (double v : chart.km) total += v;
        assertEquals(10, total, 1e-9);
    }

    @Test public void theReportListsEveryEventWithItsPositionAndTheBatteryUsed() {
        CarEnergyTracker.Event d = drive(START - 94_000, 20, 4236, 83, 77);
        d.latitude = 6.5; d.longitude = 79.5;
        d.tripStartKm = 3.5; d.tripStartKwh100 = 11.06; d.tripKm = 24.4; d.tripKwh100 = 10.94;
        CarEnergyTracker.Event stop = of("stop", START + 20 * MIN);
        stop.stoppedSeconds = 240; stop.modelKwh = 0.12; stop.latitude = 6.6; stop.longitude = 79.6;
        CarEnergyTracker.Event charge = of("charge", START + 50 * MIN);
        charge.before = 70; charge.after = 74; charge.latitude = 6.7; charge.longitude = 79.7;
        TripComputer.Trip g = gps(START - 90_000, START + 4_100_000, 17.7, 64, 68f);
        g.startLat = 6.5001; g.startLon = 79.5001; g.endLat = 6.8; g.endLon = 79.8;
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(d, stop, charge), Arrays.asList(g), START, START + 4142_000, START + 4142_000,
                42.3, 82, 77, 91, 0.5, "LKR");
        assertEquals(3, s.lines.size());
        ManualTrip.Line drive = s.lines.get(0);
        assertEquals("drive", drive.kind);
        assertTrue(drive.hasPlace() && drive.hasEndPlace());
        assertEquals(6.8, drive.endLat, 1e-9);
        assertEquals(1, s.stops);
        assertEquals(0.12, s.idleKwh, 1e-9);
        assertEquals(240, s.stopSeconds, 1e-9);
        // 5 points between the levels, plus 4 charged.
        assertEquals(9, s.batteryPoints, 1e-9);
        assertEquals(1, s.charges);
        // The trip computer's 20.9 km (to 0.1 km), not the odometer's whole 20, for the part inside the trip.
        assertEquals(20.9 * 4142.0 / 4236, s.km, 1e-6);
        assertTrue(s.overallKph > 0);
        String csv = TripReport.csv(s, START + 4142_000);
        assertTrue(csv.contains("6.500000,79.500000"));
        assertTrue(csv.contains("charge,"));
        assertTrue(csv.contains("stop,"));
        assertEquals(3, TripReport.rows(s, java.text.DateFormat.getTimeInstance(), 20).size());
    }

    @Test public void aDrivesEndIsWhereTheStopThatFollowedBegan() {
        CarEnergyTracker.Event d = drive(START, 5, 600, 80, 79);
        d.latitude = 6.1; d.longitude = 79.1;
        CarEnergyTracker.Event stop = of("stop", START + 600_000 + 3_000);
        stop.latitude = 6.2; stop.longitude = 79.2; stop.stoppedSeconds = 120;
        TripComputer.Trip early = gps(START, START + 300_000, 2, 5, 40f);
        early.endLat = 6.15; early.endLon = 79.15;
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(d, stop), Arrays.asList(early), START, 0, START + HOUR, 50, 80, 79, Double.NaN, Double.NaN, "");
        assertEquals(6.2, s.lines.get(0).endLat, 1e-9);
    }

    @Test public void highlightsSayWhatHappenedInPlainLines() {
        CarEnergyTracker.Event stop = of("stop", START + 20 * MIN);
        stop.stoppedSeconds = 240; stop.modelKwh = 0.12;
        ManualTrip.Summary s = ManualTrip.compute(Arrays.asList(drive(START, 20, 2400, 80, 74), stop), Collections.emptyList(),
                START, START + HOUR, START + HOUR, 50, 80, 74, 91, 0.5, "LKR");
        List<String> lines = TripReport.highlights(s, java.text.DateFormat.getTimeInstance());
        assertTrue(lines.get(0).startsWith("Drove 20.0 km"));
        assertTrue(lines.get(1).startsWith("Used 3.00 kWh"));
        assertTrue(lines.contains("No charging on this trip"));
        assertEquals(4, TripReport.heroes(s, START + HOUR).length);
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
