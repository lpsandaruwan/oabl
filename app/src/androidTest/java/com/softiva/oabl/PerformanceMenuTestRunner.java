package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.ListView;
import android.widget.TextView;

/** Actual rendered list contrast, before/after asynchronous root-row update, in both themes. */
public final class PerformanceMenuTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        LauncherPreferences prefs = new LauncherPreferences(getTargetContext());
        int originalMode = prefs.themeMode();
        MainActivity home = null;
        try {
            home = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            final MainActivity activity = home;
            for (int mode : new int[]{LauncherPreferences.THEME_LIGHT, LauncherPreferences.THEME_DARK}) {
                final AlertDialog[] dialog = new AlertDialog[1];
                runOnMainSync(() -> {
                    prefs.setThemeMode(mode);
                    try {
                        java.lang.reflect.Method appearance = MainActivity.class.getDeclaredMethod("applyAppearance", boolean.class);
                        appearance.setAccessible(true); appearance.invoke(activity, false);
                        java.lang.reflect.Method menu = MainActivity.class.getDeclaredMethod("showPerformanceSettings", RootAccess.Runner.class);
                        menu.setAccessible(true); dialog[0] = (AlertDialog)menu.invoke(activity, (RootAccess.Runner)RootAccess::run);
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                waitForIdleSync();
                runOnMainSync(() -> verify(dialog[0], mode));
                for (int i = 0; i < 100; i++) {
                    final boolean[] finished = {false};
                    runOnMainSync(() -> finished[0] = !dialog[0].getListView().getAdapter().getItem(3).toString().contains("Checking"));
                    if (finished[0]) break;
                    android.os.SystemClock.sleep(100);
                }
                waitForIdleSync();
                runOnMainSync(() -> {
                    check(dialog[0].getListView().getAdapter().getItem(3).toString().equals("Core settings · root access verified"), "actual root row updated");
                    verify(dialog[0], mode);
                });
                android.os.SystemClock.sleep(300);
                android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), mode == LauncherPreferences.THEME_LIGHT ? "performance-menu-light.png" : "performance-menu-dark.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } finally { screenshot.recycle(); }
                runOnMainSync(dialog[0]::dismiss); waitForIdleSync();
            }
            result.putString("stream", "PASS: " + checks + " rendered menu visibility/contrast/root-update checks in Light and Dark.\n");
        } catch (Throwable failure) { result.putString("failure", failure.toString()); result.putString("stream", "FAIL: " + failure + "\n"); }
        finally {
            prefs.setThemeMode(originalMode);
            if (home != null) {
                final MainActivity activity = home;
                runOnMainSync(() -> {
                    try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("applyAppearance", boolean.class); method.setAccessible(true); method.invoke(activity, false); }
                    catch (ReflectiveOperationException ignored) { }
                });
            }
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void verify(AlertDialog dialog, int mode) {
        ListView list = dialog.getListView();
        check(list.getChildCount() == 5, "all five options rendered");
        android.content.res.TypedArray colors = dialog.getContext().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
        int background = colors.getColor(0, mode == LauncherPreferences.THEME_LIGHT ? Color.WHITE : Color.BLACK); colors.recycle();
        int expected = LauncherTheme.resolve(getTargetContext(), new LauncherPreferences(getTargetContext())).ink;
        for (int i = 0; i < 5; i++) {
            TextView row = (TextView)list.getChildAt(i);
            check(row.getText().length() > 0 && row.getVisibility() == android.view.View.VISIBLE, "visible nonempty menu row " + i);
            check(row.getCurrentTextColor() == expected, "explicit theme ink on row " + i);
            double a = Color.luminance(row.getCurrentTextColor()), b = Color.luminance(background);
            check((Math.max(a,b)+.05)/(Math.min(a,b)+.05) >= 4.5, "readable foreground/background contrast row " + i);
        }
    }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
}
