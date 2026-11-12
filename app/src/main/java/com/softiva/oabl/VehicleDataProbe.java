package com.softiva.oabl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Read-only scan of what the box firmware exposes from the car (via CarPlay): location
 * providers, car-related system properties, packages, services and content providers.
 * Nothing is changed or sent anywhere; the report is only shown on screen.
 */
final class VehicleDataProbe {
    private static final Pattern CAR = Pattern.compile(
            "(?i)carplay|carlink|iap2?\\b|vehicle|canbus|can_bus|\\bcar\\b|car\\.|obd|zlink|autokit|carbit|tbox|mcu");

    private VehicleDataProbe() { }

    static String report(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Board: ").append(Build.BOARD).append(" · ").append(Build.HARDWARE).append("\n\n");

        out.append("LOCATION PROVIDERS\n");
        LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (location != null) {
            for (String provider : location.getAllProviders()) {
                out.append("• ").append(provider).append(location.isProviderEnabled(provider) ? " (on)" : " (off)");
                try {
                    Location last = location.getLastKnownLocation(provider);
                    if (last != null) {
                        out.append(String.format(Locale.US, " · last fix ±%.0f m%s%s", last.getAccuracy(),
                                last.hasSpeed() ? String.format(Locale.US, " · %.0f km/h", last.getSpeed() * 3.6f) : "",
                                last.hasBearing() ? " · bearing" : ""));
                    }
                } catch (SecurityException noPermission) {
                    out.append(" · location permission needed");
                }
                out.append('\n');
            }
        }

        out.append("\nBATTERY (as reported to Android)\n");
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            out.append(String.format(Locale.US, "• level %d/%d · plugged %d · status %d · voltage %d mV · temp %.1f °C\n",
                    battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1), battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                    battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1), battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f));
        }

        out.append("\nCAR-RELATED SYSTEM PROPERTIES\n");
        int properties = 0;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"getprop"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && properties < 80) {
                    if (CAR.matcher(line).find()) { out.append("• ").append(line).append('\n'); properties++; }
                }
            }
        } catch (Exception unavailable) {
            out.append("• system properties unavailable\n");
        }
        if (properties == 0) out.append("• none found\n");

        out.append("\nCAR-RELATED APPS, SERVICES AND PROVIDERS\n");
        int found = 0;
        PackageManager manager = context.getPackageManager();
        for (PackageInfo info : manager.getInstalledPackages(PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS)) {
            boolean packageMatch = CAR.matcher(info.packageName).find();
            StringBuilder parts = new StringBuilder();
            if (info.services != null) for (ServiceInfo service : info.services) {
                if (packageMatch || CAR.matcher(service.name).find()) parts.append("    service ").append(service.name).append(service.exported ? " (exported)" : "").append('\n');
            }
            if (info.providers != null) for (ProviderInfo provider : info.providers) {
                if (packageMatch || CAR.matcher(String.valueOf(provider.authority)).find()) parts.append("    provider ").append(provider.authority).append(provider.exported ? " (exported)" : "").append('\n');
            }
            if (packageMatch || parts.length() > 0) {
                out.append("• ").append(info.packageName).append('\n').append(parts);
                if (++found >= 40) break;
            }
        }
        if (found == 0) out.append("• none found\n");
        out.append("\nShare this report so OABL can read any vehicle data the firmware offers (e.g. battery level or range).");
        return out.toString();
    }
}
