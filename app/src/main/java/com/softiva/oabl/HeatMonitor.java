package com.softiva.oabl;

import android.content.Context;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HeatMonitor {
    static final String READ = "for z in /sys/class/thermal/thermal_zone*; do [ -r \"$z/temp\" ] || continue; "
            + "printf '%s|' \"${z##*/}\"; cat \"$z/type\" 2>/dev/null | tr '\\n' ' '; printf '|'; cat \"$z/temp\" 2>/dev/null | tr '\\n' ' '; echo; done";
    static final class Sensor {
        final String id, type; final double celsius;
        Sensor(String id, String type, long milliCelsius) { this.id = id; this.type = type; celsius = milliCelsius / 1000d; }
        String label() { return type + " · " + String.format(Locale.US, "%.1f °C", celsius) + " (" + id + ")"; }
        boolean cpu() { return type.toLowerCase(Locale.ROOT).matches(".*(cpu|cpuss|cluster).*" ); }
    }
    static List<Sensor> parse(String report) {
        List<Sensor> result = new ArrayList<>();
        for (String line : report.split("\\r?\\n")) {
            String[] fields = line.split("\\|", -1); if (fields.length != 3) continue;
            long raw = CpuControls.number(fields[2]);
            // Linux thermal sysfs reports millidegrees. Reject implausible/ambiguous vendor values.
            if (fields[0].trim().matches("thermal_zone[0-9]{1,4}") && raw >= -40000 && raw <= 200000
                    && (Math.abs(raw) >= 1000 || raw == 0)) result.add(new Sensor(fields[0].trim(), fields[1].trim(), raw));
        }
        return result;
    }
    static String report(Context context, int warning, RootAccess.Runner runner) {
        if (!RootAccess.check(runner)) return "Root access unavailable. Temperature sensors were not read.";
        RootAccess.Result read = runner.run(READ);
        List<Sensor> sensors = read.exitCode == 0 ? parse(read.output) : new ArrayList<>();
        StringBuilder result = new StringBuilder();
        boolean cpu = false, hot = false;
        for (Sensor sensor : sensors) { result.append(sensor.label()).append('\n'); cpu |= sensor.cpu(); hot |= sensor.celsius >= warning; }
        if (sensors.isEmpty()) result.append("No readable thermal sensors with valid millidegree values.\n");
        if (!cpu) result.append("CPU temperature unavailable; other sensors are not CPU temperature.\n");
        PowerManager power = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 29 && power != null) {
            String[] names = {"No throttling reported", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown"};
            int status = power.getCurrentThermalStatus();
            result.append("Android thermal status: ").append(status >= 0 && status < names.length ? names[status] : "Unavailable").append('\n');
        }
        result.append(hot ? "WARNING: a reported sensor is at or above " : "Local warning threshold: ").append(warning).append(" °C\n")
                .append("Warning only—not a throttle limit. Vendor thermal protection is unchanged. Sensors/status can be incomplete; no throttling reported does not prove a cool CPU.");
        return result.toString();
    }
}
