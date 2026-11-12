package com.softiva.oabl;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Speed gauge. Vertical layouts draw the hero arc; a horizontal layout (the Home instrument strip)
 * draws a compact scale bar under the number with the limit sign on the right. The arc glides
 * between readings; the number shows the latest measurement immediately. The limit sign pulses
 * while the reading is over it. Without a reliable,
 * fresh fix it shows "—" rather than an invented 0 km/h.
 */
public final class SpeedGaugeView extends LinearLayout {
    private static final float START = 150f;
    private static final float SWEEP = 240f;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface MEDIUM_BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Matrix rotation = new Matrix();
    private final float density;
    private ValueAnimator glide;
    /** Readings closer together than this get the short slide. */
    static final long FAST_UPDATES_MS = 700, FAST_GLIDE_MS = 200;
    /** At or above this on both readings the arc steps instead of gliding. */
    static final int CRUISE_KPH = 5;
    private long lastReadingAt;
    private SweepGradient gradient;
    private int gradientKey;
    private TextView valueText;
    private int target;
    private float shown;
    private int limit;
    private String limitNote;

    /** Small caption under the limit sign, e.g. "RAIN"; null for none. */
    void limitNote(String note) {
        if (note == null ? limitNote == null : note.equals(limitNote)) return;
        limitNote = note;
        invalidate();
    }
    String limitNote() { return limitNote; }
    private int maximum = 140;
    private int accent = 0xff86d9c7;
    private int track = 0x24ffffff;
    private int muted = 0xffa5b5c5;
    private int normalInk = 0xfff5f7f8;
    private boolean light;
    private boolean animated = true;
    private boolean known = true;

    private boolean linear() { return getOrientation() == HORIZONTAL; }
    /** Kept free under the number: the bar, its limit tick and a little air. */
    private static final float BAR_CLEARANCE_DP = 11;

    /** No reliable, fresh GPS speed: show a dash, not zero. */
    void unknown(int limit, LauncherTheme.Palette palette) {
        long before = look();
        boolean wasKnown = known;
        this.limit = limit;
        // The scale still follows the configured maximum while the speed is unknown.
        maximum = Math.max(40, new LauncherPreferences(getContext()).vehicleMaximumSpeed());
        if (palette != null) { accent = palette.accent; light = palette.light; muted = palette.muted; normalInk=palette.ink; track = light ? 0x1a102a2d : 0x1fffffff; }
        if (glide != null) { glide.cancel(); glide = null; }
        known = false;
        shown = 0;
        if (valueText != null && !"—".contentEquals(valueText.getText())) { showValue("—"); fitValue(); }
        updateValueColor();
        if (wasKnown || look() != before) invalidate();
    }
    boolean known() { return known; }

    public SpeedGaugeView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;
        ink.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        valueText = findViewById(R.id.speed_value);
    }

    /** The car's speed with its decimal. Its whole value drives the bar, the limit and everything else. */
    void reading(double kph, int limit, LauncherTheme.Palette palette) {
        fine |= kph != Math.rint(kph);
        exact = SpeedReading.tenths(kph, fine);
        apply((int) Math.round(kph), limit, palette);
    }

    void reading(int speed, int limit, LauncherTheme.Palette palette) {
        exact = null;
        apply(speed, limit, palette);
    }

    /** A new limit for the speed already showing. */
    void limit(int limit, LauncherTheme.Palette palette) { apply(target, limit, palette); }

    /** Seen a fraction: this Link sends finer speed, so a value landing on a whole number still gets its ".0". */
    private boolean fine;
    private String exact;

    private void apply(int speed, int limit, LauncherTheme.Palette palette) {
        long before = look();
        boolean wasKnown = known;
        known = true;
        LauncherPreferences preferences = new LauncherPreferences(getContext());
        this.limit = limit;
        maximum = Math.max(40, preferences.vehicleMaximumSpeed());
        animated = preferences.visualEffects();
        if (palette != null) {
            accent = palette.accent;
            light = palette.light;
            muted = palette.muted;
            normalInk = palette.ink;
            track = light ? 0x1a102a2d : 0x1fffffff;
        }
        styleChanged = !wasKnown || look() != before;
        glideTo(Math.max(0, speed));
        updateValueColor();
    }

    /** Set by a reading whose limit, scale or colours differ from what is drawn. */
    private boolean styleChanged;

    /** Everything besides the speed that the drawing depends on. */
    private long look() {
        long h = limit;
        h = h * 31 + maximum; h = h * 31 + accent; h = h * 31 + track; h = h * 31 + muted; h = h * 31 + normalInk;
        return h * 31 + (light ? 1 : 0);
    }

    int speed() {
        return target;
    }

    private void glideTo(int speed) {
        if (speed == target && glide == null) {
            // The same reading again (Home re-sends it every tick): a redraw would only re-encode
            // an identical frame for the car screen.
            boolean redraw = shown != speed || styleChanged;
            shown = speed;
            setValueText(speed);
            if (redraw) invalidate();
            return;
        }
        target = speed;
        setValueText(speed);
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
        long now = android.os.SystemClock.elapsedRealtime(), since = now - lastReadingAt;
        lastReadingAt = now;
        // While cruising, draw each reading once instead of gliding. On the car screen every gauge
        // frame is H.264-encoded and sent over the same USB link as CarPlay audio, and a glide per
        // one-second reading kept that video busy almost all the time. Pulling away and stopping glide.
        boolean cruising = shown >= CRUISE_KPH && speed >= CRUISE_KPH;
        if (!animated || !isAttachedToWindow() || cruising) {
            shown = speed;
            setValueText(speed);
            invalidate();
            return;
        }
        // Smooth only the arc. The number is the received measurement, never a value
        // invented between readings or delayed until an animation finishes.
        glide = ValueAnimator.ofFloat(shown, speed);
        glide.setDuration(since < FAST_UPDATES_MS ? FAST_GLIDE_MS : Math.min(900L, 320L + (long) Math.abs(speed - shown) * 25L));
        glide.setInterpolator(new DecelerateInterpolator(1.6f));
        glide.addUpdateListener(animation -> {
            shown = (float) animation.getAnimatedValue();
            invalidate();
        });
        glide.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (glide == animation) glide = null;
            }
        });
        glide.start();
    }

    private void setValueText(int value) {
        if (valueText == null || !known) return;
        String text = exact != null ? exact : String.valueOf(value);
        if (!text.contentEquals(valueText.getText())) {
            showValue(withSmallDecimal(text));
            fitValue();
        }
    }

    /** The whole number stays the size to read at a glance; the decimal sits beside it, smaller. */
    private static CharSequence withSmallDecimal(String text) {
        int point = text.indexOf('.');
        if (point < 0) return text;
        android.text.SpannableString small = new android.text.SpannableString(text);
        small.setSpan(new android.text.style.RelativeSizeSpan(DECIMAL_SIZE), point, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return small;
    }
    private static final float DECIMAL_SIZE = 0.55f;

    private int valueWidth = -1;

    /**
     * A wrap-content number re-lays out the whole screen on every reading. Its digits are tabular, so a
     * fixed width that changes only with the digit count lets a new reading redraw just the number.
     */
    private void showValue(CharSequence text) {
        int width = (int) Math.ceil(android.text.Layout.getDesiredWidth(text, valueText.getPaint()))
                + valueText.getCompoundPaddingLeft() + valueText.getCompoundPaddingRight() + 2;
        if (width != valueWidth) { valueWidth = width; valueSize = valueText.getTextSize(); valueText.setWidth(width); }
        valueText.setText(text);
    }

    private float valueSize;
    /** The size Home asked for and the size last set to fit under it; a different size means Home set a new one. */
    private float askedSize = -1, fittedSize = -1;

    /**
     * Keeps the bar along the bottom clear of the number. The car's A/C row takes height from this card, leaving
     * less than a line of text, and a shorter text box pushes the digits down onto the bar. So the digits are
     * sized to the height above the bar, and their box, taller than that for its empty space, is centred on it.
     */
    private void fitBetweenCardTopAndBar(int mode, int height) {
        if (valueText == null || mode != MeasureSpec.EXACTLY || height <= 0) return;
        int clear = Math.round(BAR_CLEARANCE_DP * density);
        if (getPaddingBottom() != clear) setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), clear);
        if (valueText.getPaddingTop() != 0 || valueText.getPaddingBottom() != 0) valueText.setPadding(0, 0, 0, 0);
        float current = valueText.getTextSize();
        if (current != fittedSize) askedSize = current;
        android.graphics.Rect digit = new android.graphics.Rect();
        valueText.getPaint().getTextBounds("0", 0, 1, digit);
        float size = Math.min(askedSize, (height - getPaddingTop() - clear - 2 * density) / (digit.height() / current));
        if (size > 0 && Math.abs(size - current) >= 1) valueText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        fittedSize = valueText.getTextSize();
        Paint.FontMetrics font = valueText.getPaint().getFontMetrics();
        android.view.ViewGroup.LayoutParams box = valueText.getLayoutParams();
        box.height = (int) Math.ceil(font.descent - font.ascent);
        if (valueText.getLayoutParams() != box) valueText.setLayoutParams(box);
        View unit = findViewById(R.id.speed_unit);
        if (unit != null && unit.getParent() instanceof View) {
            android.view.ViewGroup.LayoutParams line = ((View) unit.getParent()).getLayoutParams();
            if (line instanceof MarginLayoutParams) ((MarginLayoutParams) line).bottomMargin = 0;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Home resizes the number for the window; the locked width follows the new size.
        if (valueText != null && valueWidth >= 0 && valueText.getTextSize() != valueSize) {
            valueWidth = -1;
            showValue(valueText.getText());
        }
        if (linear()) fitBetweenCardTopAndBar(MeasureSpec.getMode(heightSpec), MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    private void updateValueColor(){
        if(valueText!=null)valueText.setTextColor(known&&limit>0&&target>limit?warning():normalInk);
    }

    /** Shrinks the number visually so three-digit speeds stay inside the ring. */
    private void fitValue() {
        if (valueText == null || getWidth() == 0) return;
        float available = linear() ? getWidth() - badgeSpace() - 70 * density : arcRadius() * 1.22f;
        float width = android.text.Layout.getDesiredWidth(valueText.getText(), valueText.getPaint());
        float scale = width <= available ? 1f : available / width;
        valueText.setScaleX(scale);
        valueText.setScaleY(scale);
    }

    private float arcRadius() {
        float stroke = 11 * density;
        // The arc is open at the bottom, so it can be taller than half the height.
        return Math.min(getWidth() / 2f, getHeight() / 1.78f) - stroke * 1.4f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::fitValue);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
        super.onDetachedFromWindow();
    }

    private float badgeSpace() { return (limit > 0 ? 64 : 0) * density; }

    /** Strip layout: a scale bar along the bottom and the limit sign at the right. */
    private void drawLinear(Canvas canvas) {
        float bar = 4 * density, bottom = getHeight() - 3 * density;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight() - badgeSpace();
        arc.setShader(null);
        arc.setStyle(Paint.Style.FILL);
        arc.setColor(track);
        oval.set(left, bottom - bar, right, bottom);
        canvas.drawRoundRect(oval, bar / 2, bar / 2, arc);
        boolean over = known && limit > 0 && target > limit;
        if (known) {
            float fraction = Math.max(0f, Math.min(1f, shown / maximum));
            arc.setColor(over ? warning() : accent);
            oval.set(left, bottom - bar, left + (right - left) * fraction, bottom);
            if (oval.width() > 0) canvas.drawRoundRect(oval, bar / 2, bar / 2, arc);
        }
        if (limit > 0) {
            float x = left + (right - left) * Math.min(1f, limit / (float) maximum);
            arc.setColor(warning());
            canvas.drawRect(x - density, bottom - bar - 4 * density, x + density, bottom + density, arc);
            drawLimitBadge(canvas, getWidth() - getPaddingRight() - 28 * density, getHeight() / 2f - 6 * density, over);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (linear()) { drawLinear(canvas); return; }
        float stroke = 11 * density;
        float radius = arcRadius();
        if (radius <= 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f + radius * 0.10f;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        arc.setShader(null);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(stroke);
        arc.setColor(track);
        canvas.drawArc(oval, START, SWEEP, false, arc);

        drawTicks(canvas, cx, cy, radius - stroke - 3 * density, radius + stroke);

        float fraction = Math.max(0f, Math.min(1f, shown / maximum));
        float sweep = Math.max(0.5f, fraction * SWEEP);
        boolean over = limit > 0 && target > limit;
        arc.setShader(over ? null : gradient(cx, cy));
        arc.setColor(over ? warning() : 0xffffffff);
        if (!light) {
            // Cheap bloom: the same arc, wider and faint. No blur filter, so it stays on the GPU path.
            arc.setStrokeWidth(stroke * 2.2f);
            arc.setAlpha(0x2c);
            canvas.drawArc(oval, START, sweep, false, arc);
            arc.setAlpha(0xff);
        }
        arc.setStrokeWidth(stroke);
        canvas.drawArc(oval, START, sweep, false, arc);
        arc.setShader(null);

        // End cap: a small bright bead marks the needle position.
        double angle = Math.toRadians(START + sweep);
        float bx = cx + (float) Math.cos(angle) * radius;
        float by = cy + (float) Math.sin(angle) * radius;
        arc.setStyle(Paint.Style.FILL);
        arc.setColor(light ? 0xffffffff : 0xfff7f9fb);
        canvas.drawCircle(bx, by, stroke * 0.36f, arc);

        if (limit > 0) {
            drawLimitMarker(canvas, cx, cy, radius, stroke);
            drawLimitBadge(canvas, cx + radius + stroke + 44 * density, cy - radius * 0.45f, over);
        }
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius, float labelRadius) {
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(muted);
        int step = maximum > 160 ? 20 : 10;
        for (int value = 0; value <= maximum; value += step) {
            double a = Math.toRadians(START + SWEEP * value / maximum);
            boolean major = value % (step * 2) == 0;
            float outer = radius;
            float inner = radius - (major ? 6 : 3) * density;
            arc.setAlpha(major ? 0x99 : 0x55);
            arc.setStrokeWidth((major ? 1.6f : 1.2f) * density);
            canvas.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                    cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, arc);
        }
        arc.setAlpha(0xff);
        ink.setTypeface(MEDIUM);
        ink.setTextSize(10 * density);
        ink.setColor(muted);
        ink.setAlpha(0xb0);
        // Scale ends sit just outside the open bottom of the arc.
        double a0 = Math.toRadians(START);
        double a1 = Math.toRadians(START + SWEEP);
        float drop = 14 * density;
        canvas.drawText("0", cx + (float) Math.cos(a0) * labelRadius, cy + (float) Math.sin(a0) * labelRadius + drop, ink);
        canvas.drawText(String.valueOf(maximum), cx + (float) Math.cos(a1) * labelRadius, cy + (float) Math.sin(a1) * labelRadius + drop, ink);
        ink.setAlpha(0xff);
    }

    private void drawLimitMarker(Canvas canvas, float cx, float cy, float radius, float stroke) {
        double a = Math.toRadians(START + SWEEP * Math.min(1f, limit / (float) maximum));
        float inner = radius - stroke * 0.9f;
        float outer = radius + stroke * 0.9f;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(3 * density);
        arc.setColor(warning());
        canvas.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, arc);
    }

    private void drawLimitBadge(Canvas canvas, float x, float y, boolean over) {
        float r = 24 * density;
        float below = (linear() ? (limitNote != null ? 17 : 3) : 26) * density;   // room for the caption under the sign
        // A short gauge (the car's A/C and mode row sits under it) gets a smaller sign, so the sign and its
        // caption always fit inside the view instead of the caption being cut off.
        if (linear()) r = Math.max(14 * density, Math.min(r, (getHeight() - below - 2 * density) / 2f));
        x = Math.max(r + 8 * density, Math.min(getWidth() - r - 4 * density, x));
        y = Math.max(r + 2 * density, Math.min(getHeight() - r - below, y));
        arc.setStyle(Paint.Style.FILL);
        arc.setColor(0xffffffff);
        canvas.drawCircle(x, y, r, arc);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(r * 0.2f);
        arc.setColor(0xffe0362c);
        canvas.drawCircle(x, y, r - r * 0.1f, arc);
        ink.setTypeface(MEDIUM_BOLD);
        ink.setTextSize(r * (limit >= 100 ? 0.62f : 0.78f));
        ink.setColor(0xff15181c);
        canvas.drawText(String.valueOf(limit), x, y - (ink.descent() + ink.ascent()) / 2f, ink);
        if (limitNote != null) {
            ink.setTypeface(MEDIUM);
            ink.setTextSize(10 * density);
            ink.setColor(muted);
            canvas.drawText(limitNote, x, y + r + 12 * density, ink);
        }
    }

    private SweepGradient gradient(float cx, float cy) {
        int key = (int) (cx * 31 + cy) * 31 + accent + (light ? 1 : 0);
        if (gradient == null || key != gradientKey) {
            int amber = light ? 0xffe08a00 : 0xffffb547;
            // Colour stops follow the arc: calm accent for most of the range, warming near the top.
            float end = SWEEP / 360f;
            gradient = new SweepGradient(cx, cy,
                    new int[]{accent, accent, amber, warning(), warning()},
                    new float[]{0f, end * 0.55f, end * 0.80f, end, 1f});
            rotation.setRotate(START - 4f, cx, cy);
            gradient.setLocalMatrix(rotation);
            gradientKey = key;
        }
        return gradient;
    }

    private int warning() {
        return light ? 0xffd93a2e : 0xffff6a5c;
    }

    float sweepForSpeed(int reading) { return Math.max(3, Math.min(240, reading / (float)maximum * 240)); }
}
