package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.util.Calendar;
import java.util.TimeZone;

/** Fixed IST boundaries independent of the device's timezone/system night setting. */
public final class AutoThemeTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        LauncherPreferences prefs = new LauncherPreferences(getTargetContext());
        TimeZone originalZone = TimeZone.getDefault();
        float originalRate = prefs.displayRefreshPreference();
        try {
            prefs.setThemeMode(LauncherPreferences.THEME_AUTO);
            for (String zone : new String[]{"America/Los_Angeles", "UTC", "Asia/Kolkata", "Asia/Colombo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (int[] sample : new int[][]{{0,0,0},{8,29,0},{8,30,1},{12,0,1},{16,59,1},{17,0,0},{23,59,0}}) {
                    long now = atIST(sample[0], sample[1]);
                    check(LauncherTheme.scheduledLight(now) == (sample[2] == 1), zone + " boundary " + sample[0] + ":" + sample[1]);
                    check(LauncherTheme.resolve(getTargetContext(), prefs, now).light == (sample[2] == 1), "Auto palette at boundary");
                }
            }
            prefs.setThemeMode(LauncherPreferences.THEME_LIGHT);
            check(LauncherTheme.resolve(getTargetContext(), prefs, atIST(20,0)).light, "manual Light overrides night");
            prefs.setThemeMode(LauncherPreferences.THEME_DARK);
            check(!LauncherTheme.resolve(getTargetContext(), prefs, atIST(12,0)).light, "manual Dark overrides day");
            MainActivity home = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                prefs.setThemeMode(LauncherPreferences.THEME_AUTO);
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("themeWatcher");
                    field.setAccessible(true);
                    ((AutoThemeWatcher)field.get(home)).refresh();
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            waitForIdleSync();
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("palette");
            field.setAccessible(true);
            check(((LauncherTheme.Palette)field.get(home)).light == LauncherTheme.scheduledLight(System.currentTimeMillis()), "open dashboard refreshes to scheduled mode");
            check(new LauncherPreferences(getTargetContext()).themeMode() == LauncherPreferences.THEME_AUTO, "Auto selection saved");
            java.util.ArrayList<Float> rates = DevicePerformance.refreshChoices(home);
            check(rates.get(0) == 0, "display choices always include System");
            for (float rate : rates) if (rate > 0) {
                int id = DevicePerformance.modeId(home, rate);
                check(id != 0, "only actual display modes offered");
                android.view.Display display = DevicePerformance.display(home);
                for (android.view.Display.Mode candidate : display.getSupportedModes()) if (candidate.getModeId() == id)
                    check(DevicePerformance.sameResolution(display.getMode(), candidate), "refresh hint does not change resolution");
            }
            check(DevicePerformance.modeId(home, 999) == 0 && DevicePerformance.modeId(home, Float.NaN) == 0, "unsupported/invalid rates fall back to System");
            runOnMainSync(() -> {
                float rate = rates.size() > 1 ? rates.get(1) : 0;
                prefs.setDisplayRefreshPreference(rate);
                DevicePerformance.apply(home, home.getWindow());
                check(new LauncherPreferences(home).displayRefreshPreference() == rate, "display preference persists");
                check(home.getWindow().getAttributes().preferredDisplayModeId == DevicePerformance.modeId(home, rate), "window receives supported hint");
                try {
                    java.lang.reflect.Method performance = MainActivity.class.getDeclaredMethod("showPerformanceSettings");
                    performance.setAccessible(true); performance.invoke(home);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            waitForIdleSync(); android.os.SystemClock.sleep(350);
            android.graphics.Bitmap preview = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "performance-settings.png"))) {
                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            } finally { preview.recycle(); }
            String diagnostics = DevicePerformance.diagnostics(getTargetContext());
            check(diagnostics.contains("governor") && diagnostics.contains("thermal") && diagnostics.contains("not a YouTube FPS cap"), "diagnostics clearly state scope and limitations");
            result.putString("stream", "PASS: " + checks + " checks; IST boundaries, device timezone independence, manual overrides, live dashboard refresh and saved Auto mode.\n");
        } catch (Throwable error) { result.putString("failure", error.toString()); result.putString("stream", "FAIL: " + error + "\n"); }
        finally {
            TimeZone.setDefault(originalZone);
            // User explicitly requested this schedule; leave the emulator in Auto.
            prefs.setThemeMode(LauncherPreferences.THEME_AUTO);
            prefs.setDisplayRefreshPreference(originalRate);
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private static long atIST(int hour, int minute) {
        Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("GMT+05:30"));
        clock.clear(); clock.set(2026, Calendar.OCTOBER, 13, hour, minute, 0); return clock.getTimeInMillis();
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
