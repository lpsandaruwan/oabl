package com.softiva.oabl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Locale;

/**
 * A shareable picture of a report: the Drive &amp; Energy figures for a day, week, month or trip on OABL's
 * graphite and champagne look, always dark whatever the launcher theme, 1080 x 1350 so it fits a chat.
 * Drawn straight to a bitmap; nothing here needs the screen.
 */
final class ReportImage {
    static final int WIDTH = 1080, HEIGHT = 1350;

    /** What to draw. Cells are the eight figures (two columns, four rows); bars are the chart. */
    static final class Model {
        String brand = "OABL", vehicle = "", title = "", subtitle = "", footer = "";
        DriveStatsView.Cell[] cells = new DriveStatsView.Cell[0];
        double[] bars = new double[0];
        String[] names = new String[0];
        boolean[] marks = new boolean[0];
        String chartTitle = "", chartSum = "";
    }

    private static final int BACKGROUND = 0xff101419, CARD = 0xff1a2027, RAISED = 0xff232b34, INK = 0xffe9edf1,
            MUTED = 0xff8e98a3, ACCENT = 0xffc7b18c, CHARGE = 0xff6fd38f;

    private ReportImage() { }

    static Bitmap render(Model m) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(BACKGROUND);
        canvas.drawRect(0, 0, WIDTH, HEIGHT, fill);
        // The faint champagne glow of the dashboard, top left.
        fill.setShader(new RadialGradient(180, 120, 760, 0x30c7b18c, 0x00c7b18c, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, fill);
        fill.setShader(null);

        TextPaint label = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 22, MUTED);
        label.setLetterSpacing(0.14f);
        TextPaint value = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 58, INK);
        value.setFontFeatureSettings("tnum");
        TextPaint unit = text(Typeface.create("sans-serif", Typeface.NORMAL), 26, MUTED);
        TextPaint note = text(Typeface.create("sans-serif", Typeface.NORMAL), 22, 0xb08e98a3);
        TextPaint title = text(Typeface.create("sans-serif-light", Typeface.NORMAL), 76, INK);
        TextPaint subtitle = text(Typeface.create("sans-serif", Typeface.NORMAL), 32, MUTED);

        float pad = 56;
        // Brand row.
        TextPaint brand = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 26, ACCENT);
        brand.setLetterSpacing(0.3f);
        canvas.drawText(m.brand, pad, 96, brand);
        if (!m.vehicle.isEmpty()) {
            label.setColor(MUTED);
            String v = m.vehicle.toUpperCase(Locale.ROOT);
            canvas.drawText(v, WIDTH - pad - label.measureText(v), 96, label);
        }
        canvas.drawText(fit(m.title, title, WIDTH - 2 * pad), pad, 196, title);
        canvas.drawText(fit(m.subtitle, subtitle, WIDTH - 2 * pad), pad, 250, subtitle);

        // The figures.
        float gap = 24, cardWidth = (WIDTH - 2 * pad - gap) / 2, cardHeight = 156, top = 300;
        RectF rect = new RectF();
        for (int i = 0; i < m.cells.length && i < 8; i++) {
            int column = i % 2, row = i / 2;
            float left = pad + column * (cardWidth + gap), y = top + row * (cardHeight + gap);
            rect.set(left, y, left + cardWidth, y + cardHeight);
            fill.setColor(CARD);
            canvas.drawRoundRect(rect, 22, 22, fill);
            DriveStatsView.Cell cell = m.cells[i];
            float room = cardWidth - 2 * 28;
            label.setColor(MUTED);
            canvas.drawText(fit(cell.label.toUpperCase(Locale.ROOT), label, room), left + 28, y + 40, label);
            String shown = fit(cell.value, value, room);
            canvas.drawText(shown, left + 28, y + 100, value);
            if (cell.unit != null && !cell.unit.isEmpty()) {
                float x = left + 28 + value.measureText(shown) + 10;
                if (x + unit.measureText(cell.unit) <= left + 28 + room) canvas.drawText(cell.unit, x, y + 100, unit);
            }
            if (!cell.note.isEmpty()) canvas.drawText(fit(cell.note, note, room), left + 28, y + 134, note);
        }

        // The chart.
        float chartTop = top + 4 * (cardHeight + gap), chartBottom = HEIGHT - 110;
        rect.set(pad, chartTop, WIDTH - pad, chartBottom);
        fill.setColor(CARD);
        canvas.drawRoundRect(rect, 22, 22, fill);
        label.setColor(MUTED);
        canvas.drawText(fit(m.chartTitle.toUpperCase(Locale.ROOT), label, 520), pad + 28, chartTop + 44, label);
        if (!m.chartSum.isEmpty()) canvas.drawText(m.chartSum, WIDTH - pad - 28 - label.measureText(m.chartSum), chartTop + 44, label);
        drawBars(canvas, m, pad + 28, chartTop + 70, WIDTH - pad - 28, chartBottom - 18, label, fill);

        canvas.drawText(m.footer, (WIDTH - note.measureText(m.footer)) / 2, HEIGHT - 52, note);
        return bitmap;
    }

    private static void drawBars(Canvas canvas, Model m, float left, float top, float right, float bottom, TextPaint label, Paint fill) {
        int count = m.bars.length;
        if (count == 0) return;
        double max = 1;
        for (double v : m.bars) max = Math.max(max, v);
        float nameRow = 34, valueRow = 28;
        float barsTop = top + valueRow, barsBottom = bottom - nameRow;
        float slot = (right - left) / count, barWidth = Math.min(slot * 0.56f, 44);
        label.setTextSize(20);
        label.setLetterSpacing(0.06f);
        RectF bar = new RectF();
        boolean every = slot >= 52;
        for (int i = 0; i < count; i++) {
            float center = left + slot * i + slot / 2;
            double km = m.bars[i];
            float h = (float) (km / max) * (barsBottom - barsTop);
            boolean now = i == count - 1;
            fill.setColor(now ? ACCENT : 0x55e9edf1);
            bar.set(center - barWidth / 2, barsBottom - Math.max(h, 4), center + barWidth / 2, barsBottom);
            canvas.drawRoundRect(bar, 6, 6, fill);
            if (km >= 0.05 && (every || km >= max)) {
                String t = km >= 100 ? String.valueOf(Math.round(km)) : String.format(Locale.US, "%.1f", km);
                label.setColor(now ? ACCENT : INK);
                canvas.drawText(t, clamp(center - label.measureText(t) / 2, left, right - label.measureText(t)), bar.top - 8, label);
            }
            if (i < m.marks.length && m.marks[i]) {
                fill.setColor(CHARGE);
                canvas.drawCircle(center + barWidth / 2 + 8, barsBottom - 5, 5, fill);
            }
            String name = i < m.names.length ? m.names[i] : "";
            if (name != null && !name.isEmpty()) {
                label.setColor(now ? INK : MUTED);
                canvas.drawText(name, clamp(center - label.measureText(name) / 2, left, right - label.measureText(name)), bottom - 8, label);
            }
        }
        label.setLetterSpacing(0.14f);
        label.setTextSize(22);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(Math.max(lo, hi), v)); }

    private static TextPaint text(Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    private static String fit(String text, TextPaint paint, float room) {
        return TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString();
    }
}
