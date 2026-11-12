package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Corner badge over a dock icon: level bars for the app that is playing media, or a short text
 * pill (ETA / next-turn distance) for the app that is navigating. Drawn only when its state
 * changes: on these boxes every Home frame is captured and encoded for CarPlay, so the badge
 * never animates while music plays.
 */
final class DockBadgeView extends View {
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final float density;
    private String text;
    private boolean media;
    private boolean playing;
    private int accent = 0xff3d6c59;

    DockBadgeView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setTypeface(MEDIUM);
        paint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void none() {
        if (text == null && !media) return;
        text = null;
        media = false;
        invalidate();
    }

    void media(boolean playing, int accent) {
        boolean changed = !media || this.playing != playing || this.accent != accent;
        media = true;
        text = null;
        this.playing = playing;
        this.accent = accent;
        if (changed) invalidate();
    }

    void text(String value, int accent) {
        if (value != null && value.equals(text) && this.accent == accent) return;
        text = value;
        media = false;
        this.accent = accent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text == null && !media) return;
        float w = getWidth();
        if (text != null) {
            paint.setTextSize(10 * density);
            float width = Math.max(18 * density, paint.measureText(text) + 10 * density);
            pill.set(w - width + 4 * density, 0, w + 4 * density, 17 * density);
            paint.setColor(accent);
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint);
            paint.setColor(0xffffffff);
            canvas.drawText(text, pill.centerX(), pill.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
            return;
        }
        float r = 10 * density;
        float cx = w - r + 3 * density;
        float cy = r - 3 * density;
        paint.setColor(accent);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(0xffffffff);
        float bar = 2.2f * density;
        float gap = 1.6f * density;
        float base = cy + 4.5f * density;
        float tall = 9 * density;
        float left = cx - (bar * 3 + gap * 2) / 2;
        for (int i = 0; i < 3; i++) {
            // Playing: a rising staircase; paused: low, level bars.
            float level = playing ? .45f + .275f * i : .35f;
            float x = left + i * (bar + gap);
            canvas.drawRoundRect(x, base - tall * level, x + bar, base, bar / 2, bar / 2, paint);
        }
    }
}
