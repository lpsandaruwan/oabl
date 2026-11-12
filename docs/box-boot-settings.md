# AI box boot settings — 25 October 2026

These are the owner's persistent checklist, applied at every boot on box `<serial>` by init services in the `/system` overlay (re-created on 24 October with `adb remount`).

| Setting | How | Off switch |
|---|---|---|
| Wireless ADB on TCP 5555 | `oabl-wireless-adb.rc` (see [wireless-adb-persistence-2026-10-17.md](wireless-adb-persistence-2026-10-17.md)) | `setprop persist.oabl.adb.enabled 0` |
| **Physical SIM**: modem started | `oabl-box-tune` sets `vendor.qcom.main.start=1`, the switch Suding's CarPlay service otherwise sets only when a car session begins. It starts `init.class_main.sh`, which starts `qcrild`. | (left on) |
| **CPU governor** from `persist.oabl.governor`: `schedutil` (the vendor default, owner's choice since 25 October; it was `performance` before) or `performance` | `oabl-box-tune`, re-asserted every 15 s. Switching to schedutil rewrites the vendor tunables (rate limits 0, hispeed 1.3056/1.4016 GHz at 85 % load) | `setprop persist.oabl.governor performance` to go back; takes effect within 15 s |
| **Minimum clocks** (owner's choice, 30 October): **1.6128 GHz on the performance cores 4–7** and **0.6144 GHz on the efficiency cores 0–3** (vendor floors 1.056 GHz big / 1.0176 GHz little; the little floor was 1.6128 GHz from 25 to 30 October) | `oabl-box-tune`, `scaling_min_freq` on policy4 and policy0, every 15 s | `persist.oabl.boxtune.enabled 0` (restores the vendor floors) |
| **Foreground on performance cores** (owner's choice, 25 October): WALT conservative boost `sched_boost=2` (a light top-app task measured on cores 4–7 25 % → 100 % of the time; background stays on 0–3). **Core control, 30 October:** two performance cores stay awake (`core_ctl` min_cpus 2, the vendor value; it was 4), and the efficiency cluster's core control is switched on (`cpu0/core_ctl/enable 1`, min_cpus 1) so cores 1–3 can be parked when idle | `oabl-box-tune`, every 15 s | `persist.oabl.boxtune.enabled 0` |
| **Battery saver off** | `low_power=0`, `low_power_trigger_level=0`, checked every 5 min | (Android's default) |
| Audio real-time priority 2 | `admin_open_play`, `AudioOut_*` mixers, HAL `writer` threads | `persist.oabl.boxtune.enabled 0` |
| Projection audio threads at real-time priority 3 (3 November) | `sdAutoReverse` `__audio_write_h`, `__record_handle`, `__write_handler`: Suding sets SCHED_RR 51, above the display commit thread (16) and all audio threads; set to SCHED_FIFO 3 every 15 s. At 51 the box froze 2–3 s every 10–20 s on the still home layouts (CPU pressure 30–51 %, about 400 HAL underruns per 3 min; after: 14 %, 83) | `persist.oabl.boxtune.enabled 0` (back to RR 51) |
| **Keyframe every 5 s** while a head unit is attached (installed 12 November; confirmed live on the box with a tablet head unit: setting the property gave an IDR within a second) | The projection encoder in `sdAutoReverse` (`libscreenrecord.so`) polls `sys.suding.force.key.frame` between frames; on `1` it asks MediaCodec for a sync frame and resets the property to `0` (logs `set force.key.frame=`). `oabl-box-tune` sets it every `persist.oabl.keyframe.sec` seconds (default 5) while the USB gadget state is `configured`. Found by disassembly; the same code sets `i-frame-interval` to 10 s or to 36000 s depending on a flag not yet traced, so the box's own interval is unknown. Unverified until a session runs | `setprop persist.oabl.keyframe.sec 0` |
| **Reverse-auto tracer** (12 November) | `sd-reverse.sh` starts `sdAutoReverse` with `LD_PRELOAD=/system/lib/liboabltrace.so` while `persist.oabl.shim=1`; log in `/data/local/tmp/oabl-trace.log` (6 MB cap). Self-disables after three starts that die within 20 s. Details and undo in `docs/device-backups/2026-11-12-boot-tracer/` | `setprop persist.oabl.shim 0` then `setprop ctl.restart sd-reverse` |
| Right-hand drive kept on (5 November) | `persist.suding.reverse.rightHand=1`, re-asserted by `oabl-box-tune` every 15 s. `sdAutoReverse` rewrites it from the driver side a head unit announces, and SystemUI uses it for the Maps PiP tile's corner: 1 is bottom left, 0 is bottom right. SystemUI reads the corner when it starts, so a change only moves the tile after a SystemUI restart or reboot | `oabl-box-tune` off, then `setprop persist.suding.reverse.rightHand 0` |
| Maps guidance PiP tile at 44.25 % (4 November) | SystemUI overlay `com.android.shell:oablPipSize` (`config_pictureInPictureDefaultSizePercent` 0.4425, Android default 0.23): 486×648 px bottom left at 1920×898 and 584×778 px at 1080 high, centred in Home's navigation card (8 px above and below; SystemUI fixes the tile's bottom 80 dp above the screen's bottom and its left 10 dp from the edge, and Home sizes its header and dock to wrap it). Re-created once when `oabl-box-tune` starts, since a reboot drops runtime overlays. Maps is a PiP tile only while it guides | `cmd overlay disable com.android.shell:oablPipSize` |
| Maps tile control (6 November) | `oabl-pip`, a root helper (`scripts/device-init/oabl-pip/OablPip.java`, built by `oabl-pip/build.sh` to `/system/etc/oabl-pip.dex`, started by `oabl-pip.sh` → `/system/bin/oabl-pip` and `oabl-pip.rc`). The launcher asks it over the abstract socket `oabl-pip` to hide Maps' tile off Home and close it 8 s after guidance ends. It answers only the launcher's uid. Set `persist.oabl.pip.enabled=1` to run it; Android reads a new `.rc` only at boot, so a first install needs a reboot. Without it the tile behaves as Android leaves it | `setprop persist.oabl.pip.enabled 0` |
| CarPlay video every 2nd frame | `suding.carplay.reverse.video.frame.interval 2` | same |
| RGB light strip off | `colorLedSpi` stopped, GPIO 1206 at 0, while `persist.oabl.lightstrip.off=1` (`led.sh` untouched: it powers the GPS) | `setprop persist.oabl.lightstrip.off 0` |
| Wi-Fi debug logger off | `vendor.cnss_diag` stopped while `persist.oabl.cnssdiag.off=1`; it wrote about 30 MB/min of firmware logs to `/data/vendor/wifi/wlan_logs` (137 MB cleared on 25 October). `cnss-daemon`, which Wi-Fi needs, is untouched | `setprop persist.oabl.cnssdiag.off 0`, then reboot |
| Suding log recorder off | core service `savelog` stopped, and its `logcat`/`kmsg` copies into `/data/.X8_Log` killed, while `persist.oabl.savelog.off=1`; it wrote about 0.5 MB/min (161 MB cleared on 25 October) | `setprop persist.oabl.savelog.off 0`, then reboot |
| ~~Google Maps as a PiP window on Home, idle~~ (removed 4 November, launcher 0.1.77) | The `oabl-maps-pip` helper and the SystemUI PiP-size overlays are gone: Maps is a PiP tile only while it guides, at Android's default size (252×336 px, bottom left). Removed from a box: `setprop persist.oabl.mapspip.enabled 0`, `rm /system/bin/oabl-maps-pip /system/etc/init/oabl-maps-pip.rc`, `cmd overlay disable com.android.shell:oablPipSize` and `:oablPipAspect` | — |
| Launcher pre-compiled | `cmd package compile -m speed` when an install resets it (launcher must be a release build) | same |
| Core placement (audio, CarPlay, OABL on cores 4–7) | `oabl-affinity` watcher (`scripts/box-affinity-boot.sh`) | `setprop persist.oabl.affinity.enabled 0` |

Sources: `scripts/device-init/oabl-box-tune.sh` → `/system/bin/oabl-box-tune`, and `scripts/device-init/oabl-box-tune.rc` → `/system/etc/init/`. Both are root:root, `system_file`. The service runs as root in `u:r:su:s0`, started at `sys.boot_completed` when `persist.oabl.boxtune.enabled=1`.

## Verified by a reboot (boot `f63044b0-387d-4333-991b-31124246ab26`)

All of these came back with no manual step:
- ADB returned about 48 s after the reboot;
- all three services were running;
- both governors were `performance`;
- `low_power=0` with trigger 0;
- the modem was started and `qcrild` running, with the SIM **LOADED on Dialog, LTE**, and mobile data on `rmnet_data1`;
- `admin_open_play` at FIFO 2;
- frame interval 2;
- the launcher at `status=speed`.

## Notes

- **The performance governor (if switched back on) keeps every core at full clock**, so watch temperatures in the car. The dashboard's box temperature uses the CPU/SoC sensors; ignore `rf-pa1-therm-usr` (about 81.6 °C, doesn't respond to load).
- **A factory reset or `/data` wipe removes the overlay and all of this.** To restore: `adb remount`, reboot, push the files again, set the `persist.oabl.*` properties.
- **Don't use `am stack move-task` on Maps' tile.** It crashed SystemUI ("Unknown task") on 6 November, which restarted on its own. `am stack remove` and `oabl-pip`'s close end Maps' guidance, so they're only for after a trip; `oabl-pip`'s hide keeps the route.
- **Don't run scrcpy with audio on this box.** Its audio forwarding crashes audioserver (`openDuplicateOutput`), and the phone process restarts with it. Use `scrcpy --no-audio`.

- **Also disabled 25 October** (user choice): FOTA updater, CITTest, the old debugging helper, MeigLogger. See [2026-10-25-disable](device-backups/2026-10-25-disable/README.md).

- **30 October: the USB network helper (`oabl-usbnet`) was removed** (box files, property, repo script and tests, and the launcher's and Link's USB discovery). It added a network function beside Android Auto for OABL Link, never worked on the head unit (the box's CarPlay path has its own address), and it left two extra USB interfaces on the cable that a host could pick wrongly. The last version is kept in `device-backups/2026-10-30-aa-disconnect/`.
