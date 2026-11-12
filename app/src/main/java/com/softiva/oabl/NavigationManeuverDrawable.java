package com.softiva.oabl;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small code-native maneuver glyphs, independent of any navigation provider's icon format. */
final class NavigationManeuverDrawable extends Drawable {
    private final NavigationGuidance.Maneuver maneuver;
    private final int exit;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    NavigationManeuverDrawable(NavigationGuidance.Maneuver maneuver, int exit, int color) {
        this.maneuver = maneuver;
        this.exit = exit;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        float scale = Math.min(bounds.width(), bounds.height()) / 100f;
        float left = bounds.left + (bounds.width() - 100f * scale) / 2f;
        float top = bounds.top + (bounds.height() - 100f * scale) / 2f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        paint.setStrokeWidth(9f);
        boolean mirror = maneuver == NavigationGuidance.Maneuver.LEFT
                || maneuver == NavigationGuidance.Maneuver.SLIGHT_LEFT
                || maneuver == NavigationGuidance.Maneuver.SHARP_LEFT
                || maneuver == NavigationGuidance.Maneuver.FORK_LEFT;
        if (mirror) canvas.scale(-1f, 1f, 50f, 50f);
        switch (maneuver) {
            case STRAIGHT: straight(canvas); break;
            case SLIGHT_LEFT: case SLIGHT_RIGHT: slight(canvas); break;
            case LEFT: case RIGHT: case SHARP_LEFT: case SHARP_RIGHT: turn(canvas); break;
            case UTURN_LEFT: uturn(canvas, true); break;
            case UTURN_RIGHT: uturn(canvas, false); break;
            case ROUNDABOUT: roundabout(canvas); break;
            case FORK_LEFT: case FORK_RIGHT: fork(canvas); break;
            case MERGE: merge(canvas); break;
            case DESTINATION: flag(canvas); break;
            default: straight(canvas); break;
        }
        canvas.restore();
    }

    private void straight(Canvas c) {
        c.drawLine(50, 82, 50, 24, paint);
        arrow(c, 50, 18, 36, 35, 64, 35);
    }
    private void turn(Canvas c) {
        Path path = new Path(); path.moveTo(25, 82); path.lineTo(25, 52); path.quadTo(25, 34, 43, 34); path.lineTo(73, 34); c.drawPath(path, paint);
        arrow(c, 79, 34, 62, 20, 62, 48);
    }
    private void slight(Canvas c) {
        Path path = new Path(); path.moveTo(34, 82); path.lineTo(34, 61); path.quadTo(34, 48, 46, 42); path.lineTo(70, 28); c.drawPath(path, paint);
        arrow(c, 76, 24, 55, 24, 69, 43);
    }
    private void uturn(Canvas c, boolean left) {
        if (left) c.scale(-1f, 1f, 50f, 50f);
        Path path = new Path(); path.moveTo(70, 82); path.lineTo(70, 47); path.quadTo(70, 24, 50, 24); path.quadTo(30, 24, 30, 47); path.lineTo(30, 61); c.drawPath(path, paint);
        arrow(c, 30, 70, 17, 51, 43, 51);
    }
    private void roundabout(Canvas c) {
        RectF ring = new RectF(25, 24, 75, 74); c.drawArc(ring, -42, 300, false, paint);
        arrow(c, 73, 21, 55, 19, 65, 37);
        if (exit > 0) {
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(25); paint.setFakeBoldText(true);
            c.drawText(Integer.toString(exit), 50, 58, paint);
            paint.setFakeBoldText(false); paint.setStyle(Paint.Style.STROKE);
        }
    }
    private void fork(Canvas c) {
        c.drawLine(38, 82, 38, 48, paint); c.drawLine(38, 48, 70, 25, paint); c.drawLine(38, 48, 25, 35, paint);
        arrow(c, 76, 21, 56, 22, 69, 39);
    }
    private void merge(Canvas c) {
        c.drawLine(58, 82, 58, 30, paint); c.drawLine(24, 30, 58, 58, paint); arrow(c, 58, 20, 44, 37, 72, 37);
    }
    private void flag(Canvas c) {
        c.drawLine(30, 82, 30, 18, paint); Path flag = new Path(); flag.moveTo(32, 22); flag.lineTo(74, 32); flag.lineTo(32, 45); flag.close();
        paint.setStyle(Paint.Style.FILL); c.drawPath(flag, paint); paint.setStyle(Paint.Style.STROKE);
    }
    private void arrow(Canvas c, float x, float y, float ax, float ay, float bx, float by) {
        Path arrow = new Path(); arrow.moveTo(x, y); arrow.lineTo(ax, ay); arrow.moveTo(x, y); arrow.lineTo(bx, by); c.drawPath(arrow, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
