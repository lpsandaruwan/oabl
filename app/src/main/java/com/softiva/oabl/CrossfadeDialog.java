package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Crossfade settings for OABL Music: fade length with a preview of the two volume curves,
 * plus same-album gapless handoff, quick fades when skipping, and soft pause/resume.
 * Settings apply from the next song change; nothing restarts.
 */
final class CrossfadeDialog {
    private static final String LAST_SECONDS = "crossfade_last_seconds";
    private CrossfadeDialog() { }

    static String summary(Context context) {
        SharedPreferences music = context.getSharedPreferences("music", 0);
        int seconds = music.getInt(MusicService.CROSSFADE, MusicService.CROSSFADE_DEFAULT);
        return seconds == 0 ? "Off" : seconds + " s" + (music.getBoolean(MusicService.CROSSFADE_ALBUMS, true) ? " · albums gapless" : "");
    }

    static void show(Context context, LauncherTheme.Palette palette, Runnable changed) {
        SharedPreferences music = context.getSharedPreferences("music", 0);
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * density);
        content.setPadding(pad, Math.round(8 * density), pad, 0);

        int savedSeconds = music.getInt(MusicService.CROSSFADE, MusicService.CROSSFADE_DEFAULT);
        Switch enabled = option(context, palette, content, "Crossfade",
                "Blend the end of one song into the next", savedSeconds > 0);
        enabled.setContentDescription("Crossfade");

        TextView value = new TextView(context);
        value.setTextSize(28);
        value.setTextColor(palette.ink);
        value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        content.addView(value);
        TextView hint = new TextView(context);
        hint.setTextSize(14);
        hint.setTextColor(palette.muted);
        content.addView(hint);

        CurveView curve = new CurveView(context, palette);
        LinearLayout.LayoutParams curveSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(120 * density));
        curveSize.topMargin = Math.round(14 * density);
        content.addView(curve, curveSize);

        SeekBar seconds = new SeekBar(context);
        // The switch owns Off; the slider selects a duration from 1 to 12 seconds.
        int duration = savedSeconds > 0 ? savedSeconds : music.getInt(LAST_SECONDS, MusicService.CROSSFADE_DEFAULT);
        seconds.setMax(11);
        seconds.setProgress(Math.max(1, Math.min(12, duration)) - 1);
        seconds.setProgressTintList(ColorStateList.valueOf(palette.accent));
        seconds.setThumbTintList(ColorStateList.valueOf(palette.accent));
        seconds.setProgressBackgroundTintList(ColorStateList.valueOf(palette.muted));
        seconds.setContentDescription("Crossfade length in seconds");
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(48 * density));
        barSize.topMargin = Math.round(6 * density);
        content.addView(seconds, barSize);

        Runnable describe = () -> {
            int s = enabled.isChecked() ? seconds.getProgress() + 1 : 0;
            value.setText(s == 0 ? "Crossfade off" : "Crossfade " + s + " s");
            hint.setText(s == 0 ? "Songs follow without overlapping." : s <= 3 ? "A quick blend, good for rock and metal."
                    : s <= 7 ? "A smooth blend between songs." : "A long, radio-style blend.");
            curve.seconds = s;
            curve.invalidate();
        };
        describe.run();
        seconds.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { describe.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        Switch albums = option(context, palette, content, "Keep albums gapless",
                "Songs from the same album flow straight on, without a fade", music.getBoolean(MusicService.CROSSFADE_ALBUMS, true));
        Switch skips = option(context, palette, content, "Fade when skipping",
                "A short blend when you change songs yourself", music.getBoolean(MusicService.CROSSFADE_SKIPS, true));
        Switch pause = option(context, palette, content, "Smooth pause and resume",
                "Fades out on pause and in on play", music.getBoolean(MusicService.FADE_PAUSE, true));

        Runnable updateEnabled = () -> {
            boolean on = enabled.isChecked();
            seconds.setEnabled(on);
            seconds.setAlpha(on ? 1f : .4f);
            skips.setEnabled(on);
            skips.setAlpha(on ? 1f : .4f);
            describe.run();
        };
        enabled.setOnCheckedChangeListener((button, on) -> updateEnabled.run());
        updateEnabled.run();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        new ThemedDialog(context, palette).setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    int selectedSeconds = seconds.getProgress() + 1;
                    music.edit().putInt(MusicService.CROSSFADE, enabled.isChecked() ? selectedSeconds : 0)
                            .putInt(LAST_SECONDS, selectedSeconds)
                            .putBoolean(MusicService.CROSSFADE_ALBUMS, albums.isChecked())
                            .putBoolean(MusicService.CROSSFADE_SKIPS, skips.isChecked())
                            .putBoolean(MusicService.FADE_PAUSE, pause.isChecked()).apply();
                    if (changed != null) changed.run();
                }).show();
    }

    private static Switch option(Context context, LauncherTheme.Palette palette, LinearLayout parent,
                                 String title, String detail, boolean checked) {
        float density = context.getResources().getDisplayMetrics().density;
        Switch toggle = new Switch(context);
        toggle.setTextSize(15);
        toggle.setTextColor(palette.ink);
        toggle.setChecked(checked);
        toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{palette.accent, palette.light ? 0xfff2f4f6 : 0xff9aa5b1}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{(palette.accent & 0x00ffffff) | 0x80000000, palette.light ? 0x33000000 : 0x40ffffff}));
        toggle.setMinHeight(Math.round(60 * density));
        // Second line in a lighter colour.
        android.text.SpannableString text = new android.text.SpannableString(title + "\n" + detail);
        text.setSpan(new android.text.style.ForegroundColorSpan(palette.muted), title.length() + 1, text.length(), 0);
        text.setSpan(new android.text.style.RelativeSizeSpan(.85f), title.length() + 1, text.length(), 0);
        toggle.setText(text);
        parent.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return toggle;
    }

    /** The outgoing song's volume falling and the next one's rising over the fade. Static drawing. */
    private static final class CurveView extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), out = new Paint(Paint.ANTI_ALIAS_FLAG),
                in = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        int seconds;

        CurveView(Context context, LauncherTheme.Palette palette) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            grid.setColor(palette.light ? 0x1a000000 : 0x24ffffff);
            grid.setStrokeWidth(density);
            out.setStyle(Paint.Style.STROKE);
            out.setStrokeWidth(3 * density);
            out.setStrokeCap(Paint.Cap.ROUND);
            out.setColor(palette.muted);
            in.set(out);
            in.setColor(palette.accent);
            label.setTextSize(12 * density);
            label.setColor(palette.muted);
            setContentDescription("Crossfade curve preview");
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), top = h * .12f, bottom = h * .78f;
            canvas.drawLine(0, bottom, w, bottom, grid);
            canvas.drawLine(0, top, w, top, grid);
            // The fade window sits in the middle; 12 s fills most of the width.
            float span = seconds == 0 ? 0 : w * (.18f + .62f * seconds / 12f);
            float start = (w - span) / 2f, end = start + span;
            path.reset();
            path.moveTo(0, top);
            if (span == 0) { path.lineTo(w / 2f, top); path.lineTo(w / 2f, bottom); }
            else for (int i = 0; i <= 40; i++) {
                float t = i / 40f;
                path.lineTo(start + span * t, bottom - (bottom - top) * (float) Math.cos(t * Math.PI / 2));
            }
            path.lineTo(span == 0 ? w / 2f : end, bottom);
            canvas.drawPath(path, out);
            path.reset();
            path.moveTo(span == 0 ? w / 2f : start, bottom);
            if (span == 0) path.lineTo(w / 2f, top);
            else for (int i = 0; i <= 40; i++) {
                float t = i / 40f;
                path.lineTo(start + span * t, bottom - (bottom - top) * (float) Math.sin(t * Math.PI / 2));
            }
            path.lineTo(w, top);
            canvas.drawPath(path, in);
            float y = h - 4 * getResources().getDisplayMetrics().density;
            canvas.drawText("This song", 0, y, label);
            float next = label.measureText("Next song");
            canvas.drawText("Next song", w - next, y, label);
        }
    }
}
