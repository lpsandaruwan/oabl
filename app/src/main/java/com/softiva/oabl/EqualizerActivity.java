package com.softiva.oabl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One 31-band equalizer for music players, plus the exciter for OABL Music
 * lift. Live audio is owned by {@link SoundEqualizerService}; this screen only edits the saved profile.
 */
public final class EqualizerActivity extends Activity {
    private static final int FIND_SOUND = 812;
    private EqualizerProfile profile;
    private EqualizerModel model;
    private LauncherTheme.Palette palette;
    private SharedPreferences ui;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusChip, preampLabel, source, linkSelector, gainLabel, normalizeSelector, bandLabel, bandGain,
            help, exciterValue, exciterHzSelector, bassValue, headroomValue, compare;
    private Switch headroomSwitch;
    private EqualizerCurveView curve;
    private EqualizerSlidersView sliders;
    private HorizontalScrollView curveScroll;
    private LinearLayout presetRow;
    private final List<TextView[]> segments = new ArrayList<>();
    /** The page below the header: fixed, never scrolled (only the preset strip and curve pan sideways). */
    private LinearLayout page;
    private AutoThemeWatcher themeWatcher;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private TextView undoButton, redoButton;
    private LinearLayout advanced;
    private HandlerThread spectrumThread;
    private Handler spectrumWorker;
    private Visualizer visualizer;
    private final Runnable apply = () -> {
        SoundEqualizerService.refresh(this);
        MusicClient.get(this).effectsChanged();
        updateStatus();
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1500); }
    };

    /** Everything an edit can change, for undo/redo. */
    private static final class Snapshot {
        float[] curve; String preset; float[] effects;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        profile = new EqualizerProfile(this);
        model = new EqualizerModel(profile);
        ui = getSharedPreferences("equalizer_ui", MODE_PRIVATE);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        themeWatcher = new AutoThemeWatcher(this, () -> {
            LauncherTheme.Palette next = LauncherTheme.resolve(this, new LauncherPreferences(this));
            if (palette.light != next.light || palette.accent != next.accent) recreate();
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().getDecorView().setForceDarkAllowed(false);
        getWindow().setNavigationBarColor(palette.root);
        getWindow().setStatusBarColor(palette.root);
        rememberPlayerIntent(getIntent());
        buildUi();
        // Once, after the move to one 14-band curve: the earlier sound is kept as a preset.
        if (profile.takeFourteenBandNotice()) {
            getWindow().getDecorView().post(() -> dialog().setTitle("Equalizer updated")
                    .setMessage("One 14-band curve now covers your music players. Your earlier sound is kept as the preset “"
                            + EqualizerProfile.FOURTEEN_BAND_BACKUP + "”.")
                    .setPositiveButton("OK", null).show());
        }
        if (state != null) {
            // Both editors: selecting on the curve consults the sliders, which aren't hidden yet.
            sliders.select(state.getInt("band", 7));
            curve.select(state.getInt("band", 7));
            page.post(() -> curveScroll.scrollTo(state.getInt("curveX"), 0));
        }
        renderAll();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("band", curve.selected());
        state.putInt("curveX", curveScroll.getScrollX());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DevicePerformance.apply(this, getWindow());
        SoundEqualizerService.start(this);
        // Nothing is held on a fresh screen: a hold cut short by a rotation or a resize must not stick.
        if (profile.comparing()) compareMusic(false);
        handler.post(refresh);
        themeWatcher.start();
        startSpectrum();
    }

    @Override
    protected void onPause() {
        stopSpectrum();
        SoundEqualizerService.setBypass(this, false);
        if (profile.comparing()) compareMusic(false);
        themeWatcher.stop();
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(apply);
        profile.flush();
        SoundEqualizerService.refresh(this);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FIND_SOUND) {
            Switch enabled = getWindow().getDecorView().findViewWithTag("equalizer_enabled");
            if (enabled != null) enabled.setChecked(profile.enabled());
            renderAll();
            scheduleApply();
        }
    }

    private void rememberPlayerIntent(Intent intent) {
        if (intent == null || intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC) == AudioEffect.CONTENT_TYPE_VOICE) return;
        int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        if (session > 0) SoundEqualizerService.rememberSession(this, session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME));
    }

    // ================= layout =================

    private void buildUi() {
        int width = getResources().getConfiguration().screenWidthDp;
        boolean compact = width < 900, small = width < 600;
        int inset = compact ? 16 : 24;
        page = column();
        page.setTag("equalizer_page");
        page.setBackgroundColor(palette.root);
        LinearLayout screen = column();
        screen.setBackgroundColor(palette.root);
        LinearLayout content = column();
        content.setPadding(dp(inset), 0, dp(inset), dp(12));
        page.addView(content, lp(-1, -1));
        setContentView(screen);
        ImmersiveWindow.apply(getWindow(), screen);

        // Header: title, status chip, hold-to-compare, master switch.
        LinearLayout header = row();
        LinearLayout headerActions = compact ? row() : header;
        header.addView(icon(MusicIconView.Icon.BACK, "Back", this::finish, "eq_back"), lp(48, 56));
        LinearLayout heading = column();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(12), 0);
        TextView name = text("Equalizer", compact ? 28 : 32, palette.ink);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name);
        heading.addView(text(model.count() + " bands · ±6 dB", 12, palette.muted));
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(56), 1));
        statusChip = text("", 13, palette.ink);
        statusChip.setTag("eq_status_chip");
        statusChip.setPadding(dp(14), 0, dp(16), 0);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setCompoundDrawablePadding(dp(8));
        medium(statusChip);
        statusChip.setContentDescription("Audio source and processing status");
        statusChip.setOnClickListener(v -> showSource());
        headerActions.addView(statusChip, new LinearLayout.LayoutParams(compact ? 0 : -2, dp(48), compact ? 1 : 0));
        compare = pill("Hold to compare", false);
        compare.setTag("eq_compare");
        compare.setContentDescription("Hold to hear the original sound without the equalizer");
        installCompare(compare);
        LinearLayout.LayoutParams compareSize = lp(-2, 48);
        compareSize.leftMargin = dp(10);
        headerActions.addView(compare, compareSize);
        Switch enabled = new Switch(this);
        enabled.setTag("equalizer_enabled");
        // The chip beside it already says whether the equalizer is on; the switch needs no label.
        enabled.setContentDescription("Equalizer on or off");
        tint(enabled);
        enabled.setChecked(profile.enabled());
        enabled.setOnCheckedChangeListener((view, on) -> {
            profile.setEnabled(on);
            profile.flush();
            SoundEqualizerService.refresh(this);
            MusicClient.get(this).effectsChanged();
            updateStatus();
        });
        LinearLayout.LayoutParams switchSize = lp(116, 56);
        switchSize.leftMargin = dp(8);
        headerActions.addView(enabled, switchSize);
        LinearLayout fixedHeader = column();
        fixedHeader.setPadding(dp(inset), dp(12), dp(inset), 0);
        fixedHeader.addView(header);
        if (compact) { space(fixedHeader, 8); fixedHeader.addView(headerActions); }
        screen.addView(fixedHeader);
        screen.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        space(content, 12);

        // Preset cards.
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.setTag("eq_presets");
        presetRow = row();
        presetScroll.addView(presetRow);
        content.addView(presetScroll, lp(-1, 72));
        space(content, 12);

        // Every control stays on the page: view, bands, shaping and output.
        advanced = column();
        advanced.setTag("eq_sound_controls");
        LinearLayout advancedModes = row();
        LinearLayout tools = row();
        linkSelector = text("", 15, palette.ink);
        source = text("", 15, palette.ink);
        LinearLayout link = control("BAND LINKING", linkSelector, this::chooseLink, "eq_link");
        LinearLayout sources = control("AUDIO SOURCE", source, this::showSource, "eq_scope");
        weighted(tools, link, 1, 0, 56);
        weighted(tools, sources, 1, 10, 56);
        advancedModes.addView(segmented(new String[]{"Curve", "Sliders"}, new String[]{"eq_view_curve", "eq_view_sliders"},
                () -> ui.getBoolean("sliders", false) ? 1 : 0, i -> { ui.edit().putBoolean("sliders", i == 1).apply(); renderAll(); }), lp(-2, 56));
        undoButton = pill("↶", false);
        undoButton.setTextSize(26);
        undoButton.setTag("eq_undo");
        undoButton.setContentDescription("Undo");
        undoButton.setOnClickListener(v -> step(undo, redo));
        redoButton = pill("↷", false);
        redoButton.setTextSize(26);
        redoButton.setTag("eq_redo");
        redoButton.setContentDescription("Redo");
        redoButton.setOnClickListener(v -> step(redo, undo));
        LinearLayout.LayoutParams undoSize = lp(52, 48);
        undoSize.leftMargin = dp(10);
        tools.addView(undoButton, undoSize);
        LinearLayout.LayoutParams redoSize = lp(52, 48);
        redoSize.leftMargin = dp(6);
        tools.addView(redoButton, redoSize);
        MusicIconView reset = icon(MusicIconView.Icon.RESET, "Reset the curve to flat", this::resetBands, "eq_reset");
        reset.setBackground(LauncherTheme.tile(this, palette));
        LinearLayout.LayoutParams resetSize = lp(52, 52);
        resetSize.leftMargin = dp(6);
        tools.addView(reset, resetSize);
        if (compact) {
            HorizontalScrollView modesScroll = new HorizontalScrollView(this);
            modesScroll.setHorizontalScrollBarEnabled(false);
            modesScroll.setHorizontalFadingEdgeEnabled(true);
            modesScroll.addView(advancedModes);
            content.addView(modesScroll, lp(-1, 58));
            space(content, 8);
            content.addView(tools);
        } else {
            // One toolbar on the wide screen: view, then linking and history.
            LinearLayout toolbar = row();
            toolbar.addView(advancedModes, lp(-2, 56));
            LinearLayout.LayoutParams rest = new LinearLayout.LayoutParams(0, -2, 1);
            rest.leftMargin = dp(10);
            toolbar.addView(tools, rest);
            content.addView(toolbar);
        }
        space(content, 10);

        // Editor: the selected band on top, then the curve or sliders.
        LinearLayout editor = column();
        editor.setTag("eq_editor");
        editor.setBackground(LauncherTheme.rounded(this, palette.light ? 0xffe9eef2 : 0xff151e28, 22, 0, 0));
        // Header: the selected band and its gain, with the hint in between.
        LinearLayout selected = row();
        selected.setTag("eq_selected_band");
        selected.setPadding(dp(8), dp(4), dp(12), 0);
        bandLabel = text("", 17, palette.ink);
        LinearLayout band = control("SELECTED BAND", bandLabel, this::chooseBand, "eq_band");
        band.setBackground(null);
        selected.addView(band, lp(compact ? 200 : 260, 56));
        help = text("", 12, palette.muted);
        help.setSingleLine(true);
        help.setEllipsize(TextUtils.TruncateAt.END);
        help.setGravity(Gravity.CENTER);
        selected.addView(help, new LinearLayout.LayoutParams(0, dp(56), 1));
        if (compact) help.setVisibility(View.INVISIBLE);
        selected.addView(icon(MusicIconView.Icon.MINUS, "Reduce selected band by 0.5 dB", () -> curve.adjust(-.5f), "eq_band_minus"), lp(52, 52));
        bandGain = text("", 22, palette.accent);
        bandGain.setTag("eq_band_gain");
        bandGain.setTypeface(null, Typeface.BOLD);
        bandGain.setFontFeatureSettings("tnum");
        bandGain.setGravity(Gravity.CENTER);
        selected.addView(bandGain, lp(110, 52));
        selected.addView(icon(MusicIconView.Icon.ADD, "Increase selected band by 0.5 dB", () -> curve.adjust(.5f), "eq_band_plus"), lp(52, 52));
        editor.addView(selected);
        EqualizerCurveView.Listener listener = new EqualizerCurveView.Listener() {
            @Override public void beforeEdit() { remember(); }
            @Override public void changed() { onEdited(); }
            @Override public void committed() { profile.flush(); scheduleApply(); renderPresets(); }
        };
        // The curve takes what the fixed page leaves: header, presets, toolbar, band header and the panels.
        int chrome = compact ? 470 : 408;
        int editorHeight = Math.max(120, Math.min(260, getResources().getConfiguration().screenHeightDp - chrome));
        FrameLayout editorFrame = new FrameLayout(this);
        curveScroll = new HorizontalScrollView(this);
        curveScroll.setTag("eq_curve_scroll");
        curveScroll.setHorizontalScrollBarEnabled(false);
        curve = new EqualizerCurveView(this, palette, model, listener);
        curve.setTag("eq_curve");
        // HorizontalScrollView measures its child with an unspecified width. The
        // custom Canvas needs an intrinsic minimum, otherwise View measures to zero.
        // The 31 bands need about 30 dp each to be picked by touch: the full screen fits them, a split
        // window scrolls.
        int curveWidth = dp(Math.max(model.count() * 30, width - inset * 2));
        curve.setMinimumWidth(curveWidth);
        // The curve fills whatever height the fixed page leaves (at least editorHeight).
        curveScroll.addView(curve, new HorizontalScrollView.LayoutParams(curveWidth, -1));
        curveScroll.setFillViewport(true);
        editorFrame.setMinimumHeight(dp(editorHeight));
        editorFrame.addView(curveScroll, new FrameLayout.LayoutParams(-1, -1));
        sliders = new EqualizerSlidersView(this, palette, model, listener);
        sliders.setTag("eq_sliders");
        FrameLayout.LayoutParams slidersSize = new FrameLayout.LayoutParams(-1, -1);
        slidersSize.setMargins(dp(16), 0, dp(16), 0);
        editorFrame.addView(sliders, slidersSize);
        editor.addView(editorFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        space(editor, 8);
        content.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        space(content, 10);
        LinearLayout soundPanel = column(), outputPanel = column();

        // Effects: the exciter, which runs in OABL Music.
        exciterValue = text("", 15, palette.ink);
        exciterHzSelector = text("", 16, palette.ink);
        bassValue = text("", 15, palette.ink);
        LinearLayout sound = row();
        weighted(sound, sliderCard("BASS · OABL MUSIC", bassValue, "eq_bass", 20, Math.round(profile.bass() * 2),
                p -> { profile.setBass(p / 2f); markCustom(); }), 2, 0, panelHeight(compact));
        weighted(sound, sliderCard("EXCITER · OABL MUSIC", exciterValue, "eq_exciter", 20, Math.round(profile.exciter() * 20),
                p -> { profile.setExciter(p / 20f); markCustom(); }), 2, 10, panelHeight(compact));
        weighted(sound, control("EXCITER FROM", exciterHzSelector, () -> chooseHz("Exciter adds harmonics from", SoundEffects.EXCITER_HZ,
                profile.exciterHz(), profile::setExciterHz), "eq_exciter_hz"), 1, 10, panelHeight(compact));
        if (compact) {
            // Two readable controls, side by side or one to a row in the narrowest panes.
            LinearLayout shaped = column();
            while (sound.getChildCount() > 0) {
                LinearLayout line = row();
                int count = small ? 1 : 2;
                for (int i = 0; i < count && sound.getChildCount() > 0; i++) {
                    View child = sound.getChildAt(0); sound.removeViewAt(0);
                    weighted(line, child, 1, i == 0 ? 0 : 8, 86);
                }
                shaped.addView(line); space(shaped, 8);
            }
            soundPanel.addView(shaped);
        } else soundPanel.addView(sound);

        // Output: master gain, normalization, peak protection.
        LinearLayout gainCard = column();
        gainCard.setTag("eq_gain_card");
        gainCard.setPadding(dp(16), dp(10), dp(16), dp(4));
        gainCard.setBackground(LauncherTheme.tile(this, palette));
        LinearLayout gainHeading = row();
        gainHeading.addView(text("MASTER GAIN", 11, palette.muted), new LinearLayout.LayoutParams(0, dp(24), 1));
        gainLabel = text("", 15, palette.ink);
        gainLabel.setTypeface(null, Typeface.BOLD);
        gainLabel.setFontFeatureSettings("tnum");
        gainHeading.addView(gainLabel);
        gainCard.addView(gainHeading);
        SeekBar gain = new SeekBar(this);
        gain.setMax(60);
        gain.setProgress(Math.round((profile.masterGain() + 18) * 2));
        gain.setTag("master_gain");
        gain.setContentDescription("Master gain, minus 18 to plus 12 decibels");
        tint(gain);
        gain.setPadding(dp(4), 0, dp(4), 0);
        gain.setSplitTrack(false);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user) { profile.setMasterGain(p / 2f - 18); updateStatus(); scheduleApply(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { b.getParent().requestDisallowInterceptTouchEvent(true); }
            @Override public void onStopTrackingTouch(SeekBar b) {
                b.getParent().requestDisallowInterceptTouchEvent(false); profile.flush(); scheduleApply();
            }
        });
        gainCard.addView(gain, lp(-1, 48));
        normalizeSelector = text("", 16, palette.ink);
        LinearLayout normal = control("EVEN LOUDNESS", normalizeSelector, this::chooseNormalization, "eq_normalization");
        headroomValue = text("", 16, palette.ink);
        LinearLayout headroomCard = control("HEADROOM", headroomValue, () -> {
            profile.setHeadroom(!profile.headroom());
            profile.flush();
            updateStatus();
            scheduleApply();
        }, "eq_headroom");
        // A switch, not the dropdown arrow the other cards carry: this one just toggles.
        headroomCard.removeViewAt(headroomCard.getChildCount() - 1);
        headroomSwitch = new Switch(this);
        headroomSwitch.setClickable(false);
        headroomSwitch.setFocusable(false);
        headroomSwitch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tint(headroomSwitch);
        headroomCard.addView(headroomSwitch, lp(-2, 48));
        preampLabel = text("", 11, palette.muted);
        preampLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (compact) {
            outputPanel.addView(gainCard, lp(-1, 86));
            space(outputPanel, 8);
            LinearLayout options = row();
            weighted(options, normal, 1, 0, 86);
            weighted(options, headroomCard, 1, 8, 86);
            outputPanel.addView(options);
        } else {
            LinearLayout output = row();
            weighted(output, gainCard, 2, 0, panelHeight(compact));
            weighted(output, normal, 1, 10, panelHeight(compact));
            weighted(output, headroomCard, 1, 10, panelHeight(compact));
            outputPanel.addView(output);
        }
        installPanels(content, compact, soundPanel, outputPanel);
    }

    private final View[] panels = new View[2];
    private FrameLayout panelHost;

    /**
     * Effects and output share one area under the curve, chosen by tabs, so the page never
     * scrolls. In a split window the chosen panel opens in a bounded sheet instead.
     */
    private void installPanels(LinearLayout content, boolean compact, View soundPanel, View outputPanel) {
        panels[0] = soundPanel;
        panels[1] = outputPanel;
        LinearLayout tabs = segmented(new String[]{"Effects", "Output"},
                new String[]{"eq_panel_sound", "eq_panel_output"},
                () -> compact ? -1 : shownPanel(),
                i -> { ui.edit().putInt("shaping_panel", i).apply(); if (compact) openPanel(i); else showPanel(i); });
        if (compact) {
            LinearLayout bar = row();
            bar.addView(tabs, lp(-2, 56));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            bar.addView(preampLabel, lp(-2, 56));
            advanced.addView(bar);
        } else {
            // Wide: the tabs stand to the left of their panel, so they cost no row of their own.
            tabs.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < tabs.getChildCount(); i++) {
                tabs.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            }
            LinearLayout shelf = row();
            shelf.addView(tabs, lp(170, panelHeight(false)));
            panelHost = new FrameLayout(this);
            for (View panel : panels) panelHost.addView(panel, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams host = new LinearLayout.LayoutParams(0, dp(panelHeight(false)), 1);
            host.leftMargin = dp(10);
            shelf.addView(panelHost, host);
            advanced.addView(shelf);
            showPanel(shownPanel());
        }
        content.addView(advanced);
    }

    /** Wide: tall enough for two stacked 48 dp tabs beside the panel. */
    private static int panelHeight(boolean compact) { return compact ? 86 : 104; }

    private int shownPanel() { return Math.max(0, Math.min(panels.length - 1, ui.getInt("shaping_panel", 0))); }

    private void showPanel(int index) {
        for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
    }

    /** Split window: one panel at a time in a bounded sheet; its controls are the same views. */
    private void openPanel(int index) {
        View panel = panels[index];
        if (panel.getParent() != null) ((android.view.ViewGroup) panel.getParent()).removeView(panel);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(16), dp(8), dp(16), dp(8));
        frame.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        String[] titles = {"Effects", "Output"};
        android.app.AlertDialog dialog = new ThemedDialog(this, palette).setTitle(titles[index]).setView(frame)
                .setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(d -> frame.removeView(panel));
        dialog.show();
        DrivingDismiss.track(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(760)), -2);
        }
    }

    // ================= rendering =================

    private void renderAll() {
        boolean slidersOn = ui.getBoolean("sliders", false);
        curveScroll.setVisibility(slidersOn ? View.INVISIBLE : View.VISIBLE);
        sliders.setVisibility(slidersOn ? View.VISIBLE : View.GONE);
        curve.sync();
        sliders.sync();
        sliders.select(curve.selected());
        for (TextView[] group : segments) styleSegments(group);
        update(help, slidersOn ? "Drag a fader up or down" : "Drag a band up or down");
        renderPresets();
        updateStatus();
    }

    private void onEdited() {
        if (sliders.getVisibility() == View.VISIBLE && sliders.selected() != curve.selected()) {
            curve.select(sliders.selected());
            return;
        }
        curve.sync();
        sliders.sync();
        sliders.select(curve.selected());
        updateStatus();
        scheduleApply();
    }

    private void renderPresets() {
        if (presetRow == null) return;
        presetRow.removeAllViews();
        TextView find = presetAction("✦  Find your sound", "eq_find_sound", () -> {
            remember();
            startActivityForResult(new Intent(this, FindSoundActivity.class), FIND_SOUND);
        });
        find.setTextColor(palette.light ? 0xffffffff : palette.root);
        find.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                LauncherTheme.rounded(this, palette.accent, 18, 0, 0), null));
        find.setContentDescription("Find your sound");
        presetRow.addView(find, presetParams());
        String current = profile.preset();
        for (int i = 0; i < EqualizerProfile.PRESETS.length; i++) {
            final int index = i;
            String name = EqualizerProfile.PRESETS[i];
            float[] curveValues = EqualizerProfile.presetCurve(i);
            addPresetCard(name, curveValues, EqualizerProfile.FREQUENCIES, name.equals(current), () -> {
                remember();
                profile.preset(index);
                profile.flush();
                renderAll();
                scheduleApply();
            }, null);
        }
        for (final String name : profile.customPresets()) {
            float[] values = profile.customCurve(name);
            if (values == null) continue;
            addPresetCard(name, values, EqualizerProfile.FREQUENCIES, name.equals(current), () -> {
                remember();
                profile.applyCustom(name);
                profile.flush();
                renderAll();
                scheduleApply();
            }, () -> dialog().setTitle("Delete “" + name + "”?").setMessage("This saved preset will be removed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> { profile.deleteCustom(name); profile.flush(); renderPresets(); }).show());
        }
        TextView save = presetAction("+  Save as…", "eq_save_preset", this::savePreset);
        presetRow.addView(save, presetParams());
        TextView more = presetAction("All presets ▾", "eq_preset", this::choosePreset);
        presetRow.addView(more, presetParams());
    }

    private void addPresetCard(String name, float[] values, float[] hz, boolean chosen, Runnable click, Runnable longClick) {
        PresetCard card = new PresetCard(name, values, hz, chosen);
        card.setTag("eq_preset_card_" + name);
        card.setContentDescription(name + " preset" + (chosen ? ", selected" : ""));
        card.setOnClickListener(v -> click.run());
        if (longClick != null) card.setOnLongClickListener(v -> { longClick.run(); return true; });
        presetRow.addView(card, presetParams());
    }

    private LinearLayout.LayoutParams presetParams() {
        LinearLayout.LayoutParams params = lp(150, 72);
        params.rightMargin = dp(10);
        return params;
    }

    private TextView presetAction(String label, String tag, Runnable action) {
        TextView view = text(label, 14, palette.accent);
        medium(view);
        view.setGravity(Gravity.CENTER);
        view.setTag(tag);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x22142e38 : 0x33ffffff),
                LauncherTheme.rounded(this, palette.light ? 0x0c142e38 : 0x10ffffff, 18, 1, (palette.accent & 0x00ffffff) | 0x55000000), null));
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
        return view;
    }

    /** Small card with a curve thumbnail; the chosen preset is outlined in the accent colour. */
    private final class PresetCard extends View {
        private final String name;
        private final float[] values, hz;
        private final boolean chosen;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        PresetCard(String name, float[] values, float[] hz, boolean chosen) {
            super(EqualizerActivity.this);
            this.name = name;
            this.values = values;
            this.hz = hz;
            this.chosen = chosen;
            setClickable(true);
            setFocusable(true);
            setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x22142e38 : 0x33ffffff),
                    LauncherTheme.rounded(EqualizerActivity.this, chosen ? (palette.accent & 0x00ffffff) | 0x22000000
                            : palette.light ? 0xffffffff : 0xff19212b, 18, chosen ? 2 : 0, palette.accent), null));
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), pad = dp(14);
            float top = dp(12), bottom = h - dp(34);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(palette.light ? 0x22142e38 : 0x22ffffff);
            float mid = (top + bottom) / 2;
            c.drawLine(pad, mid, w - pad, mid, paint);
            path.reset();
            for (int i = 0; i < values.length; i++) {
                float x = pad + (w - 2 * pad) * (float) (Math.log(hz[i] / 20) / Math.log(1000));
                float y = mid - values[i] * (bottom - top) / 12;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setColor(palette.accent);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(13));
            paint.setColor(chosen ? palette.accent : palette.ink);
            paint.setTextAlign(Paint.Align.LEFT);
            CharSequence label = TextUtils.ellipsize(name, new android.text.TextPaint(paint), w - 2 * pad, TextUtils.TruncateAt.END);
            c.drawText(label.toString(), pad, h - dp(13), paint);
        }
    }

    private void updateStatus() {
        if (statusChip == null || curve == null) return;
        String state = profile.enabled() ? SoundEqualizerService.state(this) : "off";
        String label = "active".equals(state) ? "Active"
                : "waiting".equals(state) ? "Waiting for a player"
                : "unavailable".equals(state) ? "Unavailable" : "EQ off";
        int dot = "active".equals(state) ? 0xff3fb97c : "off".equals(state) ? palette.muted : 0xffe0a03a;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(dot);
        circle.setSize(dp(9), dp(9));
        circle.setBounds(0, 0, dp(9), dp(9));
        if (!label.contentEquals(statusChip.getText())) {
            statusChip.setText(label);
            statusChip.setCompoundDrawablesRelative(circle, null, null, null);
            statusChip.setBackground(LauncherTheme.rounded(this, palette.light ? 0x10142e38 : 0x14ffffff, 20, 0, 0));
        }
        String status = SoundEqualizerService.status(this);
        update(source, "active".equals(state) && status.startsWith("Active · ")
                ? "Music players · " + status.substring("Active · ".length()) : "Music players");
        update(linkSelector, profile.linkWidth() == 0 ? "Independent" : "Linked · " + Math.round(profile.linkWidth()) + " octave" + (profile.linkWidth() > 1 ? "s" : ""));
        update(normalizeSelector, "track".equals(profile.normalization()) ? "By song" : "album".equals(profile.normalization()) ? "By album" : "Off");
        float headroom = profile.inputGain() - profile.masterGain();
        update(gainLabel, db(profile.masterGain()) + (headroom < 0 ? "  ·  headroom " + db(headroom) : ""));
        update(preampLabel, profile.headroom() ? "Input " + db(profile.inputGain()) + " · set automatically" : "Input " + db(profile.inputGain()) + " · headroom off");
        update(headroomValue, profile.headroom() ? "On" : "Off · may clip");
        if (headroomSwitch.isChecked() != profile.headroom()) headroomSwitch.setChecked(profile.headroom());
        int band = curve.selected();
        float[] values = model.values();
        update(bandLabel, frequency(band) + " · " + model.role(band));
        update(bandGain, db(values[Math.min(band, values.length - 1)]));
        update(exciterValue, percent(profile.exciter()));
        update(bassValue, profile.bass() == 0 ? "Off" : String.format(java.util.Locale.US, "+%.1f dB", profile.bass()));
        update(exciterHzSelector, EqualizerProfile.hzLabel(profile.exciterHz()).replace(" k", " kHz"));
        // A preset or undo can change the effects under the sliders.
        syncBar("eq_exciter", profile.exciter());
        syncBar("eq_bass", profile.bass() / SoundEffects.BASS_MAX_DB);
        undoButton.setEnabled(!undo.isEmpty());
        undoButton.setAlpha(undo.isEmpty() ? .5f : 1f);
        redoButton.setEnabled(!redo.isEmpty());
        redoButton.setAlpha(redo.isEmpty() ? .5f : 1f);
    }

    // ================= undo / redo =================

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.curve = profile.gains();
        s.preset = profile.preset();
        s.effects = profile.effects();
        return s;
    }

    private void remember() {
        undo.push(snapshot());
        while (undo.size() > 40) undo.removeLast();
        redo.clear();
    }

    private void step(ArrayDeque<Snapshot> from, ArrayDeque<Snapshot> to) {
        if (from.isEmpty()) return;
        to.push(snapshot());
        Snapshot s = from.pop();
        profile.setGains(s.curve);
        profile.setEffects(s.effects);
        getSharedPreferences("equalizer_profile", MODE_PRIVATE).edit().putString("preset", s.preset).apply();
        profile.flush();
        renderAll();
        scheduleApply();
    }

    // ================= actions =================

    private void savePreset() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Preset name, e.g. Dongfeng · Metal");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        dialog().setTitle("Save curve as preset").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    profile.saveCustom(name);
                    profile.flush();
                    renderPresets();
                }).show();
    }

    private void choosePreset() {
        List<String> names = new ArrayList<>();
        for (String name : EqualizerProfile.PRESETS) names.add(name);
        names.addAll(profile.customPresets());
        dialog().setTitle("Listening preset").setItems(names.toArray(new String[0]), (d, i) -> {
            remember();
            if (i < EqualizerProfile.PRESETS.length) profile.preset(i);
            else profile.applyCustom(names.get(i));
            profile.flush();
            renderAll();
            scheduleApply();
        }).setNegativeButton("Cancel", null).show();
    }

    private void resetBands() {
        dialog().setTitle("Reset bands to flat?")
                .setMessage("All bands go to 0 dB. Your other settings stay.")
                .setPositiveButton("Reset", (d, w) -> {
                    remember();
                    profile.preset(0);
                    profile.flush();
                    renderAll();
                    scheduleApply();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseLink() {
        dialog().setTitle("Band linking").setSingleChoiceItems(new String[]{"Independent bands", "Linked · 1 octave", "Linked · 2 octaves"},
                Math.round(profile.linkWidth()), (d, i) -> { profile.setLinkWidth(i); profile.flush(); updateStatus(); d.dismiss(); })
                .setNegativeButton("Close", null).show();
    }

    private void chooseBand() {
        String[] choices = new String[model.count()];
        for (int i = 0; i < choices.length; i++) choices[i] = frequency(i) + " · " + model.role(i);
        dialog().setTitle("Select a frequency").setSingleChoiceItems(choices, curve.selected(), (d, i) -> {
            curve.select(i);
            sliders.select(i);
            curveScroll.smoothScrollTo(Math.round(curve.bandX(i) - curveScroll.getWidth() / 2f), 0);
            d.dismiss();
        }).setNegativeButton("Close", null).show();
    }

    private void chooseNormalization() {
        dialog().setTitle("Even out loudness · OABL Music")
                .setSingleChoiceItems(new String[]{"Off", "By song", "By album"},
                        "track".equals(profile.normalization()) ? 1 : "album".equals(profile.normalization()) ? 2 : 0, (x, i) -> {
                            profile.setNormalization(new String[]{"none", "track", "album"}[i]);
                            profile.flush();
                            updateStatus();
                            if (MusicClient.get(this).isConnected()) MusicClient.get(this).command("refresh");
                            x.dismiss();
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseHz(String title, float[] choices, float current, java.util.function.Consumer<Float> set) {
        String[] names = new String[choices.length];
        int chosen = 0;
        for (int i = 0; i < choices.length; i++) {
            names[i] = choices[i] < 1000 ? Math.round(choices[i]) + " Hz" : EqualizerProfile.hzLabel(choices[i]).replace(" k", " kHz");
            if (choices[i] == current) chosen = i;
        }
        dialog().setTitle(title).setSingleChoiceItems(names, chosen, (x, i) -> {
            remember();
            set.accept(choices[i]);
            getSharedPreferences("equalizer_profile", MODE_PRIVATE).edit().putString("preset", "Custom").apply();
            profile.flush();
            updateStatus();
            renderPresets();
            scheduleApply();
            x.dismiss();
        }).setNegativeButton("Cancel", null).show();
    }

    /** Effects belong to presets: moving one makes the sound Custom. */
    private void markCustom() {
        if ("Custom".equals(profile.preset())) return;
        getSharedPreferences("equalizer_profile", MODE_PRIVATE).edit().putString("preset", "Custom").apply();
        renderPresets();
    }

    private static String percent(float amount) { return amount == 0 ? "Off" : Math.round(amount * 100) + "%"; }

    private void syncBar(String tag, float amount) {
        View view = advanced == null ? null : advanced.getRootView().findViewWithTag(tag);
        if (view instanceof SeekBar && !view.isPressed()) {
            int progress = Math.round(amount * ((SeekBar) view).getMax());
            if (((SeekBar) view).getProgress() != progress) ((SeekBar) view).setProgress(progress);
        }
    }

    /** Where the equalizer runs: music players that announce their audio session. */
    private void showSource() {
        dialog().setTitle("Music players")
                .setMessage((profile.enabled() ? SoundEqualizerService.status(this) : "Equalizer off")
                        + "\n\nWorks with OABL Music and other players that support it. On this box, apps such as YouTube and Chrome are picked up automatically while the equalizer is on. Navigation voice and calls are not changed.")
                .setPositiveButton("OK", null).show();
    }

    /** OABL Music's exciter and bass follow the same hold: the original comes back at a matching loudness. */
    private void compareMusic(boolean on) {
        profile.setComparing(on);
        MusicClient.get(this).effectsChanged();
    }

    /** While held, the equalizer is bypassed so the original sound can be compared. */
    @SuppressLint("ClickableViewAccessibility")
    private void installCompare(TextView button) {
        button.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                SoundEqualizerService.setBypass(this, true);
                compareMusic(true);
                button.setText("Hearing original");
                stylePill(button, true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                SoundEqualizerService.setBypass(this, false);
                compareMusic(false);
                button.setText("Hold to compare");
                stylePill(button, false);
                if (action == MotionEvent.ACTION_UP) v.performClick();
                return true;
            }
            return true;
        });
        button.setOnClickListener(v -> { });
    }

    private void scheduleApply() {
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, 100);
    }

    // ================= live spectrum =================

    /** Faint live analyser behind the curve; needs the same audio permission as the Home spectrum. */
    private void startSpectrum() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!new LauncherPreferences(this).visualEffects()) return;
        spectrumThread = new HandlerThread("OablEqSpectrum");
        spectrumThread.start();
        spectrumWorker = new Handler(spectrumThread.getLooper());
        spectrumWorker.post(() -> {
            try {
                Visualizer candidate = new Visualizer(0);
                int[] range = Visualizer.getCaptureSizeRange();
                int size = Math.max(range[0], Math.min(1024, range[1]));
                candidate.setCaptureSize(size);
                candidate.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                candidate.setEnabled(true);
                visualizer = candidate;
                final byte[] fft = new byte[size];
                final float[] levels = new float[SpectrumAnalyser.BANDS];
                final float[] shown = new float[SpectrumAnalyser.BANDS];
                final SpectrumAnalyser analyser = new SpectrumAnalyser();
                final int rate = candidate.getSamplingRate();
                final float high = (float) Math.min(16000, rate / 2000.0);
                spectrumWorker.post(new Runnable() {
                    @Override public void run() {
                        Visualizer active = visualizer;
                        if (active == null) return;
                        try {
                            if (active.getFft(fft) == Visualizer.SUCCESS) {
                                analyser.analyse(fft, rate, levels);
                                for (int i = 0; i < levels.length; i++) shown[i] += (levels[i] - shown[i]) * (levels[i] > shown[i] ? .6f : .2f);
                                final float[] copy = shown.clone();
                                handler.post(() -> { if (curve != null) curve.spectrum(copy, high); });
                            }
                        } catch (RuntimeException ignored) {
                            return;
                        }
                        spectrumWorker.postDelayed(this, 70);
                    }
                });
            } catch (RuntimeException unavailable) {
                visualizer = null;
            }
        });
    }

    private void stopSpectrum() {
        Visualizer active = visualizer;
        visualizer = null;
        if (spectrumWorker != null) {
            spectrumWorker.removeCallbacksAndMessages(null);
            spectrumWorker.post(() -> {
                if (active != null) {
                    try { active.setEnabled(false); } catch (RuntimeException ignored) { }
                    active.release();
                }
            });
        }
        if (spectrumThread != null) {
            spectrumThread.quitSafely();
            spectrumThread = null;
        }
        if (curve != null) curve.spectrum(null, 16000);
    }

    // ================= small helpers =================

    private interface IntChoice { void pick(int index); }
    private interface IntSource { int current(); }
    private interface Progress { void set(int value); }

    private LinearLayout segmented(String[] labels, String[] tags, IntSource current, IntChoice choice) {
        LinearLayout group = row();
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackground(LauncherTheme.rounded(this, palette.light ? 0x10142e38 : 0x14ffffff, 14, 0, 0));
        TextView[] options = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView option = text(labels[i], 14, palette.ink);
            medium(option);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(16), 0, dp(16), 0);
            option.setMinimumWidth(dp(56));
            option.setTag(tags[i]);
            option.setFocusable(true);
            option.setOnClickListener(v -> { choice.pick(index); for (TextView[] g : segments) styleSegments(g); });
            group.addView(option, lp(-2, -1));
            options[i] = option;
        }
        group.setTag(R.id.music_pill_primary, current);
        options[0].setTag(R.id.music_cover_scale, current);
        segments.add(options);
        styleSegments(options);
        return group;
    }

    private void styleSegments(TextView[] options) {
        IntSource source = (IntSource) options[0].getTag(R.id.music_cover_scale);
        int chosen = source.current();
        for (int i = 0; i < options.length; i++) {
            boolean on = i == chosen;
            options[i].setTextColor(on ? (palette.light ? 0xffffffff : palette.root) : palette.ink);
            options[i].setBackground(on ? LauncherTheme.rounded(this, palette.accent, 11, 0, 0) : null);
            options[i].setSelected(on);
        }
    }

    private LinearLayout sliderCard(String title, TextView value, String tag, int max, int progress, Progress onChange) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(10), dp(16), dp(4));
        card.setBackground(LauncherTheme.tile(this, palette));
        LinearLayout heading = row();
        heading.addView(text(title, 11, palette.muted), new LinearLayout.LayoutParams(0, dp(24), 1));
        value.setTypeface(null, Typeface.BOLD);
        value.setFontFeatureSettings("tnum");
        heading.addView(value);
        card.addView(heading);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setTag(tag);
        bar.setContentDescription(title.toLowerCase(Locale.ROOT));
        bar.setSplitTrack(false);
        bar.setPadding(dp(4), 0, dp(4), 0);
        tint(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user) { onChange.set(p); updateStatus(); scheduleApply(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { b.getParent().requestDisallowInterceptTouchEvent(true); }
            @Override public void onStopTrackingTouch(SeekBar b) {
                b.getParent().requestDisallowInterceptTouchEvent(false); profile.flush(); scheduleApply();
            }
        });
        card.addView(bar, lp(-1, 48));
        return card;
    }

    private TextView pill(String label, boolean primary) {
        TextView view = text(label, 14, palette.ink);
        medium(view);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setFocusable(true);
        view.setClickable(true);
        view.setTag(R.id.music_pill_primary, primary);
        stylePill(view, false);
        return view;
    }

    private void stylePill(TextView view, boolean active) {
        boolean primary = Boolean.TRUE.equals(view.getTag(R.id.music_pill_primary));
        int fill = primary || active ? palette.accent : palette.light ? 0x10142e38 : 0x14ffffff;
        view.setTextColor(primary || active ? (palette.light ? 0xffffffff : palette.root) : palette.ink);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x22142e38 : 0x33ffffff),
                LauncherTheme.rounded(this, fill, 20, 0, 0), null));
    }

    private LinearLayout control(String label, TextView value, Runnable action, String tag) {
        LinearLayout card = row();
        card.setTag(tag);
        card.setPadding(dp(16), 0, dp(8), 0);
        card.setBackground(LauncherTheme.tile(this, palette));
        LinearLayout labels = column();
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 10, palette.muted);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setTypeface(null, Typeface.BOLD);
        labels.addView(title);
        space(labels, 4);
        labels.addView(value);
        card.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));
        MusicIconView arrow = icon(MusicIconView.Icon.DOWN, "", () -> { }, null);
        arrow.setClickable(false);
        arrow.setFocusable(false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(arrow, lp(32, 48));
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private String frequency(int band) {
        float hz = model.frequencies()[band];
        return EqualizerProfile.hzLabel(hz).replace(" k", " kHz") + (hz < 1000 ? " Hz" : "");
    }

    private static void update(TextView view, String value) { if (view != null && !value.contentEquals(view.getText())) view.setText(value); }
    private AlertDialog.Builder dialog() { return new ThemedDialog(this, palette); }
    private MusicIconView icon(MusicIconView.Icon shape, String label, Runnable action, String tag) { MusicIconView v = new MusicIconView(this, shape, palette.ink, palette.accent, label, action); v.setTag(tag); v.setBackground(LauncherTheme.dockItem(this, palette)); return v; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private static void medium(TextView v) { v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
    private void toast(String value) { android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_SHORT).show(); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), lp(1, height)); }
    private void weighted(LinearLayout row, View view, float weight, int gap, int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), weight); p.leftMargin = dp(gap); row.addView(view, p); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private void tint(SeekBar v) { v.setProgressTintList(ColorStateList.valueOf(palette.accent)); v.setThumbTintList(ColorStateList.valueOf(palette.accent)); v.setProgressBackgroundTintList(ColorStateList.valueOf((palette.muted & 0x00ffffff) | 0x40000000)); }
    private void tint(Switch v) { v.setTextColor(palette.ink); v.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{palette.accent, palette.muted})); v.setTrackTintList(ColorStateList.valueOf((palette.accent & 0x00ffffff) | 0x44000000)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String db(float gain) { return String.format(Locale.US, "%+.1f dB", gain); }
}
