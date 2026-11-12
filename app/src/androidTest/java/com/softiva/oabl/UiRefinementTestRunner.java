package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Emulator-only regression for the UI rework refinements. Restores preferences and display. */
public final class UiRefinementTestRunner extends Instrumentation {
    private static boolean containsScroll(View v) {
        if (v instanceof android.widget.ScrollView) return true;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (containsScroll(g.getChildAt(i))) return true;
        }
        return false;
    }

    private volatile MainActivity home;
    private Activity tool;
    private int checks;
    private String onlySize;
    private MediaAccessService.NavigationSnapshot originalNavigation;
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    @Override public void callActivityOnResume(Activity a) { super.callActivityOnResume(a); if (a instanceof MainActivity) home = (MainActivity)a; }
    @Override public void onCreate(Bundle b) { super.onCreate(b); onlySize = b == null ? null : b.getString("size"); start(); }
    private void check(boolean b, String message) { if (!b) throw new AssertionError(message); checks++; }
    private void ui(Runnable action) {
        final Throwable[] error = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error[0] = t; } });
        if (error[0] != null) throw new RuntimeException(error[0]);
    }
    private Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void call(Object owner, String name, boolean value) {
        try { Method m = owner.getClass().getDeclaredMethod(name, boolean.class); m.setAccessible(true); m.invoke(owner, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void noArg(Object owner, String name) {
        try { Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void navigationFixture(boolean active) {
        try {
            Field snapshot = MediaAccessService.class.getDeclaredField("currentNavigation"); snapshot.setAccessible(true);
            snapshot.set(null, active ? new MediaAccessService.NavigationSnapshot(true, "com.google.android.apps.maps", "Maps",
                    new NavigationHintParser.Text("400 m", "Turn right onto Baseline Road", "12.3 km · 28 min · 11:45 ETA"), null, null,
                    "ui-refinement-navigation", System.currentTimeMillis()) : MediaAccessService.NavigationSnapshot.NONE);
            if (!active && home != null) {
                Field last = MainActivity.class.getDeclaredField("lastGuidance"); last.setAccessible(true); last.set(home, null);
                Field arrival = MainActivity.class.getDeclaredField("arrivalUntil"); arrival.setAccessible(true); arrival.setLong(home, 0);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private String shell(String command) {
        try (ParcelFileDescriptor p = getUiAutomation().executeShellCommand(command); FileInputStream in = new FileInputStream(p.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n); return out.toString("UTF-8");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void settled() { waitForIdleSync(); SystemClock.sleep(350); }
    private View tagged(Activity a, String tag) { return a.getWindow().getDecorView().findViewWithTag(tag); }
    private void capture(String name) {
        try {
            File dir = new File(getTargetContext().getExternalFilesDir(null), "ui-refinements"); dir.mkdirs();
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            bitmap.recycle();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void openHome() {
        if (home == null) ui(() -> getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        else { MainActivity previous = home; ui(previous::recreate); home = null; }
        long until = SystemClock.elapsedRealtime() + 10000;
        while (home == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
        check(home != null, "Home resumed"); settled();
        ui(() -> ((Handler)field(home, "handler")).removeCallbacks((Runnable)field(home, "mediaRefresh")));
    }
    private void finishTool() { if (tool != null) { Activity previous = tool; ui(previous::finish); tool = null; settled(); } }
    @Override public void onStart() {
        Bundle result = new Bundle(); boolean success = false;
        Context context = getTargetContext();
        try {
            check(Build.MODEL.contains("sdk_gphone"), "UI resize tests only run on emulator");
            for (String name : new String[]{"launcher_preferences", "equalizer_profile", "equalizer_ui", "music"})
                saved.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
            LauncherPreferences prefs = new LauncherPreferences(context);
            prefs.setScreensaverSeconds(0); prefs.setVisualEffects(false);
            prefs.setStartupNoticeEnabled(false); prefs.setOpenNavigationOnStart(false); prefs.setResumeMusicOnStart(false);
            originalNavigation = MediaAccessService.navigationSnapshot();
            // MY_PACKAGE_REPLACED restores Home after installation; let that one-shot alarm settle.
            SystemClock.sleep(7500);
            provenance();
            for (String size : new String[]{"1920x970", "1920x720", "960x970"}) {
                if (onlySize != null && !onlySize.equals(size)) continue;
                shell("wm size " + size); SystemClock.sleep(500);
                for (int mode : new int[]{LauncherPreferences.THEME_LIGHT, LauncherPreferences.THEME_DARK}) {
                    prefs.setThemeMode(mode);
                    String name = size + (mode == LauncherPreferences.THEME_LIGHT ? "-light" : "-dark");
                    openHome();
                    ui(() -> {
                        for (int id : new int[]{R.id.calendar_card, R.id.weather_hours, R.id.weather_sun, R.id.telemetry_card,
                                R.id.trip_distance, R.id.trip_time, R.id.trip_average, R.id.trip_peak, R.id.media_card, R.id.navigation_hint_card, R.id.dock}) {
                            View v = home.findViewById(id);
                            check(v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0, "Home retains feature " + id + " at " + name);
                        }
                        HomeTab.show(home); check(!HomeTab.isShowing(), "No Home tab over dashboard");
                        boolean narrow = home.getResources().getConfiguration().screenWidthDp < 900;
                        LinearLayout row = home.findViewById(R.id.main_row);
                        check(row.getOrientation() == (narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL), "Window-aware Home arrangement");
                        if (!narrow) for (int id : new int[]{R.id.media_previous, R.id.media_play_pause, R.id.media_next, R.id.trip_distance, R.id.trip_time})
                            fullyVisible(home.findViewById(id), "Home primary control/data visible at " + name + ": " + id);
                        if (!narrow) fullyVisible((View)home.findViewById(R.id.navigation_chips).getParent(), "Navigation shortcut viewport visible");
                    });
                    capture("home-" + name);
                    ui(() -> {
                        navigationFixture(true);
                        call(home, "setDrivingMode", true); noArg(home, "renderNavigationHints");
                        home.<TextView>findViewById(R.id.navigation_hint_title).setText("400 m");
                        home.<TextView>findViewById(R.id.navigation_hint_detail).setText("Turn right onto Baseline Road");
                        home.<ImageView>findViewById(R.id.navigation_hint_icon).setImageResource(R.drawable.ic_nav_turn);
                        home.<ImageView>findViewById(R.id.navigation_hint_icon).setImageTintList(android.content.res.ColorStateList.valueOf(home.currentPalette().light ? Color.WHITE : home.currentPalette().ink));
                        home.<TextView>findViewById(R.id.navigation_eta).setText("11:45");
                        home.<TextView>findViewById(R.id.navigation_hint_trip).setText("12.3 km · 28 min");
                        check(home.findViewById(R.id.navigation_hint_card).getParent() == home.findViewById(R.id.calendar_column), "Guidance stays in the context column");
                        check(home.findViewById(R.id.weather_hours).getVisibility() == View.VISIBLE, "Forecast retained while driving");
                        check(home.findViewById(R.id.trip_metrics).getVisibility() == View.VISIBLE, "Trip metrics retained while driving");
                    }); settled();
                    ui(() -> {
                        check(home.findViewById(R.id.navigation_guidance).isShown(), "Guidance text actually displayed");
                        if (home.getResources().getConfiguration().screenWidthDp >= 900) {
                            fullyVisible(home.findViewById(R.id.media_play_pause), "Playback visible with expanded guidance");
                            fullyVisible(home.findViewById(R.id.navigation_hint_title), "Maneuver distance visible");
                            fullyVisible(home.findViewById(R.id.navigation_hint_detail), "Street instruction visible");
                            fullyVisible(home.findViewById(R.id.navigation_hint_trip), "Remaining route visible");
                            fullyVisible(home.findViewById(R.id.trip_metrics), "Trip metrics remain visible with guidance");
                        }
                    });
                    capture("guidance-" + name);
                    ui(() -> { navigationFixture(false); call(home, "setDrivingMode", false); noArg(home, "renderNavigationHints"); }); settled();
                    final SettingsPanel[] settings = new SettingsPanel[1];
                    ui(() -> {
                        check(home.findViewById(R.id.navigation_hint_card).getParent() == home.findViewById(R.id.calendar_column), "Idle layout restored without lost views");
                        settings[0] = new SettingsPanel(home, home.currentPalette()); settings[0].show();
                    }); settled();
                    ui(() -> {
                        Dialog d = (Dialog)field(settings[0], "dialog");
                        check(d.getWindow().getDecorView().findViewWithTag("setting_appearance") != null, "Settings opens on Look & feel");
                        check(!containsScroll(d.getWindow().getDecorView()), "Settings is a fixed page: no page scrolling");
                        check(settings[0].row("equalizer") != null, "Sections page instead of scrolling: Sound shows its rows");
                    });
                    capture("settings-" + name); ui(settings[0]::close); settled();
                    final Object[] controls = new Object[1];
                    ui(() -> {
                        try {
                            Constructor<ControlsSheet> constructor = ControlsSheet.class.getDeclaredConstructor(MainActivity.class, LauncherTheme.Palette.class);
                            constructor.setAccessible(true); controls[0] = constructor.newInstance(home, home.currentPalette()); noArg(controls[0], "open");
                            LinearLayout sheet = (LinearLayout)field(controls[0], "sheet");
                            android.graphics.drawable.GradientDrawable background = (android.graphics.drawable.GradientDrawable)sheet.getBackground();
                            check(Color.alpha(background.getColor().getDefaultColor()) == 255, "Controls surface prevents ghosted dashboard text");
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }); settled(); capture("controls-" + name); ui(() -> ((Dialog)field(controls[0], "dialog")).dismiss()); settled();

                    // The one-time band migration markers are not tuning; set them first so only tuning is compared.
                    context.getSharedPreferences("equalizer_profile", 0).edit().putBoolean("fine_detail_kept", true).putBoolean("ten_band_notice", false).commit();
                    Map<String, ?> eqBefore = new HashMap<>(context.getSharedPreferences("equalizer_profile", 0).getAll());
                    tool = startActivitySync(new Intent(context, EqualizerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settled();
                    ui(() -> {
                        HomeTab.show(tool); check(!HomeTab.isShowing(), "No Home tab over EQ");
                        // Primary controls on the fixed page; the rest one tap away in the panel tabs.
                        for (String tag : new String[]{"eq_scope", "eq_view_sliders", "eq_link", "eq_band_plus", "eq_undo", "eq_reset", "eq_compare", "equalizer_enabled",
                                "eq_panel_sound", "eq_panel_output"})
                            check(tagged(tool, tag) != null && tagged(tool, tag).isShown(), "EQ primary control on the page: " + tag);
                        View[] panels = (View[]) field(tool, "panels");
                        for (String tag : new String[]{"eq_calibrate", "master_gain", "eq_normalization", "eq_exciter", "eq_exciter_hz"}) {
                            boolean found = false;
                            for (View panel : panels) found |= panel.findViewWithTag(tag) != null;
                            check(found, "EQ control kept in a panel: " + tag);
                        }
                    }); settled(); capture("eq-" + name);
                    check(eqBefore.equals(context.getSharedPreferences("equalizer_profile", 0).getAll()), "Opening EQ does not alter saved tuning");
                    finishTool();

                    tool = startActivitySync(new Intent(context, MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settled();
                    ui(() -> {
                        View root = tool.getWindow().getDecorView();
                        TextView sort = (TextView)field(tool, "sortButton");
                        check(sort.getText().toString().contains("Artist"), "Active sort visible");
                        ListView list = root.findViewWithTag("music_tracks");
                        check(list != null && list.getChildCount() > 0, "Music fixtures rendered");
                        for (int i = 0; i < list.getChildCount(); i++) {
                            LinearLayout row = (LinearLayout)list.getChildAt(i);
                            View star = row.getChildAt(row.getChildCount() - 1);
                            check(star instanceof MusicIconView && star.getVisibility() == View.VISIBLE, "Every track exposes a star");
                        }
                        HomeTab.show(tool); check(!HomeTab.isShowing(), "No Home tab over Music");
                        LinearLayout first = (LinearLayout)list.getChildAt(0);
                        View star = first.getChildAt(first.getChildCount() - 1);
                        long id = Long.parseLong(first.getTag().toString().substring("track_".length()));
                        MusicLibrary library = MusicLibrary.get(context); boolean previous = library.track(id).favorite;
                        long current = context.getSharedPreferences("music", 0).getLong("current", -1);
                        star.performClick(); check(library.track(id).favorite != previous, "Row star persists favorite");
                        check(context.getSharedPreferences("music", 0).getLong("current", -1) == current, "Favorite action does not change playback");
                        star.performClick(); check(library.track(id).favorite == previous, "Favorite restored");
                        View mini = root.findViewWithTag("music_mini_player"); if (mini != null) mini.performClick();
                    }); settled();
                    ui(() -> {
                        for (String tag : new String[]{"now_favorite", "now_add_playlist", "now_up_next"}) check(tagged(tool, tag) != null, "Now Playing retains " + tag);
                        View play = (View)field(tool, "play"); Rect bounds = new Rect();
                        check(play.getGlobalVisibleRect(bounds) && bounds.height() == play.getHeight(), "Playback control stays fully visible on short/split display");
                    }); capture("music-" + name); finishTool();
                }
            }
            if (onlySize == null) {
                shell("wm size reset"); SystemClock.sleep(500);
                prefs.setVisualEffects(true); openHome();
                ui(() -> { navigationFixture(true); call(home, "setDrivingMode", true); noArg(home, "renderNavigationHints"); });
                settled(); SystemClock.sleep(700);
                ui(() -> {
                    check(home.findViewById(R.id.navigation_hint_card).getParent() == home.findViewById(R.id.calendar_column), "Animated guidance changes parent safely");
                    fullyVisible(home.findViewById(R.id.media_play_pause), "Playback remains visible with animations enabled");
                    navigationFixture(false); call(home, "setDrivingMode", false); noArg(home, "renderNavigationHints");
                }); settled(); SystemClock.sleep(400);
                ui(() -> check(home.findViewById(R.id.navigation_hint_card).getParent() == home.findViewById(R.id.calendar_column), "Animated idle layout restored"));
            }
            if (android.provider.Settings.canDrawOverlays(context) && !SplitScreenService.isConnected(context) && !DriveOverlayService.isActive()) {
                ui(() -> context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                settled();
                // Home's onStop (and so the tab) waits for the other app to come up: allow up to 3 s.
                for (long until = SystemClock.elapsedRealtime() + 3000; !HomeTab.isShowing() && SystemClock.elapsedRealtime() < until; ) SystemClock.sleep(100);
                ui(() -> check(HomeTab.isShowing(), "External apps still offer the Home tab"));
                ui(() -> context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                settled(); ui(() -> check(!HomeTab.isShowing(), "Home tab removed on returning to OABL"));
            }
            success = true;
        } catch (Throwable error) { capture("failure"); result.putString("stream", "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error)); }
        finally {
            finishTool(); shell("wm size reset");
            try { Field nav = MediaAccessService.class.getDeclaredField("currentNavigation"); nav.setAccessible(true); nav.set(null, originalNavigation == null ? MediaAccessService.NavigationSnapshot.NONE : originalNavigation); }
            catch (Exception ignored) { }
            for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet()) restore(entry.getKey(), entry.getValue());
            if (home != null) ui(home::recreate);
        }
        if (success) result.putString("stream", "PASS · " + checks + " checks; three window sizes, light/dark, retained Home/EQ/music features, saved tuning and provenance\n");
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void provenance() throws Exception {
        RoadSpeedLimit.Result mapped = RoadSpeedLimit.limitFor(new JSONObject("{\"highway\":\"primary\",\"maxspeed\":\"70\",\"name\":\"Road | A\"}"));
        RoadSpeedLimit.Result estimated = RoadSpeedLimit.limitFor(new JSONObject("{\"highway\":\"residential\"}"));
        check(!mapped.estimated && mapped.limit == 70, "Mapped maxspeed carries provenance");
        check(estimated.estimated, "Road-class fallback marked estimated");
        check(RoadSpeedLimit.encode(mapped).endsWith("|mapped"), "Mapped provenance survives cache encoding");
        check(RoadSpeedLimit.encode(estimated).endsWith("|estimated"), "Estimated provenance survives cache encoding");
        RoadSpeedLimit isolated = RoadSpeedLimit.class.getDeclaredConstructor().newInstance();
        Method apply = RoadSpeedLimit.class.getDeclaredMethod("apply", String.class); apply.setAccessible(true);
        apply.invoke(isolated, "50|0|Old road"); check(isolated.estimated(), "Legacy cache treated as uncertain");
        apply.invoke(isolated, RoadSpeedLimit.encode(mapped)); check(!isolated.estimated(), "New mapped cache restores provenance");
    }
    private void fullyVisible(View view, String label) {
        Rect visible = new Rect();
        boolean shown = view.getGlobalVisibleRect(visible);
        check(shown && visible.width() == view.getWidth() && visible.height() == view.getHeight(), label + ": " + visible + " / " + view.getWidth() + "x" + view.getHeight());
    }
    @SuppressWarnings("unchecked") private void restore(String name, Map<String, ?> values) {
        SharedPreferences.Editor edit = getTargetContext().getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> item : values.entrySet()) {
            Object v = item.getValue(); String k = item.getKey();
            if (v instanceof Boolean) edit.putBoolean(k, (Boolean)v); else if (v instanceof Integer) edit.putInt(k, (Integer)v);
            else if (v instanceof Long) edit.putLong(k, (Long)v); else if (v instanceof Float) edit.putFloat(k, (Float)v);
            else if (v instanceof String) edit.putString(k, (String)v); else if (v instanceof Set) edit.putStringSet(k, new HashSet<>((Set<String>)v));
        }
        edit.commit();
    }
}
