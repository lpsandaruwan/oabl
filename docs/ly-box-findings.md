# LY / Liaoyuan AI box (Allwinner H618) — vendor software findings

Box: `QUAD-CORE H618 lybox`, project `ly1791`, Android 12 (userdebug build, `ro.debuggable=0`),
4 × Cortex-A53 @ 1.512 GHz, Mali GPU, 4 GB RAM. SELinux is **permissive**, so the normal adb shell
can read and set LY's `persist.*` properties without root.

Method: vendor APKs pulled from `/system/app` and decompiled with jadx; native daemon
`/system/bin/lylinkservice` inspected with `strings`. Read-only analysis; Apple authentication (MFi)
and the CarPlay protocol itself were not examined.

## Components

| Component | Role |
|---|---|
| `lylinkservice` (native, root) | CarPlay session: car link, screen capture + H.264 encode, audio, touch, car-specific workarounds, reboot logic |
| `ly_usb_driver` (kernel) | USB link to the car |
| `com.liaoyuan.mylink` (`ly6-wificp`) | Wireless CarPlay; converts/scales frames on the CPU with libyuv (`I420Scale`, `I420ToNV21`) |
| `com.ly.mirror` | Mirroring app; reads the forced size |
| `com.ap.play` (`ly6-rcp`) | Reverse-CarPlay player |
| `com.ly.logic` | System logic: properties, reboot-to-fix-resolution, floating ball, warnings |
| `com.ly.settings` (`LYBoxSet`) | Settings app, including **Engineering** tab |
| ~11 more `com.ly.*` | Bluetooth, music, video, media scan, storage, update, logs, ambient LED, wallpaper, factory tools |

## Why the resolution is forced to 1920 × 970

1. When CarPlay connects, `lylinkservice` reads the car's screen size and frame rate
   ("adjust before screenWidth/Hight/Fps"), applies car rules ("HU is Max/Min resolution",
   Lexus/Toyota/Ford/Honda/Mazda special cases) and the display offset mode.
2. It stores the result (`set lyosinfo wide/high`, `/data/lyosinfo`).
3. If that differs from the box's current resolution it sets `ly.need_reboot`
   (skipped when `persist.ly.no.reboot` is set, `/sdcard/ly_unreboot` exists, or HDMI input is active).
4. `com.ly.logic` `RebootFixPixManager` checks `ly.need_reboot` every 5 s and reboots
   ("system rebooting adjust").
5. At boot the bootloader passes `ro.boot.ly.width=1920`, `ro.boot.ly.height=970`; Android's
   display is forced to it (`wm size reset` is re-applied immediately).

So the box renders at exactly the car's CarPlay resolution: no scaling on the way to the car.
The 1280 × 720 "physical" display is the box's own (HDMI/dummy) output and is not what the car shows.
Changing it would make the car scale the picture; the native-size test showed no rendering gain.

## Engineering Mode (LY Settings → third tab)

| Option | Property | Values |
|---|---|---|
| Audio stream mode | `persist.ly.audio.mode` | 0 = Default (compatible with most cars), **1 = "Other (select when audio is stuttering)"** |
| Display smoothness | `persist.ly.video.mode` | Level 1/2/3 (0/1/2); applied after returning to the connect screen |
| Screen density | `wm density` + `persist.sys.temp_densityDpi` | 120–360 |
| Display offset | `persist.ly.display.padding.mode` | Default, Mode 1, Mode 2 |
| Delayed startup | `persist.ly.bootdelay.mode` | 0–60 s after connecting |
| Auto log output | `persist.ly.logger.switch` | true/false |
| HDMI adapter | `persist.ly.hdmi_set`, `sys.ly.hdmi.*` | |

`lylinkservice` reads video and audio mode at session start (`videoMode=%d`, `InitMode audio mode`)
and also logs `getVideoRate`, `getBitrate`, `frameDelayType/maxDelay`.

Version tab gestures: 10 quick taps → factory **Test Mode** (only if
`/system/etc/AUTO/FactoryTest/factory_test.ini` exists); 5 taps → screenshot switch
(`ly.screencap.enable`) or serial number.

## Other notable properties

| Property | Meaning |
|---|---|
| `persist.ly.hugps.enable` | Use the car's own GPS ("original vehicle GPS"), default 0 |
| `persist.ly.enable_driving_video` | Allow video while driving, default false |
| `persist.ly.navi.mix` | Navigation prompts: 0 = duck music, 1 = mute music |
| `persist.ly.no.reboot` | Skip the resolution-fix reboot |
| `persist.ly.fun.mode` | Factory/function mode |
| `persist.ly.pcm.periodsize`, `sys.ly.pcm_out.*` | Audio buffer sizes (unset = defaults) |
| `persist.carplay.left/right/top/bottom`, `xscale/yscale` | Picture position/scale on the car screen |

## Performance measurements (at home, not connected to a car)

- Idle CPU 66 °C; first thermal trip (passive throttling) at **70 °C**, then 90 °C.
- H.264 screen encode (1920×970 vs 1280×720, 4–16 Mbps) made almost no difference to load or fps.
- UI rendering is CPU-bound: Settings scroll median 53 ms/frame (~19 fps), 68 % janky;
  GPU median 11 ms. "Slow issue draw commands" on 101/162 frames.
- Wi-Fi: Realtek SDIO, 2.4 GHz only, Wi-Fi 4, 65–72 Mbps; driver threads are CPU-heavy.

## Candidate tunings to test in the car

1. `persist.ly.audio.mode` → 1 if audio stutters.
2. `persist.ly.video.mode` → compare Level 1/2/3 for smoothness vs. heat.
3. Disable unneeded `com.ly.*` services to cut CPU and heat.
4. Cooling, to stay below the 70 °C throttle point.
