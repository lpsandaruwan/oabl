package com.softiva.oabl;

/** Retry decisions after a BLE failure; the failed proof or encrypted record is never accepted. */
final class BleReconnectPolicy {
    private BleReconnectPolicy() { }

    /** A proof can fail because its Bluetooth bytes were damaged. Never latch off retries; slow
     * repeated unverified attempts so a genuinely wrong code does not hammer the head unit. */
    static long retryDelayMs(boolean authenticatedBefore, int consecutiveProofFailures) {
        return authenticatedBefore ? 2000 : consecutiveProofFailures >= 3 ? 30000 : 5000;
    }

    /** Both transports require the same PAKE proof. A failed native handshake can safely try GATT. */
    static boolean fallBackToGatt(boolean nativeStream, boolean authenticatedBefore, boolean pairingFailure) {
        return nativeStream;
    }

    /** After a stream failure GATT carries the link for a while, then the faster stream is tried again. */
    static final long GATT_SPELL_MS = 120_000;

    /** Whether the next connection may use the native stream; {@code gattUntil} is 0 when none failed. */
    static boolean tryNativeStream(long gattUntil, long now) {
        return now >= gattUntil;
    }
}
