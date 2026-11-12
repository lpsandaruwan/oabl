package com.softiva.oabl;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * A small fixed Home tab on the screen edge while another app is in front. It is a single
 * static window (no service, no dragging, no animation loop) and sits flush against the edge
 * so app content stays usable.
 */
final class HomeTab {
    private static View tab;
    private static boolean ownScreenVisible;

    static void ownScreenVisible(boolean visible) { ownScreenVisible = visible; }
    static boolean isShowing() { return tab != null; }

    private HomeTab() { }

    /** Shown when Home leaves the foreground, unless the navigation overlay already offers Home. */
    static void show(Context context) {
        Context app = context.getApplicationContext();
        String side = new LauncherPreferences(app).homeTabSide();
        if (ownScreenVisible || tab != null || "off".equals(side) || !Settings.canDrawOverlays(app)
                || DriveOverlayService.isActive() || SplitScreenService.isConnected(context)) return;
        float density = app.getResources().getDisplayMetrics().density;
        boolean left = "left".equals(side);
        ImageView view = new ImageView(app);
        view.setImageResource(R.drawable.ic_nav_home);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        int pad = Math.round(7 * density);
        view.setPadding(left ? pad - Math.round(2 * density) : pad, pad, left ? pad : pad - Math.round(2 * density), pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x8c11161c);
        float r = 16 * density;
        shape.setCornerRadii(left ? new float[]{0, 0, r, r, r, r, 0, 0} : new float[]{r, r, 0, 0, 0, 0, r, r});
        view.setBackground(shape);
        view.setContentDescription("Home");
        view.setOnClickListener(v -> goHome(app));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.round(34 * density), Math.round(52 * density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(view, params);
            tab = view;
        } catch (RuntimeException error) {
            tab = null;
        }
    }

    static void hide(Context context) {
        if (tab == null) return;
        try {
            ((WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE)).removeView(tab);
        } catch (RuntimeException ignored) {
            // Already detached.
        }
        tab = null;
    }

    private static void goHome(Context app) {
        hide(app);
        app.startActivity(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
}
