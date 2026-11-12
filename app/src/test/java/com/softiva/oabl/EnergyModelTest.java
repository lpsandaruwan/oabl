package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The calculations and validity rules of energy-model.md §5–8, including the §12 edge cases. */
public class EnergyModelTest {
    private static final double E = 1e-9;

    private static EnergyModel.Reading reading(long time, double percent, EnergyModel.Charged charged, double km) {
        EnergyModel.Reading reading = new EnergyModel.Reading();
        reading.time = time;
        reading.percent = percent;
        reading.charged = charged;
        reading.meters = km * 1000;
        reading.activeSeconds = km * 60; // about 60 km/h
        return reading;
    }

    private static EnergyModel.Interval drive(double fromPercent, double toPercent, double km) {
        return EnergyModel.between(reading(1000, fromPercent, EnergyModel.Charged.NO, 0),
                reading(2000, toPercent, EnergyModel.Charged.NO, km), 0);
    }

    @Test public void formulasFollowTheSpecification() {
        EnergyModel.Profile profile = new EnergyModel.Profile();
        profile.usableKwh = 30; // entered by the driver, not a default
        EnergyModel.Result result = EnergyModel.evaluate(57.0, 17, profile);
        assertEquals(100 * 57.0 / 17, result.equivalentRangeKm, E);           // 100·D/p
        assertEquals(result.equivalentRangeKm / 330 * 100, result.performancePercent, E); // vs WLTP
        assertEquals(30 * 17 / 100.0, result.netKwh, E);                     // C·p/100
        assertEquals(30 * 17 / 57.0, result.kwhPer100Km, E);                 // C·p/D per 100 km
        assertEquals("WLTP 330 km", result.benchmark);
    }

    @Test public void noCapacityMeansNoKwh() {
        EnergyModel.Profile profile = new EnergyModel.Profile();
        assertFalse(profile.hasCapacity());
        EnergyModel.Result result = EnergyModel.evaluate(57, 17, profile);
        assertTrue(Double.isNaN(result.netKwh));
        assertTrue(Double.isNaN(result.kwhPer100Km));
        assertEquals(335.29, result.equivalentRangeKm, 0.01);
    }

    @Test public void capacityAddedLaterRecomputesFromRawRecords() {
        EnergyModel.Interval interval = drive(80, 63, 57);
        EnergyModel.Profile profile = new EnergyModel.Profile();
        assertTrue(Double.isNaN(EnergyModel.evaluate(interval.km, interval.drop, profile).netKwh));
        profile.usableKwh = 31;
        assertEquals(31 * 17 / 100.0, EnergyModel.evaluate(interval.km, interval.drop, profile).netKwh, E);
        assertEquals(80, interval.from.percent, E); // raw reading untouched
    }

    @Test public void benchmarksAreSeparateAndSwitchingOnlyRelabels() {
        EnergyModel.Profile profile = new EnergyModel.Profile();
        assertEquals(364, profile.km(EnergyModel.Benchmark.NEDC), E);
        assertEquals(430, profile.km(EnergyModel.Benchmark.CLTC), E);
        assertEquals(330, profile.km(EnergyModel.Benchmark.WLTP), E);
        assertTrue(Double.isNaN(profile.km(EnergyModel.Benchmark.PRACTICAL)));
        assertEquals(EnergyModel.Benchmark.WLTP, profile.benchmark());
        EnergyModel.Result wltp = EnergyModel.evaluate(66, 20, profile); // 330 km equivalent
        assertEquals(EnergyModel.Verdict.ON, wltp.verdict);
        profile.selected = EnergyModel.Benchmark.CLTC;
        EnergyModel.Result cltc = EnergyModel.evaluate(66, 20, profile);
        assertEquals(wltp.equivalentRangeKm, cltc.equivalentRangeKm, E);
        assertEquals(EnergyModel.Verdict.BELOW, cltc.verdict);
        assertEquals("CLTC 430 km", cltc.benchmark);
        // An empty practical figure falls back to WLTP instead of dividing by nothing.
        profile.selected = EnergyModel.Benchmark.PRACTICAL;
        assertEquals("WLTP 330 km", profile.benchmarkLabel());
        profile.practicalKm = 300;
        assertEquals("Practical 300 km", profile.benchmarkLabel());
        assertEquals(364, profile.nedcKm, E); // never converted
    }

    @Test public void verdictNeedsFivePointsAndUsesFivePercentBand() {
        EnergyModel.Profile profile = new EnergyModel.Profile(); // WLTP 330
        for (double drop = 1; drop < 5; drop++) {
            assertEquals(EnergyModel.Verdict.NOT_ENOUGH_DATA, EnergyModel.evaluate(3.3 * drop * 1.5, drop, profile).verdict);
        }
        assertEquals(EnergyModel.Verdict.ON, EnergyModel.evaluate(3.3 * 5 * 1.04, 5, profile).verdict);
        assertEquals(EnergyModel.Verdict.ABOVE, EnergyModel.evaluate(3.3 * 5 * 1.06, 5, profile).verdict);
        assertEquals(EnergyModel.Verdict.ON, EnergyModel.evaluate(3.3 * 5 * 0.96, 5, profile).verdict);
        assertEquals(EnergyModel.Verdict.ON, EnergyModel.verdict(5, 5.0));
        assertEquals(EnergyModel.Verdict.ON, EnergyModel.verdict(5, -5.0));
        assertEquals(EnergyModel.Verdict.BELOW, EnergyModel.evaluate(3.3 * 5 * 0.94, 5, profile).verdict);
        assertEquals(6.0, EnergyModel.evaluate(3.3 * 5 * 1.06, 5, profile).differencePercent, 1e-6);
    }

    @Test public void chargingRisingZeroAndMissingAreExcluded() {
        assertEquals(EnergyModel.Exclusion.BATTERY_ROSE, drive(50, 60, 20).exclusion);
        assertEquals(EnergyModel.Exclusion.NO_DROP, drive(50, 50, 20).exclusion);
        assertEquals(EnergyModel.Exclusion.MISSING_READING, drive(Double.NaN, 40, 20).exclusion);
        assertEquals(EnergyModel.Exclusion.MISSING_READING, drive(50, Double.NaN, 20).exclusion);
        EnergyModel.Interval charged = EnergyModel.between(reading(1, 40, EnergyModel.Charged.NO, 0),
                reading(2, 35, EnergyModel.Charged.YES, 20), 0);
        assertEquals(EnergyModel.Exclusion.CHARGED, charged.exclusion);
        EnergyModel.Interval unknown = EnergyModel.between(reading(1, 40, EnergyModel.Charged.NO, 0),
                reading(2, 35, EnergyModel.Charged.UNKNOWN, 20), 0);
        assertEquals(EnergyModel.Exclusion.CHARGE_UNKNOWN, unknown.exclusion);
        assertTrue(drive(50, 45, 20).learnable());
    }

    @Test public void parkedDrainIsNotDriving() {
        EnergyModel.Interval parked = drive(60, 59, 0.1);
        assertEquals(EnergyModel.Exclusion.PARKED, parked.exclusion);
    }

    @Test public void poorGpsIsExcluded() {
        EnergyModel.Reading a = reading(1, 80, EnergyModel.Charged.NO, 0);
        EnergyModel.Reading b = reading(2, 70, EnergyModel.Charged.NO, 30);
        b.unknownSeconds = 0.11 * (b.activeSeconds - a.activeSeconds); // tunnel: 11 % unknown
        assertEquals(EnergyModel.Exclusion.POOR_GPS, EnergyModel.between(a, b, 0).exclusion);
        b.unknownSeconds = 0.09 * (b.activeSeconds - a.activeSeconds);
        assertTrue(EnergyModel.between(a, b, 0).learnable());
        b.poorMeters = 0.06 * 30_000;
        assertEquals(EnergyModel.Exclusion.POOR_GPS, EnergyModel.between(a, b, 0).exclusion);
    }

    @Test public void learningResetExcludesEarlierIntervalsOnly() {
        EnergyModel.Reading a = reading(1000, 80, EnergyModel.Charged.NO, 0);
        EnergyModel.Reading b = reading(2000, 70, EnergyModel.Charged.NO, 33);
        assertEquals(EnergyModel.Exclusion.BEFORE_LEARNING_RESET, EnergyModel.between(a, b, 2500).exclusion);
        assertTrue(EnergyModel.between(a, b, 1500).learnable());
    }

    @Test public void resetDriveExcludesItsReadings() {
        EnergyModel.Reading a = reading(1, 80, EnergyModel.Charged.NO, 0);
        EnergyModel.Reading b = reading(2, 70, EnergyModel.Charged.NO, 33);
        b.excluded = true;
        assertEquals(EnergyModel.Exclusion.RESET, EnergyModel.between(a, b, 0).exclusion);
    }

    @Test public void allTimeIsSummedNotAveraged() {
        List<EnergyModel.Interval> intervals = Arrays.asList(
                drive(90, 80, 40),   // 400 km equivalent
                drive(80, 60, 50),   // 250 km equivalent
                drive(60, 65, 10));  // charging: excluded
        EnergyModel.AllTime all = EnergyModel.aggregate(intervals, 200);
        assertEquals(100 * 90 / 30.0, all.equivalentRangeKm(), E); // 300, not (400+250)/2 = 325
        assertEquals(90 / 30.0, all.learnedKmPerPoint(), E);
        assertEquals(90 / 200.0, all.coverage(), E);
        assertEquals(2, all.validIntervals);
        assertEquals(1, all.excludedIntervals);
        EnergyModel.Profile profile = new EnergyModel.Profile();
        assertTrue(Double.isNaN(all.kwhPer100Km(profile)));
        profile.usableKwh = 30;
        assertEquals(30 * 30 / 90.0, all.kwhPer100Km(profile), E);
    }

    @Test public void rangeUsesBenchmarkUntilTenPointsLearned() {
        EnergyModel.Profile profile = new EnergyModel.Profile();
        List<EnergyModel.Interval> few = new ArrayList<>();
        few.add(drive(80, 72, 40)); // 8 points only
        EnergyModel.AllTime all = EnergyModel.aggregate(few, 40);
        assertFalse(EnergyModel.learned(all));
        assertEquals((64 - 10) * 3.3, EnergyModel.rangeToReserveKm(64, profile, all), E);
        few.add(drive(72, 70, 6));
        all = EnergyModel.aggregate(few, 46);
        assertTrue(EnergyModel.learned(all));
        assertEquals((64 - 10) * 4.6, EnergyModel.rangeToReserveKm(64, profile, all), 1e-9);
        assertEquals(0, EnergyModel.rangeToReserveKm(8, profile, all), E); // below reserve: never negative
        assertEquals(64 - 23 / 4.6, EnergyModel.projectedPercent(64, 23, profile, all), 1e-9);
        assertTrue(Double.isNaN(EnergyModel.rangeToReserveKm(Double.NaN, profile, all)));
    }

    @Test public void idleKwhIsPowerTimesTime() {
        assertEquals(1.5, EnergyModel.idleKwh(3.0, 1800), E);
        assertEquals(0.3, EnergyModel.idleKwh(EnergyModel.IdleProfile.A.kw, 3600), E);
        assertEquals(EnergyModel.IdleProfile.D, new EnergyModel.Profile().idle); // default D
    }

    @Test public void formatting() {
        assertEquals("64", EnergyModel.percent(64));
        assertEquals("63.5", EnergyModel.percent(63.5));
        assertEquals("—", EnergyModel.percent(Double.NaN));
        assertEquals("+4.3 %", EnergyModel.signed(4.3));
    }
}
