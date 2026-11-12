package com.softiva.oabl;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** The 24 October battery review: partial charges, today's capacity, rounding rises, late SOC, gaps, plugs. */
public class BatteryFixesTest {
    private static final long T0 = 1_000_000;
    private CarEnergyTracker.Frame frame(long seconds) {
        CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = T0 + seconds * 1000; f.idleKw = 1.2; f.capacity = 40; return f;
    }
    private CarEnergyTracker.Frame car(long seconds, double soc, double ready, double speed, double odo, double charging) {
        CarEnergyTracker.Frame f = frame(seconds);
        if (Double.isFinite(soc)) f.set(CarSignal.SOC, soc);
        return f.set(CarSignal.READY, ready).set(CarSignal.SPEED, speed).set(CarSignal.ODOMETER, odo).set(CarSignal.CHARGING, charging);
    }
    private CarEnergyTracker.Frame charging(long seconds, double soc, double kw) { return car(seconds, soc, 0, 0, 100, 1).set(CarSignal.POWER, kw); }
    private static CarEnergyTracker.Event find(List<CarEnergyTracker.Event> events, String kind) {
        for (CarEnergyTracker.Event e : events) if (e.kind.equals(kind)) return e;
        return null;
    }

    // ---- 1. Partly measured charges are not billed as complete ----

    @Test public void completeMeasurementIsBilledAsMeasured() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        for (int i = 0; i <= 3600; i++) t.accept(charging(i, 40 + i / 360.0, 3.6));
        CarEnergyTracker.Event done = find(t.accept(car(3601, 50, 0, 0, 100, 0)), "charge");
        assertTrue(CarChargeEnergy.complete(done));
        CarChargeEnergy.Estimate e = CarChargeEnergy.estimate(done, Double.NaN, 40);
        assertEquals(CarChargeEnergy.Basis.MEASURED, e.basis);
        assertEquals(3.6, e.kwh, 0.01);
    }

    @Test public void halfCoveredChargeIsExtrapolatedToItsWholeDuration() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        for (int i = 0; i <= 2000; i++) t.accept(charging(i, 40 + i / 360.0, 3.6));
        // About 27 minutes of charging with no samples (link lost), then the end is seen.
        t.accept(charging(3600, 50, 3.6));
        CarEnergyTracker.Event done = find(t.accept(car(3601, 50, 0, 0, 100, 0)), "charge");
        assertFalse(CarChargeEnergy.complete(done));
        assertEquals(2.0, done.measuredKwh(), 0.01);
        CarChargeEnergy.Estimate e = CarChargeEnergy.estimate(done, Double.NaN, 40);
        assertEquals(CarChargeEnergy.Basis.EXTRAPOLATED, e.basis);
        assertEquals(3.6, e.kwh, 0.02);
        EnergyModel.Charge receipt = new EnergyModel.Charge(); receipt.live = true; receipt.rate = 50;
        receipt.measuredKwh = done.measuredKwh(); receipt.measuredComplete = false; receipt.estimatedKwh = e.kwh;
        assertEquals(e.kwh * 50, receipt.money(Double.NaN), 0.001);
    }

    @Test public void smallCoverageFallsBackToLearnedPerPointThenCapacity() {
        CarEnergyTracker.Event e = new CarEnergyTracker.Event(); e.kind = "charge"; e.start = T0; e.end = T0 + 3_600_000;
        e.before = 40; e.after = 60; e.chargerKwh = 0.5; e.coveredSeconds = 600;
        CarChargeEnergy.Estimate learned = CarChargeEnergy.estimate(e, 0.45, 40);
        assertEquals(CarChargeEnergy.Basis.LEARNED_PER_POINT, learned.basis); assertEquals(9, learned.kwh, 1e-9);
        CarChargeEnergy.Estimate battery = CarChargeEnergy.estimate(e, Double.NaN, 40);
        assertEquals(CarChargeEnergy.Basis.CAPACITY, battery.basis); assertEquals(8, battery.kwh, 1e-9);
        assertEquals(CarChargeEnergy.Basis.NONE, CarChargeEnergy.estimate(e, Double.NaN, Double.NaN).basis);
    }

    @Test public void meterKwhStillOverridesEverything() {
        EnergyModel.Charge c = new EnergyModel.Charge(); c.live = true; c.rate = 40; c.kwh = 10; c.measuredKwh = 9; c.measuredComplete = true; c.estimatedKwh = 12;
        assertEquals(400, c.money(Double.NaN), 1e-9);
    }

    @Test public void gridPerPointLearnsOnlyFromCompleteChargesOfTenPoints() {
        List<EnergyModel.Charge> charges = new ArrayList<>();
        assertTrue(Double.isNaN(CarChargeEnergy.gridKwhPerPoint(charges)));
        EnergyModel.Charge partial = new EnergyModel.Charge(); partial.before = 20; partial.after = 60; partial.measuredKwh = 5; partial.measuredComplete = false;
        EnergyModel.Charge small = new EnergyModel.Charge(); small.before = 50; small.after = 55; small.measuredKwh = 2.3; small.measuredComplete = true;
        EnergyModel.Charge good = new EnergyModel.Charge(); good.before = 40; good.after = 60; good.measuredKwh = 9.4; good.measuredComplete = true;
        charges.add(partial); charges.add(small);
        assertTrue(Double.isNaN(CarChargeEnergy.gridKwhPerPoint(charges)));
        charges.add(good);
        assertEquals(0.47, CarChargeEnergy.gridKwhPerPoint(charges), 1e-9);
    }

    // ---- 2. Today's capacity, not the one in force when the drive happened ----

    @Test public void kwhUsesTheCapacityAskedFor() {
        CarEnergyTracker.Event drive = new CarEnergyTracker.Event(); drive.kind = "drive"; drive.before = 80; drive.after = 70; drive.capacity = Double.NaN;
        assertTrue(Double.isNaN(drive.usedKwh()));
        assertEquals(4.2, drive.usedKwh(42), 1e-9);
        CarEnergySummary summary = CarEnergySummary.of(drive);
        assertEquals(1, summary.energyDrives); assertEquals(10, summary.points, 0);
        assertEquals(4.2, summary.usedKwh(42), 1e-9);
        assertTrue(Double.isNaN(summary.usedKwh(Double.NaN)));
    }

    // ---- 3. A one-point rise across a gap is rounding, not a charge ----

    @Test public void onePointRiseAcrossAGapCreatesNoCharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 60, 0, 0, 100, 0));
        assertNull(find(t.accept(car(4000, 61, 0, 0, 100, 0)), "charge"));
        CarEnergyTracker.Event two = find(t.accept(car(9000, 63, 0, 0, 100, 0)), "charge");
        assertNotNull(two); assertTrue(two.inferred); assertEquals(2, two.gain(), 0);
    }

    @Test public void legacyOnePointCandidatesAreNotCountedOrPrompted() {
        CarEnergyTracker.Event legacy = new CarEnergyTracker.Event(); legacy.kind = "charge"; legacy.state = "complete"; legacy.inferred = true; legacy.before = 60; legacy.after = 61;
        assertFalse(CarChargeReviewPolicy.counts(legacy));
        assertFalse(CarChargeReviewPolicy.eligible(legacy, false));
        assertEquals(0, CarEnergySummary.of(legacy).charges);
        legacy.after = 62;
        assertFalse(CarChargeReviewPolicy.counts(legacy));
        legacy.externalCharge = true;
        assertTrue(CarChargeReviewPolicy.counts(legacy));
        assertEquals(1, CarEnergySummary.of(legacy).charges);
    }

    // ---- 6. A drive or charge that began without SOC takes the first reading ----

    @Test public void driveStartedWithoutSocTakesTheFirstReadingAndItsRange() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, Double.NaN, 1, 0, 100, 0));
        assertTrue(Double.isNaN(s.drive.before));
        t.accept(car(2, 80, 1, 20, 100, 0).set(CarSignal.RANGE_EST, 300));
        assertEquals(80, s.drive.before, 0); assertEquals(300, s.drive.rangeStart, 0);
        t.accept(car(600, 78, 1, 40, 105, 0));
        assertEquals(2, s.drive.points(), 0);
    }

    @Test public void chargeStartedWithoutSocTakesTheFirstReading() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, Double.NaN, 0, 0, 100, 1));
        t.accept(car(1, 45, 0, 0, 100, 1));
        CarEnergyTracker.Event done = find(t.accept(car(2, 46, 0, 0, 100, 0)), "charge");
        assertEquals(1, done.gain(), 0);
    }

    // ---- Charging first seen after a gap keeps the unobserved part ----

    @Test public void chargeFirstSeenAfterAGapStartsFromTheLastReadingBeforeIt() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 40, 0, 0, 100, 0));
        t.accept(charging(3600, 55, 3.6));
        for (int i = 3601; i <= 4200; i++) t.accept(charging(i, 55 + (i - 3600) / 360.0, 3.6));
        CarEnergyTracker.Event done = find(t.accept(car(4201, 57, 0, 0, 100, 0)), "charge");
        assertEquals(40, done.before, 0); assertEquals(17, done.gain(), 0);
        assertEquals(T0, done.start); assertTrue(done.gap);
        assertFalse(CarChargeEnergy.complete(done));
        // About 10 % of the time was measured: the estimate comes from the points gained instead.
        assertEquals(CarChargeEnergy.Basis.CAPACITY, CarChargeEnergy.estimate(done, Double.NaN, 40).basis);
    }

    @Test public void ordinaryPlugInDoesNotStretchAMeasuredCharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 40, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        t.accept(car(20, 40, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        for (int i = 21; i <= 600; i++) t.accept(charging(i, 40 + (i - 21) / 360.0, 3.6).set(CarSignal.PLUG, 1));
        CarEnergyTracker.Event done = find(t.accept(car(601, 41, 0, 0, 100, 0).set(CarSignal.PLUG, 1)), "charge");
        assertEquals(T0 + 21_000, done.start);
        assertTrue(CarChargeEnergy.complete(done));
    }

    // ---- Plugged in, but the car never says "charging" (an unmapped state, e.g. DC) ----

    @Test public void plugAndBatteryRiseWithoutChargingStateBecomesAPossibleCharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        for (int i = 0; i <= 1800; i += 5) t.accept(car(i, 30 + i / 60, 0, 0, 100, 0).set(CarSignal.PLUG, 1).set(CarSignal.CHARGE_STATE_RAW, 3));
        CarEnergyTracker.Event possible = find(t.accept(car(1805, 60, 0, 0, 100, 0).set(CarSignal.PLUG, 0)), "charge");
        assertNotNull(possible); assertTrue(possible.inferred); assertEquals(30, possible.gain(), 0);
        assertNull(s.plug);
    }

    @Test public void pluggedWithoutRiseLeavesNothing() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 80, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        t.accept(car(60, 81, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        assertNull(find(t.accept(car(61, 81, 0, 0, 100, 0).set(CarSignal.PLUG, 0)), "charge"));
    }

    @Test public void plugStateSurvivesACheckpoint() throws Exception {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 30, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        CarEnergyTracker.State restored = CarEnergyCodec.state(CarEnergyCodec.state(s));
        assertNotNull(restored.plug); assertEquals(30, restored.plug.before, 0);
        CarEnergyTracker t2 = new CarEnergyTracker(restored);
        t2.accept(car(60, 40, 0, 0, 100, 0).set(CarSignal.PLUG, 1));
        assertNotNull(find(t2.accept(car(61, 40, 0, 0, 100, 0).set(CarSignal.PLUG, 0)), "charge"));
    }

    // ---- Charger location: any fix during the charge, never from a backfilled sample ----

    @Test public void chargeLocationIsTakenFromAnyFixWhileCharging() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(charging(0, 40, 3.6));
        CarEnergyTracker.Frame located = charging(120, 41, 3.6); located.latitude = 6.9; located.longitude = 79.9;
        t.accept(located);
        assertEquals(6.9, s.charge.latitude, 0);
    }

    @Test public void clockOffsetSeparatesLiveSamplesFromCatchUp() {
        EnergyCarSink.Clock clock = new EnergyCarSink.Clock();
        // The head unit runs 40 s behind the box: every live sample has a 40 s lag.
        assertTrue(clock.live(T0, T0 + 40_000));
        assertTrue(clock.live(T0 + 1000, T0 + 41_200));
        // A catch-up sample from ten minutes ago is not live.
        assertFalse(clock.live(T0 - 600_000, T0 + 42_000));
    }

    // ---- Trip readings of 0 while the cluster is off ----

    @Test public void chargeKeepsTripCFromTheLastReadyReading() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 60, 1, 20, 100, 0).set(CarSignal.TRIP_C_KM, 41).set(CarSignal.TRIP_C_KWH100, 11.13));
        t.accept(car(30, 60, 0, 0, 100, 0).set(CarSignal.TRIP_C_KM, 0).set(CarSignal.TRIP_C_KWH100, 0));
        t.accept(charging(31, 60, 2.6).set(CarSignal.TRIP_C_KM, 0));
        assertEquals(41, s.charge.tripCKm, 0); assertEquals(11.13, s.charge.tripCKwh100, 0);
    }

    @Test public void tripAZeroForOneReadingDoesNotSplitADrive() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(car(0, 80, 1, 40, 100, 0).set(CarSignal.TRIP_KM, 12.3));
        CarEnergyTracker.Event before = s.drive;
        t.accept(car(1, 80, 1, 40, 100.01, 0).set(CarSignal.TRIP_KM, 0));
        t.accept(car(2, 80, 1, 40, 100.02, 0).set(CarSignal.TRIP_KM, 12.3));
        assertSame(before, s.drive); assertEquals("open", s.drive.state);
    }

    // ---- Display helpers ----

    @Test public void timeToFullFormatsMinutesAndHidesUnusableValues() {
        assertEquals("45 min", CarChargeTime.remaining(45));
        assertEquals("5 h 54 min", CarChargeTime.remaining(354));
        assertEquals("2 h", CarChargeTime.remaining(120));
        assertEquals("", CarChargeTime.remaining(0));
        assertEquals("", CarChargeTime.remaining(Double.NaN));
        assertEquals("", CarChargeTime.remaining(65535));
    }

    @Test public void homeChargeDetailShowsOnlyWhatTheCarReports() {
        assertEquals("2.6 kW · to 95 % · full in 5 h 54 min (car's estimate)", HomeEnergySnapshot.chargeDetail(2.6, 95, 354));
        assertEquals("to 95 %", HomeEnergySnapshot.chargeDetail(Double.NaN, 95, Double.NaN));
        assertEquals("Drive comparison resumes on the next drive", HomeEnergySnapshot.chargeDetail(Double.NaN, Double.NaN, Double.NaN));
    }
}
