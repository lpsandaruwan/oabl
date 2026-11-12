package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Manual mode, improved (energy-companion.md §2): the charge log and what it unlocks, parked drain,
 * and the car's odometer and trip computer beside OABL's own figures.
 */
public class EnergyManualModeTest {
    private static final double E = 1e-9;
    private static final long HOUR = 3_600_000L;

    private static EnergyModel.Charge charge(double before, double after, double kwh) {
        EnergyModel.Charge charge = new EnergyModel.Charge();
        charge.before = before;
        charge.after = after;
        charge.kwh = kwh;
        return charge;
    }

    private static EnergyModel.Reading reading(long time, double percent, EnergyModel.Charged charged, double km, double activeSeconds) {
        EnergyModel.Reading reading = new EnergyModel.Reading();
        reading.time = time;
        reading.percent = percent;
        reading.charged = charged;
        reading.meters = km * 1000;
        reading.activeSeconds = activeSeconds;
        return reading;
    }

    /** End of a drive, then the next check-in after this many hours with the counters unchanged. */
    private static EnergyModel.Interval parked(double end, double next, double hours, EnergyModel.Charged charged) {
        return EnergyModel.between(reading(0, end, EnergyModel.Charged.NO, 20, 1200),
                reading(Math.round(hours * HOUR), next, charged, 20, 1200), 0);
    }

    // ---- Charge log ----

    @Test public void capacityFromChargingIsKwhOverPointsGained() {
        EnergyModel.Charge one = charge(20, 80, 26.4); // 60 points
        assertEquals(60, one.gain(), E);
        assertTrue(one.countsForCapacity());
        EnergyModel.Charging all = EnergyModel.charging(Collections.singletonList(one), Double.NaN);
        assertEquals(26.4 / 60, all.gridKwhPerPoint(), E);
        assertEquals(26.4 / (60 / 100.0), all.capacityUpperBoundKwh(), E); // kWh ÷ (% gained ÷ 100) = 44 kWh
    }

    @Test public void capacityIsSummedNotAveraged() {
        List<EnergyModel.Charge> log = Arrays.asList(charge(30, 50, 10), charge(10, 90, 32));
        EnergyModel.Charging all = EnergyModel.charging(log, Double.NaN);
        assertEquals(100 * 42 / 100.0, all.capacityUpperBoundKwh(), E); // 42 kWh over 100 points
        assertEquals(2, all.capacityCharges);
        // Not (50 + 40) / 2 = 45.
        assertFalse(Math.abs(all.capacityUpperBoundKwh() - 45) < 0.5);
    }

    @Test public void smallOrIncompleteChargesDoNotCountForCapacity() {
        EnergyModel.Charge small = charge(70, 79, 4); // 9 points: rounding would dominate
        EnergyModel.Charge noKwh = charge(20, 80, Double.NaN);
        EnergyModel.Charge noBefore = charge(Double.NaN, 80, 20);
        EnergyModel.Charge fell = charge(80, 70, 2); // typed the wrong way round
        for (EnergyModel.Charge c : Arrays.asList(small, noKwh, noBefore, fell)) assertFalse(c.countsForCapacity());
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(small, noKwh, noBefore, fell), Double.NaN);
        assertEquals(4, all.charges);
        assertEquals(0, all.capacityCharges);
        assertTrue(Double.isNaN(all.gridKwhPerPoint()));
        assertTrue(Double.isNaN(all.capacityUpperBoundKwh()));
        assertEquals(26, all.kwh, E); // still totalled
    }

    @Test public void capacityNeedsTwentyPointsInAll() {
        EnergyModel.Charging one = EnergyModel.charging(Collections.singletonList(charge(60, 75, 6.6)), Double.NaN);
        assertEquals(1, one.capacityCharges);
        assertTrue(Double.isNaN(one.capacityUpperBoundKwh())); // 15 points so far
        EnergyModel.Charging two = EnergyModel.charging(Arrays.asList(charge(60, 75, 6.6), charge(40, 52, 5.4)), Double.NaN);
        assertEquals(100 * 12.0 / 27, two.capacityUpperBoundKwh(), 1e-9);
    }

    @Test public void moneyComesFromCostThenRateThenHomeRate() {
        EnergyModel.Charge paid = charge(20, 80, 25);
        paid.cost = 1500;
        paid.rate = 99; // the total paid wins over a rate typed as well
        assertEquals(1500, paid.money(60), E);
        EnergyModel.Charge rated = charge(20, 80, 25);
        rated.rate = 50;
        assertEquals(1250, rated.money(60), E);
        EnergyModel.Charge home = charge(20, 80, 25);
        home.place = EnergyModel.Place.HOME;
        assertTrue(home.atHomeRate(60));
        assertEquals(1500, home.money(60), E);
        assertTrue(Double.isNaN(home.money(Double.NaN))); // no home rate set: no cost
        EnergyModel.Charge publicUnpriced = charge(20, 80, 25);
        publicUnpriced.place = EnergyModel.Place.PUBLIC;
        assertTrue(Double.isNaN(publicUnpriced.money(60))); // the home rate never prices a public charge
        EnergyModel.Charge rateNoKwh = charge(20, 80, Double.NaN);
        rateNoKwh.rate = 50;
        assertTrue(Double.isNaN(rateNoKwh.money(60)));
        EnergyModel.Charge free = charge(20, 80, 25);
        free.cost = 0;
        assertEquals(0, free.money(60), E); // a free charger is a real price
    }

    @Test public void rateAndCostPer100KmFollowTheFormula() {
        EnergyModel.Charge a = charge(20, 80, 27); // 0.45 kWh per point
        a.cost = 1620;                            // 60 per kWh
        EnergyModel.Charge b = charge(30, 70, 18); // 0.45 kWh per point
        b.rate = 40;                              // 720
        EnergyModel.Charging all = EnergyModel.charging(Arrays.asList(a, b), Double.NaN);
        assertEquals(0.45, all.gridKwhPerPoint(), E);
        assertEquals((1620 + 720) / 45.0, all.ratePerKwh(), E); // Σ money ÷ Σ kWh = 52
        assertEquals(2, all.pricedCharges);
        double kmPerPoint = 3.3; // WLTP 330 ÷ 100
        double grid = 0.45 * 100 / 3.3;
        assertEquals(grid, all.gridKwhPer100Km(kmPerPoint), E); // 13.6 kWh/100 km from the grid
        assertEquals(grid * 52, all.costPer100Km(kmPerPoint), 1e-9);
        assertEquals(2340, all.money, E);
        assertEquals(45, all.kwh, E);
    }

    @Test public void costPer100KmNeedsAPriceAndAGain() {
        EnergyModel.Charging unpriced = EnergyModel.charging(Collections.singletonList(charge(20, 80, 27)), Double.NaN);
        assertTrue(Double.isNaN(unpriced.ratePerKwh()));
        assertTrue(Double.isNaN(unpriced.costPer100Km(3.3)));
        assertEquals(0.45 * 100 / 3.3, unpriced.gridKwhPer100Km(3.3), E);
        EnergyModel.Charge pricedNoGain = charge(Double.NaN, Double.NaN, 27);
        pricedNoGain.cost = 1000;
        EnergyModel.Charging noGain = EnergyModel.charging(Collections.singletonList(pricedNoGain), Double.NaN);
        assertEquals(1000 / 27.0, noGain.ratePerKwh(), E);
        assertTrue(Double.isNaN(noGain.gridKwhPer100Km(3.3)));
        assertTrue(Double.isNaN(noGain.costPer100Km(3.3)));
        assertTrue(Double.isNaN(EnergyModel.charging(new ArrayList<>(), Double.NaN).costPer100Km(3.3)));
        assertTrue(Double.isNaN(unpriced.gridKwhPer100Km(Double.NaN)));
    }

    @Test public void homeRateIsCountedSeparately() {
        EnergyModel.Charge home = charge(20, 80, 30);
        home.place = EnergyModel.Place.HOME;
        EnergyModel.Charging all = EnergyModel.charging(Collections.singletonList(home), 50);
        assertEquals(1, all.homeRateCharges);
        assertEquals(50, all.ratePerKwh(), E);
        assertEquals(1500, all.money, E);
    }

    @Test public void anEmptyEntryIsRecognised() {
        assertTrue(new EnergyModel.Charge().empty());
        EnergyModel.Charge dc = new EnergyModel.Charge();
        dc.current = EnergyModel.Current.DC;
        assertFalse(dc.empty());
    }

    // ---- Parked drain ----

    @Test public void parkedDrainIsPointsPerDay() {
        EnergyModel.Parked night = EnergyModel.parked(parked(74, 72, 12, EnergyModel.Charged.NO));
        assertTrue(night.counted());
        assertEquals(4, night.pointsPerDay(), E); // 2 points over 12 h
        EnergyModel.Parked weekend = EnergyModel.parked(parked(72, 69, 36, EnergyModel.Charged.NO));
        EnergyModel.Drain drain = EnergyModel.drain(Arrays.asList(night, weekend));
        assertEquals(24 * 5 / 48.0, drain.pointsPerDay(), E); // summed: 2.5, not (4 + 2) / 2 = 3
        assertEquals(2, drain.periods);
        EnergyModel.Profile profile = new EnergyModel.Profile();
        assertTrue(Double.isNaN(drain.kwhPerDay(profile))); // no capacity entered: no kWh
        profile.usableKwh = 40;
        assertEquals(2.5 * 0.4, drain.kwhPerDay(profile), E);
    }

    @Test public void parkedDrainOnlyWhenNotCharged() {
        assertEquals(EnergyModel.Exclusion.CHARGED, EnergyModel.parked(parked(40, 90, 10, EnergyModel.Charged.YES)).exclusion);
        assertEquals(EnergyModel.Exclusion.CHARGE_UNKNOWN, EnergyModel.parked(parked(40, 39, 10, EnergyModel.Charged.UNKNOWN)).exclusion);
        assertEquals(EnergyModel.Exclusion.BATTERY_ROSE, EnergyModel.parked(parked(40, 41, 10, EnergyModel.Charged.NO)).exclusion);
        assertEquals(EnergyModel.Exclusion.MISSING_READING, EnergyModel.parked(parked(Double.NaN, 41, 10, EnergyModel.Charged.NO)).exclusion);
        assertTrue(EnergyModel.parked(parked(40, 40, 10, EnergyModel.Charged.NO)).counted()); // no drain is a result
    }

    @Test public void shortParksAndTooLittleTimeShowNothing() {
        EnergyModel.Parked lunch = EnergyModel.parked(parked(60, 59, 2, EnergyModel.Charged.NO));
        assertEquals(EnergyModel.Exclusion.SHORT_PARK, lunch.exclusion);
        EnergyModel.Parked night = EnergyModel.parked(parked(59, 58, 10, EnergyModel.Charged.NO));
        EnergyModel.Drain drain = EnergyModel.drain(Arrays.asList(lunch, night));
        assertEquals(1, drain.periods);
        assertEquals(1, drain.excluded);
        assertTrue(Double.isNaN(drain.pointsPerDay())); // 10 h so far, needs 24 h
    }

    @Test public void aDriveIsNotAParkedPeriod() {
        EnergyModel.Interval drive = EnergyModel.between(reading(0, 80, EnergyModel.Charged.NO, 0, 0),
                reading(10 * HOUR, 74, EnergyModel.Charged.NO, 20, 1200), 0);
        assertNull(EnergyModel.parked(drive));
        // Standing inside a journey (READY, bench use) is idle time, not parked drain.
        EnergyModel.Interval standing = EnergyModel.between(reading(0, 80, EnergyModel.Charged.NO, 5, 300),
                reading(8 * HOUR, 78, EnergyModel.Charged.NO, 5, 300 + 8 * 3600), 0);
        assertNull(EnergyModel.parked(standing));
    }

    @Test public void resetDrivesAreNotCountedForDrain() {
        EnergyModel.Interval interval = parked(70, 69, 10, EnergyModel.Charged.NO);
        interval.from.excluded = true;
        assertEquals(EnergyModel.Exclusion.RESET, EnergyModel.parked(EnergyModel.between(interval.from, interval.to, 0)).exclusion);
    }

    // ---- Odometer and trip computer ----

    @Test public void odometerDistanceNeedsBothEnds() {
        EnergyModel.Reading a = reading(0, 80, EnergyModel.Charged.NO, 0, 0);
        EnergyModel.Reading b = reading(HOUR, 74, EnergyModel.Charged.NO, 19.6, 1200);
        assertTrue(Double.isNaN(EnergyModel.between(a, b, 0).odometerKm));
        a.odometerKm = 41210;
        b.odometerKm = 41230.4;
        EnergyModel.Interval interval = EnergyModel.between(a, b, 0);
        assertEquals(20.4, interval.odometerKm, 1e-9);
        assertEquals(19.6, interval.km, E); // D stays the GPS distance
        assertTrue(interval.learnable());
        b.odometerKm = 41200; // went backwards: a typing slip, not a distance
        assertTrue(Double.isNaN(EnergyModel.between(a, b, 0).odometerKm));
    }

    @Test public void gpsIsComparedWithTheOdometerOverGoodDrivesOnly() {
        EnergyModel.Reading a = reading(0, 80, EnergyModel.Charged.NO, 0, 0);
        a.odometerKm = 1000;
        EnergyModel.Reading b = reading(HOUR, 74, EnergyModel.Charged.NO, 19.6, 1200);
        b.odometerKm = 1020;
        EnergyModel.Reading c = reading(2 * HOUR, 70, EnergyModel.Charged.YES, 49, 3000);
        c.odometerKm = 1050; // charged in between: the distance still counts
        EnergyModel.Reading d = reading(3 * HOUR, 66, EnergyModel.Charged.NO, 59, 4200);
        d.unknownSeconds = 600; // tunnel: half the drive without GPS
        d.odometerKm = 1070;
        List<EnergyModel.Interval> intervals = Arrays.asList(EnergyModel.between(a, b, 0),
                EnergyModel.between(b, c, 0), EnergyModel.between(c, d, 0));
        EnergyModel.Distances distances = EnergyModel.distances(intervals);
        assertEquals(2, distances.intervals);
        assertEquals(19.6 + 29.4, distances.gpsKm, 1e-9);
        assertEquals(50, distances.odometerKm, E);
        assertEquals((49 / 50.0 - 1) * 100, distances.gpsDifferencePercent(), 1e-9); // GPS 2 % short
        assertTrue(Double.isNaN(EnergyModel.distances(new ArrayList<>()).gpsDifferencePercent()));
    }

    @Test public void tripComputerFiguresTravelWithTheReading() {
        EnergyModel.Reading end = reading(HOUR, 74, EnergyModel.Charged.NO, 20, 1200);
        end.tripKm = 20.6;
        end.tripKwhPer100Km = 14.1;
        EnergyModel.Interval interval = EnergyModel.between(reading(0, 80, EnergyModel.Charged.NO, 0, 0), end, 0);
        assertEquals(14.1, interval.to.tripKwhPer100Km, E);
        // OABL's own figure is computed exactly as before, never from the car's.
        EnergyModel.Profile profile = new EnergyModel.Profile();
        profile.usableKwh = 40;
        assertEquals(40 * 6 / 20.0, EnergyModel.evaluate(interval.km, interval.drop, profile).kwhPer100Km, E);
    }
}
