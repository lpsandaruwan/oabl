package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Simple drawn weather symbols (WMO codes), tinted with the theme. No bitmaps, no animation. */
final class WeatherIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path bite = new Path();
    private int code = 0;
    private boolean day = true;
    private int sun = 0xfff2b84b;
    private int ink = 0xffffffff;

    WeatherIconView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(int code, boolean day, int ink) {
        if (this.code == code && this.day == day && this.ink == ink) return;
        this.code = code;
        this.day = day;
        this.ink = ink;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float x0 = (getWidth() - s) / 2, y0 = (getHeight() - s) / 2;
        boolean clear = code <= 1;
        boolean partly = code == 2;
        boolean fog = code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95;
        if (clear || partly) {
            float cx = x0 + s * (partly ? .38f : .5f), cy = y0 + s * (partly ? .38f : .5f), r = s * (partly ? .2f : .24f);
            drawSky(c, cx, cy, r, s);
            if (!partly) return;
        }
        // Cloud body.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        paint.setAlpha(clear ? 0 : 0xee);
        float cy = y0 + s * (rain || snow || storm ? .46f : .56f);
        c.drawCircle(x0 + s * .38f, cy, s * .16f, paint);
        c.drawCircle(x0 + s * .56f, cy - s * .07f, s * .2f, paint);
        c.drawCircle(x0 + s * .72f, cy + s * .02f, s * .13f, paint);
        c.drawRoundRect(x0 + s * .22f, cy, x0 + s * .85f, cy + s * .15f, s * .07f, s * .07f, paint);
        paint.setAlpha(0xff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * .055f);
        if (rain) {
            paint.setColor(0xff6fb3ff);
            for (int i = 0; i < 3; i++) {
                float x = x0 + s * (.36f + i * .16f);
                c.drawLine(x, y0 + s * .7f, x - s * .05f, y0 + s * .84f, paint);
            }
        } else if (snow) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ink);
            for (int i = 0; i < 3; i++) c.drawCircle(x0 + s * (.36f + i * .16f), y0 + s * (.76f + (i % 2) * .06f), s * .04f, paint);
        } else if (storm) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff2c94c);
            path.reset();
            path.moveTo(x0 + s * .55f, y0 + s * .62f);
            path.lineTo(x0 + s * .42f, y0 + s * .8f);
            path.lineTo(x0 + s * .52f, y0 + s * .8f);
            path.lineTo(x0 + s * .46f, y0 + s * .96f);
            path.lineTo(x0 + s * .64f, y0 + s * .74f);
            path.lineTo(x0 + s * .54f, y0 + s * .74f);
            path.close();
            c.drawPath(path, paint);
        } else if (fog) {
            paint.setColor(ink);
            paint.setAlpha(0xb0);
            for (int i = 0; i < 2; i++) c.drawLine(x0 + s * .25f, y0 + s * (.82f + i * .1f), x0 + s * .8f, y0 + s * (.82f + i * .1f), paint);
            paint.setAlpha(0xff);
        }
    }

    private void drawSky(Canvas c, float cx, float cy, float r, float s) {
        paint.setStyle(Paint.Style.FILL);
        if (day) {
            paint.setColor(sun);
            c.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(s * .05f);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                c.drawLine(cx + (float) Math.cos(a) * r * 1.35f, cy + (float) Math.sin(a) * r * 1.35f,
                        cx + (float) Math.cos(a) * r * 1.7f, cy + (float) Math.sin(a) * r * 1.7f, paint);
            }
        } else {
            // Crescent moon: a disc with an offset bite.
            path.reset();
            path.addCircle(cx, cy, r * 1.15f, Path.Direction.CW);
            bite.reset();
            bite.addCircle(cx + r * .6f, cy - r * .45f, r * 1.0f, Path.Direction.CW);
            path.op(bite, Path.Op.DIFFERENCE);
            paint.setColor(0xffdfe6f0);
            c.drawPath(path, paint);
        }
    }
}
