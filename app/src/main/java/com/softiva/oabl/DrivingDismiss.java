package com.softiva.oabl;

import android.app.Dialog;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Sheets and dialogs that should not stay open once the car is moving (Driving mode):
 * settings, the app drawer, Controls and pop-up choices close so Home's large controls show.
 */
final class DrivingDismiss {
    private static final List<WeakReference<Dialog>> open = new ArrayList<>();

    private DrivingDismiss() { }

    static void track(Dialog dialog) {
        if (dialog == null) return;
        prune();
        open.add(new WeakReference<>(dialog));
    }

    static void dismissAll() {
        for (WeakReference<Dialog> reference : new ArrayList<>(open)) {
            Dialog dialog = reference.get();
            if (dialog != null && dialog.isShowing()) {
                try { dialog.dismiss(); } catch (RuntimeException alreadyGone) { }
            }
        }
        open.clear();
    }

    private static void prune() {
        for (int i = open.size() - 1; i >= 0; i--) {
            Dialog dialog = open.get(i).get();
            if (dialog == null || !dialog.isShowing()) open.remove(i);
        }
    }
}
