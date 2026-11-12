package com.softiva.oabl;

/**
 * Keeps the last road name on screen until a new one arrives, instead of flashing "Finding street name…".
 * It is dimmed once the car has moved on, and dropped after 90 s or a kilometre.
 */
final class RoadLabelHold {
    static final long MAX_AGE_MS = 90_000;
    static final double MAX_METERS = 1_000, STALE_METERS = 150;
    private String label;
    private double lat, lon;
    private long at;

    void remember(String label, double lat, double lon, long now) {
        this.label = label; this.lat = lat; this.lon = lon; this.at = now;
    }

    /** The held name, or null when it is too old or too far to be the road now. */
    String current(double nowLat, double nowLon, long now) {
        if (label == null || now - at > MAX_AGE_MS) return null;
        return GpsMotion.distance(lat, lon, nowLat, nowLon) > MAX_METERS ? null : label;
    }

    /** True when the held name was learned some way back and should be drawn quieter. */
    boolean stale(double nowLat, double nowLon) {
        return label != null && GpsMotion.distance(lat, lon, nowLat, nowLon) > STALE_METERS;
    }
}
