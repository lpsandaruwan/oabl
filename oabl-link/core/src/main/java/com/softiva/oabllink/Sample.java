package com.softiva.oabllink;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * One stored, numbered change: the signals that differed from the last known values, with the time
 * the car reported them. Samples are append-only and never edited (energy-companion.md §3.6). The
 * sequence number increases by one each time, so the box can ask for everything after the last it saw.
 */
public final class Sample {
    private final long seq;
    private final long timeMs;
    private final EnumMap<Signal, Double> values;

    Sample(long seq, long timeMs, EnumMap<Signal, Double> values) {
        this.seq = seq;
        this.timeMs = timeMs;
        this.values = values;
    }

    /** Rebuilds a stored sample, e.g. the SQLite store reading a row back. */
    public static Sample of(long seq, long timeMs, Map<Signal, Double> values) {
        EnumMap<Signal, Double> copy = new EnumMap<>(Signal.class);
        copy.putAll(values);
        return new Sample(seq, timeMs, copy);
    }

    public long seq() { return seq; }
    public long timeMs() { return timeMs; }
    public Map<Signal, Double> values() { return Collections.unmodifiableMap(values); }
}
