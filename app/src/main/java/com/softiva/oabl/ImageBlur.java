package com.softiva.oabl;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Small, dependency-free blur and colour helpers. Inputs are expected to be thumbnails. */
final class ImageBlur {
    private ImageBlur() { }

    /** Returns a mutable ARGB copy of {@code source} scaled to {@code width}×{@code height}, box-blurred three times. */
    static Bitmap blurred(Bitmap source, int width, int height, int radius) {
        Bitmap small = Bitmap.createScaledBitmap(source, Math.max(1, width), Math.max(1, height), true);
        Bitmap output = small.copy(Bitmap.Config.ARGB_8888, true);
        if (small != source && small != output) {
            small.recycle();
        }
        int w = output.getWidth();
        int h = output.getHeight();
        int[] pixels = new int[w * h];
        int[] scratch = new int[w * h];
        output.getPixels(pixels, 0, w, 0, 0, w, h);
        // Three box passes approximate a gaussian closely enough for backgrounds.
        for (int pass = 0; pass < 3; pass++) {
            boxHorizontal(pixels, scratch, w, h, radius);
            boxVertical(scratch, pixels, w, h, radius);
        }
        output.setPixels(pixels, 0, w, 0, 0, w, h);
        return output;
    }

    private static void boxHorizontal(int[] in, int[] out, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, w)];
                a += p >>> 24; red += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / span) << 24) | ((red / span) << 16) | ((g / span) << 8) | (b / span);
                int add = in[row + clamp(x + r + 1, w)];
                int remove = in[row + clamp(x - r, w)];
                a += (add >>> 24) - (remove >>> 24);
                red += ((add >> 16) & 0xff) - ((remove >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((remove >> 8) & 0xff);
                b += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static void boxVertical(int[] in, int[] out, int w, int h, int r) {
        int span = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, h) * w + x];
                a += p >>> 24; red += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / span) << 24) | ((red / span) << 16) | ((g / span) << 8) | (b / span);
                int add = in[clamp(y + r + 1, h) * w + x];
                int remove = in[clamp(y - r, h) * w + x];
                a += (add >>> 24) - (remove >>> 24);
                red += ((add >> 16) & 0xff) - ((remove >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((remove >> 8) & 0xff);
                b += (add & 0xff) - (remove & 0xff);
            }
        }
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : (value >= size ? size - 1 : value);
    }

    /**
     * Picks a lively but not garish colour from artwork: the average of the most saturated
     * mid-tone pixels, falling back to the overall average for greyscale art.
     */
    static int vibrant(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, 24, 24, true);
        int[] pixels = new int[24 * 24];
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24);
        if (small != source) {
            small.recycle();
        }
        float[] hsv = new float[3];
        long sr = 0, sg = 0, sb = 0, count = 0;
        long ar = 0, ag = 0, ab = 0;
        for (int p : pixels) {
            ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p);
            Color.colorToHSV(p, hsv);
            if (hsv[1] > 0.35f && hsv[2] > 0.25f && hsv[2] < 0.95f) {
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p); count++;
            }
        }
        if (count < pixels.length / 12) {
            int n = pixels.length;
            return Color.rgb((int) (ar / n), (int) (ag / n), (int) (ab / n));
        }
        return Color.rgb((int) (sr / count), (int) (sg / count), (int) (sb / count));
    }

    static int mix(int base, int color, float amount) {
        return Color.rgb(Math.round(Color.red(base) * (1 - amount) + Color.red(color) * amount),
                Math.round(Color.green(base) * (1 - amount) + Color.green(color) * amount),
                Math.round(Color.blue(base) * (1 - amount) + Color.blue(color) * amount));
    }
}
