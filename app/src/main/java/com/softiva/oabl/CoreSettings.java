package com.softiva.oabl;

/** Animation controls and read-only CPU report; tuning lives in CpuControls / ThermalControls. */
final class CoreSettings {
    static final String[] ANIMATION_KEYS = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};
    static final String[] ANIMATION_VALUES = {"0", "0.25", "0.5", "1"};
    static String readAnimations(RootAccess.Runner runner) {
        StringBuilder output = new StringBuilder();
        for (String key : ANIMATION_KEYS) {
            RootAccess.Result result = runner.run("settings get global " + key);
            output.append(key).append(": ").append(result.exitCode == 0 ? result.output.trim() : "Unavailable").append('\n');
        }
        return output.toString();
    }
    static String setAnimations(int selection, RootAccess.Runner runner) {
        if (selection < 0 || selection >= ANIMATION_VALUES.length) return "Invalid animation setting; nothing changed.";
        if (!RootAccess.check(runner)) return "Root permission is no longer available. Nothing changed.";
        String value = ANIMATION_VALUES[selection];
        for (String key : ANIMATION_KEYS) {
            RootAccess.Result write = runner.run("settings put global " + key + " " + value);
            RootAccess.Result read = runner.run("settings get global " + key);
            try {
                if (write.exitCode != 0 || read.exitCode != 0 || Float.parseFloat(read.output.trim()) != Float.parseFloat(value))
                    return "Could not verify all three animation settings. Some may have changed; recheck their values.";
            } catch (NumberFormatException invalid) {
                return "Animation readback unavailable. Some settings may have changed; recheck their values.";
            }
        }
        return "System animations set to " + value + "× and verified. These Android settings normally survive reboot; vendor firmware can override them.";
    }
    static String cpuReport(RootAccess.Runner runner) {
        if (!RootAccess.check(runner)) return "Root permission is no longer available.";
        RootAccess.Result result = runner.run("for p in /sys/devices/system/cpu/cpufreq/policy*; do "
                + "[ -d \"$p\" ] || continue; echo \"${p##*/}\"; "
                + "for f in scaling_governor scaling_available_governors scaling_cur_freq scaling_min_freq scaling_max_freq; do "
                + "[ -r \"$p/$f\" ] && { echo \"$f\"; cat \"$p/$f\"; }; done; done");
        return result.exitCode == 0 && !result.output.trim().isEmpty()
                ? result.output.trim() + "\n\nFrequencies are in kHz. Read-only: no governor, frequency or thermal limits changed."
                : "CPU policy data unavailable even with superuser access. No CPU settings changed.";
    }
}
