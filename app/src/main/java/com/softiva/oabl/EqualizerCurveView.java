package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/**
 * Logarithmic tone editor. Each band has a column with its name on top and its frequency below;
 * an optional live spectrum sits behind the filled curve. Drag up or down anywhere in a band's
 * column to change it, or tap a column to select it.
 */
final class EqualizerCurveView extends View {
    interface Listener { void beforeEdit(); void changed(); void committed(); }
    // A little beyond the outer bands (40 Hz, 16 kHz) so their points and labels aren't on the edge.
    private static final float MIN_HZ = 28, MAX_HZ = 23000;
    private final LauncherTheme.Palette palette;
    private final EqualizerModel model;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path editedLine = new Path(), fill = new Path();
    private final float density;
    private final int slop;
    private float[] xs = new float[0], ys = new float[0];
    private float[] values, baseline, spectrum;
    private float spectrumHigh = 16000;
    private float top, bottom, left, right, downY, downX;
    private int selected = 7, touched = -1; // 100 Hz
    private boolean held, moved;
    private Shader shading;

    EqualizerCurveView(Context context, LauncherTheme.Palette palette, EqualizerModel model, Listener listener) {
        super(context);
        this.palette = palette;
        this.model = model;
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setClickable(true);
        values = model.values();
        describe();
    }

    int selected() { return selected; }
    void select(int band) {
        selected = Math.max(0, Math.min(model.count() - 1, band));
        describe();
        invalidate();
        listener.changed();
    }
    void sync() {
        values = model.values();
        if (selected >= values.length) selected = values.length - 1;
        geometry();
        describe();
        invalidate();
    }
    float bandX(int band) { return xs[band]; }
    float bandY(int band) { return ys[band]; }

    /** Live analyser levels (0…1) for log bands between 60 Hz and {@code high}. */
    void spectrum(float[] levels, float high) {
        spectrum = levels;
        spectrumHigh = high;
        invalidate();
    }

    void adjust(float delta) {
        listener.beforeEdit();
        float[] current = model.values();
        setGain(current, current[selected] + delta);
        listener.committed();
    }

    private void setGain(float[] from, float value) {
        float target = Math.round(EqualizerProfile.clamp(value, -model.range(), model.range()) * 2) / 2f;
        model.set(model.linked(from, selected, target));
        sync();
        listener.changed();
    }

    private float dp(float value) { return value * density; }

    private float x(float hz) {
        return left + (right - left) * (float) (Math.log(hz / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ));
    }

    private float y(float db) {
        return (top + bottom) / 2 - db * (bottom - top) / (2 * model.range());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        left = dp(44);
        right = w - dp(16);
        top = dp(36);
        bottom = h - dp(30);
        shading = new LinearGradient(0, top, 0, bottom, (palette.accent & 0x00ffffff) | 0x38000000,
                (palette.accent & 0x00ffffff) | 0x03000000, Shader.TileMode.CLAMP);
        geometry();
    }

    private void geometry() {
        if (bottom <= top) return;
        float[] f = model.frequencies();
        xs = new float[f.length];
        ys = new float[f.length];
        for (int i = 0; i < f.length; i++) {
            xs[i] = x(f[i]);
            ys[i] = y(values[i]);
        }
        smooth(editedLine, xs, ys);
        fill.set(editedLine);
        fill.lineTo(xs[xs.length - 1], bottom);
        fill.lineTo(xs[0], bottom);
        fill.close();
    }

    private static void smooth(Path path, float[] px, float[] py) {
        path.reset();
        path.moveTo(px[0], py[0]);
        for (int i = 1; i < px.length; i++) {
            float middle = (px[i - 1] + px[i]) / 2;
            path.cubicTo(middle, py[i - 1], middle, py[i], px[i], py[i]);
        }
    }

    private int nearestX(float x) {
        int nearest = 0;
        for (int i = 1; i < xs.length; i++) if (Math.abs(x - xs[i]) < Math.abs(x - xs[nearest])) nearest = i;
        return nearest;
    }

    /** Half the distance to the neighbouring band, so every point of the plot belongs to one band. */
    private float column() {
        return xs.length < 2 ? dp(48) : Math.min(dp(64), (xs[1] - xs[0]) / 2);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            moved = false;
            held = false;
            touched = nearestX(downX);
            boolean inColumn = Math.abs(downX - xs[touched]) <= column() && downY > top - dp(12) && downY < bottom + dp(12);
            if (!inColumn) touched = -1;
            // On the point itself, start editing at once; elsewhere in the column, once the drag turns out vertical.
            if (touched >= 0 && Math.abs(downY - ys[touched]) <= dp(28)) grab();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            moved |= dy > slop || dx > slop;
            if (!held && touched >= 0 && dy > slop && dy > dx) grab();
            if (held && moved && bottom > top) setGain(baseline, baseline[selected] + (downY - event.getY()) * 2 * model.range() / (bottom - top));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (held) listener.committed();
            if (action == MotionEvent.ACTION_UP && !moved && !held) {
                // A tap selects the band whose column (or name, or frequency) it lands in.
                select(nearestX(event.getX()));
                performClick();
            }
            held = false;
            touched = -1;
            baseline = null;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }

    private void grab() {
        held = true;
        listener.beforeEdit();
        baseline = model.values();
        select(touched);
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { select(selected - 1); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { select(selected + 1); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_UP) { adjust(.5f); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { adjust(-.5f); return true; }
        return super.onKeyDown(key, event);
    }

    private void describe() {
        setContentDescription(String.format(Locale.US, "Equalizer curve, %d bands. Selected %s Hz, %s, %+.1f decibels.",
                model.count(), model.label(selected), model.role(selected), values[selected]));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, -model.range(), model.range(), values[selected]));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { adjust(.5f); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { adjust(-.5f); return true; }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
            listener.beforeEdit();
            setGain(model.values(), args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));
            listener.committed();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bottom <= top || xs.length == 0) return;
        int faint = palette.light ? 0x10142e38 : 0x0cffffff;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10.5f));
        paint.setTextAlign(Paint.Align.CENTER);
        // The selected band's column.
        if (selected < xs.length) {
            float half = column();
            paint.setColor((palette.accent & 0x00ffffff) | 0x14000000);
            c.drawRoundRect(xs[selected] - half + dp(3), top - dp(30), xs[selected] + half - dp(3), bottom + dp(28), dp(12), dp(12), paint);
        }
        // Live spectrum, faint, behind everything else.
        if (spectrum != null) {
            paint.setColor((palette.accent & 0x00ffffff) | 0x30000000);
            int n = spectrum.length;
            for (int i = 0; i < n; i++) {
                float lo = (float) (60 * Math.pow(spectrumHigh / 60, i / (double) n));
                float hi = (float) (60 * Math.pow(spectrumHigh / 60, (i + 1) / (double) n));
                float h = spectrum[i] * (bottom - top) * .85f;
                c.drawRect(x(lo) + dp(1), bottom - h, x(hi) - dp(1), bottom, paint);
            }
        }
        paint.setStrokeWidth(dp(1));
        int range = Math.round(model.range());
        for (int db = -range; db <= range; db += range / 2) {
            float gy = y(db);
            paint.setColor(db == 0 ? (palette.light ? 0x35142e38 : 0x35ffffff) : faint);
            c.drawLine(left, gy, right, gy, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(palette.muted);
            c.drawText(db > 0 ? "+" + db : "" + db, left - dp(12), gy + dp(3), paint);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < xs.length; i++) {
            boolean chosen = i == selected;
            paint.setColor(chosen ? (palette.accent & 0x00ffffff) | 0x45000000 : faint);
            c.drawLine(xs[i], top, xs[i], bottom, paint);
            paint.setTextSize(dp(11));
            paint.setColor(chosen ? palette.accent : palette.muted);
            paint.setTypeface(chosen ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            // Names need about 64 dp each; in a narrow pane only the selected band's is shown.
            if (chosen || column() * 2 >= dp(64)) c.drawText(model.role(i).toUpperCase(Locale.ROOT), xs[i], top - dp(14), paint);
            // More than 15 bands: every other frequency, a size smaller, plus the selected one.
            boolean many = xs.length > 15;
            paint.setTextSize(dp(many ? 10.5f : 12));
            paint.setColor(chosen ? palette.accent : palette.ink);
            if (!many || i % 2 == 0 || chosen) c.drawText(model.label(i), xs[i], bottom + dp(20), paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setShader(shading);
        c.drawPath(fill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(palette.accent);
        paint.setStrokeWidth(dp(2.5f));
        c.drawPath(editedLine, paint);
        paint.setStyle(Paint.Style.FILL);
        float small = xs.length > 15 ? 3 : 4;
        for (int i = 0; i < xs.length; i++) {
            if (i == selected) {
                paint.setColor((palette.accent & 0x00ffffff) | 0x28000000);
                c.drawCircle(xs[i], ys[i], dp(19), paint);
            }
            paint.setColor(palette.accent);
            c.drawCircle(xs[i], ys[i], dp(i == selected ? 7 : small), paint);
            paint.setColor(palette.light ? 0xffe9eef2 : 0xff151e28);
            c.drawCircle(xs[i], ys[i], dp(i == selected ? 3 : 1.5f), paint);
        }
    }
}
