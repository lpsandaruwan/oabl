package com.softiva.oabllink.app;

import android.content.Context;
import android.provider.Settings;
import com.softiva.oabllink.CarProbe;
import com.softiva.oabllink.Illumination;
import com.softiva.oabllink.Reading;
import com.softiva.oabllink.Signal;
import java.util.ArrayList;
import java.util.List;

/** Adds Android-head-unit signals that do not come from Dongfeng's vehicle-property service. */
final class HeadUnitSignalProbe implements CarProbe {
    private static final String OPEN_HEADUNIT_PREFS = "settings";
    private static final String ILLUMINATION_INVERTED = "car-signal-invert";

    private final Context context;
    private final CarProbe car;

    HeadUnitSignalProbe(Context context, CarProbe car) {
        this.context = context.getApplicationContext();
        this.car = car;
    }

    @Override public String name() { return car.name(); }

    @Override public List<Signal> available() {
        List<Signal> result = new ArrayList<>(car.available());
        if (readCarMode() != null && !result.contains(Signal.ILLUMINATION)) result.add(Signal.ILLUMINATION);
        return result;
    }

    @Override public Reading read(long nowMs) {
        Reading source = car.read(nowMs);
        Reading result = new Reading(source.timeMs());
        for (java.util.Map.Entry<Signal, Double> value : source.values().entrySet()) {
            result.set(value.getKey(), value.getValue());
        }
        Integer carMode = readCarMode();
        if (carMode != null) {
            // The combined app keeps Open Headunit UI and Link in separate processes on Android 10.
            // MODE_MULTI_PROCESS makes a calibration change visible without restarting Link.
            boolean inverted = context.getSharedPreferences(OPEN_HEADUNIT_PREFS,
                            Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS)
                    .getBoolean(ILLUMINATION_INVERTED, false);
            result.set(Signal.ILLUMINATION, Illumination.night(carMode, inverted));
        }
        return result;
    }

    @Override public double speedKph() { return car.speedKph(); }
    @Override public double displaySpeedKph() { return car.displaySpeedKph(); }

    private Integer readCarMode() {
        try {
            String raw = Settings.System.getString(context.getContentResolver(), "car_mode");
            return raw == null ? null : Integer.parseInt(raw.trim());
        } catch (RuntimeException invalidOrUnavailable) {
            return null;
        }
    }
}
