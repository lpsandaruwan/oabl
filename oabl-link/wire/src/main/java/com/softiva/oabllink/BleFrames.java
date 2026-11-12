package com.softiva.oabllink;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Bounded, ordered BLE messages, including on a minimum 23-byte ATT MTU. */
public final class BleFrames {
    public static final int HEADER = 7, MAX_BYTES = 32768, MAX_FRAGMENTS = 4096;
    public static final long TIMEOUT_MS = 15000;
    private BleFrames() { }

    public static List<byte[]> split(byte[] message, int id, int valueSize) {
        if (message.length > MAX_BYTES || valueSize <= HEADER) throw new IllegalArgumentException("message/value size");
        int capacity = Math.min(512, valueSize) - HEADER;
        int count = Math.max(1, (message.length + capacity - 1) / capacity);
        if (count > MAX_FRAGMENTS) throw new IllegalArgumentException("too many fragments");
        List<byte[]> frames = new ArrayList<>();
        for (int index = 0, offset = 0; index < count; index++) {
            int length = Math.min(capacity, message.length - offset);
            byte[] frame = new byte[HEADER + length];
            frame[0] = 1; put(frame, 1, id); put(frame, 3, index); put(frame, 5, count);
            System.arraycopy(message, offset, frame, HEADER, length);
            frames.add(frame); offset += length;
        }
        return frames;
    }
    private static void put(byte[] b, int at, int n) { b[at] = (byte) (n >>> 8); b[at + 1] = (byte) n; }
    private static int get(byte[] b, int at) { return (b[at] & 255) << 8 | b[at + 1] & 255; }

    public static final class Assembler {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int id = -1, count, next;
        private long began;
        public void clear() { bytes.reset(); id = -1; count = next = 0; }
        public byte[] accept(byte[] frame, long monotonicMs) {
            if (frame == null || frame.length < HEADER || frame.length > 512 || frame[0] != 1) {
                clear(); throw new IllegalArgumentException("bad BLE frame");
            }
            int incoming = get(frame, 1), index = get(frame, 3), total = get(frame, 5);
            if (total < 1 || total > MAX_FRAGMENTS || index >= total) {
                clear(); throw new IllegalArgumentException("bad BLE fragment count");
            }
            if (id != -1 && monotonicMs - began > TIMEOUT_MS) {
                clear(); throw new IllegalArgumentException("BLE assembly timed out");
            }
            if (id == -1) {
                if (index != 0) throw new IllegalArgumentException("missing first BLE fragment");
                id = incoming; count = total; began = monotonicMs;
            }
            if (id != incoming || count != total || index != next || bytes.size() + frame.length - HEADER > MAX_BYTES) {
                clear(); throw new IllegalArgumentException("out of order or oversized BLE message");
            }
            bytes.write(frame, HEADER, frame.length - HEADER); next++;
            if (next != count) return null;
            byte[] complete = bytes.toByteArray(); clear(); return complete;
        }
    }
}
