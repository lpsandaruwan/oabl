package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;

/** Calendar periods (not from the first record), period stats, idle clipped at a boundary, cost per km. */
public class EnergyPeriodsTest {
    private static final TimeZone COLOMBO = TimeZone.getTimeZone("Asia/Colombo");
    private static final Locale LOCALE = Locale.UK; // weeks start on Monday
    private static final double E = 1e-9;

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(COLOMBO, LOCALE);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static EnergyModel.Reading reading(long time, double percent, double km) {
        EnergyModel.Reading r = new EnergyModel.Reading();
        r.time = time;
        r.percent = percent;
        r.charged = EnergyModel.Charged.NO;
        r.meters = km * 1000;
        r.activeSeconds = km * 60;
        return r;
    }

    private static EnergyModel.Interval drive(long start, long end, double from, double to, double startKm, double endKm) {
        return EnergyModel.between(reading(start, from, startKm), reading(end, to, endKm), 0);
    }

    @Test public void periodsFollowTheCalendar() {
        long now = at(2026, 10, 20, 12, 0); // a Tuesday
        EnergyPeriods.Period today = EnergyPeriods.today(now, COLOMBO, LOCALE);
        assertEquals(at(2026, 10, 20, 0, 0), today.start);
        assertEquals(at(2026, 10, 21, 0, 0), today.end);
        EnergyPeriods.Period week = EnergyPeriods.week(now, COLOMBO, LOCALE);
        assertEquals("week starts Monday 19 October", at(2026, 10, 19, 0, 0), week.start);
        assertEquals(at(2026, 10, 26, 0, 0), week.end);
        EnergyPeriods.Period month = EnergyPeriods.month(now, COLOMBO, LOCALE);
        assertEquals("the 1st, not the day the data started", at(2026, 10, 1, 0, 0), month.start);
        assertEquals(at(2026, 11, 1, 0, 0), month.end);
        assertEquals("October 2026", month.label);
    }

    @Test public void monthsAreNewestFirstAcrossAYear() {
        List<EnergyPeriods.Period> months = EnergyPeriods.months(at(2025, 12, 10, 9, 0), at(2026, 2, 3, 9, 0), COLOMBO, LOCALE);
        assertEquals(3, months.size());
        assertEquals("February 2026", months.get(0).label);
        assertEquals("January 2026", months.get(1).label);
        assertEquals("December 2025", months.get(2).label);
    }

    @Test public void statsCountOnlyWhatFallsInThePeriod() {
        long now = at(2026, 10, 20, 12, 0);
        EnergyPeriods.Period october = EnergyPeriods.month(now, COLOMBO, LOCALE);
        List<EnergyModel.Interval> intervals = Arrays.asList(
                drive(at(2026, 9, 28, 8, 0), at(2026, 9, 28, 9, 0), 80, 74, 0, 20),     // September
                drive(at(2026, 10, 5, 8, 0), at(2026, 10, 5, 9, 0), 74, 68, 20, 40));   // October
        EnergyModel.Charge octCharge = new EnergyModel.Charge();
        octCharge.time = at(2026, 10, 6, 20, 0); octCharge.kwh = 22; octCharge.cost = 1320;
        octCharge.place = EnergyModel.Place.HOME; octCharge.current = EnergyModel.Current.AC;
        EnergyModel.Charge sepCharge = new EnergyModel.Charge();
        sepCharge.time = at(2026, 9, 29, 20, 0); sepCharge.kwh = 28; sepCharge.cost = 2800;
        EnergyModel.Profile profile = new EnergyModel.Profile();
        profile.usableKwh = 40;

        EnergyPeriods.Stats s = EnergyPeriods.of(october, intervals, 0, Arrays.asList(octCharge, sepCharge),
                Collections.emptyList(), profile, Double.NaN, 0.45, 60, now);
        assertEquals(20, s.km, 1e-6);
        assertEquals(6, s.points, E);
        assertEquals("C × points ÷ 100", 2.4, s.kwh, 1e-9);
        assertEquals(12, s.kwhPer100Km(), 1e-6);
        assertEquals("only October's charge", 1, s.charges);
        assertEquals(1320, s.money, E);
        assertEquals(1320, s.charging.home.money, E);
        assertEquals("points × grid kWh per point × rate ÷ km", 6 * 0.45 * 60 / 20, s.costPerKm, 1e-9);
    }

    @Test public void idleIsClippedToThePeriod() {
        long now = at(2026, 10, 20, 12, 0);
        IdleLedger.Period across = new IdleLedger.Period();
        across.start = at(2026, 9, 30, 23, 0);
        across.end = at(2026, 10, 1, 1, 0); // 2 h, 1 h of it in October
        across.kw = 3.0;
        EnergyPeriods.Stats oct = EnergyPeriods.of(EnergyPeriods.month(now, COLOMBO, LOCALE), Collections.emptyList(), 0,
                Collections.emptyList(), Collections.singletonList(across), new EnergyModel.Profile(), Double.NaN, Double.NaN, Double.NaN, now);
        assertEquals(3600, oct.idleSeconds, 1e-6);
        assertEquals("3 kW × 1 h, modelled", 3.0, oct.idleKwh, 1e-9);
    }

    @Test public void theDriveInProgressCountsOnlyInThePeriodHoldingNow() {
        long now = at(2026, 10, 20, 12, 0);
        EnergyModel.Profile profile = new EnergyModel.Profile();
        EnergyPeriods.Stats today = EnergyPeriods.of(EnergyPeriods.today(now, COLOMBO, LOCALE), Collections.emptyList(), 7.5,
                Collections.emptyList(), Collections.emptyList(), profile, Double.NaN, Double.NaN, Double.NaN, now);
        assertEquals(7.5, today.km, E);
        EnergyPeriods.Period september = EnergyPeriods.month(at(2026, 9, 10, 0, 0), COLOMBO, LOCALE);
        EnergyPeriods.Stats sep = EnergyPeriods.of(september, Collections.emptyList(), 7.5,
                Collections.emptyList(), Collections.emptyList(), profile, Double.NaN, Double.NaN, Double.NaN, now);
        assertEquals(0, sep.km, E);
        assertTrue(sep.empty());
        assertTrue("no capacity: no kWh, never assumed", Double.isNaN(today.kwh));
        assertFalse(today.empty());
    }
}
