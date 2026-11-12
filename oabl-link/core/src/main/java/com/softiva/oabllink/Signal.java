package com.softiva.oabllink;

/**
 * The signals OABL Link can read from the car and serve to the box. Names follow the conventions of
 * OVMS and ABRP's telemetry (energy-companion.md §3.2); units are fixed, so a value means the same
 * thing wherever it is read. Every value travels as a double: a bool is 0 or 1, and a gear is coded
 * by {@link Gear}. The battery % is always the figure the car displays, never a separate BMS figure.
 */
public enum Signal {
    // Tier 1: enough for automatic drives, charges and consumption.
    SOC("soc", "%", Kind.NUMBER, 1),
    READY("ready", "", Kind.BOOL, 1),
    GEAR("gear", "", Kind.ENUM, 1),
    CHARGING("charging", "", Kind.BOOL, 1),
    PLUG("plug", "", Kind.BOOL, 1),
    ODOMETER("odometer", "km", Kind.NUMBER, 1),

    // Tier 2: comparison with the car's own figures, and the context behind consumption.
    RANGE_EST("range_est", "km", Kind.NUMBER, 2),
    RANGE_STANDARD("range_standard", "km", Kind.NUMBER, 2),
    RANGE_NEDC("range_nedc", "km", Kind.NUMBER, 2),
    // The car's range-display setting (MP5_CRUIS_RANGE_DISPLAY_SET); 3 matched the NEDC display in the survey.
    RANGE_DISPLAY_RAW("range_display_raw", "raw · 3 = NEDC display, others unconfirmed", Kind.NUMBER, 2),
    TRIP_KM("trip_km", "km", Kind.NUMBER, 2),
    TRIP_KWH100("trip_kwh100", "kWh/100km", Kind.NUMBER, 2),
    SPEED("speed", "km/h", Kind.NUMBER, 2),
    // What the instrument cluster shows (PERF_VEHICLE_SPEED_DISPLAY): usually a little above SPEED,
    // as speedometers must never read low. For display only; distances and stops use SPEED.
    SPEED_DISPLAY("speed_display", "km/h", Kind.NUMBER, 2),
    TEMP_OUT("temp_out", "C", Kind.NUMBER, 2),
    HVAC_ON("hvac_on", "", Kind.BOOL, 2),
    DRIVE_MODE("drive_mode", "", Kind.ENUM, 2),
    /** Calibrated ILL+/headlight state from the Android 10 head unit: 1 = night, 0 = day. */
    ILLUMINATION("illumination", "", Kind.BOOL, 2),

    TRIP_B_KM("trip_b_km", "km", Kind.NUMBER, 2),
    TRIP_B_KWH100("trip_b_kwh100", "kWh/100km", Kind.NUMBER, 2),
    TRIP_C_KM("trip_c_km", "km", Kind.NUMBER, 2),
    TRIP_C_KWH100("trip_c_kwh100", "kWh/100km", Kind.NUMBER, 2),
    TRIP_A_TIME_RAW("trip_a_time_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    TRIP_B_TIME_RAW("trip_b_time_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    TRIP_C_TIME_RAW("trip_c_time_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    TRIP_A_SPEED_RAW("trip_a_speed_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    TRIP_B_SPEED_RAW("trip_b_speed_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    TRIP_C_SPEED_RAW("trip_c_speed_raw", "raw · unconfirmed", Kind.NUMBER, 2),
    HU_BOOT_ID("hu_boot_id", "boot identity", Kind.NUMBER, 2),
    // Charging context the car shows on its own energy screen.
    CHARGE_TARGET("charge_target", "%", Kind.NUMBER, 2),
    CHARGE_REMAINING_RAW("charge_remaining_raw", "raw · probably minutes", Kind.NUMBER, 2),

    // Tier 3: a bonus when the head unit exposes it.
    POWER("power", "kW", Kind.NUMBER, 3),
    VOLTAGE("voltage", "V", Kind.NUMBER, 3),
    CURRENT("current", "A", Kind.NUMBER, 3),
    BATT_TEMP("batt_temp", "C", Kind.NUMBER, 3),
    SOH("soh", "%", Kind.NUMBER, 3),
    V12("v12", "V", Kind.NUMBER, 3),
    // Raw research values: kept unscaled until a drive or charge confirms their meaning.
    CHARGE_STATE_RAW("charge_state_raw", "raw · 2 = AC charging", Kind.NUMBER, 3),
    REGEN_LEVEL_RAW("regen_level_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BRAKE_PEDAL("brake_pedal", "", Kind.BOOL, 3),
    V12_RAW("v12_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BATT_HEALTH_RAW("batt_health_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    // The A/C level the car shows (AC_BLOW_LVL_ST) and its set temperature (AC_SET_TEMP_ST); 0 fan = blower off.
    AC_FAN_RAW("ac_fan_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    AC_SET_TEMP_RAW("ac_set_temp_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    // The car's own battery-management (BMS) readings, kept raw: the Nammi 1 answers many of these with 0
    // (not wired on this model), so the box shows one only when it is non-zero.
    BMS_PACK_VOLTAGE_RAW("bms_pack_voltage_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BMS_PACK_CURRENT_RAW("bms_pack_current_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BMS_PACK_POWER_RAW("bms_pack_power_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BMS_CHARGED_ENERGY_RAW("bms_charged_energy_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BATT_HEAT_COOL_RAW("batt_heat_cool_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    SOC_CREDIBILITY_RAW("soc_credibility_raw", "raw · unconfirmed", Kind.NUMBER, 3),
    BMS_REMAIN_TIME_RAW("bms_remain_time_raw", "raw · probably minutes", Kind.NUMBER, 3);

    /** What a value is, so the protocol can render it; the wire value is always a double. */
    public enum Kind { NUMBER, BOOL, ENUM }

    /** Gear codes, in the order a selector moves through them. */
    public enum Gear {
        P(0), R(1), N(2), D(3);
        public final int code;
        Gear(int code) { this.code = code; }

        public static Gear of(double value) {
            int code = (int) Math.round(value);
            for (Gear gear : values()) if (gear.code == code) return gear;
            return P;
        }
    }

    /** The name on the wire, e.g. {@code soc}. */
    public final String key;
    /** The fixed unit, or empty for a bool or an enum. */
    public final String unit;
    public final Kind kind;
    /** 1, 2 or 3, as in energy-companion.md §3.2. */
    public final int tier;

    Signal(String key, String unit, Kind kind, int tier) {
        this.key = key;
        this.unit = unit;
        this.kind = kind;
        this.tier = tier;
    }

    public static Signal byKey(String key) {
        for (Signal signal : values()) if (signal.key.equals(key)) return signal;
        return null;
    }
}
