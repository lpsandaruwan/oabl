package com.softiva.oabl;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Bundle;

final class SpeedTracker implements LocationListener {
    interface Listener {
        void onSpeed(int kilometersPerHour, boolean reliable);
        default void onPosition(Location location, boolean reliable) { }
        default void onSatellites(int used, int seen) { }
    }

    private final Context context;
    private final LocationManager locationManager;
    private final Listener listener;
    private Location previous;
    private boolean running;
    private final GnssStatus.Callback gnss = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
            listener.onSatellites(used, status.getSatelliteCount());
        }
        @Override public void onStopped() { listener.onSatellites(0, 0); }
    };

    SpeedTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.listener = listener;
    }

    boolean start() {
        if (running) {
            return true;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED || locationManager == null) {
            listener.onSpeed(0, false);
            return false;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, this);
            running = true;
            try { locationManager.registerGnssStatusCallback(gnss, new android.os.Handler(android.os.Looper.getMainLooper())); }
            catch (RuntimeException ignored) { }
            return true;
        } catch (RuntimeException error) {
            listener.onSpeed(0, false);
            return false;
        }
    }

    void stop() {
        if (locationManager != null && running) {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(gnss);
        }
        running = false;
        previous = null;
        TripComputer.get(context).save();
    }

    @Override
    public void onLocationChanged(Location location) {
        float metersPerSecond = -1f;
        boolean reliable = location.hasSpeed() && location.hasAccuracy() && location.getAccuracy() <= 50f;
        if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()
                && location.getSpeedAccuracyMetersPerSecond() > 5) reliable = false;
        if (reliable) {
            metersPerSecond = location.getSpeed();
            if (frozen(location, metersPerSecond)) metersPerSecond = 0f;
        } else if (!location.hasSpeed() && previous != null && previous.hasAccuracy()
                && previous.getAccuracy() <= 35f && location.hasAccuracy() && location.getAccuracy() <= 35f) {
            long elapsedMillis = location.getElapsedRealtimeNanos() / 1_000_000L
                    - previous.getElapsedRealtimeNanos() / 1_000_000L;
            if (elapsedMillis >= 500L && elapsedMillis <= 5000L) {
                metersPerSecond = previous.distanceTo(location) / (elapsedMillis / 1000f);
                reliable = true;
            }
        }
        previous = location;
        if (metersPerSecond < 0f || metersPerSecond > 90f) {
            TripComputer.get(context).accept(location, false);
            listener.onSpeed(0, false);
            listener.onPosition(location, false);
            return;
        }
        listener.onSpeed(Math.max(0, Math.round(metersPerSecond * 3.6f)), reliable);
        Location reading = new Location(location);
        reading.setSpeed(metersPerSecond);
        TripComputer.get(context).accept(reading, reliable);
        listener.onPosition(location, reliable);
    }

    /**
     * A receiver that keeps replaying its last fix (or a glitching one) reports a speed while
     * the position never changes. When the claimed speed should have moved the car well over
     * 8 m since the previous fix but it moved less than a quarter of that, it is standing still.
     */
    private boolean frozen(Location location, float metersPerSecond) {
        if (previous == null || metersPerSecond < 2f) return false;
        long elapsedMillis = (location.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos()) / 1_000_000L;
        if (elapsedMillis < 500L || elapsedMillis > 5000L) return false;
        float expected = metersPerSecond * elapsedMillis / 1000f;
        return expected >= 8f && previous.distanceTo(location) < expected * 0.25f;
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { listener.onSpeed(0, false); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
