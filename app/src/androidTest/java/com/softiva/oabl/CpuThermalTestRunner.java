package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Pure validation/rollback tests plus actual Magisk CPU sysfs writes on the dedicated AVD. */
public final class CpuThermalTestRunner extends Instrumentation {
    private int checks;
    private static final class Fake implements RootAccess.Runner {
        boolean grant = true, failNext, failReadback, secondPolicy;
        String failPath;
        long critical = 100000;
        final HashMap<String, String> files = new HashMap<>();
        int writes;
        Fake() {
            files.put(CpuControls.BASE+"policy9876/scaling_governor", "schedutil");
            files.put(CpuControls.BASE+"policy9876/scaling_min_freq", "600000");
            files.put(CpuControls.BASE+"policy9876/scaling_max_freq", "1800000");
            files.put("/sys/class/thermal/thermal_zone9876/trip_point_0_temp", "80000");
            files.put("/sys/class/thermal/thermal_zone9876/trip_point_0_hyst", "2000");
        }
        public RootAccess.Result run(String command) {
            if (command.equals("id -u")) return new RootAccess.Result(grant ? 0 : 1, grant ? "0" : "Denied");
            if (command.equals(CpuControls.READ)) return new RootAccess.Result(0, "policy9876|0 1|test-driver|"+files.get(CpuControls.BASE+"policy9876/scaling_governor")
                    +"|schedutil performance powersave|600000|1800000|"+files.get(CpuControls.BASE+"policy9876/scaling_min_freq")+"|"
                    +files.get(CpuControls.BASE+"policy9876/scaling_max_freq")+"|1200000|600000 1200000 1800000|yes|yes|yes\n"
                    +(secondPolicy ? "policy9877|2 3|test-driver|"+files.get(CpuControls.BASE+"policy9877/scaling_governor")+"|schedutil performance powersave|600000|1800000|600000|1800000|1200000|600000 1200000 1800000|yes|yes|yes\n" : ""));
            if (command.equals(ThermalControls.READ)) return new RootAccess.Result(0, "thermal_zone9876|cpu-thermal|0|passive|"
                    +files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_temp")+"|"+files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_hyst")
                    +"|yes|yes\nthermal_zone9876|cpu-thermal|1|critical|"+critical+"|2000|yes|yes\n");
            Matcher write = Pattern.compile("printf '%s' '([^']+)' > '([^']+)'").matcher(command);
            if (write.matches()) {
                writes++; if (failNext || write.group(2).equals(failPath)) { failNext = false; failPath = null; return new RootAccess.Result(1, "Rejected"); }
                files.put(write.group(2), write.group(1)); return new RootAccess.Result(0, "");
            }
            Matcher read = Pattern.compile("cat '([^']+)'").matcher(command);
            if (read.matches()) {
                if (failReadback) { failReadback = false; return new RootAccess.Result(0, "invalid"); }
                return new RootAccess.Result(0, files.getOrDefault(read.group(1), ""));
            }
            throw new AssertionError("Unexpected privileged command " + command);
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context isolated = new ContextWrapper(getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("tuning_test_"+name, mode); }
        };
        try {
            isolated.getSharedPreferences("cpu_originals", Context.MODE_PRIVATE).edit().clear().commit();
            isolated.getSharedPreferences("thermal_originals", Context.MODE_PRIVATE).edit().clear().commit();
            Fake fake = new Fake();
            check(CpuControls.read(fake).size() == 1, "CPU discovery parsed");
            check(CpuControls.parse("policy0;bad|x").isEmpty(), "malformed/injected policy discarded");
            check(!CpuControls.safePolicy("policy0/../../thermal"), "policy traversal rejected");
            check(!CpuControls.safeGovernor("performance; reboot"), "governor injection rejected");
            expect(() -> CpuControls.path("policy0", "cpuinfo_max_freq"), "read-only field not writable");
            check(CpuControls.profile(isolated, "performance", fake).contains("Verified"), "common profile verified");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_governor").equals("performance"), "profile reaches governor");
            int writes = fake.writes;
            expect(() -> CpuControls.profile(isolated, "unsupported", fake), "unsupported profile blocked");
            expect(() -> CpuControls.limit(isolated, "policy9876", false, 2200000, fake), "overclock blocked");
            expect(() -> CpuControls.limit(isolated, "policy9876", true, 1234567, fake), "non-advertised frequency blocked");
            check(fake.writes == writes, "invalid choices never write");
            check(CpuControls.limit(isolated, "policy9876", false, 1200000, fake).contains("Verified"), "max cap verified");
            expect(() -> CpuControls.limit(isolated, "policy9876", true, 1800000, fake), "min above max blocked");
            check(CpuControls.restore(isolated, fake).contains("restored and verified"), "original CPU restore verified");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_governor").equals("schedutil"), "first governor saved across later changes");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_max_freq").equals("1800000"), "original max restored");
            fake.failNext = true; expect(() -> CpuControls.governor(isolated, "policy9876", "performance", fake), "kernel rejection handled");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_governor").equals("schedutil"), "governor rollback verified");
            fake.failReadback = true; expect(() -> CpuControls.limit(isolated, "policy9876", false, 1200000, fake), "bad readback handled");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_max_freq").equals("1800000"), "limit rollback verified");
            fake.grant = false; writes = fake.writes;
            expect(() -> CpuControls.profile(isolated, "performance", fake), "revoked root blocks profile");
            check(fake.writes == writes, "denial produces no sysfs writes"); fake.grant = true;
            List<ThermalControls.Trip> trips = ThermalControls.read(fake);
            check(trips.size() == 2 && trips.get(0).editable() && !trips.get(1).editable(), "critical trip remains locked even if writable");
            expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "1", false, 110000, fake), "critical thermal edit blocked");
            expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "0", false, 90000, fake), "raising passive threshold above stock blocked");
            expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "0", true, 0, fake), "reduced hysteresis blocked");
            check(ThermalControls.change(isolated, "thermal_zone9876", "0", false, 70000, fake).contains("Verified"), "earlier passive cooling is a real verified write");
            check(fake.files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_temp").equals("70000"), "thermal threshold actual command target");
            check(ThermalControls.change(isolated, "thermal_zone9876", "0", true, 5000, fake).contains("Verified"), "longer cooling hysteresis verified");
            check(ThermalControls.restore(isolated, fake).contains("restored and verified"), "original thermal restore verified");
            check(fake.files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_temp").equals("80000")
                    && fake.files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_hyst").equals("2000"), "stock thermal values restored");
            fake.failNext = true; expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "0", false, 65000, fake), "thermal driver failure handled");
            check(fake.files.get("/sys/class/thermal/thermal_zone9876/trip_point_0_temp").equals("80000"), "thermal rollback verified");
            fake.critical = 75000;
            check(ThermalControls.maximumThreshold(isolated, ThermalControls.read(fake).get(0), ThermalControls.read(fake)) == 70000, "current critical trip enforces extra margin");
            expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "0", false, 75000, fake), "critical margin blocks unsafe stock-relative value");
            expect(() -> ThermalControls.restore(isolated, fake), "restore refuses now-incompatible original trip"); fake.critical = 100000;
            fake.grant = false; writes = fake.writes;
            expect(() -> ThermalControls.change(isolated, "thermal_zone9876", "0", false, 65000, fake), "thermal root revocation blocks changes");
            check(fake.writes == writes, "denied thermal access never writes"); fake.grant = true;
            fake.secondPolicy = true; fake.files.put(CpuControls.BASE+"policy9877/scaling_governor", "schedutil");
            fake.failPath = CpuControls.BASE+"policy9877/scaling_governor";
            expect(() -> CpuControls.profile(isolated, "performance", fake), "partial multi-policy failure reported");
            check(fake.files.get(CpuControls.BASE+"policy9876/scaling_governor").equals("schedutil")
                    && fake.files.get(CpuControls.BASE+"policy9877/scaling_governor").equals("schedutil"), "all attempted policy governors rolled back");
            check(HeatMonitor.parse("thermal_zone0|battery|25000").get(0).celsius == 25, "millidegree conversion correct");
            check(!HeatMonitor.parse("thermal_zone0|battery|25000").get(0).cpu(), "battery sensor never labelled CPU");
            check(!HeatMonitor.parse("thermal_zone0|tsens-gpu|45000").get(0).cpu(), "generic/GPU TSENS sensor never guessed as CPU");
            check(HeatMonitor.parse("thermal_zone0|cpu|85").isEmpty(), "ambiguous vendor units not guessed");
            check(HeatMonitor.parse("thermal_zone0|cpu|9999999").isEmpty(), "invalid temperature rejected");
            int validationChecks = checks;
            check(RootAccess.check(), "actual app Magisk grant available");
            check(RootAccess.run("head -c 70000 /dev/zero").exitCode == -1, "oversized actual root output drained and rejected, not deadlocked");
            List<CpuControls.Policy> real = CpuControls.read(RootAccess::run);
            check(!real.isEmpty(), "actual emulator CPU policies found");
            check(CpuControls.commonGovernors(real).contains("performance"), "actual supported performance profile");
            check(CpuControls.profile(getTargetContext(), "performance", RootAccess::run).contains("Verified"), "real multi-policy governor write");
            for (CpuControls.Policy policy : CpuControls.read(RootAccess::run)) check(policy.governor.equals("performance"), "real governor readback " + policy.id);
            CpuControls.Policy first = real.get(0);
            check(CpuControls.limit(getTargetContext(), first.id, false, first.frequencies.get(0), RootAccess::run).contains("Verified"), "real supported maximum cap write");
            check(CpuControls.find(CpuControls.read(RootAccess::run), first.id).max == first.frequencies.get(0), "real maximum cap readback");
            check(CpuControls.restore(getTargetContext(), RootAccess::run).contains("restored and verified"), "real original CPU configuration restored");
            for (CpuControls.Policy original : real) {
                CpuControls.Policy restored = CpuControls.find(CpuControls.read(RootAccess::run), original.id);
                check(restored.governor.equals(original.governor) && restored.min == original.min && restored.max == original.max, "real original values restored " + original.id);
            }
            check(ThermalControls.read(RootAccess::run).isEmpty(), "emulator has no editable/pretend thermal trip points");
            check(HeatMonitor.report(getTargetContext(), 80, RootAccess::run).contains("CPU temperature unavailable"), "actual battery-only emulator sensor honest");
            CoreTuningActivity activity = (CoreTuningActivity)startActivitySync(new Intent(getTargetContext(), CoreTuningActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(await("Root verified · 4 CPU policies"), "real Core settings screen populates");
            check(await("battery · 25.0 °C"), "actual thermal reading displayed");
            check(click("Thermal cooling thresholds / hysteresis"), "thermal control opens");
            check(await("Thermal controls unavailable"), "missing kernel controls explicitly explained");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            android.os.SystemClock.sleep(350);
            check(click("CPU profile · all policies"), "profile picker opens");
            check(await("Performance · highest allowed frequency"), "actual supported profile visible");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "cpu-thermal-controls.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { screenshot.recycle(); }
            runOnMainSync(activity::finish);
            result.putString("stream", "PASS: " + checks + " checks (" + validationChecks + " simulated-driver validation/rollback and "
                    + (checks-validationChecks) + " real-root CPU/sensor/UI). CPU originals restored. No physical thermal trip writes tested.\n");
        } catch (Throwable failure) { result.putString("failure", failure.toString()); result.putString("stream", "FAIL after " + checks + " checks: " + failure + "\n"); }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void expect(Runnable operation, String message) { try { operation.run(); } catch (RuntimeException expected) { checks++; return; } throw new AssertionError(message); }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
    private boolean await(String value) { for (int i = 0; i < 300; i++) { if (find(value) != null) return true; android.os.SystemClock.sleep(100); } return false; }
    private AccessibilityNodeInfo find(String value) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(); if (root == null) return null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(value)) if (node.getText() != null && node.getText().toString().contains(value)) return node;
        return null;
    }
    private boolean click(String value) {
        AccessibilityNodeInfo node = find(value); while (node != null && !node.isClickable()) node = node.getParent();
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
