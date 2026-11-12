package com.softiva.oabllink;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A set of signal values read from the car at one moment. It is sparse: only the signals the probe
 * actually read are present, so an absent signal means "not read", never zero. The probe stamps the
 * time the car reported the values.
 */
public final class Reading {
    private final long timeMs;
    private final EnumMap<Signal, Double> values = new EnumMap<>(Signal.class);

    public Reading(long timeMs) { this.timeMs = timeMs; }

    public long timeMs() { return timeMs; }

    public Reading set(Signal signal, double value) {
        values.put(signal, value);
        return this;
    }

    public Reading set(Signal signal, boolean on) { return set(signal, on ? 1 : 0); }

    public Reading set(Signal.Gear gear) { return set(Signal.GEAR, gear.code); }

    public boolean has(Signal signal) { return values.containsKey(signal); }

    /** The value, or NaN when this reading did not include the signal. */
    public double get(Signal signal) {
        Double value = values.get(signal);
        return value == null ? Double.NaN : value;
    }

    public boolean bool(Signal signal) { return get(signal) != 0 && !Double.isNaN(get(signal)); }

    public Map<Signal, Double> values() { return Collections.unmodifiableMap(values); }

    public boolean isEmpty() { return values.isEmpty(); }
}
