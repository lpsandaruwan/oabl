package com.softiva.oabl;

import java.util.Locale;

/** Drive-specific Trip A energy, falling back to SOC × capacity. The same loss factor and
 * charging price are applied to individual drives and period totals. Regen is never a charge. */
final class CarTripCost {
    static final class Result {
        final double cost, perKm, pricePerKwh; final String basis;
        Result(double cost, double perKm, double pricePerKwh, String basis) { this.cost = cost; this.perKm = perKm; this.pricePerKwh = pricePerKwh; this.basis = basis; }
        String text(String currency) {
            return String.format(Locale.US, "≈ %s %s · %.1f %s/km at %s %s/kWh · %s", EnergyModel.percent(Math.round(cost)), currency, perKm, currency,
                    EnergyModel.percent(Math.round(pricePerKwh * 100) / 100.0), currency, basis);
        }
    }

    /** Σ money ÷ Σ kWh over priced live charges; the home rate before any charge is priced. */
    static double averagePrice(Iterable<EnergyModel.Charge> charges, double homeRate) {
        double money = 0, kwh = 0;
        for (EnergyModel.Charge charge : charges) {
            if (!charge.live) continue;
            double paid = charge.money(homeRate), energy = charge.billedKwh();
            if (Double.isFinite(paid) && energy > 0) { money += paid; kwh += energy; }
        }
        return kwh > 0 ? money / kwh : homeRate >= 0 ? homeRate : Double.NaN;
    }

    static boolean hasLossFactor(double gridPerPoint, double capacity) {
        return Double.isFinite(gridPerPoint) && gridPerPoint > 0 && capacity > 0;
    }
    static double billedEnergy(double netKwh, double gridPerPoint, double capacity) {
        return hasLossFactor(gridPerPoint, capacity) ? netKwh * Math.max(1, gridPerPoint * 100 / capacity) : netKwh;
    }
    /** Null when the drive has no distance, no usable energy figure, or there is no price yet. */
    static Result of(CarEnergyTracker.Event drive, double pricePerKwh, double gridPerPoint, double capacity) {
        if (drive == null || !(drive.km > 0) || !(pricePerKwh >= 0)) return null;
        if (!Double.isFinite(drive.tripNetKwh()) && !(drive.points()>0)) return null;
        double net = drive.netKwh(capacity);
        if (!Double.isFinite(net) || net < 0) return null;
        double kwh = billedEnergy(net, gridPerPoint, capacity);
        String basis = (Double.isFinite(drive.tripNetKwh()) ? "this drive's Trip A energy" : "battery % × capacity")
                + (hasLossFactor(gridPerPoint, capacity) ? " · charging losses included" : " · battery side; charging losses unknown");
        double cost = kwh * pricePerKwh;
        return new Result(cost, cost / drive.km, pricePerKwh, basis);
    }
}
