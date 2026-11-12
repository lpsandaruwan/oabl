# Rooted Android 13 integration device

Follow-up: [CPU & thermal controls](cpu-thermal-controls.md) has now passed 69 additional validation/real-root checks. CPU settings were exercised and restored; real thermal trip writes remain untested because this AVD has no trip points. Read-only CPU references below describe the earlier animation-only stage.

Dedicated AVD: `OABL_Rooted_API_33`, serial `emulator-5556`, Android 13 / API 33 Google APIs ARM64 revision 17. Native AVD display is 1920×970 at 238 dpi, four virtual CPU cores, host GPU, no snapshots. The existing `Medium_Tablet` / `emulator-5554` was not changed or stopped.

Root uses the official [Magisk 26.4 release](https://github.com/topjohnwu/Magisk/releases/tag/v26.4), patched with [rootAVD](https://github.com/newbit1/rootAVD) commit `c8638d0ee386017d9f1a0ed5247bb6f0749bbcff`. The temporary script was restricted to the supplied local release (no latest/canary/alpha downloads). SELinux remains **Enforcing**. Only the newly installed API 33 ramdisk was patched; its original `ramdisk.img.backup` remains alongside it. Existing API 36.1 images are untouched.

The newly downloaded system image was converted to a sparse raw file using the SDK's qemu-img to save disk space. `cmp` verified identical bytes before replacement. No user files were removed. The failed compression attempt cleaned up its own temporary output.

Magisk APK SHA-256: `543a96fe26c012d99baf3a3aa5a97b80508d67cc641af7c12ce9f7b226b2b889`.

## Real test scope

`RootedCoreTestRunner` runs as normal OABL UID **10175**, not as the root debugging shell. Tests call the production `RootAccess` and `CoreSettings` implementations without fake command runners. Magisk policy was explicitly granted, then denied and restored for OABL only.

- Configure: real writes of Off / 0.25× / 0.5× / 1× to all three global animation scales, production su readback and independent Settings.Global readback. Actual UI opens the root-only menu, shows existing values, selects 0.5×, confirms Apply and displays the verified result. **33 checks passed.**
- Permission denied: actual Magisk denial hides the Core settings menu, blocks a mutation and preserves the values read directly from Android. **5 checks passed.**
- Reboot verification: retained app root grant, visible Core settings menu, all three scales still **0.5**, and actual read-only CPU diagnostic handling. **9 checks passed.** Debugging shell was returned to ordinary UID **2000** before reboot; OABL still has its own Magisk grant. Independent shell readback also shows all three scales at **0.5**, with timezone Asia/Colombo.

**47 real-root integration checks passed on 2026-10-13.** Build and lint passed. The AVD is left running with OABL installed, its Magisk grant restored, and 0.5× system animation speed. Actual CPU settings are untouched.

The emulator kernel exposes policy0–policy3 and conservative/powersave/performance/schedutil governors. CPU diagnostics are tested read-only; no governor or frequency was changed. Virtual frequencies are not measurements of the AI box CPU. No thermal protections, custom display-resolution override or global video-frame-rate cap was applied.

The earlier 42 deterministic root/control checks are separate from these real-root integration checks. UI test selectors were corrected for Android's uppercase button text and the confirmation title containing “Apply”; those test failures were not production root-write failures. The test starts a fresh activity using CLEAR_TASK to avoid startActivitySync waiting for an already-running singleTask HOME activity after boot. One hung test was stopped before correcting that harness issue; the corrected post-reboot run passed.

![Actual verified 0.5× setting](screenshots/root-core-animations.png)

This device cannot establish whether a Dongfeng/AI-box proprietary CarPlay projection bridge stutters or which physical CPU policy is best. Physical-box tests remain necessary.

## Re-run

Build test APK with `./gradlew assembleDebug assembleDebugAndroidTest -PoablTestRunner=com.softiva.oabl.RootedCoreTestRunner` and install both APKs on the dedicated rooted AVD only.

Invoke `adb -s emulator-5556 shell am instrument -w -e phase configure com.softiva.oabl.test/com.softiva.oabl.RootedCoreTestRunner` to exercise real writes and leave 0.5× configured. `phase denied` requires OABL's Magisk grant to be denied first. `phase post-reboot` verifies retained settings without changing them.
