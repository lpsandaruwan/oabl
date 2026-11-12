package com.softiva.oabl;

import android.graphics.Insets;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** One immersive-window policy for legacy head units and Android 15/16 edge-to-edge. */
final class ImmersiveWindow {
    private ImmersiveWindow() { }

    static void apply(Window window, View root) {
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        if (root != null && Build.VERSION.SDK_INT >= 28) protectFromCutout(root);
    }

    /** System bars are deliberately transient; only a physical display cutout reserves content. */
    @android.annotation.TargetApi(28)
    private static void protectFromCutout(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int safeLeft = 0, safeTop = 0, safeRight = 0, safeBottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.displayCutout());
                safeLeft = safe.left; safeTop = safe.top; safeRight = safe.right; safeBottom = safe.bottom;
            } else {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    safeLeft = cutout.getSafeInsetLeft(); safeTop = cutout.getSafeInsetTop();
                    safeRight = cutout.getSafeInsetRight(); safeBottom = cutout.getSafeInsetBottom();
                }
            }
            view.setPadding(left + safeLeft, top + safeTop, right + safeRight, bottom + safeBottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
