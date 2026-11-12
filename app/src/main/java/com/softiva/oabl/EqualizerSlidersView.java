package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Car-friendly alternative to the curve: one tall fader per band with a large thumb. */
final class EqualizerSlidersView extends View {
    private final LauncherTheme.Palette palette;
    private final EqualizerModel model;
    private final EqualizerCurveView.Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float[] values;
    private float[] baseline;
    private int active = -1;
    private int selected;
    private float top, bottom, step, downY, startValue;

    EqualizerSlidersView(Context context, LauncherTheme.Palette palette, EqualizerModel model,
                         EqualizerCurveView.Listener listener) {
        super(context);
        this.palette = palette;
        this.model = model;
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
        values = model.values();
        setContentDescription("Equalizer faders");
    }

    void sync() {
        values = model.values();
        if (selected >= values.length) selected = values.length - 1;
        invalidate();
    }

    void select(int band) {
        selected = Math.max(0, Math.min(values.length - 1, band));
        invalidate();
    }

    int selected() { return selected; }

    private float dp(float value) { return value * density; }

    private float y(float db) { return (top + bottom) / 2 - db * (bottom - top) / (2 * model.range()); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Room above the track for the value label, clear of a thumb at +6 dB.
        top = dp(46);
        bottom = h - dp(34);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            active = Math.max(0, Math.min(values.length - 1, (int) (event.getX() / step)));
            listener.beforeEdit();
            baseline = model.values();
            downY = event.getY();
            selected = active;
            getParent().requestDisallowInterceptTouchEvent(true);
            // A tap away from the thumb jumps straight to that level; dragging continues from there.
            startValue = Math.abs(event.getY() - y(baseline[active])) > dp(28) ? levelAt(event.getY()) : baseline[active];
            if (startValue != baseline[active]) set(startValue);
            listener.changed();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && active >= 0) {
            set(startValue + (downY - event.getY()) * 2 * model.range() / (bottom - top));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (active >= 0) listener.committed();
            active = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private float levelAt(float py) {
        return ((top + bottom) / 2 - py) * 2 * model.range() / (bottom - top);
    }

    private void set(float value) {
        float target = Math.round(EqualizerProfile.clamp(value, -model.range(), model.range()) * 2) / 2f;
        model.set(model.linked(baseline, active, target));
        values = model.values();
        invalidate();
        listener.changed();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = values.length;
        if (n == 0 || bottom <= top) return;
        step = getWidth() / (float) n;
        float zero = y(0);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < n; i++) {
            float cx = step * i + step / 2;
            boolean chosen = i == selected;
            paint.setStrokeWidth(dp(n > 15 ? 4 : 6));
            paint.setColor(palette.light ? 0x18142e38 : 0x18ffffff);
            c.drawLine(cx, top, cx, bottom, paint);
            float vy = y(values[i]);
            paint.setColor(chosen ? palette.accent : (palette.accent & 0x00ffffff) | 0xb0000000);
            c.drawLine(cx, zero, cx, vy, paint);
            paint.setStyle(Paint.Style.FILL);
            float thumb = dp(n > 15 ? 9 : 13);
            paint.setColor(palette.light ? 0xffffffff : 0xff1b252f);
            c.drawCircle(cx, vy, thumb, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(chosen ? 3 : 2));
            paint.setColor(palette.accent);
            c.drawCircle(cx, vy, thumb, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(n > 15 ? 9 : 11));
            paint.setColor(chosen ? palette.accent : palette.muted);
            if (n <= 15 || i % 2 == 0 || chosen) c.drawText(model.label(i), cx, bottom + dp(20), paint);
            if (chosen || n <= 15) {
                paint.setColor(chosen ? palette.accent : palette.muted);
                c.drawText(String.format(Locale.US, "%+.1f", values[i]), cx, top - dp(22), paint);
            }
        }
    }
}
