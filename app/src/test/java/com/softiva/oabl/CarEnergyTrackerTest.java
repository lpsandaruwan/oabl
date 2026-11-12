package com.softiva.oabl;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CarEnergyTrackerTest {
    private CarEnergyTracker.Frame frame(long seconds, double soc, double ready, double speed, double odo, double charging) {
        CarEnergyTracker.Frame f = new CarEnergyTracker.Frame(); f.time = 1_000_000 + seconds * 1000; f.idleKw = 1.2; f.capacity = 40;
        return f.set(CarSignal.SOC,soc).set(CarSignal.READY,ready).set(CarSignal.SPEED,speed)
                .set(CarSignal.ODOMETER,odo).set(CarSignal.CHARGING,charging);
    }
    @Test public void driveUsesCarOdometerAndBattery() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,80,1,40,100,0)); t.accept(frame(60,79,1,60,101,0));
        CarEnergyTracker.Event drive = t.accept(frame(90,78,0,0,101.5,0)).stream().filter(e -> e.kind.equals("drive")).findFirst().get();
        assertEquals(1.5,drive.km,.0001); assertEquals(.8,drive.usedKwh(),.0001); assertTrue(drive.closed());
    }
    @Test public void readyStoppedCountsTimeAndSeparatesModelFromBatteryDrop() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,70,1,0,100,0)); t.accept(frame(60,69,1,0,100,0));
        CarEnergyTracker.Event stop = t.accept(frame(90,69,1,30,100,0)).stream().filter(e -> e.kind.equals("stop")).findFirst().get();
        assertEquals(90,stop.stoppedSeconds,.001); assertEquals(.03,stop.modelKwh,.0001); assertEquals(.4,stop.usedKwh(),.0001);
    }
    @Test public void chargingNeverCountsAsIdleEvenIfReady() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,1)); t.accept(frame(60,41,1,0,100,1));
        assertNull(s.stop); assertNull(s.drive); assertNotNull(s.charge);
    }
    @Test public void offlineTimeIsNotAddedToIdleAndRiseNeedsConfirmation() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0)); t.accept(frame(30,40,0,0,100,0));
        List<CarEnergyTracker.Event> events = t.accept(frame(4000,60,1,0,100,0));
        CarEnergyTracker.Event charge = events.stream().filter(e -> e.kind.equals("charge")).findFirst().get();
        assertTrue(charge.inferred); assertTrue(charge.offSeen); assertEquals(20,charge.gain(),0);
        assertEquals(0,s.stop.stoppedSeconds,0);
    }
    @Test public void aDisconnectWithNoRiseIsNotACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0));
        assertFalse(t.accept(frame(200,40,1,0,100,0)).stream().anyMatch(e -> e.kind.equals("charge")));
        assertFalse(t.accept(frame(400,41,1,0,100,0)).stream().anyMatch(e -> e.kind.equals("charge")));
    }
    private static CarEnergyTracker.Event chargeIn(List<CarEnergyTracker.Event> events) {
        return events.stream().filter(e -> e.kind.equals("charge")).findFirst().orElse(null);
    }
    @Test public void aRiseAcrossAGapWithTheCarOnAndNotMovedIsACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0));
        CarEnergyTracker.Event charge = chargeIn(t.accept(frame(400,42,1,0,100,0)));
        assertEquals(2, charge.gain(), 0); assertTrue(charge.inferred && charge.externalCharge);
    }
    /** 31 October: 73 to 86 % over 21 hours, found with the car already on, 5 km further. Nothing was recorded. */
    @Test public void aBigRiseOverHoursCountsWithTheCarAlreadyOnAndMoved() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,73,1,0,4326,0));
        CarEnergyTracker.Event charge = chargeIn(t.accept(frame(78_000,86,1,0,4331,0)));
        assertEquals(73, charge.before, 0); assertEquals(86, charge.after, 0);
    }
    @Test public void chargingWithTheCarOnAndNoChargingSignalIsFoundWhenItMovesOff() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0)); t.accept(frame(60,41,1,0,100,0)); t.accept(frame(120,43,1,0,100,0));
        CarEnergyTracker.Event charge = chargeIn(t.accept(frame(180,43,1,12,100,0)));
        assertEquals(40, charge.before, 0); assertEquals(43, charge.after, 0); assertTrue(charge.inferred && charge.externalCharge);
        assertNull(s.still);
    }
    @Test public void aRiseWhileMovingIsRegenNotACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,30,100,0)); t.accept(frame(60,42,1,30,100.5,0));
        assertNull(chargeIn(t.accept(frame(120,44,1,30,101,0))));
        assertNull(chargeIn(t.accept(frame(180,44,1,0,101.2,0))));
    }
    @Test public void aWholePointWobbleWhileStoppedIsNotACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0)); t.accept(frame(60,41,1,0,100,0));
        assertNull(chargeIn(t.accept(frame(120,41,1,20,100,0))));
    }
    /** 29 October: the head unit woke repeating 27 %, 57 seconds old, then the real 42 %. Not a 15-point charge. */
    @Test public void aStaleLevelFromAWakingHeadUnitIsNotTheStartOfACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        CarEnergyTracker.Frame stale = frame(0,27,0,0,4216,0); stale.times.put(CarSignal.SOC, stale.time - 57_000); t.accept(stale);
        t.accept(frame(3,42,0,0,4216,0));
        assertNull(chargeIn(t.accept(frame(60,42,1,12,4216,0))));
    }
    @Test public void theStillWatcherSurvivesARestart() throws Exception {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0)); t.accept(frame(60,43,1,0,100,0));
        CarEnergyTracker.State restored = CarEnergyCodec.state(CarEnergyCodec.state(s));
        assertEquals(100, restored.still.startOdometer, 0);
        assertEquals(3, chargeIn(new CarEnergyTracker(restored).accept(frame(120,43,1,15,100,0))).gain(), 0);
    }
    /** Lost sight of a running, parked car; seen charging after: the rise since then belongs to the charge. */
    @Test public void aChargeSeenAfterAGapStartsFromTheLastLevelEvenIfTheCarWasOn() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,1,0,100,0));
        t.accept(frame(600,47,0,0,100,1));
        assertEquals(40, s.charge.before, 0); assertTrue(s.charge.gap);
    }
    @Test public void observedChargeCompletesOnceAndReplayDoesNothing() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,40,0,0,100,1)); t.accept(frame(60,42,0,0,100,1));
        CarEnergyTracker.Frame end = frame(90,43,0,0,100,0);
        CarEnergyTracker.Event charge = t.accept(end).stream().filter(e -> e.kind.equals("charge")).findFirst().get();
        assertFalse(charge.inferred); assertEquals(3,charge.gain(),0); assertTrue(t.accept(end).isEmpty());
    }
    @Test public void profileChangeDoesNotRepriceEarlierStopTime() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,70,1,0,100,0)); CarEnergyTracker.Frame next = frame(60,70,1,0,100,0); next.idleKw = 3;
        t.accept(next); t.accept(frame(120,70,1,0,100,0)); assertEquals(.07,s.stop.modelKwh,.00001);
    }
    @Test public void staleSpeedCannotCreateAnIdlePeriod() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        CarEnergyTracker.Frame f = frame(100,70,1,0,100,0); f.times.put(CarSignal.SPEED,f.time-61000);
        t.accept(f); assertNull(s.stop);
    }
    @Test public void missingOdometerUsesLabelledCarSpeedEstimate() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        CarEnergyTracker.Frame a = frame(0,70,1,60,100,0), b = frame(60,70,1,60,100,0);
        a.values.remove(CarSignal.ODOMETER);a.times.remove(CarSignal.ODOMETER);b.values.remove(CarSignal.ODOMETER);b.times.remove(CarSignal.ODOMETER);
        t.accept(a);t.accept(b);assertEquals(1,s.drive.km,.00001);assertEquals("estimated from car speed",s.drive.distanceSource);
    }
    @Test public void persistedCheckpointSurvivesRestartWithoutDuplicateCharge() throws Exception {
        CarEnergyTracker.State s = new CarEnergyTracker.State();new CarEnergyTracker(s).accept(frame(0,40,0,0,100,1));
        CarEnergyTracker.State restored = CarEnergyCodec.state(CarEnergyCodec.state(s));
        List<CarEnergyTracker.Event> events = new CarEnergyTracker(restored).accept(frame(400,50,0,0,100,0));
        assertEquals(1,events.stream().filter(e -> e.kind.equals("charge")).count());assertFalse(events.get(0).inferred);assertTrue(events.get(0).gap);
    }
    @Test public void aShortAppRestartDoesNotSplitADrive(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);
        CarEnergyTracker.Frame before=frame(0,80,1,40,100,0);before.run="first";t.accept(before);
        String key=s.drive.key;
        CarEnergyTracker.Frame after=frame(30,79,1,40,100.5,0);after.run="restarted";t.accept(after);
        assertEquals(key,s.drive.key);assertFalse(s.drive.gap);assertEquals(.5,s.drive.km,0);
    }

    // A real case: Link connected at 11:01 and sent soc 0 before the car answered, then nothing for 80 minutes.
    @Test public void aZeroBatteryReadingNeverBecomesAChargeStart() throws Exception {
        org.json.JSONObject zero = new org.json.JSONObject().put("soc", 0).put("ready", 0);
        assertFalse(CarSignal.plausible(CarSignal.SOC, 0));
        assertTrue(CarSignal.plausible(CarSignal.SOC, 1));
        assertFalse(CarSignal.plausible(CarSignal.SOC, 101));
        assertTrue(CarSignal.plausible(CarSignal.RANGE_EST, 0));
        assertNull(CarLinkProtocol.parseValues(zero.toString()).get(CarSignal.SOC));
        assertEquals(0.0, CarLinkProtocol.parseValues(zero.toString()).get(CarSignal.READY), 0);
    }
    @Test public void chargeSeenAfterAGapStartsFromTheLastRealLevelNotTheStaleOrMissingOne() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,39,0,0,100,0));
        // seven minutes later the link is back but has no battery reading yet (the 0 was dropped)
        CarEnergyTracker.Frame silent = frame(420,39,0,0,100,0); silent.times.put(CarSignal.SOC, 1_000_000L); silent.values.put(CarSignal.SOC, 39.0);
        t.accept(silent);
        CarEnergyTracker.Frame charging = frame(5400,46,0,0,100,1);
        t.accept(charging);
        assertNotNull(s.charge);
        assertEquals(39, s.charge.before, 0);
        assertTrue(s.charge.gap);
        assertEquals(silent.time, s.charge.start);
        CarEnergyTracker.Event done = t.accept(frame(5460,47,0,0,100,0)).stream().filter(e -> e.kind.equals("charge")).findFirst().get();
        assertEquals(8, done.gain(), 0);
    }
    @Test public void aBatteryReadingOlderThanThirtyMinutesIsNotCarriedAcrossAGap() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        CarEnergyTracker.Frame old = frame(0,39,0,0,100,0); t.accept(old);
        CarEnergyTracker.Frame later = frame(4000,39,0,0,100,0); later.times.put(CarSignal.SOC, old.time); t.accept(later);
        t.accept(frame(9000,46,0,0,100,1));
        assertNotNull(s.charge);
        assertEquals("falls back to the first fresh reading", 46, s.charge.before, 0);
    }
    @Test public void anOvernightRiseIsAChargeEvenIfTheCarMovedAFewKilometresFirst() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,62,1,17,4231,0));
        // 14 hours later the head unit wakes: battery up 14 points, odometer 5 km further, car off, not charging any more
        java.util.List<CarEnergyTracker.Event> events = t.accept(frame(52_000,76,0,0,4236,0));
        CarEnergyTracker.Event charge = events.stream().filter(e -> e.kind.equals("charge")).findFirst().get();
        assertEquals(62, charge.before, 0);
        assertEquals(76, charge.after, 0);
        assertTrue(charge.inferred && charge.externalCharge && charge.gap);
    }
    /** 7 November: the box lost the car at 30 % and found it unplugged at 96 %; the record said 27 to 30. */
    @Test public void aChargeThatFinishedWhileTheBoxWasOffKeepsTheLevelSeenAfterwards() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,27,0,0,4590,1).set(CarSignal.PLUG,1)); t.accept(frame(60,30,0,0,4590,1).set(CarSignal.PLUG,1));
        CarEnergyTracker.Event charge = t.accept(frame(140_000,96,0,0,4590,0).set(CarSignal.PLUG,0)).stream()
                .filter(e -> e.kind.equals("charge")).findFirst().get();
        assertEquals(27, charge.before, 0); assertEquals(96, charge.after, 0); assertTrue(charge.gap && charge.closed());
    }
    @Test public void unpluggingInSightStillEndsAtTheLastChargingLevel() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,27,0,0,4590,1).set(CarSignal.PLUG,1)); t.accept(frame(60,30,0,0,4590,1).set(CarSignal.PLUG,1));
        CarEnergyTracker.Event charge = t.accept(frame(61,31,1,5,4590,0).set(CarSignal.PLUG,0)).stream()
                .filter(e -> e.kind.equals("charge")).findFirst().get();
        assertEquals(30, charge.after, 0);
    }
    @Test public void aSmallRiseAfterDrivingIsStillNotACharge() {
        CarEnergyTracker.State s = new CarEnergyTracker.State(); CarEnergyTracker t = new CarEnergyTracker(s);
        t.accept(frame(0,62,1,17,4231,0));
        assertFalse(t.accept(frame(52_000,64,0,0,4236,0)).stream().anyMatch(e -> e.kind.equals("charge")));
    }
    @Test public void theHeadUnitsNotReadYetValuesAreNeverReadings() {
        assertFalse(CarSignal.plausible(CarSignal.ODOMETER, 1_048_575));
        assertFalse(CarSignal.plausible(CarSignal.RANGE_EST, 1023));
        assertFalse(CarSignal.plausible(CarSignal.TRIP_C_KM, 10_000));
        assertTrue(CarSignal.plausible(CarSignal.ODOMETER, 4236));
        assertTrue(CarSignal.plausible(CarSignal.TRIP_B_KM, 1790.3));
        assertTrue(CarSignal.plausible(CarSignal.RANGE_STANDARD, 336));
    }
}
