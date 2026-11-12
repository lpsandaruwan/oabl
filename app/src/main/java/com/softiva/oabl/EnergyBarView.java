package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Battery bar for the Home glance: the last entered percentage as a solid fill, an estimate since
 * that reading as a lighter fill, and a tick at the reserve. Drawn only when a value changes.
 */
public final class EnergyBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float entered = -1, estimated = -1, reserve = -1, target = -1;
    private int accent = 0xffc9b48e, track = 0x24ffffff, marker = 0xffa3aeb9;
    private float thicknessDp = 8;

    /** How thick the bar is drawn, in dp; the view height caps it. */
    void setThickness(float dp) { if (dp != thicknessDp) { thicknessDp = dp; invalidate(); } }

    public EnergyBarView(Context context, AttributeSet attributes) { super(context, attributes); }

    /** Percentages 0–100; a negative value hides that part. */
    void set(float entered, float estimated, float reserve, int accent, int track, int marker) {
        if (entered == this.entered && estimated == this.estimated && reserve == this.reserve
                && accent == this.accent && track == this.track && marker == this.marker) return;
        this.entered = entered; this.estimated = estimated; this.reserve = reserve;
        this.accent = accent; this.track = track; this.marker = marker;
        setContentDescription(entered < 0 ? "Battery not entered"
                : "Battery " + Math.round(entered) + " percent entered" + (estimated >= 0 ? ", about " + Math.round(estimated) + " percent estimated now" : ""));
        invalidate();
    }

    /** While charging: the car's charge target, drawn as a quiet fill ahead of the level and a tick. */
    void setTarget(float target) { if (target != this.target) { this.target = target; invalidate(); } }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight(), w = getWidth(), bar = Math.min(h, thicknessDp * getResources().getDisplayMetrics().density);
        float top = (h - bar) / 2f, radius = bar / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        rect.set(0, top, w, top + bar);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (entered >= 0 && target > entered) {
            paint.setColor((accent & 0x00ffffff) | 0x33000000);
            rect.set(0, top, w * Math.min(100, target) / 100f, top + bar);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (entered >= 0) {
            float solid = Math.max(0, Math.min(100, estimated >= 0 ? Math.min(entered, estimated) : entered));
            if (estimated >= 0 && entered > estimated) {
                // The part used since the reading, by estimate: a quieter fill.
                paint.setColor((accent & 0x00ffffff) | 0x55000000);
                rect.set(0, top, w * Math.min(100, entered) / 100f, top + bar);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            paint.setColor(accent);
            rect.set(0, top, w * solid / 100f, top + bar);
            if (rect.width() > 0) canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (target > 0 && target < 100) {
            paint.setColor(accent);
            float x = w * target / 100f, d = getResources().getDisplayMetrics().density;
            canvas.drawRect(x - d, 0, x + d, h, paint);
        }
        if (reserve > 0 && reserve < 100) {
            paint.setColor(marker);
            float x = w * reserve / 100f, d = getResources().getDisplayMetrics().density;
            canvas.drawRect(x - d, 0, x + d, h, paint);
        }
    }
}
