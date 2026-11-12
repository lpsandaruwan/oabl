package com.softiva.oabl;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * The Battery page: the car's own battery readings in one place, in three cards (charge level, the
 * charger, and what the car reports about the pack). A reading the car does not give says so instead
 * of showing a zero. Fixed page, no scrolling; refreshes while open.
 */
public final class BatteryActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private LauncherTheme.Palette palette;
    private LinearLayout body;
    private String drawn = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { load(); handler.postDelayed(this, 1500); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.root);
        root.setPadding(dp(24), dp(12), dp(24), dp(16));
        setContentView(root);
        ImmersiveWindow.apply(getWindow(), root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MusicIconView back = new MusicIconView(this, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Back", this::finish);
        back.setBackground(LauncherTheme.dockItem(this, palette));
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(text("Battery", 28, palette.ink, true));
        titles.addView(text("What the car reports about its battery", 13, palette.muted, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView energy = text("Drives and energy", 16, palette.ink, true);
        energy.setGravity(Gravity.CENTER);
        energy.setPadding(dp(18), 0, dp(18), 0);
        energy.setBackground(LauncherTheme.raised(this, palette, 14));
        energy.setOnClickListener(v -> startActivity(new android.content.Intent(this, EnergyActivity.class)));
        header.addView(energy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        root.addView(header);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        fill.topMargin = dp(12);
        root.addView(body, fill);
    }

    @Override protected void onResume() { super.onResume(); EnergyClient.link(this).apply(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { loader.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void load() {
        if (!loading.compareAndSet(false, true)) return;
        loader.execute(() -> {
            HomeEnergySnapshot snapshot = null;
            try { snapshot = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && getIntent().hasExtra("demo") ? demo() : HomeEnergySnapshot.capture(getApplicationContext()); } catch (RuntimeException ignored) { }
            final HomeEnergySnapshot result = snapshot;
            handler.post(() -> { loading.set(false); render(result); });
        });
    }

    /** Debug builds only: sample readings (am start -n .../.BatteryActivity --ez demo true), for checking the layout. */
    private static HomeEnergySnapshot demo() {
        HomeEnergySnapshot s = new HomeEnergySnapshot();
        s.car = true; s.connected = true; s.live = true; s.charging = true; s.ready = false; s.linkState = "Connected · Bluetooth · encrypted";
        BatteryFacts f = new BatteryFacts();
        f.soc = 75; f.rangeKm = 260; f.rangeStandardKm = 334; f.rangeNedcKm = 298; f.usableKwh = 42; f.reservePercent = 10; f.charging = true;
        f.chargeKw = 2.7; f.chargeVolts = 213; f.chargeAmps = 12.5; f.chargeTarget = 100; f.minutesToFull = 293;
        f.healthPercent = 100; f.twelveVolt = 13.85; f.heatCool = 0; f.socCredibility = 0; f.wallKwhPerPoint = 0.47;
        s.battery = f;
        return s;
    }

    private void render(HomeEnergySnapshot snapshot) {
        if (isFinishing() || body == null) return;
        BatteryFacts facts = snapshot == null ? null : snapshot.battery;
        String live = snapshot == null ? "?" : snapshot.linkWords();
        String key = facts == null ? "none" + live : Facts.key(facts) + live;
        if (key.equals(drawn) && body.getChildCount() > 0) return;
        drawn = key;
        body.removeAllViews();
        if (facts == null) { body.addView(message(snapshot == null ? "Loading…" : "No car link: the battery readings come from the car.")); return; }
        boolean carLive = snapshot.live;
        LinearLayout[] cards = {card(), card(), card()};
        fillLevel(cards[0], facts, carLive);
        fillCharger(cards[1], facts);
        fillPack(cards[2], facts);
        for (int i = 0; i < cards.length; i++) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) size.setMarginStart(dp(14));
            body.addView(cards[i], size);
        }
    }

    // --- cards ---------------------------------------------------------------------------------

    private void fillLevel(LinearLayout card, BatteryFacts f, boolean live) {
        title(card, "CHARGE");
        TextView big = text(Double.isFinite(f.soc) ? Math.round(f.soc) + " %" : "—", 64, palette.ink, true);
        big.setPadding(0, dp(6), 0, 0);
        card.addView(big);
        card.addView(text(!live ? "No fresh car reading" : f.charging ? "Charging" : "Not charging", 15, f.charging ? palette.accent : palette.muted, true));
        card.addView(bar(f));
        spacer(card);
        double left = Double.isFinite(f.soc) && EnergyModel.usable(f.usableKwh) ? f.usableKwh * f.soc / 100 : Double.NaN;
        row(card, "Usable left", Double.isFinite(left) ? String.format(Locale.US, "%.1f kWh", left) : "—", "of " + (EnergyModel.usable(f.usableKwh) ? EnergyDialogs.kwh(f.usableKwh) : "a capacity you have not set"));
        row(card, "Keep above", Double.isFinite(f.reservePercent) ? Math.round(f.reservePercent) + " %" : "—", "your reserve");
        row(card, "Range", km(f.rangeKm), "the car's live estimate");
        row(card, "Standard cycle", km(f.rangeStandardKm), null);
        row(card, "NEDC cycle", km(f.rangeNedcKm), null);
    }

    private void fillCharger(LinearLayout card, BatteryFacts f) {
        title(card, "CHARGER");
        TextView big = text(f.charging && Double.isFinite(f.chargeKw) ? String.format(Locale.US, "%.1f kW", f.chargeKw) : f.charging ? "Charging" : "Not charging", 46, palette.ink, true);
        big.setPadding(0, dp(14), 0, 0);
        card.addView(big);
        card.addView(text(f.charging ? "AC charging at the wall side" : "Plug in to see the charger", 15, palette.muted, false));
        spacer(card);
        if (f.charging) {
            row(card, "Wall side", Double.isFinite(f.chargeVolts) && Double.isFinite(f.chargeAmps)
                    ? String.format(Locale.US, "%.0f V × %.1f A", f.chargeVolts, f.chargeAmps) : "—", "the charger's voltage and current");
            row(card, "Charging to", Double.isFinite(f.chargeTarget) ? Math.round(f.chargeTarget) + " %" : "—", "the car's target");
            String left = CarChargeTime.remaining(f.minutesToFull);
            row(card, "Time to full", left.isEmpty() ? "—" : left, "the car's estimate");
            String second = CarChargeTime.remaining(f.bmsMinutesToFull);
            if (!second.isEmpty()) row(card, "Second estimate", second, "from the battery manager");
        }
        row(card, "Wall energy per 1 %", Double.isFinite(f.wallKwhPerPoint) ? String.format(Locale.US, "%.2f kWh", f.wallKwhPerPoint) : "Still learning", "from your measured charges");
        double efficiency = f.chargingEfficiency();
        row(card, "Charging efficiency", Double.isFinite(efficiency) ? "≈ " + Math.round(efficiency * 100) + " %" : "—", Double.isFinite(efficiency) ? "at " + EnergyDialogs.kwh(f.usableKwh) : "needs a capacity and measured charges");
        row(card, "Charged energy", Double.isFinite(f.chargedEnergy) ? String.format(Locale.US, "%.1f", f.chargedEnergy) : "Not reported", "the car's own counter");
    }

    private void fillPack(LinearLayout card, BatteryFacts f) {
        title(card, "THE CAR REPORTS");
        row(card, "Battery health", Double.isFinite(f.healthPercent) ? String.format(Locale.US, "%.0f %%", f.healthPercent) : "Not reported", "as the car states it, not measured here");
        row(card, "Capacity", EnergyModel.usable(f.usableKwh) ? EnergyDialogs.kwh(f.usableKwh) : "Not set", "your setting; the car does not report it");
        row(card, "12 V battery", Double.isFinite(f.twelveVolt) ? String.format(Locale.US, "%.1f V", f.twelveVolt) : "Not reported", null);
        row(card, "Pack voltage", Double.isFinite(f.packVoltage) ? String.format(Locale.US, "%.0f", f.packVoltage) : "Not reported", "this car does not give it");
        row(card, "Pack current", Double.isFinite(f.packCurrent) ? String.format(Locale.US, "%.0f", f.packCurrent) : "Not reported", "this car does not give it");
        row(card, "Pack power", Double.isFinite(f.packPower) ? String.format(Locale.US, "%.0f", f.packPower) : "Not reported", "this car does not give it");
        row(card, "Heating / cooling", Double.isFinite(f.heatCool) ? "Code " + Math.round(f.heatCool) : "Not reported", "the car's state code");
        row(card, "Reading confidence", Double.isFinite(f.socCredibility) ? "Code " + Math.round(f.socCredibility) : "Not reported", "the car's state code");
    }

    // --- pieces ---------------------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LauncherTheme.rounded(this, palette.light ? 0xffffffff : 0xff161d25, 20, 0, 0));
        card.setPadding(dp(22), dp(16), dp(22), dp(10));
        return card;
    }

    private TextView message(String value) {
        TextView view = text(value, 20, palette.muted, false);
        view.setGravity(Gravity.CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private void title(LinearLayout card, String value) {
        TextView title = text(value, 14, palette.muted, true);
        title.setLetterSpacing(0.14f);
        card.addView(title);
    }

    private void spacer(LinearLayout card) {
        card.addView(new View(this), new LinearLayout.LayoutParams(1, dp(14)));
    }

    /** One line: what it is and a quiet note under it on the left, the value on the right. */
    private void row(LinearLayout card, String label, String value, String note) {
        View rule = new View(this);
        rule.setBackgroundColor(palette.light ? 0x10142e38 : 0x12ffffff);
        card.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(label, 16, palette.ink, false));
        if (note != null) { TextView small = text(note, 13, palette.muted, false); small.setMaxLines(1); small.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(small); }
        line.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean missing = value.equals("—") || value.startsWith("Not ") || value.equals("Still learning");
        TextView shown = text(value, 18, missing ? palette.muted : palette.ink, !missing);
        shown.setGravity(Gravity.END);
        shown.setPadding(dp(10), 0, 0, 0);
        line.addView(shown);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        card.addView(line, size);
    }

    /** The battery bar with the reserve marked, as on Home. */
    private View bar(BatteryFacts f) {
        final float soc = Double.isFinite(f.soc) ? (float) Math.max(0, Math.min(100, f.soc)) : 0f;
        final float reserve = Double.isFinite(f.reservePercent) ? (float) f.reservePercent : -1f;
        View view = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                float w = getWidth(), h = getHeight(), r = h / 2;
                paint.setColor(palette.light ? 0x1a142e38 : 0x24ffffff);
                canvas.drawRoundRect(0, 0, w, h, r, r, paint);
                paint.setColor(soc <= (reserve >= 0 ? reserve : 10) ? 0xffd9534f : palette.accent);
                if (soc > 0) canvas.drawRoundRect(0, 0, Math.max(h, w * soc / 100), h, r, r, paint);
                if (reserve > 0) { paint.setColor(palette.ink); canvas.drawRect(w * reserve / 100 - 1, -2, w * reserve / 100 + 1, h + 2, paint); }
            }
        };
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        size.topMargin = dp(14);
        view.setLayoutParams(size);
        return view;
    }

    private static String km(double value) { return Double.isFinite(value) ? Math.round(value) + " km" : "—"; }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** A cheap fingerprint so the page only rebuilds when something shown changed. */
    private static final class Facts {
        static String key(BatteryFacts f) {
            return String.format(Locale.US, "%.0f|%.0f|%.0f|%.0f|%b|%.1f|%.0f|%.1f|%.0f|%.0f|%.1f|%.0f|%.0f|%.0f|%.0f|%.2f|%.1f|%.0f|%.0f|%.0f",
                    f.soc, f.rangeKm, f.rangeStandardKm, f.rangeNedcKm, f.charging, f.chargeKw, f.chargeVolts, f.chargeAmps, f.chargeTarget,
                    f.minutesToFull, f.twelveVolt, f.healthPercent, f.packVoltage, f.packCurrent, f.packPower, f.wallKwhPerPoint,
                    f.chargedEnergy, f.heatCool, f.socCredibility, f.bmsMinutesToFull);
        }
    }
}
