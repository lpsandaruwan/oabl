# Performance-core placement — physical Bengal AI box

Implemented and exercised on the connected Android 13 Bengal box (`<device-ip>`, serial `<serial>`) on 2026-10-16. This is a runtime device tool, not a new OABL APK or a reboot-persistent system modification.

## Verified hardware and placement

- Cores 0–3: capacity 572, hardware maximum 1,804,800 kHz.
- Cores 4–7: capacity 1024, hardware maximum 2,016,000 kHz.
- Performance affinity mask: `f0`. The tool derives the highest-capacity online cluster; it does not assume those core numbers on another device.
- Recorded full checks: **15 processes, 318 and then 323 live threads, zero affinity mismatches**. Counts naturally vary as threads start/exit.
- Target processes: OABL and its colon-suffixed service/audio processes; `audioserver`; `android.hardware.audio.service`; Android Bluetooth, its Qualcomm HAL and `com.carsyso.bluetooth`; SpeedPlay and its daemon; both `sdCarplaySvc` processes; both `sd_carplay` processes; `carnetos.usbmedia`; and `media.codec`.
- `media.resource_manager` identified the active `OMX.qcom.video.encoder.avc` client as `sdCarplaySvc` PID 1051. This pins its software/codec-service threads, not the physical video accelerator or DSP firmware.

The previous one-shot tuning missed FastMixer threads because their `/audio-app` cpuset allowed only cores 1–2. Some OEM Bluetooth threads were restricted to `/background` (0–3). The new controller moves only blocked target threads to `/dev/cpuset/oabl-performance` (4–7), then sets and reads back per-thread affinity. It does not widen shared background groups. Other concurrent tuning changed the shared `audio-app` group to 4–7 during this session; that change is not made by this controller and is preserved.

## Runtime behavior and reversibility

`scripts/oabl-affinity.c` is compiled with the Android NDK. Its small native watcher reconciles process/thread placement every two seconds, including Android foreground/background resets and restarted processes. There can be up to roughly one polling interval before a new/reset thread is corrected; this is not an atomic framework-wide task-profile policy.

The watcher itself runs on efficiency cores at nice +10. RSS measured about 3 MiB. In one steady-state sample it consumed 142 scheduler ticks across 45.82 seconds; on this device's 100-Hz task accounting that is about 3.1% of one efficiency core, not 3.1% of the whole eight-core CPU. This is a short sample, not a sustained performance benchmark.

Original thread affinity/cpuset records are saved before changes in `/data/local/tmp/oabl-affinity-state/originals.tsv`, keyed by boot ID, PID, TID and task start time. PID/TID reuse does not authorize changing an unrelated replacement task during rollback. Newly inherited custom-set threads use the saved process-leader placement as a rollback anchor. Stopping the watcher restores saved affinities and any target threads moved into the custom group. If Android/vendor tooling has since changed a shared cpuset, rollback respects its current allowed CPUs rather than undoing that external policy.

No governor, CPU frequency, real-time priority, IRQ affinity, CarPlay FPS, thermal limit, SELinux, OEM startup script, boot image or partition was changed by this tool. Existing unrelated tuning is not reverted. Thermal-engine was verified running. Hardware status reporting is incomplete (`thermalservice` reported its HAL not ready), so its status 0 is not evidence of no physical throttling.

This version is **active until stopped or the box reboots**. It survives ADB disconnection but is not installed as a boot service. OABL has no `su` binary available on this firmware; root ADB access does not grant the launcher superuser access. No OABL root-menu capability is claimed.

## Commands

Use the Android SDK's `adb` on PATH, or set `ADB` to its absolute executable path. Substitute the current connected ADB endpoint when it changes.

```sh
ANDROID_NDK_HOME=/path/to/android-ndk sh scripts/box-affinity.sh DEVICE build
sh scripts/box-affinity.sh DEVICE deploy
sh scripts/box-affinity.sh DEVICE start
sh scripts/box-affinity.sh DEVICE status
sh scripts/box-affinity.sh DEVICE probe
sh scripts/box-affinity.sh DEVICE stop
```

`stop` signals only the recorded, identity-verified watcher and performs rollback. `restore` is available if a watcher was killed unexpectedly; it refuses to run while the controller holds its lock. `stop` also clears a stale watcher marker. Stop before deploying an updated helper. Do not run the legacy `box-tune.sh revert` concurrently: it has a separate older process list and can overwrite runtime affinity/clock/FPS choices.

## Verification

- Android NDK ARM64 build with `-Wall -Wextra -Werror`, stack protection and fortified libc: passed.
- Host shell syntax check: passed.
- 28 native checks: topology-list parsing, exact package/native allowlist boundaries, excluded system processes, cpuset-path validation, live task identity and affinity reads: passed.
- Read-only baseline: 296 target threads, 181 not on the performance cluster.
- First application: 296/296 verified; independent follow-up status also 296/296.
- FastMixer TIDs 549, 565 and 569 independently checked through `/proc`: allowed list 4–7, target-specific cpuset.
- Stop/rollback integration: `RESTORE verified=313 exited-or-reused=64 adapted-to-current-cpuset=3 failed=0`. The three adaptations reflect the concurrently changed shared audio cpuset.
- New-process discovery, simulated Android background reset, and newly spawned/background-reset thread: all three live synthetic tests passed, including actual execution on CPU 4. Repeated against the optimized deployed binary.
- Full statuses after leaving the watcher active: 318/318 and 323/323 on 4–7; watcher confirmed alive, on 0–3, nice +10, about 3 MiB RSS.

The synthetic tests change only their own native test process/threads. No real audio, Bluetooth or projection service was killed, restarted or intentionally backgrounded for testing. No application data was cleared and no existing launcher UI/features were changed.

This verifies placement, not that stuttering is fixed. Existing AudioFlinger FIFO underruns continued to accumulate during mixed activity and concurrent tuning, so no clean before/after playback-performance conclusion is justified. Hardware codec/DSP behavior, USB transport and projection buffering remain separate possible bottlenecks.

References: [Linux cpuset constraints](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v1/cpusets.html) and [Android Toybox per-thread taskset implementation](https://android.googlesource.com/platform/external/toybox/+/ea6fc27/toys/other/taskset.c).

## Emulator check of forced core placement (2026-10-18)

The box was offline, so placement was tested on the rooted API 33 AVD (8 vCPUs) made box-like at runtime:
- `cpu_capacity` 572 on cores 0–3 and 1024 on cores 4–7, via bind mounts;
- the background, system-background and restricted cpusets limited to 0–3;
- SELinux permissive, like the box.

The emulator has no `core_ctl`, so it never parks fast cores. Everything was reverted afterwards.

| Case | What happened |
|---|---|
| OABL playback thread (in-app `CoreAffinity`) | Cores 4–7; the `AudioTrack` thread it creates inherits 4–7. |
| App switches and pause while OABL Home is the launcher | `:music` stays in `/foreground`; the pin holds. |
| Android moves `:music` to `/background`, then back | Background overwrites it to 0–3, and OABL can't pin outside the cpuset. Back in foreground it opens to 0–7; OABL re-pins on its 30 s check (3 s in this run). |
| Native init service (audioserver, pinned once with `taskset`) | Holds through playback changes, app switches and 30 s; such services never change cpuset. Suding's `sdCarplaySvc` and `sd_carplay` are this kind. |
| App process (Settings, standing in for SpeedPlay), pinned in front | Every switch resets it: background gives 0–3 for all threads; back in front gives 0–7. |
| `oabl-affinity` watcher | Repairs within about 1 s: a thread forced into `/background` is moved to `/oabl-performance` (4–7). A restarted `:music` process is pinned from its first sample. Cost about 0.3 % CPU, 2.6 MB, nice 10. Stop restores the original placement. |

Conclusions:
- Forcing placement works.
- A one-time pin is enough for native services until they restart or the box reboots.
- App processes, including SpeedPlay, need the watcher.
- Nothing survives a reboot unless the watcher is started at boot.

## Boot service on the box (installed 2026-10-18, with your approval)

The watcher now starts at every boot: `/system/bin/oabl-affinity` and `/system/etc/init/oabl-affinity.rc`, installed with `scripts/box-affinity-boot.sh`. It's core placement only: no real-time priority, clock, IRQ or thermal changes. `oabl-wireless-adb.rc` and `persist.oabl.adb.enabled` are untouched (checksum `85cd863e…` before and after).

| | Command |
|---|---|
| Off (restores every saved placement; stays off across reboots) | `setprop persist.oabl.affinity.enabled 0`, or `sh scripts/box-affinity-boot.sh DEVICE off` |
| On | `setprop persist.oabl.affinity.enabled 1`, or `sh scripts/box-affinity-boot.sh DEVICE on` |
| Check | `sh scripts/box-affinity-boot.sh DEVICE status`; on the box, `sh /data/local/tmp/placement-report.sh` |
| Remove | `sh scripts/box-affinity-boot.sh DEVICE uninstall` |

Init reads the `.rc` only at boot. Until the next boot the watcher runs as started by the installer. From the next boot on, init runs it as service `oabl-affinity` (`getprop init.svc.oabl-affinity`).

**Before → after** (CarPlay not connected, so SpeedPlay wasn't running; it's a target and is caught when it starts):

| Process | Before | After |
|---|---|---|
| sdCarplaySvc (2) | 0–7 ×4, **core 4 only ×14** (Suding's own iAP2/AirPlay pin) | 4–7 ×18 |
| sd_carplay (2) | 0–7 ×6 | 4–7 ×6 |
| audioserver | 0–7 ×18, `audio-app` 1–2 ×3 | 4–7 ×21 (3 moved to `oabl-performance`) |
| audio HAL, media.codec, media.swcodec | 0–7 | 4–7 |
| com.carsyso.bluetooth | `background` 0–3 ×19 | 4–7 ×19 (`oabl-performance`) |
| Android Bluetooth, BT HAL, carnetos.usbmedia | 0–7 | 4–7 |
| OABL (UI, services, music) | 0–7, playback thread 4–7 | 4–7 |

Raw reports: `docs/device-backups/2026-10-18-affinity/placement-{before,after}.txt`.

**Three watcher fixes found on the box today:**
1. **Reading affinity.** `sched_getaffinity()` returns the allowed cores ANDed with those `core_ctl` keeps awake. While cores 4 and 6 were isolated, a correct 4–7 pin read as "5,7", so every thread looked wrong and was rewritten every 2 s, and rollback recorded the wrong originals. The watcher now reads `Cpus_allowed_list`.
2. **Retrying restores.** The kernel refuses an affinity whose only core is isolated. Suding's core-4 threads are therefore retried for up to 60 s while core 4 sleeps (idle sample: awake 42 of 60 s, longest parked stretch about 18 s). `stop` waits up to 65 s.
3. **Stale journal.** The first, buggy run's journal (kept for the whole boot) was cleared, and placement was restored by hand to match the before report. The off switch was then tested: `RESTORE verified=247 failed=0`, identical to before, in 23 s.

Suding pins its CarPlay protocol threads to core 4 alone, which `core_ctl` often isolates. The watcher widens them to all four fast cores, so they always have an awake fast core.

Note on the emulator check above: its `restricted` cpuset was set to 0–3, while the box's is 0–7.

### Reboot verification (2026-10-18, two reboots, with your OK)

- **Starts at boot:** after each reboot init started `oabl-affinity` by itself (`init.svc.oabl-affinity=running`, parent PID 1, nice 10, about 2.6 MB). Wireless ADB came back by itself; `oabl-wireless-adb.rc` is unchanged.
- **Placement after the final boot:** 15 processes, 238 threads, 0 mismatched, all on cores 4–7 (`placement-after-reboot.txt`). This boot's defaults are in `placement-defaults-after-reboot.txt`.
- **The off switch works through init:** `setprop persist.oabl.affinity.enabled 0` stopped the service and restored placement; `1` started it again.

**Fixes found while verifying:**
- **Inherited pins.** A thread created after its parent was pinned inherits 4–7, and was recorded with 4–7 as its "original". It's now recorded with its process leader's original. A final sweep at stop also catches threads born in the last moments.
- **Log noise.** `/proc` is mounted `hidepid=2,gid=3009`, so every `/proc` listing ran a per-process check that permissive SELinux logged. The service now has the `readproc` group, and a non-target process's name is read once per lifetime, not every pass.

**Remaining:**
- The service runs in the root shell's SELinux domain (`su`), whose policy doesn't cover reading other processes. Permissive SELinux allows these reads but logs them, about 0.5–0.7 a second in the kernel log.
- Running it in the `lmkd` domain logged none in a 2-minute test (and no notices for pinning, cpuset moves or state writes). Changing the service to that domain was stopped by the assistant's safety check as a security weakening, so that change, if wanted, is yours to make: `seclabel u:r:lmkd:s0` in `/system/etc/init/oabl-affinity.rc`, then reboot.
- The notices only take space in the kernel log; they change nothing.
