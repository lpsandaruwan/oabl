package com.softiva.oabl;

/**
 * The car signals the box consumes from OABL Link. The keys match OABL Link's wire names exactly
 * (energy-companion.md §3.2); a key the box doesn't list here is ignored, never guessed. The battery
 * % is always the figure the car displays. These feed the live Home card and, through the car_sample
 * table, the energy model.
 */
enum CarSignal {
    SOC("soc"),
    ODOMETER("odometer"),
    RANGE_EST("range_est"),
    RANGE_STANDARD("range_standard"),
    RANGE_NEDC("range_nedc"),
    RANGE_DISPLAY_RAW("range_display_raw"),
    CHARGING("charging"),
    PLUG("plug"),
    READY("ready"),
    GEAR("gear"),
    SPEED("speed"),
    /** What the car's speedometer shows (a little above SPEED); for display only, never distances. */
    SPEED_DISPLAY("speed_display"),
    TRIP_KM("trip_km"),
    TRIP_KWH100("trip_kwh100"),
    TRIP_B_KM("trip_b_km"),
    TRIP_B_KWH100("trip_b_kwh100"),
    TRIP_C_KM("trip_c_km"),
    TRIP_C_KWH100("trip_c_kwh100"),
    TRIP_A_TIME_RAW("trip_a_time_raw"),
    TRIP_B_TIME_RAW("trip_b_time_raw"),
    TRIP_C_TIME_RAW("trip_c_time_raw"),
    TRIP_A_SPEED_RAW("trip_a_speed_raw"),
    TRIP_B_SPEED_RAW("trip_b_speed_raw"),
    TRIP_C_SPEED_RAW("trip_c_speed_raw"),
    HU_BOOT_ID("hu_boot_id"),
    CHARGE_TARGET("charge_target"),
    CHARGE_REMAINING_RAW("charge_remaining_raw"),
    CHARGE_STATE_RAW("charge_state_raw"),
    REGEN_LEVEL_RAW("regen_level_raw"),
    BRAKE_PEDAL("brake_pedal"),
    V12_RAW("v12_raw"),
    BATT_HEALTH_RAW("batt_health_raw"),
    AC_FAN_RAW("ac_fan_raw"),
    AC_SET_TEMP_RAW("ac_set_temp_raw"),
    POWER("power"),
    VOLTAGE("voltage"),
    CURRENT("current"),
    HVAC_ON("hvac_on"),
    TEMP_OUT("temp_out"),
    DRIVE_MODE("drive_mode"),
    /** Calibrated head-unit ILL+/headlight state: 1 = night, 0 = day. */
    ILLUMINATION("illumination"),
    // The car's own battery-management readings, raw as the car answers (OABL Link 0.1.x, 30 October).
    BMS_PACK_VOLTAGE_RAW("bms_pack_voltage_raw"),
    BMS_PACK_CURRENT_RAW("bms_pack_current_raw"),
    BMS_PACK_POWER_RAW("bms_pack_power_raw"),
    BMS_CHARGED_ENERGY_RAW("bms_charged_energy_raw"),
    BATT_HEAT_COOL_RAW("batt_heat_cool_raw"),
    SOC_CREDIBILITY_RAW("soc_credibility_raw"),
    BMS_REMAIN_TIME_RAW("bms_remain_time_raw");

    final String key;

    CarSignal(String key) { this.key = key; }

    /**
     * False for a reading that cannot be real. The battery % is never 0 while the car talks: Link sends 0
     * for a moment when it connects, before the car has answered, and that must not become a starting level.
     */
    static boolean plausible(CarSignal signal, double value) {
        if (Double.isNaN(value)) return false;
        switch (signal) {
            case SOC: return value > 0 && value <= 100;
            // The head unit's first answer after it wakes is all ones (20-bit odometer, 10-bit range,
            // 10000 for a trip): "not read yet", never a distance.
            case ODOMETER: return value < ODOMETER_UNSET;
            case RANGE_EST: case RANGE_STANDARD: case RANGE_NEDC: return value < RANGE_UNSET;
            case TRIP_KM: case TRIP_B_KM: case TRIP_C_KM: return value < TRIP_UNSET;
            default: return true;
        }
    }
    static final double ODOMETER_UNSET = 1_000_000, RANGE_UNSET = 1023, TRIP_UNSET = 10_000;

    static CarSignal byKey(String key) {
        for (CarSignal signal : values()) if (signal.key.equals(key)) return signal;
        return null;
    }
}
