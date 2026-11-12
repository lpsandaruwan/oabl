package com.softiva.oabl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Today, this week, this month and every past month, by the calendar — not from the day the data
 * started. Every figure is recomputed from the raw records, which are never deleted, so a month's
 * numbers can always be compared with any other month's. Free of Android, so it is tested on the JVM.
 *
 * <p>What a period shows: the money spent on charging in it (split home/public), the km driven, the
 * battery points used and ≈ kWh, the cost per km of that driving at your average charging price, and
 * the modelled idle time and kWh. Idle stays modelled and is never added to the battery figures.
 */
final class EnergyPeriods {
    private EnergyPeriods() { }

    /** A half-open time span [start, end) with a label, e.g. "Today" or "October 2026". */
    static final class Period implements java.io.Serializable {
        final long start, end;
        final String label;
        Period(long start, long end, String label) { this.start = start; this.end = end; this.label = label; }
        boolean contains(long time) { return time >= start && time < end; }
    }

    private static Calendar calendar(long time, TimeZone zone, Locale locale) {
        Calendar c = Calendar.getInstance(zone, locale);
        c.setTimeInMillis(time);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static Period today(long now, TimeZone zone, Locale locale) {
        Calendar c = calendar(now, zone, locale);
        long start = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 1);
        return new Period(start, c.getTimeInMillis(), "Today");
    }

    /** This calendar week, starting on the locale's first day of the week. */
    static Period week(long now, TimeZone zone, Locale locale) {
        Calendar c = calendar(now, zone, locale);
        int back = (c.get(Calendar.DAY_OF_WEEK) - c.getFirstDayOfWeek() + 7) % 7;
        c.add(Calendar.DAY_OF_MONTH, -back);
        long start = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 7);
        return new Period(start, c.getTimeInMillis(), "This week");
    }

    static Period month(long now, TimeZone zone, Locale locale) {
        Calendar c = calendar(now, zone, locale);
        c.set(Calendar.DAY_OF_MONTH, 1);
        long start = c.getTimeInMillis();
        String label = String.format(locale, "%tB %<tY", c);
        c.add(Calendar.MONTH, 1);
        return new Period(start, c.getTimeInMillis(), label);
    }

    /** Every calendar month from the one holding {@code earliest} to this one, newest first. */
    static List<Period> months(long earliest, long now, TimeZone zone, Locale locale) {
        List<Period> result = new ArrayList<>();
        Period current = month(now, zone, locale);
        long first = month(Math.min(earliest, now), zone, locale).start;
        for (Period p = current; p.start >= first; ) {
            result.add(p);
            p = month(p.start - 1, zone, locale);
            if (result.size() > 600) break; // 50 years: a guard, never reached
        }
        return result;
    }

    /** What happened in one period. */
    static final class Stats implements java.io.Serializable {
        Period period;
        double km, points, money, idleSeconds, idleKwh;
        int charges;
        /** kWh from the battery drop, when a usable capacity is entered; NaN otherwise. */
        double kwh = Double.NaN;
        /** Spend split home/public and AC/DC for the charges in this period. */
        EnergyModel.Charging charging;
        /** The period's driving at your average charging price, per km; NaN until it can be known. */
        double costPerKm = Double.NaN;
        /** kWh per 100 km from the battery drop, when a capacity is entered and some km were driven. */
        double kwhPer100Km() { return km > 0 && !Double.isNaN(kwh) ? kwh / km * 100 : Double.NaN; }
        boolean empty() { return km <= 0 && charges == 0 && idleSeconds <= 0; }
    }

    /**
     * The period's figures. Distance counts every interval that ended in it (the parked ones add
     * 0 km); battery points count only intervals valid for learning; charges count by when they were
     * logged; idle time is clipped to the period. {@code gridKwhPerPoint} and {@code rate} are the
     * all-time charging figures, since a single day often has no charge of its own.
     */
    static Stats of(Period period, List<EnergyModel.Interval> intervals, double kmSinceLastReading,
                    List<EnergyModel.Charge> charges, List<IdleLedger.Period> idle, EnergyModel.Profile profile,
                    double homeRate, double gridKwhPerPoint, double rate, long now) {
        Stats stats = new Stats();
        stats.period = period;
        for (EnergyModel.Interval interval : intervals) {
            if (!period.contains(interval.to.time)) continue;
            stats.km += interval.km;
            if (interval.learnable()) stats.points += interval.drop;
        }
        // The drive in progress: its distance since the last reading counts in the period holding now.
        if (period.contains(now) && kmSinceLastReading > 0) stats.km += kmSinceLastReading;
        if (profile.hasCapacity()) stats.kwh = profile.usableKwh * stats.points / 100;

        List<EnergyModel.Charge> inPeriod = new ArrayList<>();
        for (EnergyModel.Charge charge : charges) if (period.contains(charge.time)) inPeriod.add(charge);
        stats.charging = EnergyModel.charging(inPeriod, homeRate);
        stats.charges = inPeriod.size();
        stats.money = stats.charging.money;

        for (IdleLedger.Period p : idle) {
            long end = p.end == 0 ? now : p.end;
            long from = Math.max(p.start, period.start), to = Math.min(end, period.end);
            if (to <= from) continue;
            double seconds = (to - from) / 1000.0;
            stats.idleSeconds += seconds;
            stats.idleKwh += EnergyModel.idleKwh(p.kw, seconds);
        }

        if (stats.km > 0 && stats.points > 0 && EnergyModel.usable(gridKwhPerPoint) && !Double.isNaN(rate) && rate >= 0) {
            stats.costPerKm = stats.points * gridKwhPerPoint * rate / stats.km;
        }
        return stats;
    }
}
