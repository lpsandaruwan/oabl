package com.softiva.oabl;

/** Billing uses kWh, not kW. A battery-based estimate never becomes an actual paid amount. */
final class CarChargePricing {
    static double rate(EnergyModel.Charge charge,double homeRate) {
        return Double.isFinite(charge.rate)?charge.rate:charge.live?Double.NaN:charge.place==EnergyModel.Place.HOME?homeRate:Double.NaN;
    }
    static double actual(EnergyModel.Charge charge,double homeRate) { return charge.money(homeRate); }
    static double estimate(CarEnergyTracker.Event event,EnergyModel.Charge charge,double homeRate,double perPoint,double capacity) {
        double rate=rate(charge,homeRate),kwh=CarChargeEnergy.estimate(event,perPoint,capacity).kwh;
        return Double.isFinite(kwh)&&Double.isFinite(rate)?kwh*rate:Double.NaN;
    }
}
