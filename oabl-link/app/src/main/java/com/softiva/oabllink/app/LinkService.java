package com.softiva.oabllink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import com.softiva.oabllink.CarProbe;
import com.softiva.oabllink.DongfengProbe;
import com.softiva.oabllink.LinkServer;
import com.softiva.oabllink.Pairing;
import com.softiva.oabllink.Reading;
import com.softiva.oabllink.SampleStore;
import com.softiva.oabllink.BleLinkRouter;
import com.softiva.oabllink.bluetooth.BleGattServer;

/**
 * The foreground service that distributes the car's data to the box (energy-companion.md §3.1, §3.8).
 * It reads the car once a second, records what changed into the SQLite store and serves the store
 * over Bluetooth only. On the head unit it reads the real car; only a recognized emulator uses the
 * simulated car. A pending or failed car-service connection on physical hardware leaves readings
 * unavailable instead of inventing data.
 */
public final class LinkService extends Service {
    private static final String TAG = "OablLink";
    private static final String PREFS = "oabllink";
    private static final String CHANNEL = "oabl-link";
    // Open Headunit uses 1 for projection. A shared id would replace either service's notification.
    private static final int NOTIFICATION_ID = 8731;
    private static final long SAMPLE_MS = 1000;

    /** Whether the link service is up, for the setup screen. */
    public static volatile boolean ACTIVE;
    public static volatile String STATUS = "Stopped";
    public static volatile String BLUETOOTH_STATUS = "Bluetooth stopped";
    private SampleStore store;
    private CarProbe probe;
    private BleGattServer bluetooth;
    private LinkServer router;
    private volatile boolean running;
    private HandlerThread sampleThread;
    private Handler sampleHandler;
    static long bootId(Context context){
        return android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,0);
    }

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long began = android.os.SystemClock.elapsedRealtime();
            Reading reading = probe.read(System.currentTimeMillis());
            reading.set(com.softiva.oabllink.Signal.HU_BOOT_ID,bootId(LinkService.this));
            store.record(reading);
            // Target start-to-start sampling, counting property/storage work in the interval.
            // A slow read may run late, but it never overlaps a second sampler or replays ticks.
            if (running) sampleHandler.postDelayed(this,
                    Math.max(0, SAMPLE_MS - (android.os.SystemClock.elapsedRealtime() - began)));
        }
    };

    /** The installed APK's own version, so /v1/hello never reports a stale hard-coded build. */
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return "unknown"; }
    }

    /** A stable id for this install of OABL Link, generated once; it changes only on reinstall or a
     *  data wipe, so the box can tell a fresh seq-from-1 head unit from a continuing one. */
    public static String installId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        String id = prefs.getString("install_id", null);
        if (id == null) {
            id = Long.toHexString(new java.security.SecureRandom().nextLong() & 0xffffffffffffffL);
            prefs.edit().putString("install_id", id).apply();
        }
        return id;
    }

    /** The current pairing code, created and kept once per install, shown to pair the box. */
    public static String pairCode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        String code = prefs.getString("pair_code", null);
        if (code == null) {
            code = new Pairing().newCode();
            prefs.edit().putString("pair_code", code).apply();
        }
        return code;
    }

    @Override public void onCreate() {
        super.onCreate();
        STATUS = "Starting";
        startForeground(NOTIFICATION_ID, notification());
        store = new SqliteSampleStore(this);
        probe = chooseProbe();
        LinkServer server = new LinkServer(probe, store, appVersion(), Build.MODEL, Build.VERSION.RELEASE, installId(this));
        router = server;
        server.setKey(Pairing.sharedKey(pairCode(this)));
        running = true;
        ACTIVE = true;
        STATUS = "Running";
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
        sampleThread = new HandlerThread("oabl-car-samples");
        sampleThread.start();
        sampleHandler = new Handler(sampleThread.getLooper());
        sampleHandler.post(sample);
        refreshBluetooth();
        Log.i(TAG, "link service up · probe " + probe.name());
    }

    /** Binding is asynchronous: retain the real source while its connection is pending. */
    private CarProbe chooseProbe() {
        if ("goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE)) {
            Log.i(TAG, "Android emulator: using the simulated car");
            return new com.softiva.oabllink.SimulatedCar(System.currentTimeMillis());
        }
        // Bench testing on a tablet: a file named simulate_car in this app's external files folder
        // (adb push, no root) makes this link serve the scripted car instead of reading a real one.
        // Absent by default; it never touches a car.
        java.io.File marker = new java.io.File(getExternalFilesDir(null), "simulate_car");
        if (marker.exists()) {
            Log.i(TAG, "Bench test: using the simulated car");
            return new com.softiva.oabllink.SimulatedCar(System.currentTimeMillis());
        }
        return new HeadUnitSignalProbe(this, new DongfengProbe(new CarPropertySource(this)));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "retry_bluetooth".equals(intent.getAction()) && bluetooth != null) bluetooth.retry();
        refreshBluetooth();
        return START_STICKY;
    }

    public static boolean bluetoothEnabled(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean("bluetooth_enabled", true);
    }

    private void refreshBluetooth() {
        if (!running) return;
        if (!bluetoothEnabled(this)) {
            if (bluetooth != null) bluetooth.close();
            bluetooth = null; BLUETOOTH_STATUS = "Bluetooth off"; return;
        }
        if (bluetooth != null) return;
        BleLinkRouter reads = new BleLinkRouter(router);
        bluetooth = new BleGattServer(this, Pairing.sharedKey(pairCode(this)), reads::handle,
                status -> BLUETOOTH_STATUS = status);
        bluetooth.start();
    }

    @Override public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        ACTIVE = false;
        if (bluetooth != null) { bluetooth.close(); bluetooth = null; }
        BLUETOOTH_STATUS = "Bluetooth stopped";
        if (wasRunning) STATUS = "Stopped";
        if (sampleHandler != null) sampleHandler.removeCallbacks(sample);
        if (store instanceof SqliteSampleStore) {
            if (sampleHandler != null) sampleHandler.post(() -> ((SqliteSampleStore) store).close());
            else ((SqliteSampleStore) store).close();
        }
        if (sampleThread != null) sampleThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public boolean isRunning() { return running; }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "OABL Link", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Serves read-only car data over Bluetooth or local Wi-Fi.");
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder
                .setContentTitle("OABL Link")
                .setContentText("Pair code " + pairCode(this) + " · " + STATUS)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, ProbeActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }
}
