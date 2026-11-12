package com.softiva.oabl;

/**
 * Approximate regenerative braking from the car's speed and brake pedal. The Nammi reports no battery current
 * or power (they read 0 throughout a 36 km logged drive), so this is an estimate, for curiosity rather than
 * accounting.
 *
 * Over one interval in which the car slows from v1 to v2, the kinetic energy given up is
 * ½·m·(v1² − v2²). Rolling resistance and air drag absorb part of it whatever the driver does; only the
 * remainder had to be braked away, by the motor (regen) or the friction brakes. The motor's share is
 * limited as this car was measured to behave at regen level 3, on the drive of 6 November 2026
 * (docs/ev-cockpit/regen-research.md):
 * - it slows the car by about 1.3 m/s² at most, on top of road load; a harder stop is friction braking;
 * - it fades out between 12 and 5 km/h;
 * - it takes 1-2 s to build up after slowing starts;
 * - it reaches about 30 kW near 60-70 km/h.
 *
 * A brake press while regen is still building up is friction braking: the motor isn't there yet. Once regen
 * has built up, the motor keeps its share under the pedal and anything over its limit is friction.
 *
 * Not measured: the mass, drag and rolling figures are public or typical values, the 65 % is a typical
 * motor, inverter and battery figure, and the motor's share under the pedal once it has built up.
 */
final class CarRegenModel {
    static final double MASS_KG = 1300, GRAVITY = 9.81, ROLLING = 0.011, AIR_DENSITY = 1.2, DRAG_AREA_M2 = 0.65;
    /** Regen is gone at this speed and at full strength by {@link #FADE_FULL_KPH}. */
    static final double FADE_KPH = 5, FADE_FULL_KPH = 12;
    /** The most the motor slows the car, on top of road load. */
    static final double MAX_DECEL_MS2 = 1.3;
    static final double MAX_REGEN_KW = 30;
    /** Seconds of slowing before the motor reaches {@link #MAX_DECEL_MS2}. */
    static final double RAMP_S = 1.5;
    /** Motor, inverter and battery charging losses on the way back into the pack. */
    static final double EFFICIENCY = 0.65;
    /** Below this rate of change the car counts as holding its speed. */
    static final double STEADY_KPH_PER_S = 0.3;
    /** Longer intervals hide what happened between the two speeds. */
    static final double MAX_INTERVAL_S = 5;

    /** As {@link #kwh(double, double, double, double)} for a car that has already been slowing for a while. */
    static double kwh(double fromKph, double toKph, double seconds) { return kwh(fromKph, toKph, seconds, RAMP_S); }

    /**
     * Battery kWh recovered while slowing from {@code fromKph} to {@code toKph} in {@code seconds}; 0 otherwise.
     *
     * @param slowingFor seconds the car had already been slowing when this interval began
     */
    static double kwh(double fromKph, double toKph, double seconds, double slowingFor) {
        return kwh(fromKph, toKph, seconds, slowingFor, false);
    }

    /** @param pedalDown the brake pedal was pressed during this interval; false too when the car doesn't say */
    static double kwh(double fromKph, double toKph, double seconds, double slowingFor, boolean pedalDown) {
        if (pedalDown && slowingFor < RAMP_S) return 0;
        if (!(seconds > 0) || seconds > MAX_INTERVAL_S || !Double.isFinite(fromKph) || !Double.isFinite(toKph)) return 0;
        if (fromKph <= toKph || fromKph > 220 || toKph < 0) return 0;
        double v1 = fromKph / 3.6, v2 = toKph / 3.6, average = (v1 + v2) / 2;
        double fade = (average * 3.6 - FADE_KPH) / (FADE_FULL_KPH - FADE_KPH);
        if (fade <= 0) return 0;
        double kinetic = 0.5 * MASS_KG * (v1 * v1 - v2 * v2);
        double roadLoad = (MASS_KG * GRAVITY * ROLLING + 0.5 * AIR_DENSITY * DRAG_AREA_M2 * average * average) * average * seconds;
        double ramp = Math.min(1, (Math.max(0, slowingFor) + seconds / 2) / RAMP_S);
        double motorLimit = Math.min(MAX_DECEL_MS2 * ramp * MASS_KG * average, MAX_REGEN_KW * 1000) * seconds * Math.min(1, fade);
        double braked = Math.min(kinetic - roadLoad, motorLimit);
        return braked > 0 ? braked * EFFICIENCY / 3_600_000 : 0;
    }

    /** The {@code slowingFor} to pass with the next interval. */
    static double slowingAfter(double slowingFor, double fromKph, double toKph, double seconds) {
        if (!(seconds > 0) || seconds > MAX_INTERVAL_S) return 0;
        // Speed changes a little on every reading, so a small change either way isn't speeding up or slowing down.
        double slowing = (fromKph - toKph) / seconds;
        if (slowing < -STEADY_KPH_PER_S) return 0;
        return Math.max(0, slowing > STEADY_KPH_PER_S ? slowingFor + seconds : slowingFor - seconds);
    }
}
