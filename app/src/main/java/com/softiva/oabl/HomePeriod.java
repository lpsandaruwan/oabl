package com.softiva.oabl;

import java.util.List;

/**
 * The period Home's Drive &amp; Energy card summarises: today, the last 7 days or the last 30 days.
 * Rolling windows ending today, so the card is never empty at the start of a week or a month.
 */
enum HomePeriod {
    DAY("Day", "today", 1),
    WEEK("Week", "7 days", 7),
    MONTH("Month", "30 days", 30),
    /** The trip the owner started by hand and ends by hand (ManualTrip); it has no fixed length. */
    TRIP("Trip", "trip", 0);

    /** The first three are calendar windows with daily data; TRIP is a separate view. */
    static final int WINDOWS = 3;

    /** Days kept for every figure: the longest period. */
    static final int HISTORY_DAYS = 30;
    private static final long DAY_MS = 86_400_000L;

    final String chip, label;
    final int days;

    HomePeriod(String chip, String label, int days) {
        this.chip = chip;
        this.label = label;
        this.days = days;
    }

    static HomePeriod fromIndex(int index) {
        HomePeriod[] all = values();
        return index >= 0 && index < all.length ? all[index] : WEEK;
    }

    /** What the card calls the window in a cell title: "Economy · 7 days". */
    String title(String name) {
        return name + " · " + label;
    }

    /** The newest {@code count} entries of an oldest-first daily array, summed; NaN entries are skipped. */
    static double sumTail(double[] daily, int count) {
        double total = 0;
        for (int i = Math.max(0, daily.length - count); i < daily.length; i++) if (Double.isFinite(daily[i])) total += daily[i];
        return total;
    }

    /** The newest {@code count} entries of an oldest-first daily array. */
    static double[] tail(double[] daily, int count) {
        int n = Math.min(count, daily.length);
        double[] out = new double[n];
        System.arraycopy(daily, daily.length - n, out, 0, n);
        return out;
    }

    static boolean[] tail(boolean[] daily, int count) {
        int n = Math.min(count, daily.length);
        boolean[] out = new boolean[n];
        System.arraycopy(daily, daily.length - n, out, 0, n);
        return out;
    }

    /**
     * Kilometres per day for the last {@code days} days, oldest first, a drive counted on the day it started.
     * {@code todayMidnight} is the start of today in the same clock as the trips.
     */
    static double[] dailyKm(List<TripComputer.Trip> trips, long todayMidnight, int days) {
        double[] out = new double[days];
        for (TripComputer.Trip trip : trips) {
            int ago = trip.start >= todayMidnight ? 0 : (int) Math.floor((todayMidnight - trip.start) / (double) DAY_MS) + 1;
            if (ago >= 0 && ago < days) out[days - 1 - ago] += trip.kilometers;
        }
        return out;
    }

    /**
     * Kilometres per hour of today (index 0 is midnight to 1 am): each drive's distance spread over the hours
     * it ran, in proportion to the time in each. A drive in progress ({@code end == 0}) runs to {@code now}.
     */
    static double[] hourlyKm(List<TripComputer.Trip> trips, long todayMidnight, long now) {
        double[] out = new double[24];
        long hour = 3_600_000L;
        for (TripComputer.Trip trip : trips) {
            long start = Math.max(trip.start, todayMidnight);
            long end = trip.end == 0 ? now : Math.min(trip.end, todayMidnight + 24 * hour);
            if (trip.start >= todayMidnight + 24 * hour || end <= todayMidnight || end < trip.start) continue;
            long whole = (trip.end == 0 ? now : trip.end) - trip.start;
            if (whole <= 0) {
                int h = (int) ((start - todayMidnight) / hour);
                if (h >= 0 && h < 24) out[h] += trip.kilometers;
                continue;
            }
            for (int h = (int) ((start - todayMidnight) / hour); h < 24; h++) {
                long from = Math.max(start, todayMidnight + h * hour), to = Math.min(end, todayMidnight + (h + 1) * hour);
                if (to <= from) { if (from >= end) break; continue; }
                out[h] += trip.kilometers * (to - from) / (double) whole;
            }
        }
        return out;
    }
}
