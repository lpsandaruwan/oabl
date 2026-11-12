package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

/** Local trip aggregation with accuracy, elapsed-time and jump filtering. */
final class TripComputer {
    private static TripComputer instance;
    private final SharedPreferences prefs;
    private final Context context;
    private double meters;
    private double movingSeconds;
    private float peakKph;
    private Location previous;
    private long lastTime;
    private long lastSave;
    // Current trip for the history log (wall-clock times so it survives restarts).
    private long tripStart, lastMoving;
    private double tripStartMeters, tripStartSeconds;
    private float tripPeak;
    // Lifetime distance (never reset) for service reminders, and where drives start and end.
    private double odometerMeters;
    private double startLat, startLon, lastLat, lastLon;
    private static final long PARK_GAP = 5 * 60_000L;

    /** One finished drive. */
    static final class Trip implements java.io.Serializable {
        long start, end;
        double kilometers, minutes;
        float peakKph;
        double startLat, startLon, endLat, endLon;
        boolean hasPlaces() { return startLat != 0 || startLon != 0; }
        double averageKph() { return minutes > 0 ? kilometers / (minutes / 60) : 0; }
    }
    static synchronized TripComputer get(Context context) {
        if (instance == null) instance = new TripComputer(context);
        return instance;
    }
    static synchronized void reload(Context context){instance=new TripComputer(context);}
    private TripComputer(Context context) {
        this.context=context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences("local_trip", 0);
        if(!EnergyClient.owner(context))return;
        meters = prefs.getFloat("meters", 0); movingSeconds = prefs.getFloat("moving_seconds", 0);
        peakKph = prefs.getFloat("peak_kph", 0);
        tripStart = prefs.getLong("trip_start", 0);
        lastMoving = prefs.getLong("trip_last_moving", 0);
        tripStartMeters = prefs.getFloat("trip_start_meters", 0);
        tripStartSeconds = prefs.getFloat("trip_start_seconds", 0);
        tripPeak = prefs.getFloat("trip_peak", 0);
        odometerMeters = Double.longBitsToDouble(prefs.getLong("odometer_bits", Double.doubleToLongBits(meters)));
        startLat = Double.longBitsToDouble(prefs.getLong("trip_start_lat", 0));
        startLon = Double.longBitsToDouble(prefs.getLong("trip_start_lon", 0));
        lastLat = Double.longBitsToDouble(prefs.getLong("last_lat", 0));
        lastLon = Double.longBitsToDouble(prefs.getLong("last_lon", 0));
        closeIfParked(System.currentTimeMillis());
    }
    synchronized void accept(Location location, boolean reliable) {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"trip.accept",new Object[]{location,reliable});return;}
        long now = location.getElapsedRealtimeNanos();
        if (now <= lastTime && lastTime - now < 60_000_000_000L) return;
        if (!reliable || !location.hasAccuracy() || !Float.isFinite(location.getAccuracy())
                || location.getAccuracy() > 30 || !location.hasSpeed() || !Float.isFinite(location.getSpeed())
                || location.getSpeed() < 0 || location.getSpeed() > 90) {
            previous = null; lastTime = now; return;
        }
        float speed = location.hasSpeed() ? location.getSpeed() : 0;
        if (previous != null && previous.distanceTo(location) > 0.5f) peakKph = Math.max(peakKph, speed * 3.6f);
        if (previous != null && now > lastTime) {
            double seconds = (now-lastTime) / 1e9;
            float distance = previous.distanceTo(location);
            float oldSpeed = previous.hasSpeed() ? previous.getSpeed() : 0;
            // A reported speed only counts when the position actually moved accordingly; parked
            // receivers (and repeated last fixes) can keep reporting speed without moving.
            boolean displaced = distance >= .35f * (speed + oldSpeed) / 2f * seconds;
            if (seconds >= .25 && seconds <= 15 && (speed >= 1.4f || oldSpeed >= 1.4f) && displaced
                    && distance <= Math.max(80, (speed+oldSpeed)*seconds+20)) {
                meters += distance;
                movingSeconds += seconds;
                odometerMeters += distance;
                long wall = System.currentTimeMillis();
                if (tripStart == 0) {
                    tripStart = wall - Math.round(seconds * 1000);
                    tripStartMeters = meters - distance;
                    tripStartSeconds = movingSeconds - seconds;
                    tripPeak = 0;
                    startLat = previous.getLatitude();
                    startLon = previous.getLongitude();
                }
                lastMoving = wall;
                lastLat = location.getLatitude();
                lastLon = location.getLongitude();
                tripPeak = Math.max(tripPeak, speed * 3.6f);
            }
        }
        closeIfParked(System.currentTimeMillis());
        previous = new Location(location); lastTime = now;
        if (now-lastSave >= 30_000_000_000L) { save(); lastSave = now; }
    }
    synchronized double kilometers() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"trip.kilometers");} return meters / 1000; }
    synchronized double movingMinutes() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"trip.movingMinutes");} return movingSeconds / 60; }
    synchronized double averageKph() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"trip.averageKph");} return movingSeconds > 0 ? meters / movingSeconds * 3.6 : 0; }
    synchronized float peakKph() {
        if(!EnergyClient.owner(context)){return (float)EnergyClient.call(context,"trip.peakKph");} return peakKph; }
    /** Kilometres driven with the box on since it was installed (not reset with the trip). */
    synchronized double odometerKm() {
        if(!EnergyClient.owner(context)){return (double)EnergyClient.call(context,"trip.odometerKm");} return odometerMeters / 1000; }
    /** Where the drive in progress started, or null when no drive is in progress. */
    synchronized double[] liveStart() {
        if(!EnergyClient.owner(context)){return (double[])EnergyClient.call(context,"trip.liveStart");}
        if (tripStart == 0 || (startLat == 0 && startLon == 0)) return null;
        return new double[]{startLat, startLon};
    }
    /** Where the car last stopped and since when, or null before the first drive. */
    synchronized double[] parked() {
        if(!EnergyClient.owner(context)){return (double[])EnergyClient.call(context,"trip.parked");}
        if (lastLat == 0 && lastLon == 0) return null;
        return new double[]{lastLat, lastLon, lastMoving};
    }
    synchronized void save() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"trip.save");return;}
        prefs.edit().putFloat("meters", (float) meters).putFloat("moving_seconds", (float) movingSeconds)
                .putFloat("peak_kph", peakKph).putLong("trip_start", tripStart).putLong("trip_last_moving", lastMoving)
                .putFloat("trip_start_meters", (float) tripStartMeters).putFloat("trip_start_seconds", (float) tripStartSeconds)
                .putFloat("trip_peak", tripPeak).putLong("odometer_bits", Double.doubleToLongBits(odometerMeters))
                .putLong("trip_start_lat", Double.doubleToLongBits(startLat)).putLong("trip_start_lon", Double.doubleToLongBits(startLon))
                .putLong("last_lat", Double.doubleToLongBits(lastLat)).putLong("last_lon", Double.doubleToLongBits(lastLon)).apply();
    }

    /** A drive ends after five minutes without movement; short hops under 300 m are not logged. */
    private void closeIfParked(long wall) {
        if (tripStart == 0 || wall - lastMoving < PARK_GAP) return;
        double km = (meters - tripStartMeters) / 1000;
        double minutes = (movingSeconds - tripStartSeconds) / 60;
        if (km >= 0.3) {
            try {
                org.json.JSONArray log = new org.json.JSONArray(prefs.getString("trip_log", "[]"));
                org.json.JSONObject trip = new org.json.JSONObject();
                trip.put("start", tripStart).put("end", lastMoving).put("km", km).put("min", minutes).put("peak", tripPeak)
                        .put("slat", startLat).put("slon", startLon).put("elat", lastLat).put("elon", lastLon);
                log.put(trip);
                while (log.length() > 300) log.remove(0);
                prefs.edit().putString("trip_log", log.toString()).apply();
            } catch (org.json.JSONException ignored) { }
        }
        tripStart = 0;
        tripPeak = 0;
        save();
    }

    /** Finished drives, newest first, plus the drive in progress (end = 0) if any. */
    synchronized java.util.List<Trip> history() {
        if(!EnergyClient.owner(context)){return (java.util.List<Trip>)EnergyClient.call(context,"trip.history");}
        closeIfParked(System.currentTimeMillis());
        java.util.List<Trip> trips = new java.util.ArrayList<>();
        if (tripStart != 0) {
            Trip live = new Trip();
            live.start = tripStart;
            live.kilometers = (meters - tripStartMeters) / 1000;
            live.minutes = (movingSeconds - tripStartSeconds) / 60;
            live.peakKph = tripPeak;
            live.startLat = startLat; live.startLon = startLon; live.endLat = lastLat; live.endLon = lastLon;
            trips.add(live);
        }
        try {
            org.json.JSONArray log = new org.json.JSONArray(prefs.getString("trip_log", "[]"));
            for (int i = log.length() - 1; i >= 0; i--) {
                org.json.JSONObject item = log.getJSONObject(i);
                Trip trip = new Trip();
                trip.start = item.getLong("start");
                trip.end = item.getLong("end");
                trip.kilometers = item.getDouble("km");
                trip.minutes = item.getDouble("min");
                trip.peakKph = (float) item.getDouble("peak");
                trip.startLat = item.optDouble("slat", 0); trip.startLon = item.optDouble("slon", 0);
                trip.endLat = item.optDouble("elat", 0); trip.endLon = item.optDouble("elon", 0);
                trips.add(trip);
            }
        } catch (org.json.JSONException ignored) { }
        return trips;
    }

    /** Kilometres per day for the last seven days (oldest first), from the log plus the live drive. */
    synchronized double[] lastSevenDays() {
        if(!EnergyClient.owner(context)){return (double[])EnergyClient.call(context,"trip.lastSevenDays");}
        double[] days = new double[7];
        java.util.Calendar midnight = java.util.Calendar.getInstance();
        midnight.set(java.util.Calendar.HOUR_OF_DAY, 0); midnight.set(java.util.Calendar.MINUTE, 0);
        midnight.set(java.util.Calendar.SECOND, 0); midnight.set(java.util.Calendar.MILLISECOND, 0);
        long today = midnight.getTimeInMillis();
        for (Trip trip : history()) {
            int ago = (int) Math.floor((today - trip.start) / 86_400_000.0) + 1;
            if (trip.start >= today) ago = 0;
            if (ago >= 0 && ago < 7) days[6 - ago] += trip.kilometers;
        }
        return days;
    }

    synchronized void clearHistory() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"trip.clearHistory");return;}
        prefs.edit().putString("trip_log", "[]").apply();
    }
    synchronized void reset() {
        if(!EnergyClient.owner(context)){EnergyClient.call(context,"trip.reset");return;}
        // Close any drive in progress first so the history keeps it.
        if (tripStart != 0) { lastMoving = Math.min(lastMoving, System.currentTimeMillis() - PARK_GAP); closeIfParked(System.currentTimeMillis()); }
        meters = 0; movingSeconds = 0; peakKph = 0; previous = null; lastTime = 0; save();
    }
}
