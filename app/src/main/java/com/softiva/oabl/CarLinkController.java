package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the box's live link to the car (energy-companion.md §3.1, §3.8): the data-source setting, the
 * pairing, the {@link CarLinkService}, and the latest live snapshot the Home card and Energy page
 * read. A single instance for the app. It starts the service only when the setting allows and a code
 * is paired, and it never overwrites a driver's entered reading — the live values sit alongside.
 *
 * <p>Modes (Settings › Vehicle and energy › Data source):
 * <ul>
 *   <li><b>auto</b> — live with a paired car, manual when unpaired (the default);</li>
 *   <li><b>manual</b> — never use the car, even when it is reachable;</li>
 *   <li><b>car</b> — only the car.</li>
 * </ul>
 */
final class CarLinkController {
    static final String PREFS = "car_link";
    static final String MODE_AUTO = "auto", MODE_MANUAL = "manual", MODE_CAR = "car";
    /** A live value counts as fresh for this long; after it, live mode shows a waiting state. */
    static final long FRESH_MS = 60_000;

    private static CarLinkController instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CarLinkService service;
    private final List<Runnable> observers = new ArrayList<>();
    private volatile String state = "off";
    private boolean started;

    static synchronized CarLinkController get(Context context) {
        if (instance == null) instance = new CarLinkController(context.getApplicationContext());
        return instance;
    }

    private CarLinkController(Context context) {
        this.context = context;
        if(!EnergyClient.owner(context)){this.service=null;EnergyClient.observe(context,this::changed);return;}
        this.service = new CarLinkService(context);
        this.service.setSink(new EnergyCarSink(context));
        this.service.setListener(new CarLinkService.Listener() {
            @Override public void onState(String s) { state = s; changed(); }
            @Override public void onLive(CarLink link) { changed(); }
        });
        String code = prefs().getString("pair_code", null);
        if (code != null) service.pair(code);
        // Wi-Fi was removed: settings saved by older versions no longer mean anything.
        prefs().edit().remove("address").remove("transport").apply();
    }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, 0); }

    // ---- Settings ----

    String mode() {
        if(!EnergyClient.owner(context)){return (String)EnergyClient.call(context,"link.mode()");} return prefs().getString("mode", MODE_AUTO); }

    void setMode(String mode) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.setMode(String)",new Object[]{mode});return;}
        prefs().edit().putString("mode", mode).apply();
        apply();
        changed();
    }

    boolean paired() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.paired()");} return service.paired(); }

    /** Whether the launcher accent should follow the car's drive mode (energy-companion.md §3.9). */
    boolean matchDriveMode() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.matchDriveMode()");} return prefs().getBoolean("match_drive_mode", false); }

    void setMatchDriveMode(boolean on) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.setMatchDriveMode(boolean)",new Object[]{on});return;}
        prefs().edit().putBoolean("match_drive_mode", on).apply();
        changed();
    }

    /** The regen research log: off by default, and it turns itself off after about two hours of driving. */

    /** Pairs with the 6-digit code shown on the head unit and remembers it. */
    void pair(String code) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.pair(String)",new Object[]{code});return;}
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("Enter all 6 digits");
        stop();
        prefs().edit().putString("pair_code", code).remove("bluetooth_address").apply();
        service.pair(code);
        apply();
        changed();
    }

    void retry() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.retry()");return;}
        stop();
        apply();
        changed();
    }

    void forget() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.forget()");return;}
        prefs().edit().remove("pair_code").remove("bluetooth_address").apply();
        stop();
        service.forget();
        changed();
        changed();
    }

    /** Starts or stops the service to match the current mode and pairing. Call from Home's onResume. */
    void apply() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.apply()");return;}
        boolean wanted = !MODE_MANUAL.equals(mode()) && service.paired();
        if (wanted) start(); else stop();
    }

    boolean collectionWanted(){return EnergyCollectorService.wanted(context);}
    void stopWorkerOnly(){if(started){started=false;service.stop();state="off";}}

    private void start() {
        EnergyCollectorService.ensure(context);
    }

    void startWorkerOnly() {
        if (started) return;
        started = true;
        service.start();
    }

    void stop() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"link.stop()");return;}
        context.stopService(new android.content.Intent(context, EnergyCollectorService.class));
        if (!started) return;
        started = false;
        service.stop();
        state = "off";
    }

    // ---- What Home and the Energy page read ----

    String state() {
        if(!EnergyClient.owner(context)){return (String)EnergyClient.call(context,"link.state()");} return state; }

    boolean usingCar() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.usingCar()");} return !MODE_MANUAL.equals(mode()); }

    /** A configured live source should show its connection state rather than request a check-in. */
    boolean expectsCarBattery() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.expectsCarBattery()");} return usingCar() && (MODE_CAR.equals(mode()) || paired()); }

    /** The freshest live battery %, or NaN when there is none or it is stale (older than {@link #FRESH_MS}). */
    double liveSoc() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"link.liveSoc()");} return fresh(CarSignal.SOC); }

    double liveRangeKm() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"link.liveRangeKm()");} return fresh(CarSignal.RANGE_EST); }
    double liveValue(CarSignal signal) {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"link.liveValue(CarSignal)",new Object[]{signal});} return fresh(signal); }

    /** Car speed only while it is current: older than {@link #SPEED_FRESH_MS} and Home switches to GPS. */
    static final long SPEED_FRESH_MS = 3_000;
    /** One measured value and its normalized source time, read atomically by the energy owner. */
    static final class SpeedReading {
        final double value;
        final long measuredAt;
        SpeedReading(double value, long measuredAt) { this.value = value; this.measuredAt = measuredAt; }
        double current(long now) { return freshSpeedAt(measuredAt, now) ? value : Double.NaN; }
    }
    static boolean freshSpeedAt(long measuredAt, long now) {
        return measuredAt > 0 && Math.abs(now - measuredAt) <= SPEED_FRESH_MS;
    }
    /**
     * The speed to show: the speedometer's own figure while it is fresh and at least as new as the
     * real speed (Link sends both together and withholds the figure it cannot trust), otherwise the
     * real speed. Owner-only; Home serializes the resulting value and time in its existing snapshot.
     */
    SpeedReading liveSpeedReading() {
        if(!EnergyClient.owner(context))throw new IllegalStateException("Car speed readings belong to the energy owner");
        CarLink readings = service.link();
        synchronized (readings) {
            if (!usingCar()) return new SpeedReading(Double.NaN, readings.latestTime(CarSignal.SPEED));
            return shownSpeed(readings.has(CarSignal.SPEED_DISPLAY) ? readings.latest(CarSignal.SPEED_DISPLAY) : Double.NaN,
                    readings.latestTime(CarSignal.SPEED_DISPLAY), readings.latest(CarSignal.SPEED),
                    readings.latestTime(CarSignal.SPEED), System.currentTimeMillis());
        }
    }
    static SpeedReading shownSpeed(double display, long displayAt, double real, long realAt, long now) {
        if (!Double.isNaN(display) && display >= 0 && freshSpeedAt(displayAt, now) && displayAt >= realAt
                && !lagsMovingCar(display, real))
            return new SpeedReading(display, displayAt);
        return new SpeedReading(real, realAt);
    }
    /**
     * A speedometer never reads below the real speed, so a figure well under a moving car's real
     * speed is stale (some clusters hold 0 at walking pace, before Link has learnt the unit). Showing
     * it hid the speed below about 15 km/h; the real speed is shown instead.
     */
    static boolean lagsMovingCar(double display, double real) {
        return !Double.isNaN(real) && real >= 2 && display < real * 0.9 - 1;
    }
    double liveSpeed() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"link.liveSpeed()");}
        return liveSpeedReading().current(System.currentTimeMillis());
    }

    boolean liveCharging() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.liveCharging()");} double speed=liveSpeed(), plug=fresh(CarSignal.PLUG);
        return fresh(CarSignal.CHARGING)==1 && plug!=0 && !(speed>1)
                && (speed>=0 || fresh(CarSignal.READY)==0 || plug==1); }

    /** Seconds since the freshest live value, or -1 when there is none. */
    long liveAgeSeconds(CarSignal signal) {
        if(!EnergyClient.owner(context)){return (long)EnergyClient.call(context,"link.liveAgeSeconds(CarSignal)",new Object[]{signal});}
        long time = service.link().latestTime(signal);
        return time == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - time) / 1000);
    }

    boolean hasFreshLive() {
        if(!EnergyClient.owner(context)){return (boolean)EnergyClient.call(context,"link.hasFreshLive()");} return usingCar() && !Double.isNaN(fresh(CarSignal.SOC)); }
    String currentSource(){
        if(!EnergyClient.owner(context))return (String)EnergyClient.call(context,"link.currentSource");
        return service.currentSource();
    }
    boolean connected(){
        if(!EnergyClient.owner(context))return (boolean)EnergyClient.call(context,"link.connected");
        return usingCar()&&service.connected();
    }

    /** The freshest live drive mode, or null when there is none, it is stale, or the code is unknown. */
    DriveMode liveDriveMode() {
        if(!EnergyClient.owner(context)){return (DriveMode)EnergyClient.call(context,"link.liveDriveMode()");}
        double code = fresh(CarSignal.DRIVE_MODE);
        return Double.isNaN(code) ? null : DriveMode.byCode((int) Math.round(code));
    }

    private double fresh(CarSignal signal) {
        if (!usingCar() || !service.link().has(signal) || stale(signal)) return Double.NaN;
        return service.link().latest(signal);
    }

    private boolean stale(CarSignal signal) {
        long time = service.link().latestTime(signal);
        return time == 0 || System.currentTimeMillis() - time > FRESH_MS;
    }

    // ---- Observers ----

    void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    private void changed() {
        // One cross-process notification per change; observers here then run on the UI thread.
        if(EnergyClient.owner(context))EnergyClient.changed(context);
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::notifyObservers); return; }
        notifyObservers();
    }
    private void notifyObservers() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }
}
