package com.softiva.oabl;

import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Quick controls that slide up from the command bar: media volume, EQ presets, split screen,
 * connectivity shortcuts, theme, performance mode, energy and launcher settings. A fixed sheet:
 * presets and tiles wrap into rows that fit the window, so nothing scrolls.
 */
final class ControlsSheet {
    private final MainActivity host;
    private final LauncherTheme.Palette palette;
    private final LauncherPreferences preferences;
    private final float density;
    private Dialog dialog;
    private LinearLayout sheet;
    private LinearLayout presetRow;

    private ControlsSheet(MainActivity host, LauncherTheme.Palette palette) {
        this.host = host;
        this.palette = palette;
        this.preferences = new LauncherPreferences(host);
        this.density = host.getResources().getDisplayMetrics().density;
    }

    static void show(MainActivity host, LauncherTheme.Palette palette) {
        new ControlsSheet(host, palette).open();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void open() {
        dialog = new Dialog(host, android.R.style.Theme_Material_NoActionBar);
        FrameLayout frame = new FrameLayout(host);
        frame.setOnClickListener(v -> close());
        sheet = new LinearLayout(host);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setPadding(dp(24), dp(18), dp(24), dp(20));
        GradientDrawable background = new GradientDrawable();
        // Opaque frost avoids dashboard text ghosting through controls; no live blur pass.
        background.setColor(palette.light ? 0xfff7f9fa : 0xff151b22);
        background.setCornerRadius(dp(28));
        sheet.setBackground(background);
        sheet.setElevation(dp(16));

        LinearLayout header = row();
        TextView title = text("Controls", 20, palette.ink, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView done = chip("Done", 0, false);
        done.setOnClickListener(v -> close());
        header.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        sheet.addView(header);

        // No brightness here: the car screen shows the CarPlay video and sets its own brightness.
        sheet.addView(volume(), spaced(14));

        TextView sound = text("SOUND", 10, palette.muted, true);
        sound.setLetterSpacing(.14f);
        sheet.addView(sound, spaced(16));
        int width = Math.min(dp(1100), host.getResources().getDisplayMetrics().widthPixels - dp(32));
        boolean narrow = width < dp(820);
        presetColumns = narrow ? 4 : 6;
        presetRow = new LinearLayout(host);
        presetRow.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(presetRow, spaced(8));
        renderPresets();

        boolean wifi = wifiOn(), bluetooth = bluetoothOn(), hotspot = hotspotOn();
        TravelStatusMonitor.Snapshot travel = host.travelNow();
        String bluetoothName = travel.bluetooth != null && travel.bluetooth.startsWith("BT ") && !travel.bluetooth.startsWith("BT details")
                ? travel.bluetooth.substring(3) : null;
        View[] all = {
                tile(R.drawable.ic_dock_split, "Split pair", host.splitPairSummary(), false, () -> { close(); host.launchSplitPairFromControls(); },
                        () -> { close(); host.editSplitPairFromControls(); }),
                tile(R.drawable.ic_ctl_wifi, wifi ? "Wi-Fi on" : "Wi-Fi off",
                        !wifi ? "Off" : travel.wifiConnected && travel.wifiName != null ? travel.wifiName : "Not connected", wifi, () -> { close(); openPanel(true); }, null),
                tile(R.drawable.ic_ctl_bluetooth, bluetooth ? "Bluetooth on" : "Bluetooth off",
                        !bluetooth ? "Off" : bluetoothName != null ? bluetoothName : "No device", bluetooth, () -> { close(); openPanel(false); }, null),
                tile(R.drawable.ic_ctl_hotspot, hotspot ? "Hotspot on" : "Hotspot", hotspot ? "Sharing" : "Off", hotspot, () -> { close(); openHotspot(); }, null),
                tile(R.drawable.ic_ctl_dnd, "Do not disturb", dndOn() ? "On" : "Off", dndOn(), this::toggleDnd, null),
                tile(R.drawable.ic_ctl_theme, themeLabel(), themeHint(), false, this::cycleTheme, null),
                tile(R.drawable.ic_ctl_performance, BoxThermal.throttled ? "Cooling down"
                        : preferences.visualEffectsChoice() ? "Visuals" : "Performance",
                        BoxThermal.throttled ? "Effects reduced" : preferences.visualEffectsChoice() ? "Full effects" : "Cuts motion and blur",
                        !preferences.visualEffects(), () -> { host.togglePerformanceFromControls(); refreshTiles(); }, null),
                tile(R.drawable.ic_bar_energy, "Energy", "Cost and drives", false, () -> { close(); host.openEnergyFromControls(); }, null)};
        // One row of eight, or two rows of four in a split window.
        LinearLayout tiles = new LinearLayout(host);
        tiles.setOrientation(LinearLayout.VERTICAL);
        tiles.setTag("controls_tiles");
        int perRow = narrow ? 4 : all.length;
        for (int i = 0; i < all.length; i += perRow) {
            LinearLayout line = row();
            for (int j = i; j < Math.min(all.length, i + perRow); j++) line.addView(all[j], tileParams());
            tiles.addView(line, spaced(i == 0 ? 0 : 8));
        }
        sheet.addView(tiles, spaced(18));

        TextView settings = chip("Launcher settings", R.drawable.ic_dock_settings, false);
        settings.setOnClickListener(v -> { close(); host.showLauncherSettingsFromControls(); });
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        settingsSize.topMargin = dp(16);
        settingsSize.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(settings, settingsSize);

        FrameLayout.LayoutParams sheetSize = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetSize.bottomMargin = dp(16);
        frame.addView(sheet, sheetSize);
        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(palette.light ? .25f : .45f);
            ImmersiveWindow.apply(window, frame);
        }
        MapsTile.hideWhileOpen(dialog);
        dialog.show();
        DrivingDismiss.track(dialog);
        if (preferences.visualEffects()) {
            sheet.setTranslationY(dp(420));
            sheet.animate().translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    private void close() {
        if (dialog == null || !dialog.isShowing()) return;
        if (!preferences.visualEffects()) {
            dialog.dismiss();
            return;
        }
        sheet.animate().translationY(sheet.getHeight() + dp(24)).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(dialog::dismiss).start();
    }

    // ---- sliders ----

    private View volume() {
        final AudioManager audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        int max = audio == null ? 15 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return slider(R.drawable.ic_ctl_volume, "Media volume", max, current, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private View slider(int icon, String label, int max, int value, SeekBar.OnSeekBarChangeListener listener) {
        LinearLayout line = row();
        line.setPadding(dp(14), 0, dp(6), 0);
        line.setBackground(rounded(palette.light ? 0x10142e38 : 0x14ffffff, 22));
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(palette.accent));
        line.addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
        SeekBar bar = new SeekBar(host);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setContentDescription(label);
        bar.setSplitTrack(false);
        bar.setProgressTintList(ColorStateList.valueOf(palette.accent));
        bar.setThumbTintList(ColorStateList.valueOf(palette.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf((palette.muted & 0x00ffffff) | 0x55000000));
        TextView percent = text(percent(value, max), 14, palette.ink, true);
        percent.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        percent.setFontFeatureSettings("tnum");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) { percent.setText(percent(v, max)); listener.onProgressChanged(b, v, user); }
            @Override public void onStartTrackingTouch(SeekBar b) { listener.onStartTrackingTouch(b); }
            @Override public void onStopTrackingTouch(SeekBar b) { listener.onStopTrackingTouch(b); }
        });
        line.addView(bar, new LinearLayout.LayoutParams(0, dp(52), 1));
        line.addView(percent, new LinearLayout.LayoutParams(dp(52), dp(52)));
        return line;
    }

    private static String percent(int value, int max) {
        return Math.round(100f * value / Math.max(1, max)) + "%";
    }

    // ---- equalizer presets ----

    private int presetColumns = 6;
    private final java.util.List<View> presetChips = new java.util.ArrayList<>();

    private void renderPresets() {
        presetRow.removeAllViews();
        presetChips.clear();
        EqualizerProfile profile = new EqualizerProfile(host);
        String selected = profile.enabled() ? profile.preset() : "Off";
        addPreset("Off", "Off".equals(selected), () -> {
            profile.setEnabled(false);
            applyEq(profile);
        });
        for (int i = 0; i < EqualizerProfile.PRESETS.length; i++) {
            final int index = i;
            String name = EqualizerProfile.PRESETS[i];
            addPreset(name, name.equals(selected), () -> {
                profile.preset(index);
                profile.setEnabled(true);
                applyEq(profile);
            });
        }
        if ("Custom".equals(selected)) addPreset("Custom", true, () -> { });
        TextView open = chip("Equalizer  ▸", R.drawable.ic_dock_equalizer, false);
        open.setTag("equalizer_shortcut");
        open.setOnClickListener(v -> { close(); host.openEqualizer(); });
        presetChips.add(open);
        // Rows of equal chips instead of a sideways scroll.
        for (int i = 0; i < presetChips.size(); i += presetColumns) {
            LinearLayout line = row();
            for (int j = i; j < i + presetColumns; j++) {
                View chip = j < presetChips.size() ? presetChips.get(j) : new View(host);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (j < i + presetColumns - 1) size.setMarginEnd(dp(8));
                line.addView(chip, size);
            }
            presetRow.addView(line, spaced(i == 0 ? 0 : 8));
        }
    }

    private void addPreset(String name, boolean selected, Runnable action) {
        TextView chip = chip(name, 0, selected);
        chip.setOnClickListener(v -> action.run());
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        presetChips.add(chip);
    }

    private void applyEq(EqualizerProfile profile) {
        profile.flush();
        SoundEqualizerService.refresh(host);
        MusicClient.get(host).effectsChanged();
        renderPresets();
    }

    // ---- tiles ----

    private String themeHint() {
        int mode = preferences.themeMode();
        return mode == LauncherPreferences.THEME_DARK ? "Graphite night" : mode == LauncherPreferences.THEME_LIGHT ? "Pearl day" : "Follows the sun";
    }

    private View tile(int icon, String label, boolean active, Runnable click, Runnable longClick) {
        return tile(icon, label, null, active, click, longClick);
    }

    /** A tile with a quiet second line that says what it is doing now. */
    private View tile(int icon, String label, String state, boolean active, Runnable click, Runnable longClick) {
        LinearLayout tile = new LinearLayout(host);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(10), dp(4), dp(8));
        tile.setBackground(ripple(rounded(active ? (palette.accent & 0x00ffffff) | 0x30000000
                : palette.light ? 0x10142e38 : 0x14ffffff, 20)));
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(active ? palette.accent : palette.ink));
        tile.addView(glyph, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(label, 12, active ? palette.accent : palette.ink, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameSize.topMargin = dp(8);
        tile.addView(name, nameSize);
        if (state != null && !state.isEmpty()) {
            TextView detail = text(state, 12, palette.muted, false);
            detail.setGravity(Gravity.CENTER);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setPadding(dp(4), 0, dp(4), 0);
            tile.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tile.setContentDescription(state == null || state.isEmpty() ? label : label + ", " + state);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> click.run());
        if (longClick != null) tile.setOnLongClickListener(v -> { longClick.run(); return true; });
        return tile;
    }

    private void refreshTiles() {
        close();
        sheet.postDelayed(() -> show(host, host.currentPalette()), 260);
    }

    private boolean dndOn() {
        NotificationManager manager = host.getSystemService(NotificationManager.class);
        return manager != null && manager.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private void toggleDnd() {
        NotificationManager manager = host.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (!manager.isNotificationPolicyAccessGranted()) {
            Toast.makeText(host, "Allow Do Not Disturb access for Open AI Box Launcher", Toast.LENGTH_LONG).show();
            close();
            host.openSystemSettingFromControls(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            return;
        }
        manager.setInterruptionFilter(dndOn() ? NotificationManager.INTERRUPTION_FILTER_ALL
                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        refreshTiles();
    }

    private String themeLabel() {
        int mode = preferences.themeMode();
        return mode == LauncherPreferences.THEME_DARK ? "Dark" : mode == LauncherPreferences.THEME_LIGHT ? "Light" : "Auto theme";
    }

    private void cycleTheme() {
        int mode = preferences.themeMode();
        int next = mode == LauncherPreferences.THEME_AUTO ? LauncherPreferences.THEME_DARK
                : mode == LauncherPreferences.THEME_DARK ? LauncherPreferences.THEME_LIGHT : LauncherPreferences.THEME_AUTO;
        host.setThemeFromControls(next);
        refreshTiles();
    }

    private boolean wifiOn() {
        android.net.wifi.WifiManager wifi = host.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        return wifi != null && wifi.isWifiEnabled();
    }

    private boolean bluetoothOn() {
        try {
            android.bluetooth.BluetoothManager manager = host.getSystemService(android.bluetooth.BluetoothManager.class);
            return manager != null && manager.getAdapter() != null && manager.getAdapter().isEnabled();
        } catch (SecurityException noPermission) {
            return false;
        }
    }

    /** Android has no public hotspot state; an access-point interface being up is a reliable sign. */
    static boolean hotspotOn() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> all = java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface item = all.nextElement();
                String name = item.getName();
                if (item.isUp() && (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap"))
                        && item.getInterfaceAddresses().size() > 0) return true;
            }
        } catch (java.net.SocketException | RuntimeException ignored) { }
        return false;
    }

    /** The tethering screen: its intent is not public, so try the known ones in turn. */
    private void openHotspot() {
        Intent[] options = {
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent().setComponent(new android.content.ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS)};
        for (Intent intent : options) {
            try {
                host.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException unavailable) {
                // Try the next screen.
            }
        }
    }

    private void openPanel(boolean wifi) {
        String action = wifi
                ? (Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS)
                : Settings.ACTION_BLUETOOTH_SETTINGS;
        host.openSystemSettingFromControls(action);
    }

    // ---- small view helpers ----

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView text = new TextView(host);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (medium) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private TextView chip(String value, int icon, boolean selected) {
        TextView chip = text(value, 13, selected ? palette.accent : palette.ink, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(14), 0, dp(14), 0);
        if (icon != 0) {
            Drawable glyph = host.getDrawable(icon).mutate();
            glyph.setTint(palette.accent);
            glyph.setBounds(0, 0, dp(18), dp(18));
            chip.setCompoundDrawablesRelative(glyph, null, null, null);
            chip.setCompoundDrawablePadding(dp(7));
        }
        chip.setBackground(ripple(rounded(selected ? (palette.accent & 0x00ffffff) | 0x30000000
                : palette.light ? 0x10142e38 : 0x14ffffff, 18)));
        chip.setSelected(selected);
        chip.setContentDescription(value);
        return chip;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x22142e38 : 0x33ffffff), content, null);
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(84), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }
}
