package com.softiva.oabllink;

import org.junit.Test;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class BleFramesTest {
    @Test public void minimumAndLargerMtuRoundTrips() {
        for (int size : new int[]{0, 1, 13, 14, 237, 238, 1024, BleFrames.MAX_BYTES}) {
            byte[] original = new byte[size]; new Random(7).nextBytes(original);
            for (int mtu : new int[]{20, 182, 244, 512, 514}) {
                BleFrames.Assembler assembly = new BleFrames.Assembler();
                List<byte[]> parts = BleFrames.split(original, 65535, mtu);
                byte[] result = null;
                for (byte[] part : parts) result = assembly.accept(part, 100);
                assertArrayEquals(original, result);
            }
        }
    }
    @Test public void rejectsMissingDuplicateAndInterleavedFragments() {
        List<byte[]> parts = BleFrames.split(new byte[40], 1, 20);
        BleFrames.Assembler a = new BleFrames.Assembler();
        assertThrows(IllegalArgumentException.class, () -> a.accept(parts.get(1), 0));
        assertNull(a.accept(parts.get(0), 0));
        assertThrows(IllegalArgumentException.class, () -> a.accept(parts.get(0), 0));
        a.accept(parts.get(0), 0);
        byte[] other = parts.get(1).clone(); other[2] = 2;
        assertThrows(IllegalArgumentException.class, () -> a.accept(other, 1));
        assertArrayEquals(new byte[1], a.accept(BleFrames.split(new byte[1], 9, 20).get(0), 2));
    }
    @Test public void rejectsMalformedOversizedAndExpiredMessages() {
        BleFrames.Assembler a = new BleFrames.Assembler();
        assertThrows(IllegalArgumentException.class, () -> a.accept(new byte[6], 0));
        byte[] wrongVersion = BleFrames.split(new byte[0], 1, 20).get(0); wrongVersion[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> a.accept(wrongVersion, 0));
        List<byte[]> parts = BleFrames.split(new byte[40], 1, 20);
        a.accept(parts.get(0), 0);
        assertThrows(IllegalArgumentException.class, () -> a.accept(parts.get(1), BleFrames.TIMEOUT_MS + 1));
        assertThrows(IllegalArgumentException.class, () -> BleFrames.split(new byte[BleFrames.MAX_BYTES + 1], 1, 20));
        assertThrows(IllegalArgumentException.class, () -> BleFrames.split(new byte[0], 1, 7));
    }
}
