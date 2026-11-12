package com.softiva.oabl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class HomePeriodTest {
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    private static final long MIDNIGHT = 1_700_000_000_000L - 1_700_000_000_000L % DAY;

    private static TripComputer.Trip trip(long start, long end, double km) {
        TripComputer.Trip trip = new TripComputer.Trip();
        trip.start = start;
        trip.end = end;
        trip.kilometers = km;
        return trip;
    }

    @Test public void periodsAreOneSevenAndThirtyDays() {
        assertEquals(1, HomePeriod.DAY.days);
        assertEquals(7, HomePeriod.WEEK.days);
        assertEquals(30, HomePeriod.MONTH.days);
        assertEquals(HomePeriod.HISTORY_DAYS, HomePeriod.MONTH.days);
        assertEquals("Economy · 7 days", HomePeriod.WEEK.title("Economy"));
        assertEquals("Cost · today", HomePeriod.DAY.title("Cost"));
    }

    @Test public void theTripIsTheFourthViewAndNotACalendarWindow() {
        assertEquals(HomePeriod.TRIP, HomePeriod.fromIndex(3));
        assertEquals(3, HomePeriod.WINDOWS);
        assertEquals(0, HomePeriod.TRIP.days);
        assertEquals(4, HomePeriod.values().length);
    }

    @Test public void anUnknownStoredIndexFallsBackToTheWeek() {
        assertEquals(HomePeriod.WEEK, HomePeriod.fromIndex(-1));
        assertEquals(HomePeriod.WEEK, HomePeriod.fromIndex(9));
        assertEquals(HomePeriod.MONTH, HomePeriod.fromIndex(2));
    }

    @Test public void tailsTakeTheNewestEntries() {
        double[] daily = {1, 2, 3, 4, 5};
        assertArrayEquals(new double[]{4, 5}, HomePeriod.tail(daily, 2), 0);
        assertArrayEquals(daily, HomePeriod.tail(daily, 30), 0);
        assertEquals(9, HomePeriod.sumTail(daily, 2), 1e-9);
        assertEquals(15, HomePeriod.sumTail(daily, 30), 1e-9);
        assertEquals(5, HomePeriod.sumTail(new double[]{Double.NaN, 5}, 2), 1e-9);
        assertArrayEquals(new boolean[]{false, true}, HomePeriod.tail(new boolean[]{true, false, true}, 2));
    }

    @Test public void dailyKmCountsADriveOnTheDayItStarted() {
        List<TripComputer.Trip> trips = Arrays.asList(
                trip(MIDNIGHT + 8 * HOUR, MIDNIGHT + 9 * HOUR, 10),
                trip(MIDNIGHT - 2 * HOUR, MIDNIGHT - HOUR, 4),
                trip(MIDNIGHT - 3 * DAY + HOUR, MIDNIGHT - 3 * DAY + 2 * HOUR, 7),
                trip(MIDNIGHT - 40 * DAY, MIDNIGHT - 40 * DAY + HOUR, 99));
        double[] week = HomePeriod.dailyKm(trips, MIDNIGHT, 7);
        assertEquals(7, week.length);
        assertEquals(10, week[6], 1e-9);
        assertEquals(4, week[5], 1e-9);
        assertEquals(7, week[3], 1e-9);
        double[] month = HomePeriod.dailyKm(trips, MIDNIGHT, 30);
        assertEquals(21, HomePeriod.sumTail(month, 30), 1e-9);
    }

    @Test public void hourlyKmSpreadsADriveOverTheHoursItRan() {
        // 8:30 to 10:30 is two hours: 30 min in hour 8, all of hour 9, 30 min in hour 10.
        double[] hours = HomePeriod.hourlyKm(Arrays.asList(trip(MIDNIGHT + 8 * HOUR + HOUR / 2, MIDNIGHT + 10 * HOUR + HOUR / 2, 40)),
                MIDNIGHT, MIDNIGHT + 12 * HOUR);
        assertEquals(24, hours.length);
        assertEquals(10, hours[8], 1e-9);
        assertEquals(20, hours[9], 1e-9);
        assertEquals(10, hours[10], 1e-9);
        assertEquals(40, HomePeriod.sumTail(hours, 24), 1e-9);
    }

    @Test public void aDriveInProgressRunsUpToNow() {
        double[] hours = HomePeriod.hourlyKm(Arrays.asList(trip(MIDNIGHT + 9 * HOUR, 0, 6)), MIDNIGHT, MIDNIGHT + 10 * HOUR);
        assertEquals(6, HomePeriod.sumTail(hours, 24), 1e-9);
        assertEquals(6, hours[9], 1e-9);
    }

    @Test public void aDriveFromYesterdayCountsOnlyItsShareOfToday() {
        // 23:00 yesterday to 01:00 today: half of the 8 km is today, all of it inside hour 0.
        double[] hours = HomePeriod.hourlyKm(Arrays.asList(trip(MIDNIGHT - HOUR, MIDNIGHT + HOUR, 8)), MIDNIGHT, MIDNIGHT + 5 * HOUR);
        assertEquals(4, HomePeriod.sumTail(hours, 24), 1e-9);
        assertEquals(4, hours[0], 1e-9);
    }

    @Test public void noTripsGiveZeroes() {
        List<TripComputer.Trip> none = new ArrayList<>();
        assertEquals(0, HomePeriod.sumTail(HomePeriod.hourlyKm(none, MIDNIGHT, MIDNIGHT + HOUR), 24), 0);
        assertEquals(0, HomePeriod.sumTail(HomePeriod.dailyKm(none, MIDNIGHT, 30), 30), 0);
    }
}
