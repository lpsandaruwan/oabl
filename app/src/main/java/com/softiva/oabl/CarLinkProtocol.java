package com.softiva.oabl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parses what OABL Link sends (energy-companion.md §3.6): {@code /v1/hello}, {@code /v1/now},
 * {@code /v1/samples} and each streamed sample. It only reads JSON into plain values; it keeps no
 * state. A signal key the box doesn't know is skipped (never mixed in), and a whole-number value
 * parses the same as a decimal one. Everything here is data the car reported, never an instruction.
 */
final class CarLinkProtocol {
    static final int SCHEMA = 1, REVISION = 3;

    private CarLinkProtocol() { }

    /** {@code /v1/hello}: who the head unit is and which signals it offers. */
    static final class Hello {
        int schema, revision;
        String probe = "", model = "", androidVersion = "", appVersion = "";
        /** The head unit's per-install id; a change means a reinstall whose seq restarts at 1. */
        String link = "";
        final List<CarSignal> signals = new ArrayList<>();
        boolean compatible() { return schema == SCHEMA; }
        boolean supportedSource(boolean allowSimulation) {
            return "dongfeng-dfcar".equals(probe) || (allowSimulation && "simulated".equals(probe));
        }
    }

    static Hello parseHello(JSONObject root) {
        Hello hello = new Hello();
        hello.schema = root.optInt("schema");
        hello.revision = root.optInt("revision");
        hello.probe = root.optString("probe");
        hello.model = root.optString("model");
        hello.androidVersion = root.optString("android");
        hello.appVersion = root.optString("app_version");
        hello.link = root.optString("link");
        JSONArray signals = root.optJSONArray("signals");
        if (signals != null) {
            for (int i = 0; i < signals.length(); i++) {
                JSONObject entry = signals.optJSONObject(i);
                if (entry == null) continue;
                CarSignal signal = CarSignal.byKey(entry.optString("key"));
                if (signal != null) hello.signals.add(signal);
            }
        }
        return hello;
    }

    /** {@code /v1/now}: the latest value and time of every signal. */
    static final class Now {
        long time;
        final EnumMap<CarSignal, Double> values = new EnumMap<>(CarSignal.class);
        final EnumMap<CarSignal, Long> times = new EnumMap<>(CarSignal.class);
    }

    static Now parseNow(JSONObject root) {
        Now now = new Now();
        now.time = root.optLong("t");
        JSONObject values = root.optJSONObject("values");
        if (values != null) {
            for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                String key = it.next();
                CarSignal signal = CarSignal.byKey(key);
                if (signal == null) continue;
                JSONObject entry = values.optJSONObject(key);
                if (entry == null || entry.isNull("v")) continue;
                double value = entry.optDouble("v", Double.NaN);
                if (!CarSignal.plausible(signal, value)) continue;
                now.values.put(signal, value);
                now.times.put(signal, entry.optLong("t", now.time));
            }
        }
        return now;
    }

    /** One numbered change, as it appears in {@code samples} and on the stream. */
    static final class CarSample {
        long seq;
        long time;
        final EnumMap<CarSignal, Double> values = new EnumMap<>(CarSignal.class);
    }

    static CarSample parseSample(JSONObject root) {
        CarSample sample = new CarSample();
        sample.seq = root.optLong("seq");
        sample.time = root.optLong("t");
        JSONObject values = root.optJSONObject("values");
        if (values != null) {
            for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                String key = it.next();
                CarSignal signal = CarSignal.byKey(key);
                if (signal == null || values.isNull(key)) continue;
                double value = values.optDouble(key, Double.NaN);
                if (CarSignal.plausible(signal, value)) sample.values.put(signal, value);
            }
        }
        return sample;
    }

    /** {@code /v1/samples}: a page of changes, oldest first, plus the newest sequence the head unit has. */
    static final class SamplesPage {
        long latestSeq;
        final List<CarSample> samples = new ArrayList<>();
    }

    static SamplesPage parseSamples(JSONObject root) {
        SamplesPage page = new SamplesPage();
        page.latestSeq = root.optLong("latest_seq");
        JSONArray array = root.optJSONArray("samples");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry != null) page.samples.add(parseSample(entry));
            }
        }
        return page;
    }

    /** The data of a sample as a {@code key -> value} map, for logging or persistence. */
    static Map<CarSignal, Double> valuesOf(CarSample sample) { return sample.values; }

    /**
     * The sample's values as a compact JSON object, for the {@code car_sample.data} column. Whole
     * numbers become integers (so {@code 62}, not {@code 62.0}), matching what OABL Link sends, so a
     * value read back from the box's own store looks the same as one from the car.
     */
    static String encodeValues(CarSample sample) {
        JSONObject object = new JSONObject();
        try {
            for (CarSignal signal : sample.values.keySet()) {
                double value = sample.values.get(signal);
                if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                object.put(signal.key, value == Math.rint(value) ? (Object) (long) value : (Object) value);
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e); // finite doubles don't throw; this never happens
        }
        return object.toString();
    }

    /** Reads a {@code car_sample.data} JSON back into values; unknown keys and bad JSON yield nothing. */
    static EnumMap<CarSignal, Double> parseValues(String json) {
        EnumMap<CarSignal, Double> values = new EnumMap<>(CarSignal.class);
        try {
            JSONObject object = new JSONObject(json);
            for (Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                CarSignal signal = CarSignal.byKey(key);
                if (signal != null && !object.isNull(key)) {
                    double value = object.optDouble(key, Double.NaN);
                    if (CarSignal.plausible(signal, value)) values.put(signal, value);
                }
            }
        } catch (org.json.JSONException ignored) {
            // malformed row: return what parsed (possibly nothing)
        }
        return values;
    }
}
