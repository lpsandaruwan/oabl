package com.softiva.oabl;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

final class LauncherPreferences {
    static final int THEME_AUTO = 0;
    static final int THEME_DARK = 1;
    static final int THEME_LIGHT = 2;
    private static final String FILE = "launcher_preferences";
    private static final String SHORTCUT = "shortcut_";
    private static final String FOCUS = "focus_component";
    private static final String SPLIT_PRIMARY = "split_primary";
    private static final String SPLIT_SECONDARY = "split_secondary";
    private static final String WALLPAPER_URI = "wallpaper_uri";
    private static final String BACKGROUND_STYLE = "background_style";
    private static final String WIDGET = "widget_";
    private static final String VISUAL_EFFECTS = "visual_effects";
    private static final String THEME_MODE = "theme_mode";
    private static final String HOME_ROLE_PROMPTED = "home_role_prompted";

    private final SharedPreferences preferences;
    private final Context context;

    LauncherPreferences(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    ComponentName shortcut(int index) {
        return unflatten(preferences.getString(SHORTCUT + index, null));
    }

    void setShortcut(int index, ComponentName component) {
        putComponent(SHORTCUT + index, component);
    }

    ComponentName focus() {
        return unflatten(preferences.getString(FOCUS, null));
    }

    void setFocus(ComponentName component) {
        putComponent(FOCUS, component);
    }

    ComponentName splitPrimary() {
        return unflatten(preferences.getString(SPLIT_PRIMARY, null));
    }

    ComponentName splitSecondary() {
        return unflatten(preferences.getString(SPLIT_SECONDARY, null));
    }

    void setSplitPair(ComponentName primary, ComponentName secondary) {
        preferences.edit()
                .putString(SPLIT_PRIMARY, primary == null ? null : primary.flattenToString())
                .putString(SPLIT_SECONDARY, secondary == null ? null : secondary.flattenToString())
                .apply();
    }

    String wallpaperUri() {
        return preferences.getString(WALLPAPER_URI, null);
    }

    void setWallpaperUri(String uri) {
        preferences.edit().putString(WALLPAPER_URI, uri).apply();
    }
    boolean sampleWallpaper() { return preferences.getBoolean("sample_wallpaper",false); }
    /** The Drive & Energy card's period: HomePeriod's index, 1 (week) until chosen. */
    int homePeriod() { return preferences.getInt("home_period", HomePeriod.WEEK.ordinal()); }
    void setHomePeriod(int index) { preferences.edit().putInt("home_period", index).apply(); }
    void setSampleWallpaper(boolean enabled) { preferences.edit().putBoolean("sample_wallpaper",enabled).apply(); }

    boolean blurredBackground() {
        return preferences.getBoolean(BACKGROUND_STYLE, false);
    }

    void setBlurredBackground(boolean blurred) {
        preferences.edit().putBoolean(BACKGROUND_STYLE, blurred).apply();
    }

    /** Speed (km/h) at which Home switches to large, driving-safe controls; 0 turns it off. */
    int drivingModeKmh() { return preferences.getInt("driving_mode_kmh", 15); }
    void setDrivingModeKmh(int kmh) { preferences.edit().putInt("driving_mode_kmh", kmh).apply(); }

    /** Driver-selected dashboard emphasis; navigation always takes priority over this choice. */
    String dashboardLayoutOverride() { return preferences.getString("dashboard_layout_override", "auto"); }
    void setDashboardLayoutOverride(String value) {
        preferences.edit().putString("dashboard_layout_override",
                "media".equals(value) || "normal".equals(value) ? value : "auto").apply();
    }

    /** Message shown once per boot until acknowledged. */
    boolean startupNoticeEnabled() { return preferences.getBoolean("startup_notice", true); }
    void setStartupNoticeEnabled(boolean value) { preferences.edit().putBoolean("startup_notice", value).apply(); }
    String startupTitle() { return preferences.getString("startup_title", "Before you drive"); }
    String startupMessage() {
        return preferences.getString("startup_message",
                "Turn off ADAS automatic emergency braking.\nIt does not suit Sri Lankan road conditions.");
    }
    void setStartupNotice(String title, String message) {
        preferences.edit().putString("startup_title", title).putString("startup_message", message).apply();
    }
    int startupAcknowledgedBoot() { return preferences.getInt("startup_ack_boot", -1); }
    void setStartupAcknowledgedBoot(int boot) { preferences.edit().putInt("startup_ack_boot", boot).apply(); }

    /** Screensaver background: a photo URI, "wallpaper", or null for plain dark. */
    String screensaverImage() { return preferences.getString("screensaver_image", null); }
    void setScreensaverImage(String value) { preferences.edit().putString("screensaver_image", value).apply(); }

    /** Seconds parked without a touch before the screensaver starts; 0 turns it off. */
    int screensaverSeconds() { return preferences.getInt("screensaver_seconds", 20); }
    void setScreensaverSeconds(int seconds) { preferences.edit().putInt("screensaver_seconds", seconds).apply(); }

    /** Fixed Home tab shown over other apps: "right", "left" or "off". */
    String homeTabSide() { return preferences.getString("home_tab_side", "right"); }
    /** Which side of the screen the driver sits on: "right" (Sri Lanka, right-hand drive) or "left". */
    String driverSide() { return preferences.getString("driver_side", "right"); }
    void setDriverSide(String side) { preferences.edit().putString("driver_side", "left".equals(side) ? "left" : "right").apply(); }
    void setHomeTabSide(String side) {
        preferences.edit().putString("home_tab_side", side).apply();
        mirrorNavSide();
    }
    /** The accessibility service runs in another process and reads the edge from the shared services state. */
    void mirrorNavSide() { ServicesState.prefs(context).edit().putString("nav_side", homeTabSide()).apply(); }

    /** Apps opened from the launcher, newest first, as flattened component names. */
    /** Speed limit source: "auto" (from the road), "manual" (manualSpeedLimit) or "off". */
    String speedLimitMode() { return preferences.getString("speed_limit_mode", "auto"); }
    void setSpeedLimitMode(String mode) { preferences.edit().putString("speed_limit_mode", mode).apply(); }
    /** A short chime when over the limit for a few seconds. */
    boolean speedChime() { return preferences.getBoolean("speed_chime", true); }
    void setSpeedChime(boolean on) { preferences.edit().putBoolean("speed_chime", on).apply(); }
    /** Expressways show 60 km/h in rain, as the road authority advises. */
    boolean rainLimit() { return preferences.getBoolean("rain_limit", true); }
    void setRainLimit(boolean on) { preferences.edit().putBoolean("rain_limit", on).apply(); }

    /** Resume OABL Music when the box starts. */
    boolean resumeMusicOnStart() { return preferences.getBoolean("start_music", false); }
    void setResumeMusicOnStart(boolean on) { preferences.edit().putBoolean("start_music", on).apply(); }
    /** Open the navigation app when the box starts. */
    boolean openNavigationOnStart() { return preferences.getBoolean("start_navigation", false); }
    void setOpenNavigationOnStart(boolean on) { preferences.edit().putBoolean("start_navigation", on).apply(); }
    int startActionsBoot() { return preferences.getInt("start_actions_boot", -1); }
    void setStartActionsBoot(int boot) { preferences.edit().putInt("start_actions_boot", boot).apply(); }

    /** Apps hidden from the app drawer (flattened component names). */
    java.util.Set<String> hiddenApps() {
        return new java.util.HashSet<>(preferences.getStringSet("hidden_apps", java.util.Collections.emptySet()));
    }

    void setHidden(String component, boolean hidden) {
        java.util.Set<String> all = hiddenApps();
        if (hidden) all.add(component); else all.remove(component);
        preferences.edit().putStringSet("hidden_apps", all).apply();
    }

    java.util.List<String> dockRecents() {
        java.util.List<String> recents = new java.util.ArrayList<>();
        for (String line : preferences.getString("dock_recents", "").split("\n")) {
            if (!line.trim().isEmpty()) recents.add(line);
        }
        return recents;
    }

    void addDockRecent(String component) {
        java.util.List<String> recents = dockRecents();
        recents.remove(component);
        recents.add(0, component);
        while (recents.size() > 8) recents.remove(recents.size() - 1);
        preferences.edit().putString("dock_recents", android.text.TextUtils.join("\n", recents)).apply();
    }

    void removeDockRecent(String component) {
        java.util.List<String> recents = dockRecents();
        recents.remove(component);
        preferences.edit().putString("dock_recents", android.text.TextUtils.join("\n", recents)).apply();
    }

    /** Saved navigation places, entered by the user on the Home navigation card. */
    String navigationHome() { return preferences.getString("nav_home", null); }
    String navigationWork() { return preferences.getString("nav_work", null); }
    /** A typed address: its exact point is unknown, so any saved point is cleared. */
    void setNavigationHome(String address) { preferences.edit().putString("nav_home", blankToNull(address)).remove("nav_home_point").apply(); }
    void setNavigationWork(String address) { preferences.edit().putString("nav_work", blankToNull(address)).remove("nav_work_point").apply(); }

    /** The exact point picked on a map, from Google Maps or from the car's location; null for a typed address. */
    double[] navigationPoint(boolean home) {
        String value = preferences.getString(home ? "nav_home_point" : "nav_work_point", null);
        if (value == null) return null;
        try {
            String[] parts = value.split(",");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    void setNavigationPoint(boolean home, double lat, double lon, String label) {
        preferences.edit().putString(home ? "nav_home" : "nav_work", blankToNull(label) == null ? String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon) : label.trim())
                .putString(home ? "nav_home_point" : "nav_work_point", String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)).apply();
    }

    /** Destinations started from the Home card, newest first. */
    java.util.List<String> navigationRecents() {
        java.util.List<String> recents = new java.util.ArrayList<>();
        String saved = preferences.getString("nav_recents", "");
        for (String line : saved.split("\n")) {
            if (!line.trim().isEmpty()) recents.add(line);
        }
        return recents;
    }

    void addNavigationRecent(String destination) {
        String value = blankToNull(destination);
        if (value == null) return;
        java.util.List<String> recents = navigationRecents();
        recents.remove(value);
        recents.add(0, value.replace('\n', ' '));
        while (recents.size() > 5) recents.remove(recents.size() - 1);
        preferences.edit().putString("nav_recents", android.text.TextUtils.join("\n", recents)).apply();
    }

    void removeNavigationRecent(String destination) {
        java.util.List<String> recents = navigationRecents();
        recents.remove(destination);
        preferences.edit().putString("nav_recents", android.text.TextUtils.join("\n", recents)).apply();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** Aurora replaces any photo with a generated, accent-tinted gradient. */
    boolean auroraBackground() { return preferences.getBoolean("aurora_background", false); }
    void setAuroraBackground(boolean enabled) { preferences.edit().putBoolean("aurora_background", enabled).apply(); }

    boolean visualEffects() {
        // A hot box holds effects back until it cools (see BoxThermal); the saved choice is kept.
        return preferences.getBoolean(VISUAL_EFFECTS, true) && !BoxThermal.throttled;
    }

    /** The saved Visual effects choice, ignoring a temporary overheat hold. */
    boolean visualEffectsChoice() {
        return preferences.getBoolean(VISUAL_EFFECTS, true);
    }

    void setVisualEffects(boolean enabled) {
        preferences.edit().putBoolean(VISUAL_EFFECTS, enabled).apply();
    }

    int themeMode() {
        // New installs follow the requested fixed IST day/night schedule.
        return preferences.getInt(THEME_MODE, THEME_AUTO);
    }

    void setThemeMode(int mode) {
        preferences.edit().putInt(THEME_MODE, mode).apply();
    }

    boolean homeRolePrompted() {
        return preferences.getBoolean(HOME_ROLE_PROMPTED, false);
    }

    int manualSpeedLimit() { return preferences.getInt("manual_speed_limit", 0); }
    int accentIndex() { return Math.max(0, Math.min(LauncherTheme.MATCH_WALLPAPER, preferences.getInt("accent_index", 0))); }
    float displayRefreshPreference() { return preferences.getFloat("display_refresh_hz", 0); }
    void setDisplayRefreshPreference(float hz) { preferences.edit().putFloat("display_refresh_hz", Float.isFinite(hz) && hz >= 0 ? hz : 0).apply(); }
    void setAccentIndex(int index) { preferences.edit().putInt("accent_index", Math.max(0, Math.min(LauncherTheme.MATCH_WALLPAPER, index))).apply(); }
    int vehicleMaximumSpeed() { return Math.max(40, Math.min(400, preferences.getInt("vehicle_maximum_speed", 140))); }
    void setVehicleMaximumSpeed(int speed) { preferences.edit().putInt("vehicle_maximum_speed", Math.max(40, Math.min(400, speed))).apply(); }
    void setManualSpeedLimit(int value) { preferences.edit().putInt("manual_speed_limit", Math.max(0, value)).apply(); }
    boolean weatherEnabled() { return preferences.getBoolean("weather_enabled", false); }
    void setWeatherEnabled(boolean value) { preferences.edit().putBoolean("weather_enabled", value).apply(); }

    void setHomeRolePrompted() {
        preferences.edit().putBoolean(HOME_ROLE_PROMPTED, true).apply();
    }

    int widgetId(int slot) {
        return preferences.getInt(WIDGET + slot, -1);
    }

    void setWidgetId(int slot, int id) {
        preferences.edit().putInt(WIDGET + slot, id).apply();
    }

    private void putComponent(String key, ComponentName component) {
        preferences.edit()
                .putString(key, component == null ? null : component.flattenToString())
                .apply();
    }

    private static ComponentName unflatten(String value) {
        return value == null ? null : ComponentName.unflattenFromString(value);
    }
}
