package com.softiva.oabl;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Runtime palette and lightweight drawables for instant dark/light switching. */
final class LauncherTheme {
    static final class Palette {
        final boolean light;
        final int root;
        final int ink;
        final int muted;
        final int accent;
        final int surface;
        final int surfaceStrong;
        final int surfacePressed;
        final int border;

        Palette(boolean light, int root, int ink, int muted, int accent, int surface,
                int surfaceStrong, int surfacePressed, int border) {
            this.light = light;
            this.root = root;
            this.ink = ink;
            this.muted = muted;
            this.accent = accent;
            this.surface = surface;
            this.surfaceStrong = surfaceStrong;
            this.surfacePressed = surfacePressed;
            this.border = border;
        }
    }

    private LauncherTheme() { }
    static final String[] ACCENT_NAMES = {"Titanium Silver", "Midnight Blue", "Amethyst Metallic", "Burgundy Red", "Champagne Gold", "Racing Green", "Copper Bronze", "Match wallpaper"};
    static final int MATCH_WALLPAPER = 7;
    /** Colour picked from the current wallpaper; Racing Green until a wallpaper has been seen. */
    private static volatile int wallpaperColor = 0xff3d6c59;
    /** Base accent forced by the car's drive mode, or 0 when the chosen accent should stand. */
    private static volatile int driveModeAccent = 0;

    /**
     * Overrides the chosen accent with the car's drive-mode colour (0 clears it). Set from the live
     * link when "match the car's drive mode" is on; {@link #resolve} tones it for light or dark.
     */
    static void setDriveModeAccent(int color) { driveModeAccent = color; }
    static int driveModeAccent() { return driveModeAccent; }

    /** Keeps a drive-mode accent legible on either palette: capped brightness on day, lifted on night. */
    private static int toneDriveMode(int base, boolean light) {
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        if (light) {
            hsv[2] = Math.min(hsv[2], .62f);
        } else {
            hsv[1] = Math.min(hsv[1], .6f);
            hsv[2] = Math.max(hsv[2], .8f);
        }
        return Color.HSVToColor(hsv);
    }

    static int accent(boolean light, int index) {
        if (index == MATCH_WALLPAPER) {
            // Deeper on light backgrounds, lighter on dark ones, so text and arcs stay legible.
            float[] hsv = new float[3];
            Color.colorToHSV(wallpaperColor, hsv);
            hsv[1] = Math.max(.25f, Math.min(.65f, hsv[1]));
            hsv[2] = light ? Math.min(.55f, Math.max(.4f, hsv[2] * .7f)) : Math.max(.78f, hsv[2]);
            if (!light) hsv[1] = Math.min(hsv[1], .4f);
            return Color.HSVToColor(hsv);
        }
        int[] day = {0xff52606d, 0xff345d80, 0xff6b587a, 0xff8b4055, 0xff806437, 0xff3d6c59, 0xff915b42};
        int[] night = {0xffc2ccd5, 0xff9ebbd2, 0xffc1b0ce, 0xffd7a4b0, 0xffd9c6a1, 0xffa9cbb9, 0xffd6b09a};
        return (light ? day : night)[Math.max(0, Math.min(6, index))];
    }

    /** Stores the wallpaper's dominant colour; returns true when it changed. */
    static boolean rememberWallpaper(Context context, android.graphics.Bitmap bitmap) {
        if (bitmap == null) return false;
        int color = ImageBlur.vibrant(bitmap);
        context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE).edit().putInt("wallpaper_color", color).apply();
        boolean changed = color != wallpaperColor;
        wallpaperColor = color;
        return changed;
    }

    static void loadWallpaperColor(Context context) {
        wallpaperColor = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE).getInt("wallpaper_color", wallpaperColor);
    }
    static GradientDrawable metallicFinish(int index) {
        int base = accent(true, index);
        GradientDrawable finish = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{mix(Color.BLACK, base, .55f), mix(Color.WHITE, base, .45f), base});
        finish.setCornerRadius(12);
        return finish;
    }

    static Palette resolve(Context context, LauncherPreferences preferences) {
        return resolve(context, preferences, System.currentTimeMillis());
    }
    static boolean scheduledLight(long now) {
        java.util.Calendar clock = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+05:30"));
        clock.setTimeInMillis(now);
        int minutes = clock.get(java.util.Calendar.HOUR_OF_DAY)*60 + clock.get(java.util.Calendar.MINUTE);
        return minutes >= 8*60+30 && minutes < 17*60;
    }
    static Palette resolve(Context context, LauncherPreferences preferences, long now) {
        int mode = preferences.themeMode();
        boolean light;
        if (mode == LauncherPreferences.THEME_LIGHT) {
            light = true;
        } else if (mode == LauncherPreferences.THEME_DARK) {
            light = false;
        } else {
            // The Android 10 head unit's calibrated ILL+ signal follows the actual headlights and
            // reacts in tunnels. When Link is unavailable, keep the existing offline sun fallback.
            double illumination = EnergyClient.link(context).liveValue(CarSignal.ILLUMINATION);
            if (!Double.isNaN(illumination)) {
                light = illumination < 0.5;
            } else {
                SunTimes sun = SunTimes.today(context, now);
                light = sun != null ? sun.daylight(now) : scheduledLight(now);
            }
        }
        // The car's drive mode, when matched, overrides the chosen accent; otherwise the chosen one stands.
        int accent = driveModeAccent != 0 ? toneDriveMode(driveModeAccent, light) : accent(light, preferences.accentIndex());
        // Pearl day and graphite night (EV cockpit). Cards are near-opaque so text stays legible
        // over any wallpaper without a live blur; muted text keeps at least 6.5:1 contrast.
        if (light) {
            return new Palette(true, 0xffe7eaed, 0xff13202a, 0xff4f5e6a,
                    accent, 0xf2f8f9fa, 0xfaffffff, 0xffdbe2e7, 0x2613202a);
        }
        return new Palette(false, 0xff0e1217, 0xffeef2f5, 0xffa3aeb9,
                accent, 0xeb171d24, 0xf2202831, 0xff2a333d, 0x26ffffff);
    }

    static StateListDrawable tile(Context context, Palette palette) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(context, palette.surfacePressed, 18, 0, 0));
        states.addState(new int[]{android.R.attr.state_focused},
                rounded(context, palette.surfacePressed, 18, 2, palette.accent));
        states.addState(new int[0],
                rounded(context, palette.surfaceStrong, 18, 0, Color.TRANSPARENT));
        return states;
    }

    static GradientDrawable glass(Context context, Palette palette) {
        return rounded(context, palette.surface, 18, 0, Color.TRANSPARENT);
    }

    /** A raised control inside a card (chips, secondary buttons): one step lighter, no outline. */
    static StateListDrawable raised(Context context, Palette palette, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(context, palette.surfacePressed, radius, 0, 0));
        states.addState(new int[]{android.R.attr.state_focused}, rounded(context, palette.surfacePressed, radius, 2, palette.accent));
        states.addState(new int[0], rounded(context, palette.surfaceStrong, radius, 0, 0));
        return states;
    }

    static StateListDrawable dockItem(Context context, Palette palette) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(context, palette.surfacePressed, 16, 0, 0));
        states.addState(new int[]{android.R.attr.state_focused},
                rounded(context, palette.surfacePressed, 16, 2, palette.accent));
        states.addState(new int[0], rounded(context, Color.TRANSPARENT, 16, 0, 0));
        return states;
    }

    static StateListDrawable playback(Context context, Palette palette) {
        StateListDrawable states = new StateListDrawable();
        int highlighted = palette.accent;
        states.addState(new int[]{android.R.attr.state_pressed}, oval(highlighted));
        states.addState(new int[]{android.R.attr.state_focused}, oval(highlighted));
        states.addState(new int[0], oval(palette.accent));
        return states;
    }

    static GradientDrawable overlayGlass(Context context, Palette palette) {
        return rounded(context, palette.light ? 0xeff8fbfb : 0xe610151b,
                20, palette.light ? 2 : 1, palette.border);
    }

    static StateListDrawable circle(Context context, Palette palette) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                oval(palette.light ? (palette.accent & 0x00ffffff) | 0x33000000 : 0x66ffffff));
        states.addState(new int[0], oval(palette.light ? (palette.accent & 0x00ffffff) | 0x15000000 : 0x30ffffff));
        return states;
    }

    static StateListDrawable primary(Context context, Palette palette) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(context, mix(palette.root, palette.accent, palette.light ? .9f : .4f),
                        20, 1, palette.accent));
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                palette.light
                        ? new int[]{palette.accent, mix(Color.BLACK, palette.accent, .85f)}
                        : new int[]{mix(palette.root, palette.accent, .25f), mix(palette.root, palette.accent, .12f)});
        normal.setCornerRadius(dp(context, 20));
        states.addState(new int[0], normal);
        return states;
    }
    private static int mix(int base, int color, float amount) {
        return Color.rgb(Math.round(Color.red(base)*(1-amount)+Color.red(color)*amount),
                Math.round(Color.green(base)*(1-amount)+Color.green(color)*amount),
                Math.round(Color.blue(base)*(1-amount)+Color.blue(color)*amount));
    }

    static GradientDrawable speed(Context context, Palette palette) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ring.setGradientRadius(dp(context, 120));
        ring.setColors(palette.light
                ? new int[]{0xe8ffffff, 0xb8d7e6e7}
                : new int[]{0xb51a2026, 0x6b101419});
        ring.setStroke(dpInt(context, palette.light ? 3 : 2), palette.accent);
        return ring;
    }

    static GradientDrawable drawerBackground(Palette palette) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                palette.light
                        ? new int[]{0xfff3f7f8, 0xffe4ecef}
                        : new int[]{0xff11171d, 0xff0d1116});
    }

    static GradientDrawable sectionBar(Context context, Palette palette) {
        return rounded(context, palette.light ? 0xcfffffff : Color.TRANSPARENT,
                14, palette.light ? 1 : 0, palette.border);
    }

    static GradientDrawable scrim(Palette palette, boolean defaultWallpaper) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                palette.light
                        ? (defaultWallpaper
                            ? new int[]{0x38ffffff, 0x06ffffff, 0x54eef3f4}
                            : new int[]{0xe5eef4f5, 0x12ffffff, 0x50ecf2f2})
                        : new int[]{0xe610161c, 0x2410161a, 0x900a0d11});
    }

    static GradientDrawable rounded(Context context, int color, int radius,
                                            int strokeWidth, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (strokeWidth > 0) {
            shape.setStroke(dpInt(context, strokeWidth), strokeColor);
        }
        return shape;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private static float dp(Context context, int value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private static int dpInt(Context context, int value) {
        return Math.round(dp(context, value));
    }
}
