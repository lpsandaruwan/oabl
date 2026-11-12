package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * "Find your sound": listen to your own music and pick the version you prefer, one tone area at
 * a time, in two passes (broad, then fine). Each choice is auditioned live through the
 * equalizer; the picks add up to a style curve you can save as a preset.
 */
public final class FindSoundActivity extends Activity {
    /** Tone areas: name, what it changes, centre Hz, width in octaves, first-pass step in dB. */
    private static final String[] AREAS = {"Bass weight", "Warmth", "Boxiness", "Vocal presence", "Bite", "Sparkle", "Air"};
    private static final String[] DESCRIPTIONS = {
            "Kick drum and bass guitar weight",
            "Body of guitars and vocals · too much sounds muddy",
            "Honky, boxy, ‘talking through a tube’",
            "How forward the singer sits",
            "Edge of guitars · too much sounds shouty and harsh",
            "Cymbals and pick attack",
            "Openness and space at the very top"};
    private static final float[] CENTRES = {63, 200, 700, 1600, 3150, 8000, 14000};
    private static final float[] WIDTHS = {1.6f, 1f, .9f, .8f, .8f, 1.1f, 1f};
    private static final float[] STEPS = {3, 2.5f, 3, 2.5f, 3, 2.5f, 2};
    private static final String[][] CHOICE_LABELS = {
            {"Lighter bass", "Heavier bass"}, {"Leaner", "Warmer"}, {"Less boxy", "More body"},
            {"Vocals back", "Vocals forward"}, {"Smoother", "More bite"}, {"Softer", "Brighter"}, {"Closed", "More air"}};
    private static final int PASSES = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EqualizerProfile profile;
    private LauncherTheme.Palette palette;
    private MediaControllerClient media;
    private float[] base;
    private float[] current;
    private int round;
    private int choice = 1;
    private final float[][] history = new float[AREAS.length * PASSES + 1][];
    private TextView step, area, description, nowPlaying, playPause;
    private ProgressBar progress;
    private final ChoiceCard[] cards = new ChoiceCard[3];
    private final Runnable mediaRefresh = new Runnable() {
        @Override public void run() { renderMedia(); handler.postDelayed(this, 1500); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        palette = LauncherTheme.resolve(this, new LauncherPreferences(this));
        profile = new EqualizerProfile(this);
        media = new MediaControllerClient(this);
        base = profile.gains();
        current = base.clone();
        history[0] = current.clone();
        if (!profile.enabled()) {
            // Auditioning needs the processor running; the user can switch it off again afterwards.
            profile.setEnabled(true);
            profile.flush();
        }
        build();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SoundEqualizerService.start(this);
        audition();
        handler.post(mediaRefresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(mediaRefresh);
        SoundEqualizerService.setPreview(this, null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (media != null) media.release();
        super.onDestroy();
    }

    // ---- flow ----

    private float stepSize() {
        return STEPS[round % AREAS.length] * (round < AREAS.length ? 1f : .5f);
    }

    /** The three candidates for this round: less, as now, more. */
    private float[] candidate(int which) {
        int index = round % AREAS.length;
        float delta = (which - 1) * stepSize();
        float[] values = current.clone();
        for (int i = 0; i < values.length; i++) {
            double octaves = Math.log(EqualizerProfile.FREQUENCIES[i] / CENTRES[index]) / Math.log(2);
            double weight = Math.exp(-.5 * Math.pow(octaves / (WIDTHS[index] / 2), 2));
            values[i] = EqualizerProfile.clamp(values[i] + (float) (delta * weight), -EqualizerProfile.STYLE_DB, EqualizerProfile.STYLE_DB);
        }
        return values;
    }

    private void audition() {
        SoundEqualizerService.setPreview(this, candidate(choice));
        for (int i = 0; i < cards.length; i++) cards[i].chosen(i == choice);
    }

    private void pick(int which) {
        choice = which;
        audition();
    }

    private void next() {
        current = candidate(choice);
        round++;
        history[round] = current.clone();
        if (round >= AREAS.length * PASSES) {
            finishWizard();
            return;
        }
        choice = 1;
        render();
        audition();
    }

    private void back() {
        if (round == 0) {
            finish();
            return;
        }
        round--;
        current = history[round].clone();
        choice = 1;
        render();
        audition();
    }

    private void finishWizard() {
        SoundEqualizerService.setPreview(this, current);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("My sound");
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new ThemedDialog(this, palette)
                .setTitle("Your sound is ready")
                .setMessage("Save it as a preset and use it now. You can fine-tune it later in the Equalizer.")
                .setView(box)
                .setNegativeButton("Discard", (d, w) -> finish())
                .setPositiveButton("Save & use", (d, w) -> {
                    String name = input.getText().toString().trim();
                    profile.setGains(current);
                    if (!name.isEmpty()) profile.saveCustom(name);
                    profile.flush();
                    setResult(RESULT_OK);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    // ---- layout ----

    private void build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(32), dp(20), dp(32), dp(20));
        page.setBackgroundColor(palette.root);
        setContentView(page);
        ImmersiveWindow.apply(getWindow(), page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Find your sound", 28, palette.ink, true));
        step = text("", 13, palette.muted, false);
        titles.addView(step);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // A small now-playing bar: the wizard only works while music is playing.
        nowPlaying = text("", 13, palette.muted, false);
        nowPlaying.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(nowPlaying, new LinearLayout.LayoutParams(dp(360), dp(40)));
        playPause = button("▶  Play", false, () -> { media.playPause(); handler.postDelayed(this::renderMedia, 400); });
        LinearLayout.LayoutParams playSize = new LinearLayout.LayoutParams(dp(130), dp(44));
        playSize.leftMargin = dp(12);
        header.addView(playPause, playSize);
        page.addView(header);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(AREAS.length * PASSES);
        progress.setProgressTintList(ColorStateList.valueOf(palette.accent));
        LinearLayout.LayoutParams progressSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        progressSize.topMargin = dp(12);
        page.addView(progress, progressSize);

        area = text("", 26, palette.ink, true);
        LinearLayout.LayoutParams areaSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        areaSize.topMargin = dp(18);
        page.addView(area, areaSize);
        description = text("", 15, palette.muted, false);
        page.addView(description);
        TextView prompt = text("Tap each version while your music plays, then choose the one you like best.", 14, palette.muted, false);
        LinearLayout.LayoutParams promptSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        promptSize.topMargin = dp(4);
        page.addView(prompt, promptSize);

        LinearLayout choices = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            final int which = i;
            cards[i] = new ChoiceCard();
            cards[i].setTag("find_choice_" + i);
            cards[i].setOnClickListener(v -> pick(which));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) size.leftMargin = dp(14);
            choices.addView(cards[i], size);
        }
        LinearLayout.LayoutParams choicesSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        choicesSize.topMargin = dp(16);
        page.addView(choices, choicesSize);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(button("Back", false, this::back), new LinearLayout.LayoutParams(dp(140), dp(56)));
        View spacer = new View(this);
        footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        footer.addView(button("Finish now", false, this::finishWizard), new LinearLayout.LayoutParams(dp(160), dp(56)));
        LinearLayout.LayoutParams nextSize = new LinearLayout.LayoutParams(dp(240), dp(56));
        nextSize.leftMargin = dp(12);
        TextView choose = button("Choose this  →", true, this::next);
        choose.setTag("find_next");
        footer.addView(choose, nextSize);
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerSize.topMargin = dp(16);
        page.addView(footer, footerSize);
    }

    private void render() {
        int index = round % AREAS.length;
        boolean refine = round >= AREAS.length;
        step.setText(String.format(Locale.US, "Step %d of %d · %s", round + 1, AREAS.length * PASSES,
                refine ? "fine-tuning" : "broad strokes"));
        progress.setProgress(round);
        area.setText(AREAS[index]);
        description.setText(DESCRIPTIONS[index]);
        String[] names = {CHOICE_LABELS[index][0], "As now", CHOICE_LABELS[index][1]};
        for (int i = 0; i < 3; i++) {
            float delta = (i - 1) * stepSize();
            cards[i].set(names[i], i == 1 ? "No change" : String.format(Locale.US, "%+.1f dB around %s Hz", delta,
                    EqualizerProfile.hzLabel(CENTRES[index]).replace(" k", "k")), candidate(i));
        }
    }

    private void renderMedia() {
        MediaControllerClient.Snapshot snapshot = media.read();
        if (snapshot == null) {
            nowPlaying.setText("Start a song you know well, then come back");
            playPause.setText("▶  Play");
            return;
        }
        nowPlaying.setText("♪  " + snapshot.title + (snapshot.subtitle == null ? "" : " · " + snapshot.subtitle));
        playPause.setText(snapshot.playing ? "❚❚  Pause" : "▶  Play");
    }

    /** One audition option: name, what changes, and the resulting curve. */
    private final class ChoiceCard extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String name = "", detail = "";
        private float[] values = new float[0];
        private boolean chosen;

        ChoiceCard() {
            super(FindSoundActivity.this);
            setClickable(true);
            setFocusable(true);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chosen(false);
        }

        void set(String name, String detail, float[] values) {
            this.name = name;
            this.detail = detail;
            this.values = values;
            setContentDescription(name + ". " + detail);
            invalidate();
        }

        void chosen(boolean value) {
            chosen = value;
            setBackground(new RippleDrawable(ColorStateList.valueOf(palette.light ? 0x22142e38 : 0x33ffffff),
                    LauncherTheme.rounded(FindSoundActivity.this, value ? (palette.accent & 0x00ffffff) | 0x22000000
                            : palette.light ? 0xffffffff : 0xff19212b, 24, value ? 3 : 0, palette.accent), null));
            setSelected(value);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), pad = dp(22);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(dp(22));
            paint.setColor(chosen ? palette.accent : palette.ink);
            c.drawText(name, pad, pad + dp(18), paint);
            paint.setTextSize(dp(13));
            paint.setColor(palette.muted);
            c.drawText(detail, pad, pad + dp(42), paint);
            if (chosen) {
                paint.setColor(palette.accent);
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setTextSize(dp(13));
                c.drawText("♪ playing", w - pad, pad + dp(16), paint);
            }
            if (values.length == 0) return;
            float top = pad + dp(64), bottom = h - pad;
            float mid = (top + bottom) / 2;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(palette.light ? 0x22142e38 : 0x22ffffff);
            c.drawLine(pad, mid, w - pad, mid, paint);
            path.reset();
            for (int i = 0; i < values.length; i++) {
                float x = pad + (w - 2 * pad) * (float) (Math.log(EqualizerProfile.FREQUENCIES[i] / 20) / Math.log(1000));
                float y = mid - values[i] * (bottom - top) / (2 * EqualizerProfile.STYLE_DB);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setColor(palette.accent);
            paint.setStrokeWidth(dp(chosen ? 3 : 2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path, paint);
        }
    }

    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView button(String label, boolean primary, Runnable action) {
        TextView view = text(label, 15, primary ? (palette.light ? 0xffffffff : palette.root) : palette.ink, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                LauncherTheme.rounded(this, primary ? palette.accent : palette.light ? 0x12142e38 : 0x18ffffff, 20, 0, 0), null));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
