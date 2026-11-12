# Physical AI-box update — 2026-10-14

Target: `<device-ip>`, QUALCOMM / Bengal for arm64, Android 13, ARM64+32-bit compatibility. Native display 1920×970 / 238 dpi, no size or density overrides reported. OABL was already the resolved default HOME application before the update and remains so afterward.

Latest debug build and lint passed. Installed `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r` successfully. No uninstall, data clear, system-launcher disabling, CPU/thermal writes or reboot was performed. Existing grants and app data were retained by the update. Device package reports lastUpdateTime `2026-10-14 12:25:14` (device-reported clock; not independently synchronized).

Previous installed APK retained at `device-backups/2026-10-14/oabl-before-update.apk` for recovery. This is an APK backup, not a complete user-data backup.

Smoke verification: MainActivity opened; EqualizerActivity opened and was visually inspected with all 15 frequency controls visible, profile Flat and compatible-player scope. Equalizer service is running as a foreground mediaPlayback service (foreground notification ID 4108); PID-filtered app error logs showed no errors during this check. Profile and audible audio effect were not changed/tested with a music source. Compatible audio-session attachment and car speaker listening remain unverified.

During the later screenshot check, the user had navigated into OEM Original Vehicle Settings. That image is labelled `screenshots/ai-box-original-vehicle-settings-2026-10-14.png`, not used as an equalizer proof image. No OEM settings were changed. Further intrusive UI/audio tests were not run. Launcher was brought to the foreground at the end of the install workflow.
