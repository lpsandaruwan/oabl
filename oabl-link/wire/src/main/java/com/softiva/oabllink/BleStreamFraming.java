package com.softiva.oabllink;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Message boundaries for LE CoC. The existing PAKE and encrypted records remain unchanged. */
public final class BleStreamFraming {
    private BleStreamFraming() { }

    /** Public routing metadata only; never a code, key or permission to read telemetry. */
    public static byte[] offer(int psm) {
        if (psm < 1 || psm > 65535) throw new IllegalArgumentException("Invalid LE PSM");
        return new byte[]{'O', 'S', 1, (byte) (psm >>> 8), (byte) psm};
    }

    public static int psm(byte[] offer) throws IOException {
        if (offer == null || offer.length != 5 || offer[0] != 'O' || offer[1] != 'S' || offer[2] != 1)
            throw new IOException("Unsupported Bluetooth stream offer");
        int psm = (offer[3] & 255) << 8 | offer[4] & 255;
        if (psm == 0) throw new IOException("Invalid Bluetooth stream PSM");
        return psm;
    }

    public static byte[] read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int length = data.readInt();
        if (length < 1 || length > BleFrames.MAX_BYTES) throw new IOException("Invalid Bluetooth stream message length");
        byte[] message = new byte[length];
        data.readFully(message);
        return message;
    }

    public static void write(OutputStream output, byte[] message) throws IOException {
        if (message == null || message.length < 1 || message.length > BleFrames.MAX_BYTES)
            throw new IOException("Invalid Bluetooth stream message length");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(message.length); data.write(message); data.flush();
    }
}
