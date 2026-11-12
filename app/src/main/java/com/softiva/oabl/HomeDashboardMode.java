package com.softiva.oabl;

/** The active route takes priority; a connected car otherwise keeps energy in front. */
enum HomeDashboardMode {
    NAVIGATION, NORMAL, MEDIA;

    static HomeDashboardMode choose(boolean navigating, boolean connected) {
        return navigating ? NAVIGATION : connected ? NORMAL : MEDIA;
    }
}
