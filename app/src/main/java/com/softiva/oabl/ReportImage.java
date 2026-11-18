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
        /** Trip report only: the four headline figures and the lines of plain-language highlights above everything else. */
        DriveStatsView.Cell[] heroes = new DriveStatsView.Cell[0];
        String[] highlights = new String[0];
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

    /** Most event rows a trip picture lists; the CSV carries any beyond. */
    static final int TRIP_ROWS = 1000;
    /** The trip picture is drawn at this multiple of its layout (2160 px for a 1080 layout), less for a very long trip. */
    static final float TRIP_SCALE = 2f;
    private static final double TRIP_PIXEL_BUDGET = 150e6;

    /**
     * The trip report: highlights, the twelve figures, the distance chart and every event with its position, as one
     * picture. Nothing is shortened: the picture is as wide as its longest line needs and as tall as the events need.
     */
    static Bitmap renderTrip(Model m, java.util.List<TripReport.Row> rows) {
        float pad = 56, gap = 16, cardHeight = 142, rowHeight = 100, compactHeight = 40;
        TextPaint label = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 22, MUTED);
        label.setLetterSpacing(0.14f);
        TextPaint value = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 50, INK);
        value.setFontFeatureSettings("tnum");
        TextPaint unit = text(Typeface.create("sans-serif", Typeface.NORMAL), 24, MUTED);
        TextPaint note = text(Typeface.create("sans-serif", Typeface.NORMAL), 21, 0xb08e98a3);
        TextPaint title = text(Typeface.create("sans-serif-light", Typeface.NORMAL), 76, INK);
        TextPaint subtitle = text(Typeface.create("sans-serif", Typeface.NORMAL), 30, MUTED);
        TextPaint brand = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 26, ACCENT);
        brand.setLetterSpacing(0.3f);
        TextPaint heroValue = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 54, INK);
        heroValue.setFontFeatureSettings("tnum");
        TextPaint bullet = text(Typeface.create("sans-serif", Typeface.NORMAL), 25, INK);
        TextPaint head = text(Typeface.create("sans-serif-medium", Typeface.NORMAL), 26, INK);
        TextPaint detail = text(Typeface.create("sans-serif", Typeface.NORMAL), 23, 0xffb3bcc6);
        TextPaint place = text(Typeface.create("sans-serif", Typeface.NORMAL), 22, ACCENT);
        TextPaint small = text(Typeface.create("sans-serif", Typeface.NORMAL), 21, 0xffb3bcc6);

        // The width every line needs.
        float textLeft = pad + 28 + 30;
        float cardNeed = 0, heroNeed = 0, wide = WIDTH;
        for (int i = 0; i < m.cells.length && i < 12; i++) {
            DriveStatsView.Cell c = m.cells[i];
            float v = value.measureText(c.value) + (c.unit == null || c.unit.isEmpty() ? 0 : 10 + unit.measureText(c.unit));
            cardNeed = Math.max(cardNeed, Math.max(label.measureText(c.label.toUpperCase(Locale.ROOT)), Math.max(v, note.measureText(c.note))) + 56);
        }
        wide = Math.max(wide, 2 * cardNeed + gap + 2 * pad);
        for (int i = 0; i < m.heroes.length && i < 4; i++) {
            DriveStatsView.Cell c = m.heroes[i];
            heroNeed = Math.max(heroNeed, Math.max(label.measureText(c.label.toUpperCase(Locale.ROOT)), Math.max(heroValue.measureText(c.value),
                    Math.max(unit.measureText(c.unit == null ? "" : c.unit), note.measureText(c.note)))) + 28);
        }
        wide = Math.max(wide, 4 * heroNeed + 56 + 2 * pad);
        for (String line : m.highlights) wide = Math.max(wide, bullet.measureText(line) + 56 + 28 + 2 * pad);
        for (TripReport.Row r : rows) {
            float need = r.compact ? small.measureText(r.title) : Math.max(head.measureText(r.title), Math.max(detail.measureText(r.detail), place.measureText(r.place)));
            wide = Math.max(wide, textLeft + need + 28 + pad);
        }
        wide = Math.max(wide, Math.max(title.measureText(m.title), subtitle.measureText(m.subtitle)) + 2 * pad);
        final float W = (float) Math.ceil(wide);

        int figureRows = (Math.min(m.cells.length, 12) + 1) / 2;
        float highlightsTop = 300, bulletHeight = 46;
        float highlightsHeight = 70 + 190 + 24 + m.highlights.length * bulletHeight + 24;
        float figuresLabel = highlightsTop + highlightsHeight + 56, figuresTop = figuresLabel + 22;
        float chartTop = figuresTop + figureRows * (cardHeight + gap) + 8, chartBottom = chartTop + 300;
        float listTop = chartBottom + 28, listHeader = 70;
        float rowsHeight = 0;
        for (TripReport.Row r : rows) rowsHeight += r.compact ? compactHeight : rowHeight;
        int height = (int) (listTop + listHeader + rowsHeight + 150);
        float scale = (float) Math.min(TRIP_SCALE, Math.sqrt(TRIP_PIXEL_BUDGET / ((double) W * height)));
        Bitmap bitmap = Bitmap.createBitmap((int) (W * scale), (int) (height * scale), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(BACKGROUND);
        canvas.drawRect(0, 0, W, height, fill);
        fill.setShader(new RadialGradient(180, 120, 760, 0x30c7b18c, 0x00c7b18c, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, 1400, fill);
        fill.setShader(null);

        canvas.drawText(m.brand, pad, 96, brand);
        if (!m.vehicle.isEmpty()) {
            String v = m.vehicle.toUpperCase(Locale.ROOT);
            canvas.drawText(v, W - pad - label.measureText(v), 96, label);
        }
        canvas.drawText(m.title, pad, 196, title);
        canvas.drawText(m.subtitle, pad, 250, subtitle);

        RectF rect = new RectF();
        // Highlights: four headline figures, then what happened in a few plain lines.
        rect.set(pad, highlightsTop, W - pad, highlightsTop + highlightsHeight);
        fill.setColor(CARD);
        canvas.drawRoundRect(rect, 22, 22, fill);
        brand.setTextSize(22);
        canvas.drawText("HIGHLIGHTS", pad + 28, highlightsTop + 48, brand);
        float heroWidth = (W - 2 * pad - 56) / 4f;
        for (int i = 0; i < m.heroes.length && i < 4; i++) {
            DriveStatsView.Cell hero = m.heroes[i];
            float x = pad + 28 + i * heroWidth, y = highlightsTop + 70;
            label.setColor(MUTED);
            canvas.drawText(hero.label.toUpperCase(Locale.ROOT), x, y + 26, label);
            canvas.drawText(hero.value, x, y + 98, heroValue);
            if (hero.unit != null && !hero.unit.isEmpty()) canvas.drawText(hero.unit, x, y + 138, unit);
            if (!hero.note.isEmpty()) canvas.drawText(hero.note, x, y + 172, note);
        }
        float by = highlightsTop + 70 + 190 + 24;
        fill.setColor(0x22e9edf1);
        canvas.drawRect(pad + 28, by - 18, W - pad - 28, by - 17, fill);
        for (int i = 0; i < m.highlights.length; i++) {
            fill.setColor(ACCENT);
            canvas.drawCircle(pad + 28 + 6, by + i * bulletHeight + 16, 5, fill);
            canvas.drawText(m.highlights[i], pad + 28 + 28, by + i * bulletHeight + 24, bullet);
        }
        label.setColor(MUTED);
        canvas.drawText("ALL FIGURES", pad + 8, figuresLabel, label);

        float cardWidth = (W - 2 * pad - gap) / 2;
        for (int i = 0; i < m.cells.length && i < 12; i++) {
            float left = pad + (i % 2) * (cardWidth + gap), y = figuresTop + (i / 2) * (cardHeight + gap);
            rect.set(left, y, left + cardWidth, y + cardHeight);
            fill.setColor(CARD);
            canvas.drawRoundRect(rect, 22, 22, fill);
            DriveStatsView.Cell cell = m.cells[i];
            label.setColor(MUTED);
            canvas.drawText(cell.label.toUpperCase(Locale.ROOT), left + 28, y + 36, label);
            canvas.drawText(cell.value, left + 28, y + 90, value);
            if (cell.unit != null && !cell.unit.isEmpty()) canvas.drawText(cell.unit, left + 28 + value.measureText(cell.value) + 10, y + 90, unit);
            if (!cell.note.isEmpty()) canvas.drawText(cell.note, left + 28, y + 124, note);
        }

        rect.set(pad, chartTop, W - pad, chartBottom);
        fill.setColor(CARD);
        canvas.drawRoundRect(rect, 22, 22, fill);
        label.setColor(MUTED);
        canvas.drawText(m.chartTitle.toUpperCase(Locale.ROOT), pad + 28, chartTop + 44, label);
        if (!m.chartSum.isEmpty()) canvas.drawText(m.chartSum, W - pad - 28 - label.measureText(m.chartSum), chartTop + 44, label);
        drawBars(canvas, m, pad + 28, chartTop + 70, W - pad - 28, chartBottom - 18, label, fill);

        // The events, in time order, each with where it happened.
        rect.set(pad, listTop, W - pad, listTop + listHeader + rowsHeight + 10);
        fill.setColor(CARD);
        canvas.drawRoundRect(rect, 22, 22, fill);
        label.setColor(MUTED);
        label.setTextSize(22);
        label.setLetterSpacing(0.14f);
        canvas.drawText("EVERY EVENT · GPS POSITIONS", pad + 28, listTop + 46, label);
        float y = listTop + listHeader;
        for (int i = 0; i < rows.size(); i++) {
            TripReport.Row r = rows.get(i);
            if (r.compact) {
                if (r.kind != 3) { fill.setColor(MUTED); canvas.drawCircle(pad + 28 + 9, y + 18, 4, fill); }
                canvas.drawText(r.title, textLeft, y + 26, small);
                y += compactHeight;
                continue;
            }
            if (i > 0) { fill.setColor(0x22e9edf1); canvas.drawRect(pad + 28, y - 4, W - pad - 28, y - 3, fill); }
            fill.setColor(r.kind == 0 ? ACCENT : r.kind == 2 ? CHARGE : MUTED);
            canvas.drawCircle(pad + 28 + 9, y + 20, 9, fill);
            canvas.drawText(r.title, textLeft, y + 28, head);
            if (!r.detail.isEmpty()) canvas.drawText(r.detail, textLeft, y + 58, detail);
            if (!r.place.isEmpty()) canvas.drawText(r.place, textLeft, y + 88, place);
            y += rowHeight;
        }
        canvas.drawText(m.footer, (W - note.measureText(m.footer)) / 2, height - 56, note);
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
