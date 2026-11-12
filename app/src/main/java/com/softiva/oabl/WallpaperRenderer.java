package com.softiva.oabl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WallpaperRenderer {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private WallpaperRenderer() { }

    static void load(final Context context, final ImageView target,
                     final Uri uri, final boolean softBlur) {
        load(context,target,uri,softBlur,null);
    }
    static void load(final Context context, final ImageView target,
                     final Uri uri, final boolean softBlur, final Runnable unavailable) {
        final String request = uri.toString() + ":" + softBlur;
        target.setTag(request);
        final int targetWidth = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
        final int targetHeight = Math.max(1, context.getResources().getDisplayMetrics().heightPixels);
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decode(context, uri, targetWidth, targetHeight, softBlur);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (request.equals(target.getTag())) {
                            if(bitmap==null&&unavailable!=null){unavailable.run();return;}
                            boolean home = target.getId() == R.id.wallpaper;
                            if (!home) {
                                // Other surfaces (the screensaver) fit themselves once laid out,
                                // keeping the top of the photo.
                                if (!(target instanceof TopCropImageView)) target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                target.setImageBitmap(bitmap);
                                return;
                            }
                            target.setScaleType(ImageView.ScaleType.MATRIX);
                            target.setImageBitmap(bitmap);
                            if (bitmap != null && target.getId() == R.id.wallpaper && LauncherTheme.rememberWallpaper(context, bitmap)
                                    && context instanceof MainActivity) ((MainActivity) context).wallpaperColorChanged();
                            if (bitmap != null) {
                                int viewWidth = Math.max(1, target.getWidth());
                                int viewHeight = Math.max(1, target.getHeight());
                                float scale = Math.max(
                                        (float) viewWidth / bitmap.getWidth(),
                                        (float) viewHeight / bitmap.getHeight());
                                float horizontalOffset =
                                        (viewWidth - bitmap.getWidth() * scale) / 2f;
                                Matrix matrix = new Matrix();
                                matrix.setScale(scale, scale);
                                // Anchor custom wallpapers to the top so faces near the upper
                                // edge are not lost to center-crop on wide automotive displays.
                                matrix.postTranslate(horizontalOffset, 0f);
                                target.setImageMatrix(matrix);
                            }
                        } else if (bitmap != null) {
                            bitmap.recycle();
                        }
                    }
                });
            }
        });
    }

    private static Bitmap decode(Context context, Uri uri, int width, int height,
                                 boolean softBlur) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        } catch (IOException | RuntimeException error) {
            return null;
        }
        int desiredWidth = softBlur ? Math.max(160, width / 8) : width;
        int desiredHeight = softBlur ? Math.max(90, height / 8) : height;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= desiredWidth
                && bounds.outHeight / (sample * 2) >= desiredHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
            if (decoded == null || !softBlur) {
                return decoded;
            }
            // Blur once at thumbnail size; the upscale then reads as a smooth, frosted
            // image instead of visible blocks, and nothing is blurred per frame.
            int blurWidth = Math.max(120, width / 12);
            int blurHeight = Math.max(68, height / 12);
            Bitmap blurred = ImageBlur.blurred(decoded, blurWidth, blurHeight, 3);
            if (blurred != decoded) {
                decoded.recycle();
            }
            return blurred;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }
}
