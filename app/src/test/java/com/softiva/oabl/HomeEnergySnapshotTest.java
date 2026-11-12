package com.softiva.oabl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** The car's ranges are shown as its display names them: Actual (headline), Standard and NEDC. No CLTC/WLTP. */
public class HomeEnergySnapshotTest {
    @Test public void directValuesAreDisplayedWithoutConversion(){assertEquals("Standard 273 km  ·  NEDC 244 km",HomeEnergySnapshot.references(273,244));}
    @Test public void noCycleTheCarDoesNotReportIsShown(){String text=HomeEnergySnapshot.references(273,244);assertFalse(text.contains("CLTC"));assertFalse(text.contains("WLTP"));}
    @Test public void oldLinkMissingOptionalReadingsDoesNotGetInventedValues(){String text=HomeEnergySnapshot.references(Double.NaN,Double.NaN);assertEquals("",text);assertFalse(text.contains("modelled"));assertFalse(text.contains("entered"));}
    @Test public void zeroRangeIsValid(){assertTrue(HomeEnergySnapshot.references(0,0).contains("NEDC 0 km"));}
    @Test public void invalidReadingsAreOmittedIndependently(){for(double invalid:new double[]{-1,2000,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})assertEquals("NEDC 244 km",HomeEnergySnapshot.references(invalid,244));}
    @Test public void wholeKmDisplayRoundsRawOnly(){assertTrue(HomeEnergySnapshot.references(273.6,110.5).startsWith("Standard 274 km  ·  NEDC 111 km"));}

    @Test public void newlyCapturedHomeDoesNotRenewAlmostExpiredSpeed(){
        HomeEnergySnapshot home=speed(72,97_100,100_000);
        assertEquals(72,home.currentSpeed(100_100),0); // Exactly three seconds since measurement.
        assertTrue(Double.isNaN(home.currentSpeed(100_101)));
        home.capturedAt=100_101;
        assertTrue("Recapturing Home cannot renew the same speed reading",Double.isNaN(home.currentSpeed(100_101)));
    }

    @Test public void ownerAndCachedSpeedShareTheThreeSecondBoundary(){
        CarLinkController.SpeedReading reading=new CarLinkController.SpeedReading(42,100_000);
        HomeEnergySnapshot home=speed(reading.value,reading.measuredAt,102_500);
        assertEquals(42,reading.current(103_000),0);
        assertEquals(42,home.currentSpeed(103_000),0);
        assertTrue(Double.isNaN(reading.current(103_001)));
        assertTrue(Double.isNaN(home.currentSpeed(103_001)));
    }

    @Test public void missingSourceTimestampCannotBecomeFreshAtCapture(){
        for(long unknown:new long[]{0,-1}){
            HomeEnergySnapshot home=speed(80,unknown,100_000);
            assertTrue(Double.isNaN(home.currentSpeed(100_000)));
        }
    }

    @Test public void futureSpeedTimeHasTheSameBoundAsTheOwner(){
        HomeEnergySnapshot home=speed(42,103_000,100_000);
        assertEquals(42,home.currentSpeed(100_000),0);
        home.speedAt=103_001;
        assertTrue(Double.isNaN(home.currentSpeed(100_000)));
    }

    @Test public void cachedSpeedUsesNormalizedSourceTimeRatherThanEitherDeviceClock(){
        CarLink link=new CarLink();CarLinkProtocol.Now now=new CarLinkProtocol.Now();
        now.time=10_000;now.values.put(CarSignal.SPEED,72d);now.times.put(CarSignal.SPEED,9_700L);
        link.ingestNow(now,100_000); // The car is ninety seconds behind the box.
        assertEquals(99_700,link.latestTime(CarSignal.SPEED));
        HomeEnergySnapshot home=speed(link.latest(CarSignal.SPEED),link.latestTime(CarSignal.SPEED),102_500);
        assertEquals(72,home.currentSpeed(102_700),0);
        assertTrue(Double.isNaN(home.currentSpeed(102_701)));
    }

    @Test public void serializedHomeSnapshotRetainsSourceSpeedAge()throws Exception{
        HomeEnergySnapshot home=speed(72,97_100,100_000);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(home);}
        HomeEnergySnapshot copy;
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){copy=(HomeEnergySnapshot)in.readObject();}
        assertEquals(97_100,copy.speedAt);assertEquals(100_000,copy.capturedAt);
        assertEquals(72,copy.currentSpeed(100_100),0);
        assertTrue(Double.isNaN(copy.currentSpeed(100_101)));
    }

    @Test public void unavailableSpeedRemainsUnknownEvenWithARecentSourceTime(){
        assertTrue(Double.isNaN(speed(Double.NaN,100_000,100_000).currentSpeed(100_000)));
    }

    private HomeEnergySnapshot speed(double value,long measuredAt,long capturedAt){
        HomeEnergySnapshot home=new HomeEnergySnapshot();home.speed=value;home.speedAt=measuredAt;home.capturedAt=capturedAt;return home;
    }
}
