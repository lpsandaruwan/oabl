# Performance controls and projection limits

**Current root tuning:** [CPU & thermal controls](cpu-thermal-controls.md) now offers supported governors, min/max frequencies, and conservative writable passive thermal trips, with saved originals and verified restore. The read-only/pending CPU statements below record the earlier implementation stage; they no longer describe the new tuning screen. Thermal protections remain locked, and physical-box capability is still unverified.

2026-10-13 menu visibility fix: the performance menu's custom ArrayAdapter used MainActivity's dark-themed context inside a light-themed dialog, resulting in nearly white list text on a white surface. It now uses the dialog's themed context and an explicit, captured matching text color (so async root updates cannot pick a different theme behind the dialog). Build/lint and 54 actual rendered row/contrast/root-update checks passed across Light and Dark; every row exceeds 4.5:1 contrast against its themed background. Updated build installed on emulator-5556. Proof: `screenshots/performance-menu-light.png` and `screenshots/performance-menu-dark.png`.

Settings → Performance & device diagnostics provides:

- Existing launcher Visuals / Performance animation mode.
- A saved launcher/EQ display-mode preference, offering System and only Android-reported rates at the current physical display resolution. Unsupported preferences fall back to System. No custom resolution, density override, unverified 30fps slider, or global secure setting is applied.
- Read-only CPU governor/available-governor information where sysfs permits reading, app-visible CPU count, available RAM, display rate and Android-reported thermal status. Reads run off the UI thread. Missing governor data is explicitly unavailable, not inferred from CPU core count.
- A real superuser-access check when opening the panel. Only a successful `su -c 'id -u'` response of `0` exposes **Core settings**. Presence of Magisk, a su file, or test-key firmware is not treated as granted access. Permission may be requested by the root manager. Missing/denied/timed-out access leaves the core menu hidden, with retry instructions. No positive result is persisted across launches.

## Root-gated Core settings

- Android-wide animation speed: Off, 0.25×, 0.5× or 1×. Shows current window/transition/animator scales before selecting, asks for confirmation, rechecks root, writes only those three fixed global settings and verifies each value. This is animation duration, not video FPS. System settings normally persist, but vendor override/reboot behavior on the physical box is not yet verified. Selecting 1× restores normal speed, not a previous custom configuration.
- Privileged, read-only CPU policy diagnostics: current/available governors and current/min/max frequencies where the kernel exposes them. No CPU policy changes or frequency limits are written.
- Root operations are off the main thread, each su invocation has a 15-second timeout, output is bounded, and the UI ignores results after its dialog/activity closes. Permission is rechecked before privileged operations. No arbitrary command input is accepted.

2026-10-13: build and lint passed; 42 instrumentation checks passed for UID validation, granted/denied menu visibility, permission revocation, all animation choices and readback/error handling, plus the actual emulator su probe and matching menu state. Privileged write responses were mocked for deterministic tests; no actual root write or physical-box behavior is claimed. Emulator build installed; physical box not connected.

Follow-up on 2026-10-13: [47 real-root integration checks](rooted-emulator.md) passed on a separate Android 13/Magisk AVD, including actual animation writes, touch-through confirmation, permission denial and reboot retention. Left at 0.5× system animations. Physical-box behavior remains unverified; CPU controls remain read-only.

These are **display hints, not a video frame-rate limiter**. [Android's frame-rate documentation](https://developer.android.com/media/optimize/performance/frame-rate) explains how the platform chooses display refresh rates from surface/window preferences. OABL cannot control a separate YouTube application's render/decoder rate or a proprietary Android-to-CarPlay bridge's encoder/transport rate through this setting. A 30fps projection setting, if the bridge exposes one, must be selected in that vendor's app/settings. No bridge capability is assumed from the emulator.

CPU governor switching remains unavailable pending verification of the physical box's exposed policies. [Linux CPUFreq documentation](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html) describes hardware policies, available governors and the influence of thermal/power limits; “performance” does not defeat those limits. Root-gated Core settings uses su only for access checks, explicit system-animation settings and read-only CPU diagnostics. The app does not write sysfs, force minimum clocks, disable thermal protection or claim to persist kernel changes after reboot.

Stuttering needs measurement on the actual AI box: bridge/projection settings, video resolution and source frame rate, decoder choice/load, Wi-Fi link, memory pressure and sustained thermal behavior. Hardware must be connected before attributing it to a CPU policy or choosing a specific governor. Android may underreport thermal status on vendor firmware; “no throttling reported” is not proof of a cool device. See [Android Thermal API limitations](https://developer.android.com/games/optimize/adpf/thermal).

## Auto theme

Auto now uses fixed **IST UTC+05:30**: Light from **08:30 inclusive to 17:00 exclusive**, Dark overnight. Manual Dark/Light override the schedule. The dashboard updates on minute boundaries and time changes; resume/reboot immediately checks wall time. Navigation overlays recolor in place. An open equalizer recreates its lightweight UI at a theme change, retaining its saved curve/metadata. No exact-alarm permission or background polling loop is needed.

The schedule is independent of the device timezone and Android system night setting. Correct device wall time is still required. New installs default to Auto; the current emulator is explicitly switched to Auto for the user's request. Third-party Maps/YouTube themes remain controlled by those applications.

Verified 2026-10-13: 67 simulator checks passed—60 schedule/manual-override/live-refresh/persistence checks and seven supported-rate/same-resolution/fallback/window-hint/diagnostic checks. Debug build and lint passed. Physical governor access and actual projection/video performance remain unverified; no CPU policy or global frame-rate cap has been changed.
