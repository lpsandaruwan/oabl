package com.softiva.oabllink;

import java.util.ArrayList;
import java.util.List;

/**
 * The real probe for the Dongfeng Nammi 1 head unit (energy-companion.md §3.6, probe A adapted).
 * It reads Dongfeng's own vendor properties through {@code dfcar_service} and decodes them into the
 * shared {@link Signal} model. Every property id, scale and code here comes from the read-only
 * survey of 20 October 2026 (docs/headunit-survey/2026-10-20/usb-parked/notes.md). It only reads.
 *
 * <p><b>What an ordinary app can read.</b> The car gates each property behind a permission. Battery %,
 * the range estimate and the charge state sit behind {@code CAR_ENERGY}/{@code CAR_ENERGY_PORTS},
 * which are normal permissions any app can hold; speed behind {@code CAR_SPEED}/{@code CAR_INFO}. But
 * odometer/gear, HVAC and drive mode use exact {@code df.car.permission.*} names that this firmware
 * checks but does not define. The personal APK defines those missing names at signature level and
 * requests them. This differs from the OEM's registered {@code dftc.car.permission.*} names. Other
 * firmware must be checked separately; a denied or unavailable read is always omitted.
 *
 * <p><b>Scales still to confirm.</b> Battery %, range, odometer and the charge figures were seen at
 * known values and are decoded exactly. Speed, outside temperature and the gear codes beyond Park
 * were read only with the car parked, so their scales and codes are marked below and are confirmed on
 * the first drive with adb attached, before anything is trusted.
 */
public final class DongfengProbe implements CarProbe {
    // Vendor property ids (hex == the decimal the car service uses), from the survey.
    public static final int SOC = 0x21402096;                // PDCU_BMS_SOCCALCULATE, %, CAR_ENERGY
    public static final int RANGE_ACTUAL = 0x214020C3;       // PDCU_ACTUAL_CRUISING_RANGE, km, CAR_ENERGY
    public static final int RANGE_STANDARD = 0x214020C4;     // PDCU_STANDARD_CRUISING_RANGE, km; cycle not identified
    public static final int RANGE_NEDC = 0x21402588;         // PDCU_NEDCCRUISING_RANGE, km (what the display showed)
    public static final int RANGE_DISPLAY = 0x2140219C;      // MP5_CRUIS_RANGE_DISPLAY_SET, raw 3 with the NEDC display; read only
    public static final int ODOMETER = 0x21402229;           // IC_TOTAL_ODMETER, km, CAR_MILEAGE (privileged)
    public static final int CHARGE_STATE = 0x21402249;       // PDCU_CHRG_ST_S31N, 2 = charging
    public static final int PLUG_CONNECTED = 0x214020EF;     // PDCU_CHRG_CNCT_STVALERT, non-zero = plugged
    public static final int DRIVE_READY = 0x21402091;        // PDCU_DRIVE_READY_ST, 1 = READY, CAR_CABIN
    public static final int GEAR = 0x2140208F;               // PDCU_SHIFT_LVL_POSN_ST, 5 = P, CAR_MILEAGE (privileged)
    public static final int SPEED = 0x21400206;              // REAL_VEHICLE_SPEED, CAR_INFO (grantable)
    public static final int SPEED_FINE = 0x2140254C;         // GW_ESC_VEH_SPD_S31N, 0.01 km/h; about 0.5 above SPEED, which rounds down
    public static final int SPEED_FALLBACK = 0x11600207;     // PERF_VEHICLE_SPEED, CAR_SPEED
    public static final int SPEED_DISPLAY = 0x11600208;      // PERF_VEHICLE_SPEED_DISPLAY, float; km/h or m/s, see displaySpeed()
    public static final int TEMP_OUT = 0x21402070;           // AC_AMB_TEMP_ST
    public static final int AC_SYSTEM_OFF = 0x21402075;      // AC_SYSTEM_OFF_ST, 0 = HVAC on
    public static final int AC_BLOW_LEVEL = 0x21402242;      // AC_BLOW_LVL_ST, fan level; 0 seen with the A/C off
    public static final int AC_SET_TEMP = 0x21402077;        // AC_SET_TEMP_ST, 17 seen (likely °C, unconfirmed)
    public static final int DRIVE_MODE = 0x21402090;         // PDCU_DRIVE_MODE_ST, 0 = Comfort, 1 = Eco, 2 = Sport
    public static final int CHARGE_POWER = 0x21402340;       // PDCU_NEW_CHRG_POWER, 0.1 kW
    public static final int CHARGE_CURRENT = 0x21402348;     // PDCU_NEW_CHRG_CURRENT, 0.1 A
    public static final int CHARGE_VOLTAGE = 0x2140233F;     // PDCU_NEW_CHRG_VOLTAGE, 0.1 V
    public static final int TRIP_A = 0x214026B3;             // IC_TRIP_A, 0.1 km (since this start)
    public static final int TRIP_A_ENERGY = 0x214026B1;      // IC_TRIP_AAVG_ENERGY_CONSUMED, 0.01 kWh/100km

    public static final int TRIP_B_KM = 0x214026B8;
    public static final int TRIP_B_KWH100 = 0x214026B6;
    public static final int TRIP_C_KM = 0x214026BD;
    public static final int TRIP_C_KWH100 = 0x214026BB;
    public static final int TRIP_A_TIME_RAW = 0x214026AF;
    public static final int TRIP_B_TIME_RAW = 0x214026B4;
    public static final int TRIP_C_TIME_RAW = 0x214026B9;
    public static final int TRIP_A_SPEED_RAW = 0x21402137;
    public static final int TRIP_B_SPEED_RAW = 0x21402139;
    public static final int TRIP_C_SPEED_RAW = 0x21402143;
    public static final int CHARGE_TARGET = 0x214020DA;       // PDCU_CHRG_SOCFB, % (95 in the charging survey)
    public static final int CHARGE_REMAINING = 0x2140224C;    // PDCU_REMAIN_CHRG_TIME_S31N, probably minutes (354)
    public static final int REGEN_LEVEL = 0x2140251C;         // PDCU_ACTUAL_REGEN_LVL_S31N, raw enum (3)
    public static final int BRAKE_PEDAL = 0x2140209A;         // PDCU_BRAKE_PEDAL_LIGHT_ST, 1 while pressed (361 presses on a logged drive)
    public static final int V12 = 0x21402042;                 // BCM_BATT_VOLT_ST, 12 V battery, scale unknown (277)
    // The car's BMS readings, read-only and raw. Live on the Nammi 1 (30 October, charging): all 0 except health.
    public static final int BMS_PACK_CURRENT = 0x21401156;    // BMS_BATPACK_ACTCUR
    public static final int BMS_PACK_VOLTAGE = 0x21401157;    // BMS_BATPACK_ACTVOL
    public static final int BMS_PACK_POWER = 0x21401158;      // BMS_BATPACK_ACTPWR
    public static final int BMS_CHARGED_ENERGY = 0x21401159;  // BMS_BATCHRG_ENGY
    public static final int SOC_CREDIBILITY = 0x21402145;     // PDCU_BMS_SOCCREDIBILITY
    public static final int BMS_REMAIN_TIME = 0x214020AB;     // PDCU_BMS_REMAIN_CHRG_TIME
    public static final int BATT_HEAT_COOL = 0x21402148;      // PDCU_BATT_HEAT_COOL_ST
    public static final int BATT_HEALTH = 0x214020C6;         // PDCU_BMS_BATT_HEALTH, CAR_CABIN, provenance unverified (1000)

    /** The vendor code for Park on the gear selector (the settings app treats "not 5" as "not in P"). */
    public static final int GEAR_PARK_CODE = 5;
    /** The car's "no value" markers seen in the survey: trip average energy, and a byte/word all-ones. */
    public static final int INVALID_12BIT = 0x7FF, INVALID_BYTE = 0xFF, INVALID_WORD = 0xFFFF;

    private final PropertySource source;

    public DongfengProbe(PropertySource source) { this.source = source; }

    @Override public String name() { return "dongfeng-dfcar"; }

    @Override public List<Signal> available() {
        List<Signal> signals = new ArrayList<>();
        addIf(signals, Signal.SOC, SOC);
        addIf(signals, Signal.RANGE_EST, RANGE_ACTUAL);
        addIf(signals, Signal.RANGE_STANDARD, RANGE_STANDARD);
        addIf(signals, Signal.RANGE_NEDC, RANGE_NEDC);
        addIf(signals, Signal.RANGE_DISPLAY_RAW, RANGE_DISPLAY);
        addIf(signals, Signal.ODOMETER, ODOMETER);
        addIf(signals, Signal.CHARGING, CHARGE_STATE);
        addIf(signals, Signal.PLUG, PLUG_CONNECTED);
        addIf(signals, Signal.READY, DRIVE_READY);
        addIf(signals, Signal.GEAR, GEAR);
        if (source.supported(SPEED) || source.supported(SPEED_FALLBACK)) signals.add(Signal.SPEED);
        addIf(signals, Signal.SPEED_DISPLAY, SPEED_DISPLAY);
        addIf(signals, Signal.TEMP_OUT, TEMP_OUT);
        addIf(signals, Signal.HVAC_ON, AC_SYSTEM_OFF);
        addIf(signals, Signal.DRIVE_MODE, DRIVE_MODE);
        addIf(signals, Signal.POWER, CHARGE_POWER);
        addIf(signals, Signal.VOLTAGE, CHARGE_VOLTAGE);
        addIf(signals, Signal.CURRENT, CHARGE_CURRENT);
        addIf(signals, Signal.TRIP_KM, TRIP_A);
        addIf(signals, Signal.TRIP_KWH100, TRIP_A_ENERGY);
        addIf(signals, Signal.TRIP_B_KM, TRIP_B_KM);
        addIf(signals, Signal.TRIP_B_KWH100, TRIP_B_KWH100);
        addIf(signals, Signal.TRIP_C_KM, TRIP_C_KM);
        addIf(signals, Signal.TRIP_C_KWH100, TRIP_C_KWH100);
        addIf(signals, Signal.TRIP_A_TIME_RAW, TRIP_A_TIME_RAW);
        addIf(signals, Signal.TRIP_B_TIME_RAW, TRIP_B_TIME_RAW);
        addIf(signals, Signal.TRIP_C_TIME_RAW, TRIP_C_TIME_RAW);
        addIf(signals, Signal.TRIP_A_SPEED_RAW, TRIP_A_SPEED_RAW);
        addIf(signals, Signal.TRIP_B_SPEED_RAW, TRIP_B_SPEED_RAW);
        addIf(signals, Signal.TRIP_C_SPEED_RAW, TRIP_C_SPEED_RAW);
        addIf(signals, Signal.CHARGE_TARGET, CHARGE_TARGET);
        addIf(signals, Signal.CHARGE_REMAINING_RAW, CHARGE_REMAINING);
        addIf(signals, Signal.CHARGE_STATE_RAW, CHARGE_STATE);
        addIf(signals, Signal.REGEN_LEVEL_RAW, REGEN_LEVEL);
        addIf(signals, Signal.BRAKE_PEDAL, BRAKE_PEDAL);
        addIf(signals, Signal.V12_RAW, V12);
        addIf(signals, Signal.BATT_HEALTH_RAW, BATT_HEALTH);
        addIf(signals, Signal.BMS_PACK_VOLTAGE_RAW, BMS_PACK_VOLTAGE);
        addIf(signals, Signal.BMS_PACK_CURRENT_RAW, BMS_PACK_CURRENT);
        addIf(signals, Signal.BMS_PACK_POWER_RAW, BMS_PACK_POWER);
        addIf(signals, Signal.BMS_CHARGED_ENERGY_RAW, BMS_CHARGED_ENERGY);
        addIf(signals, Signal.BATT_HEAT_COOL_RAW, BATT_HEAT_COOL);
        addIf(signals, Signal.SOC_CREDIBILITY_RAW, SOC_CREDIBILITY);
        addIf(signals, Signal.BMS_REMAIN_TIME_RAW, BMS_REMAIN_TIME);
        addIf(signals, Signal.AC_FAN_RAW, AC_BLOW_LEVEL);
        addIf(signals, Signal.AC_SET_TEMP_RAW, AC_SET_TEMP);
        return signals;
    }

    private void addIf(List<Signal> signals, Signal signal, int propertyId) {
        if (source.supported(propertyId)) signals.add(signal);
    }

    /** One getter for {@code /v1/speed}; serialised with {@link #read} so the property source sees one reader. */
    @Override public synchronized double speedKph() { return speed(); }

    /** The speedometer's figure for {@code /v1/speed}, read in the same pass as the real speed. */
    @Override public synchronized double displaySpeedKph() { return displaySpeed(speed()); }

    @Override public synchronized Reading read(long nowMs) {
        Reading reading = new Reading(nowMs);

        Integer soc = source.getInt(SOC);
        if (soc != null && soc >= 0 && soc <= 100) reading.set(Signal.SOC, soc);

        setRange(reading, Signal.RANGE_EST, RANGE_ACTUAL);
        setRange(reading, Signal.RANGE_STANDARD, RANGE_STANDARD);
        setRange(reading, Signal.RANGE_NEDC, RANGE_NEDC);
        // The car shows three remaining ranges: actual, standard and NEDC. The CLTC/WLTC-named
        // properties are range increases, not remaining range, so no CLTC or WLTP value is read.
        Integer display = source.getInt(RANGE_DISPLAY);
        if (display != null && display >= 0 && display < INVALID_BYTE) reading.set(Signal.RANGE_DISPLAY_RAW, display);

        // Present only when this firmware grants the exact property-read permissions.
        Integer odo = source.getInt(ODOMETER);
        if (odo != null && odo >= 0 && odo < 2_000_000) reading.set(Signal.ODOMETER, odo);

        Integer charge = source.getInt(CHARGE_STATE);
        if (charge != null) reading.set(Signal.CHARGING, charge == 2);
        // Only 2 (AC) has been seen; the raw code lets the box recognise DC or other states later.
        if (charge != null && charge >= 0 && charge != INVALID_BYTE && charge != INVALID_WORD) reading.set(Signal.CHARGE_STATE_RAW, charge);

        Integer plug = source.getInt(PLUG_CONNECTED);
        if (plug != null) reading.set(Signal.PLUG, plug != 0);

        Integer ready = source.getInt(DRIVE_READY);
        if (ready != null) reading.set(Signal.READY, ready == 1);

        Integer gear = source.getInt(GEAR);
        if (gear != null && gear == GEAR_PARK_CODE) reading.set(Signal.Gear.P); // other codes unknown: never guessed

        double speed = speed();
        if (!Double.isNaN(speed)) reading.set(Signal.SPEED, speed);
        double shown = displaySpeed(speed);
        if (!Double.isNaN(shown)) reading.set(Signal.SPEED_DISPLAY, shown);

        Integer temp = source.getInt(TEMP_OUT);
        if (temp != null && temp != INVALID_WORD) {
            double celsius = temp * 0.5 - 40; // scale to confirm on a drive
            if (celsius > -60 && celsius < 80) reading.set(Signal.TEMP_OUT, celsius);
        }

        Integer acOff = source.getInt(AC_SYSTEM_OFF);
        if (acOff != null && (acOff == 0 || acOff == 1)) reading.set(Signal.HVAC_ON, acOff == 0);

        // Drive mode: pass the raw code (0 Comfort, 1 Eco, 2 Sport); the box maps it to a theme.
        Integer driveMode = source.getInt(DRIVE_MODE);
        if (driveMode != null && driveMode >= 0 && driveMode <= 2) reading.set(Signal.DRIVE_MODE, driveMode);

        setScaled(reading, Signal.POWER, CHARGE_POWER, 0.1);
        setScaled(reading, Signal.VOLTAGE, CHARGE_VOLTAGE, 0.1);
        setScaled(reading, Signal.CURRENT, CHARGE_CURRENT, 0.1);

        Integer trip = source.getInt(TRIP_A);
        if (trip != null && trip != INVALID_WORD && trip >= 0) reading.set(Signal.TRIP_KM, trip * 0.1);

        Integer tripEnergy = source.getInt(TRIP_A_ENERGY);
        if (tripEnergy != null && tripEnergy != INVALID_12BIT && tripEnergy != INVALID_WORD && tripEnergy >= 0) {
            reading.set(Signal.TRIP_KWH100, tripEnergy * 0.01);
        }
        setTrip(reading, Signal.TRIP_B_KM, TRIP_B_KM, 0.1);
        setTrip(reading, Signal.TRIP_B_KWH100, TRIP_B_KWH100, 0.01);
        setTrip(reading, Signal.TRIP_C_KM, TRIP_C_KM, 0.1);
        setTrip(reading, Signal.TRIP_C_KWH100, TRIP_C_KWH100, 0.01);
        setTrip(reading, Signal.TRIP_A_TIME_RAW, TRIP_A_TIME_RAW, 1);
        setTrip(reading, Signal.TRIP_B_TIME_RAW, TRIP_B_TIME_RAW, 1);
        setTrip(reading, Signal.TRIP_C_TIME_RAW, TRIP_C_TIME_RAW, 1);
        setTrip(reading, Signal.TRIP_A_SPEED_RAW, TRIP_A_SPEED_RAW, 1);
        setTrip(reading, Signal.TRIP_B_SPEED_RAW, TRIP_B_SPEED_RAW, 1);
        setTrip(reading, Signal.TRIP_C_SPEED_RAW, TRIP_C_SPEED_RAW, 1);

        Integer target = source.getInt(CHARGE_TARGET);
        if (target != null && target > 0 && target <= 100) reading.set(Signal.CHARGE_TARGET, target);
        setTrip(reading, Signal.CHARGE_REMAINING_RAW, CHARGE_REMAINING, 1);
        Integer regen = source.getInt(REGEN_LEVEL);
        if (regen != null && regen >= 0 && regen < INVALID_BYTE) reading.set(Signal.REGEN_LEVEL_RAW, regen);
        Integer brake = source.getInt(BRAKE_PEDAL);
        if (brake != null && (brake == 0 || brake == 1)) reading.set(Signal.BRAKE_PEDAL, brake == 1);
        Integer v12 = source.getInt(V12);
        if (v12 != null && v12 > 0 && v12 != INVALID_WORD && v12 != INVALID_12BIT) reading.set(Signal.V12_RAW, v12);
        Integer health = source.getInt(BATT_HEALTH);
        if (health != null && health > 0 && health <= 1000) reading.set(Signal.BATT_HEALTH_RAW, health);
        setRaw(reading, Signal.BMS_PACK_VOLTAGE_RAW, BMS_PACK_VOLTAGE);
        setRaw(reading, Signal.BMS_PACK_CURRENT_RAW, BMS_PACK_CURRENT);
        setRaw(reading, Signal.BMS_PACK_POWER_RAW, BMS_PACK_POWER);
        setRaw(reading, Signal.BMS_CHARGED_ENERGY_RAW, BMS_CHARGED_ENERGY);
        setRaw(reading, Signal.BATT_HEAT_COOL_RAW, BATT_HEAT_COOL);
        setRaw(reading, Signal.SOC_CREDIBILITY_RAW, SOC_CREDIBILITY);
        setRaw(reading, Signal.BMS_REMAIN_TIME_RAW, BMS_REMAIN_TIME);
        Integer fan = source.getInt(AC_BLOW_LEVEL);
        if (fan != null && fan >= 0 && fan < INVALID_BYTE) reading.set(Signal.AC_FAN_RAW, fan);
        Integer setTemp = source.getInt(AC_SET_TEMP);
        if (setTemp != null && setTemp > 0 && setTemp < INVALID_BYTE) reading.set(Signal.AC_SET_TEMP_RAW, setTemp);
        return reading;
    }

    /** Vendor speed when the car marks it valid, otherwise the standard speed; NaN when neither reads. */
    private double speed() {
        Integer real = source.getInt(SPEED);
        if (real != null && real >= 0 && real < 400) return finerSpeed(real);
        Float fallback = source.getFloat(SPEED_FALLBACK);
        if (fallback != null && fallback >= 0 && fallback < 400) return fallback;
        return Double.NaN;
    }

    /** The car's finer speed while it agrees with the whole number read beside it; otherwise the whole number. */
    private double finerSpeed(int whole) {
        Integer fine = source.getInt(SPEED_FINE);
        if (fine == null || fine < 0) return whole;
        double kph = fine / 100.0;
        return Math.abs(kph - whole) <= FINE_SPEED_TOLERANCE_KPH ? kph : whole;
    }
    /** On a 32 km drive the two differed by up to 1.3 km/h, reads apart. */
    static final double FINE_SPEED_TOLERANCE_KPH = 3;

    /**
     * Android defines PERF_VEHICLE_SPEED_DISPLAY in m/s, but vendor HALs often report km/h, and this
     * firmware's unit has not been seen moving. So the unit is learnt from the real speed read in the
     * same pass: above {@link #DISPLAY_LEARN_KPH} a speedometer never reads below the real speed
     * (5 % allowed for timing) nor above 110 % + 4 km/h (UN R39 / GB 15082, plus 2 km/h for
     * timing), and only one unit can fit that window. Until one unit fits, and
     * whenever the learnt one stops fitting, only a standstill is passed on; otherwise the value is
     * withheld and the box falls back to the real speed. A wrong figure is never shown.
     */
    double displaySpeed(double real) {
        Float raw = source.getFloat(SPEED_DISPLAY);
        if (raw == null || raw.isNaN() || raw < 0 || raw >= 400) return Double.NaN;
        boolean measurable = !Double.isNaN(real) && real >= DISPLAY_LEARN_KPH;
        if (measurable) {
            boolean kphFits = plausibleDisplay(raw, real), msFits = plausibleDisplay(raw * 3.6, real);
            if (kphFits != msFits) displayScale = kphFits ? 1 : 3.6;
            else if (!kphFits) return Double.NaN; // neither fits this pass: don't trust it
        }
        // 0 only means stopped when the real speed agrees; a cluster holding 0 while moving is withheld.
        if (raw == 0) return Double.isNaN(real) || real < 2 ? 0 : Double.NaN;
        if (displayScale == 0) return Double.NaN;
        double kph = raw * displayScale;
        // Between learning passes, a figure far from the real speed is held back, not shown.
        if (measurable && !plausibleDisplay(kph, real)) return Double.NaN;
        return kph < 400 ? kph : Double.NaN;
    }

    static boolean plausibleDisplay(double shownKph, double realKph) {
        return shownKph >= realKph * 0.95 && shownKph <= realKph * 1.10 + 6;
    }

    /** Learnt multiplier from the raw display value to km/h: 1 (km/h) or 3.6 (m/s); 0 until learnt. */
    private double displayScale;
    static final double DISPLAY_LEARN_KPH = 15;

    /** A raw BMS value, as the car answers it (0 included); the car's "not available" markers are dropped. */
    private void setRaw(Reading reading,Signal signal,int property){
        Integer raw=source.getInt(property);
        if(raw!=null&&raw>=0&&raw!=INVALID_WORD&&raw!=INVALID_12BIT)reading.set(signal,raw);
    }
    private void setTrip(Reading reading,Signal signal,int property,double scale){
        Integer raw=source.getInt(property);
        if(raw!=null&&raw>=0&&raw!=INVALID_WORD&&raw!=INVALID_12BIT)reading.set(signal,raw*scale);
    }

    private void setRange(Reading reading, Signal signal, int property) {
        Integer km = source.getInt(property);
        // 0 is a real empty-battery range; 255 is a possible kilometre value, not a byte flag.
        // The 12-bit/word unavailable markers are outside this shared plausible-range bound.
        if (km != null && km >= 0 && km < 2000) reading.set(signal, km);
    }

    private void setScaled(Reading reading, Signal signal, int propertyId, double scale) {
        Integer raw = source.getInt(propertyId);
        if (raw != null && raw >= 0 && raw != INVALID_WORD && raw != INVALID_12BIT) reading.set(signal, raw * scale);
    }
}
