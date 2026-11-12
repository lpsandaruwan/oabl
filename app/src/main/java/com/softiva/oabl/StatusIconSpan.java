package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/** Small level-aware Wi-Fi/cellular icon that behaves like one text glyph. */
final class StatusIconSpan extends ReplacementSpan {
    static final int WIFI = 0;
    static final int CELLULAR = 1;

    private final int kind;
    private final int level;
    private final int activeColor;
    private final int inactiveColor;
    private final float density;

    StatusIconSpan(Context context, int kind, int level, int activeColor, int inactiveColor) {
        this.kind = kind;
        this.level = Math.max(0, Math.min(4, level));
        this.activeColor = activeColor;
        this.inactiveColor = inactiveColor;
        density = context.getResources().getDisplayMetrics().density;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fontMetrics) {
        if (fontMetrics != null) {
            Paint.FontMetricsInt original = paint.getFontMetricsInt();
            int iconTop = -Math.round(18f * density);
            fontMetrics.ascent = Math.min(original.ascent, iconTop);
            fontMetrics.top = Math.min(original.top, iconTop);
            fontMetrics.descent = original.descent;
            fontMetrics.bottom = original.bottom;
        }
        return Math.round(25f * density);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, Paint paint) {
        if (kind == WIFI) {
            drawWifi(canvas, x, y);
        } else {
            drawCellular(canvas, x, y);
        }
    }

    private void drawCellular(Canvas canvas, float x, float baseline) {
        Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        float barWidth = 3f * density;
        float gap = 2f * density;
        float barBottom = baseline - 2f * density;
        for (int i = 0; i < 4; i++) {
            icon.setColor(i < level ? activeColor : inactiveColor);
            float height = (4f + i * 3f) * density;
            float left = x + 2f * density + i * (barWidth + gap);
            canvas.drawRoundRect(left, barBottom - height, left + barWidth, barBottom,
                    density, density, icon);
        }
    }

    private void drawWifi(Canvas canvas, float x, float baseline) {
        Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeWidth(2.1f * density);
        float centerX = x + 11f * density;
        float centerY = baseline - 2f * density;

        icon.setStyle(Paint.Style.FILL);
        icon.setColor(level >= 1 ? activeColor : inactiveColor);
        canvas.drawCircle(centerX, centerY, 1.8f * density, icon);
        icon.setStyle(Paint.Style.STROKE);
        for (int ring = 1; ring <= 3; ring++) {
            icon.setColor(level >= ring + 1 ? activeColor : inactiveColor);
            float radius = (3f + ring * 3.4f) * density;
            RectF arc = new RectF(centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius);
            canvas.drawArc(arc, 225f, 90f, false, icon);
        }
    }
}
