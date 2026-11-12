package com.softiva.oabllink;

import java.util.List;

/**
 * The rolling store OABL Link records into and serves from. {@link SampleBuffer} is the in-memory
 * one used in tests and with the simulated car; on the head unit a SQLite-backed store with the same
 * behaviour keeps the 7 days across restarts. The server and the protocol work only against this
 * interface, so the same routing is tested on the JVM and runs over SQLite on the car.
 */
public interface SampleStore {
    /** Records the signals that changed; returns the new sample, or null when nothing changed. */
    Sample record(Reading reading);

    /** Samples with a sequence number above {@code afterSeq}, oldest first, at most {@code limit}. */
    List<Sample> after(long afterSeq, int limit);

    /** The highest sequence number stored, or 0 when empty. */
    long latestSeq();

    int size();

    boolean has(Signal signal);

    /** The latest value of a signal, or NaN when it has never been read. */
    double latest(Signal signal);

    /** When the latest value of a signal was read, or 0 when it has never been read. */
    long latestTime(Signal signal);

    /** Every signal that has a latest value. */
    List<Signal> known();
}
