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
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/**
 * The Energy page: Current drive, All time, and Charging and parked. A fixed screen with no page
 * scrolling; only the lists scroll, inside their own cards. Every number says what it rests on:
 * entered or estimated, the benchmark it's compared with, learned or benchmark-based, measured or
 * modelled, OABL's own or the car's (typed from its displays).
 */
public final class EnergyActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LauncherTheme.Palette palette;
    private LinearLayout body;
    /** Opens the page on a tab: {@link #DRIVE}, {@link #ALL_TIME} or {@link #CHARGING}. */
    static final String EXTRA_TAB = "tab";
    static final int DRIVE = 0, ALL_TIME = 1, CHARGING = 2, TODAY = 3, WEEK = 4, MONTH = 5;
    private TextView tabDrive, tabAll, tabCharging, tabToday, tabWeek, tabMonth, benchmark;
    private int tab, drawnTab = -1;
    private long interactingUntil;
    private final java.util.Map<Integer, int[]> scrollPositions = new java.util.HashMap<>();
    private final Runnable changed = this::render;
    private final Runnable settleRefresh = this::render;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 5_000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        tab = state != null ? state.getInt("tab", DRIVE) : getIntent().getIntExtra(EXTRA_TAB, DRIVE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.root);
        root.setPadding(dp(24), dp(12), dp(24), dp(16));
        setContentView(root);
        ImmersiveWindow.apply(getWindow(), root);

        LinearLayout header = row();
        MusicIconView back = new MusicIconView(this, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Back", this::finish);
        back.setBackground(LauncherTheme.dockItem(this, palette));
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        // Narrow (960 wide): a smaller title and tighter tabs, so the header still fits on one line.
        boolean narrow = narrow();
        // Six quick tabs: on the narrow window the tabs need the title's room (Back names the page).
        TextView title = text("Energy", 24, palette.ink, true);
        title.setPadding(dp(10), 0, dp(16), 0);
        if (!narrow) header.addView(title);
        tabDrive = tab(narrow ? "Drive" : "Current drive", DRIVE);
        tabToday = tab("Today", TODAY);
        tabWeek = tab("Week", WEEK);
        tabMonth = tab("Month", MONTH);
        tabAll = tab("All time", ALL_TIME);
        tabCharging = tab("Charges", CHARGING);
        TextView[] tabs = {tabDrive, tabToday, tabWeek, tabMonth, tabAll, tabCharging};
        int[] widths = narrow ? new int[]{64, 64, 60, 68, 76, 80} : new int[]{150, 96, 96, 104, 112, 124};
        LinearLayout tabStrip = row();
        header.addView(tabStrip, new LinearLayout.LayoutParams(0, dp(56), 1));
        for (int i = 0; i < tabs.length; i++) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(52), widths[i]);
            if (i > 0) size.setMarginStart(dp(narrow ? 4 : 8));
            tabStrip.addView(tabs[i], size);
        }

        benchmark = text("", 14, palette.muted, true);
        benchmark.setGravity(Gravity.CENTER_VERTICAL);
        benchmark.setPadding(dp(12), 0, dp(12), 0);
        header.addView(benchmark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        TextView vehicle = button(narrow ? "Vehicle" : "Vehicle and energy", false, () -> EnergyDialogs.vehicle(this, palette, changed));
        header.addView(vehicle, new LinearLayout.LayoutParams(dp(narrow ? 96 : 164), dp(52)));
        root.addView(header);

        body = row();
        body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        fill.topMargin = dp(12);
        root.addView(body, fill);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
    }

    @Override protected void onResume() {
        super.onResume();
        EnergyJournal.get(this).ensureRunning();
        EnergyJournal.get(this).observe(changed);
        EnergyClient.link(this).observe(changed);
        EnergyClient.link(this).apply();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        EnergyJournal.get(this).unobserve(changed);
        EnergyClient.link(this).unobserve(changed);
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(settleRefresh);
        super.onPause();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        interactingUntil=android.os.SystemClock.uptimeMillis()+600;
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDestroy() {
        loader.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }

    private boolean narrow() { return getResources().getConfiguration().screenWidthDp < 900; }
    /**
     * A short window (1920 × 720, about 420 dp once the system bars are taken off; the box's 1920 × 970
     * is about 570): optional lines give way so the cards still fit without scrolling.
     */
    private boolean shortWindow() { return getResources().getConfiguration().screenHeightDp < 500; }

    /** The page's data, read by :energy in one background request; the views are drawn from it. */
    private CarEnergyPage page;
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean renderAgain;

    private void render() {
        if(EnergyDataActivity.BUSY.get()){
            body.removeAllViews();body.addView(text("Saving and verifying energy backup…",22,palette.ink,true));return;
        }
        if (!loading.compareAndSet(false, true)) { renderAgain = true; return; }
        int wanted = tab;
        loader.execute(() -> {
            CarEnergyPage fetched = null;
            try { fetched = CarEnergyPage.capture(this, wanted); } catch (RuntimeException unavailable) { /* :energy restarting; the next change retries */ }
            CarEnergyPage result = fetched;
            handler.post(() -> {
                loading.set(false);
                if (ActivityState.isGone(this)) return;
                if (result != null && wanted == tab) {
                    page = result;
                    handler.removeCallbacks(settleRefresh);
                    if (wanted != drawnTab || android.os.SystemClock.uptimeMillis() >= interactingUntil) draw();
                    else handler.postDelayed(settleRefresh,interactingUntil-android.os.SystemClock.uptimeMillis()+50);
                }
                if (renderAgain || wanted != tab) { renderAgain = false; render(); }
            });
        });
    }

    private void draw() {
        EnergyJournal.Status status;
        if(page.expectsCarBattery()){status=new EnergyJournal.Status();status.profile=page.profile();}else status=EnergyJournal.get(this).status();
        tabDrive.setText(page.liveCharging()?(narrow()?"Now":"Charging now"):(narrow()?"Drive":"Current drive"));
        styleTab(tabDrive, tab == DRIVE);
        styleTab(tabAll, tab == ALL_TIME);
        styleTab(tabCharging, tab == CHARGING);
        styleTab(tabToday, tab == TODAY);
        styleTab(tabWeek, tab == WEEK);
        styleTab(tabMonth, tab == MONTH);
        benchmark.setText("Compared with " + status.profile.benchmarkLabel());
        // Three tabs leave no room for it beside them below 1150 dp; every result still names it.
        // Six tabs leave no room for it in the header; every result still names the benchmark.
        benchmark.setVisibility(View.GONE);
        rememberScroll();
        body.removeAllViews();
        if (page.expectsCarBattery() && tab != DRIVE) renderCarHistory(tab);
        else if (tab == ALL_TIME) renderAllTime(status);
        else if (tab == CHARGING) renderCharging(status);
        else if (tab == TODAY || tab == WEEK || tab == MONTH) renderPeriod(status, tab);
        else renderDrive(status);
        fitCards();
        drawnTab = tab;
        restoreScroll();
    }

    // ---- Current drive ----

    private void renderDrive(EnergyJournal.Status s) {
        boolean narrow = narrow();
        CarEnergyPage link = page;
        boolean carBattery = link.expectsCarBattery(), live = link.hasFreshLive();
        // Battery: what was entered, and what is estimated since.
        LinearLayout battery = card();
        battery.addView(caption(carBattery ? (live ? "BATTERY" : "BATTERY · NO CAR DATA")
                : s.recovery != null ? "BATTERY · UNFINISHED DRIVE" : s.active ? "BATTERY · THIS DRIVE" : "BATTERY"));
        String entered = carBattery ? (live ? EnergyModel.percent(Math.round(link.liveSoc())) + " %" : "—")
                : s.lastReading == null ? "—" : EnergyModel.percent(s.lastReading.percent) + " %";
        TextView big = text(entered, carBattery ? (narrow ? 52 : 88) : narrow ? 44 : 60, palette.ink, false);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setFontFeatureSettings("tnum");
        big.setMaxLines(1);
        if(android.os.Build.VERSION.SDK_INT>=26)big.setAutoSizeTextTypeUniformWithConfiguration(narrow?32:52,carBattery?(narrow?52:88):60,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        battery.addView(big);
        battery.addView(text(carBattery ? (live ? "From car · updated " + link.liveAgeSeconds(CarSignal.SOC) + "s ago" : "No fresh car reading")
                : s.lastReading == null ? "No battery reading yet"
                : "Entered " + EnergyDialogs.when(this, s.lastReading.time) + " · tap to edit", 14, palette.muted, false));
        if (!carBattery && s.lastReading != null) {
            EnergyStore.CheckIn last = s.lastReading;
            big.setOnClickListener(v -> EnergyDialogs.reading(this, palette, last, changed));
            big.setContentDescription(entered + ", entered. Tap to edit or remove this reading.");
        }
        EnergyBarView bar = new EnergyBarView(this, null);
        bar.set(carBattery ? (live ? (float) link.liveSoc() : -1) : s.lastReading == null ? -1 : (float) s.lastReading.percent,
                carBattery || Double.isNaN(s.estimatedPercent) || s.kmSinceReading < 0.1 ? -1 : (float) s.estimatedPercent,
                (float) s.profile.reservePercent, palette.accent, palette.light ? 0x1f13202a : 0x24ffffff, palette.muted);
        if (carBattery) bar.setContentDescription(live ? "Battery " + entered + " from car" : "Waiting for car battery reading");
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
        barSize.topMargin = dp(12);
        battery.addView(bar, barSize);
        if (!carBattery && s.lastReading != null && s.kmSinceReading >= 0.1) {
            battery.addView(line("Estimated now", "≈ " + EnergyModel.percent(Math.round(s.estimatedPercent * 10) / 10.0) + " %",
                    "since " + EnergyDialogs.clock(this, s.lastReading.time) + " · " + km1(s.kmSinceReading) + " driven"));
        }
        if (!carBattery && s.lastReading != null) {
            battery.addView(line("To " + EnergyModel.percent(s.profile.reservePercent) + " % reserve",
                    "≈ " + Math.round(s.kmSinceReading >= 0.1 ? s.rangeKm : s.reserveRangeKm) + " km",
                    s.learned ? "learned from your drives" : s.profile.benchmark().label + "-based until 10 points are learned"));
        }
        if (carBattery) {
            if (live && !Double.isNaN(link.liveRangeKm())) {
                double standard = HomeEnergySnapshot.validRange(link.liveValue(CarSignal.RANGE_STANDARD)), nedc = HomeEnergySnapshot.validRange(link.liveValue(CarSignal.RANGE_NEDC));
                battery.addView(text(Math.round(link.liveRangeKm())+" km",narrow?36:48,palette.ink,false));
                battery.addView(text("Range",16,palette.muted,false));
                String references=HomeEnergySnapshot.references(standard,nedc);
                if(!references.isEmpty())battery.addView(text(references,14,palette.muted,false));
            }
            if (live && link.liveCharging()) {
                double target = link.liveValue(CarSignal.CHARGE_TARGET);
                if (Double.isFinite(target)) bar.setTarget((float) target);
                battery.addView(line("Charging", chargingHeadline(link), chargingNote(link)));
            } else if (live) battery.addView(text("Updates automatically", 14, palette.muted, false));
            else battery.addView(empty("No car data. Check the connection, or choose Manual in Vehicle data source."));
            CarBatteryHealth.Status twelve = CarBatteryHealth.status(link.liveValue(CarSignal.V12_RAW), link.liveValue(CarSignal.READY), link.liveValue(CarSignal.CHARGING));
            if (live && twelve != null) {
                TextView v12 = text(twelve.text(), 13, twelve.low ? 0xffff9d91 : palette.muted, twelve.low);
                v12.setPadding(0, dp(6), 0, 0);
                battery.addView(v12);
            }
        } else if (s.lastReading == null) {
            battery.addView(divider());
            battery.addView(caption("HOW IT WORKS"));
            battery.addView(step("1", "Enter the battery % shown in the car before you drive."));
            battery.addView(step("2", "Drive. GPS records the distance, whatever app is in front."));
            battery.addView(step("3", "Enter the % again when you park. The drop over the distance gives the range."));
            battery.addView(step("·", "Nothing is read from the car, and the box's own battery is never used."));
        }
        battery.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        battery.addView(primaryAction(s));
        body.addView(battery, column(1.05f, true));
        if (carBattery) { renderCarDrivePanels(); return; }

        // This drive: distance and GPS quality.
        LinearLayout drive = card();
        EnergyStore.Session session = s.session;
        String heading = session == null ? "THIS DRIVE" : s.active ? "THIS DRIVE · SINCE " + EnergyDialogs.clock(this, session.started)
                : s.recovery != null ? "CUT OFF · " + EnergyDialogs.clock(this, session.started)
                : "LAST DRIVE · " + EnergyDialogs.when(this, session.started).toUpperCase(Locale.getDefault());
        drive.addView(caption(heading));
        if (session == null) {
            drive.addView(empty(carBattery ? "No GPS drives recorded yet. Battery readings arrive automatically from the car."
                    : "No drives recorded yet. Driving without a check-in still records the distance; the battery % is asked when you park."));
            drive.addView(divider());
            drive.addView(caption("VEHICLE PROFILE"));
            EnergyModel.Profile p = s.profile;
            drive.addView(line("Compared with", p.benchmarkLabel(), "NEDC " + EnergyDialogs.km(p.nedcKm) + " · CLTC " + EnergyDialogs.km(p.cltcKm)
                    + " · Practical " + EnergyDialogs.km(p.practicalKm)));
            drive.addView(line("Usable capacity", EnergyDialogs.kwh(p.usableKwh), p.hasCapacity() ? "used for kWh figures" : "needed for kWh · never assumed"));
            drive.addView(line("Gross capacity", EnergyDialogs.kwh(p.grossKwh), "reference only"));
            drive.addView(line("Keep above", EnergyModel.percent(p.reservePercent) + " %", "low-battery warnings and arrival estimate"));
            drive.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            drive.addView(link("Edit vehicle and energy", () -> EnergyDialogs.vehicle(this, palette, changed)));
        } else {
            double[] t = s.totals;
            LinearLayout grid = new LinearLayout(this);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.addView(metrics(metric(km1(t[0] / 1000.0), "KM BY GPS"), metric(minutes(t[1]), "MOVING")));
            grid.addView(metrics(metric(minutes(t[2]), "STANDSTILL"), metric(percentOf(t[3], t[4]), "GPS UNKNOWN")));
            grid.addView(metrics(metric(km1(t[5] / 1000.0), "POOR-GPS KM"), metric(origin(session), "STARTED BY")));
            if (!Double.isNaN(s.driveOdometerKm)) {
                // The car's own distance, typed from its odometer, next to GPS over the same check-ins.
                grid.addView(metrics(metric(km1(s.driveOdometerKm), "KM BY ODOMETER"),
                        metric(s.driveOdometerKm >= EnergyModel.MIN_ODOMETER_KM
                                ? EnergyModel.signed((s.driveOdometerGpsKm / s.driveOdometerKm - 1) * 100) : "—", "GPS VS ODOMETER")));
            }
            drive.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            if (s.active) {
                TextView reset = link("Reset current drive", () -> EnergyDialogs.resetCurrentDrive(this, palette, changed));
                drive.addView(reset);
            }
        }
        body.addView(drive, column(1f, true));

        // Result and the idle model.
        LinearLayout result = card();
        result.addView(caption((carBattery ? "MANUAL HISTORY · VS " : "RESULT · VS ") + s.profile.benchmarkLabel().toUpperCase(Locale.US)));
        boolean car = s.endReading != null && (!Double.isNaN(s.endReading.tripKm) || !Double.isNaN(s.endReading.tripKwhPer100Km));
        if (s.drive != null && !Double.isNaN(s.drive.equivalentRangeKm)) {
            renderResult(result, s.drive, s.profile, "equivalent full-charge range", car ? s.endReading : null);
        } else {
            String why = carBattery ? "Car readings are saved automatically. This comparison uses recorded manual check-ins."
                    : s.driveExclusions.isEmpty() ? (s.active ? "Shown after the drive ends with a battery reading" : "Not enough data yet · needs a 5 % drop")
                    : "Not used for energy: " + reasons(s.driveExclusions);
            result.addView(empty(why));
            if (car) result.addView(tripComputer(s.endReading, "no OABL figure for this drive"));
        }
        result.addView(divider());
        result.addView(caption("IDLE · MODELLED, NOT MEASURED"));
        TextView profile = button(s.profile.idle.title(), false, () -> EnergyDialogs.idleProfile(this, palette, changed));
        result.addView(profile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        double idleSeconds = s.driveIdle == null ? 0 : s.driveIdle.totalSeconds;
        double idleKwh = s.driveIdle == null ? 0 : s.driveIdle.totalKwh;
        result.addView(line("Standing with the car on", minutes(idleSeconds), "≈ " + String.format(Locale.US, "%.2f", idleKwh) + " kWh modelled"));
        TextView note = text("Not added to the battery figures: a battery drop already includes idling.", 12, palette.muted, false);
        note.setPadding(0, dp(4), 0, 0);
        if (!shortWindow()) result.addView(note);
        if (s.active) {
            result.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            TextView ready = button(s.ready ? "Parked with the car on (READY) · tap to stop" : "Parked with the car on (READY)",
                    s.ready, () -> EnergyJournal.get(this).setReady(!s.ready));
            result.addView(ready, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        body.addView(result, column(1.2f, false));
    }

    /** The car's trip A as typed when the drive ended: its own line, when OABL has no kWh beside it. */
    private View tripComputer(EnergyStore.CheckIn end, String oabl) {
        boolean consumption = !Double.isNaN(end.tripKwhPer100Km);
        String value = consumption ? String.format(Locale.US, "%.1f kWh/100 km", end.tripKwhPer100Km) : km1(end.tripKm);
        String note = "the car's figure since this start, entered" + (consumption && !Double.isNaN(end.tripKm) ? " · " + km1(end.tripKm) : "") + " · " + oabl;
        return line("Car's trip A", value, note);
    }

    /** "car's trip A 14.1 · 20.6 km, entered": the trip computer beside OABL's own kWh/100 km. */
    private static String carNote(EnergyStore.CheckIn end) {
        String text = !Double.isNaN(end.tripKwhPer100Km) ? String.format(Locale.US, "car's trip A %.1f", end.tripKwhPer100Km) : "car's trip A";
        if (!Double.isNaN(end.tripKm)) text += " · " + km1(end.tripKm);
        return text + ", entered";
    }

    private View primaryAction(EnergyJournal.Status s) {
        if (page.expectsCarBattery()) {
            CarEnergyRepository.Item pending = page.pending(false);
            return pending != null ? sized(button("Review detected charge",true,()->CarChargeDialogs.show(this,palette,pending,changed)))
                    : sized(button("Vehicle data source", true,() -> CarLinkDialogs.dataSource(this, palette, changed)));
        }
        if (s.recovery != null) return button("Unfinished drive · decide", true, () -> EnergyDialogs.recovery(this, palette, changed));
        if (s.active && s.needsStartPercent) {
            LinearLayout pair = row();
            pair.addView(button("Add starting %", true, () -> EnergyDialogs.startPercent(this, palette, changed)), new LinearLayout.LayoutParams(0, dp(54), 1));
            View gap = new View(this);
            pair.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            pair.addView(button("End drive", false, () -> EnergyDialogs.endDrive(this, palette, changed)), new LinearLayout.LayoutParams(0, dp(54), 1));
            return pair;
        }
        if (s.active) return sized(button("End drive · enter battery %", true, () -> EnergyDialogs.endDrive(this, palette, changed)));
        return sized(button("Battery check-in", true, () -> EnergyDialogs.checkIn(this, palette, changed)));
    }

    private View sized(TextView button) {
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return button;
    }

    private void renderCarDrivePanels() {
        CarEnergyPage link=page,repository=page;
        CarEnergyTracker.Event drive=repository.latestDrive();
        double capacity=page.profile().usableKwh;
        CarEnergyTracker.Event charging=link.liveCharging()?repository.openCharge():null;
        if(charging!=null){renderChargingNow(link,repository,charging);return;}
        LinearLayout car=card();car.addView(caption(drive==null?"THIS DRIVE":drive.closed()?"LAST DRIVE":"THIS DRIVE"));
        double speed=link.liveValue(CarSignal.SPEED),odo=link.liveValue(CarSignal.ODOMETER);
        if(drive!=null) {
            double net=drive.netKwh(capacity);
            CarTripCost.Result cost=CarTripCost.of(drive,CarTripCost.averagePrice(page.charges(),page.homeRate()),CarChargeEnergy.gridKwhPerPoint(page.charges()),capacity);
            car.addView(carMetrics(metric(km1(drive.km),"DISTANCE"),metric(minutes(drive.movingSeconds),"MOVING")));
            car.addView(carMetrics(metric(Double.isFinite(net)?String.format(Locale.US,"%.2f kWh",net):"—","ENERGY USED"),
                    metric(cost==null?"—":String.format(Locale.US,"≈ %.0f %s",cost.cost,page.currency()),"ESTIMATED COST")));
            car.addView(carLine("Consumption",Double.isFinite(net)&&drive.km>0?String.format(Locale.US,"≈ %.1f kWh/100 km",net/drive.km*100):"Collecting data",
                    Double.isFinite(drive.tripNetKwh())?"Includes stops":"Includes stops"));
            car.addView(carLine("Battery used",Double.isFinite(drive.points())?EnergyModel.percent(drive.points())+(drive.points()==1?" point":" points"):"—",
                    Double.isFinite(drive.points())&&drive.points()==0?"Under 1 % so far":"From the car's battery %"));
            if(drive.gap)car.addView(text(String.format(Locale.US,"No car data for %.1f min",drive.gapSeconds/60),14,palette.muted,false));
            car.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
            car.addView(sized(button("Drive details",false,()->showDriveDetails(drive,cost,speed,odo))));
        } else {
            car.addView(empty("Drives are recorded automatically."));
            car.addView(carMetrics(metric(Double.isNaN(speed)?"—":Math.round(speed)+" km/h","CAR SPEED"),metric(Double.isNaN(odo)?"—":km1(odo),"CAR ODOMETER")));
            car.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
            car.addView(sized(button("Connection",false,()->CarLinkDialogs.dataSource(this,palette,changed))));
        }
        body.addView(car,column(1f,true));
        LinearLayout idle=card();idle.addView(caption("STOPPED · CAR ON"));
        CarEnergySummary stops=repository.currentStops(drive==null?0:drive.start,drive==null?Long.MAX_VALUE:drive.last);
        double seconds=stops.seconds,model=stops.model;int countStops=stops.stops;
        idle.addView(text(stopTime(seconds),narrow()?40:64,palette.ink,false));
        idle.addView(text(countStops+(countStops==1?" stop":" stops")+"",13,palette.muted,false));
        idle.addView(carLine("Idle energy",String.format(Locale.US,"≈ %.2f kWh",model),"Estimated"));
        
        double rate=page.homeRate();
        if(!Double.isNaN(rate))idle.addView(carLine("Idle cost",String.format(Locale.US,"≈ %.2f %s",model*rate,page.currency()),"At your home rate"));
        idle.addView(carLine("AC on while stopped",seconds>0?String.format(Locale.US,"%.0f %%",100*Math.min(1,stops.acSeconds/seconds)):"—",
                seconds>0?stopTime(stops.acSeconds)+" of "+stopTime(seconds)+" · from car":"From the car's AC switch"));
        idle.addView(text("Already included in energy used. The AC is not measured separately.",12,palette.muted,false));
        idle.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        idle.addView(sized(button("Stopped-load profile",false,()->EnergyDialogs.idleProfile(this,palette,changed))));
        body.addView(idle,column(1.2f,false));
    }

    private void showDriveDetails(CarEnergyTracker.Event drive,CarTripCost.Result cost,double speed,double odo) {
        StringBuilder detail=new StringBuilder();
        detail.append("Distance: ").append(km1(drive.km)).append(" · ").append(drive.distanceSource)
                .append("\nMoving: ").append(minutes(drive.movingSeconds))
                .append("\nCar speed now: ").append(Double.isFinite(speed)?Math.round(speed)+" km/h":"—")
                .append("\nCar odometer now: ").append(Double.isFinite(odo)?km1(odo):"—")
                .append("\nTrip A (car's accumulated reading): ").append(carTrip(drive.tripKm,drive.tripKwh100));
        if(cost!=null)detail.append("\n\nEstimated trip cost: ").append(cost.text(page.currency()));
        detail.append(String.format(Locale.US,"\n\nRegen (estimated): ≈ %.2f kWh",drive.regenKwh));
        if(drive.last>drive.start)detail.append(String.format(Locale.US,"\nAC on: %.0f%% of observed drive time",100*Math.min(1,drive.acSeconds/Math.max(1,(drive.last-drive.start)/1000.0-drive.gapSeconds))));
        if(Double.isFinite(drive.temperature()))detail.append(String.format(Locale.US,"\nAverage outside temperature: %.1f°C",drive.temperature()));
        for(int i=0;i<6;i++)if(drive.temperatureKm[i]>0) {
            detail.append("\n").append(CarEnergyInsights.BANDS[i]).append(": ").append(km1(drive.temperatureKm[i]));
            if(page.profile().hasCapacity()&&drive.temperaturePoints[i]>0)detail.append(String.format(Locale.US," · ≈ %.1f kWh/100 km (SOC estimate)",page.profile().usableKwh*drive.temperaturePoints[i]/drive.temperatureKm[i]));
        }
        if(drive.gap)detail.append(String.format(Locale.US,"\n\nNo live data for %.1f min. Missing time is not measured stopped energy.",drive.gapSeconds/60));
        if(drive.odometerFlag)detail.append("\nAn odometer reading was flagged.");
        new ThemedDialog(this,palette).setTitle(drive.closed()?"Last drive details":"Current drive details").setMessage(detail.toString()).setPositiveButton("Close",null).show();
    }

    private void renderCarHistory(int which) {
        CarEnergyPage store=page,repository=page;
        long now=System.currentTimeMillis();
        java.util.TimeZone zone=java.util.TimeZone.getDefault();Locale locale=Locale.getDefault();
        EnergyPeriods.Period period=which==TODAY?EnergyPeriods.today(now,zone,locale):which==WEEK?EnergyPeriods.week(now,zone,locale)
                :which==MONTH?EnergyPeriods.month(now,zone,locale):new EnergyPeriods.Period(0,Long.MAX_VALUE,"All time");
        CarEnergySummary total=repository.summary(period.start,period.end);
        List<CarEnergyRepository.Item> items=repository.events(period.start,period.end,which==CHARGING?"charge":null,100);
        double km=total.km,used=total.usedKwh(store.profile().usableKwh),energyKm=total.energyKm(store.profile().usableKwh),seconds=total.seconds,model=total.model;
        int drives=total.drives,charges=total.charges;boolean energy=total.energyDrives>0&&Double.isFinite(used);
        java.util.Map<Long,EnergyModel.Charge> logged=new java.util.HashMap<>();for(EnergyModel.Charge c:store.charges())logged.put(c.id,c);
        LinearLayout summary=card();summary.addView(caption(which==CHARGING?"CHARGING · CAR DETECTED":"CAR ENERGY · "+period.label.toUpperCase(Locale.US)));
        if(which==CHARGING){
            summary.addView(text(charges+" charges detected",narrow()?26:34,palette.ink,false));
            summary.addView(empty("Charges are detected automatically. A rise while the box was off asks you to confirm it."));
            CarEnergyRepository.Item pending=repository.pending(false);
            if(pending!=null)summary.addView(sized(button("Add charge location and price",true,()->CarChargeDialogs.show(this,palette,pending,changed))));
            double bound=CarEnergyInsights.capacityUpperBound(store.charges());
            if(Double.isFinite(bound))summary.addView(sized(button(String.format(Locale.US,"Use %.1f kWh upper bound · includes charging losses",bound),false,()->new ThemedDialog(this,palette).setTitle("Use capacity upper bound?").setMessage("Measured by car (charger side); includes charging losses. Use only as your chosen capacity estimate.").setNegativeButton("Cancel",null).setPositiveButton("Use capacity",(d,w)->{EnergyJournal.get(this).acceptCapacity(bound);changed.run();}).show())));
            summary.addView(text("Trip C · "+carTrip(page.liveValue(CarSignal.TRIP_C_KM),page.liveValue(CarSignal.TRIP_C_KWH100))+" · from car",12,palette.muted,false));
            double perPoint=CarChargeEnergy.gridKwhPerPoint(logged.values());
            summary.addView(carLine("Charger energy per battery %",Double.isFinite(perPoint)?String.format(Locale.US,"%.3f kWh",perPoint):"Learning",
                    Double.isFinite(perPoint)?"Learned from your measured charges":"Needs 10 % of measured charging"));
            summary.addView(carLine("Home rate",Double.isNaN(store.homeRate())?"—":EnergyModel.percent(store.homeRate())+" "+store.currency()+"/kWh","Per kWh"));
        }else{
            summary.addView(text(km1(km),narrow()?36:48,palette.ink,false));summary.addView(text(drives+" drives · car distance",14,palette.muted,false));
            summary.addView(carLine("Energy used",energy?String.format(Locale.US,"≈ %.2f kWh",used):"—","From the car's trip data"));
            summary.addView(carLine("Consumption",energy&&energyKm>=1?String.format(Locale.US,"≈ %.1f kWh/100 km",used/energyKm*100):"Collecting data",String.format(Locale.US,"Energy coverage %.1f of %.1f km · stops included",energyKm,km)));
            double price=CarTripCost.averagePrice(page.charges(),page.homeRate()),perPoint=CarChargeEnergy.gridKwhPerPoint(page.charges());
            double drivingKwh=CarTripCost.billedEnergy(used,perPoint,store.profile().usableKwh);
            summary.addView(carLine("Driving cost",Double.isFinite(drivingKwh)&&price>=0?"≈ "+EnergyModel.percent(Math.round(drivingKwh*price))+" "+page.currency():"—",
                    Double.isFinite(drivingKwh)&&price>=0&&energyKm>=1?String.format(Locale.US,"%.1f %s/km at %s %s/kWh",drivingKwh*price/energyKm,page.currency(),EnergyModel.percent(Math.round(price*100)/100.0),page.currency()):"Needs a charge price"));
            summary.addView(carLine("Regen (estimated)",String.format(Locale.US,"≈ %.1f kWh",total.regenKwh),"From slowing down"));
            summary.addView(text("Trip B · "+carTrip(page.liveValue(CarSignal.TRIP_B_KM),page.liveValue(CarSignal.TRIP_B_KWH100))+" · from car",12,palette.muted,false));
        }
        summary.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));summary.addView(sized(button("Vehicle and energy",false,()->EnergyDialogs.vehicle(this,palette,changed))));
        body.addView(summary,column(1.05f,true));
        if (which == CHARGING) {
            LinearLayout billing=card(); billing.addView(caption("CHARGING TOTALS"));
            double paid=0, billed=0; int priced=0;
            for(EnergyModel.Charge charge:store.charges()) {
                double money=charge.money(store.homeRate()), kwh=charge.billedKwh();
                if(Double.isFinite(money) && Double.isFinite(kwh)) { paid+=money; billed+=kwh; priced++; }
            }
            billing.addView(text(String.format(Locale.US,"≈ %.0f %s",paid,store.currency()),36,palette.ink,true));
            billing.addView(text(priced+(priced==1?" priced charge":" priced charges"),16,palette.muted,false));
            billing.addView(carLine("Charger energy",String.format(Locale.US,"%.1f kWh",billed),"Energy from chargers"));
            billing.addView(carLine("Average price",billed>0?String.format(Locale.US,"%.2f %s/kWh",paid/billed,store.currency()):"—","Total cost ÷ energy"));
            billing.addView(empty("Regenerative braking is not counted as a charge. Unconfirmed battery rises are left out until you confirm them."));
            billing.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
            billing.addView(sized(button("Home electricity rate",false,()->EnergyDialogs.vehicle(this,palette,changed))));
            body.addView(billing,column(1f,true));
        } else {
        LinearLayout stopped=card();stopped.addView(caption("STOPPED ENERGY · "+period.label.toUpperCase(Locale.US)));
        stopped.addView(text(stopTime(seconds),narrow()?32:44,palette.ink,false));
        stopped.addView(carLine("Idle energy (estimated)",String.format(Locale.US,"≈ %.2f kWh",model),"Car on, not moving"));
        for(int band=0;band<6;band++)if(total.temperatureKm[band]>=1&&total.temperaturePoints[band]>0&&store.profile().hasCapacity())stopped.addView(text(String.format(Locale.US,"%s · ≈ %.1f kWh/100 km · estimated; outside temperature from car",CarEnergyInsights.BANDS[band],store.profile().usableKwh*total.temperaturePoints[band]/total.temperatureKm[band]),12,palette.muted,false));
        stopped.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));stopped.addView(sized(button("Stopped-load profile",false,()->EnergyDialogs.idleProfile(this,palette,changed))));
        body.addView(stopped,column(1f,true));
        }
        LinearLayout history=card();history.addView(caption(which==CHARGING?"CHARGE DETAILS":"CAR RECORDS"));
        ScrollView scroll=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        int count=0;
        for(CarEnergyRepository.Item item:items){CarEnergyTracker.Event e=item.event;if(!period.contains(e.closed()?e.end:e.last)||(which==CHARGING&&!e.kind.equals("charge")))continue;

            String label=e.kind.equals("charge")?(!CarChargeReviewPolicy.counts(e)?"Battery rise · not billed":item.review.equals("ignored")?"Dismissed charge":e.inferred?(item.review.equals("saved")?"Charge · confirmed":"Possible charge"):"Charge"):e.kind.equals("stop")?"Stopped · READY on":"Drive";
            String detail=EnergyDialogs.when(this,e.start)+" · "+(e.kind.equals("drive")?km1(e.km)+" · Trip A "+carTrip(e.tripKm,e.tripKwh100)+" · from car":e.kind.equals("stop")?stopTime(e.stoppedSeconds):EnergyModel.percent(e.before)+" → "+EnergyModel.percent(e.after)+" %");
            if(e.kind.equals("drive")){
                CarTripCost.Result cost=CarTripCost.of(e,CarTripCost.averagePrice(page.charges(),page.homeRate()),CarChargeEnergy.gridKwhPerPoint(page.charges()),page.profile().usableKwh);
                if(cost!=null)detail+="\n≈ "+EnergyModel.percent(Math.round(cost.cost))+" "+page.currency()+String.format(Locale.US," · regen ≈ %.2f kWh (modelled)",e.regenKwh);
                if(e.odometerFlag)detail+=" · odometer reading flagged";if(e.gapSeconds>0)detail+=String.format(Locale.US," · no live data for %.1f min",e.gapSeconds/60);}
            if(e.kind.equals("charge")){
                detail+="\nPeriod ending at this charge · Trip C "+carTrip(e.tripCKm,e.tripCKwh100)+" · from car";
                EnergyModel.Charge charge=logged.get(item.chargeId);
                if(charge!=null)detail+="\n"+(charge.place==EnergyModel.Place.HOME?"Home":"Outside")+" · "+chargePrice(e,charge,store);
                else detail+="\n"+(!CarChargeReviewPolicy.counts(e)?"No external charging proof · tap to review":item.review.equals("ignored")?"Dismissed":e.closed()?"Location / price needed":"Charging now");
            }
            TextView row=text(label+"\n"+detail,14,palette.ink,true);row.setPadding(0,dp(10),0,dp(10));
            if(e.kind.equals("charge")&&e.closed())row.setOnClickListener(v->CarChargeDialogs.show(this,palette,item,changed));
            rows.addView(row);
            if(e.kind.equals("drive")&&e.joinable){
                CarEnergyRepository.Item next=null;for(CarEnergyRepository.Item other:items)if(other.event.kind.equals("drive")&&other.event.start>e.start&&(next==null||other.event.start<next.event.start))next=other;
                if(next!=null){CarEnergyRepository.Item following=next;rows.addView(button("Join these drives",false,()->new ThemedDialog(this,palette).setTitle("Join these drives?").setMessage("Adds your join decision and keeps both original drives.").setNegativeButton("Cancel",null).setPositiveButton("Join",(d,w)->{new CarEnergyRepository(EnergyClient.store(this)).join(item,following);changed.run();}).show()));}
            }
            rows.addView(divider());count++;
        }
        if(count==0)rows.addView(empty("Car drives, stops and charges appear here as readings arrive."));
        history.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));body.addView(history,column(1.2f,false));
    }
    private View carMetrics(View a,View b){
        LinearLayout group=metrics(a,b);group.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));group.setPadding(0,dp(8),0,0);
        if(narrow())for(int i=0;i<group.getChildCount();i++)group.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }
    private View carLine(String label,String value,String note){
        if(!narrow())return line(label,value,note);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(0,dp(8),0,0);
        box.addView(text(label,12,palette.muted,false));box.addView(text(value,18,palette.ink,true));
        if(note!=null)box.addView(text(note,12,palette.muted,false));return box;
    }
    private String carTrip(double km,double consumption){return (Double.isFinite(km)?String.format(Locale.US,"%.1f km",km):"— km")+" / "+(Double.isFinite(consumption)?String.format(Locale.US,"%.2f kWh/100 km",consumption):"— kWh/100 km");}
    private String chargePrice(CarEnergyTracker.Event event,EnergyModel.Charge charge,CarEnergyPage store){
        if(charge.live){
            double amount=charge.money(Double.NaN),kwh=charge.billedKwh();
            String basis=EnergyModel.usable(charge.kwh)?"meter kWh, entered":charge.measuredComplete?"measured by car (charger side)"
                    :CarChargeEnergy.describe(CarChargeEnergy.estimate(event,CarChargeEnergy.gridKwhPerPoint(store.charges()),store.profile().usableKwh),event);
            return (Double.isFinite(amount)?String.format(Locale.US,"%s %s",EnergyModel.percent(Math.round(amount*100)/100.0),store.currency()):"Cost unknown")
                    +(Double.isFinite(kwh)?String.format(Locale.US," · %.2f kWh",kwh):"")+(Double.isFinite(charge.rate)?" at "+EnergyModel.percent(charge.rate)+" "+store.currency()+"/kWh":"")+" · "+basis;
        }
        if(!Double.isNaN(charge.cost))return EnergyModel.percent(charge.cost)+" "+store.currency()+" total";
        double rate=!Double.isNaN(charge.rate)?charge.rate:charge.place==EnergyModel.Place.HOME?store.homeRate():Double.NaN;
        if(!Double.isNaN(rate)&&!Double.isNaN(charge.kwh))return EnergyModel.percent(rate*charge.kwh)+" "+store.currency()+" · "+EnergyModel.percent(charge.kwh)+" kWh";
        double estimated=CarChargePricing.estimate(event,charge,store.homeRate(),CarChargeEnergy.gridKwhPerPoint(store.charges()),store.profile().usableKwh);
        return !Double.isNaN(rate)?EnergyModel.percent(rate)+" "+store.currency()+"/kWh"+
                (Double.isFinite(estimated)?" · ≈ "+EnergyModel.percent(estimated)+" estimated from battery gain":" · meter kWh unknown"):"Price not entered";
    }
    private String stopTime(double seconds){return seconds<60?Math.round(seconds)+" s":minutes(seconds);}

    /** "2.6 kW · to 95 %": what the car reports while charging. */
    private String chargingHeadline(CarEnergyPage link){
        double power=link.liveValue(CarSignal.POWER),target=link.liveValue(CarSignal.CHARGE_TARGET);
        String text=Double.isFinite(power)&&power>0?String.format(Locale.US,"%.1f kW",power):"Charging";
        return Double.isFinite(target)?text+" · to "+Math.round(target)+" %":text;
    }
    private String chargingNote(CarEnergyPage link){
        String left=CarChargeTime.remaining(link.liveValue(CarSignal.CHARGE_REMAINING_RAW));
        return left.isEmpty()?"From car · updates automatically":"Full in "+left+" · car's estimate";
    }
    /** Replaces the drive panels while the car charges: energy so far, power, time to full and the likely cost. */
    private void renderChargingNow(CarEnergyPage link,CarEnergyPage repository,CarEnergyTracker.Event charge){
        CarEnergyPage store=page;
        LinearLayout card=card();card.addView(caption(charge.inferred?"CHARGING · CONFIRM AFTER":"CHARGING NOW"));
        double measured=charge.measuredKwh();
        TextView energy=text(Double.isFinite(measured)?String.format(Locale.US,"+%.2f kWh",measured):"+— kWh",narrow()?36:48,palette.ink,false);
        energy.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));energy.setFontFeatureSettings("tnum");card.addView(energy);
        double duration=CarChargeEnergy.durationSeconds(charge);
        card.addView(text(Double.isFinite(measured)?String.format(Locale.US,"Measured at the charger · %.0f%% of %s",100*charge.coveredSeconds/Math.max(1,duration),minutes(duration))
                :"Waiting for charger power readings",13,palette.muted,false));
        double target=link.liveValue(CarSignal.CHARGE_TARGET);
        card.addView(carMetrics(metric(EnergyModel.percent(charge.before)+" → "+EnergyModel.percent(charge.after)+" %","BATTERY"),
                metric(Double.isFinite(target)?Math.round(target)+" %":"—","CHARGE TARGET")));
        double power=link.liveValue(CarSignal.POWER);
        String left=CarChargeTime.remaining(link.liveValue(CarSignal.CHARGE_REMAINING_RAW));
        card.addView(carMetrics(metric(Double.isFinite(power)?String.format(Locale.US,"%.1f kW",power):"—","POWER"),metric(left.isEmpty()?"—":left,"TO FULL · CAR")));
        card.addView(carLine("Started",EnergyDialogs.clock(this,charge.start),minutes(duration)+" ago"+(charge.gap&&!CarChargeEnergy.complete(charge)?" · part of it wasn't measured":"")));
        double gained=charge.gain();
        if(duration>=600&&gained>0)card.addView(carLine("Charging speed",String.format(Locale.US,"%.1f %% per hour",gained/duration*3600),"From battery % gained so far"));
        double raw=link.liveValue(CarSignal.CHARGE_REMAINING_RAW);
        if(!left.isEmpty())card.addView(carLine("Full at",EnergyDialogs.clock(this,System.currentTimeMillis()+Math.round(raw)*60_000L),"Car's estimate"));
        card.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        card.addView(sized(button("Connection",false,()->CarLinkDialogs.dataSource(this,palette,changed))));
        body.addView(card,column(1f,true));

        LinearLayout cost=card();cost.addView(caption("COST SO FAR"));
        CarCharger site=repository.charger(charge,false,0,false);
        boolean home=site.id>0&&site.home;
        double rate=home?store.homeRate():site.id>0?repository.chargerRate(charge,false,site.id):Double.NaN;
        double so=Double.isFinite(measured)&&Double.isFinite(rate)?measured*rate:Double.NaN;
        cost.addView(text(Double.isFinite(so)?String.format(Locale.US,"≈ %s %s",EnergyModel.percent(Math.round(so*100)/100.0),store.currency()):"—",narrow()?32:44,palette.ink,false));
        cost.addView(text(Double.isFinite(rate)?String.format(Locale.US,"At %s %s/kWh · %s",EnergyModel.percent(rate),store.currency(),home?"your home rate":"last price at this charger")
                :site.id>0?"No price saved for this charger yet":"New or unknown location",13,palette.muted,false));
        // What is still to come, at the same price: points to the target × charger kWh per point.
        java.util.List<EnergyModel.Charge> saved=store.charges();
        double perPoint=CarChargeEnergy.gridKwhPerPoint(saved),capacity=store.profile().usableKwh;
        double toGo=Double.isFinite(target)&&Double.isFinite(charge.after)?Math.max(0,target-charge.after):Double.NaN;
        double moreKwh=!Double.isFinite(toGo)?Double.NaN:Double.isFinite(perPoint)?toGo*perPoint:capacity>0?toGo*capacity/100:Double.NaN;
        cost.addView(carLine("To "+(Double.isFinite(target)?Math.round(target)+" %":"target"),
                Double.isFinite(moreKwh)?String.format(Locale.US,"≈ %.1f kWh more",moreKwh)+(Double.isFinite(rate)?" · ≈ "+EnergyModel.percent(Math.round(moreKwh*rate))+" "+store.currency():""):"—",
                Double.isFinite(perPoint)?"From your measured charges, losses included":capacity>0?"Battery side from your capacity; excludes losses":"Enter usable capacity, or finish one measured charge"));
        cost.addView(divider());
        cost.addView(caption(site.id>0?"RECENT CHARGES HERE":"RECENT CHARGES"));
        int listed=0;
        for(int i=saved.size()-1;i>=0&&listed<3;i--){EnergyModel.Charge c=saved.get(i);
            if(!c.live||(site.id>0&&c.chargerId!=site.id))continue;
            double paid=c.money(store.homeRate()),kwh=c.billedKwh();
            cost.addView(carLine(EnergyDialogs.when(this,c.time),Double.isFinite(paid)?EnergyModel.percent(Math.round(paid))+" "+store.currency():"—",
                    (Double.isFinite(kwh)?String.format(Locale.US,"%.1f kWh",kwh):"— kWh")+(Double.isFinite(c.rate)?" · "+EnergyModel.percent(c.rate)+" "+store.currency()+"/kWh":"")));listed++;
        }
        if(listed==0)cost.addView(empty("No saved charges yet. You add the price when this charge ends."));
        cost.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        cost.addView(sized(button("Home electricity rate",false,()->EnergyDialogs.vehicle(this,palette,changed))));
        body.addView(cost,column(1.2f,false));
    }

    /** {@code car}: the drive's end check-in when it has the car's trip A, shown beside OABL's figure. */
    private void renderResult(LinearLayout card, EnergyModel.Result r, EnergyModel.Profile profile, String rangeLabel, EnergyStore.CheckIn car) {
        TextView range = text(Math.round(r.equivalentRangeKm) + " km", narrow() || shortWindow() ? 36 : 48, palette.ink, false);
        range.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        card.addView(range);
        card.addView(text(rangeLabel + " · " + km1(r.km) + " over " + EnergyModel.percent(r.drop) + " points", 13, palette.muted, false));
        TextView verdict = text(EnergyModel.verdictText(r.verdict)
                + (r.verdict == EnergyModel.Verdict.NOT_ENOUGH_DATA ? "" : " · " + EnergyModel.signed(r.differencePercent)),
                16, r.verdict == EnergyModel.Verdict.NOT_ENOUGH_DATA ? palette.muted : palette.accent, true);
        verdict.setPadding(0, dp(8), 0, dp(2));
        card.addView(verdict);
        if (!Double.isNaN(r.performancePercent)) {
            card.addView(text(Math.round(r.performancePercent) + " % of " + r.benchmark + ". Range against the benchmark, not battery health.", 12, palette.muted, false));
        }
        if (profile.hasCapacity()) {
            card.addView(line("Approximate energy", "≈ " + String.format(Locale.US, "%.1f kWh", r.netKwh),
                    String.format(Locale.US, "%.1f kWh/100 km", r.kwhPer100Km) + (car == null ? "" : " · " + carNote(car))));
        } else if (car != null) {
            View line = tripComputer(car, "enter usable capacity for OABL's kWh");
            line.setOnClickListener(v -> EnergyDialogs.vehicle(this, palette, changed));
            card.addView(line);
        } else {
            card.addView(link("Enter usable battery capacity to see kWh", () -> EnergyDialogs.vehicle(this, palette, changed)));
        }
    }

    // ---- All time ----

    private void renderAllTime(EnergyJournal.Status s) {
        EnergyModel.AllTime all = s.allTime;
        LinearLayout summary = card();
        summary.addView(caption("ALL TIME · SUMMED OVER VALID DRIVES"));
        if (all.validDrop > 0) {
            renderResult(summary, all.result(s.profile), s.profile, "equivalent full-charge range", null);
        } else {
            summary.addView(empty("Not enough data yet. Drives count once they start and end with a battery reading, without charging in between."));
        }
        summary.addView(divider());
        summary.addView(line("Coverage", Double.isNaN(all.coverage()) ? "—" : Math.round(all.coverage() * 100) + " %",
                "Based on " + Math.round(all.validKm) + " km of " + Math.round(all.allKm) + " km driven"));
        double learned = all.learnedKmPerPoint();
        summary.addView(line("Per battery point", EnergyModel.usable(learned) ? String.format(Locale.US, "%.2f km", learned) : "—",
                EnergyModel.usable(learned) ? "learned from " + EnergyModel.percent(all.validDrop) + " points"
                        : "benchmark until 10 points (" + EnergyModel.percent(all.validDrop) + " so far)"));
        // GPS against the car's odometer: in this card when there's room, otherwise atop the interval list.
        boolean roomy = !shortWindow() && !narrow();
        if (s.distances.intervals > 0 && roomy) summary.addView(odometerLine(s.distances));
        summary.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        summary.addView(link("Reset learned consumption", () -> EnergyDialogs.resetLearning(this, palette, changed)));
        body.addView(summary, column(1.2f, true));

        LinearLayout idle = card();
        idle.addView(caption("IDLE · MODELLED, NOT MEASURED"));
        EnergyModel.IdleProfile[] profiles = EnergyModel.IdleProfile.values();
        for (EnergyModel.IdleProfile p : profiles) {
            int i = p.ordinal();
            idle.addView(line(p.name() + " · " + p.kw + " kW", minutes(s.allIdle.seconds[i]),
                    String.format(Locale.US, "≈ %.2f kWh", s.allIdle.kwh[i])));
        }
        idle.addView(divider());
        idle.addView(line("Total", minutes(s.allIdle.totalSeconds), String.format(Locale.US, "≈ %.2f kWh modelled", s.allIdle.totalKwh)));
        TextView note = text("One profile applies at a time; each period keeps the profile it was recorded with. Never added to battery figures.", 12, palette.muted, false);
        note.setPadding(0, dp(6), 0, 0);
        if (!shortWindow()) idle.addView(note);
        idle.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        // No "Delete energy history": records are kept for good, so months can always be compared.
        // Mistakes are fixed by editing or removing an entry; removed ones are kept and can come back.
        idle.addView(link("Removed entries · restore", () -> EnergyDialogs.removed(this, palette, changed)));
        body.addView(idle, column(1f, true));

        LinearLayout history = card();
        history.addView(caption("BETWEEN CHECK-INS · " + all.validIntervals + " USED, " + all.excludedIntervals + " NOT USED"));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        List<EnergyModel.Interval> intervals = s.intervals;
        if (s.distances.intervals > 0 && !roomy) list.addView(odometerLine(s.distances));
        if (intervals.isEmpty()) list.addView(empty("Each pair of check-ins appears here, with the reason when it isn't used."));
        for (int i = intervals.size() - 1; i >= 0; i--) list.addView(intervalRow(intervals.get(i), s.profile));
        history.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(history, column(1.25f, false));
    }

    private View odometerLine(EnergyModel.Distances distances) {
        return line("GPS vs odometer", EnergyModel.signed(distances.gpsDifferencePercent()),
                "over " + Math.round(distances.odometerKm) + " km on the car's odometer · results use GPS km");
    }

    private View intervalRow(EnergyModel.Interval interval, EnergyModel.Profile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        String drop = Double.isNaN(interval.drop) ? "—" : EnergyModel.percent(interval.from.percent) + " → " + EnergyModel.percent(interval.to.percent) + " %";
        row.addView(text(EnergyDialogs.when(this, interval.to.time) + " · " + km1(interval.km) + " · " + drop, 14, palette.ink, true));
        String detail;
        if (interval.learnable() || interval.exclusion == EnergyModel.Exclusion.BEFORE_LEARNING_RESET) {
            EnergyModel.Result r = EnergyModel.evaluate(interval.km, interval.drop, profile);
            detail = "≈ " + Math.round(r.equivalentRangeKm) + " km equivalent · " + (r.verdict == EnergyModel.Verdict.NOT_ENOUGH_DATA
                    ? "under 5 points, no verdict" : EnergyModel.signed(r.differencePercent));
            if (!interval.learnable()) detail += " · " + interval.exclusion.reason;
        } else {
            detail = "Not used · " + interval.exclusion.reason;
        }
        if (!Double.isNaN(interval.odometerKm)) detail += " · odometer " + km1(interval.odometerKm);
        if (!Double.isNaN(interval.to.tripKwhPer100Km)) detail += String.format(Locale.US, " · car trip A %.1f kWh/100 km", interval.to.tripKwhPer100Km);
        row.addView(text(detail, 13, interval.learnable() ? palette.muted : palette.muted, false));
        // Tap: fix the reading at either end of this interval.
        row.setOnClickListener(v -> chooseReading(interval));
        row.setContentDescription(EnergyDialogs.when(this, interval.to.time) + ", " + drop + ". Tap to edit or remove a reading.");
        return row;
    }

    private void chooseReading(EnergyModel.Interval interval) {
        EnergyStore store = EnergyJournal.get(this).store();
        EnergyStore.CheckIn from = store.checkIn(interval.from.id), to = store.checkIn(interval.to.id);
        String[] items = {
                "Start · " + (Double.isNaN(interval.from.percent) ? "no %" : EnergyModel.percent(interval.from.percent) + " %") + " · " + EnergyDialogs.when(this, interval.from.time),
                "End · " + (Double.isNaN(interval.to.percent) ? "no %" : EnergyModel.percent(interval.to.percent) + " %") + " · " + EnergyDialogs.when(this, interval.to.time)};
        new ThemedDialog(this, palette).setTitle("Which reading?")
                .setItems(items, (d, which) -> EnergyDialogs.reading(this, palette, which == 0 ? from : to, changed))
                .setNegativeButton("Close", null).show();
    }

    // ---- Today, this week, this month, and month by month ----

    private void renderPeriod(EnergyJournal.Status s, int which) {
        long now = System.currentTimeMillis();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        EnergyPeriods.Period period = which == TODAY ? EnergyPeriods.today(now, zone, locale)
                : which == WEEK ? EnergyPeriods.week(now, zone, locale) : EnergyPeriods.month(now, zone, locale);
        List<IdleLedger.Period> idle = EnergyJournal.get(this).store().idle(0);
        EnergyPeriods.Stats st = stats(s, period, idle, now);
        String currency = s.currency;
        String name = period.label.toUpperCase(locale);

        // 1. Money: what charging cost in this period, home vs public, and the cost per km of its driving.
        LinearLayout spent = card();
        spent.addView(caption("SPENT ON CHARGING · " + name));
        TextView big = text(currency + String.format(Locale.US, " %,.0f", st.money), narrow() ? 28 : 48, palette.ink, false);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setFontFeatureSettings("tnum");
        big.setMaxLines(1);
        spent.addView(big);
        spent.addView(text(st.charges == 0 ? "no charges logged in this period"
                : st.charges + (st.charges == 1 ? " charge" : " charges") + String.format(Locale.US, " · %.1f kWh", st.charging.kwh), 13, palette.muted, false));
        if (st.charging.home.any()) spent.addView(spendLine("Home", st.charging.home, currency));
        if (st.charging.onPublic.any()) spent.addView(spendLine("Public", st.charging.onPublic, currency));
        if (st.charging.placeNotTagged.any()) spent.addView(spendLine("Place not tagged", st.charging.placeNotTagged, currency));
        spent.addView(divider());
        spent.addView(line("Cost per km", Double.isNaN(st.costPerKm) ? "—" : currency + String.format(Locale.US, " %.2f", st.costPerKm),
                Double.isNaN(st.costPerKm) ? "needs a battery drop and a priced charge" : "this driving at your average charging price"));
        if (!Double.isNaN(st.costPerKm)) {
            spent.addView(line("Per 100 km", currency + String.format(Locale.US, " %,.0f", st.costPerKm * 100), null));
        }
        spent.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        spent.addView(sized(button("Log a charge", true, () -> EnergyDialogs.chargeLog(this, palette, 0, null, changed))));
        body.addView(spent, column(1.1f, true));

        // 2. Driving and idle in this period.
        LinearLayout driving = card();
        driving.addView(caption("DRIVING · " + name));
        TextView km = text(km1(st.km), narrow() ? 36 : 48, palette.ink, false);
        km.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        km.setFontFeatureSettings("tnum");
        driving.addView(km);
        driving.addView(text("driven, by GPS", 13, palette.muted, false));
        driving.addView(line("Battery used", st.points > 0 ? EnergyModel.percent(st.points) + " points" : "—",
                st.points > 0 ? (Double.isNaN(st.kwh) ? "enter usable capacity for kWh" : String.format(Locale.US, "≈ %.1f kWh", st.kwh))
                        : "from drives with a start and end reading"));
        double per100 = st.kwhPer100Km();
        if (!Double.isNaN(per100)) driving.addView(line("Consumption", String.format(Locale.US, "%.1f", per100), "kWh/100 km · from the battery drop"));
        driving.addView(divider());
        driving.addView(caption("IDLE · MODELLED, NOT MEASURED"));
        double rate = s.charging.ratePerKwh();
        driving.addView(line("Standing with the car on", minutes(st.idleSeconds),
                String.format(Locale.US, "≈ %.2f kWh", st.idleKwh)
                        + (!Double.isNaN(rate) && st.idleKwh > 0 ? " · ≈ " + currency + String.format(Locale.US, " %,.0f", st.idleKwh * rate) : "")));
        body.addView(driving, column(1f, true));

        // 3. Today and this week: what's in the period. This month: every month, to compare.
        LinearLayout list = card();
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        if (which == MONTH) {
            list.addView(caption("MONTH BY MONTH · KEPT FOR GOOD"));
            for (EnergyPeriods.Period month : EnergyPeriods.months(earliest(s, idle, now), now, zone, locale)) {
                rows.addView(monthRow(stats(s, month, idle, now), currency));
            }
        } else {
            list.addView(caption("IN " + name));
            int count = 0;
            for (int i = s.charges.size() - 1; i >= 0; i--) {
                if (period.contains(s.charges.get(i).time)) { rows.addView(chargeRow(s.charges.get(i), s)); count++; }
            }
            for (int i = s.intervals.size() - 1; i >= 0; i--) {
                EnergyModel.Interval interval = s.intervals.get(i);
                if (period.contains(interval.to.time) && interval.km >= EnergyModel.MIN_INTERVAL_KM) { rows.addView(intervalRow(interval, s.profile)); count++; }
            }
            if (count == 0) rows.addView(empty("Drives and charges in this period appear here."));
        }
        list.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(list, column(1.2f, false));
    }

    private static EnergyPeriods.Stats stats(EnergyJournal.Status s, EnergyPeriods.Period period, List<IdleLedger.Period> idle, long now) {
        return EnergyPeriods.of(period, s.intervals, s.kmSinceReading, s.charges, idle, s.profile, s.homeRate,
                s.charging.gridKwhPerPoint(), s.charging.ratePerKwh(), now);
    }

    /** The first record of any kind, so the month list reaches back to it. */
    private static long earliest(EnergyJournal.Status s, List<IdleLedger.Period> idle, long now) {
        long first = now;
        if (!s.intervals.isEmpty()) first = Math.min(first, s.intervals.get(0).from.time);
        if (!s.charges.isEmpty()) first = Math.min(first, s.charges.get(0).time);
        if (!idle.isEmpty()) first = Math.min(first, idle.get(0).start);
        return first;
    }

    private View monthRow(EnergyPeriods.Stats st, String currency) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(text(st.period.label + " · " + currency + String.format(Locale.US, " %,.0f", st.money) + " spent", 14, palette.ink, true));
        StringBuilder detail = new StringBuilder(km1(st.km));
        if (!Double.isNaN(st.costPerKm)) detail.append(" · ").append(currency).append(String.format(Locale.US, " %.2f/km", st.costPerKm));
        double per100 = st.kwhPer100Km();
        if (!Double.isNaN(per100)) detail.append(String.format(Locale.US, " · %.1f kWh/100 km", per100));
        if (st.charging.home.any() || st.charging.onPublic.any()) {
            detail.append(" · home ").append(String.format(Locale.US, "%,.0f", st.charging.home.money))
                    .append(" / public ").append(String.format(Locale.US, "%,.0f", st.charging.onPublic.money));
        }
        if (st.idleKwh > 0) detail.append(String.format(Locale.US, " · idle ≈ %.1f kWh", st.idleKwh));
        if (st.empty()) detail = new StringBuilder("nothing recorded");
        row.addView(text(detail.toString(), 13, palette.muted, false));
        return row;
    }

    // ---- Charging and parked ----

    private void renderCharging(EnergyJournal.Status s) {
        EnergyModel.Charging c = s.charging;
        EnergyModel.Profile profile = s.profile;
        String currency = s.currency;
        String basis = s.learned ? "km per point learned from your drives" : profile.benchmark().label + "-based km per point";
        double grid100 = c.gridKwhPer100Km(s.kmPerPoint);
        double cost100 = c.costPer100Km(s.kmPerPoint);
        double perPoint = c.gridKwhPerPoint();

        LinearLayout summary = card();
        summary.addView(caption("CHARGING · FROM YOUR LOG"));
        if (c.charges == 0) {
            summary.addView(empty("No charges logged yet. After a check-in that shows the battery rose, OABL offers a short charge log: "
                    + "the % before and after, the kWh from the charger, wallbox or meter app, the cost, AC or DC, home or public. "
                    + "Every entry is optional."));
        } else {
            String headline, label;
            if (!Double.isNaN(cost100)) {
                headline = currency + " " + String.format(Locale.US, "%,.0f", cost100);
                label = "per 100 km · grid kWh per 100 km × the rate paid";
            } else if (!Double.isNaN(grid100)) {
                headline = String.format(Locale.US, "%.1f kWh", grid100);
                label = "from the grid per 100 km · add a cost or rate for cost per 100 km";
            } else {
                headline = c.charges + (c.charges == 1 ? " charge" : " charges");
                label = "logged · the % before and after with the kWh give per-km figures";
            }
            TextView big = text(headline, narrow() ? 28 : 48, palette.ink, false);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setFontFeatureSettings("tnum");
            big.setMaxLines(1);
            summary.addView(big);
            summary.addView(text(label, 13, palette.muted, false));
            summary.addView(line("From the grid per 100 km", Double.isNaN(grid100) ? "—" : String.format(Locale.US, "≈ %.1f kWh", grid100),
                    Double.isNaN(grid100) ? "needs grid energy per point" : basis));
            summary.addView(line("Grid energy per point", Double.isNaN(perPoint) ? "—" : String.format(Locale.US, "%.3f kWh", perPoint),
                    Double.isNaN(perPoint) ? "needs charges with kWh adding up to 20 points (" + EnergyModel.percent(c.capacityGain) + " so far)"
                            : "from " + c.capacityCharges + (c.capacityCharges == 1 ? " charge · " : " charges · ") + EnergyModel.percent(c.capacityGain) + " points gained"));
            double rate = c.ratePerKwh();
            summary.addView(line("Rate paid", Double.isNaN(rate) ? "—" : String.format(Locale.US, "%.1f %s/kWh", rate, currency),
                    Double.isNaN(rate) ? "needs a charge with kWh and a cost or rate"
                            : "over " + c.pricedCharges + (c.pricedCharges == 1 ? " charge" : " charges")
                            + (c.homeRateCharges > 0 ? " · " + c.homeRateCharges + " at your home rate" : "")));
            if (!shortWindow()) {
                summary.addView(line("Logged", c.charges + (c.charges == 1 ? " charge" : " charges"),
                        String.format(Locale.US, "%.1f kWh", c.kwh) + (c.pricedCharges > 0 ? " · " + currency + String.format(Locale.US, " %,.0f", c.money) : "") + " in total"));
            }
        }
        summary.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        summary.addView(sized(button("Log a charge", true, () -> EnergyDialogs.chargeLog(this, palette, 0, null, changed))));
        body.addView(summary, column(1.15f, true));

        LinearLayout middle = card();
        middle.addView(caption("USABLE CAPACITY · FROM CHARGING"));
        double bound = c.capacityUpperBoundKwh();
        if (EnergyModel.usable(bound)) {
            TextView big = text("≤ " + String.format(Locale.US, narrow() ? "%.1f" : "%.1f kWh", bound), narrow() ? 32 : 40, palette.ink, false);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setFontFeatureSettings("tnum");
            big.setMaxLines(1);
            middle.addView(big);
            middle.addView(text((narrow() ? "kWh · " : "") + "upper bound · from charging, includes losses", 13, palette.muted, false));
            middle.addView(line("You entered", EnergyDialogs.kwh(profile.usableKwh),
                    profile.hasCapacity() ? (bound < profile.usableKwh ? "above the bound: check the charge entries" : "used for kWh figures")
                            : "not set · never assumed"));
            if (!profile.hasCapacity() || Math.abs(bound - profile.usableKwh) >= 0.05) {
                middle.addView(link("Use " + String.format(Locale.US, "%.1f", bound) + " kWh as usable capacity",
                        () -> EnergyDialogs.acceptCapacity(this, palette, bound, changed)));
            }
        } else {
            middle.addView(empty("Needs charges with the % before and after and the kWh: at least 10 points each, 20 in all ("
                    + EnergyModel.percent(c.capacityGain) + " so far). Shown as an upper bound, and never used unless you accept it."));
        }
        middle.addView(divider());
        middle.addView(caption("PARKED · DRAIN WHILE THE CAR IS OFF"));
        EnergyModel.Drain drain = s.drain;
        double perDay = drain.pointsPerDay();
        if (!Double.isNaN(perDay)) {
            // Narrow: the unit moves to the line below, so the figure never wraps.
            TextView big = text(String.format(Locale.US, narrow() ? "%.1f" : "%.1f points/day", perDay), narrow() ? 32 : 40, palette.ink, false);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            big.setFontFeatureSettings("tnum");
            big.setMaxLines(1);
            middle.addView(big);
            double kwhDay = drain.kwhPerDay(profile);
            middle.addView(text((narrow() ? "points a day · " : "") + "over " + drain.periods + (drain.periods == 1 ? " parked period · " : " parked periods · ") + Math.round(drain.hours) + " h"
                    + (Double.isNaN(kwhDay) ? "" : String.format(Locale.US, " · ≈ %.2f kWh/day", kwhDay)), 13, palette.muted, false));
        } else {
            middle.addView(empty("Needs a drive's ending % and the next starting % with no charging in between: 24 h parked in all ("
                    + Math.round(drain.hours) + " h so far). Parks under 6 h aren't counted."));
        }
        body.addView(middle, column(1f, true));

        LinearLayout history = card();
        history.addView(caption("CHARGES · " + s.charges.size() + " · PARKED PERIODS · " + s.parked.size()));
        // Where the money went: home vs public, and AC vs DC. Each view sums to the total spent.
        EnergyModel.Charging c2 = s.charging;
        if (c2.money > 0) {
            if (c2.home.any()) history.addView(spendLine("Home", c2.home, currency));
            if (c2.onPublic.any()) history.addView(spendLine("Public", c2.onPublic, currency));
            if (c2.placeNotTagged.any()) history.addView(spendLine("Place not tagged", c2.placeNotTagged, currency));
            if (c2.ac.any()) history.addView(spendLine("AC", c2.ac, currency));
            if (c2.dc.any()) history.addView(spendLine("DC fast", c2.dc, currency));
            if (c2.currentNotTagged.any()) history.addView(spendLine("AC/DC not tagged", c2.currentNotTagged, currency));
            history.addView(divider());
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        if (s.charges.isEmpty() && s.parked.isEmpty()) {
            list.addView(empty("Logged charges and the parked time between check-ins appear here."));
        }
        for (int i = s.charges.size() - 1; i >= 0; i--) list.addView(chargeRow(s.charges.get(i), s));
        if (!s.parked.isEmpty()) {
            TextView sub = caption("PARKED BETWEEN CHECK-INS");
            sub.setPadding(0, dp(14), 0, dp(2));
            list.addView(sub);
        }
        for (int i = s.parked.size() - 1; i >= 0; i--) list.addView(parkedRow(s.parked.get(i)));
        history.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(history, column(1.2f, false));
    }

    /** One kind of charging: what it cost in total, how many charges and how much energy. */
    private View spendLine(String label, EnergyModel.Spend spend, String currency) {
        String note = spend.charges + (spend.charges == 1 ? " charge" : " charges")
                + (spend.kwh > 0 ? String.format(Locale.US, " · %.0f kWh · %.0f %s/kWh", spend.kwh, spend.money / spend.kwh, currency) : "");
        return line(label, currency + String.format(Locale.US, " %,.0f", spend.money), note);
    }

    private View chargeRow(EnergyModel.Charge charge, EnergyJournal.Status s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setMinimumHeight(dp(48));
        StringBuilder top = new StringBuilder(EnergyDialogs.when(this, charge.time));
        if (!Double.isNaN(charge.before) || !Double.isNaN(charge.after)) {
            top.append(" · ").append(EnergyModel.percent(charge.before)).append(" → ").append(EnergyModel.percent(charge.after)).append(" %");
        }
        if (EnergyModel.usable(charge.kwh)) top.append(" · ").append(String.format(Locale.US, "%.1f kWh", charge.kwh));
        row.addView(text(top.toString(), 14, palette.ink, true));
        StringBuilder detail = new StringBuilder();
        if (charge.current != EnergyModel.Current.UNKNOWN) detail.append(charge.current.name());
        if (charge.place != EnergyModel.Place.UNKNOWN) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append(charge.place == EnergyModel.Place.HOME ? "home" : "public");
        }
        double money = charge.money(s.homeRate);
        if (!Double.isNaN(money)) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append(s.currency).append(String.format(Locale.US, " %,.0f", money));
            if (charge.atHomeRate(s.homeRate)) detail.append(" at your home rate");
            else if (Double.isNaN(charge.cost)) detail.append(" at ").append(EnergyModel.percent(charge.rate)).append("/kWh");
        }
        if (detail.length() > 0) detail.append(" · ");
        detail.append(charge.countsForCapacity() ? "counts for capacity" : "not counted for capacity");
        row.addView(text(detail.toString(), 13, palette.muted, false));
        String summary = top + "\n" + detail;
        row.setOnClickListener(v -> EnergyDialogs.charge(this, palette, charge, summary, changed));
        row.setContentDescription(summary + ". Tap to withdraw.");
        return row;
    }

    private View parkedRow(EnergyModel.Parked parked) {
        EnergyModel.Interval i = parked.interval;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        String hours = i.hours >= 48 ? Math.round(i.hours / 24) + " days" : String.format(Locale.US, "%.1f h", i.hours);
        row.addView(text(EnergyDialogs.when(this, i.from.time) + " · " + hours + " · " + EnergyModel.percent(i.from.percent)
                + " → " + EnergyModel.percent(i.to.percent) + " %", 14, palette.ink, true));
        row.addView(text(parked.counted() ? String.format(Locale.US, "%.1f points/day", parked.pointsPerDay())
                : "Not counted · " + parked.exclusion.reason, 13, palette.muted, false));
        return row;
    }

    private void rememberScroll() {
        if (drawnTab < 0) return;
        java.util.ArrayList<ScrollView> scrolls = new java.util.ArrayList<>(); collectScrolls(body, scrolls);
        int[] offsets = new int[scrolls.size()]; for(int i=0;i<offsets.length;i++) offsets[i]=scrolls.get(i).getScrollY();
        scrollPositions.put(drawnTab, offsets);
    }
    private void restoreScroll() {
        java.util.ArrayList<ScrollView> scrolls = new java.util.ArrayList<>(); collectScrolls(body, scrolls);
        int[] offsets=scrollPositions.get(tab);
        for(int i=0;i<scrolls.size();i++) {
            ScrollView scroll=scrolls.get(i); final int offset=offsets!=null&&i<offsets.length?offsets[i]:0;
            scroll.post(()->scroll.scrollTo(0,offset));
            scroll.setOnTouchListener((v,event)->{interactingUntil=android.os.SystemClock.uptimeMillis()+1800;return false;});
        }
    }
    private void collectScrolls(View view, java.util.List<ScrollView> out) {
        if(view instanceof ScrollView)out.add((ScrollView)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectScrolls(((ViewGroup)view).getChildAt(i),out);
    }
    /** Lists already have their own viewport. Other cards keep a fixed action below scrollable details. */
    private void fitCards() {
        for(int i=0;i<body.getChildCount();i++) {
            if(!(body.getChildAt(i) instanceof LinearLayout))continue;
            LinearLayout card=(LinearLayout)body.getChildAt(i);
            java.util.ArrayList<ScrollView> existing=new java.util.ArrayList<>();collectScrolls(card,existing);
            if(!existing.isEmpty())continue;
            View action=card.getChildAt(card.getChildCount()-1);
            boolean pinned=action!=null && action.isClickable();
            if(pinned)card.removeView(action);
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
            while(card.getChildCount()>0) {
                View child=card.getChildAt(0);card.removeViewAt(0);
                if(child.getClass()==View.class)continue;
                naturalHeight(child);content.addView(child);
            }
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);
            card.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
            if(pinned) {LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54));size.topMargin=dp(10);card.addView(action,size);}
        }
    }
    private void naturalHeight(View view) {
        ViewGroup.LayoutParams params=view.getLayoutParams();
        if(params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linear=(LinearLayout.LayoutParams)params;
            if(linear.weight>0 && linear.height==0)linear.height=ViewGroup.LayoutParams.WRAP_CONTENT;
            linear.weight=linear.width==0?linear.weight:0;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)naturalHeight(((ViewGroup)view).getChildAt(i));
    }

    // ---- Small builders ----

    private static String reasons(List<EnergyModel.Exclusion> exclusions) {
        StringBuilder text = new StringBuilder();
        for (EnergyModel.Exclusion e : exclusions) { if (text.length() > 0) text.append(", "); text.append(e.reason); }
        return text.toString();
    }

    private static String origin(EnergyStore.Session session) {
        return "auto".equals(session.createdBy) ? "GPS" : "recovered".equals(session.createdBy) ? "Recovery" : "You";
    }

    private static String km1(double km) { return String.format(Locale.US, "%.1f km", km); }

    private static String minutes(double seconds) {
        long m = Math.round(seconds / 60);
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    private static String percentOf(double part, double whole) {
        return whole > 0 ? Math.round(part / whole * 100) + " %" : "—";
    }

    private LinearLayout.LayoutParams column(float weight, boolean gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        if (gap) params.setMarginEnd(dp(12));
        return params;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(14), dp(20), dp(16));
        card.setBackground(LauncherTheme.glass(this, palette));
        return card;
    }

    private TextView caption(String value) {
        TextView caption = text(value, 13, palette.muted, true);
        caption.setLetterSpacing(.14f);
        caption.setPadding(0, 0, 0, dp(8));
        return caption;
    }

    private View line(String label, String value, String note) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        LinearLayout top = row();
        top.addView(text(label, 14, palette.muted, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView number = text(value, 22, palette.ink, true);
        number.setFontFeatureSettings("tnum");
        number.setGravity(Gravity.END);
        top.addView(number, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        box.addView(top);
        if (note != null) {
            TextView small = text(note, 14, palette.muted, false);
            small.setGravity(Gravity.END);
            box.addView(small, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    /** Two metrics side by side; stacked in a narrow window, where half a card is too thin for "1 h 25 min". */
    private LinearLayout metrics(View a, View b) {
        LinearLayout row = row();
        if (narrow()) {
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(a, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            LinearLayout.LayoutParams first=new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);first.setMarginEnd(dp(8));
            row.addView(a, first);
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        row.setLayoutParams(params);
        return row;
    }

    private View metric(String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = text(value, narrow() ? 24 : 32, palette.ink, false);
        number.setFontFeatureSettings("tnum");
        number.setMaxLines(1);
        if(android.os.Build.VERSION.SDK_INT>=26)number.setAutoSizeTextTypeUniformWithConfiguration(20,narrow()?24:32,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(number, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(narrow()?38:48)));
        TextView caption = text(label, 11, palette.muted, true);
        caption.setLetterSpacing(.12f);
        box.addView(caption);
        return box;
    }

    private View step(String number, String value) {
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(6), 0, 0);
        TextView index = text(number, 15, palette.accent, true);
        row.addView(index, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(text(value, 15, palette.ink, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView empty(String value) {
        TextView view = text(value, 15, palette.muted, false);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(palette.light ? 0x1a13202a : 0x1affffff);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(12);
        params.bottomMargin = dp(10);
        line.setLayoutParams(params);
        return line;
    }

    private TextView tab(String label, int index) {
        TextView view = text(label, 16, palette.ink, true);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setOnClickListener(v -> { tab = index; render(); });
        return view;
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setBackground(on ? LauncherTheme.rounded(this, palette.accent, 16, 0, 0) : LauncherTheme.dockItem(this, palette));
        tab.setTextColor(on ? (palette.light ? 0xffffffff : palette.root) : palette.ink);
    }

    private TextView button(String label, boolean primary, Runnable action) {
        String shown=label;
        if(narrow()) {
            if(label.contains("charge") && (label.contains("Review") || label.contains("location")))shown="Review charge";
            else if(label.equals("Home electricity rate"))shown="Home rate";
            else if(label.equals("Vehicle and energy"))shown="Vehicle settings";
            else if(label.equals("Vehicle data source"))shown="Data source";
            else if(label.equals("Stopped-load profile"))shown="Stopped load";
            else if(label.startsWith("Use ") && label.contains("kWh"))shown="Capacity estimate";
        }
        TextView button = text(shown, 16, primary ? (palette.light ? 0xffffffff : palette.root) : palette.ink, true);
        button.setContentDescription(label);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(narrow() ? 8 : 18), 0, dp(narrow() ? 8 : 18), 0);
        button.setMaxLines(2);
        button.setBackground(primary ? LauncherTheme.rounded(this, palette.accent, 14, 0, 0) : LauncherTheme.raised(this, palette, 14));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView link(String label, Runnable action) {
        TextView link = text(label, 14, palette.accent, true);
        link.setPadding(0, dp(12), 0, dp(4));
        link.setMinHeight(dp(44));
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setOnClickListener(v -> action.run());
        return link;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(Math.max(14, size));
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
