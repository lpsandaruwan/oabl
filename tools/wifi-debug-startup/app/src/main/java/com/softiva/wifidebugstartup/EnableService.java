package com.softiva.wifidebugstartup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** Keeps the opted-in wireless-debugging setting available across boot and Wi-Fi reconnects. */
public final class EnableService extends Service {
    private static final String CHANNEL = "wifi-debug-startup";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivity;
    private boolean watchingNetwork, watchingSetting;
    private String lastStatus;
    private long lastWrite;
    private long healthySince;
    private int attempts;

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("startup", 0); }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }

    static void start(Context context) {
        if (enabled(context)) context.startForegroundService(new Intent(context, EnableService.class));
    }

    private void status(String text) {
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        prefs(this).edit().putString("last_status", text).putLong("last_time", System.currentTimeMillis()).apply();
        Log.i("WifiDebugStartup", text);
        getSystemService(NotificationManager.class).notify(1, notification(text));
    }

    private void recheck() { handler.removeCallbacks(check); handler.post(check); }
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network value) { attempts = 0; recheck(); }
        @Override public void onLost(Network value) { attempts = 0; recheck(); }
        @Override public void onLinkPropertiesChanged(Network value, LinkProperties properties) { recheck(); }
        @Override public void onCapabilitiesChanged(Network value, NetworkCapabilities capabilities) { recheck(); }
    };
    private final ContentObserver setting = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { recheck(); }
    };

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!enabled(EnableService.this)) { status("Startup disabled"); stopSelf(); return; }
            if (!wifiConnected()) {
                attempts = 0;
                healthySince = 0;
                status("Waiting for Wi-Fi · no startup timeout");
                handler.postDelayed(this, 30_000);
                return;
            }
            try {
                if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1) {
                    if (healthySince == 0) healthySince = SystemClock.elapsedRealtime();
                    if (SystemClock.elapsedRealtime() - healthySince >= 10_000) attempts = 0;
                    status("Wireless debugging enabled · watching for Wi-Fi reconnects");
                    handler.postDelayed(this, 30_000);
                    return;
                }
                healthySince = 0;
                // Do not repeatedly reopen Android's network-approval dialog if the framework
                // refuses the setting. No network/key approval or authentication policy is changed.
                long remaining = 5_000 - (SystemClock.elapsedRealtime() - lastWrite);
                if (remaining > 0) { handler.postDelayed(this, remaining); return; }
                if (attempts >= 4) {
                    status("Android refused wireless debugging · approve this Wi-Fi network in Developer options, then Run now");
                    handler.postDelayed(this, 30_000); return;
                }
                attempts++;
                lastWrite = SystemClock.elapsedRealtime();
                boolean written = Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                status(written ? "Wi-Fi connected; enabling wireless debugging" : "Could not update wireless debugging setting");
            } catch (SecurityException denied) {
                status("Missing WRITE_SECURE_SETTINGS permission"); stopSelf(); return;
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Wireless debugging startup", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification("Waiting for Wi-Fi"));
        prefs(this).edit().putInt("last_boot_count", Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1))
                .putLong("service_started_elapsed", SystemClock.elapsedRealtime()).apply();
        connectivity = getSystemService(ConnectivityManager.class);
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network, handler);
            watchingNetwork = true;
        } catch (RuntimeException unavailable) { Log.w("WifiDebugStartup", "Using periodic Wi-Fi checks", unavailable); }
        getContentResolver().registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, setting);
        watchingSetting = true;
        status("Watching Wi-Fi for wireless debugging");
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Wireless debugging startup").setContentText(text)
                .setContentIntent(open).setOngoing(true).build();
    }

    private boolean wifiConnected() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && properties != null && !properties.getLinkAddresses().isEmpty()) return true;
        }
        return false;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        attempts = 0; recheck(); return START_STICKY;
    }
    @Override public void onDestroy() {
        if (watchingNetwork) try { connectivity.unregisterNetworkCallback(network); }
            catch (RuntimeException unavailable) { Log.w("WifiDebugStartup", "Network watcher already removed", unavailable); }
        if (watchingSetting) getContentResolver().unregisterContentObserver(setting);
        handler.removeCallbacks(check); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
