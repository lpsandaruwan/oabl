package com.softiva.oabl;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Everything a finished or running trip says, laid out three ways: the figures on the trip card, the rows of the
 * shared picture, and a CSV with every event and its GPS position. No Android dependencies besides the cell type.
 */
final class TripReport {
    private TripReport() { }

    /** Stops shorter than this are summed into one picture row (the CSV keeps each). */
    static final double SHORT_STOP_SECONDS = 60;

    static String duration(double seconds) {
        long total = Math.round(seconds);
        if (total < 60) return total + " s";
        long minutes = Math.round(total / 60.0);
        if (minutes < 60) return minutes + " min";
        if (minutes < 24 * 60) return (minutes / 60) + "h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + "m");
        return (minutes / 1440) + "d" + ((minutes % 1440) / 60 == 0 ? "" : " " + (minutes % 1440) / 60 + "h");
    }

    static String km(double value) {
        return value >= 100 ? String.valueOf(Math.round(value)) : String.format(Locale.US, "%.1f", value);
    }

    /** "6.5000° N 79.5000° E". */
    static String coordinates(double lat, double lon) {
        return String.format(Locale.US, "%.4f° %s %.4f° %s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
    }

    private static String percent(double value) { return String.format(Locale.US, "%.0f%%", value); }

    private static String soc(double value) { return Double.isFinite(value) ? percent(value) : "…"; }

    /**
     * The twelve figures, four to a row: time, distance, moving, idle; average and top speed, energy, idle energy;
     * regen, battery used, charged and cost.
     */
    static DriveStatsView.Cell[] cells(ManualTrip.Summary s, long now, String when, String places) {
        double elapsed = s.elapsedSeconds(now);
        boolean energy = Double.isFinite(s.kwh);
        String money = s.currency == null ? "" : s.currency;
        DriveStatsView.Cell cost = Double.isFinite(s.cost)
                ? new DriveStatsView.Cell("Cost", "≈" + HomeCost.money(s.cost), money)
                        .note(Double.isFinite(s.km) && s.km >= 1 ? "≈" + HomeCost.money(s.cost / s.km) + " per km" : "At the average paid price")
                : new DriveStatsView.Cell("Cost", "—", "").note("Price a charge to see costs");
        return new DriveStatsView.Cell[]{
                new DriveStatsView.Cell(s.active ? "Trip in progress" : "Trip time", duration(elapsed), "").note(when + places),
                new DriveStatsView.Cell("Distance", Double.isFinite(s.km) ? km(s.km) : "—", "km")
                        .note(s.drives + (s.drives == 1 ? " drive" : " drives") + (s.kmGps > 0 ? " · GPS " + km(s.kmGps) + " km" : "")),
                new DriveStatsView.Cell("Moving", duration(s.movingSeconds), "")
                        .note(elapsed > 0 ? Math.round(100 * s.movingSeconds / elapsed) + "% of the trip" : ""),
                new DriveStatsView.Cell("Idle", duration(s.stoppedSeconds), "")
                        .note(s.stops > 0 ? s.stops + (s.stops == 1 ? " stop" : " stops") + " · waiting, parked" : "Waiting, parked, charging"),
                new DriveStatsView.Cell("Average speed", Double.isFinite(s.averageKph) ? String.valueOf(Math.round(s.averageKph)) : "—", "km/h")
                        .note(Double.isFinite(s.overallKph) ? Math.round(s.overallKph) + " km/h with stops" : "Moving time only"),
                new DriveStatsView.Cell("Top speed", Double.isFinite(s.peakKph) ? String.valueOf(Math.round(s.peakKph)) : "—", "km/h")
                        .note("Fastest of the drives"),
                new DriveStatsView.Cell("Energy used", energy ? String.format(Locale.US, "%.2f", s.kwh) : "—", "kWh")
                        .note(Double.isFinite(s.economy) ? String.format(Locale.US, "%.1f kWh/100 km", s.economy) : "Needs 2 km of linked driving"),
                new DriveStatsView.Cell("Idle energy", s.idleKwh >= 0.005 ? "≈" + String.format(Locale.US, "%.2f", s.idleKwh) : "—", s.idleKwh >= 0.005 ? "kWh" : "")
                        .note("Used while stopped"),
                new DriveStatsView.Cell("Regen", s.regenKwh >= 0.01 ? "≈" + String.format(Locale.US, "%.2f", s.regenKwh) : "—", s.regenKwh >= 0.01 ? "kWh" : "")
                        .note(s.regenKwh >= 0.01 && energy && s.kwh > 0 ? String.format(Locale.US, "≈%.0f%% of the energy used", 100 * s.regenKwh / s.kwh) : "Modelled from the car's power"),
                new DriveStatsView.Cell("Battery used", Double.isFinite(s.batteryPercent) ? String.format(Locale.US, "%.1f", s.batteryPercent) : "—", Double.isFinite(s.batteryPercent) ? "%" : "")
                        .note(Double.isFinite(s.startSoc) ? percent(s.startSoc) + " → " + soc(s.endSoc) : "Level at the start unknown"),
                new DriveStatsView.Cell("Charged", s.charges > 0 ? String.format(Locale.US, "%.1f", s.chargedKwh) : "—", s.charges > 0 ? "kWh" : "")
                        .note(s.charges > 0 ? s.charges + (s.charges == 1 ? " charge" : " charges") + String.format(Locale.US, " · +%.0f%%", s.chargedPoints) : "No charging on this trip"),
                cost};
    }

    /** The four headline figures: distance, time, battery used and cost. */
    static DriveStatsView.Cell[] heroes(ManualTrip.Summary s, long now) {
        DriveStatsView.Cell[] all = cells(s, now, "", "");
        DriveStatsView.Cell battery = all[9];
        return new DriveStatsView.Cell[]{all[1].note(""), new DriveStatsView.Cell("Trip time", duration(s.elapsedSeconds(now)), "").note(""), battery, all[11].note("")};
    }

    /** Plain-language highlights, most important first. */
    static List<String> highlights(ManualTrip.Summary s, DateFormat clock) {
        List<String> out = new ArrayList<>();
        if (Double.isFinite(s.km)) {
            out.add("Drove " + km(s.km) + " km in " + duration(s.elapsedSeconds(s.end > 0 ? s.end : System.currentTimeMillis()))
                    + (Double.isFinite(s.averageKph) ? " · " + Math.round(s.averageKph) + " km/h moving" : "")
                    + (Double.isFinite(s.peakKph) ? " · top " + Math.round(s.peakKph) + " km/h" : ""));
        }
        if (Double.isFinite(s.kwh)) {
            out.add(String.format(Locale.US, "Used %.2f kWh", s.kwh)
                    + (Double.isFinite(s.batteryPercent) ? String.format(Locale.US, " · %.1f%% of the battery", s.batteryPercent) : "")
                    + (Double.isFinite(s.startSoc) && Double.isFinite(s.endSoc) ? " (" + percent(s.startSoc) + " → " + percent(s.endSoc) + ")" : "")
                    + (Double.isFinite(s.economy) ? String.format(Locale.US, " · %.1f kWh/100 km", s.economy) : ""));
        }
        if (s.regenKwh >= 0.01) {
            out.add(String.format(Locale.US, "Regen recovered about %.2f kWh", s.regenKwh)
                    + (Double.isFinite(s.kwh) && s.kwh > 0 ? String.format(Locale.US, ", %.0f%% of the energy used", 100 * s.regenKwh / s.kwh) : ""));
        }
        if (s.stoppedSeconds >= 60) {
            ManualTrip.Line longest = null;
            for (ManualTrip.Line l : s.lines) if ("stop".equals(l.kind) && (longest == null || l.seconds > longest.seconds)) longest = l;
            out.add("Idle " + duration(s.stoppedSeconds) + (s.stops > 0 ? " · " + s.stops + (s.stops == 1 ? " stop" : " stops") : "")
                    + (s.idleKwh >= 0.005 ? String.format(Locale.US, " · ≈%.2f kWh", s.idleKwh) : "")
                    + (longest != null && longest.seconds >= 60 ? " · longest " + duration(longest.seconds) + " at " + clock.format(new Date(longest.start)) : ""));
        }
        out.add(s.charges > 0
                ? String.format(Locale.US, "Charged %.1f kWh (+%.0f%%) in %d %s", s.chargedKwh, s.chargedPoints, s.charges, s.charges == 1 ? "session" : "sessions")
                : "No charging on this trip");
        if (Double.isFinite(s.cost)) {
            out.add("Costs about " + HomeCost.money(s.cost) + " " + (s.currency == null ? "" : s.currency)
                    + (Double.isFinite(s.km) && s.km >= 1 ? " · " + HomeCost.money(s.cost / s.km) + " per km" : ""));
        }
        return out;
    }

    /** One line of the picture's event list. */
    static final class Row {
        /** 0 drive, 1 stop, 2 charge, 3 a note about the rest. */
        int kind;
        String title = "", detail = "", place = "";
        /** One line (a short stop) instead of three. */
        boolean compact;
    }

    /**
     * The picture's rows, at most {@code limit}: every drive, charge and stop in time order. Drives, charges and stops of a
     * minute or more take three lines (what, figures, GPS position); shorter stops take one.
     */
    static List<Row> rows(ManualTrip.Summary s, DateFormat clock, int limit) {
        List<Row> out = new ArrayList<>();
        DateFormat seconds = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        int omitted = 0;
        for (ManualTrip.Line l : s.lines) {
            if (out.size() >= limit - 1 && s.lines.size() > limit) { omitted++; continue; }
            Row r = new Row();
            String times = clock.format(new Date(l.start)) + (l.end > l.start ? " – " + clock.format(new Date(l.end)) : "");
            if ("drive".equals(l.kind)) {
                r.kind = 0;
                r.title = times + "  ·  Drive" + (Double.isFinite(l.km) ? "  ·  " + km(l.km) + " km" : "") + "  ·  " + duration(l.seconds)
                        + (l.share < 0.995 ? String.format(Locale.US, "  ·  %.0f%% in the trip", 100 * l.share) : "");
                StringBuilder d = new StringBuilder();
                if (Double.isFinite(l.avgKph)) d.append("avg ").append(Math.round(l.avgKph)).append(" km/h");
                if (Double.isFinite(l.peakKph)) d.append(d.length() > 0 ? " · " : "").append("top ").append(Math.round(l.peakKph)).append(" km/h");
                if (Double.isFinite(l.kwh)) d.append(d.length() > 0 ? " · " : "").append(String.format(Locale.US, "%.2f kWh", l.kwh));
                if (l.regenKwh >= 0.01) d.append(d.length() > 0 ? " · " : "").append(String.format(Locale.US, "regen ≈%.2f kWh", l.regenKwh));
                if (Double.isFinite(l.before) && Double.isFinite(l.after)) d.append(d.length() > 0 ? " · " : "").append("battery ").append(percent(l.before)).append(" → ").append(percent(l.after));
                if ("gps".equals(l.source)) d.append(d.length() > 0 ? " · " : "").append("GPS only");
                r.detail = d.toString();
                r.place = l.hasPlace() ? coordinates(l.lat, l.lon) + (l.hasEndPlace() ? "  →  " + coordinates(l.endLat, l.endLon) : "") : "";
            } else if ("stop".equals(l.kind) && l.seconds < SHORT_STOP_SECONDS) {
                r.kind = 1;
                r.compact = true;
                StringBuilder t = new StringBuilder(seconds.format(new Date(l.start))).append("  Stopped ").append(duration(l.seconds));
                if (l.idleKwh >= 0.0005) t.append(String.format(Locale.US, "  ·  idle ≈%.3f kWh", l.idleKwh));
                if (Double.isFinite(l.before)) t.append("  ·  ").append(percent(l.before));
                if (l.hasPlace()) t.append("  ·  ").append(coordinates(l.lat, l.lon));
                r.title = t.toString();
            } else if ("stop".equals(l.kind)) {
                r.kind = 1;
                r.title = times + "  ·  Stopped  ·  " + duration(l.seconds);
                StringBuilder d = new StringBuilder();
                if (l.idleKwh >= 0.005) d.append(String.format(Locale.US, "idle ≈%.2f kWh", l.idleKwh));
                if (Double.isFinite(l.before)) d.append(d.length() > 0 ? " · " : "").append("battery ").append(percent(l.before));
                r.detail = d.toString();
                r.place = l.hasPlace() ? coordinates(l.lat, l.lon) : "";
            } else {
                r.kind = 2;
                r.title = times + "  ·  Charging" + (Double.isFinite(l.chargedKwh) ? String.format(Locale.US, "  ·  +%.1f kWh", l.chargedKwh) : "")
                        + (l.chargedPoints > 0 ? String.format(Locale.US, "  ·  +%.0f%%", l.chargedPoints) : "");
                r.detail = Double.isFinite(l.before) && Double.isFinite(l.after) ? "battery " + percent(l.before) + " → " + percent(l.after) : "";
                r.place = l.hasPlace() ? coordinates(l.lat, l.lon) : "";
            }
            out.add(r);
        }
        if (omitted > 0) {
            Row r = new Row();
            r.kind = 3;
            r.compact = true;
            r.title = omitted + " more events are in the CSV file";
            out.add(r);
        }
        return out;
    }

    private static String q(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }

    private static String n(double value, int decimals) { return Double.isFinite(value) ? String.format(Locale.US, "%." + decimals + "f", value) : ""; }

    /** A CSV: the trip's totals as name,value rows, a blank line, then one row per event with its GPS position. */
    static String csv(ManualTrip.Summary s, long now) {
        DateFormat stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder b = new StringBuilder();
        b.append("Trip summary\n");
        row(b, "start", stamp.format(new Date(s.start)));
        row(b, "end", s.end > 0 ? stamp.format(new Date(s.end)) : "running");
        row(b, "from", s.from);
        row(b, "to", s.to);
        row(b, "duration_seconds", n(s.elapsedSeconds(now), 0));
        row(b, "distance_km", n(s.km, 2));
        row(b, "distance_gps_km", n(s.kmGps, 2));
        row(b, "drives", String.valueOf(s.drives));
        row(b, "moving_seconds", n(s.movingSeconds, 0));
        row(b, "idle_seconds", n(s.stoppedSeconds, 0));
        row(b, "stops", String.valueOf(s.stops));
        row(b, "stop_event_seconds", n(s.stopSeconds, 0));
        row(b, "average_speed_kph", n(s.averageKph, 1));
        row(b, "average_speed_with_stops_kph", n(s.overallKph, 1));
        row(b, "top_speed_kph", n(s.peakKph, 0));
        row(b, "energy_used_kwh", n(s.kwh, 3));
        row(b, "economy_kwh_per_100km", n(s.economy, 1));
        row(b, "idle_energy_kwh", n(s.idleKwh, 3));
        row(b, "regen_estimate_kwh", n(s.regenKwh, 3));
        row(b, "battery_start_percent", n(s.startSoc, 0));
        row(b, "battery_end_percent", n(s.endSoc, 0));
        row(b, "battery_used_points", n(s.batteryPoints, 1));
        row(b, "battery_used_percent_by_energy", n(s.batteryPercent, 1));
        row(b, "charges", String.valueOf(s.charges));
        row(b, "charged_kwh", n(s.chargedKwh, 2));
        row(b, "charged_points", n(s.chargedPoints, 0));
        row(b, "approx_cost", n(s.cost, 2));
        row(b, "currency", s.currency);
        b.append("\nEvents\n");
        b.append("kind,start,end,seconds,latitude,longitude,end_latitude,end_longitude,distance_km,energy_kwh,idle_kwh,regen_kwh,")
                .append("battery_start_percent,battery_end_percent,average_kph,top_kph,moving_seconds,charged_kwh,charged_points,part_of_event_in_trip,source\n");
        for (ManualTrip.Line l : s.lines) {
            b.append(l.kind).append(',').append(stamp.format(new Date(l.start))).append(',').append(l.end > 0 ? stamp.format(new Date(l.end)) : "").append(',')
                    .append(n(l.seconds, 0)).append(',')
                    .append(l.hasPlace() ? n(l.lat, 6) : "").append(',').append(l.hasPlace() ? n(l.lon, 6) : "").append(',')
                    .append(l.hasEndPlace() ? n(l.endLat, 6) : "").append(',').append(l.hasEndPlace() ? n(l.endLon, 6) : "").append(',')
                    .append(n(l.km, 2)).append(',').append(n(l.kwh, 3)).append(',').append(l.idleKwh > 0 ? n(l.idleKwh, 3) : "").append(',')
                    .append(l.regenKwh > 0 ? n(l.regenKwh, 3) : "").append(',').append(n(l.before, 0)).append(',').append(n(l.after, 0)).append(',')
                    .append(n(l.avgKph, 1)).append(',').append(n(l.peakKph, 0)).append(',').append(l.movingSeconds > 0 ? n(l.movingSeconds, 0) : "").append(',')
                    .append(n(l.chargedKwh, 2)).append(',').append("charge".equals(l.kind) ? n(l.chargedPoints, 0) : "").append(',')
                    .append(n(l.share, 3)).append(',').append(l.source).append('\n');
        }
        return b.toString();
    }

    private static void row(StringBuilder b, String name, String value) { b.append(name).append(',').append(q(value == null ? "" : value)).append('\n'); }
}
