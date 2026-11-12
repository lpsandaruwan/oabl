package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Narrow CPUFreq interface. No overclocking, thermal writes, modules or boot scripts. */
final class CpuControls {
    static final String BASE = "/sys/devices/system/cpu/cpufreq/";
    static final String READ = "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"$p\" ] || continue; "
            + "printf '%s' \"${p##*/}\"; for f in related_cpus scaling_driver scaling_governor scaling_available_governors "
            + "cpuinfo_min_freq cpuinfo_max_freq scaling_min_freq scaling_max_freq scaling_cur_freq scaling_available_frequencies; do "
            + "printf '|'; cat \"$p/$f\" 2>/dev/null | tr '\\n' ' '; done; "
            + "for f in scaling_governor scaling_min_freq scaling_max_freq; do printf '|'; [ -w \"$p/$f\" ] && printf 'yes'; done; echo; done";
    static final class Policy {
        final String id, cores, driver, governor;
        final List<String> governors;
        final List<Long> frequencies;
        final long hardwareMin, hardwareMax, min, max, current;
        final boolean governorWritable, minWritable, maxWritable;
        Policy(String[] fields) {
            id = fields[0]; cores = fields[1]; driver = fields[2]; governor = fields[3];
            governors = new ArrayList<>();
            for (String token : fields[4].split("\\s+")) if (safeGovernor(token) && !governors.contains(token)) governors.add(token);
            hardwareMin = number(fields[5]); hardwareMax = number(fields[6]);
            min = number(fields[7]); max = number(fields[8]); current = number(fields[9]);
            frequencies = new ArrayList<>();
            for (String token : fields[10].split("\\s+")) {
                long value = number(token);
                if (value >= hardwareMin && value <= hardwareMax && value > 0 && !frequencies.contains(value)) frequencies.add(value);
            }
            Collections.sort(frequencies);
            governorWritable = fields[11].equals("yes"); minWritable = fields[12].equals("yes"); maxWritable = fields[13].equals("yes");
        }
        String summary() {
            return id + " · cores " + cores + " · " + driver + "\n" + governor + " · " + frequency(min) + "–" + frequency(max)
                    + " · current " + frequency(current) + (driver.equals("dummy") ? "\nVirtual CPU driver — not physical performance measurements" : "");
        }
    }
    static List<Policy> parse(String report) {
        List<Policy> result = new ArrayList<>();
        for (String line : report.split("\\r?\\n")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 14) continue;
            for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
            if (!safePolicy(fields[0]) || !safeGovernor(fields[3])) continue;
            Policy policy = new Policy(fields);
            if (policy.hardwareMin > 0 && policy.hardwareMax >= policy.hardwareMin && policy.min >= policy.hardwareMin
                    && policy.max <= policy.hardwareMax && policy.max >= policy.min) result.add(policy);
        }
        return result;
    }
    static List<Policy> read(RootAccess.Runner runner) {
        if (!RootAccess.check(runner)) throw new IllegalStateException("Root permission unavailable. No CPU changes made.");
        RootAccess.Result result = runner.run(READ);
        if (result.exitCode != 0) throw new IllegalStateException("CPU policy read failed.");
        return parse(result.output);
    }
    static Policy find(List<Policy> policies, String id) {
        for (Policy policy : policies) if (policy.id.equals(id)) return policy;
        throw new IllegalStateException("CPU policy is no longer available.");
    }
    static List<String> commonGovernors(List<Policy> policies) {
        ArrayList<String> result = new ArrayList<>();
        if (policies.isEmpty()) return result;
        result.addAll(policies.get(0).governors);
        for (Policy policy : policies) {
            if (!policy.governorWritable) { result.clear(); break; }
            result.retainAll(policy.governors);
        }
        Collections.sort(result); return result;
    }
    static boolean safePolicy(String id) { return id != null && id.matches("policy[0-9]{1,4}"); }
    static boolean safeGovernor(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"); }
    static long number(String value) { try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return -1; } }
    static String frequency(long value) {
        if (value <= 0) return "Unavailable";
        return value < 1000 ? value + " kHz" : String.format(Locale.US, "%.0f MHz", value / 1000d);
    }
    static String path(String id, String field) {
        if (!safePolicy(id) || !Arrays.asList("scaling_governor", "scaling_min_freq", "scaling_max_freq").contains(field))
            throw new IllegalArgumentException("Invalid CPU control path");
        return BASE + id + "/" + field;
    }
    private static void write(RootAccess.Runner runner, String id, String field, String value) {
        if (!(field.equals("scaling_governor") ? safeGovernor(value) : value.matches("[0-9]{1,12}")))
            throw new IllegalArgumentException("Invalid CPU control value");
        String target = path(id, field);
        RootAccess.Result result = runner.run("printf '%s' '" + value + "' > '" + target + "'");
        RootAccess.Result actual = runner.run("cat '" + target + "'");
        if (result.exitCode != 0 || actual.exitCode != 0 || !value.equals(actual.output.trim()))
            throw new IllegalStateException("Kernel rejected or overrode " + id + " " + field + ".");
    }
    private static SharedPreferences storage(Context context) { return context.getSharedPreferences("cpu_originals", Context.MODE_PRIVATE); }
    private static String device(Context context) {
        return Build.FINGERPRINT + ":" + Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }
    static boolean hasOriginals(Context context) { return device(context).equals(storage(context).getString("device", "")) && storage(context).getAll().size() > 1; }
    private static void remember(Context context, Policy policy) {
        SharedPreferences prefs = storage(context);
        SharedPreferences.Editor edit = prefs.edit();
        boolean same = device(context).equals(prefs.getString("device", ""));
        if (!same) edit.clear().putString("device", device(context));
        if (same && prefs.contains(policy.id)) return;
        try {
            JSONObject value = new JSONObject().put("governor", policy.governor).put("min", policy.min).put("max", policy.max);
            if (!edit.putString(policy.id, value.toString()).commit()) throw new IllegalStateException("Could not save original CPU configuration. Nothing changed.");
        } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
    }
    static synchronized String governor(Context context, String id, String value, RootAccess.Runner runner) {
        Policy current = find(read(runner), id);
        if (!safeGovernor(value) || !current.governorWritable || !current.governors.contains(value))
            throw new IllegalArgumentException("Governor unsupported or read-only. Nothing changed.");
        remember(context, current);
        try { write(runner, id, "scaling_governor", value); }
        catch (RuntimeException failure) { throw new IllegalStateException(failure.getMessage() + rollbackGovernor(runner, current)); }
        return "Verified " + id + " governor: " + value + ". Thermal protection remains enabled.";
    }
    private static String rollbackGovernor(RootAccess.Runner runner, Policy original) {
        try { write(runner, original.id, "scaling_governor", original.governor); return " Previous governor restored."; }
        catch (RuntimeException failed) { return " Rollback could not be verified; refresh CPU settings."; }
    }
    static synchronized String profile(Context context, String governor, RootAccess.Runner runner) {
        List<Policy> policies = read(runner);
        if (!safeGovernor(governor) || !commonGovernors(policies).contains(governor))
            throw new IllegalArgumentException("Profile unsupported by all CPU policies. Nothing changed.");
        for (Policy policy : policies) remember(context, policy);
        List<Policy> attempted = new ArrayList<>();
        try {
            for (Policy policy : policies) { attempted.add(policy); write(runner, policy.id, "scaling_governor", governor); }
        } catch (RuntimeException failure) {
            StringBuilder rollback = new StringBuilder();
            for (Policy policy : attempted) rollback.append(rollbackGovernor(runner, policy));
            throw new IllegalStateException(failure.getMessage() + rollback);
        }
        return "Verified " + governor + " across " + policies.size() + " CPU policies. Frequency limits were not changed. Thermal protection remains enabled.";
    }
    static synchronized String limit(Context context, String id, boolean minimum, long value, RootAccess.Runner runner) {
        Policy current = find(read(runner), id);
        if (!(minimum ? current.minWritable : current.maxWritable) || !current.frequencies.contains(value)
                || (minimum ? value > current.max : value < current.min))
            throw new IllegalArgumentException("Unsupported frequency, read-only limit or minimum above maximum. Nothing changed.");
        remember(context, current);
        String field = minimum ? "scaling_min_freq" : "scaling_max_freq";
        try { write(runner, id, field, Long.toString(value)); }
        catch (RuntimeException failure) {
            String rollback;
            try { write(runner, id, field, Long.toString(minimum ? current.min : current.max)); rollback = " Previous limit restored."; }
            catch (RuntimeException ignored) { rollback = " Rollback could not be verified; refresh CPU settings."; }
            throw new IllegalStateException(failure.getMessage() + rollback);
        }
        return "Verified " + id + (minimum ? " minimum: " : " maximum: ") + frequency(value) + ". Not automatically reapplied after reboot.";
    }
    static synchronized String restore(Context context, RootAccess.Runner runner) {
        if (!hasOriginals(context)) return "No original CPU settings saved for this device.";
        List<Policy> current = read(runner);
        int count = 0;
        for (Policy policy : current) {
            String saved = storage(context).getString(policy.id, null); if (saved == null) continue;
            try {
                JSONObject original = new JSONObject(saved);
                String governor = original.getString("governor"); long min = original.getLong("min"), max = original.getLong("max");
                if (!policy.governors.contains(governor) || !safeGovernor(governor) || min < policy.hardwareMin || max > policy.hardwareMax || min > max)
                    throw new IllegalStateException("Saved settings no longer supported.");
                if (min > policy.max) {
                    write(runner, policy.id, "scaling_max_freq", Long.toString(max));
                    write(runner, policy.id, "scaling_min_freq", Long.toString(min));
                } else {
                    write(runner, policy.id, "scaling_min_freq", Long.toString(min));
                    write(runner, policy.id, "scaling_max_freq", Long.toString(max));
                }
                write(runner, policy.id, "scaling_governor", governor); count++;
            } catch (Exception failure) {
                throw new IllegalStateException("Restore incomplete at " + policy.id + ": " + failure.getMessage() + " Refresh to check current values; earlier policies may have been restored.");
            }
        }
        return "Original governors and limits restored and verified for " + count + " CPU policies.";
    }
}
