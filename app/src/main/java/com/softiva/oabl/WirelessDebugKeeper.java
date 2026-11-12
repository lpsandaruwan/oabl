package com.softiva.oabl;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

/** Opt-in recovery from Home startup; does not depend on the ordered boot broadcast. */
final class WirelessDebugKeeper {
    private static WirelessDebugKeeper instance;
    private static final String SETTING = "adb_wifi_enabled";
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivity;
    private boolean watchingNetwork, watchingSetting;
    private long lastWrite, healthySince;
    private int attempts;
    private String lastStatus;

    static WirelessDebugKeeper get(Context context) {
        if (instance == null) instance = new WirelessDebugKeeper(context.getApplicationContext());
        return instance;
    }

    private WirelessDebugKeeper(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("wireless_debug", Context.MODE_PRIVATE);
        connectivity = context.getSystemService(ConnectivityManager.class);
    }

    boolean enabled() { return prefs.getBoolean("enabled", false); }
    boolean permitted() {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    String status() { return prefs.getString("status", "Off"); }
    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
        if (enabled) start(); else stop();
    }

    void start() {
        if (!enabled()) return;
        UserManager users = context.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) return;
        if (!permitted()) { status("Needs one-time ADB permission grant"); return; }
        if (!watchingSetting) {
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(SETTING), false, setting);
            watchingSetting = true;
            prefs.edit().putInt("started_boot_count", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
                    .putLong("started_elapsed_ms", SystemClock.elapsedRealtime()).apply();
        }
        if (!watchingNetwork && connectivity != null) {
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network);
                watchingNetwork = true;
            } catch (RuntimeException unavailable) {
                Log.w("OablWirelessDebug", "Using periodic network checks", unavailable);
            }
        }
        recheck();
    }

    private void stop() {
        if (watchingNetwork) {
            try { connectivity.unregisterNetworkCallback(network); }
            catch (RuntimeException removed) { Log.w("OablWirelessDebug", "Network callback already removed", removed); }
            watchingNetwork = false;
        }
        if (watchingSetting) {
            context.getContentResolver().unregisterContentObserver(setting);
            watchingSetting = false;
        }
        handler.removeCallbacks(check);
        attempts = 0;
        healthySince = 0;
        status("Off · current wireless debugging unchanged");
    }

    private void recheck() { handler.removeCallbacks(check); handler.post(check); }
    private final ContentObserver setting = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { recheck(); }
    };
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        private void changed() { handler.post(() -> { attempts = 0; recheck(); }); }
        @Override public void onAvailable(Network value) { changed(); }
        @Override public void onLost(Network value) { changed(); }
        @Override public void onLinkPropertiesChanged(Network value, LinkProperties properties) { changed(); }
    };
    private boolean wifiConnected() {
        if (connectivity == null) return false;
        for (Network value : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(value);
            LinkProperties link = connectivity.getLinkProperties(value);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && link != null && !link.getLinkAddresses().isEmpty()) return true;
        }
        return false;
    }
    private void status(String value) {
        if (value.equals(lastStatus)) return;
        lastStatus = value;
        prefs.edit().putString("status", value).putLong("status_time", System.currentTimeMillis()).apply();
        Log.i("OablWirelessDebug", value);
    }
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!enabled()) { stop(); return; }
            if (!wifiConnected()) {
                attempts = 0; healthySince = 0;
                status("Waiting for Wi-Fi");
                handler.postDelayed(this, 30_000); return;
            }
            try {
                long now = SystemClock.elapsedRealtime();
                if (Settings.Global.getInt(context.getContentResolver(), SETTING, 0) == 1) {
                    if (healthySince == 0) healthySince = now;
                    if (now - healthySince >= 10_000) attempts = 0;
                    status("Wireless debugging on · recovery active");
                    handler.postDelayed(this, 30_000); return;
                }
                healthySince = 0;
                if (attempts >= 4) {
                    status("Android needs Wi-Fi approval · open Developer options");
                    handler.postDelayed(this, 30_000); return;
                }
                if (now - lastWrite < 5_000) {
                    handler.postDelayed(this, 5_000 - (now - lastWrite)); return;
                }
                attempts++; lastWrite = now;
                boolean written = Settings.Global.putInt(context.getContentResolver(), SETTING, 1);
                status(written ? "Enabling wireless debugging after Wi-Fi connected" : "Android refused wireless debugging");
                handler.postDelayed(this, 2000);
            } catch (SecurityException denied) {
                status("Needs one-time ADB permission grant");
            }
        }
    };
}
