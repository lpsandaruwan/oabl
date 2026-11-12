package com.softiva.oabl;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Audio CPU policy, verified rather than assumed: for each thread that carries music, what OABL
 * asked for and what the kernel actually applied (allowed cores, scheduling policy, nice).
 *
 * <p>OABL changes only its own playback thread (pinned to the performance cores, normal
 * scheduling at audio priority). Other processes' threads (the decoder in media.swcodec,
 * audioserver's mixer, the HAL writer) are reported when Android lets an app read them, and are
 * never changed from here: that needs a separate, explicitly installed root service.
 */
final class AudioThreads {
    private AudioThreads() { }

    static final String PINNED_MASK = "pinned_mask", PINNED_AT = "pinned_at";

    /** The music process records what it asked for, so the report can compare. */
    static void recordRequest(Context context, long mask) {
        context.getSharedPreferences(MusicService.STATE, Context.MODE_MULTI_PROCESS).edit()
                .putLong(PINNED_MASK, mask).putLong(PINNED_AT, System.currentTimeMillis()).apply();
    }

    static String report(Context context) {
        StringBuilder out = new StringBuilder("AUDIO THREADS · requested → verified");
        int music = pidOf(context, context.getPackageName() + ":music");
        SharedPreferences state = context.getSharedPreferences(MusicService.STATE, Context.MODE_MULTI_PROCESS);
        long mask = state.getLong(PINNED_MASK, 0);
        boolean asked = state.getLong(PINNED_AT, 0) > 0;
        String requested = !asked ? "not requested yet (starts with playback)"
                : mask > 0 ? "performance cores " + cores(mask) + ", normal scheduling"
                : mask == 0 ? "all cores are alike here, nothing to pin" : "pinning failed (" + mask + ")";
        if (music <= 0) {
            out.append("\nOABL playback: music process not running · requested ").append(requested);
        } else {
            boolean any = false;
            File[] tasks = new File("/proc/" + music + "/task").listFiles();
            if (tasks != null) for (File task : tasks) {
                String name = read(new File(task, "comm"));
                if (name == null || !(name.startsWith("OablPlayback") || name.startsWith("ExoPlayer") || name.startsWith("AudioTrack"))) continue;
                any = true;
                out.append("\n").append(name).append(": requested ")
                        .append(name.startsWith("OablPlayback") ? requested : "unchanged")
                        .append(" → verified ").append(verified(task));
            }
            if (!any) out.append("\nOABL playback: no playback thread yet · requested ").append(requested);
        }
        for (String process : new String[]{"media.swcodec", "audioserver", "android.hardware.audio.service"}) {
            int pid = pidByScan(process);
            if (pid <= 0) {
                out.append("\n").append(process).append(": not readable by apps · no change requested by OABL");
                continue;
            }
            File[] tasks = new File("/proc/" + pid + "/task").listFiles();
            int shown = 0;
            if (tasks != null) for (File task : tasks) {
                String name = read(new File(task, "comm"));
                if (name == null || !(name.startsWith("AudioOut") || name.startsWith("writer") || name.startsWith("c2.")
                        || name.contains("decoder") || name.startsWith("FastMixer"))) continue;
                out.append("\n").append(process).append(" / ").append(name).append(": no change requested → ").append(verified(task));
                if (++shown >= 6) break;
            }
            if (shown == 0) out.append("\n").append(process).append(": no audio thread readable now");
        }
        out.append("\n\nNo real-time priority is set by OABL. Thermal protection and CPU limits are untouched.");
        return out.toString();
    }

    /** "cores 4-7 · SCHED_OTHER · nice -16", or why it couldn't be read. */
    static String verified(File task) {
        String status = read(new File(task, "status"));
        String allowed = null;
        if (status != null) for (String line : status.split("\n")) {
            if (line.startsWith("Cpus_allowed_list:")) allowed = line.substring(18).trim();
        }
        String stat = read(new File(task, "stat"));
        String policy = "?", nice = "?";
        if (stat != null) {
            // Fields after the last ")" (the name may contain spaces): field 3 is the first one there.
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            if (fields.length > 38) {
                nice = fields[16];
                policy = policyName(fields[38]);
            }
        }
        if (allowed == null) return "not readable";
        return "cores " + allowed + " · " + policy + " · nice " + nice;
    }

    static String policyName(String value) {
        switch (value) {
            case "0": return "SCHED_OTHER";
            case "1": return "SCHED_FIFO";
            case "2": return "SCHED_RR";
            case "3": return "SCHED_BATCH";
            case "5": return "SCHED_IDLE";
            default: return "policy " + value;
        }
    }

    /** "4-7" from a mask like 0xf0. */
    static String cores(long mask) {
        StringBuilder text = new StringBuilder();
        int start = -1;
        for (int cpu = 0; cpu <= 64; cpu++) {
            boolean on = cpu < 64 && (mask & (1L << cpu)) != 0;
            if (on && start < 0) start = cpu;
            if (!on && start >= 0) {
                if (text.length() > 0) text.append(',');
                text.append(start == cpu - 1 ? Integer.toString(start) : String.format(Locale.US, "%d-%d", start, cpu - 1));
                start = -1;
            }
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private static int pidOf(Context context, String processName) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || manager.getRunningAppProcesses() == null) return -1;
        for (ActivityManager.RunningAppProcessInfo info : manager.getRunningAppProcesses()) {
            if (processName.equals(info.processName)) return info.pid;
        }
        return -1;
    }

    /** Other apps' and system processes: usually hidden from apps by SELinux; -1 then. */
    private static int pidByScan(String name) {
        File[] all = new File("/proc").listFiles();
        if (all == null) return -1;
        for (File dir : all) {
            if (!dir.getName().matches("[0-9]+")) continue;
            String cmdline = read(new File(dir, "cmdline"));
            if (cmdline == null) continue;
            String first = cmdline.split("\0")[0];
            if (first.equals(name) || first.endsWith("/" + name)) return Integer.parseInt(dir.getName());
        }
        return -1;
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0 && out.size() < 65536) out.write(buffer, 0, n);
            return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception unreadable) {
            return null;
        }
    }
}
