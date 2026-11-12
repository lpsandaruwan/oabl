package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight, foreground-only root tuning. No background boot writes or thermal bypass. */
public final class CoreTuningActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<CpuControls.Policy> policies = new ArrayList<>();
    private List<ThermalControls.Trip> trips = new ArrayList<>();
    private LauncherTheme.Palette palette;
    private TextView status, heat;
    private LinearLayout cpuList;
    private Button profiles, limits, thermal;
    private boolean busy, visible, monitoring;
    private int warning;
    private AutoThemeWatcher themeWatcher;
    private final Runnable tick = () -> { if (visible && monitoring && !busy) refreshHeat(); };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        android.content.SharedPreferences prefs = getSharedPreferences("core_monitor", MODE_PRIVATE);
        warning = prefs.getInt("warning", 80); monitoring = prefs.getBoolean("monitoring", false);
        themeWatcher = new AutoThemeWatcher(this, () -> {
            LauncherTheme.Palette next = LauncherTheme.resolve(this, new LauncherPreferences(this));
            if (next.light != palette.light || next.accent != palette.accent) recreate();
        });
        LinearLayout root = column(); root.setPadding(dp(24), dp(12), dp(24), dp(16)); root.setBackgroundColor(palette.root);
        LinearLayout header = new LinearLayout(this);
        TextView title = text("Core settings", 28); title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        header.addView(button("Back", this::finish)); root.addView(header);
        status = text("Checking root access and supported controls…", 15); root.addView(status);
        LinearLayout columns = new LinearLayout(this);
        LinearLayout left = card(); LinearLayout right = card();
        left.addView(text("CPU · governors & limits", 22));
        profiles = button("CPU profile · all policies", this::pickProfile); left.addView(profiles);
        limits = button("Per-policy governor / frequency limits", this::pickPolicy); left.addView(limits);
        left.addView(button("Restore original CPU settings", () -> confirm("Restore CPU settings?", "Restores the governors and limits saved before your first change on this device.",
                () -> operation(() -> CpuControls.restore(this, RootAccess::run)))));
        cpuList = column(); left.addView(cpuList);
        left.addView(text("Changes apply now, not automatically at boot. Higher minimum clocks or Performance can increase heat. No overclocking or voltage controls.", 15));
        right.addView(text("Heat · sensors & thermal controls", 22));
        thermal = button("Thermal cooling thresholds / hysteresis", this::pickTrip); right.addView(thermal);
        right.addView(button("Restore original thermal settings", () -> confirm("Restore thermal settings?", "Restores saved passive trip thresholds and hysteresis. Critical/shutdown values are never modified.",
                () -> operation(() -> ThermalControls.restore(this, RootAccess::run)))));
        Switch monitor = new Switch(this); monitor.setText("Monitor temperatures · every 5 seconds while open"); monitor.setTextColor(palette.ink); monitor.setChecked(monitoring);
        monitor.setPadding(dp(8), dp(8), dp(8), dp(8));
        monitor.setOnCheckedChangeListener((button, checked) -> {
            monitoring = checked; prefs.edit().putBoolean("monitoring", checked).apply(); ui.removeCallbacks(tick);
            if (checked) { if (!busy) refreshHeat(); else ui.postDelayed(tick, 5000); }
        }); right.addView(monitor);
        Button threshold = button("Local warning · " + warning + " °C", () -> {});
        threshold.setOnClickListener(v -> dialogBuilder().setTitle("Local heat warning · not a kernel trip")
                .setItems(new String[]{"65 °C", "70 °C", "75 °C", "80 °C", "85 °C", "90 °C", "95 °C"}, (dialog, which) -> {
                    warning = 65+which*5; prefs.edit().putInt("warning", warning).apply(); threshold.setText("Local warning · " + warning + " °C");
                    if (!busy) refreshHeat();
                }).setNegativeButton("Cancel", null).show()); right.addView(threshold);
        heat = text("Reading available sensors…", 16); right.addView(heat);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); lp.setMargins(0, dp(12), dp(8), 0);
        columns.addView(scroll(left), lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); rp.setMargins(dp(8), dp(12), 0, 0);
        columns.addView(scroll(right), rp);
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(button("Refresh device readings", this::refresh));
        setContentView(root); ImmersiveWindow.apply(getWindow(), root); refresh();
    }
    @Override protected void onResume() { super.onResume(); visible = true; themeWatcher.start(); DevicePerformance.apply(this, getWindow()); if (monitoring) ui.postDelayed(tick, 5000); }
    @Override protected void onPause() { visible = false; ui.removeCallbacks(tick); themeWatcher.stop(); super.onPause(); }
    @Override protected void onDestroy() { ui.removeCallbacks(tick); worker.shutdown(); super.onDestroy(); }
    private void controls(boolean available) { profiles.setEnabled(available && !CpuControls.commonGovernors(policies).isEmpty()); limits.setEnabled(available && !policies.isEmpty()); thermal.setEnabled(available); }
    private void refresh() {
        if (busy || ActivityState.isGone(this)) return; busy = true; controls(false);
        worker.execute(() -> {
            List<CpuControls.Policy> nextCpu = new ArrayList<>(); List<ThermalControls.Trip> nextTrips = new ArrayList<>(); String report; boolean verified = false;
            try { nextCpu = CpuControls.read(RootAccess::run); nextTrips = ThermalControls.read(RootAccess::run); verified = true; report = "Root verified · " + nextCpu.size() + " CPU policies · " + nextTrips.size() + " thermal trips. Critical/shutdown protections locked."; }
            catch (RuntimeException failed) { report = failed.getMessage(); }
            final List<CpuControls.Policy> cpu = nextCpu; final List<ThermalControls.Trip> thermalTrips = nextTrips; final String summary = report;
            final boolean access = verified;
            final String temperatures = HeatMonitor.report(this, warning, RootAccess::run);
            ui.post(() -> {
                if (ActivityState.isGone(this)) return;
                policies = cpu; trips = thermalTrips; status.setText(summary); cpuList.removeAllViews();
                for (CpuControls.Policy policy : policies) { TextView label = text(policy.summary(), 16); label.setPadding(0, dp(12), 0, dp(12)); cpuList.addView(label); }
                if (policies.isEmpty()) cpuList.addView(text("No valid CPU policies exposed. Controls unavailable.", 16));
                heat.setText(temperatures); busy = false; controls(access); schedule();
            });
        });
    }
    private void refreshHeat() {
        if (busy || ActivityState.isGone(this)) return; busy = true;
        worker.execute(() -> {
            String report = HeatMonitor.report(this, warning, RootAccess::run);
            ui.post(() -> { if (ActivityState.isGone(this)) return; heat.setText(report); busy = false; schedule(); });
        });
    }
    private void schedule() { ui.removeCallbacks(tick); if (visible && monitoring) ui.postDelayed(tick, 5000); }
    private void operation(Callable<String> action) {
        if (busy) { message("Device operation in progress", "Wait for the current reading or change to finish."); return; }
        busy = true; controls(false); status.setText("Applying and verifying…");
        worker.execute(() -> {
            String report; try { report = action.call(); } catch (Exception failed) { report = failed.getMessage(); }
            final String result = report;
            ui.post(() -> { if (ActivityState.isGone(this)) return; busy = false; message("Core setting result", result); refresh(); });
        });
    }
    private void pickProfile() {
        List<String> governors = CpuControls.commonGovernors(policies);
        if (governors.isEmpty()) { message("CPU profiles unavailable", "No common writable governor supported by every CPU policy."); return; }
        String[] labels = new String[governors.size()]; for (int i = 0; i < labels.length; i++) labels[i] = profileName(governors.get(i));
        dialogBuilder().setTitle("CPU profile · all policies").setItems(labels, (dialog, which) -> {
            String governor = governors.get(which);
            confirm("Apply " + profileName(governor) + "?", "Changes only the governor for every CPU policy. Supported frequencies and thermal protection stay intact. Performance may increase temperature and cause later throttling. Originals are saved for restore.",
                    () -> operation(() -> CpuControls.profile(this, governor, RootAccess::run)));
        }).setNegativeButton("Cancel", null).show();
    }
    static String profileName(String value) {
        if (value.equals("performance")) return "Performance · highest allowed frequency";
        if (value.equals("schedutil")) return "Responsive · schedutil";
        if (value.equals("conservative")) return "Conservative · gradual scaling";
        if (value.equals("powersave")) return "Power saving · lowest allowed frequency";
        return value;
    }
    private void pickPolicy() {
        String[] labels = new String[policies.size()]; for (int i = 0; i < labels.length; i++) labels[i] = policies.get(i).summary();
        dialogBuilder().setTitle("Select CPU policy / core group").setItems(labels, (dialog, which) -> {
            CpuControls.Policy policy = policies.get(which);
            dialogBuilder().setTitle(policy.id + " · cores " + policy.cores)
                    .setItems(new String[]{"Governor · " + policy.governor, "Minimum · " + CpuControls.frequency(policy.min), "Maximum · " + CpuControls.frequency(policy.max)}, (menu, option) -> {
                        if (option == 0) pickGovernor(policy); else pickFrequency(policy, option == 1);
                    }).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Cancel", null).show();
    }
    private void pickGovernor(CpuControls.Policy policy) {
        if (!policy.governorWritable || policy.governors.isEmpty()) { message("Governor unavailable", "This policy does not expose a writable governor."); return; }
        dialogBuilder().setTitle(policy.id + " governor").setItems(policy.governors.toArray(new String[0]), (dialog, which) -> {
            String value = policy.governors.get(which);
            confirm("Apply governor?", policy.id + " → " + value + ". Affects cores " + policy.cores + ". Higher clocks can increase heat.",
                    () -> operation(() -> CpuControls.governor(this, policy.id, value, RootAccess::run)));
        }).setNegativeButton("Cancel", null).show();
    }
    private void pickFrequency(CpuControls.Policy policy, boolean minimum) {
        List<Long> values = new ArrayList<>();
        if (minimum ? policy.minWritable : policy.maxWritable) for (long value : policy.frequencies)
            if (minimum ? value <= policy.max : value >= policy.min) values.add(value);
        if (values.isEmpty()) { message("Frequency limits unavailable", "No writable limit with an advertised supported-frequency table. Arbitrary frequencies are not accepted."); return; }
        String[] labels = new String[values.size()]; for (int i = 0; i < labels.length; i++) labels[i] = CpuControls.frequency(values.get(i));
        dialogBuilder().setTitle(policy.id + (minimum ? " minimum frequency" : " maximum frequency")).setItems(labels, (dialog, which) -> {
            long value = values.get(which);
            confirm("Apply CPU frequency limit?", policy.id + (minimum ? " minimum → " : " maximum → ") + CpuControls.frequency(value)
                            + ". Raising the minimum increases sustained load/heat. Lowering the maximum can reduce heat but also video performance. This applies now, not automatically at boot.",
                    () -> operation(() -> CpuControls.limit(this, policy.id, minimum, value, RootAccess::run)));
        }).setNegativeButton("Cancel", null).show();
    }
    private void pickTrip() {
        if (trips.isEmpty()) { message("Thermal controls unavailable", "This kernel exposes no thermal trip-point interface. Root cannot create missing vendor controls. Monitoring remains available; no fake temperature settings are shown."); return; }
        String[] labels = new String[trips.size()]; for (int i = 0; i < labels.length; i++) labels[i] = trips.get(i).label();
        dialogBuilder().setTitle("Kernel thermal trip points").setItems(labels, (dialog, which) -> {
            ThermalControls.Trip trip = trips.get(which);
            if (!trip.editable()) { message("Protected / read-only thermal trip", "Only writable passive cooling trips can be tuned. Active, hot and critical/shutdown trips remain locked."); return; }
            dialogBuilder().setTitle(trip.label()).setItems(new String[]{"Passive cooling threshold · " + trip.temp/1000d + " °C", "Hysteresis · " + (trip.hyst >= 0 ? trip.hyst/1000d + " °C" : "Unavailable")},
                    (menu, option) -> pickThermalValue(trip, option == 1)).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Cancel", null).show();
    }
    private void pickThermalValue(ThermalControls.Trip trip, boolean hysteresis) {
        List<Long> values = new ArrayList<>();
        if (hysteresis ? trip.hystWritable && trip.hyst >= 0 : trip.tempWritable) {
            if (hysteresis) {
                long minimum = ThermalControls.minimumHysteresis(this, trip);
                for (long value : new long[]{0, 2000, 5000, 10000, 15000, 20000}) if (value >= minimum && value < trip.temp-15000) values.add(value);
            } else {
                long maximum = ThermalControls.maximumThreshold(this, trip, trips);
                for (long value = 30000; value <= maximum; value += 5000) if (trip.hyst < 0 || value-trip.hyst >= 15000) values.add(value);
            }
        }
        if (values.isEmpty()) { message("Thermal control unavailable", "Read-only or no values within conservative bounds. Original settings can be restored separately."); return; }
        String[] labels = new String[values.size()]; for (int i = 0; i < labels.length; i++) labels[i] = values.get(i)/1000d + " °C";
        dialogBuilder().setTitle(hysteresis ? "Passive cooling hysteresis" : "Passive cooling threshold").setItems(labels, (dialog, which) -> {
            long value = values.get(which);
            confirm("Change kernel thermal setting?", trip.sensor + " / " + trip.zone + " trip " + trip.index + " → " + value/1000d
                            + " °C. This is a real kernel change: earlier cooling or longer cooling can reduce performance. Only thresholds at/below the saved stock value and non-reduced hysteresis are allowed. Critical/shutdown protection stays unchanged. Driver behavior is vendor-specific; readback does not prove cooling effectiveness.",
                    () -> operation(() -> ThermalControls.change(this, trip.zone, trip.index, hysteresis, value, RootAccess::run)));
        }).setNegativeButton("Cancel", null).show();
    }
    private AlertDialog.Builder dialogBuilder() { return new AlertDialog.Builder(this, palette.light ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert); }
    private void message(String title, String body) { dialogBuilder().setTitle(title).setMessage(body).setPositiveButton("Done", null).show(); }
    private void confirm(String title, String body, Runnable action) { dialogBuilder().setTitle(title).setMessage(body).setPositiveButton("Apply", (dialog, button) -> action.run()).setNegativeButton("Cancel", null).show(); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card() { LinearLayout layout = column(); layout.setPadding(dp(18), dp(16), dp(18), dp(16)); layout.setBackground(LauncherTheme.glass(this, palette)); return layout; }
    private ScrollView scroll(View view) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(view); return scroll; }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextColor(palette.ink); view.setTextSize(size); view.setPadding(0, dp(5), 0, dp(5)); return view; }
    private Button button(String label, Runnable action) { Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(palette.ink); button.setBackground(LauncherTheme.dockItem(this, palette)); button.setPadding(dp(12), dp(6), dp(12), dp(6)); button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run()); return button; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
