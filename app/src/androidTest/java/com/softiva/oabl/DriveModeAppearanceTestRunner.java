package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * UI-only day/night previews. No drive-mode signal, car setting, source, history or preference
 * is written. Home is paused while its palette and label are previewed, then restored/resumed.
 * Screenshots are labelled PREVIEW so they cannot be mistaken for physical car-mode evidence.
 */
public final class DriveModeAppearanceTestRunner extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MainActivity[] pausedHome = {null};
        TextView[] previewMarker = {null};
        LauncherTheme.Palette[] originalPalette = {null};
        CharSequence[] originalMode = {null}, originalDescription = {null};
        int[] originalVisibility = {View.GONE};
        int[] originalAccent = {LauncherTheme.driveModeAccent()};
        try {
            check(new LauncherPreferences(getTargetContext()).auroraBackground(),
                    "Aurora background is already selected");
            MainActivity home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                originalPalette[0] = home.currentPalette();
                originalAccent[0] = LauncherTheme.driveModeAccent();
                TextView mode = home.findViewById(R.id.drive_mode_label);
                originalMode[0] = mode.getText();
                originalDescription[0] = mode.getContentDescription();
                originalVisibility[0] = mode.getVisibility();
                callActivityOnPause(home);
                pausedHome[0] = home;
                home.findViewById(R.id.root).animate().cancel();
                home.findViewById(R.id.root).setAlpha(1f);
                TextView marker = new TextView(home);
                marker.setTextColor(Color.WHITE);
                marker.setTextSize(11);
                marker.setPadding(12, 6, 12, 6);
                marker.setBackgroundColor(0xd9000000);
                FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                ((ViewGroup)home.findViewById(R.id.root)).addView(marker, place);
                previewMarker[0] = marker;
            });
            for (int theme : new int[]{LauncherPreferences.THEME_LIGHT, LauncherPreferences.THEME_DARK}) {
                final String period = theme == LauncherPreferences.THEME_LIGHT ? "day" : "night";
                LauncherPreferences previewPreferences = previewPreferences(theme);
                for (DriveMode mode : DriveMode.values()) {
                    runOnMainSync(() -> {
                        LauncherTheme.setDriveModeAccent(mode.accent);
                        LauncherTheme.Palette next = LauncherTheme.resolve(getTargetContext(), previewPreferences);
                        invoke(home, "applyAppearanceNow", new Class<?>[]{LauncherTheme.Palette.class}, next);
                        TextView label = home.findViewById(R.id.drive_mode_label);
                        label.setText(mode.label);
                        label.setContentDescription("Drive mode " + mode.label + ", UI preview only");
                        label.setVisibility(View.VISIBLE);
                        previewMarker[0].setText("UI PREVIEW · " + mode.label + " · " + period);
                    });
                    waitForIdleSync();
                    SystemClock.sleep(350L);
                    runOnMainSync(() -> {
                        TextView label = home.findViewById(R.id.drive_mode_label);
                        Rect bounds = new Rect();
                        check(label.isShown() && mode.label.contentEquals(label.getText())
                                        && label.getGlobalVisibleRect(bounds) && bounds.width() == label.getWidth()
                                        && label.getPaint().measureText(mode.label)
                                        <= label.getWidth() - label.getPaddingLeft() - label.getPaddingRight(),
                                mode.label + " full label fits in " + period);
                        check(home.currentPalette().light == (theme == LauncherPreferences.THEME_LIGHT)
                                        && ((ImageView)home.findViewById(R.id.wallpaper)).getDrawable() instanceof AuroraDrawable,
                                mode.label + " uses Aurora in " + period);
                    });
                    screenshot("drive-mode-" + mode.name().toLowerCase(java.util.Locale.ROOT)
                            + "-" + period + "-preview.png");
                }
            }
            result.putString("stream", "PASS: " + checks
                    + " UI-only checks; six labelled Eco/Comfort/Sports day/night previews.\n");
        } catch (Throwable failure) {
            result.putString("failure", failure.toString());
            result.putString("stream", "FAIL: " + failure + "\n");
        } finally {
            LauncherTheme.setDriveModeAccent(originalAccent[0]);
            if (pausedHome[0] != null) {
                try {
                    runOnMainSync(() -> {
                        MainActivity home = pausedHome[0];
                        try {
                            if (previewMarker[0] != null) {
                                ((ViewGroup)previewMarker[0].getParent()).removeView(previewMarker[0]);
                            }
                            if (originalPalette[0] != null) invoke(home, "applyAppearanceNow",
                                    new Class<?>[]{LauncherTheme.Palette.class}, originalPalette[0]);
                            TextView label = home.findViewById(R.id.drive_mode_label);
                            label.setText(originalMode[0]);
                            label.setContentDescription(originalDescription[0]);
                            label.setVisibility(originalVisibility[0]);
                        } finally {
                            callActivityOnResume(home);
                        }
                    });
                } catch (Throwable cleanupFailure) {
                    result.putString("failure", cleanupFailure.toString());
                    result.putString("stream", result.getString("stream", "")
                            + "FAIL: preview cleanup: " + cleanupFailure + "\n");
                }
            }
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }

    /** Read-only preferences view: choose a palette without changing the driver's settings. */
    private LauncherPreferences previewPreferences(int theme) {
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                            if ("edit".equals(method.getName())) throw new AssertionError("preview cannot write preferences");
                            if ("getInt".equals(method.getName()) && "theme_mode".equals(args[0])) return theme;
                            return method.invoke(actual, args);
                        });
            }
        };
        return new LauncherPreferences(context);
    }

    private void screenshot(String name) throws java.io.IOException {
        Bitmap full = getUiAutomation().takeScreenshot();
        check(full != null, "preview screenshot captured");
        int width = Math.min(1280, full.getWidth());
        Bitmap small = Bitmap.createScaledBitmap(full, width,
                Math.max(1, Math.round((float)full.getHeight() * width / full.getWidth())), true);
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            check(small.compress(Bitmap.CompressFormat.PNG, 100, out), "preview screenshot saved");
        } finally {
            if (small != full) small.recycle();
            full.recycle();
        }
    }

    private static void invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(owner, args);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private void check(boolean pass, String label) {
        if (!pass) throw new AssertionError(label);
        checks++;
    }
}
