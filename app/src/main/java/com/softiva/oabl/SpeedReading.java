package com.softiva.oabl;

/** Shared speed-source decision for Home and the floating navigation widget. */
final class SpeedReading {
    final boolean fromCar;
    final int kph;
    private SpeedReading(boolean fromCar, int kph) { this.fromCar = fromCar; this.kph = kph; }
    boolean known() { return kph >= 0; }
    /**
     * The speed with one decimal, for a car that sends finer speed; null for a whole number, as from
     * a Link that hasn't sent a fraction yet or a car standing still.
     */
    static String tenths(double kph, boolean fine) {
        return fine && Double.isFinite(kph) && kph >= 0.05 && kph <= 220 ? String.format(java.util.Locale.US, "%.1f", kph) : null;
    }
    static SpeedReading select(boolean expectsCar, double carSpeed, int gpsSpeed, boolean gpsReliable) {
        if (expectsCar) return new SpeedReading(true,
                Double.isFinite(carSpeed) && carSpeed >= 0 && carSpeed <= 220 ? (int) Math.round(carSpeed) : -1);
        return new SpeedReading(false, gpsReliable && gpsSpeed >= 0 ? gpsSpeed : -1);
    }
}
