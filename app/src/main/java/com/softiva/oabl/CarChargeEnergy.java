package com.softiva.oabl;

import java.util.Locale;

/**
 * Charger-side kWh for a live charge when the car's measurement doesn't cover all of it. Grid energy
 * per battery point is learned from complete measured charges, so charging losses are included; before
 * that, capacity × points gives battery-side energy and says it excludes losses.
 */
final class CarChargeEnergy {
    /** Below half coverage the unmeasured part is too large to extrapolate from the measured power. */
    static final double MIN_EXTRAPOLATED_COVERAGE = 0.5;
    /** Points gained by complete measured charges before their kWh-per-point is trusted. */
    static final double MIN_LEARNED_POINTS = 10;
    enum Basis { MEASURED, EXTRAPOLATED, LEARNED_PER_POINT, CAPACITY, NONE }

    static final class Estimate {
        final double kwh; final Basis basis;
        Estimate(double kwh, Basis basis) { this.kwh = kwh; this.basis = basis; }
    }

    static double durationSeconds(CarEnergyTracker.Event charge) { return Math.max(0, (charge.end > 0 ? charge.end : charge.last) - charge.start) / 1000.0; }

    /** Every interval of the charge was measured; one transition interval of tolerance at 1 Hz. */
    static boolean complete(CarEnergyTracker.Event charge) {
        return charge.coveredSeconds > 0 && !charge.inferred && charge.coveredSeconds >= Math.max(1, durationSeconds(charge) - 2);
    }

    /** Σ measured kWh ÷ Σ points over complete measured charges of at least 10 points; NaN until 10 points. */
    static double gridKwhPerPoint(Iterable<EnergyModel.Charge> charges) {
        double kwh = 0, points = 0;
        for (EnergyModel.Charge charge : charges)
            if (charge.measuredComplete && charge.measuredKwh > 0 && charge.gain() >= EnergyModel.MIN_CHARGE_GAIN) { kwh += charge.measuredKwh; points += charge.gain(); }
        return points >= MIN_LEARNED_POINTS ? kwh / points : Double.NaN;
    }

    static Estimate estimate(CarEnergyTracker.Event charge, double perPoint, double capacity) {
        double measured = charge.measuredKwh(), duration = durationSeconds(charge);
        if (complete(charge)) return new Estimate(measured, Basis.MEASURED);
        if (Double.isFinite(measured) && duration > 0 && charge.coveredSeconds >= MIN_EXTRAPOLATED_COVERAGE * duration)
            return new Estimate(measured * duration / charge.coveredSeconds, Basis.EXTRAPOLATED);
        double gain = charge.gain();
        if (gain > 0 && Double.isFinite(perPoint)) return new Estimate(gain * perPoint, Basis.LEARNED_PER_POINT);
        if (gain > 0 && capacity > 0) return new Estimate(gain * capacity / 100, Basis.CAPACITY);
        return new Estimate(Double.NaN, Basis.NONE);
    }

    static String describe(Estimate estimate, CarEnergyTracker.Event charge) {
        switch (estimate.basis) {
            case MEASURED: return "measured by car (charger side)";
            case EXTRAPOLATED: return String.format(Locale.US, "estimated · car measured %.2f kWh over %.0f%% of the charge",
                    charge.measuredKwh(), 100 * charge.coveredSeconds / Math.max(1, durationSeconds(charge)));
            case LEARNED_PER_POINT: return "estimated from battery % gained · your measured charges";
            case CAPACITY: return "estimated from battery % gained · battery side, excludes charging losses";
            default: return "not measured · enter meter kWh for a cost";
        }
    }
}
