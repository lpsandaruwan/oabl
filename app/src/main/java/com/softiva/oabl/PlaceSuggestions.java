package com.softiva.oabl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Learned destinations, like the "suggested destinations" of Tesla and Google Maps but from OABL's
 * own trip log only: places where drives often end around this time on this kind of day
 * (weekday or weekend). Nothing leaves the box. No Android dependencies, so it is unit tested.
 */
final class PlaceSuggestions {
    private PlaceSuggestions() { }

    /** Drive ends within this distance are the same place. */
    static final double SAME_PLACE_M = 250;
    /** A place this close to where the car is now isn't suggested. */
    static final double HERE_M = 500;
    static final int MIN_VISITS = 2;
    static final double MIN_SCORE = 1.0;

    static final class Visit {
        final double lat, lon;
        final long time;
        Visit(double lat, double lon, long time) { this.lat = lat; this.lon = lon; this.time = time; }
    }

    static final class Place {
        double lat, lon, score;
        int visits;
        long last;
        double metersFromHere = Double.NaN;
    }

    /**
     * Best first. A drive that ended on the same kind of day within 90 minutes of this time of day
     * counts 1; within 3 hours 0.5; any other visit 0.15. A place needs at least two visits and a
     * score of 1 (for example two drives there around this time on weekdays).
     */
    static List<Place> suggest(List<Visit> ends, long now, TimeZone zone, double hereLat, double hereLon,
                               boolean haveHere, int limit) {
        return suggest(ends, now, zone, hereLat, hereLon, haveHere, limit, true);
    }

    private static List<Place> suggest(List<Visit> ends, long now, TimeZone zone, double hereLat, double hereLon,
                                       boolean haveHere, int limit, boolean timed) {
        List<Place> places = new ArrayList<>();
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(now);
        boolean weekendNow = weekend(calendar);
        int minuteNow = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        for (Visit visit : ends) {
            if (visit.lat == 0 && visit.lon == 0) continue;
            Place match = null;
            double best = SAME_PLACE_M;
            for (Place place : places) {
                double d = GpsMotion.distance(place.lat, place.lon, visit.lat, visit.lon);
                if (d <= best) { best = d; match = place; }
            }
            if (match == null) {
                match = new Place();
                match.lat = visit.lat;
                match.lon = visit.lon;
                places.add(match);
            } else {
                // Running mean keeps the place at the middle of where you usually stop.
                match.lat += (visit.lat - match.lat) / (match.visits + 1);
                match.lon += (visit.lon - match.lon) / (match.visits + 1);
            }
            match.visits++;
            match.last = Math.max(match.last, visit.time);
            calendar.setTimeInMillis(visit.time);
            int minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
            int gap = Math.abs(minutes - minuteNow);
            gap = Math.min(gap, 24 * 60 - gap);
            boolean sameDay = weekend(calendar) == weekendNow;
            match.score += sameDay && gap <= 90 ? 1 : sameDay && gap <= 180 ? 0.5 : 0.15;
        }
        List<Place> result = new ArrayList<>();
        for (Place place : places) {
            if (place.visits < MIN_VISITS || (timed && place.score < MIN_SCORE)) continue;
            if (haveHere) {
                place.metersFromHere = GpsMotion.distance(hereLat, hereLon, place.lat, place.lon);
                if (place.metersFromHere < HERE_M) continue;
            }
            result.add(place);
        }
        result.sort((a, b) -> a.score != b.score ? Double.compare(b.score, a.score)
                : a.visits != b.visits ? Integer.compare(b.visits, a.visits) : Long.compare(b.last, a.last));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    /** Where drives end most often, whatever the time: candidates for Home and Work. */
    static List<Place> frequent(List<Visit> ends, int limit) {
        List<Place> all = suggest(ends, 0, TimeZone.getTimeZone("UTC"), 0, 0, false, Integer.MAX_VALUE, false);
        all.sort((a, b) -> a.visits != b.visits ? Integer.compare(b.visits, a.visits) : Long.compare(b.last, a.last));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    private static boolean weekend(Calendar calendar) {
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY;
    }

    /** "800 m" or "12 km". */
    static String distance(double meters) {
        if (Double.isNaN(meters)) return "";
        return meters < 1000 ? (Math.round(meters / 100.0) * 100) + " m" : Math.round(meters / 1000.0) + " km";
    }
}
