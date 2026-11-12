package com.softiva.oabl;

import java.util.Locale;

/**
 * Everything the Battery page shows, read by the :energy owner in one go. A value the car does not
 * give is NaN, and the page says "not reported" instead of a zero. The Nammi 1 answers its BMS pack
 * readings (voltage, current, power) and the charged-energy counter with 0, which means "not wired",
 * so those count as reported only when non-zero.
 */
final class BatteryFacts implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    double soc = Double.NaN, rangeKm = Double.NaN, rangeStandardKm = Double.NaN, rangeNedcKm = Double.NaN;
    double usableKwh = Double.NaN, reservePercent = Double.NaN;
    boolean charging;
    double chargeKw = Double.NaN, chargeVolts = Double.NaN, chargeAmps = Double.NaN, chargeTarget = Double.NaN, minutesToFull = Double.NaN;
    /** The car's second time-to-full (PDCU_BMS_REMAIN_CHRG_TIME), minutes, when it gives one. */
    double bmsMinutesToFull = Double.NaN;
    double healthPercent = Double.NaN, twelveVolt = Double.NaN;
    /** BMS pack readings and counters, raw; NaN when not reported (absent, or 0 on this car). */
    double packVoltage = Double.NaN, packCurrent = Double.NaN, packPower = Double.NaN, chargedEnergy = Double.NaN;
    /** State codes, raw: 0 is a real value for these. */
    double heatCool = Double.NaN, socCredibility = Double.NaN;
    /** Grid kWh per battery point learned from complete charges, and how many points it rests on. */
    double wallKwhPerPoint = Double.NaN;
    double learnedKwhPerKm = Double.NaN;

    /** Raw BMS values: 0 means the car does not wire the signal. */
    static double reported(double raw) { return Double.isFinite(raw) && raw > 0 ? raw : Double.NaN; }

    /** The car's time-to-full in minutes, or NaN for no value or the "unknown" markers. */
    static double minutes(double raw) { return Double.isFinite(raw) && raw > 0 && raw < 4095 ? raw : Double.NaN; }

    /** Battery health as the car reports it (raw ×0.1 %), NaN when out of range. */
    static double health(double raw) { return Double.isFinite(raw) && raw > 0 && raw <= 1000 ? raw * 0.1 : Double.NaN; }

    static BatteryFacts read(CarLinkController link, EnergyStore store, EnergyModel.Profile profile, boolean charging) {
        BatteryFacts facts = new BatteryFacts();
        facts.usableKwh = profile.usableKwh; facts.reservePercent = profile.reservePercent; facts.charging = charging;
        facts.soc = link.liveSoc();
        facts.rangeKm = HomeEnergySnapshot.validRange(link.liveRangeKm());
        facts.rangeStandardKm = HomeEnergySnapshot.validRange(link.liveValue(CarSignal.RANGE_STANDARD));
        facts.rangeNedcKm = HomeEnergySnapshot.validRange(link.liveValue(CarSignal.RANGE_NEDC));
        if (charging) {
            double power = link.liveValue(CarSignal.POWER), volts = link.liveValue(CarSignal.VOLTAGE), amps = link.liveValue(CarSignal.CURRENT);
            facts.chargeKw = power > 0 ? power : Double.NaN; facts.chargeVolts = volts > 0 ? volts : Double.NaN; facts.chargeAmps = amps > 0 ? amps : Double.NaN;
            double target = link.liveValue(CarSignal.CHARGE_TARGET);
            facts.chargeTarget = target > 0 && target <= 100 ? target : Double.NaN;
            facts.minutesToFull = minutes(link.liveValue(CarSignal.CHARGE_REMAINING_RAW));
        }
        facts.bmsMinutesToFull = minutes(link.liveValue(CarSignal.BMS_REMAIN_TIME_RAW));
        facts.healthPercent = health(link.liveValue(CarSignal.BATT_HEALTH_RAW));
        facts.twelveVolt = CarBatteryHealth.volts(link.liveValue(CarSignal.V12_RAW));
        facts.packVoltage = reported(link.liveValue(CarSignal.BMS_PACK_VOLTAGE_RAW));
        facts.packCurrent = reported(link.liveValue(CarSignal.BMS_PACK_CURRENT_RAW));
        facts.packPower = reported(link.liveValue(CarSignal.BMS_PACK_POWER_RAW));
        facts.chargedEnergy = reported(link.liveValue(CarSignal.BMS_CHARGED_ENERGY_RAW));
        facts.heatCool = link.liveValue(CarSignal.BATT_HEAT_COOL_RAW);
        facts.socCredibility = link.liveValue(CarSignal.SOC_CREDIBILITY_RAW);
        facts.wallKwhPerPoint = wallKwhPerPoint(store);
        return facts;
    }

    // Parsing every charge is not cheap and the answer moves only when a charge is written: kept 30 s or until then.
    private static long learnedAt, learnedGeneration = -1;
    private static double learned = Double.NaN;
    private static synchronized double wallKwhPerPoint(EnergyStore store) {
        long now = android.os.SystemClock.elapsedRealtime(), generation = CarEnergyRepository.chargeGeneration();
        if (generation != learnedGeneration || now - learnedAt > 30_000) {
            learned = CarChargeEnergy.gridKwhPerPoint(store.charges()); learnedGeneration = generation; learnedAt = now;
        }
        return learned;
    }

    /** "13.9 V", or the not-reported word. */
    static String volts(double value) { return Double.isFinite(value) ? String.format(Locale.US, "%.1f V", value) : "Not reported"; }

    /** Wall energy ÷ the energy the battery gains: a rough charging efficiency, only if the capacity is entered. */
    double chargingEfficiency() {
        if (!Double.isFinite(wallKwhPerPoint) || wallKwhPerPoint <= 0 || !EnergyModel.usable(usableKwh)) return Double.NaN;
        double e = usableKwh / 100 / wallKwhPerPoint;
        return e > 0.5 && e <= 1.05 ? e : Double.NaN;
    }
}
