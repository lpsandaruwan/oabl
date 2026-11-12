package com.softiva.oabllink.bluetooth;

import java.io.IOException;

/** Distinguishes a rejected cryptographic exchange from an interrupted BLE transport. */
final class BleHandshakeFailure {
    private BleHandshakeFailure() { }

    static Exception classify(Exception failure, boolean closed) {
        if (closed) return new IOException("Bluetooth link closed", failure);
        // PAKE/ECDH rejects with SecurityException. GATT permission errors are already wrapped
        // as IOExceptions by the transport, so do not classify by a nested SecurityException.
        if (failure instanceof SecurityException)
            return new BleGattClient.PairingException(
                    "OABL Bluetooth code pairing failed · check code and Retry (Link 0.1.3+ required)", failure);
        // Preserve interruption, link loss, framing errors and operation timeouts for the owner.
        return failure;
    }
}
