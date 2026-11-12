package com.softiva.oabllink.bluetooth;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class BleHandshakeFailureTest {
    @Test public void lostLinkDuringPairingRemainsRetryableIoFailure() {
        IOException lost = new IOException("Bluetooth disconnected · 8");
        assertSame(lost, BleHandshakeFailure.classify(lost, false));
    }

    @Test public void delayedHandshakeResponseRemainsRetryable() {
        SocketTimeoutException timeout = new SocketTimeoutException("Bluetooth response timed out");
        assertSame(timeout, BleHandshakeFailure.classify(timeout, false));
    }

    @Test public void malformedTransportFrameRetainsItsCause() {
        IllegalArgumentException framing = new IllegalArgumentException("out of order BLE message");
        IOException failure = new IOException("Bluetooth framing failed", framing);
        assertSame(failure, BleHandshakeFailure.classify(failure, false));
        assertSame(framing, failure.getCause());
    }

    @Test public void interruptionStillStopsTheOwnerInsteadOfPausingForUserRetry() {
        InterruptedException interrupted = new InterruptedException("worker stopped");
        assertSame(interrupted, BleHandshakeFailure.classify(interrupted, false));
    }

    @Test public void permissionFailureIsNotReportedAsARejectedPairingCode() {
        IOException revoked = new IOException("Bluetooth permission revoked", new SecurityException("nearby devices"));
        assertSame(revoked, BleHandshakeFailure.classify(revoked, false));
    }

    @Test public void cryptographicRejectionRequiresUserRetryAndRetainsDiagnosticCause() {
        IllegalArgumentException badProof = new IllegalArgumentException("round 3 MAC validation failed");
        SecurityException rejected = new SecurityException("Bluetooth code authentication failed", badProof);
        Exception result = BleHandshakeFailure.classify(rejected, false);
        assertTrue(result instanceof BleGattClient.PairingException);
        assertSame(rejected, result.getCause());
        assertSame(badProof, result.getCause().getCause());
    }

    @Test public void incompleteCryptographicConfirmationAlsoRequiresUserRetry() {
        SecurityException incomplete = new SecurityException("incomplete code authentication");
        Exception result = BleHandshakeFailure.classify(incomplete, false);
        assertTrue(result instanceof BleGattClient.PairingException);
        assertSame(incomplete, result.getCause());
    }

    @Test public void explicitCloseWinsOverAnInFlightAuthenticationError() {
        SecurityException inFlight = new SecurityException("handshake state");
        Exception result = BleHandshakeFailure.classify(inFlight, true);
        assertTrue(result instanceof IOException);
        assertFalse(result instanceof BleGattClient.PairingException);
        assertEquals("Bluetooth link closed", result.getMessage());
        assertSame(inFlight, result.getCause());
    }
}
