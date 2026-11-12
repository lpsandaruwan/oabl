package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The idle navigation card's trip computer: a grid of labelled live values (economy, speed, trip,
 * idle energy, last charge, parked time) and, when the card is tall enough, a distance chart for the
 * chosen period (today by the hour, the last 7 or the last 30 days) with a Day / Week / Month switch:
 * distance bars, each day's battery energy (week), and a mark where a charge happened.
 * One view drawn directly, so a 1 Hz refresh costs one invalidate.
 */
final class DriveStatsView extends View {
    static final class Cell {
        final String label, value, unit;
        /** A quiet line under the value: the period, times or place the value is about. */
        String note = "";
        Cell(String label, String value, String unit) { this.label = label; this.value = value; this.unit = unit; }
        Cell note(String text) { note = text == null ? "" : text; return this; }
    }

    private static final int COLUMNS = 4;
    private static final int CHARGE = 0xff6fd38f;
    private Cell[] cells = new Cell[0];
    private double[] week = new double[7], weekKwh = new double[7];
    private boolean[] charged = new boolean[7];
    private String weekNote = "";
    /** 0 day, 1 week, 2 month: HomePeriod's order. */
    private int period = 1;
    /** Where the Day / Week / Month chips were drawn last, for touches. */
    private final RectF[] chips = {new RectF(), new RectF(), new RectF(), new RectF()};
    /** The trip button and the share button, left of the period chips. */
    private final RectF[] actionChips = {new RectF(), new RectF()};
    private boolean chipsShown;
    /** Pressed chip: 0-3 the periods, 4 the trip button, 5 share; -1 none. */
    private int pressedChip = -1;
    private OnPeriodListener listener;
    private OnActionListener actionListener;
    private String tripLabel = "START TRIP";
    /** 0 no trip, 1 a trip is running, 2 waiting for the second tap that ends it. */
    private int tripState;
    private String[] barNames;
    private String chartTitle;
    private static final int TRIP_ON = 0xff6fd38f, TRIP_CONFIRM = 0xffe5a04a;

    interface OnPeriodListener { void onPeriod(int index); }

    interface OnActionListener { void onAction(int id); }

    static final int ACTION_TRIP = 0, ACTION_SHARE = 1;

    void setOnPeriodListener(OnPeriodListener listener) { this.listener = listener; }

    void setOnActionListener(OnActionListener listener) { this.actionListener = listener; }

    /** The trip button's words and state (0 none, 1 running, 2 asking to confirm the end). */
    void setTrip(String label, int state) {
        if (label.equals(tripLabel) && state == tripState) return;
        tripLabel = label;
        tripState = state;
        invalidate();
    }
    private boolean weekShown = true;
    private String shownKey = "";
    private int ink = 0xffe9edf1, muted = 0x99e9edf1, accent = 0xffc7b18c;
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;

    public DriveStatsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        label.setTypeface(medium);
        label.setLetterSpacing(0.14f);
        value.setTypeface(medium);
        value.setFontFeatureSettings("tnum");
        unit.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        note.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        rule.setStrokeWidth(Math.max(1f, d));
    }

    void colors(int ink, int muted, int accent) {
        if (this.ink == ink && this.muted == muted && this.accent == accent) return;
        this.ink = ink; this.muted = muted; this.accent = accent;
        invalidate();
    }

    /** km per day (oldest first), battery kWh per day (NaN unknown), charge days, and the week's note. */
    void show(Cell[] cells, double[] week, double[] kwh, boolean[] charged, String note) {
        show(cells, week, kwh, charged, note, 1);
    }

    /**
     * The same with a period: 0 today (24 bars, one per hour), 1 the last 7 days, 2 the last 30 days.
     * The arrays are oldest first and as long as the period has bars.
     */
    void show(Cell[] cells, double[] week, double[] kwh, boolean[] charged, String note, int period) {
        show(cells, week, kwh, charged, note, period, null, null);
    }

    /** With the bar labels ("" for none) and the chart title chosen by the caller (the trip view). */
    void show(Cell[] cells, double[] week, double[] kwh, boolean[] charged, String note, int period, String[] names, String title) {
        StringBuilder key = new StringBuilder();
        key.append(period).append('#').append(title).append('#');
        if (names != null) for (String name : names) key.append(name).append(',');
        for (Cell cell : cells) key.append(cell.label).append(cell.value).append(cell.unit).append(cell.note).append('|');
        if (week != null) for (double day : week) key.append(Math.round(day * 10)).append(',');
        else key.append("no-week");
        if (kwh != null) for (double day : kwh) key.append(Math.round(day * 100)).append(',');
        if (charged != null) for (boolean day : charged) key.append(day ? 'c' : '-');
        key.append(note);
        if (key.toString().equals(shownKey)) return;
        shownKey = key.toString();
        this.cells = cells;
        this.period = period;
        this.barNames = names;
        this.chartTitle = title;
        // No week (a trip summary): the cells take the whole view.
        this.weekShown = week != null;
        this.week = week == null ? new double[7] : week;
        this.weekKwh = kwh == null ? new double[this.week.length] : kwh;
        this.charged = charged == null ? new boolean[this.week.length] : charged;
        this.weekNote = note == null ? "" : note;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        chipsShown = false;
        if (width == 0 || height == 0 || cells.length == 0) return;
        boolean withWeek = weekShown && height >= 230 * d;
        float weekHeight = withWeek ? Math.min(height * 0.40f, 164 * d) : 0;
        float gridHeight = height - weekHeight - (withWeek ? 16 * d : 0);
        int rows = (cells.length + COLUMNS - 1) / COLUMNS;
        float cellWidth = width / (float) COLUMNS, cellHeight = gridHeight / rows;
        boolean notes = false;
        for (Cell cell : cells) notes |= !cell.note.isEmpty();
        float labelSize = clamp(cellHeight * 0.14f, 10 * d, 13 * d);
        float valueSize = clamp(cellHeight * (notes ? 0.32f : 0.40f), 18 * d, 34 * d);
        float noteSize = clamp(cellHeight * 0.13f, 10 * d, 13 * d);
        label.setTextSize(labelSize);
        value.setTextSize(valueSize);
        unit.setTextSize(Math.max(11 * d, valueSize * 0.42f));
        note.setTextSize(noteSize);
        label.setColor(muted);
        value.setColor(ink);
        unit.setColor(muted);
        note.setColor((muted & 0x00ffffff) | 0xb0000000);
        rule.setColor((muted & 0x00ffffff) | 0x26000000);

        for (int i = 0; i < cells.length; i++) {
            int column = i % COLUMNS, row = i / COLUMNS;
            float left = column * cellWidth, top = row * cellHeight;
            float inset = column == 0 ? 0 : 16 * d;
            if (column > 0) canvas.drawLine(left, top + 10 * d, left, top + cellHeight - 10 * d, rule);
            if (row > 0 && column == 0) canvas.drawLine(0, top, width, top, rule);
            Cell cell = cells[i];
            float block = labelSize + 8 * d + valueSize * 0.78f + (notes ? 8 * d + noteSize : 0);
            float labelBaseline = top + (cellHeight - block) / 2 + labelSize;
            float valueBaseline = labelBaseline + 8 * d + valueSize * 0.78f;
            float room = cellWidth - inset - 12 * d;
            // A long title ("DISTANCE · 7 DAYS") shrinks a little to fit its cell before it is cut off.
            String title = cell.label.toUpperCase(Locale.ROOT);
            float titleWide = label.measureText(title);
            if (titleWide > room) label.setTextSize(Math.max(9 * d, labelSize * room / titleWide));
            canvas.drawText(fit(title, label, room), left + inset, labelBaseline, label);
            label.setTextSize(labelSize);
            // A long word ("North West") shrinks to the cell instead of being cut off.
            float wide = value.measureText(cell.value);
            if (wide > room) value.setTextSize(Math.max(12 * d, valueSize * room / wide));
            String shown = fit(cell.value, value, room);
            canvas.drawText(shown, left + inset, valueBaseline, value);
            if (cell.unit != null && !cell.unit.isEmpty()) {
                float x = left + inset + value.measureText(shown) + 5 * d;
                if (x + unit.measureText(cell.unit) <= left + inset + room) canvas.drawText(cell.unit, x, valueBaseline, unit);
            }
            value.setTextSize(valueSize);
            if (!cell.note.isEmpty()) canvas.drawText(fit(cell.note, note, room), left + inset, valueBaseline + 8 * d + noteSize, note);
        }
        if (withWeek) drawWeek(canvas, gridHeight + 16 * d, width, height, labelSize);
    }

    private static final String[] PERIOD_TITLES = {"TODAY · KM PER HOUR", "LAST 7 DAYS", "LAST 30 DAYS", "TRIP · KM"};

    private void drawWeek(Canvas canvas, float top, int width, int height, float labelSize) {
        canvas.drawLine(0, top - 8 * d, width, top - 8 * d, rule);
        int count = week.length;
        double total = 0, max = 1;
        for (double day : week) { total += day; max = Math.max(max, day); }
        float headerHeight = 30 * d;
        float titleBaseline = top + headerHeight / 2 + labelSize * 0.35f;
        String title = chartTitle != null ? chartTitle : PERIOD_TITLES[Math.max(0, Math.min(3, period))];

        // The period switch, right-aligned in the header, and the trip and share buttons left of it.
        String[] names = {HomePeriod.DAY.chip.toUpperCase(Locale.ROOT), HomePeriod.WEEK.chip.toUpperCase(Locale.ROOT),
                HomePeriod.MONTH.chip.toUpperCase(Locale.ROOT), HomePeriod.TRIP.chip.toUpperCase(Locale.ROOT)};
        float chipHeight = 26 * d, chipTop = top + (headerHeight - chipHeight) / 2, x = width;
        for (int i = 3; i >= 0; i--) {
            float chipWidth = Math.max(54 * d, label.measureText(names[i]) + 24 * d);
            chips[i].set(x - chipWidth, chipTop, x, chipTop + chipHeight);
            x -= chipWidth + 6 * d;
        }
        x -= 10 * d;
        String[] actions = {tripLabel, "SHARE"};
        for (int i = 1; i >= 0; i--) {
            float chipWidth = Math.max(60 * d, label.measureText(actions[i]) + 28 * d);
            actionChips[i].set(x - chipWidth, chipTop, x, chipTop + chipHeight);
            x -= chipWidth + 6 * d;
        }
        chipsShown = true;
        for (int i = 0; i < 4; i++) drawChip(canvas, chips[i], names[i], i == period, accent, chipHeight, labelSize);
        int tripColor = tripState == 2 ? TRIP_CONFIRM : tripState == 1 ? TRIP_ON : accent;
        drawChip(canvas, actionChips[0], actions[0], tripState != 0, tripColor, chipHeight, labelSize);
        drawChip(canvas, actionChips[1], actions[1], false, accent, chipHeight, labelSize);
        label.setColor(muted);
        // The title takes what the buttons leave: "TRIP IN PROGRESS · KM PER HOUR" is cut rather than run into them.
        title = fit(title, label, Math.max(80 * d, actionChips[0].left - 14 * d));
        canvas.drawText(title, 0, titleBaseline, label);

        float left = label.measureText(title) + 20 * d;
        boolean anyCharge = false;
        for (boolean day : charged) anyCharge |= day;
        // The legend only when it clears the buttons (it ran into START TRIP on the box's wider card).
        if (anyCharge && left + 10 * d + label.measureText("CHARGED") + 14 * d <= actionChips[0].left) {
            // Legend for the charge marks.
            bar.setColor(CHARGE);
            canvas.drawCircle(left + 3 * d, titleBaseline - labelSize * 0.35f, 3 * d, bar);
            canvas.drawText("CHARGED", left + 10 * d, titleBaseline, label);
            left += 10 * d + label.measureText("CHARGED") + 20 * d;
        }
        String sum = total >= 0.05 ? "GPS " + format(total) + " km" + (weekNote.isEmpty() ? "" : "  ·  " + weekNote) : "No drives yet";
        float sumRight = actionChips[0].left - 16 * d;
        if (sumRight - left > label.measureText(sum)) canvas.drawText(sum, sumRight - label.measureText(sum), titleBaseline, label);

        // Under each bar: the label (hour, weekday or date), and in the week view the battery energy that day.
        boolean energyRow = period == 1;
        float dayRow = labelSize * (energyRow ? 2 : 1) + 14 * d;
        float valueRow = labelSize + 6 * d;
        float barsTop = top + headerHeight + 4 * d + valueRow, barsBottom = height - dayRow;
        float slot = width / (float) count, barWidth = Math.min(slot * 0.5f, 44 * d);
        Calendar day = Calendar.getInstance();
        int current = period == 0 ? day.get(Calendar.HOUR_OF_DAY) : count - 1;
        day.add(Calendar.DAY_OF_YEAR, -(count - 1));
        boolean everyValue = slot >= 30 * d;
        for (int i = 0; i < count; i++) {
            float center = slot * i + slot / 2;
            double km = week[i];
            float h = (float) (km / max) * (barsBottom - barsTop);
            boolean now = i == current;
            bar.setColor(now ? accent : (ink & 0x00ffffff) | 0x40000000);
            rect.set(center - barWidth / 2, barsBottom - Math.max(h, 3 * d), center + barWidth / 2, barsBottom);
            canvas.drawRoundRect(rect, 4 * d, 4 * d, bar);
            if (km >= 0.05 && (everyValue || km >= max)) {
                String text = format(km);
                label.setColor(now ? accent : ink);
                canvas.drawText(text, centered(center, label.measureText(text), width), rect.top - 6 * d, label);
            }
            if (charged[i]) {
                // A charge in that hour or day: a small accent dot at the foot of the bar.
                bar.setColor(CHARGE);
                canvas.drawCircle(center + barWidth / 2 + 6 * d, barsBottom - 3 * d, 3 * d, bar);
            }
            String name = null;
            if (barNames != null) name = i < barNames.length && !barNames[i].isEmpty() ? barNames[i] : null;
            else if (period == 0) name = now ? "NOW" : i % 3 == 0 ? String.format(Locale.US, "%02d", i) : null;
            else if (period == 1) name = now ? "TODAY" : day.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()).toUpperCase(Locale.ROOT);
            else if (now) name = "TODAY";
            else if ((count - 1 - i) % 5 == 0) name = String.valueOf(day.get(Calendar.DAY_OF_MONTH));
            float nameBaseline = barsBottom + labelSize + 8 * d;
            if (name != null) {
                label.setColor(now ? ink : muted);
                canvas.drawText(name, centered(center, label.measureText(name), width), nameBaseline, label);
            }
            if (energyRow && Double.isFinite(weekKwh[i]) && weekKwh[i] >= 0.01) {
                String energy = String.format(Locale.US, "%.1f kWh", weekKwh[i]);
                label.setColor(muted);
                canvas.drawText(energy, centered(center, label.measureText(energy), width), nameBaseline + labelSize + 6 * d, label);
            }
            label.setColor(muted);
            if (period != 0) day.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private void drawChip(Canvas canvas, RectF chip, String text, boolean on, int color, float chipHeight, float labelSize) {
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(on ? (color & 0x00ffffff) | 0x40000000 : 0x00000000);
        canvas.drawRoundRect(chip, chipHeight / 2, chipHeight / 2, bar);
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeWidth(Math.max(1f, d));
        bar.setColor(on ? color : (muted & 0x00ffffff) | 0x55000000);
        canvas.drawRoundRect(chip, chipHeight / 2, chipHeight / 2, bar);
        bar.setStyle(Paint.Style.FILL);
        label.setColor(on ? ink : muted);
        canvas.drawText(text, chip.centerX() - label.measureText(text) / 2, chip.centerY() + labelSize * 0.35f, label);
    }

    private int chipAt(float x, float y) {
        if (!chipsShown) return -1;
        float slop = 10 * d;
        for (int i = 0; i < 6; i++) {
            RectF c = i < 4 ? chips[i] : actionChips[i - 4];
            if (x >= c.left - slop / 2 && x <= c.right + slop / 2 && y >= c.top - slop && y <= c.bottom + slop) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null && actionListener == null) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedChip = chipAt(event.getX(), event.getY());
                return pressedChip >= 0;
            case MotionEvent.ACTION_UP:
                int chip = pressedChip;
                pressedChip = -1;
                if (chip >= 0 && chip == chipAt(event.getX(), event.getY())) {
                    performClick();
                    if (chip < 4) { if (listener != null) listener.onPeriod(chip); }
                    else if (actionListener != null) actionListener.onAction(chip - 4);
                    return true;
                }
                return chip >= 0;
            case MotionEvent.ACTION_CANCEL:
                boolean was = pressedChip >= 0;
                pressedChip = -1;
                return was;
            default:
                return pressedChip >= 0;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    /** Left edge for text centred on a bar, kept inside the view so the last bar's label is not cut off. */
    private static float centered(float center, float textWidth, int width) {
        return Math.max(0, Math.min(width - textWidth, center - textWidth / 2));
    }

    private static String format(double km) {
        return km >= 100 ? String.valueOf(Math.round(km)) : String.format(Locale.US, "%.1f", km);
    }

    private static String fit(String text, Paint paint, float room) {
        return TextUtils.ellipsize(text, new android.text.TextPaint(paint), room, TextUtils.TruncateAt.END).toString();
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
