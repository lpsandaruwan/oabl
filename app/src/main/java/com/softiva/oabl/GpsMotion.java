package com.softiva.oabl;

/**
 * Classifies each second of an active journey as moving, standstill or unknown, with hysteresis so
 * GPS jitter at a traffic light isn't counted as dozens of stops (energy-model.md §3):
 * <ul>
 *   <li>standstill: valid fixes under 3 km/h for 20 s, counted from when the car slowed;</li>
 *   <li>leaving standstill: 6 km/h or more for 5 s, or a move of 25 m that exceeds the fix's
 *       accuracy;</li>
 *   <li>unknown: no valid fix (accuracy 30 m or better) for more than 5 s. Unknown time is never
 *       standstill and never accrues idle energy.</li>
 * </ul>
 * Distance comes from {@link TripComputer}'s filtered odometer, never re-measured here; a gap
 * that TripComputer didn't accept is recorded as poor-GPS distance. No Android dependencies.
 */
final class GpsMotion {
    enum State { UNKNOWN, MOVING, STANDSTILL }

    interface Listener {
        /** The car has been standing since {@code since} (wall-clock ms). */
        void standstillStarted(long since);
        /** Standstill ended at {@code at}: the car moved, or GPS was lost. */
        void standstillEnded(long at);
    }

    static final float MAX_ACCURACY_M = 30;
    static final long FIX_TIMEOUT_MS = 5_000;
    static final float SLOW_KPH = 3, FAST_KPH = 6;
    static final long STANDSTILL_AFTER_MS = 20_000, MOVING_AFTER_MS = 5_000;
    static final double LEAVE_METERS = 25;
    /** A recovered fix this much farther than TripComputer accepted is a poor-GPS gap. */
    static final double POOR_GAP_SLACK_M = 50;

    private final Listener listener;
    private State state = State.UNKNOWN, beforeUnknown = State.UNKNOWN;
    private boolean hasFix;
    private double lat, lon, anchorLat, anchorLon;
    private long lastFixElapsed, lastFixWall, lastTick = -1;
    private long slowSince = -1, slowSinceWall, fastSince = -1, fastSinceWall;

    // Counters (seconds and metres), accumulated only while this tracker is fed.
    double meters, movingSeconds, standstillSeconds, unknownSeconds, activeSeconds, poorMeters;

    GpsMotion(Listener listener) { this.listener = listener; }

    State state() { return state; }

    /** Restores counters after a restart (from the last heartbeat). The state starts unknown. */
    void restore(double meters, double moving, double standstill, double unknown, double active, double poor) {
        this.meters = meters; movingSeconds = moving; standstillSeconds = standstill;
        unknownSeconds = unknown; activeSeconds = active; poorMeters = poor;
    }

    /**
     * One GPS fix. {@code speedKph} is NaN when the fix has no speed; {@code acceptedMeters} is
     * how far TripComputer's odometer advanced for this fix.
     */
    void fix(long elapsed, long wall, float accuracy, float speedKph, double latitude, double longitude,
             double acceptedMeters) {
        if (acceptedMeters > 0) meters += acceptedMeters;
        if (Float.isNaN(accuracy) || accuracy > MAX_ACCURACY_M) return;
        double moved = hasFix ? distance(lat, lon, latitude, longitude) : 0;
        float speed = speedKph;
        if (Float.isNaN(speed) && hasFix && elapsed > lastFixElapsed) {
            speed = (float) (moved / ((elapsed - lastFixElapsed) / 1000.0) * 3.6);
        }
        if (Float.isNaN(speed)) speed = 0;

        if (state == State.UNKNOWN) {
            if (hasFix && moved > acceptedMeters + POOR_GAP_SLACK_M) poorMeters += moved - acceptedMeters;
            boolean stillAtAnchor = beforeUnknown == State.STANDSTILL
                    && distance(anchorLat, anchorLon, latitude, longitude) < Math.max(LEAVE_METERS, accuracy);
            if (speed < SLOW_KPH && stillAtAnchor) {
                state = State.STANDSTILL;
                fastSince = -1;
                listener.standstillStarted(wall);
            } else {
                state = State.MOVING;
                slowSince = speed < SLOW_KPH ? elapsed : -1;
                slowSinceWall = wall;
            }
        } else if (state == State.MOVING) {
            if (speed < SLOW_KPH) {
                if (slowSince < 0) { slowSince = elapsed; slowSinceWall = wall; }
                if (elapsed - slowSince >= STANDSTILL_AFTER_MS) {
                    // The slow seconds were standstill all along.
                    double seconds = Math.min(movingSeconds, (elapsed - slowSince) / 1000.0);
                    movingSeconds -= seconds;
                    standstillSeconds += seconds;
                    state = State.STANDSTILL;
                    anchorLat = latitude; anchorLon = longitude;
                    fastSince = -1;
                    listener.standstillStarted(slowSinceWall);
                }
            } else {
                slowSince = -1;
            }
        } else {
            double fromAnchor = distance(anchorLat, anchorLon, latitude, longitude);
            boolean displaced = fromAnchor >= LEAVE_METERS && fromAnchor > accuracy;
            if (speed >= FAST_KPH) {
                if (fastSince < 0) { fastSince = elapsed; fastSinceWall = wall; }
            } else {
                fastSince = -1;
            }
            boolean sustained = fastSince >= 0 && elapsed - fastSince >= MOVING_AFTER_MS;
            if (displaced || sustained) {
                // The car pulled away when it first went faster, if it did.
                boolean pulledAway = fastSince >= 0;
                long since = pulledAway ? fastSince : elapsed;
                double seconds = Math.min(standstillSeconds, (elapsed - since) / 1000.0);
                standstillSeconds -= seconds;
                movingSeconds += seconds;
                state = State.MOVING;
                slowSince = -1;
                listener.standstillEnded(pulledAway ? fastSinceWall : wall);
            }
        }
        hasFix = true;
        lat = latitude; lon = longitude;
        lastFixElapsed = elapsed;
        lastFixWall = wall;
    }

    /** Called about once a second: accounts the time since the last tick to the current state. */
    void tick(long elapsed, long wall) {
        if (state != State.UNKNOWN && elapsed - lastFixElapsed > FIX_TIMEOUT_MS) {
            // The first 5 s after the last fix still belong to the previous state.
            if (state == State.STANDSTILL) listener.standstillEnded(lastFixWall + FIX_TIMEOUT_MS);
            beforeUnknown = state;
            state = State.UNKNOWN;
            slowSince = -1;
            fastSince = -1;
        }
        if (lastTick >= 0 && elapsed > lastTick) {
            double seconds = (elapsed - lastTick) / 1000.0;
            activeSeconds += seconds;
            // A long pause in ticks (the process was frozen) is time we know nothing about.
            if (seconds > 10 || state == State.UNKNOWN) unknownSeconds += seconds;
            else if (state == State.STANDSTILL) standstillSeconds += seconds;
            else movingSeconds += seconds;
        }
        lastTick = elapsed;
    }

    /** Haversine distance in metres. */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
