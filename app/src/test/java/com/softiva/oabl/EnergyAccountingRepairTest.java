package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyAccountingRepairTest {
    private CarEnergyTracker.Frame frame(int seconds,double soc,double odo,double speed,double ready) {
        CarEnergyTracker.Frame f=new CarEnergyTracker.Frame(); f.time=1000000+seconds*1000L;
        return f.set(CarSignal.SOC,soc).set(CarSignal.ODOMETER,odo).set(CarSignal.SPEED,speed)
                .set(CarSignal.READY,ready).set(CarSignal.CHARGING,0).set(CarSignal.PLUG,0);
    }
    @Test public void downhillRegenAcrossGapDoesNotCreatePricedCharge() {
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker tracker=new CarEnergyTracker(s);
        tracker.accept(frame(0,40,100,50,1));
        assertFalse(tracker.accept(frame(600,45,105,30,1)).stream().anyMatch(e->e.kind.equals("charge")));
        assertNull(s.charge);
    }
    /** The owner's rule (9 November): moving is regen, stopped is charging, whether or not the car is on. */
    @Test public void aRiseWithTheCarOnButNotMovingIsACharge() {
        assertTrue(CarEnergyTracker.stationaryChargeGap(frame(0,40,100,0,1),frame(600,45,100,0,1)));
        assertFalse(CarEnergyTracker.stationaryChargeGap(frame(0,40,100,50,1),frame(600,45,105,30,1)));
    }
    @Test public void stationaryOffGapIsReviewableButNotAutomaticallyPriced() {
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker tracker=new CarEnergyTracker(s);
        tracker.accept(frame(0,40,100,0,0));
        CarEnergyTracker.Event e=tracker.accept(frame(600,50,100,0,0)).stream().filter(x->x.kind.equals("charge")).findFirst().get();
        assertTrue(e.externalCharge);assertTrue(e.inferred);assertTrue(CarChargeReviewPolicy.eligible(e,true));
    }
    @Test public void spuriousChargingFlagWhileMovingCannotMeasureRegenAsGridEnergy() {
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker tracker=new CarEnergyTracker(s);
        tracker.accept(frame(0,40,100,50,1).set(CarSignal.CHARGING,1).set(CarSignal.POWER,20));
        tracker.accept(frame(1,41,100.01,40,1).set(CarSignal.CHARGING,1).set(CarSignal.POWER,10));
        assertNull(s.charge);assertNotNull(s.drive);assertEquals(.01,s.drive.km,1e-9);
    }
    @Test public void explicitUnpluggedSignalRejectsStationaryChargingFlag() {
        assertFalse(CarEnergyTracker.externalCharging(frame(0,40,100,0,1).set(CarSignal.CHARGING,1)));
    }
    @Test public void unverifiedLegacyRiseIsPreservedButExcludedFromChargeCount() throws Exception {
        CarEnergyTracker.Event e=new CarEnergyTracker.Event();e.kind="charge";e.key="legacy";e.inferred=true;e.before=40;e.after=50;e.state="complete";
        org.json.JSONObject json=CarEnergyCodec.event(e);json.remove("external_charge");
        CarEnergyTracker.Event legacy=CarEnergyCodec.event(json);
        assertEquals(10,legacy.gain(),0);assertFalse(CarChargeReviewPolicy.counts(legacy));
    }
    private CarEnergyTracker.Event drive(double km,double before,double after) {
        CarEnergyTracker.Event e=new CarEnergyTracker.Event();e.kind="drive";e.km=km;e.before=before;e.after=after;return e;
    }
    @Test public void periodEnergyAndCostMatchIndividualDriveTotals() throws Exception {
        CarEnergyTracker.Event a=drive(10,80,78);a.tripStartKm=100;a.tripStartKwh100=15;a.tripKm=110;a.tripKwh100=15;
        CarEnergyTracker.Event b=drive(5,78,77),missing=drive(20,Double.NaN,Double.NaN);
        CarEnergySummary s=new CarEnergySummary();s.add(CarEnergySummary.of(a),1);s.add(CarEnergySummary.of(b),1);s.add(CarEnergySummary.of(missing),1);
        s=CarEnergySummary.read(s.json().toString());
        assertEquals(35,s.km,0);assertEquals(15,s.energyKm(40),0);assertEquals(1.9,s.usedKwh(40),1e-9);
        double sum=CarTripCost.of(a,50,.45,40).cost+CarTripCost.of(b,50,.45,40).cost;
        assertEquals(sum,CarTripCost.billedEnergy(s.usedKwh(40),.45,40)*50,1e-9);
        assertEquals(10,s.energyKm(Double.NaN),0);assertEquals(1.5,s.usedKwh(Double.NaN),1e-9);
    }
    @Test public void cumulativeTripAverageDoesNotBecomeCurrentDriveEnergy() {
        CarEnergyTracker.Event e=drive(10,80,79);e.tripStartKm=100;e.tripStartKwh100=15;e.tripKm=110;e.tripKwh100=14;
        assertEquals(.4,e.tripNetKwh(),1e-9);
        assertEquals(20,CarTripCost.of(e,50,Double.NaN,40).cost,1e-9);
    }
    @Test public void zeroSocDropWithoutTripEnergyIsUnresolvedRatherThanFree() {
        CarEnergySummary s=CarEnergySummary.of(drive(10,80,80));
        assertEquals(0,s.energyKm(40),0);assertTrue(Double.isNaN(s.usedKwh(40)));
    }
    @Test public void regenPowerBeforePluggingInNeverBecomesChargerEnergy() {
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker tracker=new CarEnergyTracker(s);
        tracker.accept(frame(0,40,100,10,1).set(CarSignal.CHARGING,1).set(CarSignal.POWER,25));
        tracker.accept(frame(1,40,100,0,0).set(CarSignal.CHARGING,1).set(CarSignal.PLUG,1).set(CarSignal.POWER,3));
        assertNotNull(s.charge);assertTrue(Double.isNaN(s.charge.measuredKwh()));
    }
    @Test public void clusterBlankingAtKeyOffKeepsLastValidTripEnergy() {
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker tracker=new CarEnergyTracker(s);
        tracker.accept(frame(0,80,100,0,1).set(CarSignal.TRIP_KM,0).set(CarSignal.TRIP_KWH100,0));
        tracker.accept(frame(60,79,101,60,1).set(CarSignal.TRIP_KM,1).set(CarSignal.TRIP_KWH100,14));
        CarEnergyTracker.Event e=tracker.accept(frame(61,79,101,0,0).set(CarSignal.TRIP_KM,0).set(CarSignal.TRIP_KWH100,0))
                .stream().filter(x->x.kind.equals("drive")).findFirst().get();
        assertEquals(.14,e.tripNetKwh(),1e-12);
    }

}
