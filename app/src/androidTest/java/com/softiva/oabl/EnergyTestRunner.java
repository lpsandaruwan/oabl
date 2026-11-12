package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;

/**
 * The EV energy flows on a real energy.db (energy-model.md §9–12): drives, charging, power loss and
 * recovery, a reboot that is not a drive, continuing, auto-start, idle periods, the three resets'
 * isolation and the backup round trip. Manual mode, improved (energy-companion.md §2): the schema 1
 * to 2 migration, parked drain, the charge log, the odometer and trip computer, and the Energy page
 * and number pads fitting three window sizes without scrolling. It deletes energy history (and the
 * migration check replaces energy.db), so it refuses to run on the box.
 *
 * <pre>./gradlew assembleDebug assembleDebugAndroidTest -PoablTestRunner=com.softiva.oabl.EnergyTestRunner
 * adb -s emulator-5554 shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.EnergyTestRunner</pre>
 */
public final class EnergyTestRunner extends Instrumentation {
    private int checks;
    private long clock;
    private double lon = 79.86;
    private static final double LAT = 6.9;

    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private void near(double expected, double actual, double tolerance, String label) {
        check(Math.abs(expected - actual) <= tolerance, label + " (expected " + expected + ", got " + actual + ")");
    }

    private boolean keep;
    private static final long HOUR = 3_600_000L;
    private volatile EnergyActivity page;

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof EnergyActivity) page = (EnergyActivity) activity;
    }

    private boolean shots;

    /**
     * {@code -e data keep} leaves the simulated drives in place (emulator screenshots); {@code -e shots 1}
     * saves each Energy tab and number pad, per window size, to files/energy-shots on the emulator.
     */
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        keep = args != null && "keep".equals(args.getString("data"));
        shots = args != null && args.getString("shots") != null;
        start();
    }

    /** Draws the window's own views (the emulator's screen capture can come back black). Main thread. */
    private void capture(String name, View root) {
        if (!shots || root.getWidth() == 0) return;
        try {
            File dir = new File(getTargetContext().getExternalFilesDir(null), "energy-shots");
            dir.mkdirs();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(0xff808080);
            root.draw(canvas);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, name + ".png"))) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();int outcome=Activity.RESULT_CANCELED;
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            result.putString("stream", "REFUSED · this test deletes energy history; run it on the emulator\n");
            finish(Activity.RESULT_CANCELED, result);
            return;
        }
        SharedPreferences profilePrefs = c.getSharedPreferences(EnergyStore.PROFILE, 0);
        SharedPreferences launcher = c.getSharedPreferences("launcher_preferences", 0);
        SharedPreferences carPrefs=c.getSharedPreferences("car_link",0);
        boolean hadMode=carPrefs.contains("mode");String savedMode=carPrefs.getString("mode",CarLinkController.MODE_AUTO);
        main(()->CarLinkController.get(c).setMode(CarLinkController.MODE_MANUAL));
        Map<String, ?> savedProfile = profilePrefs.getAll();
        clock = SystemClock.elapsedRealtimeNanos();
        EnergyJournal.serviceEnabled = false;EnergyClient.call(c,"journal.serviceEnabled",false); // only the simulated fixes below
        // Home's own GPS would feed real readings (0 km/h on the emulator): put another app in front,
        // after the one-shot "restore Home after installation" alarm has fired.
        SystemClock.sleep(8000);
        c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(2000);
        try {
            main(() -> EnergyJournal.get(c).deleteHistory());
            profilePrefs.edit().clear().commit();EnergyClient.call(c,"profile.restore",new org.json.JSONObject());
            launcher.edit().putInt("energy_test_marker", 42).commit();
            int tripsBefore = TripComputer.get(c).history().size();

            // Defaults: WLTP selected, no capacity assumed, D idle.
            EnergyModel.Profile defaults = EnergyStore.get(c).profile();
            check(defaults.selected == EnergyModel.Benchmark.WLTP && !defaults.hasCapacity()
                    && defaults.idle == EnergyModel.IdleProfile.D, "defaults: WLTP, capacity empty, idle D");
            check(Double.isNaN(EnergyStore.get(c).homeRate()), "defaults: no home rate assumed");

            // 0. energy.db schema 1 → 2 keeps every raw record; everything below runs on the migrated file.
            migration(c);

            // A boot is not a drive: nothing starts without the driver or real driving.
            EnergyJournal.Status s = status(c);
            check(!s.active && s.session == null && s.askStart, "fresh boot: no journey, check-in offered");
            EnergyJournal.forgetForTests();
            EnergyJournal.serviceEnabled = false;EnergyClient.call(c,"journal.serviceEnabled",false);
            for (int i = 0; i < 40; i++) main(() -> EnergyJournal.get(c).observeSpeed(0, false));
            check(status(c).session == null, "reboot/USB with unreliable GPS starts nothing");

            // 1. A normal drive: 80 % → 74 % over 20 km (333 km equivalent, on WLTP 330).
            main(() -> EnergyJournal.get(c).startDrive(80, EnergyModel.Charged.NO));
            check(status(c).active, "check-in starts a journey");
            drive(c, 20, 72);
            near(20, status(c).km(), 0.2, "GPS distance from TripComputer's odometer");
            main(() -> EnergyJournal.get(c).endDrive(74, EnergyModel.Charged.NO));
            s = status(c);
            check(!s.active && s.drive != null, "ended drive has a result");
            near(333.3, s.drive.equivalentRangeKm, 4, "equivalent range 100·D/p");
            check(s.drive.verdict == EnergyModel.Verdict.ON, "verdict on benchmark");
            check(Double.isNaN(s.drive.netKwh), "no kWh without a usable capacity");
            EnergyModel.Profile p = EnergyStore.get(c).profile();
            p.usableKwh = 30;
            main(() -> EnergyJournal.get(c).saveProfile(p));
            s = status(c);
            near(1.8, s.drive.netKwh, 0.01, "capacity added later: C·p/100 recomputed");
            near(9.0, s.drive.kwhPer100Km, 0.15, "kWh/100 km = C·p/D");
            check(s.canContinue == false, "a drive ended with a reading isn't offered to continue");

            // 2. Charged in between: that interval is excluded, the next drive counts.
            main(() -> EnergyJournal.get(c).startDrive(90, EnergyModel.Charged.YES));
            drive(c, 15, 60);
            main(() -> EnergyJournal.get(c).endDrive(85, EnergyModel.Charged.NO));
            s = status(c);
            check(hasExclusion(s, EnergyModel.Exclusion.CHARGED), "charging interval excluded");
            near(35, s.allTime.validKm, 0.5, "all time sums valid km");
            near(11, s.allTime.validDrop, 1e-9, "all time sums valid drop");
            check(EnergyModel.learned(s.allTime), "learned after 10 points");

            // 3. No drop, battery rose: never negative consumption.
            main(() -> EnergyJournal.get(c).startDrive(85, EnergyModel.Charged.NO));
            drive(c, 5, 60);
            main(() -> EnergyJournal.get(c).endDrive(85, EnergyModel.Charged.NO));
            check(hasExclusion(status(c), EnergyModel.Exclusion.NO_DROP), "zero drop excluded");
            main(() -> EnergyJournal.get(c).startDrive(84, EnergyModel.Charged.NO));
            drive(c, 2, 60);
            main(() -> EnergyJournal.get(c).endDrive(86, EnergyModel.Charged.NO));
            check(hasExclusion(status(c), EnergyModel.Exclusion.BATTERY_ROSE), "battery rise excluded");

            // 4. Power cut mid-drive: never resumed silently after a reboot.
            main(() -> EnergyJournal.get(c).startDrive(70, EnergyModel.Charged.NO));
            drive(c, 5, 60);
            main(() -> EnergyJournal.get(c).heartbeatNow());
            long cut = status(c).session.id;
            EnergyStore.get(c).heartbeat(cut, System.currentTimeMillis(), "previous-boot");
            EnergyJournal.forgetForTests();
            EnergyJournal.serviceEnabled = false;EnergyClient.call(c,"journal.serviceEnabled",false);
            s = status(c);
            check(!s.active && s.recovery != null && s.recovery.id == cut, "power cut: journey waits for recovery");
            near(5, s.km(), 0.2, "recovered distance from the last heartbeat");
            check(!s.askStart, "no new check-in while recovery is pending");
            fastFor(c, 32);
            check(status(c).recovery != null && EnergyStore.get(c).activeSession().id == cut, "driving doesn't start a second journey");
            main(() -> EnergyJournal.get(c).recoverEnd(66, EnergyModel.Charged.NO));
            s = status(c);
            check(s.recovery == null && !s.active, "recovered drive ended");
            check("ended".equals(EnergyStore.get(c).session(cut).state), "session state ended");

            // 5. Ended without a reading: continue within 6 h, same journey.
            main(() -> EnergyJournal.get(c).startDrive(66, EnergyModel.Charged.NO));
            long first = status(c).session.id;
            drive(c, 2, 50);
            main(() -> EnergyJournal.get(c).endDrive(Double.NaN, EnergyModel.Charged.UNKNOWN));
            check(status(c).canContinue, "continue offered after an end without a reading");
            main(() -> EnergyJournal.get(c).continueDrive(Double.NaN, EnergyModel.Charged.UNKNOWN));
            check(status(c).active && status(c).session.id == first, "continue resumes the same journey");
            drive(c, 3, 50);
            main(() -> EnergyJournal.get(c).endDrive(64, EnergyModel.Charged.NO));
            s = status(c);
            check(s.drive != null && Math.abs(s.drive.km - 5) < 0.2 && s.drive.drop == 2, "continued drive is one interval: 66 → 64 over 5 km");

            // 6. Idle: standstill opens a period at the profile's kW; a change starts a new one.
            main(() -> EnergyJournal.get(c).startDrive(64, EnergyModel.Charged.NO));
            long idleSession = status(c).session.id;
            main(() -> EnergyJournal.get(c).setIdleProfile(EnergyModel.IdleProfile.C));
            drive(c, 1, 50);
            stand(c, 30);
            check(EnergyStore.get(c).openIdlePeriod(idleSession) != null, "standstill opens an idle period");
            main(() -> EnergyJournal.get(c).setIdleProfile(EnergyModel.IdleProfile.A));
            main(() -> EnergyJournal.get(c).setReady(true));
            main(() -> EnergyJournal.get(c).endDrive(63, EnergyModel.Charged.NO));
            java.util.List<IdleLedger.Period> periods = EnergyStore.get(c).idle(idleSession);
            check(periods.size() == 3, "C standstill, A standstill, A READY: " + periods.size());
            near(1.2, periods.get(0).kw, 1e-9, "first period keeps 1.2 kW");
            near(0.3, periods.get(1).kw, 1e-9, "after the change 0.3 kW");
            for (IdleLedger.Period period : periods) check(!period.open(), "all idle periods closed at the end");
            for (int i = 1; i < periods.size(); i++) check(periods.get(i).start >= periods.get(i - 1).end, "idle periods never overlap");

            // 7. Reset current drive: only that journey is set aside.
            s = status(c);
            double validKm = s.allTime.validKm, validDrop = s.allTime.validDrop;
            int readings = EnergyStore.get(c).checkIns().size();
            double odometer = TripComputer.get(c).odometerKm();
            main(() -> EnergyJournal.get(c).startDrive(63, EnergyModel.Charged.NO));
            long reset = status(c).session.id;
            drive(c, 4, 60);
            main(() -> EnergyJournal.get(c).resetCurrentDrive());
            s = status(c);
            check("abandoned".equals(EnergyStore.get(c).session(reset).state), "reset drive abandoned");
            check(EnergyStore.get(c).checkIns().size() == readings + 1, "its check-in kept in the record");
            near(validKm, s.allTime.validKm, 1e-6, "reset drive: all-time km unchanged");
            near(validDrop, s.allTime.validDrop, 1e-9, "reset drive: all-time drop unchanged");
            check(EnergyStore.get(c).profile().hasCapacity(), "reset drive: profile kept");
            check(TripComputer.get(c).odometerKm() >= odometer, "reset drive: trip odometer untouched");

            // 8. Reset learned consumption: learning starts again, records stay.
            int intervals = status(c).intervals.size();
            int before = EnergyStore.get(c).checkIns().size();
            SystemClock.sleep(5);
            main(() -> EnergyJournal.get(c).resetLearning());
            s = status(c);
            check(s.allTime.validDrop == 0 && !EnergyModel.learned(s.allTime), "learning reset: benchmark-based again");
            check(s.intervals.size() == intervals && EnergyStore.get(c).checkIns().size() == before, "learning reset keeps records");
            check(EnergyStore.get(c).profile().hasCapacity(), "learning reset: profile kept");

            // 9. Auto-start: sustained driving without a check-in, then the starting % added later.
            fastFor(c, 32);
            s = status(c);
            check(s.active && s.needsStartPercent && "auto".equals(s.session.createdBy), "driving without a check-in starts a journey with no %: active="
                    + s.active + " session=" + (s.session == null ? null : s.session.state + "/" + s.session.createdBy) + " needsStart=" + s.needsStartPercent
                    + " start=" + (s.startReading == null ? null : s.startReading.percent));
            main(() -> EnergyJournal.get(c).setStartPercent(60));
            check(!status(c).needsStartPercent && status(c).startReading.percent == 60, "missing start % added as a correction");
            main(() -> EnergyJournal.get(c).endDrive(58, EnergyModel.Charged.NO));

            // 10. Backup round trip.
            org.json.JSONObject backup = EnergyStore.get(c).export();
            int all = EnergyStore.get(c).checkIns().size();
            main(() -> EnergyJournal.get(c).deleteHistory());
            check(EnergyStore.get(c).checkIns().isEmpty() && EnergyStore.get(c).lastSession() == null, "delete history empties records");
            check(EnergyStore.get(c).profile().hasCapacity(), "delete history keeps the vehicle profile");
            check(TripComputer.get(c).history().size() >= tripsBefore, "delete history keeps trip history");
            check(launcher.getInt("energy_test_marker", 0) == 42, "delete history keeps launcher settings");
            EnergyStore.get(c).restore(backup);
            check(EnergyStore.get(c).checkIns().size() == all, "backup restores every check-in");

            // 11–14. Manual mode, improved.
            parkedDrain(c);
            chargeLog(c);
            carFigures(c);
            fixMistakes(c);
            carTables(c);
            pageFits(c);

            if (!keep) main(() -> EnergyJournal.get(c).deleteHistory());
            result.putString("stream", "PASS · " + checks + " energy checks\n");
            outcome=Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
            outcome=Activity.RESULT_CANCELED;
        } finally {
            if (!keep) {
                SharedPreferences.Editor editor = profilePrefs.edit().clear();
                for (Map.Entry<String, ?> entry : savedProfile.entrySet()) editor.putString(entry.getKey(), String.valueOf(entry.getValue()));
                editor.commit();
                org.json.JSONObject values=new org.json.JSONObject();try{for(Map.Entry<String,?> entry:savedProfile.entrySet())values.put(entry.getKey(),new org.json.JSONObject().put("t","str").put("v",String.valueOf(entry.getValue())));}catch(Exception failure){throw new RuntimeException(failure);}
                EnergyClient.call(c,"profile.restore",values);
            }
            launcher.edit().remove("energy_test_marker").commit();
            main(()->CarLinkController.get(c).stop());
            if(hadMode)carPrefs.edit().putString("mode",savedMode).commit();else carPrefs.edit().remove("mode").commit();
            EnergyJournal.serviceEnabled = true;EnergyClient.call(c,"journal.serviceEnabled",true);
        }
        finish(outcome,result);
    }

    // ---- Manual mode, improved (energy-companion.md §2) ----

    /** The version 1 schema exactly as the Stage 3–4 build created it (frozen here on purpose). */
    private static final String[] V1 = {
            "CREATE TABLE checkin (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, percent REAL,"
                    + " kind TEXT NOT NULL, charged TEXT NOT NULL, session INTEGER, meters REAL NOT NULL DEFAULT 0,"
                    + " active REAL NOT NULL DEFAULT 0, unknown REAL NOT NULL DEFAULT 0, poor REAL NOT NULL DEFAULT 0,"
                    + " excluded INTEGER NOT NULL DEFAULT 0, superseded_by INTEGER, assumptions TEXT)",
            "CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, state TEXT NOT NULL,"
                    + " created_by TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, heartbeat INTEGER, boot TEXT,"
                    + " base TEXT NOT NULL, final TEXT)",
            "CREATE TABLE idle (id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER NOT NULL,"
                    + " start INTEGER NOT NULL, end_time INTEGER, profile TEXT NOT NULL, kw REAL NOT NULL, reason TEXT NOT NULL)",
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
            "CREATE INDEX checkin_time ON checkin(time)",
            "CREATE INDEX idle_session ON idle(session)"};

    private void deleteDatabaseFiles(Context c) {
        File file = c.getDatabasePath(EnergyStore.NAME);
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(file.getPath() + suffix).delete();
    }

    private static String schema(SQLiteDatabase db) {
        StringBuilder text = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
                + " AND name != 'android_metadata' ORDER BY name", null)) {
            while (c.moveToNext()) text.append(c.getString(0)).append(' ').append(c.getString(1)).append(": ").append(c.getString(2)).append('\n');
        }
        return text.toString();
    }

    private void migration(Context c) throws Exception {
        long t = System.currentTimeMillis() - 2 * HOUR;
        // Close, replace and reopen on the main thread, so Home can't open the file halfway through.
        main(() -> {
            EnergyJournal.get(c).deleteHistory();
            EnergyStore.closeForTests();
            EnergyJournal.forgetForTests();
            deleteDatabaseFiles(c);
            SQLiteDatabase v1 = SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath(EnergyStore.NAME), null);
            try {
                for (String sql : V1) v1.execSQL(sql);
                v1.execSQL("INSERT INTO session (id, state, created_by, started, ended, heartbeat, boot, base, final)"
                        + " VALUES (1, 'ended', 'user', ?, ?, ?, 'v1', '[0,0,0,0,0,0]', '[20000,1100,100,0,1200,0]')",
                        new Object[]{t, t + HOUR, t + HOUR});
                v1.execSQL("INSERT INTO checkin (time, percent, kind, charged, session, meters, active, unknown, poor)"
                        + " VALUES (?, 80, 'start', 'NO', 1, 0, 0, 0, 0)", new Object[]{t});
                v1.execSQL("INSERT INTO checkin (time, percent, kind, charged, session, meters, active, unknown, poor)"
                        + " VALUES (?, 74, 'end', 'NO', 1, 20000, 1200, 0, 0)", new Object[]{t + HOUR});
                v1.execSQL("INSERT INTO idle (session, start, end_time, profile, kw, reason) VALUES (1, ?, ?, 'D', 3.0, 'STANDSTILL')",
                        new Object[]{t + HOUR / 2, t + HOUR / 2 + 60_000});
                v1.execSQL("INSERT INTO meta (key, value) VALUES ('c_meters', '20000.0'), ('c_active', '1200.0'), ('learning_since', '0')");
                v1.setVersion(1);
            } finally { v1.close(); }
        });
        EnergyJournal.serviceEnabled = false;EnergyClient.call(c,"journal.serviceEnabled",false);
        EnergyStore store = EnergyStore.get(c);
        check(store.getReadableDatabase().getVersion() == EnergyStore.VERSION && EnergyStore.VERSION == 4, "energy.db migrated to version 4");
        List<EnergyStore.CheckIn> rows = store.checkIns();
        check(rows.size() == 2 && rows.get(0).percent == 80 && rows.get(1).percent == 74, "migration keeps every check-in");
        for (EnergyStore.CheckIn row : rows) {
            check(EnergyStore.ENTERED.equals(row.source), "migrated check-ins are marked entered");
            check(Double.isNaN(row.odometerKm) && Double.isNaN(row.tripKm) && Double.isNaN(row.tripKwhPer100Km), "no car figures invented by the migration");
        }
        check(store.session(1) != null && "ended".equals(store.session(1).state), "migration keeps the session");
        check(store.idle(0).size() == 1 && store.idle(0).get(0).kw == 3.0, "migration keeps the idle period");
        near(20000, store.counters()[0], 1e-9, "migration keeps the GPS counters");
        check(store.charges().isEmpty(), "the charge log starts empty");
        EnergyJournal.Status s = status(c);
        check(s.intervals.size() == 1 && s.intervals.get(0).learnable(), "migrated drive still learnable");
        near(333.3, s.drive.equivalentRangeKm, 0.1, "migrated drive gives the same result");
        String migrated = schema(store.getReadableDatabase());
        check(migrated.contains("charge") && migrated.contains("source TEXT NOT NULL DEFAULT 'entered'"), "schema 2 has the charge log and sources");
        check(migrated.contains("table car_sample") && migrated.contains("table car_session"), "schema 2 has the live car tables");
        check(store.lastCarSeq("a1b2c3d4") == 0 && store.carSessions(null, 10).isEmpty(), "the live car tables start empty");
        org.json.JSONObject export = store.export();
        check(export.getInt("schema") == EnergyStore.VERSION && export.has("charge") && export.has("car_event"), "backups include the current schema, charges and car events");

        // A schema 1 backup (no charge log, no car figures) still restores, with the new columns' defaults.
        org.json.JSONObject old = new org.json.JSONObject(export.toString());
        old.put("schema", 1);
        for (String table : new String[]{"charge", "car_sample", "car_session"}) old.remove(table);
        org.json.JSONArray checkins = old.getJSONArray("checkin");
        for (int i = 0; i < checkins.length(); i++) {
            for (String column : new String[]{"source", "odometer_km", "trip_km", "trip_kwh100"}) checkins.getJSONObject(i).remove(column);
        }
        main(() -> EnergyJournal.get(c).deleteHistory());
        store.restore(old);
        rows = store.checkIns();
        check(rows.size() == 2 && EnergyStore.ENTERED.equals(rows.get(1).source), "a schema 1 backup restores as entered check-ins");

        // A fresh database ends up identical to a migrated one.
        main(() -> {
            EnergyStore.closeForTests();
            EnergyJournal.forgetForTests();
            deleteDatabaseFiles(c);
        });
        EnergyJournal.serviceEnabled = false;EnergyClient.call(c,"journal.serviceEnabled",false);
        check(migrated.equals(schema(EnergyStore.get(c).getReadableDatabase())), "a fresh energy.db matches a migrated one");
        main(() -> EnergyJournal.get(c).deleteHistory());
    }

    private static double[] counters(double km, double activeSeconds) {
        return new double[]{km * 1000, activeSeconds, 0, 0, activeSeconds, 0};
    }

    /** A finished drive written straight to the store, so parked hours can be in the past. */
    private static void drive(EnergyStore store, EnergyModel.Profile p, long start, double from, EnergyModel.Charged charged,
                              double[] before, long end, double to, double[] after) {
        long id = store.createSession("user", start, "energy-test", before);
        store.addCheckIn(start, from, "start", charged, id, before, p);
        store.addCheckIn(end, to, "end", EnergyModel.Charged.NO, id, after, p);
        store.setSessionState(id, "ended", end, after);
    }

    private void parkedDrain(Context c) {
        // 11. Parked drain: a drive's end % to the next start %, charged since "no", parked 6 h or more.
        main(() -> EnergyJournal.get(c).deleteHistory());
        EnergyStore store = EnergyStore.get(c);
        EnergyModel.Profile p = store.profile();
        long t0 = System.currentTimeMillis() - 3 * 24 * HOUR;
        double[][] k = {counters(0, 0), counters(20, 1200), counters(40, 2400), counters(55, 3300), counters(70, 4200), counters(85, 5100)};
        EnergyModel.Charged no = EnergyModel.Charged.NO;
        drive(store, p, t0, 80, no, k[0], t0 + HOUR, 74, k[1]);
        drive(store, p, t0 + 15 * HOUR, 72, no, k[1], t0 + 16 * HOUR, 66, k[2]);   // parked 14 h: 2 points
        drive(store, p, t0 + 18 * HOUR, 65, no, k[2], t0 + 19 * HOUR, 60, k[3]);   // parked 2 h: too short
        drive(store, p, t0 + 31 * HOUR, 59, no, k[3], t0 + 32 * HOUR, 53, k[4]);   // parked 12 h: 1 point
        drive(store, p, t0 + 42 * HOUR, 90, EnergyModel.Charged.YES, k[4], t0 + 43 * HOUR, 84, k[5]); // charged
        store.saveCounters(k[5]);
        EnergyJournal.Status s = status(c);
        check(s.parked.size() == 4, "four parked periods between drives: " + s.parked.size());
        check(s.parked.get(0).counted() && s.parked.get(2).counted(), "overnight parks counted");
        check(s.parked.get(1).exclusion == EnergyModel.Exclusion.SHORT_PARK, "a 2 h park isn't counted");
        check(s.parked.get(3).exclusion == EnergyModel.Exclusion.CHARGED, "charged since: not drain");
        near(26, s.drain.hours, 1e-6, "drain hours summed");
        near(24 * 3 / 26.0, s.drain.pointsPerDay(), 1e-6, "parked drain = 24 × Σ points ÷ Σ hours");
        check(s.allTime.validIntervals == 5, "the drives themselves are still learnable: " + s.allTime.validIntervals);
        near(12, s.parked.get(2).interval.hours, 1e-6, "parked hours from the check-in times");
        // A journey never contains parked drain: standing at a light or READY is the idle model's.
        for (EnergyModel.Parked parked : s.parked) check(parked.interval.activeSeconds == 0, "parked periods have no journey time");
    }

    private void chargeLog(Context c) {
        // 12. Charge log: raw entries, summed; the capacity estimate is only used once accepted.
        EnergyStore store = EnergyStore.get(c);
        double entered = store.profile().usableKwh;
        check(EnergyModel.usable(entered), "capacity entered earlier is kept: " + entered);
        long[] rise = new long[1];
        main(() -> rise[0] = EnergyJournal.get(c).startDrive(95, EnergyModel.Charged.YES, null));
        check(rise[0] > 0, "the check-in that showed the rise has an id");
        check(status(c).parked.get(status(c).parked.size() - 1).exclusion == EnergyModel.Exclusion.CHARGED,
                "journal check-ins after a drive form parked periods too");
        EnergyModel.Charge one = new EnergyModel.Charge();
        one.checkIn = rise[0];
        one.before = 48;
        one.after = 95;
        one.kwh = 22.09;  // 0.47 kWh per point
        one.cost = 1325.4; // 60 per kWh
        one.current = EnergyModel.Current.AC;
        one.place = EnergyModel.Place.HOME;
        main(() -> EnergyJournal.get(c).logCharge(one));
        EnergyJournal.Status s = status(c);
        check(s.charges.size() == 1 && s.charges.get(0).checkIn == rise[0] && s.charges.get(0).current == EnergyModel.Current.AC
                && s.charges.get(0).place == EnergyModel.Place.HOME, "charge saved with its check-in, AC and home");
        near(0.47, s.charging.gridKwhPerPoint(), 1e-9, "grid kWh per point = kWh ÷ points gained");
        near(47, s.charging.capacityUpperBoundKwh(), 1e-9, "capacity upper bound = kWh ÷ (% gained ÷ 100)");
        near(60, s.charging.ratePerKwh(), 1e-9, "rate = cost ÷ kWh");
        near(0.47 * 100 / s.kmPerPoint, s.charging.gridKwhPer100Km(s.kmPerPoint), 1e-9, "grid kWh per 100 km");
        near(0.47 * 100 / s.kmPerPoint * 60, s.charging.costPer100Km(s.kmPerPoint), 1e-6, "cost per 100 km = grid kWh/100 km × rate");
        near(entered, store.profile().usableKwh, 1e-9, "the estimate never replaces the entered capacity by itself");

        // A mistyped entry is withdrawn: it stops counting and stays in the record.
        EnergyModel.Charge typo = new EnergyModel.Charge();
        typo.before = 60;
        typo.after = 70;
        typo.kwh = 30;
        long[] typoId = new long[1];
        main(() -> typoId[0] = EnergyJournal.get(c).logCharge(typo));
        check(Math.abs(status(c).charging.capacityUpperBoundKwh() - 47) > 1, "a mistyped charge skews the estimate");
        main(() -> EnergyJournal.get(c).withdrawCharge(typoId[0]));
        near(47, status(c).charging.capacityUpperBoundKwh(), 1e-9, "withdrawn charge no longer counts");
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*), SUM(superseded_by IS NOT NULL) FROM charge", null)) {
            check(rows.moveToFirst() && rows.getInt(0) == 2 && rows.getInt(1) == 1, "withdrawn charge kept in the record, marked");
        }
        long[] empty = new long[1];
        main(() -> empty[0] = EnergyJournal.get(c).logCharge(new EnergyModel.Charge()));
        check(empty[0] == 0 && status(c).charges.size() == 1, "an empty charge log isn't saved");

        // A home charge with kWh and no price uses the home rate only once one is entered.
        EnergyModel.Charge home = new EnergyModel.Charge();
        home.before = 30;
        home.after = 50;
        home.kwh = 9.4;
        home.place = EnergyModel.Place.HOME;
        main(() -> EnergyJournal.get(c).logCharge(home));
        check(status(c).charging.pricedCharges == 1, "no home rate: the unpriced charge isn't priced");
        main(() -> EnergyJournal.get(c).setHomeRate(50));
        s = status(c);
        check(s.charging.pricedCharges == 2 && s.charging.homeRateCharges == 1, "home rate prices the home charge");
        near((1325.4 + 470) / (22.09 + 9.4), s.charging.ratePerKwh(), 1e-9, "rate summed over priced kWh");
        near(47, s.charging.capacityUpperBoundKwh(), 1e-9, "capacity summed over both charges");

        double estimate = s.charging.capacityUpperBoundKwh();
        main(() -> EnergyJournal.get(c).acceptCapacity(estimate));
        near(47, store.profile().usableKwh, 1e-9, "accepted estimate becomes the usable capacity");
        main(() -> EnergyJournal.get(c).endDrive(90, EnergyModel.Charged.NO));
    }

    private int rawRows(String table) {
        try (Cursor rows = EnergyStore.get(getTargetContext()).getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return rows.moveToFirst() ? rows.getInt(0) : -1;
        }
    }

    private void fixMistakes(Context c) {
        // 15. Fixing mistakes: edit and remove, and nothing is ever deleted.
        main(() -> EnergyJournal.get(c).deleteHistory());
        main(() -> EnergyJournal.get(c).startDrive(80, EnergyModel.Charged.NO));
        drive(c, 20, 72);
        long[] end = new long[1];
        main(() -> end[0] = EnergyJournal.get(c).endDrive(74, EnergyModel.Charged.NO));
        EnergyJournal.Status s = status(c);
        check(s.intervals.size() == 1 && s.intervals.get(0).drop == 6, "a drive 80 → 74");

        EnergyModel.CarFigures fixed = new EnergyModel.CarFigures();
        fixed.odometerKm = 4000;
        main(() -> EnergyJournal.get(c).correctReading(end[0], 72, EnergyModel.Charged.NO, fixed));
        s = status(c);
        check(s.intervals.size() == 1 && s.intervals.get(0).drop == 8, "an edited end % recomputes the drive: 80 → 72");
        check(s.endReading.odometerKm == 4000, "an edit can add the odometer");
        check(rawRows("checkin") == 3, "the mistyped reading stays in the record, superseded");
        long corrected = s.endReading.id;

        main(() -> EnergyJournal.get(c).removeReading(corrected));
        s = status(c);
        check(s.intervals.isEmpty(), "a removed reading stops counting");
        check(EnergyStore.get(c).removedCheckIns().size() == 1 && rawRows("checkin") == 3, "a removed reading is kept, listed under Removed entries");
        main(() -> EnergyJournal.get(c).restoreReading(corrected));
        s = status(c);
        check(s.intervals.size() == 1 && s.intervals.get(0).drop == 8, "a restored reading counts again, exactly as it was");
        check(EnergyStore.get(c).removedCheckIns().isEmpty(), "nothing left under Removed entries");

        EnergyModel.Charge typo = new EnergyModel.Charge();
        typo.before = 30; typo.after = 80; typo.kwh = 22; typo.cost = 13200; // an extra zero
        typo.place = EnergyModel.Place.HOME; typo.current = EnergyModel.Current.AC;
        long[] id = new long[1];
        main(() -> id[0] = EnergyJournal.get(c).logCharge(typo));
        EnergyModel.Charge right = new EnergyModel.Charge();
        right.time = typo.time; right.before = 30; right.after = 80; right.kwh = 22; right.cost = 1320;
        right.place = EnergyModel.Place.HOME; right.current = EnergyModel.Current.AC;
        main(() -> id[0] = EnergyJournal.get(c).editCharge(id[0], right));
        s = status(c);
        check(s.charges.size() == 1 && s.charges.get(0).cost == 1320 && s.charging.home.money == 1320, "an edited charge replaces the typo in every figure");
        check(s.charges.get(0).time == typo.time, "an edit keeps when the charge happened");
        check(rawRows("charge") == 2, "the mistyped charge stays in the record");
        main(() -> EnergyJournal.get(c).withdrawCharge(id[0]));
        check(status(c).charges.isEmpty() && EnergyStore.get(c).removedCharges().size() == 1, "a removed charge stops counting and is kept");
        main(() -> EnergyJournal.get(c).restoreCharge(id[0]));
        check(status(c).charges.size() == 1 && status(c).charging.money == 1320, "a restored charge counts again");
    }

    private void carFigures(Context c) throws Exception {
        // 13. Odometer and trip computer: shown beside OABL's figures, never used as the distance D.
        EnergyModel.CarFigures start = new EnergyModel.CarFigures();
        start.odometerKm = 41210;
        main(() -> EnergyJournal.get(c).startDrive(90, EnergyModel.Charged.NO, start));
        drive(c, 20, 72);
        EnergyModel.CarFigures end = new EnergyModel.CarFigures();
        end.odometerKm = 41230.4;
        end.tripKm = 20.6;
        end.tripKwhPer100Km = 14.1;
        main(() -> EnergyJournal.get(c).endDrive(84, EnergyModel.Charged.NO, end));
        EnergyJournal.Status s = status(c);
        near(20.4, s.driveOdometerKm, 1e-6, "the car's odometer distance for the drive");
        near(20, s.driveOdometerGpsKm, 0.2, "GPS between the same check-ins");
        near(20, s.drive.km, 0.2, "results still use the GPS distance");
        check(s.endReading != null && s.endReading.tripKm == 20.6 && s.endReading.tripKwhPer100Km == 14.1, "trip computer kept with the end reading");
        check(EnergyStore.ENTERED.equals(s.endReading.source), "typed check-ins are marked entered");
        check(s.distances.intervals == 1, "one drive to compare GPS with the odometer: " + s.distances.intervals);
        near((s.driveOdometerGpsKm / 20.4 - 1) * 100, s.distances.gpsDifferencePercent(), 1e-6, "GPS vs odometer");
        // A correction keeps what was typed from the car's displays.
        EnergyStore store = EnergyStore.get(c);
        main(() -> store.correctCheckIn(s.startReading.id, 90, EnergyModel.Charged.NO, store.profile()));
        EnergyJournal.Status corrected = status(c);
        check(corrected.startReading.id != s.startReading.id && corrected.startReading.odometerKm == 41210, "corrections keep the odometer");
        near(20.4, corrected.driveOdometerKm, 1e-6, "odometer distance unchanged by the correction");

        // Backup round trip with the charge log and the car figures.
        org.json.JSONObject backup = store.export();
        int charges = store.charges().size(), readings = store.checkIns().size();
        main(() -> EnergyJournal.get(c).deleteHistory());
        check(store.charges().isEmpty(), "delete history removes the charge log");
        check(EnergyModel.usable(store.homeRate()) && store.profile().hasCapacity(), "delete history keeps the home rate and capacity");
        store.restore(backup);
        check(store.charges().size() == charges && store.checkIns().size() == readings, "backup restores charges and check-ins");
        check(status(c).endReading.tripKwhPer100Km == 14.1, "backup restores the trip computer figure");
    }

    private void carTables(Context c) throws Exception {
        // 14. Live car tables (energy-companion.md §3.8): raw samples keyed by OABL Link's install id and
        // sequence number, derived sessions.
        EnergyStore store = EnergyStore.get(c);
        long t = System.currentTimeMillis();
        String link = "a1b2c3d4", reinstalled = "e5f60718";
        check(store.insertCarSample(link, 1, t, "{\"soc\":62}") && store.insertCarSample(link, 2, t + 1000, "{\"soc\":61.5}")
                && store.insertCarSample(link, 5, t + 5000, "{\"odometer\":3851}"), "car samples stored");
        check(!store.insertCarSample(link, 2, t + 9000, "{\"soc\":10}"), "a repeated sequence number is ignored");
        List<EnergyStore.CarSampleRow> after = store.carSamplesAfter(link, 1, 10);
        check(after.size() == 2 && after.get(0).seq == 2 && after.get(0).data.contains("61.5") && after.get(1).seq == 5
                && link.equals(after.get(0).link), "samples after a sequence, oldest first, never overwritten");
        check(store.carSamplesAfter(link, 0, 1).size() == 1, "sample pages respect the limit");
        check(store.lastCarSeq(link) == 5, "the newest sequence for catching up");
        // A reinstalled head-unit app starts its numbers again at 1: kept apart, nothing lost.
        check(store.insertCarSample(reinstalled, 1, t + 20_000, "{\"soc\":60}") && store.insertCarSample(reinstalled, 2, t + 21_000, "{\"soc\":59}"),
                "a new link's sequence numbers don't collide with the old one's");
        check(store.lastCarSeq(reinstalled) == 2 && store.lastCarSeq(link) == 5 && store.carSamplesAfter(reinstalled, 0, 10).size() == 2
                && store.carSamplesAfter(link, 0, 10).size() == 3, "catch-up is per link");
        check(store.lastCarSeq("ffffffff") == 0, "an unknown link starts from 0");
        long drive = store.addCarSession("drive", "open", t, 62, null);
        store.updateCarSession(drive, "closed", t + 600_000, 58, "{\"km\":12.3}");
        long charge = store.addCarSession("charge", "open", t + 700_000, Double.NaN, null);
        EnergyStore.CarSession closed = store.carSession(drive);
        check(closed != null && "closed".equals(closed.state) && closed.ended == t + 600_000 && closed.startPercent == 62
                && closed.endPercent == 58 && closed.data.contains("12.3"), "car session updated in place");
        EnergyStore.CarSession open = store.carSession(charge);
        check(open != null && open.ended == 0 && Double.isNaN(open.startPercent), "an open car session with no % yet");
        check(store.carSessions("drive", 10).size() == 1 && store.carSessions(null, 10).get(0).id == charge, "car sessions by kind, newest first");
        check(store.checkIns().size() > 0 && status(c).intervals.size() > 0, "car data leaves the entered records alone");

        org.json.JSONObject backup = store.export();
        check(backup.has("car_sample") && backup.getJSONArray("car_session").length() == 2,
                "backups keep car sessions and every raw sample");
        main(() -> EnergyJournal.get(c).deleteHistory());
        check(store.lastCarSeq(link) == 5 && store.lastCarSeq(reinstalled) == 2 && store.carSessions(null, 10).isEmpty(),
                "new totals retain raw car data while superseding active sessions");
        check(store.insertCarSample(link, 9, t, "{}"), "a sample after the wipe");
        store.restore(backup);
        check(store.lastCarSeq(link) == 9 && store.carSessions(null, 10).size() == 2,
                "a restore activates saved car sessions and retains samples added after the backup");
    }

    // ---- The Energy page and number pads fit without scrolling ----

    private String shell(String command) {
        try (ParcelFileDescriptor p = getUiAutomation().executeShellCommand(command);
             FileInputStream in = new FileInputStream(p.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object field(Object owner, String name) {
        try {
            java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static boolean inside(View view, Class<?> type) {
        for (Object parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) {
            if (type.isInstance(parent)) return true;
        }
        return false;
    }

    private static boolean scrolls(View view) {
        if (view instanceof ScrollView) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (scrolls(group.getChildAt(i))) return true;
        }
        return false;
    }

    /** Fit problems are collected over every window size, tab and dialog, then reported together. */
    private final List<String> misfits = new java.util.ArrayList<>();

    private void fits(boolean value, String label) {
        if (!value) misfits.add(label);
        checks++;
    }

    /** Every shown text outside a list is fully on screen and tall enough for its lines. */
    private void fullyShown(View view, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(inside(view, ScrollView.class))) {
            TextView text = (TextView) view;
            if (text.getText().length() > 0) {
                Rect bounds = new Rect();
                boolean visible = text.getGlobalVisibleRect(bounds);
                String name = "\"" + text.getText().toString().replace('\n', ' ') + "\"";
                fits(visible && bounds.height() == text.getHeight() && bounds.width() == text.getWidth(),
                        where + ": " + name + " fully visible (" + bounds.height() + " of " + text.getHeight() + " px)");
                android.text.Layout layout = text.getLayout();
                fits(layout == null || layout.getHeight() <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1,
                        where + ": " + name + " not cut short (" + (layout == null ? 0 : layout.getLineCount()) + " lines in "
                                + text.getWidth() + " × " + text.getHeight() + " px)");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) fullyShown(group.getChildAt(i), where);
        }
    }

    private static void tagged(View view, String tag, List<View> found) {
        if (tag.equals(view.getTag())) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tagged(group.getChildAt(i), tag, found);
        }
    }

    private static TextView labelled(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = labelled(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void pageFits(Context c) {
        // 14. Fixed screens: each tab's cards and each number pad fit, in all three window shapes.
        if (!Build.MODEL.contains("sdk_gphone")) return; // window sizes are only ever changed on the emulator
        String[] tabs = {"tabDrive", "tabAll", "tabCharging", "tabToday", "tabWeek", "tabMonth"};
        try {
            for (String size : new String[]{"1920x970", "1920x720", "960x970"}) {
                shell("wm size " + size);
                SystemClock.sleep(800);
                page = null;
                startActivitySync(new Intent(c, EnergyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                SystemClock.sleep(500);
                for (int t = 0; t < tabs.length; t++) {
                    String tab = tabs[t];
                    boolean list = t > 0;
                    main(() -> ((View) field(page, tab)).performClick());
                    waitForIdleSync();
                    main(() -> {
                        ViewGroup body = (ViewGroup) field(page, "body");
                        check(body.getChildCount() == 3, size + " " + tab + ": three cards");
                        check(!(body.getParent() instanceof ScrollView), "the page itself never scrolls");
                        for (int i = 0; i < body.getChildCount(); i++) {
                            check(!scrolls(body.getChildAt(i)) || (list && i == 2), size + " " + tab + ": only the list card scrolls");
                        }
                        fullyShown(body, size + " " + tab);
                        fullyShown(((ViewGroup) body.getParent()).getChildAt(0), size + " header");
                        capture(size + "-" + tab, page.getWindow().getDecorView());
                    });
                }
                LauncherTheme.Palette palette = (LauncherTheme.Palette) field(page, "palette");
                String[][] dialogs = {{"checkIn", "Start a new drive", "Cancel"}, {"endDrive", "End drive", "Keep driving"},
                        {"chargeLog", "Save charge", "Cancel"}};
                for (String[] d : dialogs) {
                    AlertDialog[] dialog = new AlertDialog[1];
                    main(() -> dialog[0] = "checkIn".equals(d[0]) ? EnergyDialogs.checkIn(page, palette, null)
                            : "endDrive".equals(d[0]) ? EnergyDialogs.endDrive(page, palette, null)
                            : EnergyDialogs.chargeLog(page, palette, 0, null, null));
                    waitForIdleSync();
                    SystemClock.sleep(300);
                    main(() -> {
                        View decor = dialog[0].getWindow().getDecorView();
                        capture(size + "-" + d[0], decor);
                        for (int i = 1; i < d.length; i++) check(labelled(decor, d[i]) != null, size + " " + d[0] + ": has " + d[i]);
                        fullyShown(decor, size + " " + d[0]);
                    });
                    // Each tile selected in turn: its prompt above the pad and every tile name read in full.
                    List<View> tiles = new java.util.ArrayList<>();
                    main(() -> tagged(dialog[0].getWindow().getDecorView(), "form_tile", tiles));
                    for (View tile : tiles) {
                        main(tile::performClick);
                        waitForIdleSync();
                        main(() -> {
                            List<View> texts = new java.util.ArrayList<>();
                            View decor = dialog[0].getWindow().getDecorView();
                            tagged(decor, "form_prompt", texts);
                            tagged(decor, "form_tile_name", texts);
                            for (View text : texts) {
                                android.text.Layout layout = ((TextView) text).getLayout();
                                fits(layout != null && layout.getEllipsisCount(0) == 0,
                                        size + " " + d[0] + ": \"" + ((TextView) text).getText() + "\" not ellipsized");
                            }
                        });
                    }
                    main(() -> dialog[0].dismiss());
                }
                main(() -> page.finish());
                waitForIdleSync();
            }
            check(misfits.isEmpty(), misfits.size() + " texts don't fit:\n" + String.join("\n", misfits));
        } finally {
            shell("wm size reset");
            SystemClock.sleep(800);
        }
    }

    private EnergyJournal.Status status(Context c) {
        EnergyJournal.Status[] holder = new EnergyJournal.Status[1];
        main(() -> holder[0] = EnergyJournal.get(c).status());
        return holder[0];
    }

    /** On the main thread; a failed check there is reported, not a crash, so the clean-up still runs. */
    private void main(Runnable action) {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error[0] = t; } });
        if (error[0] instanceof AssertionError) throw (AssertionError) error[0];
        if (error[0] instanceof RuntimeException) throw (RuntimeException) error[0];
        if (error[0] != null) throw new RuntimeException(error[0]);
    }

    private static boolean hasExclusion(EnergyJournal.Status s, EnergyModel.Exclusion exclusion) {
        for (EnergyModel.Interval interval : s.intervals) if (interval.exclusion == exclusion) return true;
        return false;
    }

    /** Fixes one second apart, as SpeedTracker delivers them: TripComputer first, then the journey. */
    private void drive(Context c, double km, double kph) {
        double step = kph / 3.6;
        int fixes = (int) Math.round(km * 1000 / step);
        for (int i = 0; i < fixes; i++) fix(c, step, (float) step);
    }

    private void stand(Context c, int seconds) {
        for (int i = 0; i < seconds; i++) fix(c, 0, 0f);
    }

    private void fix(Context c, double meters, float speed) {
        clock += 1_000_000_000L;
        lon += meters / (111_320 * Math.cos(Math.toRadians(LAT)));
        Location location = new Location("gps");
        location.setLatitude(LAT);
        location.setLongitude(lon);
        location.setAccuracy(5);
        location.setSpeed(speed);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(clock);
        main(() -> {
            TripComputer.get(c).accept(location, true);
            EnergyJournal.get(c).position(location, speed * 3.6f);
        });
    }

    /** Reliable speed readings above the auto-start threshold for this many real seconds. */
    private void fastFor(Context c, int seconds) {
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < until) {
            main(() -> EnergyJournal.get(c).observeSpeed(40, true));
            SystemClock.sleep(500);
        }
        main(() -> EnergyJournal.get(c).observeSpeed(40, true));
    }
}
