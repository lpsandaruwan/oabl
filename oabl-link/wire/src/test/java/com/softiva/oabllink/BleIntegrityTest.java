package com.softiva.oabllink;

import static org.junit.Assert.*;

import org.junit.Test;

public class BleIntegrityTest {
    @Test public void aWrappedMessageComesBackUnchanged() {
        byte[] message = {3, 1, 9, 8, 7};
        assertArrayEquals(message, BleIntegrity.unwrap(BleIntegrity.wrap(message)));
    }

    @Test public void anyChangedByteIsDetected() {
        byte[] wire = BleIntegrity.wrap(new byte[]{3, 1, 9, 8, 7, 6, 5});
        for (int i = 0; i < wire.length; i++) {
            byte[] damaged = wire.clone();
            damaged[i] ^= 0x10;
            assertNull("byte " + i, BleIntegrity.unwrap(damaged));
        }
    }

    @Test public void tooShortOrMissingIsRejected() {
        assertNull(BleIntegrity.unwrap(null));
        assertNull(BleIntegrity.unwrap(new byte[0]));
        assertNull(BleIntegrity.unwrap(new byte[4]));
    }

    @Test public void theRequestToSendAgainSurvivesTheRoundTripAndIsNotARealMessage() {
        byte[] nak = BleIntegrity.unwrap(BleIntegrity.wrap(BleIntegrity.NAK));
        assertTrue(BleIntegrity.isNak(nak));
        assertFalse(BleIntegrity.isNak(new byte[]{BlePakeSession.VERSION, 1}));
        assertFalse(BleIntegrity.isNak(new byte[]{0, 2}));
        assertNotEquals("no pairing message begins with 0", 0, BlePakeSession.VERSION);
    }
}
