package com.softiva.oabl;

/**
 * The car's time-to-full. The raw value read 354 at 61 % charging at 2.6 kW towards 95 %, which fits
 * minutes; it's labelled as the car's estimate until a real charge confirms the unit.
 */
final class CarChargeTime {
    /** "1 h 20 min", "45 min", or empty when the car gives no usable value. */
    static String remaining(double raw) {
        if (!Double.isFinite(raw) || raw <= 0 || raw > 72 * 60) return "";
        long minutes = Math.round(raw);
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }
}
