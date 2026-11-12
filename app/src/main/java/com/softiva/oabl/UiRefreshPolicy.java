package com.softiva.oabl;

/** Refresh cadences for an Android 13 AI box whose screen is video-encoded for the car display. */
final class UiRefreshPolicy {
    /**
     * The drive card's fallback tick. Speed does not wait for it: each GPS or car reading redraws the
     * gauge as it arrives. Every tick re-encodes changed card values for the car screen, so keep it slow.
     */
    static final long HOME_MS = 1_500;
    static final long MEDIA_SAFETY_MS = 10_000;
    static final long STATUS_MS = 10_000;
    static final long WEATHER_MS = 60_000;
    /** The overlay's fallback speed tick; readings themselves redraw it as they arrive. */
    static final long OVERLAY_SPEED_MS = 1_500;
    /** How often the overlay rechecks its foreground-service type; it costs a call into the energy process. */
    static final long FOREGROUND_RECHECK_MS = 10_000;

    private UiRefreshPolicy() { }

    static long homeDelay(boolean driving, boolean navigating) {
        return HOME_MS;
    }

    static long overlayDelay(boolean thermallyThrottled) {
        return OVERLAY_SPEED_MS;
    }
}
