package com.softiva.oabllink;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** Charge target/remaining and the raw research values: read-only, sparse and unscaled when unconfirmed. */
public class BatteryExtensionTest {
    private static FakePropertySource surveyed() {
        // Values from the 20 October parked-and-charging survey.
        return new FakePropertySource()
                .putInt(DongfengProbe.CHARGE_STATE, 2)
                .putInt(DongfengProbe.CHARGE_TARGET, 95)
                .putInt(DongfengProbe.CHARGE_REMAINING, 354)
                .putInt(DongfengProbe.REGEN_LEVEL, 3)
                .putInt(DongfengProbe.V12, 277)
                .putInt(DongfengProbe.BATT_HEALTH, 1000);
    }

    @Test public void surveyedChargingValuesArriveWithTheirOwnKeys() {
        Reading reading = new DongfengProbe(surveyed()).read(1);
        assertEquals(1, reading.get(Signal.CHARGING), 0);
        assertEquals(2, reading.get(Signal.CHARGE_STATE_RAW), 0);
        assertEquals(95, reading.get(Signal.CHARGE_TARGET), 0);
        assertEquals(354, reading.get(Signal.CHARGE_REMAINING_RAW), 0);
        assertEquals(3, reading.get(Signal.REGEN_LEVEL_RAW), 0);
        assertEquals(277, reading.get(Signal.V12_RAW), 0);       // raw: never presented as volts
        assertEquals(1000, reading.get(Signal.BATT_HEALTH_RAW), 0);
        assertFalse(reading.has(Signal.V12));
        assertFalse(reading.has(Signal.SOH));
    }

    @Test public void unknownChargeStatesStayRawAndAreNotCharging() {
        Reading dc = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.CHARGE_STATE, 3)).read(1);
        assertEquals(0, dc.get(Signal.CHARGING), 0);
        assertEquals(3, dc.get(Signal.CHARGE_STATE_RAW), 0);
        for (int invalid : new int[]{-1, DongfengProbe.INVALID_BYTE, DongfengProbe.INVALID_WORD}) {
            assertFalse(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.CHARGE_STATE, invalid)).read(1)
                    .has(Signal.CHARGE_STATE_RAW));
        }
    }

    @Test public void invalidMarkersAndImpossibleValuesAreOmittedNotZeroed() {
        FakePropertySource bad = new FakePropertySource()
                .putInt(DongfengProbe.CHARGE_TARGET, 0)
                .putInt(DongfengProbe.CHARGE_REMAINING, DongfengProbe.INVALID_WORD)
                .putInt(DongfengProbe.REGEN_LEVEL, DongfengProbe.INVALID_BYTE)
                .putInt(DongfengProbe.V12, DongfengProbe.INVALID_WORD)
                .putInt(DongfengProbe.BATT_HEALTH, 1001);
        Reading reading = new DongfengProbe(bad).read(1);
        for (Signal signal : new Signal[]{Signal.CHARGE_TARGET, Signal.CHARGE_REMAINING_RAW, Signal.REGEN_LEVEL_RAW,
                Signal.V12_RAW, Signal.BATT_HEALTH_RAW}) assertFalse(signal.key, reading.has(signal));
        Reading absent = new DongfengProbe(new FakePropertySource()).read(1);
        assertFalse(absent.has(Signal.CHARGE_TARGET));
        assertFalse(absent.has(Signal.CHARGE_STATE_RAW));
    }

    @Test public void airConditioningLevelsArriveRawAndInvalidMarkersAreDropped() {
        Reading on = new DongfengProbe(new FakePropertySource()
                .putInt(DongfengProbe.AC_BLOW_LEVEL, 3).putInt(DongfengProbe.AC_SET_TEMP, 22)).read(1);
        assertEquals(3, on.get(Signal.AC_FAN_RAW), 0);
        assertEquals(22, on.get(Signal.AC_SET_TEMP_RAW), 0);
        Reading off = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.AC_BLOW_LEVEL, 0)).read(1);
        assertEquals("a fan level of 0 is a real reading: the blower is off", 0, off.get(Signal.AC_FAN_RAW), 0);
        Reading bad = new DongfengProbe(new FakePropertySource()
                .putInt(DongfengProbe.AC_BLOW_LEVEL, DongfengProbe.INVALID_BYTE).putInt(DongfengProbe.AC_SET_TEMP, 0)).read(1);
        assertFalse(bad.has(Signal.AC_FAN_RAW));
        assertFalse(bad.has(Signal.AC_SET_TEMP_RAW));
        assertTrue(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.AC_BLOW_LEVEL, 2)).available().contains(Signal.AC_FAN_RAW));
    }

    @Test public void onlyReadablePropertiesAreAdvertised() {
        List<Signal> available = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.CHARGE_TARGET, 95)).available();
        assertEquals(1, available.size());
        assertTrue(available.contains(Signal.CHARGE_TARGET));
        assertTrue(new DongfengProbe(surveyed()).available().contains(Signal.CHARGE_STATE_RAW));
    }

    @Test public void bmsReadingsArriveRawIncludingZeroAndInvalidMarkersAreDropped() {
        FakePropertySource live = new FakePropertySource()
                .putInt(DongfengProbe.BMS_PACK_VOLTAGE, 0)
                .putInt(DongfengProbe.BMS_CHARGED_ENERGY, 12)
                .putInt(DongfengProbe.BATT_HEAT_COOL, 0)
                .putInt(DongfengProbe.SOC_CREDIBILITY, 1)
                .putInt(DongfengProbe.BMS_REMAIN_TIME, DongfengProbe.INVALID_WORD);
        DongfengProbe probe = new DongfengProbe(live);
        Reading reading = probe.read(1);
        assertEquals(0, reading.get(Signal.BMS_PACK_VOLTAGE_RAW), 0);
        assertEquals(12, reading.get(Signal.BMS_CHARGED_ENERGY_RAW), 0);
        assertEquals(0, reading.get(Signal.BATT_HEAT_COOL_RAW), 0);
        assertEquals(1, reading.get(Signal.SOC_CREDIBILITY_RAW), 0);
        assertFalse(reading.has(Signal.BMS_REMAIN_TIME_RAW));
        assertFalse(reading.has(Signal.BMS_PACK_CURRENT_RAW));
        assertTrue(probe.available().contains(Signal.BMS_CHARGED_ENERGY_RAW));
    }

    @Test public void brakePedalIsSentOnlyAsZeroOrOne() {
        Reading pressed = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.BRAKE_PEDAL, 1)).read(1);
        assertEquals(1, pressed.get(Signal.BRAKE_PEDAL), 0);
        Reading lifted = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.BRAKE_PEDAL, 0)).read(1);
        assertEquals(0, lifted.get(Signal.BRAKE_PEDAL), 0);
        Reading invalid = new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.BRAKE_PEDAL, 255)).read(1);
        assertFalse(invalid.has(Signal.BRAKE_PEDAL));
    }
}
