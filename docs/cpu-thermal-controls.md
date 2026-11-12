# CPU and thermal controls

Current entry point: **Settings → Performance & device → Core settings → CPU & thermal controls**. Core settings requires actual OABL superuser UID 0 access. `CoreTuningActivity` is not exported. Every mutation checks root and re-reads capabilities; none runs from an arbitrary user-supplied command or filename.

## CPU

- All-policy profiles enumerate the intersection of supported writable governors. Labels identify the actual governor: Performance, Responsive (schedutil), Conservative, Power saving, or the kernel's own name. No fabricated “Turbo” mode.
- Per-policy controls show the related core group, driver, governor, current requested frequency and min/max limits. Groups may share hardware frequency controls.
- Minimum/maximum pickers use only the advertised frequency table and obey current min ≤ max and hardware bounds. No arbitrary/overclock/voltage values or forcing offline cores online. If the table or writable interface is missing, controls are unavailable.
- Originals are synchronously saved before the first write, bound to the device's Android ID and build fingerprint. Restore keeps the kernel's required min/max ordering and verifies every write. Multi-policy governor failure attempts to restore every attempted policy; a failed rollback is explicitly reported. Driver clamping/override is not reported as success.

Kernel interface and units follow the [Linux CPUFreq policy documentation](https://docs.kernel.org/admin-guide/pm/cpufreq.html). `scaling_cur_freq` may be a requested P-state, not an exact physical measurement. The emulator's dummy driver reports 1–2 kHz; the UI explicitly labels this virtual, not AI-box CPU performance.

## Actual thermal settings

- Read kernel trip-point type, threshold, hysteresis and writability. This is distinct from the local temperature-warning setting.
- Only writable **passive** trips can be changed. Threshold choices start cooling earlier: 30 °C up to the lower of the saved stock threshold, 85 °C, and five degrees below the currently reported hot/critical trip. These are application guardrails, not proof of universal hardware safety.
- Hysteresis can be increased, not reduced below the original, with a 20 °C tuning ceiling and a minimum 15 °C release threshold. Longer cooling can reduce performance.
- Active, hot, critical/shutdown thresholds, thermal enable/disable, thermal governors, emulated temperatures, cooling-device bypass, offsets and voltage controls are **not writable through OABL**.
- Confirmation describes the real kernel change and risks. Each write is read back; failure triggers a best-effort rollback and a clear status. Restore verifies sensor mapping and refuses an original threshold that conflicts with a changed protected-trip margin.
- Thresholds/hysteresis that the vendor makes read-only or omits cannot be invented by root. Readback proves a parameter changed, not that the car hardware is cooling effectively. Physical validation is still needed.

The trip-point interface and millidegree units are documented in the [Linux thermal sysfs API](https://docs.kernel.org/driver-api/thermal/sysfs-api.html).

## Monitoring

Root sensor readings show original sensor type and zone. A battery sensor is never labelled CPU. Implausible or ambiguous vendor units are not guessed. Android's reported thermal status is shown separately, with a caveat that unavailable/“no throttling reported” is not proof of a cool CPU.

Optional monitoring runs every five seconds **only while this screen is foreground**. Polling is serialized off the UI thread, and there is no FFT, chart animation or background thermal daemon. Monitoring choice and the local warning threshold persist. A local warning does not alter kernel throttling. The controls screen follows the launcher's scheduled/manual appearance.

CPU/thermal modifications apply immediately and are **not automatically re-applied at reboot**. No boot script or exact alarm is installed. Original settings remain available for restore. Existing system-animation settings are independent and remain at 0.5× on the test AVD.

## Verification — 2026-10-13

Build and lint passed. `CpuThermalTestRunner` passed **69 checks**:

- 45 simulated-driver validation/rollback checks: parsing, injection/path rejection, unadvertised/overclock values, root denial, min/max consistency, saved originals, rejected writes/readback, partial multi-policy rollback, passive-vs-critical protection, threshold/hysteresis constraints, changed protected-trip margins, original restore and temperature units/sensor identity (including not guessing a generic/GPU TSENS sensor as CPU).
- 24 real-root CPU/sensor/UI checks on `OABL_Rooted_API_33` / emulator-5556: production Magisk access, supported multi-policy Performance writes, sysfs readback, a supported maximum-frequency cap, restoring every original governor and limit, real battery-only temperature reporting, unsupported thermal trip UI, and profile picker. Oversized root output is drained and rejected without a pipe deadlock; incomplete reports are not accepted.

The emulator's four CPU policies are restored to their prior **schedutil / min 1 kHz / max 2 kHz** values. It has no kernel trip points, so **actual thermal trip writes have not been tested on hardware**; those paths were tested with simulated driver responses. The physical AI box and proprietary CarPlay bridge were not connected or changed. No YouTube frame-rate cap or projection performance claim is made.

![CPU and thermal controls](screenshots/cpu-thermal-controls-overview.png)

Re-run on the dedicated rooted AVD only: build with `-PoablTestRunner=com.softiva.oabl.CpuThermalTestRunner`, install app and test APKs, then run `adb -s emulator-5556 shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.CpuThermalTestRunner`. This runner writes real CPU settings before restoring them. The kernel must expose the test AVD's expected dummy CPUFreq interfaces; do not run this runner on the physical car box.
