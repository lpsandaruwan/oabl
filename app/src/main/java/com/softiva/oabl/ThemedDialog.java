package com.softiva.oabl;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** AlertDialog builder in the launcher's style: rounded card, palette surface, accent buttons. */
final class ThemedDialog extends AlertDialog.Builder {
    private final LauncherTheme.Palette palette;

    ThemedDialog(Context context, LauncherTheme.Palette palette) {
        super(context, palette.light ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert);
        this.palette = palette;
    }

    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        if (dialog.getWindow() != null) {
            float density = getContext().getResources().getDisplayMetrics().density;
            GradientDrawable card = new GradientDrawable();
            card.setColor(palette.light ? 0xfff7f9fa : 0xff161d25);
            card.setCornerRadius(26 * density);
            int inset = Math.round(16 * density);
            dialog.getWindow().setBackgroundDrawable(new InsetDrawable(card, inset));
        }
        return dialog;
    }

    @Override
    public AlertDialog show() {
        AlertDialog dialog = create();
        dialog.show();
        DrivingDismiss.track(dialog);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setTextColor(palette.accent);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setAllCaps(false);
            button.setTextSize(16);
        }
        match(dialog, palette);
        return dialog;
    }

    /**
     * Inputs and labels built with the app's own (dark) theme keep white text, which disappears on
     * the light dialog: give them this palette's ink, hint and accent. Call it after show() for a
     * dialog made with create().
     */
    static void match(AlertDialog dialog, LauncherTheme.Palette palette) {
        if (dialog.getWindow() != null) match(dialog.getWindow().getDecorView(), palette);
    }

    private static void match(View view, LauncherTheme.Palette palette) {
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(palette.ink);
            field.setHintTextColor(palette.muted);
            field.setHighlightColor((palette.accent & 0x00ffffff) | 0x55000000);
            field.setBackgroundTintList(ColorStateList.valueOf(palette.accent));
            if (Build.VERSION.SDK_INT >= 29) {
                Drawable cursor = field.getTextCursorDrawable();
                if (cursor != null) cursor.setTint(palette.accent);
            }
        } else if (view instanceof TextView && !(view instanceof Button) && palette.light) {
            TextView text = (TextView) view;
            if (light(text.getCurrentTextColor())) text.setTextColor(palette.ink);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) match(group.getChildAt(i), palette);
        }
    }

    /** Near-white text: the dark theme's default, unreadable on a light card. */
    private static boolean light(int color) {
        if (Color.alpha(color) < 0x80) return false;
        double luminance = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255;
        return luminance > 0.8;
    }
}
