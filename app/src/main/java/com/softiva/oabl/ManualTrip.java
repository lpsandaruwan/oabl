package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A trip the owner starts and ends by hand. Parking, charging and switching the car off do not end it: it is
 * over only when the owner says so. Its figures are worked out from the car's own drive, stop and charge
 * events and the box's GPS drives since the start, so nothing is stored while it runs except the start.
 */
final class ManualTrip {
    private static final String PREFS = "manual_trip", ACTIVE = "active", START = "start", START_SOC = "start_soc",
            START_LAT = "start_lat", START_LON = "start_lon", LOG = "log";
    private static final int KEEP = 60;

    /** One event inside the trip, for the report: a drive, a stop or a charge, with where it happened. */
    static final class Line {
        String kind = "", source = "car";
        long start, end;
        double lat = Double.NaN, lon = Double.NaN, endLat = Double.NaN, endLon = Double.NaN;
        double km = Double.NaN, kwh = Double.NaN, regenKwh, idleKwh, chargedKwh = Double.NaN, chargedPoints;
        double before = Double.NaN, after = Double.NaN, avgKph = Double.NaN, peakKph = Double.NaN;
        double movingSeconds, seconds;
        /** Under 1 when only part of the event fell inside the trip. */
        double share = 1;
        boolean hasPlace() { return Double.isFinite(lat) && Double.isFinite(lon) && (lat != 0 || lon != 0); }
        boolean hasEndPlace() { return Double.isFinite(endLat) && Double.isFinite(endLon) && (endLat != 0 || endLon != 0); }
    }

    /** The figures of one trip, running or finished. NaN where the car gave nothing. */
    static final class Summary {
        long start, end;
        boolean active;
        double km = Double.NaN, kmGps, movingSeconds, stoppedSeconds, averageKph = Double.NaN, peakKph = Double.NaN;
        double kwh = Double.NaN, energyKm, economy = Double.NaN, regenKwh, idleKwh, cost = Double.NaN;
        double startSoc = Double.NaN, endSoc = Double.NaN;
        int drives, charges, stops;
        double chargedKwh, chargedPoints;
        /** Whole-trip average including the stops, and the battery used in percentage points (net of charging). */
        double overallKph = Double.NaN, batteryPoints = Double.NaN, batteryPercent = Double.NaN, capacity;
        /** Time spent in the car's stop events (waiting, parked), apart from {@link #stoppedSeconds}, which is everything not driving. */
        double stopSeconds;
        String from = "", to = "", currency = "";
        /** Every drive, stop and charge in time order. Worked out on demand; not kept with a saved trip. */
        List<Line> lines = new ArrayList<>();

        double elapsedSeconds(long now) { return Math.max(0, ((end > 0 ? end : now) - start) / 1000.0); }
    }

    private final SharedPreferences prefs;

    ManualTrip(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean active() { return prefs.getBoolean(ACTIVE, false) && prefs.getLong(START, 0) > 0; }

    long start() { return prefs.getLong(START, 0); }

    double startSoc() { return prefs.contains(START_SOC) ? prefs.getFloat(START_SOC, Float.NaN) : Double.NaN; }

    double startLat() { return prefs.getFloat(START_LAT, 0); }

    double startLon() { return prefs.getFloat(START_LON, 0); }

    void begin(long now, double soc, double lat, double lon) {
        SharedPreferences.Editor edit = prefs.edit().putBoolean(ACTIVE, true).putLong(START, now)
                .putFloat(START_LAT, (float) lat).putFloat(START_LON, (float) lon);
        if (Double.isFinite(soc)) edit.putFloat(START_SOC, (float) soc); else edit.remove(START_SOC);
        edit.apply();
    }

    /** Ends the trip with its final figures, kept in the trip log (newest last). */
    void end(Summary finished) {
        List<Summary> log = history();
        log.add(finished);
        while (log.size() > KEEP) log.remove(0);
        JSONArray array = new JSONArray();
        for (Summary s : log) array.put(toJson(s));
        prefs.edit().putBoolean(ACTIVE, false).remove(START).remove(START_SOC).putString(LOG, array.toString()).apply();
    }

    /** Finished trips, oldest first. */
    List<Summary> history() {
        List<Summary> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(LOG, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(fromJson(array.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }

    Summary last() {
        List<Summary> log = history();
        return log.isEmpty() ? null : log.get(log.size() - 1);
    }

    // ---- The figures ----

    /**
     * One trip's figures from the events and GPS drives since {@code start}. {@code end} is 0 while it runs.
     * Energy comes from the car's drive events (whole drives that began after the start); distance is the car's
     * odometer for those drives, else the box's GPS.
     */
    static Summary compute(List<CarEnergyTracker.Event> events, List<TripComputer.Trip> gps, long start, long end, long now,
                           double capacity, double startSoc, double endSoc, double price, double gridPerPoint, String currency) {
        Summary s = new Summary();
        s.start = start;
        s.end = end;
        s.active = end == 0;
        s.startSoc = startSoc;
        s.endSoc = endSoc;
        s.capacity = capacity;
        s.currency = currency == null ? "" : currency;
        long to = end > 0 ? end : Math.max(now, start);
        double carKm = 0, usedKwh = 0, energyKm = 0, peak = 0, moving = 0, drivePoints = 0;
        boolean anyEnergy = false, anyPoints = false;
        for (CarEnergyTracker.Event e : events) {
            Line line = new Line();
            line.kind = e.kind;
            line.start = e.start;
            line.end = e.end > 0 ? e.end : Math.max(e.last, e.start);
            line.seconds = (line.end - line.start) / 1000.0;
            line.lat = e.latitude;
            line.lon = e.longitude;
            line.before = e.before;
            line.after = e.after;
            if ("charge".equals(e.kind)) {
                if (e.start < start || e.start > to) continue;
                s.charges++;
                double added = e.measuredKwh();
                if (!Double.isFinite(added) && Double.isFinite(e.after) && Double.isFinite(e.before) && capacity > 0) added = Math.max(0, e.after - e.before) * capacity / 100;
                if (Double.isFinite(added)) s.chargedKwh += added;
                if (Double.isFinite(e.after) && Double.isFinite(e.before)) s.chargedPoints += Math.max(0, e.after - e.before);
                line.chargedKwh = added;
                line.chargedPoints = Double.isFinite(e.after) && Double.isFinite(e.before) ? Math.max(0, e.after - e.before) : 0;
                s.lines.add(line);
                continue;
            }
            // A drive already under way when the trip was started, or still going when it ended, counts for the part inside.
            double share = share(e.start, line.end, start, to);
            if (share <= 0) continue;
            line.share = share;
            if ("drive".equals(e.kind)) {
                s.drives++;
                double km = Math.max(0, driveKm(e)) * share;
                carKm += km;
                moving += e.movingSeconds * share;
                s.regenKwh += e.regenKwh * share;
                peak = Math.max(peak, e.peakSpeed);
                double kwh = e.netKwh(capacity) * share;
                if (Double.isFinite(kwh) && e.km > 0) { usedKwh += kwh; energyKm += km; anyEnergy = true; }
                if (Double.isFinite(e.points())) { drivePoints += e.points() * share; anyPoints = true; }
                line.km = km;
                line.kwh = Double.isFinite(kwh) ? kwh : Double.NaN;
                line.regenKwh = e.regenKwh * share;
                line.movingSeconds = e.movingSeconds * share;
                line.peakKph = e.peakSpeed > 0 ? e.peakSpeed : Double.NaN;
                if (line.movingSeconds >= 30 && km > 0) line.avgKph = km / (line.movingSeconds / 3600);
            } else if ("stop".equals(e.kind)) {
                s.stops++;
                double seconds = (e.stoppedSeconds > 0 ? e.stoppedSeconds : line.seconds) * share;
                s.stopSeconds += seconds;
                s.idleKwh += Math.max(0, e.modelKwh) * share;
                line.seconds = seconds;
                line.idleKwh = Math.max(0, e.modelKwh) * share;
            } else {
                continue;
            }
            s.lines.add(line);
        }
        double gpsKm = 0, gpsMinutes = 0, gpsPeak = 0;
        List<Line> gpsLines = new ArrayList<>();
        for (TripComputer.Trip t : gps) {
            double share = share(t.start, t.end, start, to);
            if (share <= 0) continue;
            gpsKm += t.kilometers * share;
            gpsMinutes += t.minutes * share;
            gpsPeak = Math.max(gpsPeak, t.peakKph);
            Line line = new Line();
            line.kind = "drive";
            line.source = "gps";
            line.start = t.start;
            line.end = t.end > 0 ? t.end : to;
            line.seconds = (line.end - line.start) / 1000.0;
            line.share = share;
            line.km = t.kilometers * share;
            line.movingSeconds = t.minutes * 60 * share;
            line.peakKph = t.peakKph > 0 ? t.peakKph : Double.NaN;
            if (line.movingSeconds >= 30 && line.km > 0) line.avgKph = line.km / (line.movingSeconds / 3600);
            if (t.hasPlaces()) { line.lat = t.startLat; line.lon = t.startLon; line.endLat = t.endLat; line.endLon = t.endLon; }
            gpsLines.add(line);
        }
        // A car drive's start position is the event's own; its end is where the stop or charge that followed began, else the end of a
        // GPS drive that finished at about the same moment. A GPS drive no car drive covers is a line of its own.
        for (Line g : gpsLines) {
            Line best = null; long bestOverlap = 0;
            for (Line c : s.lines) {
                if (!"drive".equals(c.kind) || !"car".equals(c.source)) continue;
                long overlap = Math.min(c.end, g.end) - Math.max(c.start, g.start);
                if (overlap > bestOverlap) { bestOverlap = overlap; best = c; }
            }
            if (best == null) { s.lines.add(g); continue; }
            if (!best.hasPlace() && g.hasPlace()) { best.lat = g.lat; best.lon = g.lon; }
            if (!best.hasEndPlace() && g.hasEndPlace() && Math.abs(g.end - best.end) <= 120_000) { best.endLat = g.endLat; best.endLon = g.endLon; }
            if (!Double.isFinite(best.peakKph)) best.peakKph = g.peakKph;
        }
        java.util.Collections.sort(s.lines, (a, b) -> Long.compare(a.start, b.start));
        for (int i = 0; i < s.lines.size(); i++) {
            Line c = s.lines.get(i);
            if (!"drive".equals(c.kind) || c.hasEndPlace()) continue;
            for (int j = i + 1; j < s.lines.size(); j++) {
                Line next = s.lines.get(j);
                if (next.start - c.end > 120_000) break;
                if (!"drive".equals(next.kind) && next.start >= c.end - 5_000 && next.hasPlace()) { c.endLat = next.lat; c.endLon = next.lon; break; }
            }
        }
        s.kmGps = gpsKm;
        s.km = carKm > 0 ? carKm : gpsKm > 0 ? gpsKm : s.drives > 0 || !gps.isEmpty() ? 0 : Double.NaN;
        s.movingSeconds = moving > 0 ? moving : gpsMinutes * 60;
        double elapsed = s.elapsedSeconds(now);
        // Everything that is not driving: waiting, parked, charging.
        s.stoppedSeconds = Math.max(0, elapsed - s.movingSeconds);
        double distance = Double.isFinite(s.km) ? s.km : 0;
        if (s.movingSeconds >= 60 && distance > 0) s.averageKph = distance / (s.movingSeconds / 3600);
        if (elapsed >= 60 && distance > 0) s.overallKph = distance / (elapsed / 3600);
        double peakKph = Math.max(peak, gpsPeak);
        if (peakKph > 0) s.peakKph = peakKph;
        if (anyEnergy) {
            s.kwh = usedKwh;
            s.energyKm = energyKm;
            if (energyKm >= 2 && usedKwh > 0) s.economy = usedKwh / energyKm * 100;
            if (Double.isFinite(price) && price >= 0 && usedKwh > 0) s.cost = CarTripCost.billedEnergy(usedKwh, gridPerPoint, capacity) * price;
        } else if (Double.isFinite(startSoc) && Double.isFinite(endSoc) && endSoc <= startSoc && capacity > 0 && s.charges == 0) {
            // Nothing from the drive log yet: the battery level the trip started and is at, to the nearest point.
            s.kwh = (startSoc - endSoc) * capacity / 100;
            if (distance >= 2 && s.kwh > 0) { s.energyKm = distance; s.economy = s.kwh / distance * 100; }
        }
        // Battery used: the level it started at less the level now, plus whatever was charged in between.
        if (Double.isFinite(startSoc) && Double.isFinite(endSoc)) s.batteryPoints = Math.max(0, startSoc - endSoc + s.chargedPoints);
        else if (anyPoints) s.batteryPoints = drivePoints;
        if (Double.isFinite(s.kwh) && capacity > 0) s.batteryPercent = s.kwh / capacity * 100;
        else if (Double.isFinite(s.batteryPoints)) s.batteryPercent = s.batteryPoints;
        return s;
    }

    /**
     * A drive's distance: the car's trip computer to 0.1 km when it agrees with the odometer, which only moves in
     * whole kilometres, else the odometer's.
     */
    static double driveKm(CarEnergyTracker.Event e) {
        double fine = e.tripKm - e.tripStartKm;
        if (Double.isFinite(fine) && fine >= 0 && Math.abs(fine - e.km) <= 1.5 + 0.05 * e.km) return fine;
        return e.km;
    }

    /**
     * The fraction of something that ran from {@code from} to {@code until} that falls inside the trip window.
     * 1 for all of it, 0 for none; an instant counts whole when it is inside the window.
     */
    static double share(long from, long until, long start, long to) {
        if (until <= from) return from >= start && from <= to ? 1 : 0;
        long overlap = Math.min(until, to) - Math.max(from, start);
        return overlap <= 0 ? 0 : Math.min(1, overlap / (double) (until - from));
    }

    /**
     * The trip's drives as distances for {@link #chart}: each drive's own kilometres, already cut to the part inside the trip,
     * so the bars add up to the distance shown.
     */
    static List<TripComputer.Trip> chartTrips(Summary s) {
        List<TripComputer.Trip> out = new ArrayList<>();
        long to = s.end > 0 ? s.end : Long.MAX_VALUE;
        for (Line l : s.lines) {
            if (!"drive".equals(l.kind) || !(l.km > 0)) continue;
            TripComputer.Trip t = new TripComputer.Trip();
            t.start = Math.max(l.start, s.start);
            t.end = Math.min(l.end, to);
            t.kilometers = l.km;
            out.add(t);
        }
        return out;
    }

    /** Kilometres per bucket across the trip, hourly up to 36 hours and daily beyond, with a label for some buckets. */
    static final class Chart {
        double[] km = new double[0];
        String[] names = new String[0];
        boolean hourly;
    }

    static Chart chart(List<TripComputer.Trip> gps, long start, long end, long now, java.util.TimeZone zone) {
        long to = end > 0 ? end : Math.max(now, start + 1);
        boolean hourly = to - start <= 36 * 3_600_000L;
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(start);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (!hourly) c.set(Calendar.HOUR_OF_DAY, 0);
        long bucket = hourly ? 3_600_000L : 86_400_000L;
        long first = c.getTimeInMillis();
        if (!hourly) {
            // Daily bars: no more than the last 30 days.
            long limit = to - 30 * bucket;
            if (first < limit) { c.setTimeInMillis(limit); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); first = c.getTimeInMillis(); }
        }
        int n = (int) Math.max(1, Math.min(48, (to - first + bucket - 1) / bucket));
        Chart chart = new Chart();
        chart.hourly = hourly;
        chart.km = new double[n];
        chart.names = new String[n];
        for (TripComputer.Trip t : gps) {
            if (share(t.start, t.end, start, to) <= 0) continue;
            // Only the part of a drive inside the trip window is spread over the bars.
            long ts = Math.max(t.start, start), te = t.end == 0 ? to : Math.min(t.end, to);
            long whole = Math.max(1, t.end > t.start ? t.end - t.start : te - ts);
            for (int i = 0; i < n; i++) {
                long from = Math.max(ts, first + i * bucket), until = Math.min(te, first + (i + 1) * bucket);
                if (until > from) chart.km[i] += t.kilometers * (until - from) / (double) whole;
                else if (te == ts && ts >= first + i * bucket && ts < first + (i + 1) * bucket) chart.km[i] += t.kilometers;
            }
        }
        Calendar label = Calendar.getInstance(zone);
        int step = hourly ? (n > 24 ? 6 : n > 12 ? 3 : 2) : (n > 20 ? 5 : n > 10 ? 2 : 1);
        for (int i = 0; i < n; i++) {
            label.setTimeInMillis(first + i * bucket);
            boolean show = i == 0 || i % step == 0;
            if (i == n - 1) show = true;
            chart.names[i] = !show ? "" : hourly ? String.format(Locale.US, "%02d", label.get(Calendar.HOUR_OF_DAY)) : String.valueOf(label.get(Calendar.DAY_OF_MONTH));
        }
        return chart;
    }

    // ---- Storage ----

    private static JSONObject toJson(Summary s) {
        JSONObject o = new JSONObject();
        try {
            o.put("start", s.start).put("end", s.end).put("km", num(s.km)).put("kmGps", s.kmGps).put("moving", s.movingSeconds)
                    .put("stopped", s.stoppedSeconds).put("avg", num(s.averageKph)).put("peak", num(s.peakKph)).put("kwh", num(s.kwh))
                    .put("energyKm", s.energyKm).put("economy", num(s.economy)).put("regen", s.regenKwh).put("idle", s.idleKwh)
                    .put("cost", num(s.cost)).put("startSoc", num(s.startSoc)).put("endSoc", num(s.endSoc)).put("drives", s.drives)
                    .put("charges", s.charges).put("chargedKwh", s.chargedKwh).put("chargedPoints", s.chargedPoints)
                    .put("from", s.from).put("to", s.to).put("currency", s.currency).put("stops", s.stops).put("stopSeconds", s.stopSeconds)
                    .put("overall", num(s.overallKph)).put("batteryPoints", num(s.batteryPoints)).put("batteryPercent", num(s.batteryPercent)).put("capacity", s.capacity);
        } catch (JSONException ignored) { }
        return o;
    }

    private static Object num(double value) { return Double.isFinite(value) ? (Object) value : JSONObject.NULL; }

    private static double read(JSONObject o, String key) { return o.isNull(key) ? Double.NaN : o.optDouble(key, Double.NaN); }

    private static Summary fromJson(JSONObject o) {
        Summary s = new Summary();
        s.start = o.optLong("start"); s.end = o.optLong("end");
        s.km = read(o, "km"); s.kmGps = o.optDouble("kmGps"); s.movingSeconds = o.optDouble("moving"); s.stoppedSeconds = o.optDouble("stopped");
        s.averageKph = read(o, "avg"); s.peakKph = read(o, "peak"); s.kwh = read(o, "kwh"); s.energyKm = o.optDouble("energyKm");
        s.economy = read(o, "economy"); s.regenKwh = o.optDouble("regen"); s.idleKwh = o.optDouble("idle"); s.cost = read(o, "cost");
        s.startSoc = read(o, "startSoc"); s.endSoc = read(o, "endSoc"); s.drives = o.optInt("drives"); s.charges = o.optInt("charges");
        s.chargedKwh = o.optDouble("chargedKwh"); s.chargedPoints = o.optDouble("chargedPoints");
        s.from = o.optString("from"); s.to = o.optString("to"); s.currency = o.optString("currency");
        s.stops = o.optInt("stops"); s.stopSeconds = o.optDouble("stopSeconds", 0); s.overallKph = read(o, "overall");
        s.batteryPoints = read(o, "batteryPoints"); s.batteryPercent = read(o, "batteryPercent"); s.capacity = o.optDouble("capacity", 0);
        return s;
    }
}
