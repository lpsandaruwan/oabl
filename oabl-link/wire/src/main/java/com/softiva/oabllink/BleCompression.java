package com.softiva.oabllink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Shrinks long answers before they are encrypted. A Bluetooth message is split into 237-byte
 * fragments and a long one stays in flight for seconds, which is when damage has been seen; the
 * answers are JSON, so they compress several times over.
 *
 * Compressed data starts with the gzip magic bytes 0x1F 0x8B, which JSON text never does, so a
 * receiver can tell the two apart without a flag and still read an uncompressed answer from an older
 * peer. {@link #unpack} refuses to inflate past {@link #MAX_OUTPUT} bytes.
 */
public final class BleCompression {
    /** Answers shorter than this are not worth the header. */
    public static final int MIN_BYTES = 400;
    public static final int MAX_OUTPUT = 256 * 1024;

    private BleCompression() { }

    public static byte[] pack(byte[] plain) {
        if (plain == null || plain.length < MIN_BYTES) return plain;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(plain.length / 2);
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(plain); }
            byte[] packed = bytes.toByteArray();
            // Keep the original unless it is clearly smaller.
            return packed.length < plain.length - 32 ? packed : plain;
        } catch (IOException e) { return plain; }
    }

    public static byte[] unpack(byte[] data) throws IOException {
        if (data == null || data.length < 2 || data[0] != (byte) 0x1F || data[1] != (byte) 0x8B) return data;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                if (out.size() + read > MAX_OUTPUT) throw new IOException("Compressed Bluetooth answer too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
