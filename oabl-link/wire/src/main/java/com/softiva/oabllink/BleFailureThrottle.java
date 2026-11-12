package com.softiva.oabllink;

/** One failure budget shared by GATT and CoC; timestamps are monotonic milliseconds. */
public final class BleFailureThrottle {
    private int failures;
    private long blockedUntil;

    public synchronized boolean allowed(long now) { return now >= blockedUntil; }
    public synchronized void authenticated() { failures = 0; }
    public synchronized void failed(long now) {
        failures = Math.min(3, failures + 1);
        if (failures >= 3) blockedUntil = now + 30000;
    }
}
