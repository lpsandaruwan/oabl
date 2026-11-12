package com.softiva.oabllink;

/** Reassemble one GATT answer, discarding the tail of a damaged answer until its resend starts. */
public final class BleFrameRecovery {
    private final BleFrames.Assembler assembler = new BleFrames.Assembler();
    private boolean waitingForFirst;

    public synchronized byte[] accept(byte[] frame, long monotonicMs) {
        if (waitingForFirst) {
            if (!first(frame)) return null;
            assembler.clear();
            waitingForFirst = false;
        }
        try { return assembler.accept(frame, monotonicMs); }
        catch (IllegalArgumentException damaged) {
            waitingForFirst = true;
            throw damaged;
        }
    }

    /** A partial reply timed out; its remaining fragments belong to the abandoned message. */
    public synchronized void awaitFreshMessage() {
        assembler.clear();
        waitingForFirst = true;
    }

    public synchronized void clear() {
        assembler.clear();
        waitingForFirst = false;
    }

    private static boolean first(byte[] frame) {
        if (frame == null || frame.length < BleFrames.HEADER || frame[0] != 1) return false;
        int index = (frame[3] & 255) << 8 | (frame[4] & 255);
        int count = (frame[5] & 255) << 8 | (frame[6] & 255);
        return index == 0 && count > 0 && count <= BleFrames.MAX_FRAGMENTS;
    }
}
