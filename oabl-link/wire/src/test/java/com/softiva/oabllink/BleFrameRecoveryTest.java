package com.softiva.oabllink;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class BleFrameRecoveryTest {
    @Test public void malformedMiddleFragmentDropsOldTailAndAcceptsResend() {
        byte[] message = new byte[900];
        for (int i = 0; i < message.length; i++) message[i] = (byte) i;
        List<byte[]> original = BleFrames.split(message, 10, 244);
        BleFrameRecovery recovery = new BleFrameRecovery();
        assertNull(recovery.accept(original.get(0), 1));
        byte[] damaged = original.get(1).clone(); damaged[4] = 9;
        assertThrows(IllegalArgumentException.class, () -> recovery.accept(damaged, 2));
        for (int i = 2; i < original.size(); i++) assertNull(recovery.accept(original.get(i), 3 + i));
        byte[] result = null;
        for (byte[] frame : BleFrames.split(message, 11, 244)) result = recovery.accept(frame, 20);
        assertArrayEquals(message, result);
    }

    @Test public void timedOutPartialReplyIgnoresTailUntilFreshFirstFragment() {
        byte[] message = new byte[500];
        List<byte[]> original = BleFrames.split(message, 20, 244);
        BleFrameRecovery recovery = new BleFrameRecovery();
        assertNull(recovery.accept(original.get(0), 1));
        recovery.awaitFreshMessage();
        for (int i = 1; i < original.size(); i++) assertNull(recovery.accept(original.get(i), 2 + i));
        byte[] result = null;
        for (byte[] frame : BleFrames.split(message, 21, 244)) result = recovery.accept(frame, 10);
        assertArrayEquals(message, result);
    }

    @Test public void singleFragmentHeaderErrorCanRecoverOnNextMessage() {
        BleFrameRecovery recovery = new BleFrameRecovery();
        byte[] damaged = BleFrames.split(new byte[]{7}, 30, 244).get(0);
        damaged[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> recovery.accept(damaged, 1));
        assertArrayEquals(new byte[]{7}, recovery.accept(BleFrames.split(new byte[]{7}, 31, 244).get(0), 2));
    }
}
