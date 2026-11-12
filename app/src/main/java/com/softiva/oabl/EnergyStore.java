package com.softiva.oabl;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local storage for the EV energy model (energy-model.md §2 and §11): a separate {@code energy.db}
 * for raw records, so nothing here touches the music library, the trip log, the equalizer or the
 * launcher settings. The vehicle profile lives in the {@code energy_profile} preferences.
 *
 * <p>Raw records are never edited: a correction is a new check-in that supersedes the old one.
 * Intervals and aggregates are always recomputed from these rows. GPS counters are global and only
 * advance while a journey is active; every check-in keeps a copy of them, so the distance and GPS
 * quality between any two check-ins is a subtraction.
 *
 * <p>Schema 2 (energy-companion.md §2 and §3.8) adds the optional charge log, the odometer and
 * trip-computer figures typed with a check-in, a {@code source} on check-ins and charges
 * ({@code entered} today), and the live car tables: {@code car_sample} (raw, never edited) and
 * {@code car_session} (drives, charges and parked periods from the car, always recomputable).
 */
final class EnergyStore extends SQLiteOpenHelper {
    static final String NAME = "energy.db";
    static final String PROFILE = "energy_profile";
    static final int VERSION = 4;
    static final String[] TABLES = {"checkin", "session", "idle", "meta", "charge", "car_sample", "car_session", "car_frame", "car_event", "car_energy_state", "car_charge_review", "charger", "car_summary", "car_drive_join", "energy_retained"};
    /** Complete backups include every table, including permanently retained raw telemetry. */
    static final String[] NOT_BACKED_UP = {};
    /** Where a record came from. Everything is typed in today; live car data would add "car". */
    static final String ENTERED = "entered";
    static final String[] COUNTERS = {"meters", "moving", "still", "unknown", "active", "poor"};

    private static EnergyStore instance;
    final Context context;
    private final String databaseName;
    private SQLiteDatabase remoteRead;
    boolean remote(){return NAME.equals(databaseName)&&!EnergyClient.owner(context);}
    @Override public SQLiteDatabase getWritableDatabase(){if(remote())throw new IllegalStateException("energy.db is owned by :energy");return super.getWritableDatabase();}
    @Override public SQLiteDatabase getReadableDatabase(){
        if(!remote())return super.getReadableDatabase();
        EnergyClient.call(context,"ping");
        if(remoteRead==null||!remoteRead.isOpen())remoteRead=SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).getPath(),null,SQLiteDatabase.OPEN_READONLY);
        return remoteRead;
    }

    @Override public synchronized void close(){if(remoteRead!=null){remoteRead.close();remoteRead=null;}super.close();}

    static synchronized EnergyStore get(Context context) {
        if (instance == null) instance = new EnergyStore(context.getApplicationContext());
        return instance;
    }

    /** Tests only: close and forget the open database, so a file put in its place is opened (and migrated) next. */
    static synchronized void closeForTests() {
        if(instance!=null&&instance.remote())EnergyClient.call(instance.context,"store.closeForTests");
        if (instance != null) instance.close();
        instance = null;
    }

    private EnergyStore(Context context) {
        this(context, NAME);
    }

    /** A separate named database lets instrumentation exercise destructive flows without user data. */
    EnergyStore(Context context, String name) {
        super(context, name, null, VERSION);
        this.context = context;this.databaseName=name;
    }

    /**
     * Write-ahead logging, so the launcher's read-only connection never waits on :energy's writes
     * (one sample a second while driving). The mode is stored in the file, so readers in the other
     * process see it too. They still ping first, so :energy has opened and migrated the database
     * before any reader does. Backups export rows through SQL, never by copying the file.
     */
    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        if (!db.isReadOnly()) db.enableWriteAheadLogging();
    }

    /**
     * Lookup indexes for the per-second car path and Home's queries. Created on open rather than
     * in a schema version, so an older build can still open the file; it simply ignores them.
     */
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        db.execSQL("CREATE INDEX IF NOT EXISTS car_drive_join_live ON car_drive_join(link,superseded_by)");
        db.execSQL("CREATE INDEX IF NOT EXISTS car_event_kind_start ON car_event(kind,start)");
        db.execSQL("CREATE INDEX IF NOT EXISTS car_event_link_kind_start ON car_event(link,kind,start)");
    }

    /** A fresh database goes through the same steps as an upgraded one, so both end up identical. */
    @Override public void onCreate(SQLiteDatabase db) {
        createVersion1(db);
        onUpgrade(db, 1, VERSION);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Every step only adds: raw records are never dropped or rewritten.
        if (oldVersion < 2) upgradeTo2(db);
        if (oldVersion < 3) CarEnergyRepository.createTables(db);
        if(oldVersion<4){
            db.execSQL("ALTER TABLE car_event ADD COLUMN last_time INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX car_event_last ON car_event(last_time)");
            db.execSQL("CREATE TABLE charger(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,latitude REAL,longitude REAL,home INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE car_summary(link TEXT NOT NULL,event_key TEXT NOT NULL,kind TEXT NOT NULL,start INTEGER NOT NULL,last INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(link,event_key))");
            db.execSQL("CREATE INDEX car_summary_last ON car_summary(last)");
            db.execSQL("CREATE TABLE car_drive_join(id INTEGER PRIMARY KEY AUTOINCREMENT,link TEXT NOT NULL,first_key TEXT NOT NULL,second_key TEXT NOT NULL,created INTEGER NOT NULL,superseded_by INTEGER)");
            db.execSQL("CREATE TABLE energy_retained(id INTEGER PRIMARY KEY AUTOINCREMENT,retained INTEGER NOT NULL,table_name TEXT NOT NULL,data TEXT NOT NULL,UNIQUE(table_name,data))");
            db.execSQL("ALTER TABLE charge ADD COLUMN charger_id INTEGER");
            db.execSQL("ALTER TABLE charge ADD COLUMN measured_kwh REAL");
            db.execSQL("ALTER TABLE charge ADD COLUMN estimated_kwh REAL");
            db.execSQL("ALTER TABLE charge ADD COLUMN coverage_seconds REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE charge ADD COLUMN measured_complete INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE charge ADD COLUMN live INTEGER NOT NULL DEFAULT 0");

        }
    }

    private static void createVersion1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE checkin (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, percent REAL,"
                + " kind TEXT NOT NULL, charged TEXT NOT NULL, session INTEGER, meters REAL NOT NULL DEFAULT 0,"
                + " active REAL NOT NULL DEFAULT 0, unknown REAL NOT NULL DEFAULT 0, poor REAL NOT NULL DEFAULT 0,"
                + " excluded INTEGER NOT NULL DEFAULT 0, superseded_by INTEGER, assumptions TEXT)");
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, state TEXT NOT NULL,"
                + " created_by TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, heartbeat INTEGER, boot TEXT,"
                + " base TEXT NOT NULL, final TEXT)");
        db.execSQL("CREATE TABLE idle (id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER NOT NULL,"
                + " start INTEGER NOT NULL, end_time INTEGER, profile TEXT NOT NULL, kw REAL NOT NULL, reason TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE INDEX checkin_time ON checkin(time)");
        db.execSQL("CREATE INDEX idle_session ON idle(session)");
    }

    /** Charge log, car figures on check-ins, a source on every typed record, and the live car tables. */
    private static void upgradeTo2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE checkin ADD COLUMN source TEXT NOT NULL DEFAULT '" + ENTERED + "'");
        db.execSQL("ALTER TABLE checkin ADD COLUMN odometer_km REAL");
        db.execSQL("ALTER TABLE checkin ADD COLUMN trip_km REAL");
        db.execSQL("ALTER TABLE checkin ADD COLUMN trip_kwh100 REAL");
        db.execSQL("CREATE TABLE charge (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL,"
                + " percent_before REAL, percent_after REAL, kwh REAL, cost REAL, rate REAL,"
                + " current TEXT NOT NULL DEFAULT 'UNKNOWN', place TEXT NOT NULL DEFAULT 'UNKNOWN', checkin INTEGER,"
                + " source TEXT NOT NULL DEFAULT '" + ENTERED + "', superseded_by INTEGER)");
        db.execSQL("CREATE INDEX charge_time ON charge(time)");
        // Live car data (energy-companion.md §3.8): samples keep OABL Link's own sequence numbers, per
        // install of the head-unit app ("link"), so a reinstall that restarts at 1 never collides.
        db.execSQL("CREATE TABLE car_sample (link TEXT NOT NULL DEFAULT '', seq INTEGER NOT NULL, t INTEGER NOT NULL,"
                + " data TEXT NOT NULL, PRIMARY KEY (link, seq))");
        db.execSQL("CREATE TABLE car_session (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, state TEXT NOT NULL,"
                + " started INTEGER NOT NULL, ended INTEGER, start_percent REAL, end_percent REAL, data TEXT)");
        db.execSQL("CREATE INDEX car_session_started ON car_session(started)");
    }

    // ---- Profile ----

    EnergyModel.Profile profile() {
        if(remote()){return (EnergyModel.Profile)EnergyClient.call(context,"store.profile()");}
        SharedPreferences prefs = context.getSharedPreferences(PROFILE, 0);
        EnergyModel.Profile profile = new EnergyModel.Profile();
        profile.nedcKm = number(prefs, "nedc_km", profile.nedcKm);
        profile.cltcKm = number(prefs, "cltc_km", profile.cltcKm);
        profile.wltpKm = number(prefs, "wltp_km", profile.wltpKm);
        profile.practicalKm = number(prefs, "practical_km", Double.NaN);
        profile.usableKwh = number(prefs, "usable_kwh", Double.NaN);
        profile.grossKwh = number(prefs, "gross_kwh", Double.NaN);
        profile.reservePercent = number(prefs, "reserve_percent", profile.reservePercent);
        profile.selected = enumValue(EnergyModel.Benchmark.class, prefs.getString("benchmark", null), EnergyModel.Benchmark.WLTP);
        profile.idle = enumValue(EnergyModel.IdleProfile.class, prefs.getString("idle_profile", null), EnergyModel.IdleProfile.D);
        return profile;
    }

    void saveProfile(EnergyModel.Profile profile) {
        if(remote()){EnergyClient.call(context,"store.saveProfile(EnergyModel.Profile)",new Object[]{profile});return;}
        context.getSharedPreferences(PROFILE, 0).edit()
                .putString("nedc_km", text(profile.nedcKm)).putString("cltc_km", text(profile.cltcKm))
                .putString("wltp_km", text(profile.wltpKm)).putString("practical_km", text(profile.practicalKm))
                .putString("usable_kwh", text(profile.usableKwh)).putString("gross_kwh", text(profile.grossKwh))
                .putString("reserve_percent", text(profile.reservePercent))
                .putString("benchmark", profile.selected.name()).putString("idle_profile", profile.idle.name()).apply();
    }

    /** When to ask for the battery: "parked" (before a drive), "after_off" (box off 30 min or more) or "never". */
    String promptMode() {
        if(remote()){return (String)EnergyClient.call(context,"store.promptMode()");} return context.getSharedPreferences(PROFILE, 0).getString("prompt", "parked"); }
    void setPromptMode(String mode) {
        if(remote()){EnergyClient.call(context,"store.setPromptMode(String)",new Object[]{mode});return;} context.getSharedPreferences(PROFILE, 0).edit().putString("prompt", mode).apply(); }
    String vehicleName() {
        if(remote()){return (String)EnergyClient.call(context,"store.vehicleName()");} return context.getSharedPreferences(PROFILE, 0).getString("vehicle_name", "NAMMI 1 · E3"); }
    /** Optional: prices home charges logged with kWh but no cost or rate. NaN until entered. */
    double homeRate() {
        if(remote()){return (double)EnergyClient.call(context,"store.homeRate()");} return number(context.getSharedPreferences(PROFILE, 0), "home_rate", Double.NaN); }
    void setHomeRate(double rate) {
        if(remote()){EnergyClient.call(context,"store.setHomeRate(double)",new Object[]{rate});return;} context.getSharedPreferences(PROFILE, 0).edit().putString("home_rate", text(rate)).apply(); }
    /** Only a label for money; nothing is converted. */
    String currency() {
        if(remote()){return (String)EnergyClient.call(context,"store.currency()");} return context.getSharedPreferences(PROFILE, 0).getString("currency", "LKR"); }
    void setCurrency(String code) {
        if(remote()){EnergyClient.call(context,"store.setCurrency(String)",new Object[]{code});return;} context.getSharedPreferences(PROFILE, 0).edit().putString("currency", code).apply(); }
    void setVehicleName(String name) {
        if(remote()){EnergyClient.call(context,"store.setVehicleName(String)",new Object[]{name});return;} context.getSharedPreferences(PROFILE, 0).edit().putString("vehicle_name", name).apply(); }

    private static double number(SharedPreferences prefs, String key, double fallback) {
        String value = prefs.getString(key, null);
        if (value == null) return fallback;
        if (value.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static String text(double value) { return Double.isNaN(value) ? "" : Double.toString(value); }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return fallback; }
    }

    // ---- Meta and counters ----

    String meta(String key) {
        if(remote()){return (String)EnergyClient.call(context,"store.meta(String)",new Object[]{key});}
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void setMeta(String key, String value) {
        if(remote()){EnergyClient.call(context,"store.setMeta(String,String)",new Object[]{key,value});return;}
        if (value == null) { getWritableDatabase().delete("meta", "key=?", new String[]{key}); return; }
        ContentValues row = new ContentValues();
        row.put("key", key);
        row.put("value", value);
        getWritableDatabase().insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long metaLong(String key, long fallback) {
        if(remote()){return (long)EnergyClient.call(context,"store.metaLong(String,long)",new Object[]{key,fallback});}
        String value = meta(key);
        try { return value == null ? fallback : Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }

    /** meters, moving, still, unknown, active seconds and poor-GPS metres since the history began. */
    double[] counters() {
        if(remote()){return (double[])EnergyClient.call(context,"store.counters()");}
        double[] values = new double[COUNTERS.length];
        for (int i = 0; i < values.length; i++) {
            String value = meta("c_" + COUNTERS[i]);
            values[i] = value == null ? 0 : Double.parseDouble(value);
        }
        return values;
    }

    void saveCounters(double[] values) {
        if(remote()){EnergyClient.call(context,"store.saveCounters(double[])",new Object[]{values});return;}
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < values.length; i++) setMeta("c_" + COUNTERS[i], Double.toString(values[i]));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static String encode(double[] values) {
        JSONArray array = new JSONArray();
        for (double value : values) {
            try { array.put(value); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        }
        return array.toString();
    }

    static double[] decode(String text) {
        double[] values = new double[COUNTERS.length];
        if (text == null) return values;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < values.length && i < array.length(); i++) values[i] = array.getDouble(i);
        } catch (org.json.JSONException ignored) { }
        return values;
    }

    // ---- Sessions ----

    static final class Session implements java.io.Serializable {
        long id, started, ended, heartbeat;
        String state, createdBy, boot;
        double[] base, last;
        boolean active() { return "active".equals(state); }
        /** Counters for this journey: final ones once ended, otherwise against the given current ones. */
        double[] totals(double[] current) {
            double[] end = last != null ? last : current;
            double[] totals = new double[base.length];
            for (int i = 0; i < totals.length; i++) totals[i] = Math.max(0, end[i] - base[i]);
            return totals;
        }
    }

    long createSession(String createdBy, long now, String boot, double[] counters) {
        if(remote()){return (long)EnergyClient.call(context,"store.createSession(String,long,String,double[])",new Object[]{createdBy,now,boot,counters});}
        ContentValues row = new ContentValues();
        row.put("state", "active");
        row.put("created_by", createdBy);
        row.put("started", now);
        row.put("heartbeat", now);
        row.put("boot", boot);
        row.put("base", encode(counters));
        return getWritableDatabase().insertOrThrow("session", null, row);
    }

    void setSessionState(long id, String state, long ended, double[] finalCounters) {
        if(remote()){EnergyClient.call(context,"store.setSessionState(long,String,long,double[])",new Object[]{id,state,ended,finalCounters});return;}
        ContentValues row = new ContentValues();
        row.put("state", state);
        if (ended > 0) row.put("ended", ended); else row.putNull("ended");
        if (finalCounters != null) row.put("final", encode(finalCounters)); else row.putNull("final");
        getWritableDatabase().update("session", row, "id=?", new String[]{Long.toString(id)});
    }

    void heartbeat(long id, long now, String boot) {
        if(remote()){EnergyClient.call(context,"store.heartbeat(long,long,String)",new Object[]{id,now,boot});return;}
        ContentValues row = new ContentValues();
        row.put("heartbeat", now);
        row.put("boot", boot);
        getWritableDatabase().update("session", row, "id=?", new String[]{Long.toString(id)});
    }

    Session session(long id) {
        if(remote()){return (Session)EnergyClient.call(context,"store.session(long)",new Object[]{id});}
        List<Session> found = sessions("id=?", new String[]{Long.toString(id)}, null);
        return found.isEmpty() ? null : found.get(0);
    }

    Session activeSession() {
        if(remote()){return (Session)EnergyClient.call(context,"store.activeSession()");}
        List<Session> found = sessions("state='active'", null, "1");
        return found.isEmpty() ? null : found.get(0);
    }

    Session lastSession() {
        if(remote()){return (Session)EnergyClient.call(context,"store.lastSession()");}
        List<Session> found = sessions(null, null, "1");
        return found.isEmpty() ? null : found.get(0);
    }

    List<Session> sessions(String where, String[] args, String limit) {
        if(remote()){return (List<Session>)EnergyClient.call(context,"store.sessions(String,String[],String)",new Object[]{where,args,limit});}
        List<Session> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("session",
                new String[]{"id", "state", "created_by", "started", "ended", "heartbeat", "boot", "base", "final"},
                where, args, null, null, "started DESC, id DESC", limit)) {
            while (c.moveToNext()) {
                Session s = new Session();
                s.id = c.getLong(0);
                s.state = c.getString(1);
                s.createdBy = c.getString(2);
                s.started = c.getLong(3);
                s.ended = c.isNull(4) ? 0 : c.getLong(4);
                s.heartbeat = c.isNull(5) ? 0 : c.getLong(5);
                s.boot = c.getString(6);
                s.base = decode(c.getString(7));
                s.last = c.isNull(8) ? null : decode(c.getString(8));
                result.add(s);
            }
        }
        return result;
    }

    // ---- Check-ins ----

    static final class CheckIn implements java.io.Serializable {
        long id, time, session, supersededBy;
        double percent = Double.NaN;
        String kind;
        EnergyModel.Charged charged = EnergyModel.Charged.UNKNOWN;
        boolean excluded;
        double[] counters = new double[COUNTERS.length];
        String source = ENTERED;
        /** As typed from the car's displays; NaN when not entered. */
        double odometerKm = Double.NaN, tripKm = Double.NaN, tripKwhPer100Km = Double.NaN;

        EnergyModel.CarFigures figures() {
            EnergyModel.CarFigures figures = new EnergyModel.CarFigures();
            figures.odometerKm = odometerKm;
            figures.tripKm = tripKm;
            figures.tripKwhPer100Km = tripKwhPer100Km;
            return figures;
        }

        EnergyModel.Reading reading() {
            EnergyModel.Reading reading = new EnergyModel.Reading();
            reading.id = id;
            reading.time = time;
            reading.percent = percent;
            reading.charged = charged;
            reading.excluded = excluded;
            reading.meters = counters[0];
            reading.unknownSeconds = counters[3];
            reading.activeSeconds = counters[4];
            reading.poorMeters = counters[5];
            reading.odometerKm = odometerKm;
            reading.tripKm = tripKm;
            reading.tripKwhPer100Km = tripKwhPer100Km;
            return reading;
        }
    }

    long addCheckIn(long time, double percent, String kind, EnergyModel.Charged charged, long session,
                    double[] counters, EnergyModel.Profile assumptions) {
        if(remote()){return (long)EnergyClient.call(context,"store.addCheckIn(long,double,String,EnergyModel.Charged,long,double[],EnergyModel.Profile)",new Object[]{time,percent,kind,charged,session,counters,assumptions});}
        return addCheckIn(time, percent, kind, charged, session, counters, assumptions, null);
    }

    long addCheckIn(long time, double percent, String kind, EnergyModel.Charged charged, long session,
                    double[] counters, EnergyModel.Profile assumptions, EnergyModel.CarFigures figures) {
        if(remote()){return (long)EnergyClient.call(context,"store.addCheckIn(long,double,String,EnergyModel.Charged,long,double[],EnergyModel.Profile,EnergyModel.CarFigures)",new Object[]{time,percent,kind,charged,session,counters,assumptions,figures});}
        ContentValues row = new ContentValues();
        row.put("time", time);
        if (Double.isNaN(percent)) row.putNull("percent"); else row.put("percent", percent);
        row.put("kind", kind);
        row.put("charged", charged.name());
        if (session > 0) row.put("session", session); else row.putNull("session");
        row.put("meters", counters[0]);
        row.put("unknown", counters[3]);
        row.put("active", counters[4]);
        row.put("poor", counters[5]);
        row.put("assumptions", snapshot(assumptions));
        row.put("source", ENTERED);
        if (figures != null) {
            optional(row, "odometer_km", figures.odometerKm);
            optional(row, "trip_km", figures.tripKm);
            optional(row, "trip_kwh100", figures.tripKwhPer100Km);
        }
        return getWritableDatabase().insertOrThrow("checkin", null, row);
    }

    /** superseded_by for a check-in the driver removed: hidden from every figure, kept, restorable. */
    static final long REMOVED = -2;

    /** A correction: a new record with the same time and counters; the old one stays, superseded. */
    long correctCheckIn(long id, double percent, EnergyModel.Charged charged, EnergyModel.Profile assumptions) {
        if(remote()){return (long)EnergyClient.call(context,"store.correctCheckIn(long,double,EnergyModel.Charged,EnergyModel.Profile)",new Object[]{id,percent,charged,assumptions});}
        CheckIn old = checkIn(id);
        return old == null ? -1 : correctCheckIn(id, percent, charged, assumptions, old.figures());
    }

    /** A correction that also replaces what was typed from the car's displays (odometer, trip A). */
    long correctCheckIn(long id, double percent, EnergyModel.Charged charged, EnergyModel.Profile assumptions,
                        EnergyModel.CarFigures figures) {
        if(remote()){return (long)EnergyClient.call(context,"store.correctCheckIn(long,double,EnergyModel.Charged,EnergyModel.Profile,EnergyModel.CarFigures)",new Object[]{id,percent,charged,assumptions,figures});}
        CheckIn old = checkIn(id);
        if (old == null) return -1;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long replacement = addCheckIn(old.time, percent, old.kind, charged, old.session, old.counters, assumptions, figures);
            if (old.excluded) markExcluded(replacement);
            ContentValues row = new ContentValues();
            row.put("superseded_by", replacement);
            db.update("checkin", row, "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return replacement;
        } finally { db.endTransaction(); }
    }

    private void markExcluded(long id) {
        ContentValues row = new ContentValues();
        row.put("excluded", 1);
        getWritableDatabase().update("checkin", row, "id=?", new String[]{Long.toString(id)});
    }

    /** A continued journey's "I don't know" end reading stops counting (kept, marked withdrawn). */
    void withdrawUnknownEnd(long session) {
        if(remote()){EnergyClient.call(context,"store.withdrawUnknownEnd(long)",new Object[]{session});return;}
        ContentValues row = new ContentValues();
        row.put("superseded_by", -1);
        getWritableDatabase().update("checkin", row, "session=? AND kind='end' AND percent IS NULL AND superseded_by IS NULL",
                new String[]{Long.toString(session)});
    }

    void excludeSession(long session) {
        if(remote()){EnergyClient.call(context,"store.excludeSession(long)",new Object[]{session});return;}
        ContentValues row = new ContentValues();
        row.put("excluded", 1);
        getWritableDatabase().update("checkin", row, "session=?", new String[]{Long.toString(session)});
    }

    /** The driver removed a mistaken check-in: it stops counting everywhere and stays in the record. */
    void removeCheckIn(long id) {
        if(remote()){EnergyClient.call(context,"store.removeCheckIn(long)",new Object[]{id});return;}
        ContentValues row = new ContentValues();
        row.put("superseded_by", REMOVED);
        getWritableDatabase().update("checkin", row, "id=? AND superseded_by IS NULL", new String[]{Long.toString(id)});
    }

    /** Brings a removed check-in back, exactly as it was. */
    void restoreCheckIn(long id) {
        if(remote()){EnergyClient.call(context,"store.restoreCheckIn(long)",new Object[]{id});return;}
        ContentValues row = new ContentValues();
        row.putNull("superseded_by");
        getWritableDatabase().update("checkin", row, "id=? AND superseded_by=?", new String[]{Long.toString(id), Long.toString(REMOVED)});
    }

    /** Check-ins the driver removed, newest first. */
    List<CheckIn> removedCheckIns() {
        if(remote()){return (List<CheckIn>)EnergyClient.call(context,"store.removedCheckIns()");}
        List<CheckIn> removed = checkIns("superseded_by=?", new String[]{Long.toString(REMOVED)});
        java.util.Collections.reverse(removed);
        return removed;
    }

    CheckIn checkIn(long id) {
        if(remote()){return (CheckIn)EnergyClient.call(context,"store.checkIn(long)",new Object[]{id});}
        List<CheckIn> found = checkIns("id=?", new String[]{Long.toString(id)});
        return found.isEmpty() ? null : found.get(0);
    }

    /** Current (not superseded) check-ins, oldest first. */
    List<CheckIn> checkIns() {
        if(remote()){return (List<CheckIn>)EnergyClient.call(context,"store.checkIns()");} return checkIns("superseded_by IS NULL", null); }

    List<CheckIn> sessionCheckIns(long session) {
        if(remote()){return (List<CheckIn>)EnergyClient.call(context,"store.sessionCheckIns(long)",new Object[]{session});}
        return checkIns("superseded_by IS NULL AND session=?", new String[]{Long.toString(session)});
    }

    private List<CheckIn> checkIns(String where, String[] args) {
        List<CheckIn> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("checkin",
                new String[]{"id", "time", "percent", "kind", "charged", "session", "meters", "active", "unknown", "poor", "excluded",
                        "superseded_by", "source", "odometer_km", "trip_km", "trip_kwh100"},
                where, args, null, null, "time ASC, id ASC")) {
            while (c.moveToNext()) {
                CheckIn item = new CheckIn();
                item.id = c.getLong(0);
                item.time = c.getLong(1);
                item.percent = c.isNull(2) ? Double.NaN : c.getDouble(2);
                item.kind = c.getString(3);
                item.charged = enumValue(EnergyModel.Charged.class, c.getString(4), EnergyModel.Charged.UNKNOWN);
                item.session = c.isNull(5) ? 0 : c.getLong(5);
                item.counters[0] = c.getDouble(6);
                item.counters[4] = c.getDouble(7);
                item.counters[3] = c.getDouble(8);
                item.counters[5] = c.getDouble(9);
                item.excluded = c.getInt(10) != 0;
                item.supersededBy = c.isNull(11) ? 0 : c.getLong(11);
                item.source = c.getString(12);
                item.odometerKm = c.isNull(13) ? Double.NaN : c.getDouble(13);
                item.tripKm = c.isNull(14) ? Double.NaN : c.getDouble(14);
                item.tripKwhPer100Km = c.isNull(15) ? Double.NaN : c.getDouble(15);
                result.add(item);
            }
        }
        return result;
    }

    private static void optional(ContentValues row, String column, double value) {
        if (Double.isNaN(value)) row.putNull(column); else row.put(column, value);
    }

    private static double optional(Cursor c, int column) { return c.isNull(column) ? Double.NaN : c.getDouble(column); }

    private static String snapshot(EnergyModel.Profile profile) {
        if (profile == null) return null;
        try {
            return new JSONObject()
                    .put("nedc", text(profile.nedcKm)).put("cltc", text(profile.cltcKm)).put("wltp", text(profile.wltpKm))
                    .put("practical", text(profile.practicalKm)).put("benchmark", profile.selected.name())
                    .put("usable_kwh", text(profile.usableKwh)).put("reserve", text(profile.reservePercent))
                    .put("idle", profile.idle.name()).toString();
        } catch (org.json.JSONException e) { return null; }
    }

    // ---- Charge log ----

    /** A new charge entry. Raw like a check-in: never edited, only withdrawn. */
    long addCharge(EnergyModel.Charge charge) {
        if(remote()){return (long)EnergyClient.call(context,"store.addCharge(EnergyModel.Charge)",new Object[]{charge});}
        return addCharge(charge, ENTERED);
    }

    long addCharge(EnergyModel.Charge charge, String source) {
        if(remote()){return (long)EnergyClient.call(context,"store.addCharge(EnergyModel.Charge,String)",new Object[]{charge,source});}
        ContentValues row = new ContentValues();
        row.put("charger_id",charge.chargerId);optional(row,"measured_kwh",charge.measuredKwh);optional(row,"estimated_kwh",charge.estimatedKwh);
        row.put("coverage_seconds",charge.coverageSeconds);row.put("measured_complete",charge.measuredComplete?1:0);row.put("live",charge.live?1:0);
        row.put("time", charge.time);
        optional(row, "percent_before", charge.before);
        optional(row, "percent_after", charge.after);
        optional(row, "kwh", charge.kwh);
        optional(row, "cost", charge.cost);
        optional(row, "rate", charge.rate);
        row.put("current", charge.current.name());
        row.put("place", charge.place.name());
        if (charge.checkIn > 0) row.put("checkin", charge.checkIn); else row.putNull("checkin");
        row.put("source", source);
        charge.id = getWritableDatabase().insertOrThrow("charge", null, row);
        return charge.id;
    }

    /** A mistyped entry stops counting; the row stays in the record, marked withdrawn. */
    void withdrawCharge(long id) {
        if(remote()){EnergyClient.call(context,"store.withdrawCharge(long)",new Object[]{id});return;}
        ContentValues row = new ContentValues();
        row.put("superseded_by", -1);
        getWritableDatabase().update("charge", row, "id=? AND superseded_by IS NULL", new String[]{Long.toString(id)});
    }

    /** A correction: the edited entry is a new record; the old one stays, superseded by it. */
    long editCharge(long oldId, EnergyModel.Charge edited) {
        if(remote()){return (long)EnergyClient.call(context,"store.editCharge(long,EnergyModel.Charge)",new Object[]{oldId,edited});}
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = addCharge(edited);
            ContentValues row = new ContentValues();
            row.put("superseded_by", id);
            db.update("charge", row, "id=? AND superseded_by IS NULL", new String[]{Long.toString(oldId)});
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    /** Brings a withdrawn charge back, exactly as it was. */
    void restoreCharge(long id) {
        if(remote()){EnergyClient.call(context,"store.restoreCharge(long)",new Object[]{id});return;}
        ContentValues row = new ContentValues();
        row.putNull("superseded_by");
        getWritableDatabase().update("charge", row, "id=? AND superseded_by=-1", new String[]{Long.toString(id)});
    }

    /** Current (not withdrawn) charges, oldest first. */
    List<EnergyModel.Charge> charges() {
        if(remote()){return (List<EnergyModel.Charge>)EnergyClient.call(context,"store.charges()");} 
        List<EnergyModel.Charge> active = charges("superseded_by IS NULL", "time ASC, id ASC");
        java.util.Set<Long> unverified = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT r.charge_id,e.data,r.status FROM car_charge_review r JOIN car_event e ON r.link=e.link AND r.event_key=e.event_key WHERE r.charge_id IS NOT NULL", null)) {
            while(c.moveToNext()) try {
                if ("ignored".equals(c.getString(2)) || !CarChargeReviewPolicy.counts(CarEnergyCodec.event(new JSONObject(c.getString(1))))) unverified.add(c.getLong(0));
            } catch(Exception invalid) { unverified.add(c.getLong(0)); android.util.Log.w("CarEnergy", "Charge excluded: unreadable charging evidence", invalid); }
        }
        active.removeIf(charge -> unverified.contains(charge.id));
        return active;
    }

    /** Charges the driver removed, newest first. */
    List<EnergyModel.Charge> removedCharges() {
        if(remote()){return (List<EnergyModel.Charge>)EnergyClient.call(context,"store.removedCharges()");} return charges("superseded_by=-1", "time DESC, id DESC"); }

    private List<EnergyModel.Charge> charges(String where, String order) {
        List<EnergyModel.Charge> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("charge",
                new String[]{"id", "time", "percent_before", "percent_after", "kwh", "cost", "rate", "current", "place", "checkin", "charger_id", "measured_kwh", "estimated_kwh", "coverage_seconds", "measured_complete", "live"},
                where, null, null, null, order)) {
            while (c.moveToNext()) {
                EnergyModel.Charge charge = new EnergyModel.Charge();
                charge.id = c.getLong(0);
                charge.time = c.getLong(1);
                charge.before = optional(c, 2);
                charge.after = optional(c, 3);
                charge.kwh = optional(c, 4);
                charge.cost = optional(c, 5);
                charge.rate = optional(c, 6);
                charge.current = enumValue(EnergyModel.Current.class, c.getString(7), EnergyModel.Current.UNKNOWN);
                charge.place = enumValue(EnergyModel.Place.class, c.getString(8), EnergyModel.Place.UNKNOWN);
                charge.checkIn = c.isNull(9) ? 0 : c.getLong(9);
                charge.chargerId=c.getLong(10);charge.measuredKwh=optional(c,11);charge.estimatedKwh=optional(c,12);charge.coverageSeconds=c.getDouble(13);charge.measuredComplete=c.getInt(14)!=0;charge.live=c.getInt(15)!=0;
                result.add(charge);
            }
        }
        return result;
    }

    // ---- Live car data (energy-companion.md §3.8) ----

    static final class CarSampleRow implements java.io.Serializable {
        /** The head-unit app's install id ("link" in /v1/hello); its sequence numbers are its own. */
        String link;
        long seq, time;
        /** The sample's values as JSON, exactly as the car-link client stored them. */
        String data;
    }

    /** Stores one numbered sample; a (link, sequence) already stored is left as it is. True when stored. */
    boolean insertCarSample(String link, long seq, long time, String data) {
        if(remote()){return (boolean)EnergyClient.call(context,"store.insertCarSample(String,long,long,String)",new Object[]{link,seq,time,data});}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
        if(time<=metaLong("car_reset_after",0)||seq<=metaLong("car_cursor:"+link,0)){db.setTransactionSuccessful();return false;}
        ContentValues row = new ContentValues();
        row.put("link", link == null ? "" : link);
        row.put("seq", seq);
        row.put("t", time);
        row.put("data", data);
        boolean saved=db.insertWithOnConflict("car_sample", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1;
        db.setTransactionSuccessful();return saved;
        }finally{db.endTransaction();}
    }

    /** One link's samples after a sequence number, oldest first, at most {@code limit}. */
    List<CarSampleRow> carSamplesAfter(String link, long seq, int limit) {
        if(remote()){return (List<CarSampleRow>)EnergyClient.call(context,"store.carSamplesAfter(String,long,int)",new Object[]{link,seq,limit});}
        List<CarSampleRow> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("car_sample", new String[]{"link", "seq", "t", "data"}, "link=? AND seq>?",
                new String[]{link == null ? "" : link, Long.toString(seq)}, null, null, "seq ASC", Integer.toString(Math.max(1, limit)))) {
            while (c.moveToNext()) {
                CarSampleRow row = new CarSampleRow();
                row.link = c.getString(0);
                row.seq = c.getLong(1);
                row.time = c.getLong(2);
                row.data = c.getString(3);
                result.add(row);
            }
        }
        return result;
    }

    /** A link's newest stored sequence number, or 0 when it has none (the box catches up from there). */
    long lastCarSeq(String link) {
        if(remote()){return (long)EnergyClient.call(context,"store.lastCarSeq(String)",new Object[]{link});}
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(seq) FROM car_sample WHERE link=?", new String[]{link == null ? "" : link})) {
            return Math.max(c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0,metaLong("car_cursor:"+link,0));
        }
    }

    /** A drive, charge or parked period seen in the car's data. Recomputable from car_sample. */
    static final class CarSession implements java.io.Serializable {
        long id, started, ended;
        String kind, state, data;
        double startPercent = Double.NaN, endPercent = Double.NaN;
    }

    long addCarSession(String kind, String state, long started, double startPercent, String data) {
        if(remote()){return (long)EnergyClient.call(context,"store.addCarSession(String,String,long,double,String)",new Object[]{kind,state,started,startPercent,data});}
        ContentValues row = new ContentValues();
        row.put("kind", kind);
        row.put("state", state);
        row.put("started", started);
        optional(row, "start_percent", startPercent);
        row.put("data", data);
        return getWritableDatabase().insertOrThrow("car_session", null, row);
    }

    /** Car sessions are derived, so unlike raw records they're updated in place (ended 0 leaves it open). */
    void updateCarSession(long id, String state, long ended, double endPercent, String data) {
        if(remote()){EnergyClient.call(context,"store.updateCarSession(long,String,long,double,String)",new Object[]{id,state,ended,endPercent,data});return;}
        ContentValues row = new ContentValues();
        row.put("state", state);
        if (ended > 0) row.put("ended", ended); else row.putNull("ended");
        optional(row, "end_percent", endPercent);
        row.put("data", data);
        getWritableDatabase().update("car_session", row, "id=?", new String[]{Long.toString(id)});
    }

    CarSession carSession(long id) {
        if(remote()){return (CarSession)EnergyClient.call(context,"store.carSession(long)",new Object[]{id});}
        List<CarSession> found = carSessions("id=?", new String[]{Long.toString(id)}, "1");
        return found.isEmpty() ? null : found.get(0);
    }

    /** Newest first; {@code kind} null for every kind. */
    List<CarSession> carSessions(String kind, int limit) {
        if(remote()){return (List<CarSession>)EnergyClient.call(context,"store.carSessions(String,int)",new Object[]{kind,limit});}
        return kind == null ? carSessions(null, null, Integer.toString(Math.max(1, limit)))
                : carSessions("kind=?", new String[]{kind}, Integer.toString(Math.max(1, limit)));
    }

    private List<CarSession> carSessions(String where, String[] args, String limit) {
        List<CarSession> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("car_session",
                new String[]{"id", "kind", "state", "started", "ended", "start_percent", "end_percent", "data"},
                where, args, null, null, "started DESC, id DESC", limit)) {
            while (c.moveToNext()) {
                CarSession session = new CarSession();
                session.id = c.getLong(0);
                session.kind = c.getString(1);
                session.state = c.getString(2);
                session.started = c.getLong(3);
                session.ended = c.isNull(4) ? 0 : c.getLong(4);
                session.startPercent = optional(c, 5);
                session.endPercent = optional(c, 6);
                session.data = c.getString(7);
                result.add(session);
            }
        }
        return result;
    }

    // ---- Idle periods ----

    void openIdle(IdleLedger.Period period) {
        if(remote()){EnergyClient.call(context,"store.openIdle(IdleLedger.Period)",new Object[]{period});return;}
        ContentValues row = new ContentValues();
        row.put("session", period.session);
        row.put("start", period.start);
        row.put("profile", period.profile.name());
        row.put("kw", period.kw);
        row.put("reason", period.reason.name());
        period.id = getWritableDatabase().insertOrThrow("idle", null, row);
    }

    void closeIdle(IdleLedger.Period period) {
        if(remote()){EnergyClient.call(context,"store.closeIdle(IdleLedger.Period)",new Object[]{period});return;}
        ContentValues row = new ContentValues();
        row.put("end_time", period.end);
        getWritableDatabase().update("idle", row, "id=?", new String[]{Long.toString(period.id)});
    }

    /** Closes periods left open by a power cut at the session's last heartbeat. */
    void closeOpenIdle(long session, long at) {
        if(remote()){EnergyClient.call(context,"store.closeOpenIdle(long,long)",new Object[]{session,at});return;}
        ContentValues row = new ContentValues();
        row.put("end_time", at);
        getWritableDatabase().update("idle", row, "session=? AND end_time IS NULL AND start<=?",
                new String[]{Long.toString(session), Long.toString(at)});
        // A period that "started" after the last heartbeat never really happened.
        getWritableDatabase().execSQL("UPDATE idle SET end_time=start WHERE session=? AND end_time IS NULL",new Object[]{session});
    }

    List<IdleLedger.Period> idle(long session) {
        if(remote()){return (List<IdleLedger.Period>)EnergyClient.call(context,"store.idle(long)",new Object[]{session});}
        return idle(session > 0 ? "session=?" : null, session > 0 ? new String[]{Long.toString(session)} : null);
    }

    List<IdleLedger.Period> idle(String where, String[] args) {
        if(remote()){return (List<IdleLedger.Period>)EnergyClient.call(context,"store.idle(String,String[])",new Object[]{where,args});}
        List<IdleLedger.Period> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("idle",
                new String[]{"id", "session", "start", "end_time", "profile", "kw", "reason"},
                where, args, null, null, "start ASC")) {
            while (c.moveToNext()) {
                IdleLedger.Period period = new IdleLedger.Period();
                period.id = c.getLong(0);
                period.session = c.getLong(1);
                period.start = c.getLong(2);
                period.end = c.isNull(3) ? 0 : c.getLong(3);
                period.profile = enumValue(EnergyModel.IdleProfile.class, c.getString(4), EnergyModel.IdleProfile.D);
                period.kw = c.getDouble(5);
                period.reason = enumValue(IdleLedger.Reason.class, c.getString(6), IdleLedger.Reason.STANDSTILL);
                result.add(period);
            }
        }
        return result;
    }

    IdleLedger.Period openIdlePeriod(long session) {
        if(remote()){return (IdleLedger.Period)EnergyClient.call(context,"store.openIdlePeriod(long)",new Object[]{session});}
        List<IdleLedger.Period> found = idle("session=? AND end_time IS NULL", new String[]{Long.toString(session)});
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    // ---- Resets ----

    /** Supersedes active records into a retained generation; raw telemetry and the profile stay. */
    void deleteHistory() {
        if(remote()){EnergyClient.call(context,"store.deleteHistory()");return;}
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try{EnergyRetention.supersede(db);}catch(Exception failure){throw new IllegalStateException(failure);}
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    // ---- Backup ----

    JSONObject export() throws org.json.JSONException {
        if(remote()){return (JSONObject)EnergyClient.call(context,"store.export()");}
        SQLiteDatabase snapshot=getWritableDatabase();snapshot.beginTransaction();
        try{
        JSONObject root = new JSONObject();
        root.put("schema", VERSION);
        for (String table : TABLES) {
            if (java.util.Arrays.asList(NOT_BACKED_UP).contains(table)) continue;
            JSONArray rows = new JSONArray();
            try (Cursor c = getReadableDatabase().query(table, null, null, null, null, null, null)) {
                while (c.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: row.put(c.getColumnName(i), JSONObject.NULL); break;
                            case Cursor.FIELD_TYPE_INTEGER: row.put(c.getColumnName(i), c.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: row.put(c.getColumnName(i), c.getDouble(i)); break;
                            default: row.put(c.getColumnName(i), c.getString(i));
                        }
                    }
                    rows.put(row);
                }
            }
            root.put(table, rows);
        }
        snapshot.setTransactionSuccessful();return root;
        }finally{snapshot.endTransaction();}
    }

    /**
     * Activates a backup while retaining the outgoing generation and all immutable raw telemetry.
     * Legacy rows take added columns' defaults. Raw key collisions must agree exactly, otherwise
     * the entire transaction rolls back. Returns how many saved rows were processed.
     */
    int restore(JSONObject root) throws org.json.JSONException {
        if(remote()){return (int)EnergyClient.call(context,"store.restore(JSONObject)",new Object[]{root});}
        int schema = root.optInt("schema", 0);
        if (schema < 1 || schema > VERSION) throw new IllegalArgumentException("Unknown energy data version");
        SQLiteDatabase db = getWritableDatabase();
        int count = 0;
        db.beginTransaction();
        try {
            try{EnergyRetention.supersede(db);}catch(Exception failure){throw new IllegalStateException(failure);}
            for (String table : TABLES) {
                JSONArray rows = root.optJSONArray(table);
                if (rows == null) continue;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    ContentValues values = new ContentValues();
                    for (java.util.Iterator<String> keys = row.keys(); keys.hasNext(); ) {
                        String key = keys.next();
                        Object value = row.get(key);
                        if (value == JSONObject.NULL) values.putNull(key);
                        else if (value instanceof Integer || value instanceof Long) values.put(key, ((Number) value).longValue());
                        else if (value instanceof Number) values.put(key, ((Number) value).doubleValue());
                        else values.put(key, value.toString());
                    }
                    if(table.equals("energy_retained"))values.remove("id");
                    if(EnergyRetention.raw(table)&&!table.equals("energy_retained"))EnergyRetention.mergeRaw(db,table,values);else if(table.equals("energy_retained"))db.insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_IGNORE);else db.insertOrThrow(table, null, values);
                    count++;
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return count;
    }
}
