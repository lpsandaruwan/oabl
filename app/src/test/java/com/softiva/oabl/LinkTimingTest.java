package com.softiva.oabl;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Head-unit timestamps measured on the box clock, and drives made while nothing was received. */
public class LinkTimingTest {
    private static CarLinkProtocol.Now now(long stamp, double speed) {
        CarLinkProtocol.Now now = new CarLinkProtocol.Now(); now.time = stamp;
        now.values.put(CarSignal.SPEED, speed); now.times.put(CarSignal.SPEED, stamp);
        return now;
    }

    @Test public void headUnitBehindTheBoxStillReadsAsFresh() {
        CarLink link = new CarLink();
        long box = 10_000_000, headUnit = box - 95_000; // the head unit's clock is 95 s behind
        link.ingestNow(now(headUnit, 42), box + 200);
        assertEquals(box, link.latestTime(CarSignal.SPEED), 250);
    }

    @Test public void headUnitAheadOfTheBoxDoesNotMakeOldReadingsLookNew() {
        CarLink link = new CarLink();
        long box = 10_000_000, ahead = 120_000;
        link.ingestNow(now(box + ahead, 40), box + 100);
        // Ten seconds later the same reading is ten seconds old on the box clock.
        assertEquals(box, link.latestTime(CarSignal.SPEED), 200);
        assertTrue(box + 10_000 - link.latestTime(CarSignal.SPEED) >= 9_800);
    }

    @Test public void slowerDeliveriesDoNotShiftTheOffset() {
        CarClockOffset clock = new CarClockOffset();
        clock.observe(1_000, 41_000);   // 40 s offset, fast transfer
        clock.observe(2_000, 44_500);   // a slow 2.5 s transfer
        assertEquals(40_000, clock.offsetMs());
        assertEquals(43_000, clock.toLocal(3_000));
    }

    @Test public void beforeAnySnapshotTimesAreUnchanged() {
        assertEquals(5_000, new CarClockOffset().toLocal(5_000));
        assertEquals(0, new CarClockOffset().toLocal(0));
    }

    @Test public void orderingStillUsesHeadUnitTime() {
        CarLink link = new CarLink();
        link.ingestNow(now(2_000, 50), 3_000);
        link.ingestNow(now(1_000, 10), 3_100); // older reading arriving late
        assertEquals(50, link.latest(CarSignal.SPEED), 0);
    }

    // ---- Drives with no live data, from the odometer ----

    private CarEnergyTracker.Frame car(long seconds, double soc, double ready, double odo) {
        CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = 1_000_000 + seconds * 1000; f.capacity = 40;
        return f.set(CarSignal.SOC, soc).set(CarSignal.READY, ready).set(CarSignal.SPEED, 0).set(CarSignal.ODOMETER, odo).set(CarSignal.CHARGING, 0);
    }
    private static CarEnergyTracker.Event offline(List<CarEnergyTracker.Event> events) {
        for (CarEnergyTracker.Event e : events) if (e.key.startsWith("offline-drive:")) return e;
        return null;
    }

    @Test public void driveWhileLinkWasDownIsRecordedFromTheOdometer() {
        CarEnergyTracker t = new CarEnergyTracker(new CarEnergyTracker.State());
        t.accept(car(0, 80, 0, 3851));
        CarEnergyTracker.Event e = offline(t.accept(car(3600, 74, 0, 3871.4)));
        assertNotNull(e);
        assertEquals("drive", e.kind); assertEquals(20.4, e.km, 1e-9); assertEquals(6, e.points(), 0);
        assertTrue(e.inferred); assertEquals("car odometer · no live data", e.distanceSource);
        assertEquals(20.4, e.gapKm, 1e-9); assertEquals(3600, e.gapSeconds, 0);
    }

    @Test public void chargeInBetweenKeepsTheDistanceButNotTheBattery() {
        CarEnergyTracker t = new CarEnergyTracker(new CarEnergyTracker.State());
        t.accept(car(0, 50, 0, 3851));
        CarEnergyTracker.Event e = offline(t.accept(car(20_000, 90, 0, 3880)));
        assertNotNull(e); assertEquals(29, e.km, 1e-9); assertTrue(Double.isNaN(e.points()));
    }

    @Test public void parkedGapOrOdometerFaultIsNotADrive() {
        CarEnergyTracker t = new CarEnergyTracker(new CarEnergyTracker.State());
        t.accept(car(0, 80, 0, 3851));
        assertNull(offline(t.accept(car(3600, 80, 0, 3851.2))));
        assertNull(offline(t.accept(car(7200, 80, 0, 3000))));      // backwards
        assertNull(offline(t.accept(car(7300, 80, 0, 9000))));      // impossible in 100 s
    }

    @Test public void rejoinedDriveIsNotCountedTwice() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 80, 1, 3851).set(CarSignal.SPEED, 40));
        assertNull(offline(t.accept(car(300, 79, 1, 3853).set(CarSignal.SPEED, 40))));
        assertEquals(2, s.drive.km, 1e-9);
    }
}
