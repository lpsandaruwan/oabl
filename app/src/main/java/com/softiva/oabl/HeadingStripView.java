package com.softiva.oabl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A compass tape (… NW — N — NE …) that slides with the GPS bearing. */
public final class HeadingStripView extends View {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float WINDOW = 150f;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final float density;
    private ValueAnimator glide;
    private float bearing;
    private boolean known;
    private int ink = 0xfff4f8fb;
    private int muted = 0xffa5b5c5;
    private int accent = 0xff86d9c7;

    public HeadingStripView(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void theme(LauncherTheme.Palette palette) {
        ink = palette.ink;
        muted = palette.muted;
        accent = palette.accent;
        invalidate();
    }

    void heading(boolean available, float degrees, boolean animate) {
        float next = ((degrees % 360) + 360) % 360;
        if (!available) {
            if (!known) return; // already drawn as unknown; Home repeats this every tick
            known = false;
            invalidate();
            return;
        }
        if (!known || !animate) {
            if (known && glide == null && bearing == next) return;
            if (glide != null) glide.cancel();
            known = true;
            bearing = next;
            invalidate();
            return;
        }
        // Glide the short way round, so 350° → 10° turns 20°, not 340°.
        float delta = ((next - bearing + 540) % 360) - 180;
        if (Math.abs(delta) < 0.5f) return;
        if (glide != null) glide.cancel();
        final float from = bearing;
        glide = ValueAnimator.ofFloat(0f, delta);
        glide.setDuration(700L);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> {
            bearing = ((from + (float) a.getAnimatedValue()) % 360 + 360) % 360;
            invalidate();
        });
        glide.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (glide != null) glide.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Fade both ends so the tape reads as continuous.
        fade.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0x00000000, 0xff000000, 0xff000000, 0x00000000},
                new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0) return;
        float cx = w / 2f;
        float pxPerDegree = w / WINDOW;
        float baseline = h * 0.62f;
        float alphaScale = known ? 1f : 0.35f;
        for (int step = -12; step <= 12; step++) {
            float tick = Math.round(bearing / 15f) * 15f + step * 15f;
            float x = cx + (tick - bearing) * pxPerDegree;
            if (x < -20 || x > w + 20) continue;
            int index = Math.round(((tick % 360) + 360) % 360 / 15f) % 24;
            if (index % 3 == 0) {
                String name = NAMES[index / 3];
                boolean cardinal = name.length() == 1;
                paint.setTypeface(MEDIUM);
                paint.setTextSize((cardinal ? 12 : 10) * density);
                paint.setColor(cardinal ? ink : muted);
                paint.setAlpha(Math.round((cardinal ? 0xff : 0xc0) * alphaScale));
                canvas.drawText(name, x, baseline, paint);
            } else {
                paint.setColor(muted);
                paint.setAlpha(Math.round(0x60 * alphaScale));
                paint.setStrokeWidth(1.2f * density);
                canvas.drawLine(x, baseline - 9 * density, x, baseline - 3 * density, paint);
            }
        }
        // Caret marks the direction of travel.
        paint.setColor(accent);
        paint.setAlpha(Math.round(0xff * alphaScale));
        paint.setStrokeWidth(2 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx, h - 3 * density, cx, h - 1 * density, paint);
        canvas.drawCircle(cx, h - 2 * density, 2 * density, paint);
        canvas.drawRect(0, 0, w, h, fade);
    }
}
