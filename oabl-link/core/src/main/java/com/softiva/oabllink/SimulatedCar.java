package com.softiva.oabllink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A scripted car for the emulator (energy-companion.md §5, build order 3): it charges, is unplugged,
 * drives, then parks, so the link protocol, the buffer and the box's CarLinkService can all be built
 * and tested before the real probe exists. It only ever reports values; it reads nothing and the
 * numbers resemble the surveyed Nammi 1 (about 40 kWh usable, ~14 kWh/100 km). State is a step
 * function of the elapsed time, so a test can jump the clock and get a deterministic reading.
 */
public final class SimulatedCar implements CarProbe {
    /** The phases, as whole seconds from the start. */
    static final int CHARGE_END = 600, UNPLUG = 660, DRIVE_START = 720, DRIVE_END = 1320, PARK = 1380;

    private final long startMs;
    private final List<Frame> frames = new ArrayList<>();

    private static final class Frame {
        final long elapsedMs;
        final Reading state;
        Frame(long elapsedMs, Reading state) { this.elapsedMs = elapsedMs; this.state = state; }
    }

    public SimulatedCar(long startMs) {
        this.startMs = startMs;
        build();
    }

    @Override public String name() { return "simulated"; }

    @Override public List<Signal> available() {
        return Arrays.asList(Signal.SOC, Signal.READY, Signal.GEAR, Signal.CHARGING, Signal.PLUG,
                Signal.ODOMETER, Signal.RANGE_EST, Signal.TRIP_KM, Signal.TRIP_KWH100, Signal.SPEED,
                Signal.TEMP_OUT, Signal.HVAC_ON, Signal.POWER, Signal.VOLTAGE, Signal.CURRENT,
                Signal.TRIP_B_KM,Signal.TRIP_B_KWH100,Signal.TRIP_C_KM,Signal.TRIP_C_KWH100,
                Signal.CHARGE_TARGET,Signal.CHARGE_REMAINING_RAW,Signal.CHARGE_STATE_RAW);
    }

    @Override public Reading read(long nowMs) {
        long elapsed = nowMs - startMs;
        Frame chosen = frames.get(0);
        for (Frame frame : frames) {
            if (frame.elapsedMs <= elapsed) chosen = frame; else break;
        }
        // Re-stamp the chosen state with the time it is read.
        Reading now = new Reading(nowMs);
        for (Signal signal : chosen.state.values().keySet()) now.set(signal, chosen.state.get(signal));
        return now;
    }

    /** The whole scripted run, so a test can drive the buffer by replaying it. */
    public List<Reading> timeline(int stepSeconds) {
        List<Reading> readings = new ArrayList<>();
        for (long t = 0; t <= PARK; t += stepSeconds) readings.add(read(startMs + t * 1000));
        return readings;
    }

    private void build() {
        double soc = 40, odo = 3851, trip = 0;
        // Charging while parked: the battery rises; nothing moves.
        for (int t = 0; t <= CHARGE_END; t += 10) {
            soc = 40 + 15.0 * t / CHARGE_END; // 40 % → 55 %
            frames.add(frame(t, state(soc, odo, 0, 0, false, Signal.Gear.P, true, true, Double.NaN, 2.6, 213, 12.5)));
        }
        // Plugged but finished, then unplugged, still parked.
        frames.add(frame(CHARGE_END + 10, state(soc, odo, 0, 0, false, Signal.Gear.P, false, true, Double.NaN, 0, 0, 0)));
        frames.add(frame(UNPLUG, state(soc, odo, 0, 0, false, Signal.Gear.P, false, false, Double.NaN, 0, 0, 0)));
        // Driving: a short run with a speed hump; the odometer and trip rise, the battery falls.
        for (int t = DRIVE_START; t <= DRIVE_END; t += 5) {
            double f = (double) (t - DRIVE_START) / (DRIVE_END - DRIVE_START); // 0..1
            double speed = Math.round(60 * Math.sin(Math.PI * f)); // 0 → ~60 → 0 km/h
            double stepKm = speed / 3600.0 * 5;
            odo += stepKm;
            trip += stepKm;
            soc -= stepKm * 0.25; // about 4 km per point
            frames.add(frame(t, drive(soc, odo, speed, trip)));
        }
        // Parked again at the end of the drive.
        frames.add(frame(PARK, state(soc, odo, 0, 0, false, Signal.Gear.P, false, false, trip, 0, 0, 0)));
    }

    private Frame frame(int elapsedSeconds, Reading state) { return new Frame(elapsedSeconds * 1000L, state); }

    private Reading drive(double soc, double odo, double speed, double trip) {
        Reading reading = state(soc, odo, speed, 1, true, Signal.Gear.D, false, false, trip, Double.NaN, Double.NaN, Double.NaN);
        reading.set(Signal.TRIP_KWH100, 14.1);
        return reading;
    }

    private Reading state(double soc, double odo, double speed, double ready, boolean readyBool, Signal.Gear gear,
                          boolean charging, boolean plug, double trip, double power, double voltage, double current) {
        Reading reading = new Reading(startMs);
        reading.set(Signal.TRIP_B_KM,1448.7+(Double.isFinite(trip)?trip:0));reading.set(Signal.TRIP_B_KWH100,10.8);
        reading.set(Signal.TRIP_C_KM,37.9+(Double.isFinite(trip)?trip:0));reading.set(Signal.TRIP_C_KWH100,11.17);
        reading.set(Signal.SOC, round(soc, 1));
        reading.set(Signal.ODOMETER, round(odo, 1));
        reading.set(Signal.RANGE_EST, Math.round(soc * 4));
        reading.set(Signal.SPEED, speed);
        // A speedometer reads a little high, as the real car's does.
        reading.set(Signal.SPEED_DISPLAY, speed > 0 ? Math.round(speed * 1.04 + 1) : 0);
        reading.set(Signal.READY, readyBool);
        reading.set(gear);
        reading.set(Signal.CHARGING, charging);
        reading.set(Signal.CHARGE_STATE_RAW, charging ? 2 : 0);
        reading.set(Signal.CHARGE_TARGET, 95);
        if (charging) reading.set(Signal.CHARGE_REMAINING_RAW, Math.max(0, Math.round((95 - soc) * 10)));
        reading.set(Signal.PLUG, plug);
        reading.set(Signal.TEMP_OUT, 30);
        reading.set(Signal.HVAC_ON, readyBool);
        if (!Double.isNaN(trip)) reading.set(Signal.TRIP_KM, round(trip, 1));
        if (!Double.isNaN(power)) reading.set(Signal.POWER, power);
        if (!Double.isNaN(voltage)) reading.set(Signal.VOLTAGE, voltage);
        if (!Double.isNaN(current)) reading.set(Signal.CURRENT, current);
        return reading;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
