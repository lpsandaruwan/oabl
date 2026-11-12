package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Runs against the installed launcher; sample guidance exists only in this test APK. */
public final class NavigationHintsTestRunner extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        LauncherPreferences prefs = new LauncherPreferences(getTargetContext());
        int originalTheme = prefs.themeMode();
        boolean originalEffects = prefs.visualEffects();
        int originalAccent = prefs.accentIndex(), originalMaximum = prefs.vehicleMaximumSpeed();
        prefs.setVisualEffects(false);
        MediaAccessService.NavigationSnapshot original = MediaAccessService.navigationSnapshot();
        MainActivity activity = null;
        try {
            parserChecks();
            batteryChecks();
            tripChecks();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity home = activity;
            for (int theme : new int[]{LauncherPreferences.THEME_LIGHT, LauncherPreferences.THEME_DARK}) {
                prefs.setThemeMode(theme);
                onUi(() -> {
                    invoke(home, "applyAppearance", new Class<?>[]{boolean.class}, false);
                    invoke(home, "renderDashboardData", new Class<?>[0]);
                    setSnapshot(new MediaAccessService.NavigationSnapshot(true,
                            "com.google.android.apps.maps", "Sample guidance",
                            NavigationHintParser.parse("300 m", "Turn left onto Main Street",
                                    null, "18 min · 7.2 km · 10:42 ETA", null),
                            null, null, "test-route", System.currentTimeMillis()));
                    invoke(home, "renderNavigationHints", new Class<?>[0]);
                    ((TextView) home.findViewById(R.id.navigation_hint_label)).setText("TEST GUIDANCE");
                });
                waitForIdleSync();
                // Visibility changes schedule measurement on the next display frame.
                android.os.SystemClock.sleep(350L);
                capture(theme == LauncherPreferences.THEME_LIGHT
                        ? "navigation-hints-test-light.png" : "navigation-hints-test-dark.png");
                onUi(() -> {
                    View card = home.findViewById(R.id.navigation_hint_card);
                    View media = home.findViewById(R.id.media_card);
                    check(card.getVisibility() == View.VISIBLE, "guidance visible");
                    Rect cardRect = new Rect(), mediaRect = new Rect();
                    card.getGlobalVisibleRect(cardRect);
                    media.getGlobalVisibleRect(mediaRect);
                    check(!Rect.intersects(cardRect, mediaRect), "guidance beside media, not over it: " + cardRect + " versus " + mediaRect);
                    check(home.findViewById(R.id.dock).findViewWithTag("dock_apps") != null
                                    && home.findViewById(R.id.dock).findViewWithTag("dock_controls") != null,
                            "dock keeps Apps and Controls");
                    check(getTargetContext().getResources().getIdentifier("agenda_card", "id", getTargetContext().getPackageName()) == 0,
                            "calendar agenda removed");
                    check(getTargetContext().getResources().getIdentifier("trip_badge", "id", getTargetContext().getPackageName()) == 0,
                            "duplicate trip summary removed");
                    int[] cards = {R.id.calendar_card, R.id.telemetry_card, R.id.media_card, R.id.trip_card, R.id.energy_card,
                            R.id.navigation_hint_card, R.id.dock};
                    for (int id : cards) {
                        View tile = home.findViewById(id);
                        Rect rect = new Rect();
                        check(tile.getGlobalVisibleRect(rect) && rect.width() == tile.getWidth()
                                && rect.height() == tile.getHeight(), "bento tile not clipped: " + home.getResources().getResourceEntryName(id) + " visible " + rect + " size " + tile.getWidth() + "x" + tile.getHeight());
                    }
                    for (int a = 0; a < cards.length; a++) for (int b = a + 1; b < cards.length; b++) {
                        Rect first = new Rect(), second = new Rect();
                        home.findViewById(cards[a]).getGlobalVisibleRect(first);
                        home.findViewById(cards[b]).getGlobalVisibleRect(second);
                        check(!Rect.intersects(first, second), "cards do not overlap: " + home.getResources().getResourceEntryName(cards[a])
                                + " and " + home.getResources().getResourceEntryName(cards[b]));
                    }
                    int[] buttons = {R.id.media_previous, R.id.media_play_pause, R.id.media_next, R.id.media_open};
                    for (int id : buttons) {
                        View button = home.findViewById(id);
                        Rect bounds = new Rect();
                        check(button.getGlobalVisibleRect(bounds) && bounds.width() == button.getWidth()
                                && bounds.height() == button.getHeight(), "media control not clipped: " + id);
                    }
                });
            }
            onUi(() -> {
                setSnapshot(MediaAccessService.NavigationSnapshot.NONE);
                invoke(home, "renderNavigationHints", new Class<?>[0]);
                check("Route ended".contentEquals(((TextView)home.findViewById(R.id.navigation_hint_title)).getText()),
                        "route end shows a short summary");
                invoke(home, "dismissArrival", new Class<?>[0]);
                check(home.findViewById(R.id.navigation_idle).getVisibility() == View.VISIBLE
                                && home.findViewById(R.id.navigation_guidance).getVisibility() == View.GONE,
                        "guidance clears to Where to? after the summary");
                check(home.findViewById(R.id.navigation_divider).getVisibility() == View.GONE,
                        "divider clears when route ends");
            });
            onUi(() -> {
                android.graphics.Paint text = new android.graphics.Paint(); text.setTextSize(13*home.getResources().getDisplayMetrics().density);
                android.graphics.Paint.FontMetricsInt metrics = new android.graphics.Paint.FontMetricsInt();
                new StatusIconSpan(home, StatusIconSpan.WIFI, 4, 0xffffffff, 0xff555555)
                        .getSize(text, "x", 0, 1, metrics);
                check(metrics.ascent <= -Math.round(18*home.getResources().getDisplayMetrics().density), "Wi-Fi full arc fits line metrics");
                for (int color = 0; color < LauncherTheme.ACCENT_NAMES.length; color++) {
                    prefs.setAccentIndex(color);
                    check(new LauncherPreferences(home).accentIndex() == color, "accent persists " + color);
                    invoke(home, "applyAppearance", new Class<?>[]{boolean.class}, false);
                    LauncherTheme.Palette selected = LauncherTheme.resolve(home, prefs);
                    check(((TextView)home.findViewById(R.id.vehicle_name)).getCurrentTextColor() == selected.accent, "Home uses selected accent " + color);
                }
                prefs.setVehicleMaximumSpeed(200);
                invoke(home, "renderDashboardData", new Class<?>[0]);
                check(new LauncherPreferences(home).vehicleMaximumSpeed() == 200, "maximum speed persists");
                check("SCALE 200".contentEquals(((TextView)home.findViewById(R.id.speed_scale)).getText()), "gauge displays configured scale");
                check(Math.abs(((SpeedGaugeView)home.findViewById(R.id.speed_cluster)).sweepForSpeed(100)-120) < .01, "gauge scales to configured maximum");
                check(((SpeedGaugeView)home.findViewById(R.id.speed_cluster)).sweepForSpeed(250) == 240, "gauge saturates above full scale");
                prefs.setAccentIndex(originalAccent); prefs.setVehicleMaximumSpeed(originalMaximum);
                invoke(home, "applyAppearance", new Class<?>[]{boolean.class}, false);
            });
            maximumSpeedDialogChecks(home, prefs);
            onUi(() -> invoke(home, "showAccentPicker", new Class<?>[0]));
            waitForIdleSync(); android.os.SystemClock.sleep(350);
            capture("metallic-finish-selector.png");
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> cancel =
                    getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Cancel");
            check(!cancel.isEmpty() && cancel.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "metallic selector opens and dismisses");
            waitForIdleSync();
            result.putString("stream", "PASS: " + assertions + " assertions; Light/Dark route layouts, "
                    + "provider filtering, fallback text, and route cleanup.\n");
            Instrumentation.ActivityMonitor equalizer = addMonitor(EqualizerActivity.class.getName(), null, false);
            onUi(() -> invoke(home, "openEqualizer", new Class<?>[0]));
            Activity opened = waitForMonitorWithTimeout(equalizer, 5000);
            check(opened instanceof EqualizerActivity, "Controls opens the actual equalizer screen");
            onUi(opened::finish);
            removeMonitor(equalizer);
            result.putString("stream", "PASS: " + assertions + " assertions; dashboard Light/Dark, unclipped tiles and text, trip filters, navigation cleanup and equalizer launch.\n");
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            result.putString("failure", failure.toString());
        } finally {
            prefs.setAccentIndex(originalAccent); prefs.setVehicleMaximumSpeed(originalMaximum);
            prefs.setVisualEffects(originalEffects);
            prefs.setThemeMode(originalTheme);
            // LauncherPreferences writes with apply(); commit so the restore survives the
            // instrumentation process being killed right after finish().
            getTargetContext().getSharedPreferences("launcher_preferences", 0).edit()
                    .putInt("theme_mode", originalTheme)
                    .putBoolean("visual_effects", originalEffects)
                    .commit();
            setSnapshot(original);
            if (activity != null) {
                final MainActivity home = activity;
                onUi(() -> {
                    ((TextView) home.findViewById(R.id.navigation_hint_label)).setText(R.string.navigation_guidance);
                    invoke(home, "applyAppearance", new Class<?>[]{boolean.class}, false);
                    invoke(home, "renderNavigationHints", new Class<?>[0]);
                });
            }
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }

    private void maximumSpeedDialogChecks(MainActivity home, LauncherPreferences prefs) {
        int before = prefs.vehicleMaximumSpeed();
        onUi(() -> invoke(home, "showMaximumSpeedPicker", new Class<?>[0]));
        waitForIdleSync(); android.os.SystemClock.sleep(350);
        setMaximumInput("0"); clickSave(); waitForIdleSync();
        check(prefs.vehicleMaximumSpeed() == before, "invalid maximum rejected by dialog");
        check(findInput(getUiAutomation().getRootInActiveWindow()) != null, "invalid input keeps dialog open");
        setMaximumInput("180"); clickSave(); waitForIdleSync();
        check(prefs.vehicleMaximumSpeed() == 180, "settings Save applies valid maximum");
        onUi(() -> { prefs.setVehicleMaximumSpeed(before); invoke(home, "renderDashboardData", new Class<?>[0]); });
    }
    private void setMaximumInput(String value) {
        android.view.accessibility.AccessibilityNodeInfo input = findInput(getUiAutomation().getRootInActiveWindow());
        check(input != null, "maximum input accessible");
        Bundle text = new Bundle();
        text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text), "maximum input editable");
    }
    private android.view.accessibility.AccessibilityNodeInfo findInput(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo result = findInput(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }
    private void clickSave() {
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons =
                getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Save");
        check(!buttons.isEmpty(), "Save accessible");
        check(buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "Save clickable");
    }

    private void batteryChecks() {
        Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(android.os.BatteryManager.EXTRA_PRESENT, true)
                .putExtra(android.os.BatteryManager.EXTRA_LEVEL, 85)
                .putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                .putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_CHARGING);
        check("Device · 85% · charging".equals(DeviceBattery.label(battery)), "device charge percentage");
        battery.putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING);
        check("Device · 85%".equals(DeviceBattery.label(battery)), "discharging percentage");
        battery.putExtra(android.os.BatteryManager.EXTRA_LEVEL, 170).putExtra(android.os.BatteryManager.EXTRA_SCALE, 200);
        check("Device · 85%".equals(DeviceBattery.label(battery)), "battery scale normalized");
        battery.putExtra(android.os.BatteryManager.EXTRA_SCALE, 0);
        check("Device battery unavailable".equals(DeviceBattery.label(battery)), "invalid battery scale rejected");
        check("Device battery unavailable".equals(DeviceBattery.label(null)), "missing battery handled");
        battery.putExtra(android.os.BatteryManager.EXTRA_PRESENT, false).putExtra(android.os.BatteryManager.EXTRA_PLUGGED, 1);
        check("Device · external power".equals(DeviceBattery.label(battery)), "batteryless powered box labelled");
    }

    private void tripChecks() throws Exception {
        android.content.SharedPreferences storage = getTargetContext().getSharedPreferences("local_trip", 0);
        float meters = storage.getFloat("meters", 0), seconds = storage.getFloat("moving_seconds", 0), peak = storage.getFloat("peak_kph", 0);
        java.lang.reflect.Constructor<TripComputer> constructor = TripComputer.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        TripComputer trip = constructor.newInstance(getTargetContext());
        try {
            trip.reset();
            android.location.Location a = sample(0, 1, 10);
            android.location.Location b = sample(.0001, 2, 10);
            trip.accept(a, true); trip.accept(b, true);
            check(trip.kilometers() > .010 && trip.kilometers() < .012, "accurate movement accumulated");
            check(trip.movingMinutes() > .016 && trip.movingMinutes() < .017, "moving time accumulated");
            check(Math.abs(trip.peakKph()-36) < .01, "peak speed tracked");
            double distance = trip.kilometers(); trip.accept(b, true);
            check(trip.kilometers() == distance, "duplicate fix excluded");
            trip.accept(sample(1, 3, 10), true);
            check(trip.kilometers() == distance, "GPS jump excluded");
            trip.reset(); trip.accept(sample(0, 1, 0), true); trip.accept(sample(.00001, 2, 0), true);
            check(trip.kilometers() == 0 && trip.movingMinutes() == 0, "stationary jitter excluded");
            trip.accept(sample(.0001, 30, 10), true);
            check(trip.kilometers() == 0, "long gap excluded");
            android.location.Location poor = sample(.0002, 31, 10); poor.setAccuracy(100);
            trip.accept(poor, true);
            check(trip.kilometers() == 0, "inaccurate fix excluded");
            check("North East".equals(DashboardData.compassName(45)), "compass heading");
        } finally {
            storage.edit().putFloat("meters", meters).putFloat("moving_seconds", seconds).putFloat("peak_kph", peak).commit();
        }
    }
    private static android.location.Location sample(double latitude, int second, float speed) {
        android.location.Location position = new android.location.Location("test");
        position.setLatitude(latitude); position.setLongitude(0); position.setAccuracy(5);
        position.setSpeed(speed); position.setElapsedRealtimeNanos(second*1_000_000_000L); return position;
    }

    private void parserChecks() {
        NavigationGuidance maps = NavigationGuidance.read(NavigationHintParser.parse("300 m",
                "Turn left onto Main Street", null, "18 min · 7.2 km · 10:42 ETA", null));
        check("300 m".equals(maps.distance) && Math.abs(maps.meters - 300) < .01, "Maps distance split");
        check("Turn left onto Main Street".equals(maps.instruction), "Maps instruction kept");
        check("10:42".equals(maps.eta) && "18 min · 7.2 km".equals(maps.remaining), "ETA separated from trip");
        NavigationGuidance waze = NavigationGuidance.read(NavigationHintParser.parse("Turn right",
                "in 1.2 km to Galle Road", null, null, null));
        check("1.2 km".equals(waze.distance) && Math.abs(waze.meters - 1200) < .01, "Waze distance split");
        check("Turn right to Galle Road".equals(waze.instruction), "Waze instruction joined");
        NavigationGuidance prefixed = NavigationGuidance.read(NavigationHintParser.parse("In 300 m",
                "Turn left onto Main Street", null, "18 min · 7.2 km", null));
        check("Turn left onto Main Street".equals(prefixed.instruction) && prefixed.eta == null
                && "18 min · 7.2 km".equals(prefixed.remaining), "prefixed distance and plain trip");
        NavigationGuidance plain = NavigationGuidance.read(NavigationHintParser.parse("Continue on A1",
                null, null, null, null));
        check(plain.distance == null && "Continue on A1".equals(plain.instruction), "unknown format passes through");
        check(NavigationGuidance.read(NavigationHintParser.parse("50 m", "Arrive at destination",
                null, null, null)).arriving, "arrival detected");
        check(NavigationHintParser.accepts("com.google.android.apps.maps", null, "navigation",
                true, false, "navigation", null), "Maps route accepted");
        check(NavigationHintParser.accepts("com.waze", null, null,
                true, false, "Navigation", null), "legacy explicit navigation accepted");
        check(NavigationHintParser.accepts("custom.nav", "custom.nav", "navigation",
                true, false, null, null), "selected provider accepted");
        check(!NavigationHintParser.accepts("unrelated.app", null, "navigation",
                true, false, null, null), "unrelated notification rejected");
        check(!NavigationHintParser.accepts("com.google.android.apps.maps", null, "status",
                true, false, "location_sharing", null), "location sharing rejected");
        check(!NavigationHintParser.accepts("com.google.android.apps.maps", null, "promo",
                true, false, "navigation", null), "Maps promo rejected");
        check(!NavigationHintParser.accepts("com.google.android.apps.maps", null, "navigation",
                false, false, null, null), "nonongoing route rejected");
        check(!NavigationHintParser.accepts("com.google.android.apps.maps", null, "navigation",
                true, true, null, null), "group summary rejected");
        check(NavigationHintParser.parse(null, null, null, null, null) == null, "empty guidance rejected");
        NavigationHintParser.Text fallback = NavigationHintParser.parse(null, null,
                " Turn left\nonto Main Street ", null, null);
        check("Turn left onto Main Street".equals(fallback.title), "big-text fallback normalized");
        check(fallback.detail == null, "missing title not duplicated");
        NavigationHintParser.Text duplicate = NavigationHintParser.parse("Continue", "Continue", null,
                "Continue", "12 min");
        check(duplicate.detail == null && "12 min".equals(duplicate.trip), "duplicates removed");
        check("左折して中央通りへ".equals(NavigationHintParser.parse("左折して中央通りへ", null,
                null, null, null).title), "localized guidance preserved");
    }

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }

    private void onUi(Runnable action) {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void setSnapshot(MediaAccessService.NavigationSnapshot snapshot) {
        try {
            Field field = MediaAccessService.class.getDeclaredField("currentNavigation");
            field.setAccessible(true);
            field.set(null, snapshot);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void invoke(MainActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void capture(String name) throws Exception {
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new AssertionError("screenshot unavailable");
        File file = new File(getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            screenshot.recycle();
        }
    }
}
