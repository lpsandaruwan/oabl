package com.softiva.oabllink;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BleSecureSessionTest {
    private static final byte[] KEY = new byte[32];
    private BleSecureSession[] pair() {
        BleSecureSession c = new BleSecureSession(false, KEY), s = new BleSecureSession(true, KEY);
        assertNull(c.handshake(s.handshake(c.handshake(s.handshake(c.begin())))));
        assertTrue(c.authenticated()); assertTrue(s.authenticated()); return new BleSecureSession[]{c, s};
    }
    @Test public void mutuallyAuthenticatesAndEncryptsBothDirections() {
        BleSecureSession[] p = pair(); byte[] message = "private telemetry".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = p[0].seal(message);
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("private telemetry"));
        assertArrayEquals(message, p[1].open(encrypted));
        assertArrayEquals(message, p[0].open(p[1].seal(message)));
        assertArrayEquals(new byte[0], p[1].open(p[0].seal(new byte[0])));
    }
    @Test public void wrongPairingCodeFailsClosed() {
        byte[] wrong = KEY.clone(); wrong[0] = 1;
        BleSecureSession c = new BleSecureSession(false, wrong), s = new BleSecureSession(true, KEY);
        byte[] proof = c.handshake(s.handshake(c.begin()));
        assertThrows(SecurityException.class, () -> s.handshake(proof)); assertFalse(s.authenticated());
        assertThrows(SecurityException.class, () -> s.handshake(proof));
    }
    @Test public void tamperReplayWrongDirectionAndDifferentSessionsFail() {
        BleSecureSession[] p = pair(); byte[] record = p[0].seal(new byte[]{1, 2});
        p[1].open(record);
        final BleSecureSession replayReceiver = p[1]; final byte[] replay = record;
        assertThrows(SecurityException.class, () -> replayReceiver.open(replay));
        p = pair(); final BleSecureSession receiver = p[1]; record = p[0].seal(new byte[]{1});
        record[record.length - 1] ^= 1; final byte[] tampered = record;
        assertThrows(BleSecureSession.RecordCorrupted.class, () -> receiver.open(tampered)); assertTrue(receiver.authenticated());
        BleSecureSession[] a = pair(), b = pair();
        byte[] first = a[0].seal(new byte[]{1});
        assertThrows(SecurityException.class, () -> b[1].open(first));
        assertThrows(SecurityException.class, () -> a[0].open(first));
    }
    @Test public void rejectsRecordsBeforeProofAndOversizedOrMalformedHandshakes() {
        BleSecureSession c = new BleSecureSession(false, KEY), s = new BleSecureSession(true, KEY);
        assertThrows(SecurityException.class, () -> c.seal(new byte[1]));
        byte[] hello = c.begin(); hello[2] = 1;
        assertThrows(SecurityException.class, () -> s.handshake(hello));
        BleSecureSession[] p = pair();
        assertThrows(SecurityException.class, () -> p[0].seal(new byte[BleFrames.MAX_BYTES]));
    }
    @Test public void everySessionHasFreshKeysAndBindsTheTranscript() {
        BleSecureSession a = new BleSecureSession(false, KEY), b = new BleSecureSession(false, KEY), s = new BleSecureSession(true, KEY);
        byte[] ah = a.begin(), bh = b.begin(); assertFalse(Arrays.equals(ah, bh));
        byte[] serverHello = s.handshake(ah), wrongTranscriptProof = b.handshake(serverHello);
        assertThrows(SecurityException.class, () -> s.handshake(wrongTranscriptProof));
    }
    @Test public void replayReportsExpectedAndReceivedSequenceAndEncryptedFingerprint() throws Exception {
        BleSecureSession[] p = pair();
        byte[] record = p[0].seal("private replay payload".getBytes(StandardCharsets.UTF_8));
        p[1].open(record);
        SecurityException failure = assertThrows(SecurityException.class, () -> p[1].open(record));
        String digest = String.format("%064x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(record)));
        assertTrue(failure.getMessage().contains("record replay/order"));
        assertTrue(failure.getMessage().contains("expected=2 · received=1 · bytes=" + record.length));
        assertTrue(failure.getMessage().endsWith("sha256=" + digest));
        assertEquals("record replay/order", failure.getCause().getMessage());
        assertFalse(failure.getMessage().contains("private replay payload"));
        assertFalse(p[1].authenticated());
    }
    @Test public void tamperDiagnosticsPreserveCipherCauseAndCannotReviveFailedSession() {
        BleSecureSession[] p = pair();
        byte[] record = p[0].seal("private tamper payload".getBytes(StandardCharsets.UTF_8));
        byte[] corrupt = record.clone(); corrupt[corrupt.length - 1] ^= 1;
        SecurityException failure = assertThrows(SecurityException.class, () -> p[1].open(corrupt));
        assertTrue(failure.getMessage().contains("record authentication failed · expected=1 · received=1 · bytes=" + corrupt.length));
        assertTrue(failure.getMessage().matches(".*sha256=[0-9a-f]{64}"));
        assertTrue(failure.getCause().getCause() instanceof javax.crypto.AEADBadTagException);
        assertFalse(failure.getMessage().contains("private tamper payload"));
        assertTrue("a damaged record alone does not close the session", p[1].authenticated());
        // Persistent damage still closes it for good, and it cannot be revived afterwards.
        for (int i = 0; i < BleSecureSession.MAX_DAMAGED_IN_A_ROW - 1; i++) {
            assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[1].open(corrupt));
        }
        SecurityException last = assertThrows(SecurityException.class, () -> p[1].open(corrupt));
        assertFalse(last instanceof BleSecureSession.RecordCorrupted);
        assertFalse(p[1].authenticated());
        assertThrows(SecurityException.class, () -> p[1].open(record));
        assertThrows(SecurityException.class, () -> p[1].seal(new byte[]{1}));
    }
    @Test public void aDamagedRecordCanBeFollowedByTheIntactOne() {
        BleSecureSession[] p = pair();
        byte[] record = p[1].seal("answer".getBytes(StandardCharsets.UTF_8));
        byte[] damaged = record.clone();
        for (int i = 14; i < 24; i++) damaged[i] ^= 0x5a;
        assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[0].open(damaged));
        assertTrue(p[0].authenticated());
        assertArrayEquals("answer".getBytes(StandardCharsets.UTF_8), p[0].open(record));
        // the sequence moved on exactly once, so the next record follows normally
        assertArrayEquals(new byte[]{7}, p[0].open(p[1].seal(new byte[]{7})));
    }
    @Test public void theDamagedRunCounterRestartsAfterAGoodRecord() {
        BleSecureSession[] p = pair();
        for (int round = 0; round < 3; round++) {
            byte[] record = p[1].seal(new byte[]{(byte) round});
            byte[] damaged = record.clone(); damaged[damaged.length - 1] ^= 1;
            for (int i = 0; i < BleSecureSession.MAX_DAMAGED_IN_A_ROW; i++) {
                assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[0].open(damaged));
            }
            assertArrayEquals(new byte[]{(byte) round}, p[0].open(record));
        }
        assertTrue(p[0].authenticated());
    }
    @Test public void aWrongSequenceIsNeverTreatedAsDamage() {
        BleSecureSession[] p = pair();
        byte[] first = p[1].seal(new byte[]{1}), second = p[1].seal(new byte[]{2});
        SecurityException failure = assertThrows(SecurityException.class, () -> p[0].open(second));
        assertFalse(failure instanceof BleSecureSession.RecordCorrupted);
        assertFalse(p[0].authenticated());
    }
}
