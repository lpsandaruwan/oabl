package com.softiva.oabl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Emulator-only regression: measured speed is immediate while only the gauge arc interpolates. */
public final class SpeedDisplayTestRunner extends Instrumentation {
    private MainActivity home;
    private SpeedGaugeView gauge;
    private TextView number;
    private String expectedLabel;
    private final List<String> inventedLabels = new ArrayList<>();
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable failure = null;
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE);
        boolean changedPreference = false;
        boolean hadEffects = false;
        boolean savedEffects = true;
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "Emulator only");
            check(Build.VERSION.SDK_INT >= 26 && ValueAnimator.areAnimatorsEnabled(),
                    "Animations must be enabled for this regression; set the emulator animator duration scale to 1");
            hadEffects = preferences.contains("visual_effects");
            savedEffects = preferences.getBoolean("visual_effects", true);
            preferences.edit().putBoolean("visual_effects", true).commit();
            changedPreference = true;
            check(new LauncherPreferences(context).visualEffects(), "Visual effects must be active; thermal throttling prevents this test");

            home = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            ui(() -> {
                gauge = new SpeedGaugeView(home, null);
                // Home keeps rendering its own R.id.speed_cluster. This attached fixture has no ID.
                gauge.setId(View.NO_ID);
                gauge.setOrientation(LinearLayout.VERTICAL);
                number = new TextView(home);
                number.setId(R.id.speed_value);
                number.setText("—");
                gauge.addView(number, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                gauge.onFinishInflate();
                int size = Math.round(240 * home.getResources().getDisplayMetrics().density);
                home.addContentView(gauge, new ViewGroup.LayoutParams(size, size));
                number.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable text) {
                        if (!text.toString().equals(expectedLabel))
                            inventedLabels.add("Expected " + expectedLabel + ", saw " + text);
                    }
                });
            });
            waitForIdleSync();
            ui(() -> {
                check(gauge.isAttachedToWindow(), "Fixture is attached to an actual window");
                reading(0);
                label("0", "Initial measured zero");
                reading(80);
                label("80", "Acceleration displays the new measurement before an animation frame");
                check(animating() && arcSpeed() < 80f, "Arc starts gliding without delaying the measured number");
            });

            SystemClock.sleep(100);
            ui(() -> {
                label("80", "Number holds 80 while the arc passes through intermediate positions");
                check(animating() && arcSpeed() > 0f && arcSpeed() < 80f, "Attached arc actually animates through intermediate positions");
                reading(3);
                label("3", "A lower measurement replaces the number during the previous animation");
                check(animating(), "Replacement measurement retains an arc animation");
            });
            SystemClock.sleep(350);
            ui(() -> {
                label("3", "Old acceleration completion cannot overwrite the newer measurement");
                check(!animating() && Math.abs(arcSpeed() - 3f) < 0.01f, "Arc finishes at the latest measurement");
                reading(95);
                label("95", "Next measurement displays immediately");
                check(animating(), "Unknown-state fixture starts with a running animation");
                expectedLabel = "—";
                gauge.unknown(0, null);
                label("—", "Missing or stale speed immediately displays a dash");
                check(!gauge.known() && !animating(), "Unknown speed cancels the active animation");
            });

            // Longer than either arc duration: a canceled old listener must never resurrect a number.
            SystemClock.sleep(1100);
            ui(() -> {
                label("—", "Dash survives the time when the old animation would have ended");
                check(!gauge.known(), "Unknown state remains unknown after the former animation duration");
                reading(95);
                label("95", "A fresh measurement recovers immediately, including the previous target");
                check(gauge.known(), "Fresh measurement restores known speed");
                check(Math.abs(arcSpeed() - 95f) < 0.01f, "Recovered unchanged measurement restores the arc too");
                reading(42);
                label("42", "Another fresh measurement updates immediately after recovery");
                check(!animating() && Math.abs(arcSpeed() - 42f) < 0.01f, "Cruising readings step the arc instead of gliding (less CarPlay video)");
            });
            SystemClock.sleep(350);
            ui(() -> {
                label("42", "Recovered number remains the measurement throughout the arc animation");
                check(inventedLabels.isEmpty(), "Every numeric text change is a received measurement: " + inventedLabels);
            });
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                ui(() -> {
                    if (gauge != null && gauge.getParent() instanceof ViewGroup)
                        ((ViewGroup) gauge.getParent()).removeView(gauge);
                    if (home != null) home.finish();
                });
                waitForIdleSync();
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
            try {
                if (changedPreference) {
                    SharedPreferences.Editor edit = preferences.edit();
                    if (hadEffects) edit.putBoolean("visual_effects", savedEffects);
                    else edit.remove("visual_effects");
                    if (!edit.commit()) throw new AssertionError("Could not restore the visual-effects preference");
                }
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        result.putString("stream", failure == null
                ? "PASS · " + checks + " speed display checks; immediate measurements, animated arc when starting or stopping, stepped arc while cruising, mid-animation replacement, unknown cancellation and recovery.\n"
                : "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(failure));
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void reading(int speed) {
        expectedLabel = String.valueOf(speed);
        gauge.reading(speed, 0, null);
    }

    private void label(String expected, String message) {
        check(expected.contentEquals(number.getText()), message + ": " + number.getText());
        check(inventedLabels.isEmpty(), "No interpolated numeric readings: " + inventedLabels);
    }

    private boolean animating() {
        ValueAnimator animator = (ValueAnimator) field("glide");
        return animator != null && animator.isRunning();
    }

    private float arcSpeed() { return (Float) field("shown"); }

    private Object field(String name) {
        try {
            Field field = SpeedGaugeView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(gauge);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    /** Assertions raised on the main thread must be reported by the runner, not crash the app. */
    private void ui(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0].toString(), failure[0]);
    }
}
