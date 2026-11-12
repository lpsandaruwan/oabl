package com.softiva.oabl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** Android device battery only; never substitutes for vehicle state of charge. */
final class DeviceBattery {
    static String read(Context context) {
        try {
            return label(context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        } catch (RuntimeException unavailable) {
            return "Device battery unavailable";
        }
    }

    static String label(Intent battery) {
        if (battery == null) return "Device battery unavailable";
        if (!battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    ? "Device · external power" : "Device · no battery detected";
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (scale <= 0 || level < 0 || level > scale) return "Device battery unavailable";
        int percent = Math.round(level * 100f / scale);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return "Device · " + percent + "%"
                + (status == BatteryManager.BATTERY_STATUS_CHARGING ? " · charging"
                : status == BatteryManager.BATTERY_STATUS_FULL ? " · full" : "");
    }
}
