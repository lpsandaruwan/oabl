package com.softiva.oabllink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;

/**
 * The rolling store OABL Link keeps and serves (energy-companion.md §3.6). The AI box is the store
 * of record: it keeps everything in energy.db. The head unit only holds a short BRIDGE window — long
 * enough for the box to catch up the few samples it missed while it rebooted or briefly lost Wi-Fi —
 * not a week of history. Each change has an increasing sequence number, so the box catches up with
 * {@code /v1/samples?after=<seq>}. A value is recorded only when it changes, so a still signal costs
 * nothing. The latest value and time of every signal back {@code /v1/now}.
 *
 * <p>This is the pure in-memory model; on the head unit the same rows live in SQLite. It is not
 * synchronized: OABL Link records and serves on one thread.
 */
public final class SampleBuffer implements SampleStore {
    /** The bridge window: enough to cover a box reboot or reconnect, not an archive. */
    static final long BRIDGE_MS = 6L * 3600 * 1000; // 6 hours
    /**
     * A source that sends its boot identity gets whole rows, so every signal stays fresh on the box. Written
     * at once when anything changes and otherwise this often: well inside the box's 60 s freshness and 90 s gap
     * limits, without a row a second while the car sits parked or charging.
     */
    public static final long FULL_ROW_MS = 15_000;
    private double rowSpeed = Double.NaN;

    /**
     * Speed comes in hundredths of a km/h and changes on nearly every read. A row for each 1 km/h, and for
     * stopping or pulling away, keeps history the size it was; the latest value still follows every read.
     */
    public static boolean speedMoved(double lastRow, double value) {
        return Double.isNaN(lastRow) || Math.abs(value - lastRow) >= 1 || (value == 0) != (lastRow == 0);
    }

    private final long retentionMs;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final EnumMap<Signal, Double> lastValue = new EnumMap<>(Signal.class);
    private final EnumMap<Signal, Long> lastTime = new EnumMap<>(Signal.class);
    private long nextSeq = 1;

    public SampleBuffer() { this(BRIDGE_MS); }

    public SampleBuffer(long retentionMs) { this.retentionMs = retentionMs; }

    /**
     * Records the signals in the reading that changed since the last known value. Returns the new
     * sample, or null when nothing changed (so a still car adds no rows). Readings must arrive in
     * time order; an out-of-order reading is dropped.
     */
    public Sample record(Reading reading) {
        if (reading.isEmpty()) return null;
        if (!samples.isEmpty() && reading.timeMs() < samples.peekLast().timeMs()) return null;
        EnumMap<Signal, Double> changed = new EnumMap<>(Signal.class);
        for (Signal signal : reading.values().keySet()) {
            double value = reading.get(signal);
            Double previous = lastValue.get(signal);
            boolean write = signal == Signal.SPEED ? speedMoved(rowSpeed, value) : previous == null || previous != value;
            if (write) {
                changed.put(signal, value);
                if (signal == Signal.SPEED) rowSpeed = value;
            }
            lastValue.put(signal, value);
            lastTime.put(signal, reading.timeMs());
        }
        if (reading.has(Signal.HU_BOOT_ID)) {
            boolean due = samples.isEmpty() || reading.timeMs() - samples.peekLast().timeMs() >= FULL_ROW_MS;
            if (changed.isEmpty() && !due) return null;
            changed.putAll(reading.values());
        }
        if (changed.isEmpty()) return null;
        Sample sample = new Sample(nextSeq++, reading.timeMs(), changed);
        samples.addLast(sample);
        prune(sample.timeMs());
        return sample;
    }

    /** Drops samples older than the retention window, measured back from the newest sample's time. */
    private void prune(long newestMs) {
        long cutoff = newestMs - retentionMs;
        while (!samples.isEmpty() && samples.peekFirst().timeMs() < cutoff) samples.removeFirst();
    }

    /** Samples with a sequence number above {@code afterSeq}, oldest first, at most {@code limit}. */
    public List<Sample> after(long afterSeq, int limit) {
        List<Sample> result = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.seq() <= afterSeq) continue;
            result.add(sample);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** The highest sequence number stored, or 0 when the buffer is empty. */
    public long latestSeq() { return samples.isEmpty() ? 0 : samples.peekLast().seq(); }

    public int size() { return samples.size(); }

    public boolean has(Signal signal) { return lastValue.containsKey(signal); }

    /** The latest value of a signal, or NaN when it has never been read. */
    public double latest(Signal signal) {
        Double value = lastValue.get(signal);
        return value == null ? Double.NaN : value;
    }

    /** When the latest value of a signal was read, or 0 when it has never been read. */
    public long latestTime(Signal signal) {
        Long time = lastTime.get(signal);
        return time == null ? 0 : time;
    }

    /** Every signal that has a latest value, for {@code /v1/now}. */
    public List<Signal> known() {
        return new ArrayList<>(lastValue.keySet());
    }
}
