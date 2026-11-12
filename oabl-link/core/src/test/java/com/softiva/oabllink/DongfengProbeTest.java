package com.softiva.oabllink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Dongfeng probe decodes the car's vendor properties into the shared signals. The parked-and-
 * charging case uses the exact raw values the survey read off the car on 20 October 2026.
 */
public class DongfengProbeTest {
    /** Exactly what the survey saw while the car was parked and charging. */
    private FakePropertySource parkedCharging() {
        return new FakePropertySource()
                .putInt(DongfengProbe.SOC, 62)
                .putInt(DongfengProbe.RANGE_ACTUAL, 215)
                .putInt(DongfengProbe.ODOMETER, 3851)        // present only when the app is privileged
                .putInt(DongfengProbe.CHARGE_STATE, 2)
                .putInt(DongfengProbe.PLUG_CONNECTED, 1)
                .putInt(DongfengProbe.DRIVE_READY, 0)
                .putInt(DongfengProbe.GEAR, 5)               // 5 = Park
                .putInt(DongfengProbe.TEMP_OUT, 140)
                .putInt(DongfengProbe.AC_SYSTEM_OFF, 0)
                .putInt(DongfengProbe.CHARGE_POWER, 26)
                .putInt(DongfengProbe.CHARGE_CURRENT, 125)
                .putInt(DongfengProbe.CHARGE_VOLTAGE, 2130)
                .putInt(DongfengProbe.TRIP_A, 0)
                .putInt(DongfengProbe.TRIP_A_ENERGY, 2047);  // the car's "invalid" marker
    }

    @Test public void decodesTheSurveyedParkedCharge() {
        Reading r = new DongfengProbe(parkedCharging()).read(1000);
        assertEquals(62, r.get(Signal.SOC), 0);
        assertEquals(215, r.get(Signal.RANGE_EST), 0);
        assertEquals(3851, r.get(Signal.ODOMETER), 0);
        assertTrue("charging", r.bool(Signal.CHARGING));
        assertTrue("plugged in", r.bool(Signal.PLUG));
        assertFalse("not READY", r.bool(Signal.READY));
        assertEquals(Signal.Gear.P, Signal.Gear.of(r.get(Signal.GEAR)));
        assertEquals("140 → 0.5×140 − 40", 30, r.get(Signal.TEMP_OUT), 0);
        assertTrue("HVAC on when the system-off flag is 0", r.bool(Signal.HVAC_ON));
        assertEquals("2.6 kW", 2.6, r.get(Signal.POWER), 1e-9);
        assertEquals("213 V", 213, r.get(Signal.VOLTAGE), 1e-9);
        assertEquals("12.5 A", 12.5, r.get(Signal.CURRENT), 1e-9);
        assertEquals("trip A not driven since start", 0, r.get(Signal.TRIP_KM), 0);
    }

    @Test public void invalidMarkersAreLeftOutNotDecoded() {
        Reading r = new DongfengProbe(parkedCharging()).read(1000);
        assertFalse("2047 is the trip-energy invalid marker", r.has(Signal.TRIP_KWH100));
        assertFalse("no speed value while parked", r.has(Signal.SPEED));
        FakePropertySource badTemp = parkedCharging().putInt(DongfengProbe.TEMP_OUT, 0xFFFF);
        assertFalse("an all-ones temperature is not decoded", new DongfengProbe(badTemp).read(1000).has(Signal.TEMP_OUT));
    }

    @Test public void drivingReadsSpeedAndReadyButNeverGuessesAnUnknownGear() {
        FakePropertySource driving = new FakePropertySource()
                .putInt(DongfengProbe.SOC, 58)
                .putInt(DongfengProbe.DRIVE_READY, 1)
                .putInt(DongfengProbe.CHARGE_STATE, 0)
                .putInt(DongfengProbe.PLUG_CONNECTED, 0)
                .putInt(DongfengProbe.SPEED, 42)
                .putInt(DongfengProbe.GEAR, 8); // a code we have not confirmed
        Reading r = new DongfengProbe(driving).read(2000);
        assertTrue("READY while driving", r.bool(Signal.READY));
        assertFalse("not charging", r.bool(Signal.CHARGING));
        assertEquals(42, r.get(Signal.SPEED), 0);
        assertFalse("an unconfirmed gear code is left out, never guessed", r.has(Signal.GEAR));
    }

    @Test public void decodesTheDriveModeCodeAndRejectsUnknownOnes() {
        for (int code = 0; code <= 2; code++) {
            FakePropertySource f = new FakePropertySource().putInt(DongfengProbe.DRIVE_MODE, code);
            Reading reading = new DongfengProbe(f).read(0);
            assertTrue("Comfort zero is a valid reading, not a missing value", reading.has(Signal.DRIVE_MODE));
            assertEquals("Comfort 0 / Eco 1 / Sport 2 pass through as the raw code",
                    code, reading.get(Signal.DRIVE_MODE), 0);
        }
        for (int code : new int[]{3, 255, -1}) {
            FakePropertySource unknown = new FakePropertySource().putInt(DongfengProbe.DRIVE_MODE, code);
            assertFalse("a code outside 0..2 is left out, never guessed: " + code,
                    new DongfengProbe(unknown).read(0).has(Signal.DRIVE_MODE));
        }
        assertFalse("a missing mode remains unavailable",
                new DongfengProbe(new FakePropertySource()).read(0).has(Signal.DRIVE_MODE));
    }

    @Test public void hvacUsesTheSystemOffFlagAndOmitsInvalidMarkers() {
        assertTrue(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.AC_SYSTEM_OFF, 0)).read(0).bool(Signal.HVAC_ON));
        assertFalse(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.AC_SYSTEM_OFF, 1)).read(0).bool(Signal.HVAC_ON));
        assertFalse(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.AC_SYSTEM_OFF, 255)).read(0).has(Signal.HVAC_ON));
    }

    @Test public void speedFallsBackToTheStandardProperty() {
        FakePropertySource f = new FakePropertySource().putFloat(DongfengProbe.SPEED_FALLBACK, 33.0f);
        assertEquals(33, new DongfengProbe(f).read(0).get(Signal.SPEED), 1e-6);
    }

    @Test public void displaySpeedLearnsKilometresPerHourFromTheRealSpeed() {
        FakePropertySource f = new FakePropertySource().putInt(DongfengProbe.SPEED, 60).putFloat(DongfengProbe.SPEED_DISPLAY, 63f);
        DongfengProbe probe = new DongfengProbe(f);
        assertTrue(probe.available().contains(Signal.SPEED_DISPLAY));
        Reading r = probe.read(0);
        assertEquals(60, r.get(Signal.SPEED), 0);
        assertEquals(63, r.get(Signal.SPEED_DISPLAY), 1e-6);
        // Once learnt, slow speeds below the learning threshold are shown too.
        f.putInt(DongfengProbe.SPEED, 8).putFloat(DongfengProbe.SPEED_DISPLAY, 9f);
        assertEquals(9, probe.displaySpeedKph(), 1e-6);
    }

    @Test public void displaySpeedLearnsMetresPerSecond() {
        // Android's own unit: 17.5 m/s = 63 km/h against a real 60 km/h.
        FakePropertySource f = new FakePropertySource().putInt(DongfengProbe.SPEED, 60).putFloat(DongfengProbe.SPEED_DISPLAY, 17.5f);
        assertEquals(63, new DongfengProbe(f).read(0).get(Signal.SPEED_DISPLAY), 1e-4);
    }

    @Test public void displaySpeedIsWithheldUntilItsUnitIsKnown() {
        FakePropertySource f = new FakePropertySource().putInt(DongfengProbe.SPEED, 10).putFloat(DongfengProbe.SPEED_DISPLAY, 11f);
        DongfengProbe probe = new DongfengProbe(f);
        assertFalse("too slow to tell the unit", probe.read(0).has(Signal.SPEED_DISPLAY));
        f.putInt(DongfengProbe.SPEED, 0).putFloat(DongfengProbe.SPEED_DISPLAY, 0f);
        assertEquals("a standstill needs no unit", 0, probe.read(0).get(Signal.SPEED_DISPLAY), 0);
    }

    @Test public void implausibleDisplaySpeedIsNeverShown() {
        FakePropertySource f = new FakePropertySource().putInt(DongfengProbe.SPEED, 60).putFloat(DongfengProbe.SPEED_DISPLAY, 63f);
        DongfengProbe probe = new DongfengProbe(f);
        probe.read(0); // learns km/h
        f.putFloat(DongfengProbe.SPEED_DISPLAY, 40f); // reads low: a speedometer never does
        assertFalse(probe.read(0).has(Signal.SPEED_DISPLAY));
        f.putFloat(DongfengProbe.SPEED_DISPLAY, 0f); // stuck at zero while moving
        assertFalse(probe.read(0).has(Signal.SPEED_DISPLAY));
        assertTrue(Double.isNaN(new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.SPEED, 60)).displaySpeedKph()));
    }

    @Test public void anOrdinaryAppCannotReadTheOdometerOrGear() {
        // CAR_ENERGY is grantable; CAR_MILEAGE (odometer, gear) is not. The probe simply omits them.
        FakePropertySource ordinary = new FakePropertySource()
                .putInt(DongfengProbe.SOC, 62)
                .putInt(DongfengProbe.RANGE_ACTUAL, 215)
                .putInt(DongfengProbe.CHARGE_STATE, 2)
                .putInt(DongfengProbe.PLUG_CONNECTED, 1);
        DongfengProbe probe = new DongfengProbe(ordinary);
        assertTrue(probe.available().contains(Signal.SOC));
        assertTrue(probe.available().contains(Signal.RANGE_EST));
        assertTrue(probe.available().contains(Signal.CHARGING));
        assertFalse("odometer needs a privileged permission", probe.available().contains(Signal.ODOMETER));
        assertFalse("gear needs a privileged permission", probe.available().contains(Signal.GEAR));
        Reading r = probe.read(1000);
        assertEquals(62, r.get(Signal.SOC), 0);
        assertFalse(r.has(Signal.ODOMETER));
    }

    @Test public void finerSpeedIsUsedWhileItAgreesWithTheWholeNumber() {
        DongfengProbe probe = new DongfengProbe(new FakePropertySource()
                .putInt(DongfengProbe.SPEED, 43).putInt(DongfengProbe.SPEED_FINE, 4362));
        assertEquals(43.62, probe.read(1).get(Signal.SPEED), 1e-9);
        assertEquals(43.62, probe.speedKph(), 1e-9);
    }

    @Test public void theWholeNumberStandsWhenFinerSpeedIsMissingOrDisagrees() {
        assertEquals(43, new DongfengProbe(new FakePropertySource().putInt(DongfengProbe.SPEED, 43)).speedKph(), 0);
        for (int bad : new int[]{0, 65535, -1, 4700}) {
            assertEquals(43, new DongfengProbe(new FakePropertySource()
                    .putInt(DongfengProbe.SPEED, 43).putInt(DongfengProbe.SPEED_FINE, bad)).speedKph(), 0);
        }
    }
}
