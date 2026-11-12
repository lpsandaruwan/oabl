package com.softiva.oabl;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * Box temperature and an overheat guard. A car parked in the sun can push the box hard; when it
 * runs hot the launcher turns its own animations down until it has cooled again.
 */
final class BoxThermal {
    /** True while the box is hot enough that visual effects are held back. */
    static volatile boolean throttled;
    private static final float HOT = 85f, COOL = 75f;
    private static long readAt;
    private static float cached = Float.NaN;

    private BoxThermal() { }

    private static final java.util.concurrent.ExecutorService READER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean reading;
    /** The CPU/SoC/GPU sensors found on the first scan (all readable zones if none match), read from then on. */
    private static File[] zones;

    /**
     * Hottest CPU/SoC sensor in °C, or NaN where the firmware doesn't let apps read it. Never reads
     * files on the caller's thread: it returns the last value and refreshes it in the background
     * at most every 10 s.
     */
    static synchronized float celsius() {
        long now = SystemClock.elapsedRealtime();
        if ((readAt == 0 || now - readAt >= 10_000) && !reading) {
            reading = true;
            READER.execute(() -> {
                float value = read();
                synchronized (BoxThermal.class) { cached = value; readAt = SystemClock.elapsedRealtime(); reading = false; }
            });
        }
        return cached;
    }

    private static float read() {
        if (zones == null) zones = discover();
        float hottest = Float.NaN;
        for (File zone : zones) {
            float value = parse(line(new File(zone, "temp")));
            if (!Float.isNaN(value)) hottest = Float.isNaN(hottest) ? value : Math.max(hottest, value);
        }
        return hottest;
    }

    private static File[] discover() {
        File[] all = new File("/sys/class/thermal").listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (all == null) return new File[0];
        java.util.List<File> preferred = new java.util.ArrayList<>(), readable = new java.util.ArrayList<>();
        for (File zone : all) {
            if (Float.isNaN(parse(line(new File(zone, "temp"))))) continue;
            readable.add(zone);
            String type = line(new File(zone, "type"));
            type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("gpu")) preferred.add(zone);
        }
        return (preferred.isEmpty() ? readable : preferred).toArray(new File[0]);
    }

    /** Re-checks the guard; returns true when the throttled state changed. */
    static boolean update(Context context) {
        float temperature = celsius();
        boolean severe = false;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = context.getSystemService(PowerManager.class);
            severe = power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
        }
        boolean next = throttled
                ? severe || (!Float.isNaN(temperature) && temperature > COOL)
                : severe || (!Float.isNaN(temperature) && temperature >= HOT);
        if (next == throttled) return false;
        throttled = next;
        return true;
    }

    private static String line(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Kernels report millidegrees (most) or degrees; anything outside 5–150 °C is a dummy sensor. */
    private static float parse(String raw) {
        if (raw == null) return Float.NaN;
        try {
            float value = Float.parseFloat(raw.trim());
            if (value > 1000) value /= 1000f;
            return value >= 5 && value <= 150 ? value : Float.NaN;
        } catch (NumberFormatException bad) {
            return Float.NaN;
        }
    }
}
