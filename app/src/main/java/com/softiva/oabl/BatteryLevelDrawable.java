package com.softiva.oabl;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Battery silhouette with a proportional fill; the adjacent percentage is the precise reading. */
final class BatteryLevelDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double percent;
    private final int color;
    BatteryLevelDrawable(double percent,int color){this.percent=percent;this.color=color;}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/32f,bounds.height()/24f);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
        canvas.drawRoundRect(1.5f,5,27,19,2.5f,2.5f,paint);paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(29,9,31,15,1,1,paint);
        if(Double.isFinite(percent)&&percent>0)canvas.drawRoundRect(4,7.5f,4+(float)Math.min(1,percent/100)*20.5f,16.5f,1,1,paint);
        canvas.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public int getIntrinsicWidth(){return 32;}
    @Override public int getIntrinsicHeight(){return 24;}
}
