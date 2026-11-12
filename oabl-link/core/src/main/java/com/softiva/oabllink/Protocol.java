package com.softiva.oabllink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The read-only link protocol, version 1 (energy-companion.md §3.6). OABL Link answers these reads
 * over Bluetooth, all JSON:
 * <ul>
 *   <li>{@code GET /v1/hello} — who it is and which signals it has;</li>
 *   <li>{@code GET /v1/now} — the latest value and time of every signal;</li>
 *   <li>{@code GET /v1/samples?after=<seq>&limit=<n>} — buffered changes after a sequence number;</li>
 * </ul>
 * This class is the one place that turns readings and samples into JSON and back, so the head unit
 * and the box always agree. It never carries an instruction, only data.
 */
public final class Protocol {
    public static final int SCHEMA = 1, REVISION = 6;

    private Protocol() { }

    /** {@code /v1/hello}: the app, the head unit and the signals it can supply with their units. */
    public static JSONObject hello(String appVersion, String model, String androidVersion, String link, CarProbe probe) {
        JSONObject root = new JSONObject();
        root.put("app_version", appVersion);
        root.put("schema", SCHEMA);
        root.put("revision", REVISION);
        // A stable per-install id, so the box can tell a fresh head-unit install (seq restarts) from this one.
        root.put("link", link);
        root.put("model", model);
        root.put("android", androidVersion);
        root.put("probe", probe.name());
        JSONArray signals = new JSONArray();
        List<Signal> offered=new ArrayList<>(probe.available());if(!offered.contains(Signal.HU_BOOT_ID))offered.add(Signal.HU_BOOT_ID);
        for (Signal signal : offered) {
            JSONObject entry = new JSONObject();
            entry.put("key", signal.key);
            entry.put("unit", signal.unit);
            entry.put("kind", signal.kind.name());
            entry.put("tier", signal.tier);
            signals.put(entry);
        }
        root.put("signals", signals);
        return root;
    }

    /** {@code /v1/now}: the latest value and time of every signal the buffer knows. */
    public static JSONObject now(SampleStore buffer) {
        JSONObject values = new JSONObject();
        long newest = 0;
        for (Signal signal : buffer.known()) {
            JSONObject entry = new JSONObject();
            entry.put("v", number(buffer.latest(signal)));
            entry.put("t", buffer.latestTime(signal));
            values.put(signal.key, entry);
            newest = Math.max(newest, buffer.latestTime(signal));
        }
        JSONObject root = new JSONObject();
        root.put("t", newest);
        root.put("values", values);
        return root;
    }

    /** {@code /v1/samples}: the buffered changes after a sequence number, with the newest seq stored. */
    public static JSONObject samples(SampleStore buffer, long afterSeq, int limit) {
        JSONArray array = new JSONArray();
        for (Sample sample : buffer.after(afterSeq, limit)) array.put(sample(sample));
        JSONObject root = new JSONObject();
        root.put("after", afterSeq);
        root.put("samples", array);
        root.put("latest_seq", buffer.latestSeq());
        return root;
    }

    /** One sample, as it appears in {@code samples} and on the stream. */
    public static JSONObject sample(Sample sample) {
        JSONObject values = new JSONObject();
        for (Map.Entry<Signal, Double> entry : sample.values().entrySet()) {
            values.put(entry.getKey().key, number(entry.getValue()));
        }
        JSONObject root = new JSONObject();
        root.put("seq", sample.seq());
        root.put("t", sample.timeMs());
        root.put("values", values);
        return root;
    }

    /** A sample read back on the box side: the pure data, with unknown keys skipped, not dropped silently. */
    public static final class Incoming {
        public final long seq;
        public final long timeMs;
        public final Map<Signal, Double> values;
        public final List<String> unknownKeys;

        Incoming(long seq, long timeMs, Map<Signal, Double> values, List<String> unknownKeys) {
            this.seq = seq;
            this.timeMs = timeMs;
            this.values = Collections.unmodifiableMap(values);
            this.unknownKeys = Collections.unmodifiableList(unknownKeys);
        }
    }

    /** Parses one sample the box received. A key the box doesn't know is kept aside, never guessed. */
    public static Incoming readSample(JSONObject root) {
        long seq = root.optLong("seq");
        long timeMs = root.optLong("t");
        Map<Signal, Double> values = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        JSONObject object = root.optJSONObject("values");
        if (object != null) {
            // keys() is the iterator both the Maven org.json (tests) and Android's org.json expose.
            for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                Signal signal = Signal.byKey(key);
                if (signal == null) unknown.add(key);
                else if (!object.isNull(key)) values.put(signal, object.getDouble(key));
            }
        }
        return new Incoming(seq, timeMs, values, unknown);
    }

    /** Parses a {@code samples} page the box received, oldest first. */
    public static List<Incoming> readSamples(JSONObject root) {
        List<Incoming> result = new ArrayList<>();
        JSONArray array = root.optJSONArray("samples");
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(readSample(array.getJSONObject(i)));
        return result;
    }

    /** Whole numbers travel as integers (so {@code 62}, not {@code 62.0}); the rest keep one or more decimals. */
    private static Object number(double value) {
        if (Double.isNaN(value)) return JSONObject.NULL;
        if (value == Math.rint(value) && !Double.isInfinite(value)) return (long) value;
        return value;
    }
}
