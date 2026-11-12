package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class PlaceSuggestionsTest {
    private static final TimeZone COLOMBO = TimeZone.getTimeZone("Asia/Colombo");
    private static final double HOME_LAT = 7.2906, HOME_LON = 80.6337;
    private static final double WORK_LAT = 7.3674, WORK_LON = 80.5030;   // ~16 km away
    private static final double GYM_LAT = 7.3354, GYM_LON = 80.5300;

    /** 2026-10-19 is a Monday. */
    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(COLOMBO);
        c.clear();
        c.set(2026, Calendar.OCTOBER, day, hour, minute);
        return c.getTimeInMillis();
    }

    @Test public void weekdayMorningSuggestsWorkNotTheGym() {
        List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        for (int day = 12; day <= 16; day++) ends.add(new PlaceSuggestions.Visit(WORK_LAT + 0.0005, WORK_LON, at(day, 8, 40)));
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(17, 18, 0)));   // Saturday evening
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(18, 18, 30)));  // Sunday evening
        List<PlaceSuggestions.Place> places = PlaceSuggestions.suggest(ends, at(19, 8, 15), COLOMBO, HOME_LAT, HOME_LON, true, 2);
        assertEquals(1, places.size());
        assertEquals(5, places.get(0).visits);
        assertEquals(16, places.get(0).metersFromHere / 1000, 1.5);
        assertEquals("17 km", PlaceSuggestions.distance(places.get(0).metersFromHere));
    }

    @Test public void weekendEveningSuggestsTheGym() {
        List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        for (int day = 12; day <= 16; day++) ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(day, 8, 40)));
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(10, 18, 0)));
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(11, 18, 30)));
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(17, 17, 45)));
        List<PlaceSuggestions.Place> places = PlaceSuggestions.suggest(ends, at(18, 17, 30), COLOMBO, HOME_LAT, HOME_LON, true, 2);
        assertEquals(1, places.size());
        assertEquals(GYM_LAT, places.get(0).lat, 0.001);
    }

    @Test public void oneVisitOrWhereYouAreIsNotSuggested() {
        List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(16, 8, 30)));
        assertTrue(PlaceSuggestions.suggest(ends, at(19, 8, 30), COLOMBO, HOME_LAT, HOME_LON, true, 2).isEmpty());
        ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(15, 8, 30)));
        assertEquals(1, PlaceSuggestions.suggest(ends, at(19, 8, 30), COLOMBO, HOME_LAT, HOME_LON, true, 2).size());
        // Already at work: nothing to suggest there.
        assertTrue(PlaceSuggestions.suggest(ends, at(19, 8, 30), COLOMBO, WORK_LAT, WORK_LON, true, 2).isEmpty());
    }

    @Test public void midnightWrapsAround() {
        List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(13, 23, 50)));
        ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(14, 23, 40)));
        // 00:10 is 20 minutes after 23:50, not 23 h 40 min before.
        assertEquals(1, PlaceSuggestions.suggest(ends, at(15, 0, 10), COLOMBO, HOME_LAT, HOME_LON, true, 2).size());
    }

    @Test public void frequentPlacesIgnoreTheTime() {
        List<PlaceSuggestions.Visit> ends = new ArrayList<>();
        for (int day = 12; day <= 16; day++) ends.add(new PlaceSuggestions.Visit(WORK_LAT, WORK_LON, at(day, 8, 40)));
        for (int day = 12; day <= 18; day++) ends.add(new PlaceSuggestions.Visit(HOME_LAT, HOME_LON, at(day, 18, 30)));
        ends.add(new PlaceSuggestions.Visit(GYM_LAT, GYM_LON, at(17, 18, 0)));
        List<PlaceSuggestions.Place> places = PlaceSuggestions.frequent(ends, 3);
        assertEquals(2, places.size()); // the gym once is not a place yet
        assertEquals(7, places.get(0).visits);
        assertEquals(HOME_LAT, places.get(0).lat, 1e-6);
        assertEquals(5, places.get(1).visits);
    }

    @Test public void distanceLabels() {
        assertEquals("800 m", PlaceSuggestions.distance(760));
        assertEquals("12 km", PlaceSuggestions.distance(12_400));
        assertEquals("", PlaceSuggestions.distance(Double.NaN));
    }
}
