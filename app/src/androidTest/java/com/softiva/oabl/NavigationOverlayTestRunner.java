package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;

/** Exercises the actual overlay service above the installed Maps app. */
public final class NavigationOverlayTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        android.content.SharedPreferences placement = getTargetContext().getSharedPreferences("drive_overlay", 0);
        boolean originalRight = placement.getBoolean("right", true);
        float originalY = placement.getFloat("vertical_fraction", .45f);
        try {
            MainActivity home = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                home.startForegroundService(new Intent(home, DriveOverlayService.class));
                Intent maps = home.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
                if (maps == null) throw new AssertionError("Maps must be installed");
                home.startActivity(maps);
            });
            waitForIdleSync(); SystemClock.sleep(1500);
            java.lang.ref.WeakReference<?> reference = (java.lang.ref.WeakReference<?>)field(DriveOverlayService.class, null, "current");
            DriveOverlayService service = (DriveOverlayService)reference.get();
            check(service != null, "navigation service active");
            View root = (View)field(service.getClass(), service, "overlayRoot");
            View panel = (View)field(service.getClass(), service, "panel");
            View handle = (View)field(service.getClass(), service, "speedCard");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams)field(service.getClass(), service, "overlayParams");
            runOnMainSync(() -> {
                check(root.isAttachedToWindow(), "one actual window attached");
                check(panel.getVisibility() == View.GONE, "starts collapsed");
                check(params.width == Math.round(76*service.getResources().getDisplayMetrics().density), "compact width");
                check(((View)field(service.getClass(), service, "clockCard")).getParent() == panel, "clock nested in the same overlay window");
                handle.performClick();
                check(panel.getVisibility() == View.VISIBLE, "tap expands controls");
                check(!SplitScreenService.showAppControls(getTargetContext()), "duplicate back/home rail suppressed");
                check(((View)field(service.getClass(), service, "mediaCard")).getVisibility() == View.GONE, "idle media omitted");
                check(params.x >= 0 && params.x+params.width <= service.getResources().getDisplayMetrics().widthPixels, "expanded panel on screen");
            });
            waitForIdleSync(); SystemClock.sleep(8600);
            runOnMainSync(() -> check(panel.getVisibility() == View.GONE, "controls auto-collapse"));
            screenshot("navigation-overlay-compact.png");
            runOnMainSync(handle::performClick);
            waitForIdleSync(); SystemClock.sleep(400);
            screenshot("navigation-overlay-expanded.png");
            runOnMainSync(handle::performClick);
            waitForIdleSync(); SystemClock.sleep(250);
            int[] location = new int[2]; runOnMainSync(() -> handle.getLocationOnScreen(location));
            float x = location[0] + handle.getWidth()/2f, y = location[1] + handle.getHeight()/2f;
            long down = SystemClock.uptimeMillis();
            touch(down, android.view.MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(100);
            touch(down, android.view.MotionEvent.ACTION_MOVE, 60, y);
            SystemClock.sleep(100);
            touch(down, android.view.MotionEvent.ACTION_UP, 60, y);
            waitForIdleSync(); SystemClock.sleep(300);
            check(!placement.getBoolean("right", true), "drag snaps and persists left edge");
            runOnMainSync(() -> { check(params.x >= 0, "drag stays on screen"); handle.performClick(); });
            runOnMainSync(() -> ((View)field(service.getClass(), service, "homeCard")).performClick());
            waitForIdleSync(); SystemClock.sleep(600);
            check(!DriveOverlayService.isActive(), "Home removes navigation window");
            result.putString("stream", "PASS: " + checks + " navigation overlay checks above actual Maps.\n");
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error + "\n"); result.putString("failure", error.toString()); }
        finally { placement.edit().putBoolean("right", originalRight).putFloat("vertical_fraction", originalY).commit(); }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private static Object field(Class<?> owner, Object instance, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private void touch(long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { check(getUiAutomation().injectInputEvent(event, true), "drag event injected"); }
        finally { event.recycle(); }
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("No screenshot");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
}
