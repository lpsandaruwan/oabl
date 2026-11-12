package com.softiva.oabllink;

/** Normalizes the vendor ILL+ value after the driver calibrates its polarity. */
public final class Illumination {
    private Illumination() { }

    public static boolean night(int rawCarMode, boolean inverted) {
        boolean defaultNight = rawCarMode != 0;
        return inverted ? !defaultNight : defaultNight;
    }
}
