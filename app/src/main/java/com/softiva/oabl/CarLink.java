package com.softiva.oabl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/**
 * The box's link client, free of any socket so it is tested on the JVM (energy-companion.md §3.5,
 * §3.8). It holds the pairing key and the last sequence number it has seen, signs requests, and
 * folds in {@code now}, {@code samples} and streamed samples, keeping the latest value and time of
 * every signal. It drives catch-up: when a streamed sample skips ahead, it asks for the gap with
 * {@code /v1/samples?after=<lastSeq>} rather than guessing the missing rows.
 *
 * <p>It never overwrites a driver's entered reading and holds no opinion about the data; merging live
 * and manual readings, and writing the car_sample rows, is the service's job on top of this.
 */
final class CarLink {
    /** What a streamed sample was: applied in order, a duplicate to ignore, or a gap to catch up. */
    enum Ingest { APPLIED, DUPLICATE, GAP }

    static final class Headers {
        final long time;
        final String nonce;
        final String signature;
        Headers(long time, String nonce, String signature) { this.time = time; this.nonce = nonce; this.signature = signature; }
    }

    private byte[] key;
    private long lastSeq;
    private final EnumMap<CarSignal, Double> latest = new EnumMap<>(CarSignal.class);
    private final EnumMap<CarSignal, Long> latestTime = new EnumMap<>(CarSignal.class);
    /** Stored times stay on the head-unit clock for ordering; {@link #latestTime} reports them in box time. */
    final CarClockOffset clock = new CarClockOffset();

    /** Pairs with the 6-digit code the head unit shows; replayed codes just re-derive the same key. */
    synchronized void pair(String code) { key = CarLinkPairing.sharedKey(code); }

    synchronized void forget() { key = null; lastSeq = 0; latest.clear(); latestTime.clear(); }

    synchronized boolean paired() { return key != null; }

    synchronized long lastSeq() { return lastSeq; }

    /** Resumes catch-up from what the box already stored for the current head-unit link. */
    synchronized void seedLastSeq(long seq) { lastSeq = Math.max(0, seq); }
    synchronized void clearReadings() { lastSeq = 0; latest.clear(); latestTime.clear(); }

    /** The signature headers for a request; a fresh nonce each time so none can be replayed. */
    synchronized Headers sign(String method, String target, long nowMs) {
        String nonce = UUID.randomUUID().toString();
        String signature = CarLinkPairing.sign(key, method, target, "", nowMs, nonce);
        return new Headers(nowMs, nonce, signature);
    }

    /** The catch-up request for everything the head unit has after the last sample seen. */
    synchronized String catchUpTarget(int limit) { return "/v1/samples?after=" + lastSeq + "&limit=" + limit; }

    /** Folds in a {@code now} snapshot: the latest value and time of each signal. Never advances seq. */
    synchronized void ingestNow(CarLinkProtocol.Now now) { ingestNow(now, System.currentTimeMillis()); }

    synchronized void ingestNow(CarLinkProtocol.Now now, long receivedAt) {
        clock.observe(now.time, receivedAt);
        for (CarSignal signal : now.values.keySet()) {
            long time = now.times.getOrDefault(signal, now.time);
            if (time < rawTime(signal)) continue;
            latest.put(signal, now.values.get(signal));
            latestTime.put(signal, time);
        }
    }

    /** Applies a catch-up page in order, advancing the sequence; returns how many new samples applied. */
    synchronized int ingestSamples(CarLinkProtocol.SamplesPage page) {
        int applied = 0;
        for (CarLinkProtocol.CarSample sample : page.samples) {
            if (sample.seq <= lastSeq) continue;
            apply(sample);
            lastSeq = sample.seq;
            applied++;
        }
        return applied;
    }

    /**
     * Folds in one streamed sample. In order, it applies and advances. A sample already seen is a
     * duplicate. A sample that skips ahead means the box missed some: it applies the newest values so
     * the screen stays live, but leaves the sequence where it was so the caller fills the gap with
     * {@link #catchUpTarget}.
     */
    synchronized Ingest ingestStreamSample(CarLinkProtocol.CarSample sample) {
        if (sample.seq <= lastSeq) return Ingest.DUPLICATE;
        clock.observe(sample.time, System.currentTimeMillis()); // streamed as it is read: a live stamp
        if (sample.seq == lastSeq + 1) {
            apply(sample);
            lastSeq = sample.seq;
            return Ingest.APPLIED;
        }
        apply(sample); // keep the display fresh, but don't skip the sequence
        return Ingest.GAP;
    }

    private void apply(CarLinkProtocol.CarSample sample) {
        for (CarSignal signal : sample.values.keySet()) {
            if (sample.time < rawTime(signal)) continue;
            latest.put(signal, sample.values.get(signal));
            latestTime.put(signal, sample.time);
        }
    }

    synchronized boolean has(CarSignal signal) { return latest.containsKey(signal); }

    synchronized double latest(CarSignal signal) {
        Double value = latest.get(signal);
        return value == null ? Double.NaN : value;
    }

    /** When the signal was read, in box time. */
    synchronized long latestTime(CarSignal signal) { return clock.toLocal(rawTime(signal)); }

    private long rawTime(CarSignal signal) {
        Long time = latestTime.get(signal);
        return time == null ? 0 : time;
    }

    synchronized List<CarSignal> known() { return new ArrayList<>(latest.keySet()); }
}
