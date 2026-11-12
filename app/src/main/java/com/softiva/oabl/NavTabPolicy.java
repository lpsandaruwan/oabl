package com.softiva.oabl;

import java.util.Set;

/**
 * When the Back and Home edge tab should be on screen, from the window that just came forward.
 * The tab is for every screen that is not an OABL screen, however it was reached: a launched app, Recents,
 * a notification, Settings or a system dialog. Keyboards and the system's own shade say nothing about
 * which app is in front, so they leave it as it is.
 */
final class NavTabPolicy {
    enum Action { SHOW, HIDE, IGNORE }

    private NavTabPolicy() { }

    /**
     * @param pkg     package of the window that changed
     * @param cls     its class name (an activity's, or a dialog's or popup's)
     * @param ours    this app's package
     * @param ignored packages that never count: the status bar and shade, input methods
     */
    static Action decide(String pkg, String cls, String ours, Set<String> ignored) {
        if (pkg == null || pkg.isEmpty()) return Action.IGNORE;
        if (ignored.contains(pkg) || "android".equals(pkg)) return Action.IGNORE;
        if (pkg.equals(ours)) {
            // An OABL screen is in front. Anything else of ours (a popup, the tab itself) changes nothing.
            return cls != null && cls.endsWith("Activity") ? Action.HIDE : Action.IGNORE;
        }
        return Action.SHOW;
    }
}
