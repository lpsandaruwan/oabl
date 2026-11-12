package com.softiva.oabl;

import java.util.Locale;

/**
 * The 12 V battery, from BCM_BATT_VOLT_ST (0x21402042). Its scale isn't documented: the only capture
 * read 277 while the car charged, when the DC-DC converter holds the 12 V system near 13.8–14.4 V,
 * which fits ×0.05 V (13.85 V). A converted value outside 9–16 V means the scale is wrong, so nothing
 * is shown or warned. Resting voltage is only meaningful with the car off and not charging.
 */
final class CarBatteryHealth {
    static final double SCALE = 0.05, MIN_PLAUSIBLE = 9, MAX_PLAUSIBLE = 16;
    /** A resting 12 V lead-acid battery below about 12.0 V is around half charged or less. */
    static final double LOW_RESTING_V = 12.0;

    static final class Status implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        final double volts; final boolean resting, low;
        Status(double volts, boolean resting, boolean low) { this.volts = volts; this.resting = resting; this.low = low; }
        String text() {
            return String.format(Locale.US, "12 V battery %s · ≈ %.1f V%s · scale unconfirmed", low ? "low" : "OK", volts, resting ? " at rest" : " (car on)");
        }
    }

    static double volts(double raw) {
        if (!Double.isFinite(raw) || raw <= 0) return Double.NaN;
        double v = raw * SCALE;
        return v >= MIN_PLAUSIBLE && v <= MAX_PLAUSIBLE ? v : Double.NaN;
    }

    /** Null when there's no plausible reading. A low warning needs the car off and not charging. */
    static Status status(double raw, double ready, double charging) {
        double v = volts(raw);
        if (Double.isNaN(v)) return null;
        boolean resting = ready == 0 && charging == 0;
        return new Status(v, resting, resting && v < LOW_RESTING_V);
    }
}
