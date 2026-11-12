package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Conservative real thermal tuning: earlier passive cooling, never disabling protection. */
final class ThermalControls {
    static final String READ = "for z in /sys/class/thermal/thermal_zone*; do [ -d \"$z\" ] || continue; "
            + "for f in \"$z\"/trip_point_*_type; do [ -r \"$f\" ] || continue; n=${f##*/trip_point_}; n=${n%_type}; "
            + "printf '%s|' \"${z##*/}\"; cat \"$z/type\" 2>/dev/null | tr '\\n' ' '; printf '|%s|' \"$n\"; "
            + "cat \"$f\" | tr '\\n' ' '; printf '|'; cat \"$z/trip_point_${n}_temp\" 2>/dev/null | tr '\\n' ' '; "
            + "printf '|'; cat \"$z/trip_point_${n}_hyst\" 2>/dev/null | tr '\\n' ' '; "
            + "printf '|'; [ -w \"$z/trip_point_${n}_temp\" ] && printf yes; "
            + "printf '|'; [ -w \"$z/trip_point_${n}_hyst\" ] && printf yes; echo; done; done";
    static final class Trip {
        final String zone, sensor, index, kind; final long temp, hyst; final boolean tempWritable, hystWritable;
        Trip(String[] values) {
            zone = values[0]; sensor = values[1]; index = values[2]; kind = values[3];
            temp = CpuControls.number(values[4]); hyst = CpuControls.number(values[5]);
            tempWritable = values[6].equals("yes"); hystWritable = values[7].equals("yes");
        }
        String key() { return zone + "_" + index; }
        boolean editable() { return kind.equals("passive") && (tempWritable || (hystWritable && hyst >= 0)); }
        String label() { return sensor + " · trip " + index + " · " + kind + " · " + temp / 1000d + " °C" + (editable() ? "" : " · locked"); }
    }
    static boolean valid(String zone, String index) {
        return zone != null && zone.matches("thermal_zone[0-9]{1,4}") && index != null && index.matches("[0-9]{1,3}");
    }
    static List<Trip> parse(String report) {
        List<Trip> trips = new ArrayList<>();
        for (String line : report.split("\\r?\\n")) {
            String[] values = line.split("\\|", -1); if (values.length != 8) continue;
            for (int i = 0; i < values.length; i++) values[i] = values[i].trim();
            if (!valid(values[0], values[2])) continue;
            Trip trip = new Trip(values);
            if (trip.temp >= 15000 && trip.temp <= 200000) trips.add(trip);
        }
        return trips;
    }
    static List<Trip> read(RootAccess.Runner runner) {
        if (!RootAccess.check(runner)) throw new IllegalStateException("Root access unavailable. Thermal settings unchanged.");
        RootAccess.Result result = runner.run(READ);
        if (result.exitCode != 0) throw new IllegalStateException("Thermal trip-point read failed.");
        return parse(result.output);
    }
    static Trip find(List<Trip> trips, String zone, String index) {
        if (!valid(zone, index)) throw new IllegalArgumentException("Invalid thermal control");
        for (Trip trip : trips) if (trip.zone.equals(zone) && trip.index.equals(index)) return trip;
        throw new IllegalStateException("Thermal trip no longer available.");
    }
    private static SharedPreferences storage(Context context) { return context.getSharedPreferences("thermal_originals", Context.MODE_PRIVATE); }
    private static String device(Context context) { return Build.FINGERPRINT + ":" + Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID); }
    private static JSONObject original(Context context, Trip trip, boolean save) {
        SharedPreferences prefs = storage(context);
        boolean same = device(context).equals(prefs.getString("device", ""));
        try {
            if (same && prefs.contains(trip.key())) return new JSONObject(prefs.getString(trip.key(), "{}"));
            JSONObject current = new JSONObject().put("temp", trip.temp).put("hyst", trip.hyst).put("kind", trip.kind).put("sensor", trip.sensor);
            if (save) {
                SharedPreferences.Editor editor = prefs.edit();
                if (!same) editor.clear().putString("device", device(context));
                if (!editor.putString(trip.key(), current.toString()).commit()) throw new IllegalStateException("Could not save original thermal setting. Nothing changed.");
            }
            return current;
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("Original thermal record invalid."); }
    }
    static long maximumThreshold(Context context, Trip trip, List<Trip> trips) {
        long max = Math.min(85000, original(context, trip, false).optLong("temp", trip.temp));
        for (Trip other : trips) if (other.zone.equals(trip.zone) && (other.kind.equals("critical") || other.kind.equals("hot"))) max = Math.min(max, other.temp-5000);
        return max;
    }
    static long minimumHysteresis(Context context, Trip trip) { return Math.max(0, original(context, trip, false).optLong("hyst", trip.hyst)); }
    private static void write(RootAccess.Runner runner, Trip trip, boolean hysteresis, long value) {
        if (!valid(trip.zone, trip.index) || !trip.kind.equals("passive") || value < 0 || value > 200000)
            throw new IllegalArgumentException("Invalid thermal control");
        String path = "/sys/class/thermal/" + trip.zone + "/trip_point_" + trip.index + (hysteresis ? "_hyst" : "_temp");
        RootAccess.Result changed = runner.run("printf '%s' '" + value + "' > '" + path + "'");
        RootAccess.Result actual = runner.run("cat '" + path + "'");
        if (changed.exitCode != 0 || actual.exitCode != 0 || CpuControls.number(actual.output) != value)
            throw new IllegalStateException("Thermal driver rejected or overrode the setting.");
    }
    static synchronized String change(Context context, String zone, String index, boolean hysteresis, long value, RootAccess.Runner runner) {
        List<Trip> trips = read(runner); Trip current = find(trips, zone, index);
        boolean supported = current.kind.equals("passive") && (hysteresis ? current.hystWritable && current.hyst >= 0 : current.tempWritable);
        if (!supported || (hysteresis ? value < minimumHysteresis(context, current) || value > 20000 || value >= current.temp-15000
                : value < 30000 || value > maximumThreshold(context, current, trips) || (current.hyst >= 0 && value-current.hyst < 15000)))
            throw new IllegalArgumentException("Outside conservative bounds or unsupported. No thermal settings changed.");
        original(context, current, true);
        try { write(runner, current, hysteresis, value); }
        catch (RuntimeException failure) {
            String rollback;
            try { write(runner, current, hysteresis, hysteresis ? current.hyst : current.temp); rollback = " Previous setting restored."; }
            catch (RuntimeException ignored) { rollback = " Rollback unverified; refresh thermal settings."; }
            throw new IllegalStateException(failure.getMessage() + rollback);
        }
        return "Verified passive " + (hysteresis ? "hysteresis" : "cooling threshold") + ": " + value/1000d
                + " °C. Critical/shutdown protection unchanged. Not automatically reapplied after reboot.";
    }
    static synchronized String restore(Context context, RootAccess.Runner runner) {
        List<Trip> trips = read(runner); SharedPreferences prefs = storage(context); int count = 0;
        if (!device(context).equals(prefs.getString("device", ""))) return "No original thermal settings saved for this device.";
        for (Trip trip : trips) if (trip.kind.equals("passive") && prefs.contains(trip.key())) {
            JSONObject original = original(context, trip, false);
            if (!trip.sensor.equals(original.optString("sensor")) || !trip.kind.equals(original.optString("kind")))
                throw new IllegalStateException("Thermal sensor mapping changed. Restore stopped.");
            try {
                long savedTemp = original.getLong("temp"), savedHyst = original.getLong("hyst");
                if (savedTemp < 15000 || savedTemp > 200000 || savedHyst < -1 || savedHyst > 200000
                        || savedTemp-Math.max(0, savedHyst) < 15000)
                    throw new IllegalStateException("Original thermal record outside valid bounds.");
                for (Trip protectedTrip : trips) if (protectedTrip.zone.equals(trip.zone)
                        && (protectedTrip.kind.equals("hot") || protectedTrip.kind.equals("critical")) && savedTemp > protectedTrip.temp-5000)
                    throw new IllegalStateException("Protected trip values changed; saved threshold no longer has the required margin.");
                if (trip.tempWritable && trip.temp != original.optLong("temp")) write(runner, trip, false, original.getLong("temp"));
                if (savedHyst >= 0 && trip.hystWritable && trip.hyst != savedHyst) write(runner, trip, true, savedHyst);
                count++;
            } catch (Exception failure) { throw new IllegalStateException("Restore incomplete: " + failure.getMessage() + " Earlier trips may already be restored."); }
        }
        return "Original passive thermal settings restored and verified for " + count + " trips. Critical/shutdown settings untouched.";
    }
}
