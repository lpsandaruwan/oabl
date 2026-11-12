package com.softiva.oabl;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CarLiveEnergyRulesTest {
    private CarEnergyTracker.Frame f(long seconds,double soc,double odo){
        CarEnergyTracker.Frame f=new CarEnergyTracker.Frame();f.time=1000000+seconds*1000;f.capacity=40;f.idleKw=1.2;
        return f.set(CarSignal.READY,1).set(CarSignal.CHARGING,0).set(CarSignal.PLUG,0).set(CarSignal.SOC,soc).set(CarSignal.ODOMETER,odo).set(CarSignal.SPEED,40);
    }
    private CarEnergyTracker.Frame charge(long seconds,double soc,double power){return f(seconds,soc,100).set(CarSignal.READY,0).set(CarSignal.SPEED,0).set(CarSignal.PLUG,1).set(CarSignal.CHARGING,1).set(CarSignal.POWER,power);}
    @Test public void plausibleMovingGapRejoinsAndUsesOnlyOdometer(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100));String key=s.drive.key;t.accept(f(600,78,110));
        assertEquals(key,s.drive.key);assertEquals(10,s.drive.km,0);assertEquals(600,s.drive.gapSeconds,0);assertEquals(0,s.drive.movingSeconds,0);
    }
    @Test public void averageSpeedExcludesGapDistanceWithoutObservedMovingTime(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100));t.accept(f(60,79,101));t.accept(f(660,77,111));assertEquals(60,s.drive.averageKph(),1e-10);
    }
    @Test public void longStoppedGapKeepsOneStopWithoutInventingModelEnergy(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100).set(CarSignal.SPEED,0));String key=s.stop.key;
        t.accept(f(36000,75,100).set(CarSignal.SPEED,0));assertEquals(key,s.stop.key);assertEquals(0,s.stop.modelKwh,0);assertEquals(36000,s.drive.gapStopSeconds,0);
    }
    @Test public void headUnitBootChangeSplitsEvenAShortGap(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100).set(CarSignal.HU_BOOT_ID,1));String key=s.drive.key;
        t.accept(f(1,80,100).set(CarSignal.HU_BOOT_ID,2));assertNotEquals(key,s.drive.key);
    }
    @Test public void tripResetSplitsEvenWithoutBootIdentity(){assertEquals(CarDriveRejoin.Decision.RESTART,CarDriveRejoin.decide(f(0,80,100).set(CarSignal.TRIP_KM,20),f(300,79,101).set(CarSignal.TRIP_KM,0)));}
    @Test public void pluggedEndpointNeverRejoins(){assertEquals(CarDriveRejoin.Decision.CHARGE,CarDriveRejoin.decide(f(0,80,100),f(300,80,100).set(CarSignal.PLUG,1)));}
    @Test public void chargingEndpointNeverRejoins(){assertEquals(CarDriveRejoin.Decision.CHARGE,CarDriveRejoin.decide(f(0,80,100),f(300,80,100).set(CarSignal.CHARGING,1)));}
    @Test public void twoPointRiseNeverRejoins(){assertEquals(CarDriveRejoin.Decision.CHARGE,CarDriveRejoin.decide(f(0,80,100),f(300,82,100)));}
    @Test public void backwardsOdometerIsFlaggedWithoutSpeedFallback(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100));t.accept(f(1,80,99));assertTrue(s.drive.odometerFlag);assertEquals(0,s.drive.km,0);
    }
    @Test public void impossibleOdometerJumpRejectsRejoin(){assertEquals(CarDriveRejoin.Decision.INVALID_ODOMETER,CarDriveRejoin.decide(f(0,80,100),f(100,79,200)));}
    @Test public void impossibleSocDropDoesNotJoin(){assertEquals(CarDriveRejoin.Decision.UNKNOWN,CarDriveRejoin.decide(f(0,80,100),f(1000,60,101)));}
    @Test public void missingSocForMovingGapDoesNotJoin(){assertEquals(CarDriveRejoin.Decision.UNKNOWN,CarDriveRejoin.decide(f(0,Double.NaN,100),f(1000,60,101)));}
    @Test public void unknownReadyKeepsSplitAndOffersJoin(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100));
        CarEnergyTracker.Frame after=f(200,79,101);after.values.remove(CarSignal.READY);after.times.remove(CarSignal.READY);
        assertTrue(t.accept(after).stream().anyMatch(e->e.joinable&&e.state.equals("interrupted")));
    }
    @Test public void staleOdometerCannotJustifyJoin(){CarEnergyTracker.Frame a=f(0,80,100),b=f(1000,79,101);b.times.put(CarSignal.ODOMETER,b.time-61000);assertEquals(CarDriveRejoin.Decision.UNKNOWN,CarDriveRejoin.decide(a,b));}
    @Test public void anyFixWhileChargingLocatesTheCharge(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,3));CarEnergyTracker.Frame fix=charge(10,40,3);fix.latitude=6.9;fix.longitude=79.86;t.accept(fix);assertEquals(6.9,s.charge.latitude,0);
        CarEnergyTracker.State late=new CarEnergyTracker.State();CarEnergyTracker other=new CarEnergyTracker(late);other.accept(charge(0,40,3));fix=charge(40,40,3);fix.latitude=7;fix.longitude=80;other.accept(fix);assertEquals(7,late.charge.latitude,0);
        CarEnergyTracker.Frame second=charge(50,40,3);second.latitude=8;second.longitude=81;other.accept(second);assertEquals(7,late.charge.latitude,0);
    }
    @Test public void trapezoidIntegratesChargerSidePower(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,3));t.accept(charge(1,40,5));assertEquals(4.0/3600,s.charge.measuredKwh(),1e-12);assertEquals(1,s.charge.coveredSeconds,0);
    }
    @Test public void gapsNeverCountTowardsMeasuredKwh(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,3));t.accept(charge(1,40,3));t.accept(charge(601,50,3));assertEquals(3.0/3600,s.charge.measuredKwh(),1e-12);assertEquals(1,s.charge.coveredSeconds,0);
    }
    @Test public void staleOrRepeatedPowerTimestampIsExcluded(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,3));CarEnergyTracker.Frame b=charge(1,40,3);b.times.put(CarSignal.POWER,b.time-1000);t.accept(b);assertTrue(Double.isNaN(s.charge.measuredKwh()));
    }
    @Test public void gapAtChargeStartDoesNotIntegrateDrivingTime(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,40,100).set(CarSignal.POWER,3));t.accept(charge(1000,40,3));assertTrue(Double.isNaN(s.charge.measuredKwh()));
    }
    @Test public void chargeEndDoesNotAssumeChargingThroughUnknownEndpoint(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,3));CarEnergyTracker.Event e=t.accept(f(600,50,100).set(CarSignal.POWER,0)).stream().filter(x->x.kind.equals("charge")).findFirst().get();assertTrue(Double.isNaN(e.measuredKwh()));
    }
    @Test public void invalidPowerIsNotEnergy(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(charge(0,40,-1));t.accept(charge(1,40,6553.5));assertTrue(Double.isNaN(s.charge.measuredKwh()));
    }
    @Test public void processKillCheckpointMatchesUninterruptedReduction()throws Exception{
        CarEnergyTracker.State a=new CarEnergyTracker.State(),b=new CarEnergyTracker.State();
        for(int i=0;i<30;i++){
            CarEnergyTracker.Frame frame=charge(i,40+i/10.0,3+i/10.0);new CarEnergyTracker(a).accept(frame);
            b=CarEnergyCodec.state(CarEnergyCodec.state(b));new CarEnergyTracker(b).accept(CarEnergyCodec.frame(CarEnergyCodec.frame(frame)));
        }
        assertEquals(CarEnergyCodec.state(a),CarEnergyCodec.state(b));
    }
    @Test public void replayAndOutOfOrderNeverChangeCheckpoint()throws Exception{
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(1,80,100));t.accept(f(2,79,101));String checkpoint=CarEnergyCodec.state(s);
        assertTrue(t.accept(f(2,40,1000)).isEmpty());assertTrue(t.accept(f(1,1,0)).isEmpty());assertEquals(checkpoint,CarEnergyCodec.state(s));
    }
    @Test public void replayAcrossJoinedGapIsDeterministic()throws Exception{
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100));t.accept(f(1000,78,110));String before=CarEnergyCodec.state(s);assertTrue(t.accept(f(500,79,105)).isEmpty());assertEquals(before,CarEnergyCodec.state(s));
    }
    @Test public void meterOverridesMeasuredForCostWhileBothRemain(){EnergyModel.Charge c=new EnergyModel.Charge();c.live=true;c.rate=50;c.kwh=9;c.measuredKwh=8;assertEquals(450,c.money(1),0);assertEquals(8,c.measuredKwh,0);}
    @Test public void confirmedPriceNeverChangesWithHomeRate(){EnergyModel.Charge c=new EnergyModel.Charge();c.live=true;c.place=EnergyModel.Place.HOME;c.rate=50;c.measuredKwh=8;assertEquals(400,c.money(200),0);assertEquals(400,c.money(500),0);}
    @Test public void inferredCostRemainsEstimatedAndFreeRateWorks(){EnergyModel.Charge c=new EnergyModel.Charge();c.live=true;c.rate=0;c.estimatedKwh=8;assertEquals(0,c.money(100),0);assertTrue(Double.isNaN(c.measuredKwh));}
    @Test public void missingPriceDoesNotInheritLaterHomeRate(){EnergyModel.Charge c=new EnergyModel.Charge();c.live=true;c.place=EnergyModel.Place.HOME;c.measuredKwh=8;assertTrue(Double.isNaN(c.money(50)));}
    @Test public void chargersWithin150MetresMatchAndUnknownDoesNot(){assertTrue(CarCharger.near(6.9,79.86,6.901,79.86));assertFalse(CarCharger.near(6.9,79.86,6.902,79.86));assertFalse(CarCharger.near(Double.NaN,79.86,6.9,79.86));}
    @Test public void capacitySumsEnergyAndGainAndExcludesInsufficientCoverage(){
        EnergyModel.Charge a=new EnergyModel.Charge(),b=new EnergyModel.Charge(),partial=new EnergyModel.Charge();a.before=20;a.after=40;a.measuredKwh=9;a.measuredComplete=true;b.before=40;b.after=80;b.measuredKwh=17;b.measuredComplete=true;partial.before=10;partial.after=50;partial.measuredKwh=1;
        assertEquals(100*26.0/60,CarEnergyInsights.capacityUpperBound(Arrays.asList(a,b,partial)),1e-10);assertTrue(Double.isNaN(CarEnergyInsights.capacityUpperBound(Arrays.asList(partial))));
    }
    @Test public void temperatureBandsHaveDeterministicBoundaries(){assertEquals(0,CarEnergyInsights.temperatureBand(-.1));assertEquals(1,CarEnergyInsights.temperatureBand(0));assertEquals(2,CarEnergyInsights.temperatureBand(10));assertEquals(3,CarEnergyInsights.temperatureBand(20));assertEquals(4,CarEnergyInsights.temperatureBand(30));assertEquals(5,CarEnergyInsights.temperatureBand(40));}
    @Test public void rangeAndTemperatureAreTakenFromCarWithoutBenchmark(){
        CarEnergyTracker.State s=new CarEnergyTracker.State();CarEnergyTracker t=new CarEnergyTracker(s);t.accept(f(0,80,100).set(CarSignal.RANGE_EST,300).set(CarSignal.TEMP_OUT,25));t.accept(f(60,79,101).set(CarSignal.TEMP_OUT,25));assertEquals(300,s.drive.rangeStart,0);assertEquals(80,s.drive.rangeAtStartSoc(),0);assertEquals(25,s.drive.temperature(),0);assertEquals(1,s.drive.temperatureKm[3],0);
    }
    @Test public void incrementalSummarySubtractAndAddIsRebuildable()throws Exception{
        CarEnergyTracker.Event e=new CarEnergyTracker.Event();e.kind="drive";e.before=80;e.after=78;e.capacity=40;e.km=10;CarEnergySummary s=new CarEnergySummary();s.add(CarEnergySummary.of(e),1);s.add(CarEnergySummary.of(e),-1);e.km=12;s.add(CarEnergySummary.of(e),1);CarEnergySummary rebuilt=CarEnergySummary.read(s.json().toString());assertEquals(12,rebuilt.km,0);assertEquals(1,rebuilt.drives);assertEquals(.8,rebuilt.usedKwh(40),1e-12);assertEquals(.84,rebuilt.usedKwh(42),1e-12);
    }
}
