package com.softiva.oabllink;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * A checksum for the messages sent before the session keys exist (the code pairing), where nothing
 * else can tell a message damaged in transit from one built with the wrong code.
 *
 * Bluetooth stacks on both devices have been seen to change bytes inside long transfers, so a
 * pairing message can arrive with the right length and wrong content. Without this the only visible
 * symptom is a failed zero-knowledge proof, which looks exactly like a mistyped code. The checksum
 * is not a security feature: it carries no secret and an attacker can compute it. It only lets the
 * receiver say "that arrived damaged, send it again" ({@link #NAK}) instead of giving up.
 */
public final class BleIntegrity {
    /** A request for the peer's last pairing message again. No real message begins with 0. */
    public static final byte[] NAK = {0, 1};
    private static final int TRAILER = 4;

    private BleIntegrity() { }

    public static byte[] wrap(byte[] message) {
        CRC32 crc = new CRC32();
        crc.update(message, 0, message.length);
        return ByteBuffer.allocate(message.length + TRAILER).put(message).putInt((int) crc.getValue()).array();
    }

    /** The message inside, or null when it is too short or its checksum does not match. */
    public static byte[] unwrap(byte[] wire) {
        if (wire == null || wire.length <= TRAILER) return null;
        int length = wire.length - TRAILER;
        CRC32 crc = new CRC32();
        crc.update(wire, 0, length);
        if ((int) crc.getValue() != ByteBuffer.wrap(wire, length, TRAILER).getInt()) return null;
        return Arrays.copyOf(wire, length);
    }

    public static boolean isNak(byte[] message) {
        return Arrays.equals(message, NAK);
    }
}
