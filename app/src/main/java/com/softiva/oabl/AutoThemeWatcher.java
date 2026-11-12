package com.softiva.oabl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Minute-boundary updates while visible; resume immediately catches sleep/reboot. */
final class AutoThemeWatcher {
    private final Context context;
    private final Runnable update;
    private final Runnable illuminationChanged = () -> refresh(false);
    private boolean registered;
    private boolean observingLink;
    private int lastIllumination = Integer.MIN_VALUE;
    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(true); }
    };
    AutoThemeWatcher(Context context, Runnable update) { this.context = context; this.update = update; }
    void refresh() { refresh(true); }
    private void refresh(boolean force) {
        if (new LauncherPreferences(context).themeMode() != LauncherPreferences.THEME_AUTO) return;
        double value = EnergyClient.link(context).liveValue(CarSignal.ILLUMINATION);
        int illumination = Double.isNaN(value) ? -1 : value >= 0.5 ? 1 : 0;
        if (force || illumination != lastIllumination) {
            lastIllumination = illumination;
            update.run();
        }
    }
    void start() {
        if (!registered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(clock, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(clock, filter);
            registered = true;
        }
        if (!observingLink) {
            EnergyClient.link(context).observe(illuminationChanged);
            observingLink = true;
        }
        refresh(true);
    }
    void stop() {
        if (registered) { context.unregisterReceiver(clock); registered = false; }
        if (observingLink) {
            EnergyClient.link(context).unobserve(illuminationChanged);
            observingLink = false;
        }
    }
}
