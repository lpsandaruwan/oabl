package com.softiva.oabl;

/** Estimates destination battery from the route distance and OABL's measured driving economy. */
final class ArrivalBattery {
    static final class Result {
        final double percent;
        final boolean belowReserve;

        Result(double percent, boolean belowReserve) {
            this.percent = percent;
            this.belowReserve = belowReserve;
        }
    }

    private ArrivalBattery() { }

    /** Returns null unless every input is a real, usable reading. */
    static Result estimate(double currentPercent, double remainingKm, double kwhPerKm,
                           double usableKwh, double reservePercent) {
        if (!Double.isFinite(currentPercent) || currentPercent < 0 || currentPercent > 100
                || !Double.isFinite(remainingKm) || remainingKm < 0
                || !Double.isFinite(kwhPerKm) || kwhPerKm <= 0 || kwhPerKm > 1
                || !Double.isFinite(usableKwh) || usableKwh <= 0
                || !Double.isFinite(reservePercent) || reservePercent < 0 || reservePercent > 100) {
            return null;
        }
        double usedPoints = remainingKm * kwhPerKm / usableKwh * 100;
        double arrival = Math.max(0, Math.min(100, currentPercent - usedPoints));
        return new Result(arrival, arrival < reservePercent);
    }
}
