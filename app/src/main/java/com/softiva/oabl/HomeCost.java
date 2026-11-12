package com.softiva.oabl;

import java.util.Locale;

/**
 * What Home's header shows about money and economy: today's driving cost and kWh/100 km, and the
 * cost of the current car-on session (the open drive; the last one of today once the car is off).
 * Prepared by the :energy owner from the same records, prices and loss factor the Energy page uses.
 */
final class HomeCost implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    String currency = "";
    /** Today: km covered by energy figures, ≈ kWh from the grid, kWh/100 km and cost; NaN when unknown. */
    double dayKm, dayKwh = Double.NaN, dayPer100 = Double.NaN, dayCost = Double.NaN;
    /** The session: km, battery kWh, cost; {@code sessionOpen} is false for the last finished one. */
    boolean hasSession, sessionOpen;
    double sessionKm, sessionKwh = Double.NaN, sessionCost = Double.NaN;

    /** Below this, kWh/100 km is rounding noise (the car's trip counter moves in 0.01 kWh steps). */
    static final double MIN_ECONOMY_KM = 5;

    static HomeCost of(CarEnergySummary today, CarEnergyTracker.Event session, long dayStart,
                       double capacity, double price, double gridPerPoint, String currency) {
        HomeCost result = new HomeCost();
        result.currency = currency == null ? "" : currency;
        if (today != null) {
            double used = today.usedKwh(capacity), energyKm = today.energyKm(capacity);
            result.dayKm = today.km;
            if (today.energyDrives > 0 && Double.isFinite(used) && used >= 0 && energyKm >= 0.3) {
                // The cost is an amount, fine on a short day; the economy is a ratio, only meaningful over a distance.
                if (energyKm >= MIN_ECONOMY_KM) result.dayPer100 = used / energyKm * 100;
                result.dayKwh = CarTripCost.billedEnergy(used, gridPerPoint, capacity);
                if (price >= 0) result.dayCost = result.dayKwh * price;
            }
        }
        // A finished session only counts while it is still today's; yesterday's is not "this session".
        if (session != null && (!session.closed() || session.start >= dayStart)) {
            result.hasSession = true;
            result.sessionOpen = !session.closed();
            result.sessionKm = session.km;
            double net = session.netKwh(capacity);
            if (Double.isFinite(net) && net >= 0) result.sessionKwh = net;
            CarTripCost.Result cost = CarTripCost.of(session, price, gridPerPoint, capacity);
            if (cost != null) result.sessionCost = cost.cost;
        }
        return result;
    }

    /** "420" or "—": whole currency units, never a made-up zero. */
    static String money(double value) {
        return Double.isFinite(value) && value >= 0 ? EnergyModel.percent(Math.round(value)) : "—";
    }

    String dayCostText() { return Double.isFinite(dayCost) ? currency + " " + money(dayCost) : "—"; }
    String dayEconomyText() { return Double.isFinite(dayPer100) ? String.format(Locale.US, "%.1f kWh/100 km", dayPer100) : "—"; }
    String sessionCostText() { return Double.isFinite(sessionCost) ? currency + " " + money(sessionCost) : "—"; }
    String sessionEnergyText() { return Double.isFinite(sessionKwh) ? String.format(Locale.US, "%.1f kWh", sessionKwh) : "—"; }
}
