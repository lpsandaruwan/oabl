package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

/** Physical car-link check. Exercises conflicting GPS callbacks only; never injects a car value. */
public final class CarWidgetsTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            MainActivity home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            CarLinkController link = CarLinkController.get(getTargetContext());
            for (int i = 0; i < 120 && Double.isNaN(link.liveValue(CarSignal.ODOMETER)); i++) SystemClock.sleep(250);
            waitForIdleSync();
            onUi(() -> invoke(home, "renderDashboardData", new Class<?>[0]));
            awaitHomeShown(home);
            check(link.expectsCarBattery(), "car source configured");
            double car = link.liveValue(CarSignal.SPEED);
            check(Double.isFinite(car), "fresh physical car speed");
            check(Double.isFinite(link.liveValue(CarSignal.ODOMETER)), "fresh physical car odometer");
            check(Double.isFinite(link.liveValue(CarSignal.HVAC_ON)), "fresh physical HVAC status");
            check(link.liveDriveMode() != null, "fresh physical drive mode");
            check(Double.isFinite(link.liveValue(CarSignal.TEMP_OUT)), "fresh physical outside temperature");
            final int expectedSpeed = (int) Math.round(car);
            final int conflictingGps = expectedSpeed == 72 ? 87 : 72;
            onUi(() -> {
                invoke(home, "renderDashboardData", new Class<?>[0]);
                TextView odo = home.findViewById(R.id.trip_note);
                check(odo.getText().toString().startsWith("ODO ") && !odo.getText().toString().contains("—"), "Home renders live odometer");
                TextView outside = home.findViewById(R.id.outside_temperature);
                check(outside.isShown() && outside.getText().toString().startsWith("OUT ")
                        && outside.getText().toString().endsWith("°C"), "outside temperature is shown on dashboard");
                TextView mode = home.findViewById(R.id.drive_mode_label);
                check(mode.isShown() && mode.getText().toString().equals(link.liveDriveMode().label), "dashboard shows full current drive mode name");
                invoke(home, "setDrivingMode", new Class<?>[]{boolean.class}, false);
                check(((TextView)home.findViewById(R.id.telemetry_label)).getText().toString().contains("CAR SPEED"), "mode refresh keeps car source label");
                callback(home).onSpeed(conflictingGps, true);
                check(((SpeedGaugeView)home.findViewById(R.id.speed_cluster)).speed() == expectedSpeed, "Home ignores conflicting GPS speed");
                check(((TextView)home.findViewById(R.id.launcher_tagline)).getText().toString().equals("CAR SPEED"), "Home speed caption is car");
                home.startForegroundService(new Intent(home, DriveOverlayService.class));
            });
            for (int i = 0; i < 40 && !DriveOverlayService.isActive(); i++) SystemClock.sleep(100);
            check(DriveOverlayService.isActive(), "floating widget started");
            onUi(() -> {
                Object overlay = ((java.lang.ref.WeakReference<?>)field(DriveOverlayService.class, null, "current")).get();
                callback(overlay).onSpeed(conflictingGps, true);
                check(((TextView)field(DriveOverlayService.class, overlay, "speedValue")).getText().toString().equals(Integer.toString(expectedSpeed)), "floating widget ignores conflicting GPS speed");
                check(((TextView)field(DriveOverlayService.class, overlay, "speedStatus")).getText().toString().contains("CAR"), "floating widget labels car speed");
            });
            SystemClock.sleep(250);
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "car-widgets-verified.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
            result.putString("stream", "PASS: " + checks + " physical car signal/Home/floating-widget checks; conflicting GPS callback ignored.\n");
        } catch (Throwable failure) {
            result.putString("failure", failure.toString()); result.putString("stream", "FAIL: " + failure + "\n");
        } finally { getTargetContext().stopService(new Intent(getTargetContext(), DriveOverlayService.class)); }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void awaitHomeShown(MainActivity home) {
        long deadline = SystemClock.elapsedRealtime() + 8_000L;
        boolean[] shown = {false};
        while (SystemClock.elapsedRealtime() < deadline) {
            onUi(() -> {
                View root = home.findViewById(R.id.root);
                shown[0] = home.hasWindowFocus() && root.isShown() && root.getWidth() > 0
                        && home.findViewById(R.id.outside_temperature).isShown()
                        && home.findViewById(R.id.drive_mode_label).isShown();
            });
            if (shown[0]) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Home window and car condition labels did not become visible");
    }
    /** Return UI failures to the instrumentation thread so cleanup and the FAIL report still run. */
    private void onUi(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0].toString(), failure[0]);
    }
    private SpeedTracker.Listener callback(Object owner) {
        SpeedTracker tracker = (SpeedTracker)field(owner.getClass(), owner, "speedTracker");
        return (SpeedTracker.Listener)field(SpeedTracker.class, tracker, "listener");
    }
    private static Object field(Class<?> type, Object owner, String name) {
        try { java.lang.reflect.Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try { java.lang.reflect.Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(owner, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
}
