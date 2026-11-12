package com.softiva.oabllink;
import org.junit.Test;
import static org.junit.Assert.*;
public class TripExtensionTest {
    @Test public void scaledTripBandCAndUnconfirmedRawUnits(){
        FakePropertySource p=new FakePropertySource().putInt(DongfengProbe.TRIP_B_KM,14487).putInt(DongfengProbe.TRIP_B_KWH100,1080).putInt(DongfengProbe.TRIP_C_KM,379).putInt(DongfengProbe.TRIP_C_KWH100,1117).putInt(DongfengProbe.TRIP_A_TIME_RAW,123).putInt(DongfengProbe.TRIP_B_TIME_RAW,456).putInt(DongfengProbe.TRIP_C_TIME_RAW,789).putInt(DongfengProbe.TRIP_A_SPEED_RAW,12).putInt(DongfengProbe.TRIP_B_SPEED_RAW,13).putInt(DongfengProbe.TRIP_C_SPEED_RAW,14);
        Reading r=new DongfengProbe(p).read(1);assertEquals(1448.7,r.get(Signal.TRIP_B_KM),1e-9);assertEquals(10.8,r.get(Signal.TRIP_B_KWH100),1e-9);assertEquals(37.9,r.get(Signal.TRIP_C_KM),1e-9);assertEquals(11.17,r.get(Signal.TRIP_C_KWH100),1e-9);assertEquals(123,r.get(Signal.TRIP_A_TIME_RAW),0);assertEquals(456,r.get(Signal.TRIP_B_TIME_RAW),0);assertEquals(789,r.get(Signal.TRIP_C_TIME_RAW),0);assertEquals(12,r.get(Signal.TRIP_A_SPEED_RAW),0);assertEquals(13,r.get(Signal.TRIP_B_SPEED_RAW),0);assertEquals(14,r.get(Signal.TRIP_C_SPEED_RAW),0);assertTrue(Signal.TRIP_A_SPEED_RAW.unit.contains("unconfirmed"));
    }
    @Test public void bothInvalidMarkersOmitEveryNewTripProperty(){
        for(int marker:new int[]{DongfengProbe.INVALID_WORD,DongfengProbe.INVALID_12BIT,-1}){
            FakePropertySource p=new FakePropertySource();int[] ids={DongfengProbe.TRIP_B_KM,DongfengProbe.TRIP_B_KWH100,DongfengProbe.TRIP_C_KM,DongfengProbe.TRIP_C_KWH100,DongfengProbe.TRIP_A_TIME_RAW,DongfengProbe.TRIP_B_TIME_RAW,DongfengProbe.TRIP_C_TIME_RAW,DongfengProbe.TRIP_A_SPEED_RAW,DongfengProbe.TRIP_B_SPEED_RAW,DongfengProbe.TRIP_C_SPEED_RAW};for(int id:ids)p.putInt(id,marker);assertTrue(new DongfengProbe(p).read(1).isEmpty());
        }
    }
    @Test public void additiveRevisionsKeepLegacySchemaAndRouteCompatibility(){org.json.JSONObject hello=Protocol.hello("test","test","test","test",new DongfengProbe(new FakePropertySource()));assertEquals(1,hello.getInt("schema"));assertEquals(6,hello.getInt("revision"));}
    @Test public void bootIdentityAndEveryFreshValueSurviveEvenWhenUnchanged(){SampleBuffer b=new SampleBuffer();b.record(new Reading(1000).set(Signal.HU_BOOT_ID,7).set(Signal.SOC,80).set(Signal.READY,true));
        assertNull("a still car writes no row between full rows",b.record(new Reading(2000).set(Signal.HU_BOOT_ID,7).set(Signal.SOC,80).set(Signal.READY,true)));
        assertEquals("the latest time still moves on for /v1/now",2000,b.latestTime(Signal.SOC));
        Sample changed=b.record(new Reading(3000).set(Signal.HU_BOOT_ID,7).set(Signal.SOC,79).set(Signal.READY,true));assertNotNull("a change writes at once",changed);assertEquals("and the whole row, so every signal stays fresh",1,changed.values().get(Signal.READY),0);
        assertNull(b.record(new Reading(3000+SampleBuffer.FULL_ROW_MS-1).set(Signal.HU_BOOT_ID,7).set(Signal.SOC,79).set(Signal.READY,true)));
        Sample sample=b.record(new Reading(3000+SampleBuffer.FULL_ROW_MS).set(Signal.HU_BOOT_ID,7).set(Signal.SOC,79).set(Signal.READY,true));assertNotNull(sample);assertEquals(7,sample.values().get(Signal.HU_BOOT_ID),0);assertEquals(79,sample.values().get(Signal.SOC),0);assertTrue("inside the box's 60 s freshness limit",SampleBuffer.FULL_ROW_MS<=30_000);}
    @Test public void oldDeltaSourceStillUsesOldShape(){SampleBuffer b=new SampleBuffer();assertNotNull(b.record(new Reading(1000).set(Signal.SOC,80)));assertNull(b.record(new Reading(2000).set(Signal.SOC,80)));}
    @Test public void chargerInvalidWordsAreNotScaledToPower(){assertFalse(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.CHARGE_POWER,65535)).read(1).has(Signal.POWER));}
}
