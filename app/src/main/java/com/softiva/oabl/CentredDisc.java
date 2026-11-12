package com.softiva.oabl;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** A round button face that stays a circle however wide its view is stretched. */
final class CentredDisc extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    CentredDisc(int color) { paint.setColor(color); }

    @Override public void draw(Canvas canvas) {
        float width = getBounds().width(), height = getBounds().height();
        canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), Math.min(width, height) / 2f, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
