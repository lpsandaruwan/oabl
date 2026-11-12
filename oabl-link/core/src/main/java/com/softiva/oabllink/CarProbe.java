package com.softiva.oabllink;

import java.util.List;

/**
 * A source of car readings. There is one real probe per head unit (chosen by the survey) and a
 * {@link SimulatedCar} for the emulator, so the whole data path is built and tested before the real
 * probe exists. A probe only ever reads; it never writes a vehicle property or sends a command.
 */
public interface CarProbe {
    /** A short name of the probe in use, for {@code /v1/hello}, e.g. {@code dongfeng-dfcar}. */
    String name();

    /** The signals this head unit can supply, so the box knows what to expect. */
    List<Signal> available();

    /** The latest values the car reports, stamped with the car's time. Never null; may be empty. */
    Reading read(long nowMs);

    /** The car's speed read now, for low-latency display between samples; NaN when unavailable. */
    default double speedKph() {
        Reading reading = read(System.currentTimeMillis());
        return reading.has(Signal.SPEED) ? reading.get(Signal.SPEED) : Double.NaN;
    }

    /** The speed the car's own speedometer shows, read now; NaN when unavailable or not yet trusted. */
    default double displaySpeedKph() {
        Reading reading = read(System.currentTimeMillis());
        return reading.has(Signal.SPEED_DISPLAY) ? reading.get(Signal.SPEED_DISPLAY) : Double.NaN;
    }
}
