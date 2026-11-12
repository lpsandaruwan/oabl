package com.softiva.oabl;

/**
 * The car's drive mode and the accent each one paints the launcher with when "match the car's drive
 * mode" is on (energy-companion.md §3.9). The codes are {@code PDCU_DRIVE_MODE_ST} as read on the
 * Nammi 1 and passed through unchanged by OABL Link: Comfort = 0, Eco = 1, Sport = 2. The driver's
 * colour map: Eco green (calm, efficient), Comfort blue (relaxed), Sport red (racing). These are the
 * base hues; {@link LauncherTheme} tones each one for the light or dark palette before painting.
 *
 * <p>Pure data with no Android types, so it unit-tests on the JVM. The box never writes the mode to
 * the car; it only reflects what OABL Link reports.
 */
enum DriveMode {
    ECO(1, "Eco", 0xff2e9e5b),
    COMFORT(0, "Comfort", 0xff2f6fd0),
    SPORT(2, "Sports", 0xffd23b3b);

    /** The raw PDCU_DRIVE_MODE_ST code. */
    final int code;
    /** The name shown in settings and the link status. */
    final String label;
    /** The base accent colour (ARGB) before light/dark toning. */
    final int accent;

    DriveMode(int code, String label, int accent) {
        this.code = code;
        this.label = label;
        this.accent = accent;
    }

    /** The mode for a raw code, or null when the code is not one we have confirmed (never guessed). */
    static DriveMode byCode(int code) {
        for (DriveMode mode : values()) if (mode.code == code) return mode;
        return null;
    }
}
