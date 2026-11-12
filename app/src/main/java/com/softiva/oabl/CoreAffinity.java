package com.softiva.oabl;

import android.util.Log;

/**
 * Keeps the music playback thread on the performance cores. Android moves a process between
 * cpusets when it changes between foreground and background, which resets thread affinity, so
 * callers pin again whenever playback starts and periodically while it plays.
 */
final class CoreAffinity {
    private static boolean loaded;
    static {
        try { System.loadLibrary("oablaudio"); loaded = true; }
        catch (UnsatisfiedLinkError missing) { Log.w("OablMusic", "Core pinning unavailable: " + missing.getMessage()); }
    }
    private CoreAffinity() { }

    /** Mask of the cores the calling thread now runs on, 0 if all cores are alike, or -errno. */
    private static native long pinCurrentThreadToFastCores();

    static long pinCurrentThread() {
        return loaded ? pinCurrentThreadToFastCores() : -1;
    }
}
