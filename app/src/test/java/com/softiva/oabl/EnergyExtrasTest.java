package com.softiva.oabl;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Modelled regen, trip cost, the 12 V reading and AC time. */
public class EnergyExtrasTest {
    // ---- Regen ----

    @Test public void brakingFromSixtyRecoversAPlausibleAmount() {
        // 60 → 30 km/h in 4 s: about 135 kJ of kinetic energy, minus road load, × 65 %.
        double kwh = CarRegenModel.kwh(60, 30, 4);
        assertTrue(kwh > 0.015 && kwh < 0.03);
    }

    @Test public void slowingHarderThanTheMotorCanIsFrictionBraking() {
        // 2.2 and 4.4 m/s²: both are over the motor's limit, so the harder stop recovers no more.
        double firm = CarRegenModel.kwh(50, 42, 1), hard = CarRegenModel.kwh(50, 34, 1);
        assertEquals(firm, hard, firm * 0.25);
        double limit = CarRegenModel.MAX_DECEL_MS2 * CarRegenModel.MASS_KG * (46 / 3.6) * CarRegenModel.EFFICIENCY / 3_600_000;
        assertEquals(limit, firm, 1e-9);
        assertTrue(hard < firm);
    }

    @Test public void regenFadesOutAtLowSpeed() {
        assertEquals(0, CarRegenModel.kwh(5, 4, 1), 0);
        double fading = CarRegenModel.kwh(10, 7, 1), full = CarRegenModel.kwh(16, 13, 1);
        assertTrue(fading > 0 && fading < full);
    }

    @Test public void regenBuildsUpOverTheFirstSecondsOfSlowing() {
        double first = CarRegenModel.kwh(60, 55, 1, 0), later = CarRegenModel.kwh(60, 55, 1, 2);
        assertTrue(first > 0 && first < later * 0.6);
        assertEquals(later, CarRegenModel.kwh(60, 55, 1), 0);
    }

    @Test public void aBrakePressBeforeRegenHasBuiltUpIsFrictionBraking() {
        assertEquals(0, CarRegenModel.kwh(60, 55, 1, 0, true), 0);
        assertEquals(0, CarRegenModel.kwh(60, 55, 1, 1, true), 0);
        assertEquals("built up: the motor keeps its share under the pedal",
                CarRegenModel.kwh(60, 55, 1, 2, false), CarRegenModel.kwh(60, 55, 1, 2, true), 0);
    }

    @Test public void trackerUsesTheBrakePedalAndWorksWithoutIt() {
        double[] speeds = {60, 55, 50, 45, 40};
        double without = regenOf(speeds, Double.NaN), lifted = regenOf(speeds, 0), pressed = regenOf(speeds, 1);
        assertEquals("a Link that doesn't send the pedal", lifted, without, 0);
        assertTrue(pressed > 0 && pressed < lifted);
    }

    private static double regenOf(double[] speeds, double pedal) {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        for (int i = 0; i < speeds.length; i++) {
            CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = 1_000_000 + i * 1000L;
            f.set(CarSignal.SOC, 80).set(CarSignal.READY, 1).set(CarSignal.SPEED, speeds[i]).set(CarSignal.ODOMETER, 100 + i * 0.015);
            if (!Double.isNaN(pedal)) f.set(CarSignal.BRAKE_PEDAL, pedal);
            t.accept(f);
        }
        return s.drive.regenKwh;
    }

    @Test public void slowingTimeRunsDownOnARepeatedSpeedAndResetsOnSpeedingUp() {
        assertEquals(1.5, CarRegenModel.slowingAfter(1, 50, 48, 0.5), 1e-9);
        assertEquals(2.5, CarRegenModel.slowingAfter(3, 50, 50, 0.5), 1e-9);
        assertEquals(0, CarRegenModel.slowingAfter(1, 50, 50, 5), 0);
        assertEquals(0, CarRegenModel.slowingAfter(3, 50, 52, 1), 0);
        assertEquals(0, CarRegenModel.slowingAfter(3, 50, 40, 30), 0);
    }

    @Test public void aSmallChangeInFinerSpeedIsHoldingSpeed() {
        assertEquals("a tiny drop runs the time down", 2, CarRegenModel.slowingAfter(3, 50.2, 50.1, 1), 1e-9);
        assertEquals("a tiny rise doesn't reset it", 2, CarRegenModel.slowingAfter(3, 50.1, 50.2, 1), 1e-9);
        assertEquals("real slowing still counts", 4, CarRegenModel.slowingAfter(3, 50.6, 49.9, 1), 1e-9);
        assertEquals("real speeding up still resets", 0, CarRegenModel.slowingAfter(3, 49.9, 50.6, 1), 0);
    }

    @Test public void aWobbleInFinerSpeedRecoversNothing() {
        assertEquals(0, CarRegenModel.kwh(40.3, 40.1, 1), 0);
        assertTrue("whole numbers saw a drop", CarRegenModel.kwh(41, 40, 1) > 0);
    }

    @Test public void trackerKeepsSlowingTimeAcrossARestart() throws Exception {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); s.slowingFor = 2.5;
        assertEquals(2.5, CarEnergyCodec.state(CarEnergyCodec.state(s)).slowingFor, 1e-9);
    }

    @Test public void coastingAndAcceleratingRecoverNothing() {
        assertEquals(0, CarRegenModel.kwh(50, 49.9, 1), 0);   // road load alone explains a gentle slowdown
        assertEquals(0, CarRegenModel.kwh(30, 40, 1), 0);
        assertEquals(0, CarRegenModel.kwh(5, 0, 1), 0);       // below the fade speed
        assertEquals(0, CarRegenModel.kwh(80, 40, 30), 0);    // too long an interval to tell
        assertEquals(0, CarRegenModel.kwh(Double.NaN, 40, 1), 0);
    }

    private static CarEnergyTracker.Frame tripFrame(long time, double speed, double odo, double tripKm, double tripKwh100) {
        CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = time;
        f.set(CarSignal.SOC, 60).set(CarSignal.READY, 1).set(CarSignal.SPEED, speed).set(CarSignal.ODOMETER, odo).set(CarSignal.CHARGING, 0)
                .set(CarSignal.TRIP_KM, tripKm).set(CarSignal.TRIP_KWH100, tripKwh100);
        return f;
    }

    @Test public void driveNetEnergyComesFromTripA() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(tripFrame(1_000_000, 30, 4119, 5.4, 11.06));
        t.accept(tripFrame(1_001_000, 32, 4119, 5.4, 11.06));
        t.accept(tripFrame(1_600_000, 40, 4123, 9.6, 10.94));
        CarEnergyTracker.Event d = s.drive;
        assertEquals(5.4, d.tripStartKm, 0); assertEquals(11.06, d.tripStartKwh100, 0);
        // 9.6 × 10.94 − 5.4 × 11.06, over 100 = 0.4529 kWh
        assertEquals(0.4529, d.tripNetKwh(), 1e-4);
        assertEquals(0.4529, d.netKwh(31.45), 1e-4);
    }

    @Test public void tripResetOrMissingStartFallsBackToSocPoints() {
        CarEnergyTracker.Event d = new CarEnergyTracker.Event(); d.before = 60; d.after = 58;
        d.tripKm = 3; d.tripKwh100 = 12;
        assertTrue(Double.isNaN(d.tripNetKwh()));
        assertEquals(0.629, d.netKwh(31.45), 1e-3);
        d.tripStartKm = 9; d.tripStartKwh100 = 11;           // trip A reset during the drive
        assertTrue(Double.isNaN(d.tripNetKwh()));
    }

    @Test public void tripStartSurvivesTheEventCodec() throws Exception {
        CarEnergyTracker.Event d = new CarEnergyTracker.Event(); d.key = "drive:1"; d.kind = "drive";
        d.tripStartKm = 5.4; d.tripStartKwh100 = 11.06; d.tripKm = 9.6; d.tripKwh100 = 10.94;
        CarEnergyTracker.Event back = CarEnergyCodec.event(CarEnergyCodec.event(d));
        assertEquals(d.tripNetKwh(), back.tripNetKwh(), 1e-9);
    }

    @Test public void hardStopsAreCappedByMotorPower() {
        double hard = CarRegenModel.kwh(100, 8, 1);
        assertTrue(hard <= CarRegenModel.MAX_REGEN_KW * CarRegenModel.EFFICIENCY / 3600 + 1e-12);
    }

    @Test public void trackerAddsRegenOnlyWhileDriving() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        double[] speeds = {60, 55, 50, 45, 40, 35, 30};
        for (int i = 0; i < speeds.length; i++) {
            CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = 1_000_000 + i * 1000L;
            f.set(CarSignal.SOC, 80).set(CarSignal.READY, 1).set(CarSignal.SPEED, speeds[i]).set(CarSignal.ODOMETER, 100 + i * 0.013).set(CarSignal.CHARGING, 0);
            t.accept(f);
        }
        assertTrue(s.drive.regenKwh > 0.01);
    }

    // ---- Trip cost ----

    private static EnergyModel.Charge charge(double kwh, double rate) {
        EnergyModel.Charge c = new EnergyModel.Charge(); c.live = true; c.kwh = kwh; c.rate = rate; return c;
    }

    @Test public void averagePriceIsWeightedByKwh() {
        List<EnergyModel.Charge> charges = new ArrayList<>();
        charges.add(charge(10, 30)); charges.add(charge(30, 50));
        assertEquals(45, CarTripCost.averagePrice(charges, 20), 1e-9);
        assertEquals(20, CarTripCost.averagePrice(new ArrayList<>(), 20), 0); // home rate before any priced charge
    }

    @Test public void tripCostUsesDriveDeltaAndAppliesLearnedChargingLosses() {
        CarEnergyTracker.Event drive = new CarEnergyTracker.Event(); drive.kind = "drive"; drive.km = 20; drive.before = 80; drive.after = 74;
        drive.tripStartKm = 0; drive.tripStartKwh100 = 0; drive.tripKm = 20; drive.tripKwh100 = 12;
        CarTripCost.Result learned = CarTripCost.of(drive, 40, 0.47, 42.3);
        assertEquals(2.4 * (0.47 * 100 / 42.3) * 40, learned.cost, 1e-9); assertEquals(learned.cost / 20, learned.perKm, 1e-9);
        assertEquals(12 * 20 / 100.0 * 40, CarTripCost.of(drive, 40, Double.NaN, 42.3).cost, 1e-9);
        drive.tripKwh100 = Double.NaN;
        assertEquals(6 * 42.3 / 100 * 40, CarTripCost.of(drive, 40, Double.NaN, 42.3).cost, 1e-9);
        assertNull(CarTripCost.of(drive, Double.NaN, 0.47, 42.3));
        drive.km = 0; assertNull(CarTripCost.of(drive, 40, 0.47, 42.3));
    }

    // ---- 12 V ----

    @Test public void surveyedTwelveVoltReadingFitsTheAssumedScale() {
        assertEquals(13.85, CarBatteryHealth.volts(277), 1e-9);
        assertTrue(Double.isNaN(CarBatteryHealth.volts(1000)));   // 50 V: the scale must be wrong, say nothing
        assertTrue(Double.isNaN(CarBatteryHealth.volts(0)));
    }

    @Test public void lowWarningOnlyAtRest() {
        assertTrue(CarBatteryHealth.status(235, 0, 0).low);       // 11.75 V, car off, not charging
        assertFalse(CarBatteryHealth.status(235, 1, 0).low);      // car on: the DC-DC converter is charging it
        assertFalse(CarBatteryHealth.status(235, 0, 1).low);
        assertFalse(CarBatteryHealth.status(250, 0, 0).low);      // 12.5 V is fine
        assertNull(CarBatteryHealth.status(Double.NaN, 0, 0));
    }

    // ---- AC time ----

    @Test public void acTimeIsCountedForStopsAndDrives() throws Exception {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        for (int i = 0; i <= 60; i++) {
            CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = 1_000_000 + i * 1000L;
            f.set(CarSignal.SOC, 70).set(CarSignal.READY, 1).set(CarSignal.SPEED, 0).set(CarSignal.ODOMETER, 100).set(CarSignal.CHARGING, 0).set(CarSignal.HVAC_ON, i < 30 ? 1 : 0);
            t.accept(f);
        }
        assertEquals(30, s.stop.acSeconds, 0); assertEquals(60, s.stop.stoppedSeconds, 0);
        assertEquals(30, s.drive.acSeconds, 0);
        CarEnergySummary summary = CarEnergySummary.read(CarEnergySummary.of(s.stop).json().toString());
        assertEquals(30, summary.acSeconds, 0);
        CarEnergyTracker.Event restored = CarEnergyCodec.event(CarEnergyCodec.event(s.stop));
        assertEquals(30, restored.acSeconds, 0);
    }
}
