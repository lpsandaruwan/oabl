package com.softiva.oabl;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Test access to playback, which runs in the ":music" process. Goes through {@link MusicClient},
 * as the launcher does; the client is main-thread only, so every call runs there.
 */
final class MusicTestControl {
    private final Instrumentation instrumentation;

    /** Safe to create in a field initializer: the target context is read only when used. */
    MusicTestControl(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    private Context context() { return instrumentation.getTargetContext(); }
    private MusicClient client() { return MusicClient.get(context()); }

    @SuppressWarnings("unchecked")
    private <T> T read(Supplier<T> value) {
        Object[] out = new Object[1];
        instrumentation.runOnMainSync(() -> out[0] = value.get());
        return (T) out[0];
    }

    void play(List<MusicLibrary.Track> tracks, long id) { instrumentation.runOnMainSync(() -> client().play(tracks, id)); }
    void command(String action) { instrumentation.runOnMainSync(() -> client().command(action)); }
    void seek(long position) { instrumentation.runOnMainSync(() -> client().seek(position)); }
    void shuffle() { instrumentation.runOnMainSync(() -> client().shuffle()); }

    boolean playing() { return read(() -> client().isPlaying()); }
    long current() { return read(() -> client().currentId()); }
    long position() { return read(() -> client().position()); }
    String error() { return read(() -> client().error()); }
    int audioSession() { return read(() -> client().audioSessionId()); }
    List<Long> queue() { return read(() -> client().queue()); }
    android.media.session.MediaController platformController() { return read(() -> client().platformController()); }

    /** Polls every 100 ms until the condition holds or the time runs out; returns the last result. */
    boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() >= end) return false;
            SystemClock.sleep(100);
        }
        return true;
    }
    boolean waitPlaying(boolean playing, long timeoutMs) { return waitFor(() -> playing() == playing, timeoutMs); }

    /** True while the ":music" process exists. */
    boolean running() { return musicPid() > 0; }

    /** Ends the ":music" process, as Android does under memory pressure, and waits until it's gone. */
    boolean stop() {
        int pid = musicPid();
        if (pid > 0) Process.killProcess(pid);
        return waitFor(() -> !running(), 5000);
    }

    private int musicPid() {
        Context context = context();
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = activities.getRunningAppProcesses();
        if (processes == null) return -1;
        String name = context.getPackageName() + ":music";
        for (ActivityManager.RunningAppProcessInfo process : processes) if (name.equals(process.processName)) return process.pid;
        return -1;
    }
}
