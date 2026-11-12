package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Explicit real-root integration tests. Run only on the dedicated OABL rooted test AVD. */
public final class RootedCoreTestRunner extends Instrumentation {
    private String phase;
    private int checks;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); phase = args == null ? "configure" : args.getString("phase", "configure"); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(android.os.Process.myUid() >= 10000, "tests execute as sandboxed OABL UID, not adb root");
            // A default HOME launcher can already be started by Android while instrumentation initializes.
            // Force a fresh activity so startActivitySync does not wait forever for an existing singleTask.
            MainActivity home = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if (phase.equals("denied")) {
                String before = actualScales();
                check(!RootAccess.check(), "actual Magisk denial detected");
                check(CoreSettings.setAnimations(0, RootAccess::run).contains("Nothing changed"), "revocation blocks real mutation");
                check(before.equals(actualScales()), "denied mutation preserves animation scales");
                show(home, "showPerformanceSettings");
                check(await("Root access unavailable · Core settings hidden"), "real denied access hides Core settings");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            } else {
                check(RootAccess.check(), "real app su returned root UID 0");
                show(home, "showPerformanceSettings");
                check(await("Core settings · root access verified"), "actual root exposes Core settings");
                check(click("Core settings · root access verified"), "root menu opens by touch action");
                check(await("System animation speed"), "Core controls displayed");
                if (phase.equals("configure")) {
                    for (int choice = 0; choice < CoreSettings.ANIMATION_VALUES.length; choice++) {
                        check(CoreSettings.setAnimations(choice, RootAccess::run).contains("verified"), "real animation writes verified for " + choice);
                        verifyScales(CoreSettings.ANIMATION_VALUES[choice]);
                    }
                    check(click("System animation speed"), "opens current scales");
                    check(await("animator_duration_scale: 1"), "current scales shown from actual root read");
                    check(click("Change speed"), "speed picker opens");
                    check(await("0.5× · fast"), "fast choice visible");
                    check(click("0.5× · fast"), "fast speed selected");
                    check(await("Apply system animation speed?"), "mutation confirmation shown");
                    check(click("Apply"), "explicit apply works");
                    check(await("System animations set to 0.5× and verified."), "UI reports real verified result");
                    verifyScales("0.5");
                    android.os.SystemClock.sleep(300);
                    android.graphics.Bitmap preview = getUiAutomation().takeScreenshot();
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "root-core-animations.png"))) {
                        preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                    } finally { preview.recycle(); }
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                } else if (phase.equals("post-reboot")) {
                    verifyScales("0.5");
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                } else throw new AssertionError("Unknown phase " + phase);
                String policies = CoreSettings.cpuReport(RootAccess::run);
                check(policies.contains("Read-only") || policies.contains("unavailable even with superuser"), "actual CPU diagnostics report availability honestly");
                result.putString("cpu", policies);
            }
            result.putString("stream", "PASS: " + checks + " real-root checks, phase " + phase + ". App UID=" + android.os.Process.myUid() + ".\n");
        } catch (Throwable failure) {
            android.graphics.Bitmap preview = getUiAutomation().takeScreenshot();
            if (preview != null) {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "root-test-failure.png"))) {
                    preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException ignored) { } finally { preview.recycle(); }
            }
            result.putString("failure", failure.toString()); result.putString("stream", "FAIL: " + failure + "\n");
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void verifyScales(String value) {
        for (String key : CoreSettings.ANIMATION_KEYS) {
            String actual = Settings.Global.getString(getTargetContext().getContentResolver(), key);
            check(actual != null && Float.parseFloat(actual) == Float.parseFloat(value), "independent Android readback " + key);
        }
    }
    private String actualScales() {
        StringBuilder values = new StringBuilder();
        for (String key : CoreSettings.ANIMATION_KEYS)
            values.append(Settings.Global.getString(getTargetContext().getContentResolver(), key)).append('\n');
        return values.toString();
    }
    private void show(MainActivity home, String name) {
        runOnMainSync(() -> {
            try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(home); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    private boolean await(String text) {
        for (int i = 0; i < 200; i++) { if (find(text) != null) return true; android.os.SystemClock.sleep(100); }
        return false;
    }
    private AccessibilityNodeInfo find(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
            if (node.getText() != null && node.getText().toString().toLowerCase(java.util.Locale.ROOT)
                    .contains(text.toLowerCase(java.util.Locale.ROOT))) return node;
        return null;
    }
    private boolean click(String text) {
        for (int i = 0; i < 40; i++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo candidate : root.findAccessibilityNodeInfosByText(text)) {
                if (candidate.getText() == null || !candidate.getText().toString().equalsIgnoreCase(text)) continue;
                AccessibilityNodeInfo node = candidate;
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node != null && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            android.os.SystemClock.sleep(100);
        }
        return false;
    }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
}
