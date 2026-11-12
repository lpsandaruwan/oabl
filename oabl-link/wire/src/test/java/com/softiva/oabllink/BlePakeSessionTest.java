package com.softiva.oabllink;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BlePakeSessionTest {
    private static final byte[] KEY = new byte[32];
    private BlePakeSession[] pair() {
        BlePakeSession c = new BlePakeSession(false, KEY), s = new BlePakeSession(true, KEY);
        byte[] message = c.begin();
        for (int i = 0; i < 8 && !c.authenticated(); i++) {
            message = c.handshake(s.handshake(message));
        }
        assertNull(message); assertTrue(c.authenticated()); assertTrue(s.authenticated());
        return new BlePakeSession[]{c, s};
    }
    @Test public void mutuallyConfirmsCodeThenEncryptsBothDirections() {
        BlePakeSession[] p = pair(); byte[] clear = "private car data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = p[0].seal(clear);
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("private car data"));
        assertArrayEquals(clear, p[1].open(encrypted));
        assertArrayEquals(clear, p[0].open(p[1].seal(clear)));
    }
    @Test public void wrongCodeFailsMutualConfirmationBeforeAnyData() {
        byte[] wrong = KEY.clone(); wrong[3] = 1;
        BlePakeSession c = new BlePakeSession(false, wrong), s = new BlePakeSession(true, KEY);
        byte[] c2 = c.handshake(s.handshake(c.begin()));
        byte[] c3 = c.handshake(s.handshake(c2));
        assertThrows(SecurityException.class, () -> s.handshake(c3));
        assertFalse(s.authenticated()); assertFalse(c.authenticated());
        assertThrows(SecurityException.class, () -> s.seal(new byte[]{1}));
    }
    @Test public void rejectsLegacyVersionReflectionAndMalformedIntegerLengths() {
        BlePakeSession c = new BlePakeSession(false, KEY), s = new BlePakeSession(true, KEY);
        byte[] hello = c.begin(); assertThrows(SecurityException.class, () -> c.handshake(hello));
        byte[] legacy = hello.clone(); legacy[0] = 2;
        assertThrows(SecurityException.class, () -> s.handshake(legacy));
        BlePakeSession sender = new BlePakeSession(false, KEY), receiver = new BlePakeSession(true, KEY);
        byte[] bad = sender.begin(); int integerOffset = 4 + "oabl-ai-box/v3".length();
        bad[integerOffset] = 0x7f; bad[integerOffset + 1] = (byte) 0xff;
        assertThrows(SecurityException.class, () -> receiver.handshake(bad));
    }
    @Test public void rejectsInvalidGroupProofAndTrailingBytes() {
        BlePakeSession c = new BlePakeSession(false, KEY), s = new BlePakeSession(true, KEY);
        byte[] bad = c.begin(); bad[bad.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> s.handshake(bad));
        BlePakeSession c2 = new BlePakeSession(false, KEY), s2 = new BlePakeSession(true, KEY);
        byte[] hello = c2.begin(), extra = Arrays.copyOf(hello, hello.length + 1);
        assertThrows(SecurityException.class, () -> s2.handshake(extra));
    }
    @Test public void tamperReplayDirectionAndCrossSessionRecordsFailClosed() {
        BlePakeSession[] p = pair(); byte[] message = p[0].seal(new byte[]{1}); p[1].open(message);
        assertThrows(SecurityException.class, () -> p[1].open(message)); assertFalse(p[1].authenticated());
        BlePakeSession[] a = pair(), b = pair(); byte[] record = a[0].seal(new byte[]{2});
        assertThrows(SecurityException.class, () -> b[1].open(record));
        assertThrows(SecurityException.class, () -> a[0].open(record));
        BlePakeSession[] t = pair(); byte[] corrupt = t[0].seal(new byte[]{3}); corrupt[corrupt.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> t[1].open(corrupt));
    }
    @Test public void fullHandshakeSurvivesMinimumAndTrialMtuFraming() {
        for (int valueSize : new int[]{20, 244}) {
            BlePakeSession c = new BlePakeSession(false, KEY), s = new BlePakeSession(true, KEY);
            byte[] message = c.begin(); int id = 0;
            for (int i = 0; i < 8 && !c.authenticated(); i++) {
                byte[] reply = s.handshake(framed(message, ++id, valueSize));
                message = c.handshake(framed(reply, ++id, valueSize));
            }
            assertNull(message); assertTrue(c.authenticated()); assertTrue(s.authenticated());
        }
    }
    @Test public void encryptedFailuresHaveDistinctWordingAndRemainClosed() {
        BlePakeSession[] p = pair(); byte[] record = p[0].seal(new byte[]{1});
        byte[] corrupt = record.clone(); corrupt[corrupt.length - 1] ^= 1;
        // A first damaged record is recoverable: the intact one still opens afterwards.
        SecurityException damaged = assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[1].open(corrupt));
        assertTrue(damaged.getMessage().contains("expected=1 · received=1"));
        assertTrue(p[1].authenticated());
        for (int i = 0; i < BleSecureSession.MAX_DAMAGED_IN_A_ROW - 1; i++) {
            assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[1].open(corrupt));
        }
        // Persistent damage closes the session with the usual wording and it cannot be revived.
        SecurityException failure = assertThrows(SecurityException.class, () -> p[1].open(corrupt));
        assertEquals("Bluetooth encrypted data rejected", failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains("expected=1 · received=1"));
        assertFalse(p[1].authenticated());
        assertThrows(SecurityException.class, () -> p[1].open(record));
        assertThrows(SecurityException.class, () -> p[1].seal(new byte[]{1}));
        BlePakeSession[] oversized = pair();
        SecurityException size = assertThrows(SecurityException.class, () -> oversized[0].seal(new byte[BleFrames.MAX_BYTES]));
        assertEquals("Bluetooth encrypted data rejected", size.getMessage());
        assertFalse(oversized[0].authenticated());
    }
    private byte[] framed(byte[] message, int id, int valueSize) {
        BleFrames.Assembler assembler = new BleFrames.Assembler(); byte[] complete = null;
        for (byte[] fragment : BleFrames.split(message, id, valueSize)) complete = assembler.accept(fragment, 1);
        assertArrayEquals(message, complete); return complete;
    }
}
