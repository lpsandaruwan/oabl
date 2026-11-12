package com.softiva.oabl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Thin bar that fills as the next maneuver approaches. */
public final class TurnProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private ValueAnimator glide;
    private float fraction;
    private int fill = 0xff86d9c7;
    private int track = 0x2effffff;

    public TurnProgressView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    void colors(int fill, int track) {
        this.fill = fill;
        this.track = track;
        invalidate();
    }

    void progress(float next, boolean animate) {
        next = Math.max(0f, Math.min(1f, next));
        if (glide != null) glide.cancel();
        // Snap backwards (a new maneuver started); glide forwards.
        if (!animate || next < fraction) {
            fraction = next;
            invalidate();
            return;
        }
        glide = ValueAnimator.ofFloat(fraction, next);
        glide.setDuration(600L);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
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
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        bar.set(0, 0, getWidth(), h);
        paint.setColor(track);
        canvas.drawRoundRect(bar, h / 2, h / 2, paint);
        bar.right = Math.max(h, getWidth() * fraction);
        paint.setColor(fill);
        canvas.drawRoundRect(bar, h / 2, h / 2, paint);
    }
}
