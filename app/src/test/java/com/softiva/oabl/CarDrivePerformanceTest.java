package com.softiva.oabl;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class CarDrivePerformanceTest {
    private static final long NOW=1_000_000;
    private CarEnergyTracker.Event drive(double km){
        CarEnergyTracker.Event e=new CarEnergyTracker.Event();e.kind="drive";e.before=80;e.after=70;e.rangeStart=400;e.km=km;e.start=NOW-600_000;e.last=NOW;return e;
    }
    private CarDrivePerformance.Result result(CarEnergyTracker.Event e){return CarDrivePerformance.evaluate(e,NOW,true);}
    private void unavailable(CarEnergyTracker.Event e){
        CarDrivePerformance.Result r=result(e);assertEquals(CarDrivePerformance.Verdict.LEARNING,r.verdict);assertFalse(r.eligible);assertEquals("Comparison unavailable",r.title);assertFalse(r.detail.isEmpty());assertTrue(Double.isNaN(r.relativePercent));
    }
    @Test public void comparesKmPerPointWithCarStartKmPerPoint(){
        CarDrivePerformance.Result r=result(drive(60));assertEquals(CarDrivePerformance.Verdict.OVERPERFORMING,r.verdict);assertEquals("Overperforming",r.title);assertEquals(50,r.expectedKm,0);assertEquals(20,r.relativePercent,1e-10);assertTrue(r.eligible);
    }
    @Test public void belowStartEstimateIsUnderperforming(){CarDrivePerformance.Result r=result(drive(40));assertEquals(CarDrivePerformance.Verdict.UNDERPERFORMING,r.verdict);assertEquals("Underperforming",r.title);assertEquals(-20,r.relativePercent,1e-10);}
    @Test public void expectedDistanceIsOnPoint(){CarDrivePerformance.Result r=result(drive(50));assertEquals(CarDrivePerformance.Verdict.ON_POINT,r.verdict);assertEquals("On point",r.title);assertEquals(0,r.relativePercent,0);}
    @Test public void exactPlusTenPercentIsOnPoint(){assertEquals(CarDrivePerformance.Verdict.ON_POINT,result(drive(55)).verdict);}
    @Test public void exactMinusTenPercentIsOnPoint(){assertEquals(CarDrivePerformance.Verdict.ON_POINT,result(drive(45)).verdict);}
    @Test public void beyondEitherBoundaryChangesVerdict(){assertEquals(CarDrivePerformance.Verdict.OVERPERFORMING,result(drive(55.000001)).verdict);assertEquals(CarDrivePerformance.Verdict.UNDERPERFORMING,result(drive(44.999999)).verdict);}
    @Test public void exactlyFiveKmAndTenPointsAreEnough(){CarEnergyTracker.Event e=drive(5);e.rangeStart=40;assertTrue(result(e).eligible);assertEquals(CarDrivePerformance.Verdict.ON_POINT,result(e).verdict);}
    @Test public void shorterDistanceKeepsLearning(){assertEquals("Learning this drive",result(drive(4.999)).title);assertFalse(result(drive(4.999)).eligible);}
    @Test public void smallerSocDropKeepsLearning(){CarEnergyTracker.Event e=drive(60);e.after=70.001;assertEquals("Learning this drive",result(e).title);assertFalse(result(e).eligible);}
    @Test public void zeroDropAndZeroDistanceKeepLearning(){CarEnergyTracker.Event e=drive(0);e.after=e.before;assertEquals("Learning this drive",result(e).title);assertFalse(result(e).eligible);}
    @Test public void socRiseCannotBecomePerformance(){CarEnergyTracker.Event e=drive(60);e.after=81;unavailable(e);}
    @Test public void zeroInvalidAndNonfiniteBaselinesAreUnavailable(){for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){CarEnergyTracker.Event e=drive(60);e.rangeStart=value;unavailable(e);}}
    @Test public void socMustBeFiniteAndWithinBatteryBounds(){for(double value:new double[]{-1,101,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){CarEnergyTracker.Event e=drive(60);e.before=value;unavailable(e);e=drive(60);e.after=value;unavailable(e);}CarEnergyTracker.Event e=drive(60);e.before=0;unavailable(e);}
    @Test public void emptyBatteryCanFinishAnEligibleDrive(){CarEnergyTracker.Event e=drive(55);e.before=10;e.after=0;e.rangeStart=50;assertEquals(CarDrivePerformance.Verdict.ON_POINT,result(e).verdict);}
    @Test public void negativeAndNonfiniteDistanceAreUnavailable(){for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})unavailable(drive(value));}
    @Test public void missingOdometerReliabilityBlocksVerdict(){CarEnergyTracker.Event e=drive(60);e.odometerFlag=true;unavailable(e);}
    @Test public void unresolvedSplitBlocksVerdict(){CarEnergyTracker.Event e=drive(60);e.joinable=true;unavailable(e);e=drive(60);e.state="interrupted";unavailable(e);}
    @Test public void rejoinedGapKeepsVerdictFromMeasuredEndpoints(){
        CarEnergyTracker.Event e=drive(60);e.gap=true;e.gapSeconds=240;e.gapKm=3;e.gapStopSeconds=0;
        assertTrue(CarDrivePerformance.evaluate(e,e.last,true).eligible);
    }
    @Test public void interruptedDriveCannotGiveACurrentVerdict(){CarEnergyTracker.Event e=drive(60);e.state="interrupted";unavailable(e);}
    @Test public void chargingAndOtherKindsCannotGiveADriveVerdict(){for(String kind:new String[]{"charge","stop",null}){CarEnergyTracker.Event e=drive(60);e.kind=kind;unavailable(e);}}
    @Test public void completedDriveCannotMasqueradeAsCurrentDrive(){CarEnergyTracker.Event e=drive(60);e.state="complete";assertEquals("Learning this drive",result(e).title);assertFalse(result(e).eligible);}
    @Test public void callerFreshnessCannotBeBypassedByFreshEvent(){CarDrivePerformance.Result r=CarDrivePerformance.evaluate(drive(60),NOW,false);assertEquals("Waiting for car data",r.title);assertFalse(r.eligible);}
    @Test public void oldMissingOrFutureEventCannotBeCurrent(){for(long last:new long[]{0,NOW-CarEnergyTracker.FRESH_MS-1,NOW+5001}){CarEnergyTracker.Event e=drive(60);e.last=last;assertEquals("Waiting for car data",result(e).title);assertFalse(result(e).eligible);}}
    @Test public void freshnessBoundaryAndSmallClockSkewAreAccepted(){for(long last:new long[]{NOW-CarEnergyTracker.FRESH_MS,NOW+5000}){CarEnergyTracker.Event e=drive(60);e.last=last;assertTrue(result(e).eligible);}}
    @Test public void noEventKeepsLearningAndNoCarDataWaits(){assertEquals("Learning this drive",result(null).title);assertFalse(result(null).eligible);assertEquals("Waiting for car data",CarDrivePerformance.evaluate(null,NOW,false).title);}
    @Test public void comparisonDoesNotChangeStoredDrive(){CarEnergyTracker.Event e=drive(60);result(e);assertEquals(80,e.before,0);assertEquals(70,e.after,0);assertEquals(400,e.rangeStart,0);assertEquals(60,e.km,0);assertEquals("open",e.state);assertEquals(NOW,e.last);}
    @Test public void resultCanCrossOwnerProcessBoundary()throws Exception{
        CarDrivePerformance.Result original=result(drive(60));ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(original);}CarDrivePerformance.Result copy;try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){copy=(CarDrivePerformance.Result)in.readObject();}assertEquals(original.verdict,copy.verdict);assertEquals(original.title,copy.title);assertEquals(original.detail,copy.detail);assertEquals(original.relativePercent,copy.relativePercent,0);assertEquals(original.expectedKm,copy.expectedKm,0);assertEquals(original.eligible,copy.eligible);
    }
}
