package com.softiva.oabllink.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.softiva.oabllink.Reading;
import com.softiva.oabllink.Sample;
import com.softiva.oabllink.SampleStore;
import com.softiva.oabllink.Signal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * The head unit's bridge store (energy-companion.md §3.6), the same behaviour as the in-memory
 * {@link com.softiva.oabllink.SampleBuffer} but in SQLite so it survives a restart or the car's task
 * killer. It is NOT an archive: the AI box keeps the full history in energy.db, and the head unit
 * holds only a short window so the box can catch up after a reboot. {@code car_sample} holds each
 * numbered change; {@code car_latest} holds the latest value and time per signal, for
 * {@code /v1/now}. Rows are append-only and never edited.
 *
 * <p>An in-memory mirror of the latest values makes change-detection and {@code now} fast and keeps
 * the semantics identical to the tested in-memory store.
 */
public final class SqliteSampleStore extends SQLiteOpenHelper implements SampleStore {
    static final String NAME = "oabllink.db";
    /** The bridge window: enough to cover a box reboot or reconnect, not an archive. */
    static final long BRIDGE_MS = 6L * 3600 * 1000; // 6 hours

    private final long retentionMs;
    private final EnumMap<Signal, Double> lastValue = new EnumMap<>(Signal.class);
    private final EnumMap<Signal, Long> lastTime = new EnumMap<>(Signal.class);
    private long nextSeq = 1;
    private long newestMs;
    /** Old rows go in batches: a delete in every transaction only adds flash writes. */
    private static final long PRUNE_MS = 5 * 60_000L;
    private long lastPruneMs;

    public SqliteSampleStore(Context context) { this(context, BRIDGE_MS); }

    public SqliteSampleStore(Context context, long retentionMs) {
        super(context.getApplicationContext(), NAME, null, 1);
        this.retentionMs = retentionMs;
        setWriteAheadLoggingEnabled(true); // one small append per row, instead of a journal file each time
        load();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE car_sample (seq INTEGER PRIMARY KEY, t INTEGER NOT NULL, data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE car_latest (sig TEXT PRIMARY KEY, v REAL NOT NULL, t INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX car_sample_t ON car_sample(t)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first; later versions only add, keeping every raw sample.
    }

    /** Restores the running state (next seq, newest time, latest values) from the tables. */
    private void load() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT MAX(seq), MAX(t) FROM car_sample", null)) {
            if (c.moveToFirst()) {
                nextSeq = (c.isNull(0) ? 0 : c.getLong(0)) + 1;
                newestMs = c.isNull(1) ? 0 : c.getLong(1);
            }
        }
        try (Cursor c = db.query("car_latest", new String[]{"sig", "v", "t"}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                Signal signal = Signal.byKey(c.getString(0));
                if (signal == null) continue;
                lastValue.put(signal, c.getDouble(1));
                lastTime.put(signal, c.getLong(2));
            }
        }
    }

    private double rowSpeed = Double.NaN;

    @Override public synchronized Sample record(Reading reading) {
        if (reading.isEmpty() || reading.timeMs() < newestMs) return null;
        EnumMap<Signal, Double> changed = new EnumMap<>(Signal.class);
        for (Signal signal : reading.values().keySet()) {
            double value = reading.get(signal);
            Double previous = lastValue.get(signal);
            if (signal == Signal.SPEED ? com.softiva.oabllink.SampleBuffer.speedMoved(rowSpeed, value) : previous == null || previous != value) {
                changed.put(signal, value);
                if (signal == Signal.SPEED) rowSpeed = value;
            }
            lastValue.put(signal, value);
            lastTime.put(signal, reading.timeMs());
        }
        if (reading.has(Signal.HU_BOOT_ID)) {
            if (changed.isEmpty() && reading.timeMs() - newestMs < com.softiva.oabllink.SampleBuffer.FULL_ROW_MS) return null;
            changed.putAll(reading.values());
        }
        if (changed.isEmpty()) return null;

        long seq = nextSeq++;
        newestMs = reading.timeMs();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject data = new JSONObject();
            for (Map.Entry<Signal, Double> entry : changed.entrySet()) data.put(entry.getKey().key, entry.getValue());
            ContentValues row = new ContentValues();
            row.put("seq", seq);
            row.put("t", reading.timeMs());
            row.put("data", data.toString());
            db.insertOrThrow("car_sample", null, row);
            for (Signal signal : changed.keySet()) {
                ContentValues latest = new ContentValues();
                latest.put("sig", signal.key);
                latest.put("v", changed.get(signal));
                latest.put("t", reading.timeMs());
                db.insertWithOnConflict("car_latest", null, latest, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (newestMs - lastPruneMs >= PRUNE_MS) {
                db.delete("car_sample", "t < ?", new String[]{Long.toString(newestMs - retentionMs)});
                lastPruneMs = newestMs;
            }
            db.setTransactionSuccessful();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        } finally {
            db.endTransaction();
        }
        return Sample.of(seq, reading.timeMs(), changed);
    }

    @Override public synchronized List<Sample> after(long afterSeq, int limit) {
        List<Sample> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("car_sample", new String[]{"seq", "t", "data"},
                "seq > ?", new String[]{Long.toString(afterSeq)}, null, null, "seq ASC", Integer.toString(Math.max(0, limit)))) {
            while (c.moveToNext()) {
                Map<Signal, Double> values = new LinkedHashMap<>();
                try {
                    JSONObject data = new JSONObject(c.getString(2));
                    for (java.util.Iterator<String> it = data.keys(); it.hasNext(); ) {
                        String key = it.next();
                        Signal signal = Signal.byKey(key);
                        if (signal != null) values.put(signal, data.getDouble(key));
                    }
                } catch (org.json.JSONException ignored) {
                    continue;
                }
                result.add(Sample.of(c.getLong(0), c.getLong(1), values));
            }
        }
        return result;
    }

    @Override public synchronized long latestSeq() { return nextSeq - 1; }

    @Override public synchronized int size() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM car_sample", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    @Override public synchronized boolean has(Signal signal) { return lastValue.containsKey(signal); }

    @Override public synchronized double latest(Signal signal) {
        Double value = lastValue.get(signal);
        return value == null ? Double.NaN : value;
    }

    @Override public synchronized long latestTime(Signal signal) {
        Long time = lastTime.get(signal);
        return time == null ? 0 : time;
    }

    @Override public synchronized List<Signal> known() { return new ArrayList<>(lastValue.keySet()); }
}
