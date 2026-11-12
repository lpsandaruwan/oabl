package com.softiva.oabl;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * The EV journey and battery check-in flows (energy-model.md §9–10) on top of {@link EnergyStore},
 * {@link GpsMotion} and {@link IdleLedger}. Main-thread only.
 *
 * <ul>
 *   <li>A journey starts only from the driver's check-in, or from sustained GPS driving (then
 *       with no starting %). A box boot or USB reconnect is never treated as a drive.</li>
 *   <li>A journey left active by a power cut is never resumed silently after a reboot: Home asks
 *       to end it, continue it or keep it as a trip only.</li>
 *   <li>The previous ending % is never assumed to be the next starting %.</li>
 * </ul>
 */
final class EnergyJournal implements GpsMotion.Listener, IdleLedger.Sink {
    /** Driving at least this fast for this long, with no journey, starts one without a reading. */
    static final int AUTO_START_KPH = 15;
    static final long AUTO_START_MS = 30_000;
    /** "Continue the previous drive" is offered for this long after it ended without a reading. */
    static final long CONTINUE_WINDOW_MS = 6 * 3_600_000L;
    /** Parked this long during a journey: Home offers to end it. */
    static final long PARKED_PROMPT_MS = 3 * 60_000L;
    static final long HEARTBEAT_MS = 30_000;

    private static EnergyJournal instance;
    /** Tests feed GPS themselves and turn the service off. */
    static boolean serviceEnabled = true;
    private final Context context;
    private final EnergyStore store;
    private final String boot;
    private final long bootWall;

    // Runtime state while the journey service runs.
    private GpsMotion motion;
    private IdleLedger ledger;
    private long runningSession;
    private double lastOdometerKm = Double.NaN;
    private long lastHeartbeat;
    private long parkedSince;
    private boolean serviceRunning;

    // Auto-start and prompt state for this boot.
    private long fastSince = -1;
    private boolean notDrivingThisBoot;
    private long quietUntil;
    private Boolean offLongEnough;
    private int lastKph = -1;
    private long lastSpeedAt;
    private final List<Runnable> observers = new ArrayList<>();

    static synchronized EnergyJournal get(Context context) {
        if (instance == null) instance = new EnergyJournal(context.getApplicationContext());
        return instance;
    }

    private EnergyJournal(Context context) {
        this.context = context;
        this.store = EnergyClient.store(context);
        String count;
        try {
            count = Integer.toString(Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT));
        } catch (Settings.SettingNotFoundException e) {
            count = "unknown";
        }
        if(!EnergyClient.owner(context))EnergyClient.observe(context,this::changed);
        boot = count;
        bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    EnergyStore store() { return store; }

    void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    private void changed() { if(EnergyClient.owner(context))EnergyClient.changed(context); for (Runnable observer : new ArrayList<>(observers)) observer.run(); }

    // ---- What Home and the Energy page show ----

    static final class Status implements java.io.Serializable {
        EnergyModel.Profile profile;
        /** The active journey, or the last one when none is active. */
        EnergyStore.Session session;
        boolean active;
        /** An active journey from before a reboot, waiting for the driver's decision. */
        EnergyStore.Session recovery;
        EnergyStore.CheckIn startReading, lastReading;
        double[] totals = new double[EnergyStore.COUNTERS.length];
        double kmSinceReading, estimatedPercent = Double.NaN, rangeKm = Double.NaN, reserveRangeKm = Double.NaN;
        boolean learned;
        EnergyModel.AllTime allTime;
        List<EnergyModel.Interval> intervals = new ArrayList<>();
        /** Learnable intervals of this journey, summed. */
        EnergyModel.Result drive;
        List<EnergyModel.Exclusion> driveExclusions = new ArrayList<>();
        IdleLedger.Summary driveIdle, allIdle;
        GpsMotion.State gps = GpsMotion.State.UNKNOWN;
        boolean ready, askStart, askEnd, canContinue, needsStartPercent;
        long learningSince;

        // Manual mode, improved (energy-companion.md §2).
        /** This journey's end check-in, which carries the car's trip-computer figures when entered. */
        EnergyStore.CheckIn endReading;
        /** The car's odometer over this journey, and GPS between the same two check-ins; NaN without both. */
        double driveOdometerKm = Double.NaN, driveOdometerGpsKm = Double.NaN;
        List<EnergyModel.Charge> charges = new ArrayList<>();
        EnergyModel.Charging charging;
        List<EnergyModel.Parked> parked = new ArrayList<>();
        EnergyModel.Drain drain;
        EnergyModel.Distances distances;
        /** km per battery point behind derived figures: learned, or the benchmark's (then labelled). */
        double kmPerPoint;
        double homeRate = Double.NaN;
        String currency;

        double km() { return totals[0] / 1000.0; }
    }

    Status status() {
        if(!EnergyClient.owner(context)){return (Status)EnergyClient.call(context,"journal.status()");}
        long now = System.currentTimeMillis();
        Status status = new Status();
        status.profile = store.profile();
        status.learningSince = store.metaLong("learning_since", 0);
        EnergyStore.Session active = store.activeSession();
        if (active != null && needsRecovery(active)) {
            status.recovery = active;
            active = null;
        }
        status.session = active != null ? active : status.recovery != null ? status.recovery : store.lastSession();
        status.active = active != null;
        double[] counters = runningSession > 0 && motion != null ? countersOf(motion) : store.counters();

        // Intervals between consecutive current check-ins, and the all-time sums.
        List<EnergyStore.CheckIn> readings = store.checkIns();
        for (int i = 1; i < readings.size(); i++) {
            status.intervals.add(EnergyModel.between(readings.get(i - 1).reading(), readings.get(i).reading(), status.learningSince));
        }
        status.allTime = EnergyModel.aggregate(status.intervals, counters[0] / 1000.0);
        status.learned = EnergyModel.learned(status.allTime);
        status.allIdle = IdleLedger.summarize(store.idle(0), now);
        status.kmPerPoint = EnergyModel.kmPerPoint(status.profile, status.allTime);
        for (EnergyModel.Interval interval : status.intervals) {
            EnergyModel.Parked parked = EnergyModel.parked(interval);
            if (parked != null) status.parked.add(parked);
        }
        status.drain = EnergyModel.drain(status.parked);
        status.distances = EnergyModel.distances(status.intervals);
        status.homeRate = store.homeRate();
        status.currency = store.currency();
        status.charges = store.charges();
        status.charging = EnergyModel.charging(status.charges, status.homeRate);

        if (status.session != null) {
            EnergyStore.Session session = status.session;
            // Active: live counters. Ended: its final counters. Awaiting recovery: the last heartbeat's.
            status.totals = session.totals(status.active || status.recovery != null ? counters : session.base);
            List<EnergyStore.CheckIn> own = store.sessionCheckIns(session.id);
            EnergyStore.CheckIn firstOdometer = null, lastOdometer = null;
            for (EnergyStore.CheckIn reading : own) {
                if (status.startReading == null) status.startReading = reading;
                if (!Double.isNaN(reading.percent)) status.lastReading = reading;
                if ("end".equals(reading.kind)) status.endReading = reading;
                if (!Double.isNaN(reading.odometerKm)) {
                    if (firstOdometer == null) firstOdometer = reading;
                    lastOdometer = reading;
                }
            }
            if (firstOdometer != null && lastOdometer != firstOdometer) {
                status.driveOdometerKm = EnergyModel.odometerKm(firstOdometer.odometerKm, lastOdometer.odometerKm);
                status.driveOdometerGpsKm = Math.max(0, lastOdometer.counters[0] - firstOdometer.counters[0]) / 1000.0;
            }
            status.needsStartPercent = status.startReading != null && Double.isNaN(status.startReading.percent);
            // This journey's learnable intervals, summed like the all-time figures.
            double km = 0, drop = 0;
            for (EnergyModel.Interval interval : status.intervals) {
                if (!belongsTo(interval, own)) continue;
                if (interval.learnable() || interval.exclusion == EnergyModel.Exclusion.BEFORE_LEARNING_RESET) {
                    km += interval.km;
                    drop += interval.drop;
                } else if (!status.driveExclusions.contains(interval.exclusion)) {
                    status.driveExclusions.add(interval.exclusion);
                }
            }
            if (drop > 0) status.drive = EnergyModel.evaluate(km, drop, status.profile);
            status.driveIdle = IdleLedger.summarize(store.idle(session.id), now);
        }
        // The last entered reading anywhere (a parked check-in or the previous journey's end).
        EnergyStore.CheckIn latest = null;
        for (EnergyStore.CheckIn reading : readings) if (!Double.isNaN(reading.percent)) latest = reading;
        if (status.lastReading == null && latest != null && !status.active) status.lastReading = latest;
        if (status.lastReading != null) {
            status.kmSinceReading = Math.max(0, counters[0] - status.lastReading.counters[0]) / 1000.0;
            status.estimatedPercent = EnergyModel.projectedPercent(status.lastReading.percent, status.kmSinceReading, status.profile, status.allTime);
            status.reserveRangeKm = EnergyModel.rangeToReserveKm(status.lastReading.percent, status.profile, status.allTime);
            status.rangeKm = EnergyModel.rangeToReserveKm(status.estimatedPercent, status.profile, status.allTime);
        }
        status.gps = motion != null && runningSession > 0 ? motion.state() : GpsMotion.State.UNKNOWN;
        status.ready = ledger != null && ledger.ready();

        // Prompts. Home shows them in the battery card; nothing is modal.
        EnergyStore.Session last = store.lastSession();
        status.canContinue = !status.active && status.recovery == null && last != null
                && "ended".equals(last.state) && now - last.ended < CONTINUE_WINDOW_MS && !endedWithReading(last);
        String mode = store.promptMode();
        boolean moving = lastKph >= 6 && SystemClock.elapsedRealtime() - lastSpeedAt < 10_000;
        status.askStart = !status.active && status.recovery == null && !notDrivingThisBoot && !moving
                && SystemClock.elapsedRealtime() >= quietUntil
                && ("parked".equals(mode) || ("after_off".equals(mode) && offLongEnough()));
        status.askEnd = status.active && !"never".equals(mode) && parkedSince > 0
                && SystemClock.elapsedRealtime() - parkedSince >= PARKED_PROMPT_MS;
        return status;
    }

    /** Both ends in this journey: the parked gap before it isn't part of the drive. */
    private static boolean belongsTo(EnergyModel.Interval interval, List<EnergyStore.CheckIn> own) {
        boolean from = false, to = false;
        for (EnergyStore.CheckIn reading : own) {
            if (reading.id == interval.from.id) from = true;
            if (reading.id == interval.to.id) to = true;
        }
        return from && to;
    }

    private boolean endedWithReading(EnergyStore.Session session) {
        for (EnergyStore.CheckIn reading : store.sessionCheckIns(session.id)) {
            if ("end".equals(reading.kind) && !Double.isNaN(reading.percent)) return true;
        }
        return false;
    }

    /** A journey still marked active from an earlier boot (or not heard from in this one). */
    private boolean needsRecovery(EnergyStore.Session session) {
        if (session.id == runningSession) return false;
        return !boot.equals(session.boot);
    }

    private boolean offLongEnough() {
        if (offLongEnough == null) {
            long lastAlive = store.metaLong("last_alive", 0);
            offLongEnough = lastAlive == 0 || bootWall - lastAlive >= 30 * 60_000L;
        }
        return offLongEnough;
    }

    /** Called about once a minute while the box is on, to tell later how long it was off. */
    void alive() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.alive()");return;} store.setMeta("last_alive", Long.toString(System.currentTimeMillis())); }

    // ---- Driver actions ----

    /** Start a new journey with the battery % now and whether the car was charged since the last reading. */
    long startDrive(double percent, EnergyModel.Charged charged) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.startDrive(double,EnergyModel.Charged)",new Object[]{percent,charged});} return startDrive(percent, charged, null); }

    /** As above, with whatever the driver typed from the car's displays (the odometer). Returns the check-in. */
    long startDrive(double percent, EnergyModel.Charged charged, EnergyModel.CarFigures figures) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.startDrive(double,EnergyModel.Charged,EnergyModel.CarFigures)",new Object[]{percent,charged,figures});}
        endRunning(System.currentTimeMillis(), "ended");
        long now = System.currentTimeMillis();
        double[] counters = store.counters();
        long session = store.createSession("user", now, boot, counters);
        long checkIn = store.addCheckIn(now, percent, "start", charged, session, counters, store.profile(), figures);
        begin(session, false);
        return checkIn;
    }

    /** Continue the previous journey (ended within 6 h without a reading), optionally with a reading now. */
    long continueDrive(double percent, EnergyModel.Charged charged) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.continueDrive(double,EnergyModel.Charged)",new Object[]{percent,charged});} return continueDrive(percent, charged, null); }

    /** Returns the new check-in, or 0 when no % was given (the odometer is kept only with a %). */
    long continueDrive(double percent, EnergyModel.Charged charged, EnergyModel.CarFigures figures) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.continueDrive(double,EnergyModel.Charged,EnergyModel.CarFigures)",new Object[]{percent,charged,figures});}
        EnergyStore.Session last = store.lastSession();
        if (last == null || last.active()) return 0;
        long now = System.currentTimeMillis();
        // The counters carry on from now; the gap while parked is not journey time.
        double[] counters = store.counters();
        // Its "I don't know" ending wasn't an ending after all: withdraw it so the drive stays one interval.
        store.withdrawUnknownEnd(last.id);
        store.setSessionState(last.id, "active", 0, null);
        store.heartbeat(last.id, now, boot);
        long checkIn = Double.isNaN(percent) ? 0
                : store.addCheckIn(now, percent, "parked", charged, last.id, counters, store.profile(), figures);
        begin(last.id, false);
        return checkIn;
    }

    /** Bench use: don't ask again until the next boot. A drive is still noticed from GPS. */
    void notDriving() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.notDriving()");return;}
        notDrivingThisBoot = true;
        changed();
    }

    /** End the journey with the battery % now (NaN for "I don't know"). */
    long endDrive(double percent, EnergyModel.Charged charged) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.endDrive(double,EnergyModel.Charged)",new Object[]{percent,charged});} return endDrive(percent, charged, null); }

    /** As above, with the odometer and the car's trip computer when typed in. Returns the check-in. */
    long endDrive(double percent, EnergyModel.Charged charged, EnergyModel.CarFigures figures) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.endDrive(double,EnergyModel.Charged,EnergyModel.CarFigures)",new Object[]{percent,charged,figures});}
        EnergyStore.Session session = store.activeSession();
        if (session == null) return 0;
        long now = System.currentTimeMillis();
        double[] counters = runningSession == session.id && motion != null ? countersOf(motion) : store.counters();
        store.saveCounters(counters);
        long checkIn = store.addCheckIn(now, percent, "end", charged, session.id, counters, store.profile(), figures);
        endRunning(now, "ended");
        store.setSessionState(session.id, "ended", now, counters);
        quietUntil = SystemClock.elapsedRealtime() + 2 * 60_000L; // just parked: not straight away
        changed();
        return checkIn;
    }

    /** Fill in a missing starting % for the current or last journey, as a correction record. */
    void setStartPercent(double percent) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.setStartPercent(double)",new Object[]{percent});return;}
        Status status = status();
        if (status.startReading == null) return;
        store.correctCheckIn(status.startReading.id, percent, status.startReading.charged, store.profile());
        changed();
    }

    // Power-cut recovery.

    /** End a journey cut off by a power loss, at its last heartbeat, with the % now (or NaN). */
    long recoverEnd(double percent, EnergyModel.Charged charged) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.recoverEnd(double,EnergyModel.Charged)",new Object[]{percent,charged});} return recoverEnd(percent, charged, null); }

    long recoverEnd(double percent, EnergyModel.Charged charged, EnergyModel.CarFigures figures) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.recoverEnd(double,EnergyModel.Charged,EnergyModel.CarFigures)",new Object[]{percent,charged,figures});}
        EnergyStore.Session session = store.activeSession();
        if (session == null) return 0;
        double[] counters = store.counters();
        store.closeOpenIdle(session.id, session.heartbeat);
        long checkIn = store.addCheckIn(session.heartbeat, percent, "end", charged, session.id, counters, store.profile(), figures);
        store.setSessionState(session.id, "ended", session.heartbeat, counters);
        quietUntil = SystemClock.elapsedRealtime() + 2 * 60_000L;
        changed();
        return checkIn;
    }

    // Charge log: every field optional, nothing assumed.

    /** Logs a charge (time now unless given). An entry with nothing in it isn't saved. */
    long logCharge(EnergyModel.Charge charge) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.logCharge(EnergyModel.Charge)",new Object[]{charge});}
        if (charge.empty()) return 0;
        if (charge.time <= 0) charge.time = System.currentTimeMillis();
        long id = store.addCharge(charge);
        changed();
        return id;
    }

    void withdrawCharge(long id) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.withdrawCharge(long)",new Object[]{id});return;}
        store.withdrawCharge(id);
        changed();
    }

    // Fixing mistakes. Nothing is deleted: an edit is a new record that supersedes the old one, and a
    // removed entry is only hidden from the figures and can be restored.

    /** Corrects a check-in's %, its "charged since?" answer and what was typed from the car's displays. */
    void correctReading(long id, double percent, EnergyModel.Charged charged, EnergyModel.CarFigures figures) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.correctReading(long,double,EnergyModel.Charged,EnergyModel.CarFigures)",new Object[]{id,percent,charged,figures});return;}
        store.correctCheckIn(id, percent, charged, store.profile(), figures);
        changed();
    }

    void removeReading(long id) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.removeReading(long)",new Object[]{id});return;}
        store.removeCheckIn(id);
        changed();
    }

    void restoreReading(long id) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.restoreReading(long)",new Object[]{id});return;}
        store.restoreCheckIn(id);
        changed();
    }

    /** Replaces a charge entry with an edited copy; the original stays in the record. */
    long editCharge(long oldId, EnergyModel.Charge edited) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.editCharge(long,EnergyModel.Charge)",new Object[]{oldId,edited});}
        if (edited.empty()) return 0;
        long id = store.editCharge(oldId, edited);
        changed();
        return id;
    }

    void restoreCharge(long id) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.restoreCharge(long)",new Object[]{id});return;}
        store.restoreCharge(id);
        changed();
    }

    /** Only on the driver's explicit choice: the estimate from charging becomes the usable capacity. */
    void acceptCapacity(double kwh) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.acceptCapacity(double)",new Object[]{kwh});return;}
        if (!EnergyModel.usable(kwh)) return;
        EnergyModel.Profile profile = store.profile();
        profile.usableKwh = Math.round(kwh * 10) / 10.0;
        saveProfile(profile);
    }

    void setHomeRate(double rate) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.setHomeRate(double)",new Object[]{rate});return;}
        store.setHomeRate(rate >= 0 ? rate : Double.NaN);
        changed();
    }

    void setCurrency(String code) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.setCurrency(String)",new Object[]{code});return;}
        store.setCurrency(code == null || code.trim().isEmpty() ? "LKR" : code.trim());
        changed();
    }

    void recoverContinue() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.recoverContinue()");return;}
        EnergyStore.Session session = store.activeSession();
        if (session == null) return;
        store.closeOpenIdle(session.id, session.heartbeat);
        store.heartbeat(session.id, System.currentTimeMillis(), boot);
        begin(session.id, false);
    }

    void recoverTripOnly() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.recoverTripOnly()");return;}
        EnergyStore.Session session = store.activeSession();
        if (session == null) return;
        store.closeOpenIdle(session.id, session.heartbeat);
        store.excludeSession(session.id);
        store.setSessionState(session.id, "trip_only", session.heartbeat, store.counters());
        changed();
    }

    void setReady(boolean on) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.setReady(boolean)",new Object[]{on});return;}
        if (ledger == null) return;
        ledger.setReady(on, System.currentTimeMillis());
        changed();
    }

    void setIdleProfile(EnergyModel.IdleProfile profile) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.setIdleProfile(EnergyModel.IdleProfile)",new Object[]{profile});return;}
        EnergyModel.Profile saved = store.profile();
        saved.idle = profile;
        store.saveProfile(saved);
        if (ledger != null) ledger.setProfile(profile, System.currentTimeMillis());
        changed();
    }

    void saveProfile(EnergyModel.Profile profile) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.saveProfile(EnergyModel.Profile)",new Object[]{profile});return;}
        EnergyModel.IdleProfile before = store.profile().idle;
        store.saveProfile(profile);
        if (ledger != null && profile.idle != before) ledger.setProfile(profile.idle, System.currentTimeMillis());
        changed();
    }

    // Resets: each separate, each isolated.

    /** The active journey's energy data is abandoned and excluded from learning; raw rows stay. */
    void resetCurrentDrive() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.resetCurrentDrive()");return;}
        EnergyStore.Session session = store.activeSession();
        if (session == null) return;
        long now = System.currentTimeMillis();
        double[] counters = runningSession == session.id && motion != null ? countersOf(motion) : store.counters();
        store.saveCounters(counters);
        endRunning(now, "abandoned");
        store.excludeSession(session.id);
        store.setSessionState(session.id, "abandoned", now, counters);
        changed();
    }

    /** Learned km per point and verdict history start again from now; raw rows stay. */
    void resetLearning() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.resetLearning()");return;}
        store.setMeta("learning_since", Long.toString(System.currentTimeMillis()));
        changed();
    }

    /** Flush runtime state before a verified archive transaction. */
    void prepareDataChange() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.prepareDataChange()");return;}
        endRunning(System.currentTimeMillis(),null);
        fastSince=-1;notDrivingThisBoot=false;quietUntil=0;lastKph=-1;lastSpeedAt=0;
    }

    /** Tests only. User resets go through EnergyArchive with verified backups. */
    void deleteHistory() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.deleteHistory()");return;}
        endRunning(System.currentTimeMillis(), null);
        store.deleteHistory();
        changed();
    }

    // ---- GPS ----

    /** Every speed reading (from Home or the journey service): starts a journey on sustained driving. */
    void observeSpeed(int kph, boolean reliable) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.observeSpeed(int,boolean)",new Object[]{kph,reliable});return;}
        if(EnergyDataActivity.BUSY.get())return;
        if(EnergyClient.link(context).expectsCarBattery()){fastSince=-1;return;}
        long now = SystemClock.elapsedRealtime();
        if (reliable) { lastKph = kph; lastSpeedAt = now; }
        if (runningSession > 0 || !reliable) { fastSince = -1; return; }
        if (kph < AUTO_START_KPH) { fastSince = -1; return; }
        if (fastSince < 0) { fastSince = now; return; }
        if (now - fastSince < AUTO_START_MS) return;
        fastSince = -1;
        EnergyStore.Session active = store.activeSession();
        if (active != null) {
            // Same boot (the service stopped): carry on. Earlier boot: the driver decides first.
            if (!needsRecovery(active)) resume(active);
            return;
        }
        long wall = System.currentTimeMillis();
        double[] counters = store.counters();
        long session = store.createSession("auto", wall, boot, counters);
        store.addCheckIn(wall, Double.NaN, "start", EnergyModel.Charged.UNKNOWN, session, counters, store.profile());
        begin(session, true);
    }

    /** Home resumed: a journey of this boot whose service stopped carries on (the gap counts as unknown GPS). */
    void ensureRunning() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.ensureRunning()");return;}
        if(EnergyDataActivity.BUSY.get())return;
        EnergyStore.Session active = store.activeSession();
        if (active == null || runningSession == active.id || needsRecovery(active)) return;
        resume(active);
    }

    private void resume(EnergyStore.Session active) {
        long now = System.currentTimeMillis();
        double[] counters = store.counters();
        double gap = Math.max(0, (now - active.heartbeat) / 1000.0);
        counters[3] += gap; // unknown
        counters[4] += gap; // active
        store.saveCounters(counters);
        store.closeOpenIdle(active.id, active.heartbeat);
        store.heartbeat(active.id, now, boot);
        begin(active.id, false);
    }

    private void begin(long session, boolean automatic) {
        runningSession = session;
        double[] c = store.counters();
        motion = new GpsMotion(this);
        motion.restore(c[0], c[1], c[2], c[3], c[4], c[5]);
        ledger = new IdleLedger(this, session, store.profile().idle);
        ledger.resume(store.openIdlePeriod(session));
        lastOdometerKm = Double.NaN;
        lastHeartbeat = SystemClock.elapsedRealtime();
        parkedSince = 0;
        if (serviceEnabled) try {
            Intent intent = new Intent(context, JourneyService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException notAllowed) {
            // Started from the background (rare): Home resumes the service when it is next shown.
        }
        changed();
    }

    private void endRunning(long now, String state) {
        if (runningSession > 0) {
            if (ledger != null) ledger.stop(now);
            if (motion != null) store.saveCounters(countersOf(motion));
        }
        runningSession = 0;
        motion = null;
        ledger = null;
        parkedSince = 0;
        context.stopService(new Intent(context, JourneyService.class));
    }

    // ---- Runtime, driven by JourneyService ----

    long runningSession() {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"journal.runningSession()");} return runningSession; }
    void serviceRunning(boolean running) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.serviceRunning(boolean)",new Object[]{running});return;} serviceRunning = running; }
    boolean serviceRunning() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"journal.serviceRunning()");} return serviceRunning; }

    void position(Location location, float speedKph) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.position(Location,float)",new Object[]{location,speedKph});return;}
        if (motion == null) return;
        double odometer = TripComputer.get(context).odometerKm();
        double accepted = Double.isNaN(lastOdometerKm) ? 0 : Math.max(0, odometer - lastOdometerKm) * 1000;
        lastOdometerKm = odometer;
        motion.fix(location.getElapsedRealtimeNanos() / 1_000_000L, System.currentTimeMillis(),
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN, speedKph,
                location.getLatitude(), location.getLongitude(), accepted);
    }

    void tick() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.tick()");return;}
        if (motion == null) return;
        long elapsed = SystemClock.elapsedRealtime();
        motion.tick(elapsed, System.currentTimeMillis());
        GpsMotion.State state = motion.state();
        if (state == GpsMotion.State.MOVING) parkedSince = 0;
        else if (parkedSince == 0) parkedSince = elapsed;
        if (elapsed - lastHeartbeat >= HEARTBEAT_MS) heartbeatNow();
    }

    /** Saves the counters and marks the journey alive, so a power cut loses at most 30 s. */
    void heartbeatNow() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"journal.heartbeatNow()");return;}
        if (motion == null) return;
        lastHeartbeat = SystemClock.elapsedRealtime();
        store.saveCounters(countersOf(motion));
        store.heartbeat(runningSession, System.currentTimeMillis(), boot);
        changed();
    }

    /** Tests only: forget this instance, as a process death would. */
    static synchronized void forgetForTests() {if(instance!=null&&!EnergyClient.owner(instance.context))EnergyClient.call(instance.context,"journal.forgetForTests");instance = null; }

    private static double[] countersOf(GpsMotion motion) {
        return new double[]{motion.meters, motion.movingSeconds, motion.standstillSeconds,
                motion.unknownSeconds, motion.activeSeconds, motion.poorMeters};
    }

    // GpsMotion.Listener

    @Override public void standstillStarted(long since) { if (ledger != null) ledger.standstillStarted(since); }
    @Override public void standstillEnded(long at) { if (ledger != null) ledger.standstillEnded(at); }

    // IdleLedger.Sink

    @Override public void opened(IdleLedger.Period period) { store.openIdle(period); }
    @Override public void closed(IdleLedger.Period period) { store.closeIdle(period); }
}
