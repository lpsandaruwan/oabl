package com.softiva.oabllink;

import java.io.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class BleStreamFramingTest {
    private byte[] encoded(byte[] message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); BleStreamFraming.write(out, message); return out.toByteArray();
    }
    private InputStream chunks(byte[] bytes, int maximum) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(maximum, length));
            }
        };
    }
    private byte[] transferred(byte[] message, int chunk) throws IOException {
        return BleStreamFraming.read(chunks(encoded(message), chunk));
    }
    private BlePakeSession[] pair() throws IOException {
        BlePakeSession c = new BlePakeSession(false, new byte[32]), s = new BlePakeSession(true, new byte[32]);
        byte[] message = c.begin();
        for (int n = 0; !c.authenticated() && n < 8; n++)
            message = c.handshake(transferred(s.handshake(transferred(message, 1)), 7));
        assertNull(message); assertTrue(c.authenticated()); assertTrue(s.authenticated());
        return new BlePakeSession[]{c, s};
    }
    @Test public void acceptsDynamicPsmRoutingMetadataAcrossUnsignedBoundary() throws Exception {
        for (int psm : new int[]{1, 128, 255, 256, 65535}) assertEquals(psm, BleStreamFraming.psm(BleStreamFraming.offer(psm)));
    }
    @Test public void rejectsUnsupportedOrMalformedRoutingMetadata() {
        for (byte[] bad : new byte[][]{null, new byte[0], new byte[]{'O','S',2,0,(byte) 128}, new byte[]{'O','S',1,0,0}, new byte[]{'O','X',1,0,(byte) 128}})
            assertThrows(IOException.class, () -> BleStreamFraming.psm(bad));
        assertThrows(IllegalArgumentException.class, () -> BleStreamFraming.offer(0));
        assertThrows(IllegalArgumentException.class, () -> BleStreamFraming.offer(65536));
    }
    @Test public void arbitraryShortReadsPreserveLargeMessagesAndLimitBoundary() throws Exception {
        Random random = new Random(7);
        for (int size : new int[]{1, 117, 1619, 3233, BleFrames.MAX_BYTES}) {
            byte[] message = new byte[size]; random.nextBytes(message);
            for (int chunk : new int[]{1, 7, 237, 4096}) assertArrayEquals(message, transferred(message, chunk));
        }
    }
    @Test public void coalescedMessagesDoNotLoseBoundariesOrConsumeTheNextRecord() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[][] messages = {new byte[]{1}, new byte[3233], new byte[]{7, 8, 9}};
        for (byte[] message : messages) BleStreamFraming.write(bytes, message);
        InputStream input = chunks(bytes.toByteArray(), 3);
        for (byte[] message : messages) assertArrayEquals(message, BleStreamFraming.read(input));
        assertEquals(-1, input.read());
    }
    @Test public void truncatedPrefixOrBodyNeverProducesAPartialMessage() throws Exception {
        byte[] encoded = encoded(new byte[17]);
        for (int length : new int[]{0, 1, 2, 3, 4, 8, encoded.length - 1}) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(EOFException.class, () -> BleStreamFraming.read(chunks(truncated, 1)));
        }
    }
    @Test public void invalidLengthsFailBeforeAllocatingOrReadingPayload() throws Exception {
        for (int invalid : new int[]{-1, 0, BleFrames.MAX_BYTES + 1, Integer.MAX_VALUE}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); new DataOutputStream(out).writeInt(invalid);
            assertThrows(IOException.class, () -> BleStreamFraming.read(new ByteArrayInputStream(out.toByteArray())));
        }
    }
    @Test public void invalidOutgoingMessageWritesNothing() {
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[BleFrames.MAX_BYTES + 1]}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> BleStreamFraming.write(out, invalid)); assertEquals(0, out.size());
        }
    }
    @Test public void ioFailurePropagatesInsteadOfReturningInventedBytes() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("link dropped"); }
        };
        assertEquals("link dropped", assertThrows(IOException.class, () -> BleStreamFraming.read(broken)).getMessage());
    }
    @Test public void samePakeAndLargeEncryptedRecordsWorkInBothDirections() throws Exception {
        BlePakeSession[] p = pair(); Random random = new Random(19);
        for (int size : new int[]{1, 3207, 15613, BleFrames.MAX_BYTES - 26}) {
            byte[] clear = new byte[size]; random.nextBytes(clear);
            assertArrayEquals(clear, p[1].open(transferred(p[0].seal(clear), 13)));
            assertArrayEquals(clear, p[0].open(transferred(p[1].seal(clear), 1)));
        }
    }
    @Test public void corruptionStillFailsClosedAndCannotBeRetriedOnTheSameKeys() throws Exception {
        BlePakeSession[] p = pair(); byte[] record = p[1].seal(new byte[3207]);
        byte[] damaged = encoded(record); damaged[damaged.length - 1] ^= 1;
        byte[] received = BleStreamFraming.read(chunks(damaged, 7));
        for (int i = 0; i < BleSecureSession.MAX_DAMAGED_IN_A_ROW; i++) {
            assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[0].open(received));
            assertTrue(p[0].authenticated());
        }
        assertThrows(SecurityException.class, () -> p[0].open(received));
        assertFalse(p[0].authenticated());
        assertThrows(SecurityException.class, () -> p[0].open(record));
    }
    @Test public void aDamagedLongRecordIsRecoveredByTheIntactCopy() throws Exception {
        BlePakeSession[] p = pair(); byte[] record = p[1].seal(new byte[3207]);
        byte[] damaged = encoded(record); damaged[damaged.length - 1] ^= 1;
        byte[] received = BleStreamFraming.read(chunks(damaged, 7));
        assertThrows(BleSecureSession.RecordCorrupted.class, () -> p[0].open(received));
        assertEquals(3207, p[0].open(record).length);
    }
    @Test public void duplicateTransportRecordDoesNotBypassReplayProtection() throws Exception {
        BlePakeSession[] p = pair(); byte[] record = p[1].seal(new byte[]{1});
        p[0].open(transferred(record, 1));
        assertThrows(SecurityException.class, () -> p[0].open(transferred(record, 2)));
        assertFalse(p[0].authenticated());
    }
    @Test public void wrongCodeStillFailsBeforeAnyTelemetry() throws Exception {
        byte[] wrong = new byte[32]; wrong[0] = 1;
        BlePakeSession c = new BlePakeSession(false, wrong), s = new BlePakeSession(true, new byte[32]);
        byte[] second = c.handshake(transferred(s.handshake(transferred(c.begin(), 3)), 7));
        byte[] confirmation = c.handshake(transferred(s.handshake(transferred(second, 2)), 5));
        assertThrows(SecurityException.class, () -> s.handshake(transferred(confirmation, 1)));
        assertFalse(c.authenticated()); assertFalse(s.authenticated());
        assertThrows(SecurityException.class, () -> s.seal(new byte[]{1}));
    }
}
