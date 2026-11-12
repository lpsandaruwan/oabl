package com.softiva.oabl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** One consistent icon family; no emoji glyphs, image assets or animation loop. */
final class MusicIconView extends View {
    enum Icon { BACK, DOWN, MORE, PLAY, PAUSE, PREVIOUS, NEXT, SHUFFLE, REPEAT, STAR, FOLDER, QUEUE, SEARCH, ADD, MINUS, RESET }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Icon icon;
    private int ink;
    private int accent;
    private boolean selected;
    private String badge;
    MusicIconView(Context context, Icon icon, int ink, int accent, String label, Runnable action) {
        super(context); this.icon=icon; this.ink=ink; this.accent=accent;
        setContentDescription(label); setFocusable(true); setClickable(true);
        setOnClickListener(v->action.run());
    }
    /** Recolours the glyph, e.g. white over album-art backdrops. */
    void colors(int ink, int accent) { if(this.ink!=ink||this.accent!=accent){this.ink=ink;this.accent=accent;invalidate();} }
    void icon(Icon value) { if(icon!=value){icon=value;invalidate();} }
    void chosen(boolean value) { if(selected!=value){selected=value;setSelected(value);invalidate();} }
    boolean chosen() { return selected; }
    void badge(String value) { if(!java.util.Objects.equals(badge,value)){badge=value;invalidate();} }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Button");
        if(icon==Icon.STAR || icon==Icon.REPEAT){info.setCheckable(true);info.setChecked(selected);}
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float size=Math.min(getWidth(),getHeight())*.49f;
        c.save(); c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/24,size/24);
        paint.setColor(selected?accent:ink);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
        switch(icon){
            case BACK: line(c,14.5f,5,7.5f,12,14.5f,19); break;
            case DOWN: line(c,5,9,12,16,19,9);break;
            case MORE: paint.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)c.drawCircle(x,12,1.6f,paint);break;
            case PLAY: paint.setStyle(Paint.Style.FILL);triangle(c,7,4,20,12,7,20);break;
            case PAUSE: paint.setStyle(Paint.Style.FILL);c.drawRoundRect(6,4,10,20,1,1,paint);c.drawRoundRect(14,4,18,20,1,1,paint);break;
            case PREVIOUS: paint.setStyle(Paint.Style.FILL);c.drawRect(3,5,5,19,paint);triangle(c,13,5,5,12,13,19);triangle(c,22,5,14,12,22,19);break;
            case NEXT: paint.setStyle(Paint.Style.FILL);c.drawRect(19,5,21,19,paint);triangle(c,2,5,10,12,2,19);triangle(c,11,5,19,12,11,19);break;
            case STAR:
                for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=i%2==0?9:4.2f;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();
                if(selected)paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);break;
            case SHUFFLE:
                path.moveTo(3,6);path.lineTo(6,6);path.cubicTo(11,6,13,18,18,18);path.lineTo(21,18);c.drawPath(path,paint);
                path.reset();path.moveTo(3,18);path.lineTo(6,18);path.cubicTo(9,18,10,13,12,10);path.moveTo(15,7);path.lineTo(18,6);path.lineTo(21,6);c.drawPath(path,paint);
                line(c,18,3,21,6,18,9);line(c,18,15,21,18,18,21);break;
            case REPEAT:
                path.moveTo(4,10);path.lineTo(4,7);path.quadTo(4,5,7,5);path.lineTo(20,5);path.moveTo(20,14);path.lineTo(20,17);path.quadTo(20,19,17,19);path.lineTo(4,19);c.drawPath(path,paint);
                line(c,17,2,20,5,17,8);line(c,7,16,4,19,7,22);break;
            case FOLDER:
                path.moveTo(3,7);path.lineTo(3,19);path.lineTo(21,19);path.lineTo(21,7);path.lineTo(12,7);path.lineTo(10,4);path.lineTo(3,4);path.close();c.drawPath(path,paint);break;
            case QUEUE:
                for(int y=5;y<=15;y+=5){c.drawCircle(3,y,.7f,paint);c.drawLine(7,y,20,y,paint);}line(c,15,18,18,21,21,18);break;
            case SEARCH:c.drawCircle(10,10,6.5f,paint);c.drawLine(15,15,21,21,paint);break;
            case ADD:c.drawLine(12,4,12,20,paint);c.drawLine(4,12,20,12,paint);break;
            case MINUS:c.drawLine(4,12,20,12,paint);break;
            case RESET:c.drawArc(4,4,20,20,-65,300,false,paint);line(c,4,3,4,10,11,10);break;
        }
        if(badge!=null){paint.setStyle(Paint.Style.FILL);paint.setTextSize(9);paint.setTextAlign(Paint.Align.CENTER);c.drawText(badge,12,15,paint);}
        c.restore();
    }
    private void line(Canvas c,float x1,float y1,float x2,float y2,float x3,float y3){path.reset();path.moveTo(x1,y1);path.lineTo(x2,y2);path.lineTo(x3,y3);c.drawPath(path,paint);}
    private void triangle(Canvas c,float x1,float y1,float x2,float y2,float x3,float y3){path.reset();path.moveTo(x1,y1);path.lineTo(x2,y2);path.lineTo(x3,y3);path.close();c.drawPath(path,paint);}
}
