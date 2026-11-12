package com.softiva.oabl;

import java.util.List;
import java.util.Locale;

/**
 * The EV energy model, free of Android so it can be unit tested on the JVM. It works only from what
 * OABL can observe: GPS distance and battery percentages the driver types in. The box's own
 * battery is never the car's. See docs/ev-cockpit/energy-model.md.
 */
final class EnergyModel {
    private EnergyModel() { }

    /** A verdict needs at least this many battery points of drop. */
    static final double MIN_VERDICT_DROP = 5;
    /** Within this many percent of the benchmark is "on benchmark". */
    static final double ON_BAND_PERCENT = 5;
    /** The learned km per point replaces the benchmark once it rests on this much drop. */
    static final double MIN_LEARNED_DROP = 10;
    static final double MAX_UNKNOWN_FRACTION = 0.10;
    static final double MAX_POOR_FRACTION = 0.05;
    /** Below this an interval is parked time, not driving. */
    static final double MIN_INTERVAL_KM = 0.5;

    enum Benchmark {
        NEDC("NEDC"), CLTC("CLTC"), WLTP("WLTP"), PRACTICAL("Practical");
        final String label;
        Benchmark(String label) { this.label = label; }
    }

    /** Total idle load, one at a time and never added together. Modelled, not measured. */
    enum IdleProfile {
        A(0.3, "READY, AC off"),
        B(0.4, "READY, screen or music on"),
        C(1.2, "AC holding about 25 °C"),
        D(3.0, "AC maximum cooling");
        final double kw;
        final String label;
        IdleProfile(double kw, String label) { this.kw = kw; this.label = label; }
        String title() { return name() + " · " + label + " · " + kw + " kW"; }
    }

    enum Charged { YES, NO, UNKNOWN }

    enum Verdict { ABOVE, ON, BELOW, NOT_ENOUGH_DATA }

    /** Why an interval is not used for learning. NONE means it is. */
    enum Exclusion {
        NONE(""),
        CHARGED("charged in between"),
        CHARGE_UNKNOWN("charging not known"),
        MISSING_READING("battery reading missing"),
        BATTERY_ROSE("battery rose"),
        NO_DROP("no battery drop"),
        PARKED("parked, no driving"),
        POOR_GPS("poor GPS"),
        RESET("drive reset"),
        BEFORE_LEARNING_RESET("before the learning reset"),
        /** Parked drain only: too short for whole-point readings to mean anything. */
        SHORT_PARK("parked under 6 h");
        final String reason;
        Exclusion(String reason) { this.reason = reason; }
    }

    /** The vehicle profile. Benchmarks are separate figures and are never converted into each other. */
    static final class Profile implements java.io.Serializable {
        double nedcKm = 364, cltcKm = 430, wltpKm = 330, practicalKm = Double.NaN;
        Benchmark selected = Benchmark.WLTP;
        /** kWh the car can use. Never assumed: NaN until the driver enters it. */
        double usableKwh = Double.NaN;
        /** Nameplate capacity, shown for reference and never used in a calculation. */
        double grossKwh = Double.NaN;
        double reservePercent = 10;
        IdleProfile idle = IdleProfile.D;

        double km(Benchmark benchmark) {
            switch (benchmark) {
                case NEDC: return nedcKm;
                case CLTC: return cltcKm;
                case PRACTICAL: return practicalKm;
                default: return wltpKm;
            }
        }

        /** The selected benchmark, or WLTP when the selected one has no figure. */
        Benchmark benchmark() { return usable(km(selected)) ? selected : Benchmark.WLTP; }
        double benchmarkKm() { return km(benchmark()); }
        String benchmarkLabel() { return benchmark().label + " " + Math.round(benchmarkKm()) + " km"; }
        boolean hasCapacity() { return usable(usableKwh); }
    }

    /** Figures read off the car's own displays and typed in with a check-in. NaN when not entered. */
    static final class CarFigures implements java.io.Serializable {
        double odometerKm = Double.NaN, tripKm = Double.NaN, tripKwhPer100Km = Double.NaN;
    }

    /** A battery check-in with the GPS counters at that moment (counters only advance in a session). */
    static final class Reading implements java.io.Serializable {
        long id, time;
        /** NaN when the driver didn't know. */
        double percent = Double.NaN;
        Charged charged = Charged.UNKNOWN;
        boolean excluded;
        double meters, activeSeconds, unknownSeconds, poorMeters;
        /** Optional, as entered from the car's displays; NaN when not entered. */
        double odometerKm = Double.NaN, tripKm = Double.NaN, tripKwhPer100Km = Double.NaN;
    }

    /** What happened between two consecutive check-ins. */
    static final class Interval implements java.io.Serializable {
        Reading from, to;
        double km, drop, activeSeconds, unknownSeconds, poorKm, hours;
        /** The car's odometer distance, when both check-ins have one; never used as D. */
        double odometerKm = Double.NaN;
        Exclusion exclusion = Exclusion.NONE;
        boolean learnable() { return exclusion == Exclusion.NONE; }
    }

    static Interval between(Reading a, Reading b, long learningSince) {
        Interval interval = new Interval();
        interval.from = a;
        interval.to = b;
        interval.km = Math.max(0, b.meters - a.meters) / 1000.0;
        interval.activeSeconds = Math.max(0, b.activeSeconds - a.activeSeconds);
        interval.unknownSeconds = Math.max(0, b.unknownSeconds - a.unknownSeconds);
        interval.poorKm = Math.max(0, b.poorMeters - a.poorMeters) / 1000.0;
        interval.hours = Math.max(0, b.time - a.time) / 3_600_000.0;
        interval.drop = Double.isNaN(a.percent) || Double.isNaN(b.percent) ? Double.NaN : a.percent - b.percent;
        interval.odometerKm = odometerKm(a.odometerKm, b.odometerKm);
        interval.exclusion = exclusion(interval, learningSince);
        return interval;
    }

    /** b − a, or NaN when either is missing or the odometer went backwards (a typing slip). */
    static double odometerKm(double a, double b) {
        return Double.isNaN(a) || Double.isNaN(b) || b < a ? Double.NaN : b - a;
    }

    /** Unknown-GPS time and poor-GPS distance within the §5 limits. */
    static boolean gpsGood(Interval i) {
        return i.unknownSeconds <= MAX_UNKNOWN_FRACTION * i.activeSeconds && i.poorKm <= MAX_POOR_FRACTION * i.km;
    }

    private static Exclusion exclusion(Interval i, long learningSince) {
        if (i.to.charged == Charged.YES) return Exclusion.CHARGED;
        if (i.to.charged == Charged.UNKNOWN) return Exclusion.CHARGE_UNKNOWN;
        if (Double.isNaN(i.drop)) return Exclusion.MISSING_READING;
        if (i.from.excluded || i.to.excluded) return Exclusion.RESET;
        if (i.drop < 0) return Exclusion.BATTERY_ROSE;
        if (i.drop == 0) return Exclusion.NO_DROP;
        if (i.km < MIN_INTERVAL_KM) return Exclusion.PARKED;
        if (!gpsGood(i)) return Exclusion.POOR_GPS;
        if (i.to.time < learningSince) return Exclusion.BEFORE_LEARNING_RESET;
        return Exclusion.NONE;
    }

    /** Results for a distance over a battery drop, against the profile's selected benchmark. */
    static final class Result implements java.io.Serializable {
        double km, drop;
        double equivalentRangeKm = Double.NaN, performancePercent = Double.NaN, differencePercent = Double.NaN;
        double netKwh = Double.NaN, kwhPer100Km = Double.NaN;
        Verdict verdict = Verdict.NOT_ENOUGH_DATA;
        String benchmark;
    }

    static Result evaluate(double km, double drop, Profile profile) {
        Result result = new Result();
        result.km = km;
        result.drop = drop;
        result.benchmark = profile.benchmarkLabel();
        if (!(drop > 0) || !(km > 0)) return result;
        result.equivalentRangeKm = 100 * km / drop;
        double benchmark = profile.benchmarkKm();
        if (usable(benchmark)) {
            result.performancePercent = result.equivalentRangeKm / benchmark * 100;
            result.differencePercent = result.performancePercent - 100;
        }
        if (profile.hasCapacity()) {
            result.netKwh = profile.usableKwh * drop / 100;
            result.kwhPer100Km = profile.usableKwh * drop / km;
        }
        result.verdict = verdict(drop, result.differencePercent);
        return result;
    }

    static Verdict verdict(double drop, double differencePercent) {
        if (!(drop >= MIN_VERDICT_DROP) || Double.isNaN(differencePercent)) return Verdict.NOT_ENOUGH_DATA;
        if (differencePercent > ON_BAND_PERCENT) return Verdict.ABOVE;
        if (differencePercent < -ON_BAND_PERCENT) return Verdict.BELOW;
        return Verdict.ON;
    }

    static String verdictText(Verdict verdict) {
        switch (verdict) {
            case ABOVE: return "Above benchmark · overperformed";
            case ON: return "On benchmark";
            case BELOW: return "Below benchmark · underperformed";
            default: return "Not enough data yet · needs a 5 % drop";
        }
    }

    /** All-time figures: sums over valid intervals, never an average of per-drive ranges. */
    static final class AllTime implements java.io.Serializable {
        double validKm, validDrop, allKm;
        int validIntervals, excludedIntervals;

        double equivalentRangeKm() { return validDrop > 0 ? 100 * validKm / validDrop : Double.NaN; }
        /** km per battery point, or NaN until it rests on enough drop. */
        double learnedKmPerPoint() { return validDrop >= MIN_LEARNED_DROP && validKm > 0 ? validKm / validDrop : Double.NaN; }
        double coverage() { return allKm > 0 ? Math.min(1, validKm / allKm) : Double.NaN; }
        double kwhPer100Km(Profile profile) {
            return profile.hasCapacity() && validKm > 0 ? profile.usableKwh * validDrop / validKm : Double.NaN;
        }
        Result result(Profile profile) { return evaluate(validKm, validDrop, profile); }
    }

    static AllTime aggregate(List<Interval> intervals, double allKm) {
        AllTime all = new AllTime();
        all.allKm = allKm;
        for (Interval interval : intervals) {
            if (interval.learnable()) {
                all.validKm += interval.km;
                all.validDrop += interval.drop;
                all.validIntervals++;
            } else {
                all.excludedIntervals++;
            }
        }
        return all;
    }

    /** km per battery point: learned when there is enough data, otherwise the benchmark's. */
    static double kmPerPoint(Profile profile, AllTime all) {
        double learned = all == null ? Double.NaN : all.learnedKmPerPoint();
        return usable(learned) ? learned : profile.benchmarkKm() / 100;
    }

    static boolean learned(AllTime all) { return all != null && usable(all.learnedKmPerPoint()); }

    /** Range to the reserve from an entered percentage: max(0, s − r) × k. */
    static double rangeToReserveKm(double percent, Profile profile, AllTime all) {
        if (Double.isNaN(percent)) return Double.NaN;
        return Math.max(0, percent - profile.reservePercent) * kmPerPoint(profile, all);
    }

    /** The battery projected since a reading: s − distance ÷ k. Never replaces the reading itself. */
    static double projectedPercent(double percent, double kmSince, Profile profile, AllTime all) {
        if (Double.isNaN(percent)) return Double.NaN;
        return Math.max(0, percent - kmSince / kmPerPoint(profile, all));
    }

    /** Modelled idle energy: Σ kW × hours, each period at its own recorded kW. */
    static double idleKwh(double kw, double seconds) { return kw * seconds / 3600.0; }

    // ---- Charge log, parked drain and the car's odometer (energy-companion.md §2) ----

    /** A charge counts towards the capacity estimate only when it added at least this many points. */
    static final double MIN_CHARGE_GAIN = 10;
    /** Grid energy per point (and the capacity estimate) is shown once counted charges add up to this. */
    static final double MIN_CAPACITY_GAIN = 20;
    /** A parked period counts for drain when it lasted at least this long. */
    static final double MIN_DRAIN_HOURS = 6;
    /** Parked drain is shown once counted periods add up to this many hours. */
    static final double MIN_DRAIN_TOTAL_HOURS = 24;
    /** More journey time than this between two check-ins and it wasn't a parked period. */
    static final double MAX_PARKED_ACTIVE_SECONDS = 60;
    /** GPS is compared with the odometer only over at least this much odometer distance. */
    static final double MIN_ODOMETER_KM = 1;

    enum Current { AC, DC, UNKNOWN }
    enum Place { HOME, PUBLIC, UNKNOWN }

    /** One entry of the driver's charge log. Every field is optional: NaN or UNKNOWN when not entered. */
    static final class Charge implements java.io.Serializable {
        long id, time, checkIn, chargerId;
        double measuredKwh=Double.NaN,estimatedKwh=Double.NaN,coverageSeconds;
        boolean measuredComplete,live;
        /** Meter kWh when typed; else a complete car measurement; else the whole-charge estimate; else what was measured. */
        double billedKwh(){return usable(kwh)?kwh:measuredComplete&&Double.isFinite(measuredKwh)?measuredKwh:Double.isFinite(estimatedKwh)?estimatedKwh:measuredKwh;}
        double before = Double.NaN, after = Double.NaN;
        /** Grid side: from the charger, the wallbox or a meter app, so it includes charging losses. */
        double kwh = Double.NaN;
        /** What the charge cost in total, or the rate per kWh; either may be entered. */
        double cost = Double.NaN, rate = Double.NaN;
        Current current = Current.UNKNOWN;
        Place place = Place.UNKNOWN;

        /** Points added, or NaN without both readings. */
        double gain() { return Double.isNaN(before) || Double.isNaN(after) ? Double.NaN : after - before; }
        boolean countsForCapacity() { return usable(kwh) && gain() >= MIN_CHARGE_GAIN; }

        /** As entered; otherwise the entered rate × kWh; otherwise a home charge at the home rate. */
        double money(double homeRate) {
            if(live)return Double.isFinite(rate)&&rate>=0&&Double.isFinite(billedKwh())?rate*billedKwh():Double.NaN;
            if (known(cost)) return cost;
            if (!usable(kwh)) return Double.NaN;
            if (known(rate)) return rate * kwh;
            return atHomeRate(homeRate) ? homeRate * kwh : Double.NaN;
        }

        /** Priced only by the profile's home rate (nothing about money entered for this charge). */
        boolean atHomeRate(double homeRate) {
            return !known(cost) && !known(rate) && place == Place.HOME && known(homeRate) && usable(kwh);
        }

        boolean empty() {
            return Double.isNaN(before) && Double.isNaN(after) && Double.isNaN(kwh) && Double.isNaN(cost)
                    && Double.isNaN(rate) && current == Current.UNKNOWN && place == Place.UNKNOWN;
        }

        /** Money may be zero (a free charger); kWh may not. */
        private static boolean known(double value) { return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0; }
    }

    /** Sums over the charge log, never an average of per-charge figures. */
    /** A running spend and energy total for one kind of charging, for the by-type breakdown. */
    static final class Spend implements java.io.Serializable {
        int charges;
        double money, kwh;
        boolean any() { return charges > 0; }
        void add(double money, double kwh) {
            charges++;
            this.money += money;
            if (usable(kwh)) this.kwh += kwh;
        }
    }

    static final class Charging implements java.io.Serializable {
        int charges, capacityCharges, pricedCharges, homeRateCharges;
        double kwh, money, capacityKwh, capacityGain, pricedKwh, pricedMoney;
        /** Spend split by where you charged (home vs public) and how (AC vs DC). A charge counts in
         *  both views; a charge without that tag goes to "not tagged", so each view always sums to
         *  {@link #money}. */
        final Spend home = new Spend(), onPublic = new Spend(), placeNotTagged = new Spend();
        final Spend ac = new Spend(), dc = new Spend(), currentNotTagged = new Spend();

        /** Grid kWh per battery point = Σ kWh ÷ Σ points gained, or NaN until they add up to 20 points. */
        double gridKwhPerPoint() { return capacityGain >= MIN_CAPACITY_GAIN && capacityKwh > 0 ? capacityKwh / capacityGain : Double.NaN; }
        /** kWh ÷ (% gained ÷ 100): an upper bound on usable capacity, since grid kWh include charging losses. */
        double capacityUpperBoundKwh() { return 100 * gridKwhPerPoint(); }
        /** Σ money ÷ Σ kWh over charges with both. */
        double ratePerKwh() { return pricedKwh > 0 ? pricedMoney / pricedKwh : Double.NaN; }
        /** Grid kWh per 100 km = grid kWh per point × 100 ÷ km per point. */
        double gridKwhPer100Km(double kmPerPoint) {
            return usable(kmPerPoint) ? gridKwhPerPoint() * 100 / kmPerPoint : Double.NaN;
        }
        /** Cost per 100 km = grid kWh per 100 km × the rate paid. */
        double costPer100Km(double kmPerPoint) { return gridKwhPer100Km(kmPerPoint) * ratePerKwh(); }
    }

    static Charging charging(List<Charge> charges, double homeRate) {
        Charging all = new Charging();
        for (Charge charge : charges) {
            all.charges++;
            if (usable(charge.kwh)) all.kwh += charge.kwh;
            double money = charge.money(homeRate);
            if (!Double.isNaN(money)) {
                all.money += money;
                if (charge.place == Place.HOME) all.home.add(money, charge.kwh);
                else if (charge.place == Place.PUBLIC) all.onPublic.add(money, charge.kwh);
                else all.placeNotTagged.add(money, charge.kwh);
                if (charge.current == Current.AC) all.ac.add(money, charge.kwh);
                else if (charge.current == Current.DC) all.dc.add(money, charge.kwh);
                else all.currentNotTagged.add(money, charge.kwh);
            }
            if (charge.countsForCapacity()) {
                all.capacityCharges++;
                all.capacityKwh += charge.kwh;
                all.capacityGain += charge.gain();
            }
            if (!Double.isNaN(money) && usable(charge.kwh)) {
                all.pricedCharges++;
                all.pricedKwh += charge.kwh;
                all.pricedMoney += money;
                if (charge.atHomeRate(homeRate)) all.homeRateCharges++;
            }
        }
        return all;
    }

    /** Two consecutive check-ins with the car parked in between: no journey time and no distance. */
    static final class Parked implements java.io.Serializable {
        Interval interval;
        Exclusion exclusion = Exclusion.NONE;
        boolean counted() { return exclusion == Exclusion.NONE; }
        double pointsPerDay() { return interval.hours > 0 ? 24 * interval.drop / interval.hours : Double.NaN; }
    }

    /** The parked period between two check-ins, or null when the car was driven (or a journey ran). */
    static Parked parked(Interval i) {
        if (i.km >= MIN_INTERVAL_KM || i.activeSeconds > MAX_PARKED_ACTIVE_SECONDS) return null;
        Parked parked = new Parked();
        parked.interval = i;
        if (i.to.charged == Charged.YES) parked.exclusion = Exclusion.CHARGED;
        else if (i.to.charged == Charged.UNKNOWN) parked.exclusion = Exclusion.CHARGE_UNKNOWN;
        else if (Double.isNaN(i.drop)) parked.exclusion = Exclusion.MISSING_READING;
        else if (i.from.excluded || i.to.excluded) parked.exclusion = Exclusion.RESET;
        else if (i.drop < 0) parked.exclusion = Exclusion.BATTERY_ROSE;
        else if (i.hours < MIN_DRAIN_HOURS) parked.exclusion = Exclusion.SHORT_PARK;
        return parked;
    }

    /** Parked drain, summed over counted periods: 24 × Σ points ÷ Σ hours. */
    static final class Drain implements java.io.Serializable {
        double hours, drop;
        int periods, excluded;
        double pointsPerDay() { return hours >= MIN_DRAIN_TOTAL_HOURS ? 24 * drop / hours : Double.NaN; }
        double kwhPerDay(Profile profile) {
            return profile.hasCapacity() ? pointsPerDay() * profile.usableKwh / 100 : Double.NaN;
        }
    }

    static Drain drain(List<Parked> parked) {
        Drain drain = new Drain();
        for (Parked period : parked) {
            if (period.counted()) {
                drain.hours += period.interval.hours;
                drain.drop += period.interval.drop;
                drain.periods++;
            } else {
                drain.excluded++;
            }
        }
        return drain;
    }

    /** GPS distance against the car's odometer, over drives with good GPS and an odometer at both ends. */
    static final class Distances implements java.io.Serializable {
        double gpsKm, odometerKm;
        int intervals;
        /** Negative when GPS reads short of the odometer. */
        double gpsDifferencePercent() { return odometerKm > 0 ? (gpsKm / odometerKm - 1) * 100 : Double.NaN; }
    }

    static Distances distances(List<Interval> intervals) {
        Distances distances = new Distances();
        for (Interval i : intervals) {
            if (!(i.odometerKm >= MIN_ODOMETER_KM) || i.km < MIN_INTERVAL_KM || !gpsGood(i)) continue;
            distances.gpsKm += i.km;
            distances.odometerKm += i.odometerKm;
            distances.intervals++;
        }
        return distances;
    }

    static boolean usable(double value) { return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0; }

    static String percent(double value) {
        if (Double.isNaN(value)) return "—";
        return value == Math.rint(value) ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.1f", value);
    }

    static String signed(double value) {
        return Double.isNaN(value) ? "—" : String.format(Locale.US, "%+.1f %%", value);
    }
}
