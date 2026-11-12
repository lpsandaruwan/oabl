package com.softiva.oabl;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logged drives with a weekly chart, the running trip meter and where the car is parked.
 * Drives are detected automatically by the trip computer; place names come from the geocoder.
 */
public final class TripHistoryActivity extends Activity {
    private static final ExecutorService PLACES = Executors.newSingleThreadExecutor();
    private static final Map<String, String> placeNames = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherTheme.Palette palette;
    private LinearLayout list;
    /** Car drives and what's needed to cost them, read from :energy off the UI thread. */
    private static final class CarData {
        List<CarEnergyTracker.Event> drives = new java.util.ArrayList<>();
        double price = Double.NaN, perPoint = Double.NaN, capacity = Double.NaN; String currency = "";
    }
    private CarData car = new CarData();
    private static final ExecutorService CAR = Executors.newSingleThreadExecutor();

    private void loadCar() {
        CAR.execute(() -> {
            CarData loaded = new CarData();
            try {
                EnergyStore store = EnergyClient.store(this);
                for (CarEnergyRepository.Item item : new CarEnergyRepository(store).events(0, Long.MAX_VALUE, "drive", 300)) loaded.drives.add(item.event);
                List<EnergyModel.Charge> charges = store.charges();
                loaded.price = CarTripCost.averagePrice(charges, store.homeRate()); loaded.perPoint = CarChargeEnergy.gridKwhPerPoint(charges);
                loaded.capacity = store.profile().usableKwh; loaded.currency = store.currency();
            } catch (RuntimeException unavailable) { return; }
            runOnUiThread(() -> { if (!ActivityState.isGone(this)) { car = loaded; render(); } });
        });
    }

    /** "≈ 57.8 kWh · 13.8 kWh/100 km" for the car's drives in the last seven days, or "" when none is measured. */
    private String sevenDayEnergy() {
        long since = System.currentTimeMillis() - 7 * 86_400_000L;
        double kwh = 0, km = 0;
        for (CarEnergyTracker.Event drive : car.drives) {
            if (drive.start < since) continue;
            double net = drive.netKwh(car.capacity);
            if (Double.isFinite(net) && net >= 0 && drive.km > 0) { kwh += net; km += drive.km; }
        }
        if (km < 1 || kwh <= 0) return "";
        return String.format(Locale.US, "Car energy ≈ %.1f kWh · %.1f kWh/100 km", kwh, kwh / km * 100);
    }

    /** The car drive overlapping this GPS trip the most, or null. */
    private CarEnergyTracker.Event match(TripComputer.Trip trip, java.util.Set<CarEnergyTracker.Event> used) {
        long end = trip.end == 0 ? System.currentTimeMillis() : trip.end;
        CarEnergyTracker.Event best = null; long bestOverlap = 0;
        for (CarEnergyTracker.Event drive : car.drives) {
            long overlap = Math.min(end, drive.closed() ? drive.end : drive.last) - Math.max(trip.start, drive.start);
            if (overlap > bestOverlap) { bestOverlap = overlap; best = drive; }
        }
        if (best != null) used.add(best);
        return best;
    }

    /** "Battery 80 → 74 % · ≈ 145 LKR · regen ≈ 0.31 kWh (estimated)" for a car drive. */
    private String carLine(CarEnergyTracker.Event drive) {
        StringBuilder text = new StringBuilder("Car");
        if (Double.isFinite(drive.before) && Double.isFinite(drive.after)) text.append(" · battery ").append(EnergyModel.percent(drive.before)).append(" → ").append(EnergyModel.percent(drive.after)).append(" %");
        CarTripCost.Result cost = CarTripCost.of(drive, car.price, car.perPoint, car.capacity);
        if (cost != null) text.append(" · ≈ ").append(EnergyModel.percent(Math.round(cost.cost))).append(" ").append(car.currency);
        if (drive.regenKwh > 0) text.append(String.format(Locale.US, " · regen ≈ %.2f kWh (estimated)", drive.regenKwh));
        if (drive.gapSeconds > 0) text.append(" · partly no live data");
        return text.toString();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(palette.root);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(16), dp(28), dp(24));
        page.addView(content);
        setContentView(page);
        ImmersiveWindow.apply(getWindow(), page);

        LinearLayout header = row();
        MusicIconView back = new MusicIconView(this, MusicIconView.Icon.BACK, palette.ink, palette.accent, "Back", this::finish);
        back.setBackground(LauncherTheme.dockItem(this, palette));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView title = text("Trip history", 30, palette.ink, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = text("Clear history", 14, palette.accent, true);
        clear.setPadding(dp(16), dp(10), dp(16), dp(10));
        clear.setOnClickListener(v -> new ThemedDialog(this, palette)
                .setTitle("Clear trip history?").setMessage("Logged drives are removed. The trip meter stays.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> { TripComputer.get(this).clearHistory(); render(); }).show());
        header.addView(clear);
        content.addView(header);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        render();
        loadCar();
    }

    private void render() {
        list.removeAllViews();
        TripComputer computer = TripComputer.get(this);
        List<TripComputer.Trip> trips = computer.history();
        Calendar week = Calendar.getInstance();
        week.set(Calendar.DAY_OF_WEEK, week.getFirstDayOfWeek());
        week.set(Calendar.HOUR_OF_DAY, 0);
        week.set(Calendar.MINUTE, 0);
        week.set(Calendar.SECOND, 0);
        double km = 0, minutes = 0, top = 0;
        int count = 0;
        for (TripComputer.Trip trip : trips) {
            if (trip.start < week.getTimeInMillis()) continue;
            km += trip.kilometers;
            minutes += trip.minutes;
            top = Math.max(top, trip.peakKph);
            count++;
        }

        LinearLayout summary = row();
        summary.setPadding(0, dp(14), 0, 0);
        summary.addView(statCard(String.format(Locale.US, "%.1f", km), "KM THIS WEEK"), cardWeight(true));
        summary.addView(statCard(duration(minutes), "DRIVING"), cardWeight(true));
        summary.addView(statCard(String.valueOf(count), "TRIPS"), cardWeight(true));
        summary.addView(statCard(String.format(Locale.US, "%.0f", top), "TOP KM/H"), cardWeight(false));
        list.addView(summary);

        LinearLayout middle = row();
        middle.setGravity(Gravity.TOP);
        middle.setPadding(0, dp(12), 0, 0);
        LinearLayout chartCard = card();
        double[] days = computer.lastSevenDays();
        double sevenDayKm = 0;
        for (double day : days) sevenDayKm += day;
        chartCard.addView(caption(String.format(Locale.US, "LAST 7 DAYS · KM · AVERAGE %.1f / DAY", sevenDayKm / 7)));
        chartCard.addView(new WeekChart(days), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(136)));
        String energy = sevenDayEnergy();
        if (!energy.isEmpty()) chartCard.addView(text(energy, 13, palette.muted, false));
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(0, dp(200), 1.4f);
        chartSize.rightMargin = dp(12);
        middle.addView(chartCard, chartSize);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(tripMeter(computer), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(94)));
        LinearLayout.LayoutParams parkedSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(94));
        parkedSize.topMargin = dp(12);
        side.addView(parkedCard(computer), parkedSize);
        middle.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(middle);

        if (trips.isEmpty() && car.drives.isEmpty()) {
            TextView empty = text("No drives yet.\nDrives over 300 m are recorded automatically.",
                    16, palette.muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            list.addView(empty);
            return;
        }
        DateFormat day = DateFormat.getDateInstance(DateFormat.FULL);
        DateFormat clock = android.text.format.DateFormat.getTimeFormat(this);
        String lastDay = null;
        java.util.Set<CarEnergyTracker.Event> matched = new java.util.HashSet<>();
        for (TripComputer.Trip trip : trips) {
            CarEnergyTracker.Event drive = match(trip, matched);
            String dayLabel = day.format(new Date(trip.start));
            if (!dayLabel.equals(lastDay)) {
                list.addView(heading(dayLabel.toUpperCase(Locale.getDefault())));
                lastDay = dayLabel;
            }
            LinearLayout card = row();
            card.setPadding(dp(20), dp(14), dp(20), dp(14));
            card.setBackground(surface());
            LinearLayout when = new LinearLayout(this);
            when.setOrientation(LinearLayout.VERTICAL);
            when.addView(text(clock.format(new Date(trip.start)) + "  →  "
                    + (trip.end == 0 ? "now" : clock.format(new Date(trip.end))), 17, palette.ink, true));
            TextView route = text(trip.end == 0 ? "Driving now" : duration(trip.minutes) + " moving", 13, palette.muted, false);
            route.setMaxLines(1);
            route.setEllipsize(android.text.TextUtils.TruncateAt.END);
            when.addView(route);
            if (trip.hasPlaces()) placeRoute(route, trip);
            if (drive != null) when.addView(text(carLine(drive), 13, palette.accent, false));
            card.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            // The car's odometer distance when the car recorded this drive; GPS otherwise.
            card.addView(stat(String.format(Locale.US, "%.1f", drive != null && drive.km > 0 ? drive.km : trip.kilometers), drive != null && drive.km > 0 ? "KM · CAR" : "KM · GPS"), weight());
            card.addView(stat(String.format(Locale.US, "%.0f", trip.averageKph()), "AVG KM/H"), weight());
            card.addView(stat(String.format(Locale.US, "%.0f", trip.peakKph), "TOP KM/H"), weight());
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.bottomMargin = dp(8);
            list.addView(card, gap);
        }
        // Drives only the car recorded (no GPS trip): for example GPS off, or the box joined mid-drive.
        boolean headed = false;
        for (CarEnergyTracker.Event drive : car.drives) {
            if (matched.contains(drive) || drive.km < 0.3) continue;
            if (!headed) { list.addView(heading("CAR DRIVES WITHOUT A GPS TRIP")); headed = true; }
            LinearLayout card = row();
            card.setPadding(dp(20), dp(14), dp(20), dp(14));
            card.setBackground(surface());
            LinearLayout when = new LinearLayout(this);
            when.setOrientation(LinearLayout.VERTICAL);
            long end = drive.closed() ? drive.end : drive.last;
            when.addView(text(day.format(new Date(drive.start)) + " · " + clock.format(new Date(drive.start)) + "  →  " + clock.format(new Date(end)), 17, palette.ink, true));
            when.addView(text(carLine(drive), 13, palette.accent, false));
            card.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            card.addView(stat(String.format(Locale.US, "%.1f", drive.km), "KM · CAR"), weight());
            card.addView(stat(drive.movingSeconds > 0 ? String.format(Locale.US, "%.0f", drive.averageKph()) : "—", "AVG KM/H"), weight());
            card.addView(stat(String.format(Locale.US, "%.0f", drive.peakSpeed), "TOP KM/H"), weight());
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.bottomMargin = dp(8);
            list.addView(card, gap);
        }
    }

    /** The resettable trip meter shown on Home (all movement since the last reset, short hops too). */
    private View tripMeter(TripComputer computer) {
        LinearLayout card = card();
        LinearLayout top = row();
        top.addView(caption("TRIP METER · SINCE RESET"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView reset = text("Reset", 13, palette.accent, true);
        reset.setPadding(dp(10), dp(2), 0, dp(2));
        reset.setOnClickListener(v -> new ThemedDialog(this, palette).setTitle("Reset the trip meter?")
                .setMessage("Distance, time and top speed on Home start again from zero. Logged drives stay.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> { computer.reset(); render(); }).show());
        top.addView(reset);
        card.addView(top);
        card.addView(text(String.format(Locale.US, "%.1f km  ·  %s  ·  top %.0f km/h", computer.kilometers(),
                duration(computer.movingMinutes()), computer.peakKph()), 18, palette.ink, true));
        return card;
    }

    /** Where the car stopped last, with a shortcut to the navigation app. */
    private View parkedCard(TripComputer computer) {
        LinearLayout card = card();
        double[] parked = computer.parked();
        LinearLayout top = row();
        top.addView(caption(parked == null ? "PARKED" : "PARKED SINCE " + android.text.format.DateFormat.getTimeFormat(this)
                .format(new Date((long) parked[2])).toUpperCase(Locale.getDefault())), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView place = text(parked == null ? "After your first drive" : "Finding street…", 18, palette.ink, true);
        place.setMaxLines(1);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (parked != null) {
            TextView open = text("Open in Maps", 13, palette.accent, true);
            open.setPadding(dp(10), dp(2), 0, dp(2));
            open.setOnClickListener(v -> openMap(parked[0], parked[1]));
            top.addView(open);
            name(parked[0], parked[1], value -> place.setText(value));
        }
        card.addView(top);
        card.addView(place);
        return card;
    }

    private void openMap(double lat, double lon) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Parked car)", lat, lon, lat, lon)));
        ComponentName map = new LauncherPreferences(this).focus();
        if (map != null) intent.setPackage(map.getPackageName());
        try {
            startActivity(intent);
        } catch (RuntimeException noMapApp) {
            try { startActivity(intent.setPackage(null)); } catch (RuntimeException ignored) { }
        }
    }

    private void placeRoute(TextView route, TripComputer.Trip trip) {
        String prefix = route.getText().toString();
        name(trip.startLat, trip.startLon, from -> name(trip.endLat, trip.endLon, to ->
                route.setText(prefix + "  ·  " + from + (to.equals(from) ? "" : " → " + to))));
    }

    interface Named { void ready(String name); }

    /** Street (or area) for a point; cached per ~100 m so a long history costs few lookups. */
    private void name(double lat, double lon, Named result) {
        String key = String.format(Locale.US, "%.3f,%.3f", lat, lon);
        synchronized (placeNames) {
            String known = placeNames.get(key);
            if (known != null) { result.ready(known); return; }
        }
        PLACES.execute(() -> {
            String value = String.format(Locale.US, "%.4f, %.4f", lat, lon);
            if (Geocoder.isPresent()) {
                try {
                    List<Address> found = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                    if (found != null && !found.isEmpty()) {
                        Address a = found.get(0);
                        for (String part : new String[]{a.getThoroughfare(), a.getSubLocality(), a.getLocality(), a.getSubAdminArea()}) {
                            if (part != null && !part.trim().isEmpty()) { value = part.trim(); break; }
                        }
                    }
                } catch (Exception offline) {
                    // Coordinates stay as the label.
                }
            }
            String name = value;
            synchronized (placeNames) { placeNames.put(key, name); }
            main.post(() -> { if (!ActivityState.isGone(this)) result.ready(name); });
        });
    }

    /** Seven bars, today on the right; static drawing. */
    private final class WeekChart extends View {
        private final double[] days;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        WeekChart(double[] days) {
            super(TripHistoryActivity.this);
            this.days = days;
            label.setTextSize(12 * getResources().getDisplayMetrics().density);
            label.setTextAlign(Paint.Align.CENTER);
            StringBuilder spoken = new StringBuilder("Kilometres per day:");
            for (double d : days) spoken.append(' ').append(String.format(Locale.US, "%.1f", d));
            setContentDescription(spoken);
        }

        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            double max = 1;
            for (double d : days) max = Math.max(max, d);
            float slot = getWidth() / 7f, labelSpace = 34 * density, top = 18 * density;
            Calendar day = Calendar.getInstance();
            day.add(Calendar.DAY_OF_YEAR, -6);
            for (int i = 0; i < 7; i++) {
                float height = (float) ((getHeight() - labelSpace - top) * days[i] / max);
                float cx = slot * i + slot / 2, w = Math.min(slot * .5f, 34 * density);
                box.set(cx - w / 2, getHeight() - labelSpace - Math.max(height, 3 * density), cx + w / 2, getHeight() - labelSpace);
                bar.setColor(i == 6 ? palette.accent : (palette.accent & 0x00ffffff) | 0x66000000);
                canvas.drawRoundRect(box, 6 * density, 6 * density, bar);
                label.setColor(palette.muted);
                if (days[i] > 0) {
                    label.setColor(palette.ink);
                    canvas.drawText(String.format(Locale.US, days[i] < 10 ? "%.1f" : "%.0f", days[i]), cx, box.top - 5 * density, label);
                    label.setColor(palette.muted);
                }
                String name = i == 6 ? "Today" : day.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
                canvas.drawText(name, cx, getHeight() - 12 * density, label);
                day.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
    }

    private static String duration(double minutes) {
        int total = (int) Math.round(minutes);
        return total < 60 ? total + " min" : (total / 60) + " h " + (total % 60) + " min";
    }

    private android.graphics.drawable.Drawable surface() {
        return LauncherTheme.rounded(this, palette.light ? 0xffffffff : 0xff19212b, 18, 0, 0);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setBackground(surface());
        return card;
    }

    private View statCard(String value, String label) {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER);
        TextView number = text(value, 26, palette.ink, true);
        number.setFontFeatureSettings("tnum");
        number.setGravity(Gravity.CENTER);
        card.addView(number);
        TextView caption = caption(label);
        caption.setGravity(Gravity.CENTER);
        card.addView(caption);
        return card;
    }

    private LinearLayout.LayoutParams cardWeight(boolean gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(92), 1);
        if (gap) params.rightMargin = dp(12);
        return params;
    }

    private TextView caption(String value) {
        TextView caption = text(value, 10, palette.muted, true);
        caption.setLetterSpacing(.12f);
        caption.setPadding(0, 0, 0, dp(4));
        return caption;
    }

    private TextView heading(String value) {
        TextView heading = text(value, 11, palette.muted, true);
        heading.setLetterSpacing(.12f);
        heading.setPadding(dp(4), dp(18), 0, dp(8));
        return heading;
    }

    private LinearLayout stat(String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView number = text(value, 22, palette.ink, true);
        number.setFontFeatureSettings("tnum");
        box.addView(number);
        TextView caption = text(label, 10, palette.muted, true);
        caption.setLetterSpacing(.12f);
        box.addView(caption);
        return box;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
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
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
