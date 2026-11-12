package com.softiva.oabl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Geometry and pacing for road lookups; no Android, so it is tested on the JVM. */
final class RoadLookup {
    private RoadLookup() { }

    /** A lookup is never started sooner than this after the last one. */
    static final long MIN_GAP_MS = 3_000;
    /** A limit is shown for this long after its road was last confirmed, however close the car stays. */
    static final long LIMIT_FRESH_MS = 60_000;
    /** ...and only this close to where it was confirmed: past that, the car may be on another road. */
    static final double LIMIT_HOLD_METERS = 150;
    /** A cached road this far off the car's heading is a different road crossing the same square. */
    static final double SAME_ROAD_DEGREES = 50;

    /** Whether a limit confirmed {@code ageMs} ago, {@code meters} from here, can still be shown. */
    static boolean limitStillHolds(long ageMs, double meters) {
        return ageMs < LIMIT_FRESH_MS && meters <= LIMIT_HOLD_METERS;
    }

    /** Whether a road running along {@code direction} (0-179 degrees) suits a car heading {@code bearing}. */
    static boolean headingFits(int direction, double bearing) {
        double difference = Math.abs(((bearing - direction) % 180 + 180) % 180);
        return Math.min(difference, 180 - difference) <= SAME_ROAD_DEGREES;
    }

    /** The direction saved with a cache entry, 0-179 degrees, or -1 for an older entry that has none. */
    static int savedDirection(String saved) {
        String[] parts = saved.split("\\|", -1);
        if (parts.length < 5) return -1;
        try { return Integer.parseInt(parts[4]); } catch (NumberFormatException none) { return -1; }
    }

    /** The point {@code meters} ahead along {@code bearingDegrees}: {lat, lon}. */
    static double[] projectAhead(double lat, double lon, double bearingDegrees, double meters) {
        double bearing = Math.toRadians(bearingDegrees);
        double north = Math.cos(bearing) * meters, east = Math.sin(bearing) * meters;
        return new double[]{lat + north / 110_540.0, lon + east / (111_320.0 * Math.cos(Math.toRadians(lat)))};
    }

    /** How far ahead to look: about six seconds of travel, 60 to 250 m, so the name is ready on arrival. */
    static double lookAheadMeters(double speedMetersPerSecond) {
        return Math.max(60, Math.min(250, speedMetersPerSecond * 6));
    }

    /** Wait before trying again after {@code failures} failed lookups in a row: 3 s, 6 s, 12 s … up to a minute. */
    static long retryDelayMs(int failures) {
        if (failures <= 0) return 0;
        return Math.min(60_000L, 3_000L << Math.min(failures - 1, 5));
    }

    /**
     * The ~55 m squares a road runs through within {@code radiusMeters} of a point, from its shape as
     * {lat, lon} pairs. One answer then covers the road for several hundred metres.
     */
    static Set<String> cellsAlong(double[][] shape, double centerLat, double centerLon, double radiusMeters) {
        return directionsAlong(shape, centerLat, centerLon, radiusMeters).keySet();
    }

    /** {@link #cellsAlong}, with the way the road runs through each square, 0-179 degrees. */
    static Map<String, Integer> directionsAlong(double[][] shape, double centerLat, double centerLon, double radiusMeters) {
        Map<String, Integer> cells = new LinkedHashMap<>();
        if (shape == null) return cells;
        double cos = Math.cos(Math.toRadians(centerLat));
        for (int i = 1; i < shape.length; i++) {
            double[] a = shape[i - 1], b = shape[i];
            double length = GpsMotion.distance(a[0], a[1], b[0], b[1]);
            int steps = (int) Math.max(1, Math.ceil(length / 20.0));
            int direction = direction(a, b, cos);
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                double lat = a[0] + (b[0] - a[0]) * t, lon = a[1] + (b[1] - a[1]) * t;
                double dx = (lon - centerLon) * cos * 111_320.0, dy = (lat - centerLat) * 110_540.0;
                if (Math.sqrt(dx * dx + dy * dy) <= radiusMeters) cells.putIfAbsent(cell(lat, lon), direction);
            }
        }
        return cells;
    }

    private static int direction(double[] a, double[] b, double cos) {
        double east = (b[1] - a[1]) * cos * 111_320.0, north = (b[0] - a[0]) * 110_540.0;
        return (int) Math.round((Math.toDegrees(Math.atan2(east, north)) % 180 + 180) % 180) % 180;
    }

    /** The ~55 m square a position falls in; the key of the on-device road cache. */
    static String cell(double lat, double lon) {
        return Math.round(lat * 2000) + ":" + Math.round(lon * 2000);
    }
}
