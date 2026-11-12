package com.softiva.oabl;

/** Small disconnected SOC corrections remain reviewable without opening an automatic prompt. */
final class CarChargeReviewPolicy {
    static boolean eligible(CarEnergyTracker.Event event, boolean automatic) {
        return "charge".equals(event.kind) && event.closed() && counts(event) && (event.gain() > 0||event.measuredKwh()>0)
                && (!automatic || !event.inferred || event.gain() >= 5);
    }
    /** Older records hold one-point rises across gaps; those are SOC rounding, never a charge. */
    static boolean counts(CarEnergyTracker.Event event) {
        return "charge".equals(event.kind) && event.externalCharge
                && (!event.inferred || event.gain() >= CarEnergyTracker.MIN_INFERRED_RISE);
    }
}
