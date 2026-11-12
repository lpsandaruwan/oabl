package com.softiva.oabl;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * Fills the view like centre-crop but keeps the top edge of the image, so faces and heads near
 * the top of a photo are never cut off on wide car screens. Recomputed on every size change.
 */
final class TopCropImageView extends ImageView {
    private final Matrix matrix = new Matrix();

    TopCropImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        boolean changed = super.setFrame(left, top, right, bottom);
        fit();
        return changed;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        fit();
    }

    private void fit() {
        Drawable drawable = getDrawable();
        int width = getWidth(), height = getHeight();
        if (drawable == null || width == 0 || height == 0
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return;
        float scale = Math.max((float) width / drawable.getIntrinsicWidth(), (float) height / drawable.getIntrinsicHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - drawable.getIntrinsicWidth() * scale) / 2f, 0f);
        setImageMatrix(matrix);
    }
}
