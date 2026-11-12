package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.ArrayList;

/** Fake privileged command tests never write real system settings or root the emulator. */
public final class CoreSettingsTestRunner extends Instrumentation {
    private int checks;
    private static final class FakeRoot implements RootAccess.Runner {
        boolean granted = true;
        boolean failWrite, badReadback;
        final HashMap<String, String> values = new HashMap<>();
        final ArrayList<String> commands = new ArrayList<>();
        public RootAccess.Result run(String command) {
            commands.add(command);
            if (command.equals("id -u")) return new RootAccess.Result(granted ? 0 : 1, granted ? "0\n" : "Permission denied");
            if (command.startsWith("settings put global ")) {
                String[] tokens = command.split(" ");
                if (failWrite) return new RootAccess.Result(1, "denied");
                values.put(tokens[3], tokens[4]); return new RootAccess.Result(0, "");
            }
            if (command.startsWith("settings get global ")) {
                String key = command.substring("settings get global ".length());
                return new RootAccess.Result(0, badReadback ? "invalid" : values.getOrDefault(key, "1"));
            }
            return new RootAccess.Result(0, "policy0\nscaling_governor\nschedutil\nscaling_available_governors\nschedutil performance\n");
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(RootAccess.granted(new RootAccess.Result(0, "0\n")), "actual root UID accepted");
            check(!RootAccess.granted(new RootAccess.Result(1, "0")), "failure cannot claim root");
            check(!RootAccess.granted(new RootAccess.Result(0, "2000")), "shell UID is not root");
            check(!RootAccess.granted(new RootAccess.Result(0, "uid=0(root)")), "unexpected response fails closed");
            check(!RootAccess.granted(new RootAccess.Result(-1, "timeout")), "timeout fails closed");
            check(!RootAccess.granted(null), "missing result fails closed");
            for (int selected = 0; selected < 4; selected++) {
                FakeRoot fake = new FakeRoot();
                check(CoreSettings.setAnimations(selected, fake).contains("verified"), "animation write verified");
                check(fake.commands.get(0).equals("id -u"), "permission rechecked first");
                for (String key : CoreSettings.ANIMATION_KEYS)
                    check(CoreSettings.ANIMATION_VALUES[selected].equals(fake.values.get(key)), "all three scales updated");
            }
            FakeRoot denied = new FakeRoot(); denied.granted = false;
            check(CoreSettings.setAnimations(2, denied).contains("Nothing changed"), "revoked permission handled");
            check(denied.commands.size() == 1 && denied.values.isEmpty(), "denied access performs no write");
            FakeRoot invalid = new FakeRoot();
            check(CoreSettings.setAnimations(-1, invalid).contains("Invalid"), "negative selection blocked");
            check(CoreSettings.setAnimations(4, invalid).contains("Invalid"), "out-of-range selection blocked");
            check(invalid.commands.isEmpty(), "invalid choice never executes su");
            FakeRoot writeFailure = new FakeRoot(); writeFailure.failWrite = true;
            check(CoreSettings.setAnimations(2, writeFailure).contains("Could not verify"), "write failure not reported as success");
            FakeRoot readFailure = new FakeRoot(); readFailure.badReadback = true;
            check(CoreSettings.setAnimations(2, readFailure).contains("readback unavailable"), "bad readback not reported as success");
            FakeRoot cpu = new FakeRoot();
            check(CoreSettings.cpuReport(cpu).contains("Read-only"), "CPU diagnostics clearly read-only");
            check(cpu.commands.size() == 2 && cpu.commands.get(1).contains("cat") && !cpu.commands.get(1).contains(">"), "CPU probe has no writes");
            check(CoreSettings.readAnimations(new FakeRoot()).contains("animator_duration_scale: 1"), "current values reported");

            MainActivity home = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            show(home, new FakeRoot());
            check(awaitText("Core settings · root access verified"), "root verified exposes Core settings");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            show(home, denied);
            check(awaitText("Root access unavailable · Core settings hidden"), "unrooted/denied UX explains hidden controls");
            check(!hasText("Core settings · root access verified"), "denied root does not expose Core settings");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            long before = android.os.SystemClock.elapsedRealtime();
            RootAccess.Result actual = RootAccess.run("id -u");
            check(android.os.SystemClock.elapsedRealtime()-before < 17000, "actual emulator su probe bounded");
            check(actual != null, "actual unavailable/granted result handled");
            show(home, RootAccess::run);
            check(awaitText(RootAccess.granted(actual) ? "Core settings · root access verified"
                    : "Root access unavailable · Core settings hidden"), "actual emulator root state matches menu");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            result.putString("stream", "PASS: " + checks + " root detection, gated menu and fixed animation-operation checks. Privileged writes mocked; no device CPU or animation settings changed.\n");
        } catch (Throwable error) {
            result.putString("failure", error.toString()); result.putString("stream", "FAIL: " + error + "\n");
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void show(MainActivity home, RootAccess.Runner fake) {
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("showPerformanceSettings", RootAccess.Runner.class);
                method.setAccessible(true); method.invoke(home, fake);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        waitForIdleSync();
    }
    private boolean awaitText(String text) {
        for (int i = 0; i < 170; i++) {
            if (hasText(text)) return true;
            android.os.SystemClock.sleep(100);
        }
        return false;
    }
    private boolean hasText(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return false;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
            if (text.contentEquals(node.getText() == null ? "" : node.getText())) return true;
        return false;
    }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
}
