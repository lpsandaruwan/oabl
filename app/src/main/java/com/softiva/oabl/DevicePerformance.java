package com.softiva.oabl;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Locale;

/** Read-only device diagnostics and public, same-resolution display hints. No su or sysfs writes. */
final class DevicePerformance {
    @SuppressWarnings("deprecation")
    static Display display(Context context) {
        WindowManager manager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        return manager == null ? null : manager.getDefaultDisplay();
    }
    static boolean sameResolution(Display.Mode current, Display.Mode candidate) {
        return current.getPhysicalWidth() == candidate.getPhysicalWidth()
                && current.getPhysicalHeight() == candidate.getPhysicalHeight();
    }
    static ArrayList<Float> refreshChoices(Context context) {
        ArrayList<Float> result = new ArrayList<>(); result.add(0f);
        Display display = display(context);
        if (display != null) for (Display.Mode mode : display.getSupportedModes()) {
            if (!sameResolution(display.getMode(), mode)) continue;
            boolean duplicate = false;
            for (float rate : result) if (Math.abs(rate-mode.getRefreshRate()) < .2f) duplicate = true;
            if (!duplicate) result.add(mode.getRefreshRate());
        }
        java.util.Collections.sort(result); return result;
    }
    static int modeId(Context context, float requested) {
        Display display = display(context);
        if (display == null || !Float.isFinite(requested) || requested <= 0) return 0;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (sameResolution(display.getMode(), mode) && Math.abs(mode.getRefreshRate()-requested) < .2f) return mode.getModeId();
        }
        return 0;
    }
    static void apply(Context context, Window window) {
        float requested = new LauncherPreferences(context).displayRefreshPreference();
        int id = modeId(context, requested);
        WindowManager.LayoutParams params = window.getAttributes();
        if (params.preferredDisplayModeId == id && params.preferredRefreshRate == (id == 0 ? 0 : requested)) return;
        params.preferredDisplayModeId = id; params.preferredRefreshRate = id == 0 ? 0 : requested;
        window.setAttributes(params);
    }
    static String diagnostics(Context context) {
        StringBuilder result = new StringBuilder(Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);
        result.append("\nApp-visible CPU count: ").append(Runtime.getRuntime().availableProcessors());
        ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
            result.append(String.format(Locale.US, "\nRAM available: %.1f / %.1f GiB", memory.availMem/1073741824d, memory.totalMem/1073741824d));
        }
        Display display = display(context);
        if (display != null) result.append(String.format(Locale.US, "\nAndroid-reported display rate: %.1f Hz", display.getRefreshRate()));
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                String[] names = {"No throttling reported", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown"};
                int thermal = power.getCurrentThermalStatus();
                result.append("\nAndroid-reported thermal status: ").append(thermal >= 0 && thermal < names.length ? names[thermal] : "Unavailable");
            }
        }
        File[] policies = new File("/sys/devices/system/cpu/cpufreq").listFiles();
        boolean found = false;
        if (policies != null) for (File policy : policies) {
            if (!policy.getName().matches("policy[0-9]{1,3}")) continue;
            String governor = read(new File(policy, "scaling_governor"));
            if (governor == null) continue;
            found = true; result.append("\n").append(policy.getName()).append(" governor: ").append(governor);
            String available = read(new File(policy, "scaling_available_governors"));
            if (available != null) result.append("\nAvailable: ").append(available);
        }
        if (!found) result.append("\nCPU governor information is not exposed to this app.");
        result.append("\n\n").append(AudioThreads.report(context));
        result.append("\n\nThis diagnostic view is unprivileged. Root-verified Core settings offers supported CPU governors/frequency limits and conservative passive thermal controls. Critical/shutdown protection stays locked. No CPU or thermal change is made by this diagnostic view."
                + "\n\nDisplay hints affect launcher/EQ windows only—not a YouTube FPS cap or the CarPlay bridge. Firmware may ignore hints. Check bridge settings for supported projection FPS/quality; 30 fps is worth testing if 60 fps stutters, but availability is vendor-specific.");
        return result.toString();
    }
    private static String read(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] chars = new char[256]; int count = reader.read(chars); return count > 0 ? new String(chars, 0, count).trim() : null;
        } catch (Exception unavailable) { return null; }
    }
}
