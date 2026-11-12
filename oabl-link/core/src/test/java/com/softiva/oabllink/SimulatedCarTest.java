package com.softiva.oabllink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The scripted car charges, is unplugged, drives and parks, so the whole path works before the real probe. */
public class SimulatedCarTest {
    private static final long START = 1_000_000L;

    private Reading at(SimulatedCar car, int seconds) { return car.read(START + seconds * 1000L); }

    @Test public void chargingWhileParked() {
        SimulatedCar car = new SimulatedCar(START);
        Reading early = at(car, 60);
        Reading late = at(car, 540);
        assertTrue("charging", early.bool(Signal.CHARGING));
        assertFalse("not ready while charging", early.bool(Signal.READY));
        assertEquals(0, early.get(Signal.SPEED), 0);
        assertEquals(Signal.Gear.P, Signal.Gear.of(early.get(Signal.GEAR)));
        assertTrue("battery rises while charging", late.get(Signal.SOC) > early.get(Signal.SOC));
        assertTrue("plugged in", early.bool(Signal.PLUG));
    }

    @Test public void drivingRaisesTheOdometerAndLowersTheBattery() {
        SimulatedCar car = new SimulatedCar(START);
        Reading parked = at(car, SimulatedCar.UNPLUG + 10);
        Reading midDrive = at(car, (SimulatedCar.DRIVE_START + SimulatedCar.DRIVE_END) / 2);
        assertFalse("unplugged before the drive", parked.bool(Signal.PLUG));
        assertTrue("ready while driving", midDrive.bool(Signal.READY));
        assertEquals(Signal.Gear.D, Signal.Gear.of(midDrive.get(Signal.GEAR)));
        assertTrue("moving", midDrive.get(Signal.SPEED) > 0);
        assertTrue("odometer climbed", midDrive.get(Signal.ODOMETER) > parked.get(Signal.ODOMETER));
        assertTrue("battery fell from the charge peak", midDrive.get(Signal.SOC) < parked.get(Signal.SOC));
        assertEquals("the car's own consumption figure", 14.1, midDrive.get(Signal.TRIP_KWH100), 1e-9);
    }

    @Test public void parksAtTheEnd() {
        SimulatedCar car = new SimulatedCar(START);
        Reading end = at(car, SimulatedCar.PARK);
        assertFalse(end.bool(Signal.READY));
        assertEquals(Signal.Gear.P, Signal.Gear.of(end.get(Signal.GEAR)));
        assertEquals(0, end.get(Signal.SPEED), 0);
        assertTrue("a trip was recorded", end.get(Signal.TRIP_KM) > 0);
    }

    @Test public void theWholeRunFeedsTheBuffer() {
        SimulatedCar car = new SimulatedCar(START);
        SampleBuffer buffer = new SampleBuffer();
        int samples = 0;
        for (Reading reading : car.timeline(5)) if (buffer.record(reading) != null) samples++;
        assertTrue("the run produced samples", samples > 20);
        assertEquals("sequence numbers match the stored samples", samples, buffer.latestSeq());
        assertTrue("drove past the starting odometer", buffer.latest(Signal.ODOMETER) > 3851);
        assertTrue(buffer.latest(Signal.SOC) > 40 && buffer.latest(Signal.SOC) < 55);
        assertFalse("ends unplugged and parked", buffer.latest(Signal.CHARGING) != 0);
    }
}
