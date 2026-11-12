package com.softiva.oabl;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Static "aurora" background: a deep base gradient with a few soft, accent-tinted glows.
 * It is resolution independent and costs one full-screen draw, with no live blur.
 */
final class AuroraDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final boolean light;
    private final boolean vividMode;
    private final int[] baseColors;
    private final int[] glows;
    private Shader base;
    private Shader[] glowShaders = new Shader[0];
    private Shader vignette;

    AuroraDrawable(LauncherTheme.Palette palette) {
        this(palette, false);
    }

    /** Stronger mode colour across exposed areas; the default/Eco treatment stays unchanged. */
    AuroraDrawable(LauncherTheme.Palette palette, boolean vividMode) {
        light = palette.light;
        this.vividMode = vividMode;
        float[] hsv = new float[3];
        Color.colorToHSV(palette.accent, hsv);
        float hue = hsv[0];
        if (vividMode) {
            // Tint the base too: most of the wallpaper is covered by the dashboard's cards.
            // Nearby hues keep blue recognisably blue and red recognisably red in every corner.
            baseColors = light
                    ? new int[]{tone(hue, .13f, .99f, 0xff), tone(hue + 8, .19f, .98f, 0xff), tone(hue - 8, .17f, .97f, 0xff)}
                    : new int[]{tone(hue, .74f, .18f, 0xff), tone(hue + 8, .65f, .12f, 0xff), tone(hue - 8, .73f, .08f, 0xff)};
            glows = light
                    ? new int[]{tone(hue, .27f, 1f, 0xb0), tone(hue + 8, .22f, 1f, 0x90), tone(hue - 8, .20f, 1f, 0x80)}
                    : new int[]{tone(hue, .78f, .46f, 0xc0), tone(hue + 8, .80f, .36f, 0xb0), tone(hue - 8, .72f, .30f, 0x90)};
            return;
        }
        baseColors = light ? new int[]{0xfff4f7f9, 0xffe8eef2, 0xffdfe6eb}
                : new int[]{0xff0b1016, 0xff080b10, 0xff05070a};
        glows = light
                ? new int[]{tone(hue, .30f, .97f, 0xb0), tone(hue + 45, .22f, .98f, 0x90), tone(hue - 35, .18f, 1f, 0x70)}
                : new int[]{tone(hue, .62f, .55f, 0x9c), tone(hue + 45, .55f, .42f, 0x80), tone(hue - 35, .50f, .50f, 0x3a)};
    }

    private static int tone(float hue, float saturation, float value, int alpha) {
        return Color.HSVToColor(alpha, new float[]{(hue + 360) % 360, saturation, value});
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        float w = Math.max(1, bounds.width());
        float h = Math.max(1, bounds.height());
        base = new LinearGradient(vividMode ? -.15f * w : 0, 0, vividMode ? .8f * w : 0, h, baseColors,
                null, Shader.TileMode.CLAMP);
        float[][] spots = vividMode
                ? new float[][]{{.02f, .72f, .95f}, {1.02f, .02f, .95f}, {.62f, .48f, .75f}}
                : new float[][]{{.10f, 1.05f, .78f}, {.94f, -.08f, .62f}, {.56f, .62f, .46f}};
        glowShaders = new Shader[spots.length];
        for (int i = 0; i < spots.length; i++) {
            glowShaders[i] = new RadialGradient(spots[i][0] * w, spots[i][1] * h, spots[i][2] * w,
                    new int[]{glows[i], glows[i] & 0x00ffffff | ((Color.alpha(glows[i]) * (vividMode ? 2 : 1) / 3) << 24), 0x00000000},
                    new float[]{0f, vividMode ? .52f : .45f, 1f}, Shader.TileMode.CLAMP);
        }
        vignette = new RadialGradient(w / 2f, h * .45f, Math.max(w, h) * (vividMode ? .9f : .75f),
                light ? new int[]{0x00ffffff, 0x00ffffff, vividMode ? 0x18ffffff : 0x30b9c6cf}
                        : new int[]{0x00000000, 0x00000000, vividMode ? 0x5c000000 : 0xa0000000},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas) {
        if (base == null) {
            onBoundsChange(getBounds());
        }
        Rect bounds = getBounds();
        paint.setShader(base);
        canvas.drawRect(bounds, paint);
        for (Shader glow : glowShaders) {
            paint.setShader(glow);
            canvas.drawRect(bounds, paint);
        }
        paint.setShader(vignette);
        canvas.drawRect(bounds, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
