package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Track progress bar with elapsed and remaining time. It extrapolates from the last
 * PlaybackState itself, so it ticks smoothly between the Home screen's 1.5 s refreshes.
 */
public final class MediaProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float density;
    private long position;
    private long duration;
    private long updatedAt;
    private float rate;
    private boolean playing;
    private int fill = 0xffffffff;
    private int track = 0x40ffffff;
    private int text = 0xb3ffffff;

    public MediaProgressView(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setFontFeatureSettings("tnum");
    }

    void colors(int fill, int track, int text) {
        if (this.fill == fill && this.track == track && this.text == text) return;
        this.fill = fill;
        this.track = track;
        this.text = text;
        invalidate();
    }

    void state(long position, long duration, long updatedAt, float rate, boolean playing) {
        this.position = position;
        this.duration = duration;
        this.updatedAt = updatedAt;
        this.rate = rate;
        this.playing = playing;
        invalidate();
    }

    boolean hasDuration() {
        return duration > 0;
    }

    private long current() {
        long value = position;
        if (playing && updatedAt > 0) {
            value += (long) ((SystemClock.elapsedRealtime() - updatedAt) * (rate == 0 ? 1f : rate));
        }
        return Math.max(0, Math.min(duration, value));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (duration <= 0) return;
        long now = current();
        float h = 4 * density;
        float top = 4 * density;
        // The track is inset by the dot's radius so the dot stays whole at 0:00 and at the end.
        float dot = h * 1.1f;
        float left = dot, right = getWidth() - dot;
        float x = left + (right - left) * Math.max(0f, Math.min(1f, now / (float) duration));
        bar.set(left, top, right, top + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        canvas.drawRoundRect(bar, h / 2, h / 2, paint);
        bar.right = Math.max(left + h, x);
        paint.setColor(fill);
        canvas.drawRoundRect(bar, h / 2, h / 2, paint);
        canvas.drawCircle(x, top + h / 2, dot, paint);

        paint.setTextSize(11 * density);
        paint.setColor(text);
        float baseline = top + h + 15 * density;
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(format(now), 0, baseline, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("-" + format(duration - now), getWidth(), baseline, paint);

        if (playing && isShown()) {
            // Repaint about when the seconds digit changes; cheap, and only while visible.
            postInvalidateDelayed(1000 - (now % 1000) + 16);
        }
    }

    private static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
