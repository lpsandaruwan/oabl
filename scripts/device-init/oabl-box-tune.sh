#!/system/bin/sh
# Boot-time box settings (private development box only), the owner's persistent checklist plus
# scripts/box-tune.sh apply. Every 15 s it re-asserts, without touching anything already set:
#   - the modem started for the physical SIM (vendor.qcom.main.start=1, the switch Suding's
#     CarPlay service uses when a car session begins; without it the radio stays unavailable).
#   - right-hand drive (persist.suding.reverse.rightHand=1). sdAutoReverse rewrites it from the
#     driver side a head unit announces, and SystemUI uses it to pick the Maps PiP tile's corner:
#     1 is bottom left, where Home's navigation card expects it; 0 is bottom right.
#   - the CPU governor on both clusters: persist.oabl.governor, schedutil (the vendor default,
#     owner's choice since 25 October) when unset, or performance.
#   - battery saver off and its automatic trigger disabled (checked every 5 minutes).
#   - the RGB light strip off, when persist.oabl.lightstrip.off=1.
#   - the Wi-Fi firmware debug logger (cnss_diag) off, when persist.oabl.cnssdiag.off=1.
#   - Suding's log recorder (savelog) off, when persist.oabl.savelog.off=1.
#   - real-time priority 2 (SCHED_FIFO) for admin_open_play's silence keep-alive, audioserver's
#     AudioOut_* mixer threads and the audio HAL writer threads. These threads come and go with
#     music and with audioserver/HAL restarts, so a one-off apply doesn't last.
#   - Suding's projection audio threads (sdAutoReverse __audio_write_h, __record_handle,
#     __write_handler) down from SCHED_RR 51 to SCHED_FIFO 3, just above the audio threads they feed.
#     At 51 they outrank the display commit thread (16) and every Android audio thread, and on the
#     still home layouts the box froze for 2-3 s every 10-20 s: audio underruns, a stuck screen,
#     slow wheel keys and Bluetooth drops. Measured 3 November: CPU pressure 30-51 % -> 14 %, HAL
#     underruns about 400 -> 83 per 3 minutes. Re-applied because the threads restart with projection.
#   - minimum clocks (owner's choice, 30 October): 1.6128 GHz on the performance cores (4-7, vendor
#     1.056 GHz) and 0.6144 GHz on the efficiency cores (0-3, vendor 1.0176 GHz; was 1.6128 GHz).
#   - foreground work on the performance cores: WALT conservative boost (sched_boost 2) places
#     top-app/foreground tasks on cores 4-7 (a light top-app task measured 25 % -> 100 % there);
#     background stays on 0-3. core_ctl keeps two performance cores awake (min_cpus 2, the vendor
#     value; it was 4 until 30 October) and the efficiency cluster's core_ctl is on (min_cpus 1), so
#     cores 1-3 are parked when idle. The perf HAL's own short boosts restore the value they found.
#   - CarPlay video at every second frame (suding.carplay.reverse.video.frame.interval 2).
#   - the launcher pre-compiled (`cmd package compile -m speed`) after an install reset it
#     (checked every 5 minutes).
#   - Google Maps' guidance PiP tile at 44.25 % of the display's short edge (Android's default is 23 %):
#     486 × 648 px, the height of Home's navigation card less 8 px above and below. SystemUI pins the
#     tile's bottom 80 dp above the screen's bottom, so its size is what centres it in the card.
#     A runtime overlay that a reboot drops, so it is re-created once at start (4 November).
#   - a keyframe every persist.oabl.keyframe.sec seconds (default 5, 0 = off) while a head unit is
#     attached. The projection encoder (libscreenrecord inside sdAutoReverse) polls
#     sys.suding.force.key.frame between frames, asks MediaCodec for a sync frame when it reads 1
#     and sets it back to 0. After a stall drops frames, the head unit's picture stays black or
#     broken until the next keyframe, and Android Auto has no keyframe request of its own.
# Core placement is the separate oabl-affinity watcher. Off switch (restores defaults, stays off
# across reboots): setprop persist.oabl.boxtune.enabled 0
FRAME=suding.carplay.reverse.video.frame.interval
LITTLE=/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
BIG=/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq
# Owner's choice, 30 October: the performance cores never drop below 1.6128 GHz and two of them stay awake;
# the efficiency cores idle down to 0.6144 GHz and may be parked when there is nothing for them to do.
FLOOR_BIG=1612800
FLOOR_LITTLE=614400
BOOST=/proc/sys/kernel/sched_boost
CORECTL=/sys/devices/system/cpu/cpu4/core_ctl/min_cpus
LITTLECTL=/sys/devices/system/cpu/cpu0/core_ctl
rt() { case "$(chrt -p "$1" 2>/dev/null)" in *FIFO*) ;; *) chrt -f -p "$1" 2 2>/dev/null ;; esac; }
other() { chrt -o -p "$1" 0 2>/dev/null; }
projection() { # $1 policy flag, $2 priority
  for t in $(threads sdAutoReverse __audio_write_h) $(threads sdAutoReverse __record_handle) \
           $(threads sdAutoReverse __write_handler); do
    case "$(chrt -p "$t" 2>/dev/null)" in *"priority: $2"*) ;; *) chrt "$1" -p "$t" "$2" 2>/dev/null ;; esac
  done
}
threads() { # $1 process, $2 comm pattern
  for pid in $(pidof "$1"); do for t in /proc/$pid/task/*; do
    case "$(cat "$t/comm" 2>/dev/null)" in $2) echo "${t##*/}" ;; esac
  done; done
}
# The box's RGB light strip (vendor service colorLedSpi, powered through GPIO 1206). Owner's choice:
# off while persist.oabl.lightstrip.off=1. led.sh is never touched: it also powers the GPS.
lightstrip() {
  [ "$(getprop persist.oabl.lightstrip.off)" = 1 ] || return
  [ "$(getprop init.svc.colorLedSpi)" = running ] && setprop ctl.stop colorLedSpi
  p=$(pidof colorLedSpi); [ -n "$p" ] && kill $p
  [ "$(cat /sys/class/gpio/gpio1206/value 2>/dev/null)" = 0 ] || echo 0 > /sys/class/gpio/gpio1206/value
}
# Qualcomm's Wi-Fi/Bluetooth firmware debug logger (vendor service vendor.cnss_diag) writes about
# 30 MB a minute to /data/vendor/wifi/wlan_logs. Nothing on the box uses it; cnss-daemon, which
# Wi-Fi itself needs, is never touched. Off while persist.oabl.cnssdiag.off=1.
cnssdiag() {
  [ "$(getprop persist.oabl.cnssdiag.off)" = 1 ] || return
  [ "$(getprop init.svc.vendor.cnss_diag)" = running ] && setprop ctl.stop vendor.cnss_diag
  p=$(pidof cnss_diag); [ -n "$p" ] && kill $p
}
# Suding's log recorder (core service savelog, started from init.rc) copies logcat and the kernel
# log into /data/.X8_Log, about 0.5 MB a minute. Its logcat and cat /proc/kmsg children are
# reparented to init, so they're found by where they write. Off while persist.oabl.savelog.off=1.
savelog() {
  [ "$(getprop persist.oabl.savelog.off)" = 1 ] || return
  [ "$(getprop init.svc.savelog)" = running ] && setprop ctl.stop savelog
  for p in $(pidof logcat) $(pidof cat); do
    case "$(readlink /proc/$p/fd/1 2>/dev/null)" in /data/.X8_Log/*) kill $p ;; esac
  done
}
# Switching back to schedutil resets its tunables, so the vendor values (init.qcom.post_boot.sh,
# bengal) are written again: no rate limits, hispeed at 85 % load, no RTG boost.
sutune() { # $1 policy dir, $2 hispeed_freq
  echo 0 > $1/schedutil/up_rate_limit_us; echo 0 > $1/schedutil/down_rate_limit_us
  echo $2 > $1/schedutil/hispeed_freq; echo 85 > $1/schedutil/hispeed_load
  echo 0 > $1/schedutil/rtg_boost_freq
}
governor() { # $1 schedutil or performance
  for p in /sys/devices/system/cpu/cpufreq/policy0 /sys/devices/system/cpu/cpufreq/policy4; do
    [ "$(cat $p/scaling_governor)" = "$1" ] && continue
    echo "$1" > $p/scaling_governor
    [ "$1" = schedutil ] && sutune $p $([ ${p##*policy} = 0 ] && echo 1305600 || echo 1401600)
  done
}
# Maps and other apps follow Android's night mode, the launcher doesn't use it. The launcher leaves 1 (dark)
# or 0 (light) in files/night whenever its theme resolves (SystemNightMode); mirror that here.
# Off while persist.oabl.nightsync.off=1. Maps itself must be on "Same as device theme".
nightsync() {
  [ "$(getprop persist.oabl.nightsync.off)" = 1 ] && return
  case "$(cat /data/data/com.softiva.oabl/files/night 2>/dev/null)" in
    1) want=2; word=yes ;;
    0) want=1; word=no ;;
    *) return ;;
  esac
  [ "$(settings get secure ui_night_mode)" = "$want" ] || cmd uimode night $word >/dev/null 2>&1
}
apply() {
  lightstrip
  nightsync
  cnssdiag
  savelog
  [ "$(getprop vendor.qcom.main.start)" = 1 ] || setprop vendor.qcom.main.start 1
  [ "$(getprop persist.suding.reverse.rightHand)" = 1 ] || setprop persist.suding.reverse.rightHand 1
  case "$(getprop persist.oabl.governor)" in performance) governor performance ;; *) governor schedutil ;; esac
  [ "$(cat $BOOST)" = 2 ] || echo 2 > $BOOST
  [ "$(cat $CORECTL)" = 2 ] || echo 2 > $CORECTL
  # Core control for the efficiency cluster is off in the vendor setup; on, it parks cores 1-3 when idle.
  [ "$(cat $LITTLECTL/min_cpus)" = 1 ] || echo 1 > $LITTLECTL/min_cpus
  [ "$(cat $LITTLECTL/enable)" = 1 ] || echo 1 > $LITTLECTL/enable
  [ "$(cat $LITTLE)" = $FLOOR_LITTLE ] || echo $FLOOR_LITTLE > $LITTLE
  [ "$(cat $BIG)" = $FLOOR_BIG ] || echo $FLOOR_BIG > $BIG
  [ "$(getprop $FRAME)" = 2 ] || setprop $FRAME 2
  for p in $(pidof admin_open_play); do rt "$p"; done
  for t in $(threads audioserver 'AudioOut_*') $(threads android.hardware.audio.service writer); do rt "$t"; done
  projection -f 3
}
# Every 5 minutes: battery saver stays off, and the launcher stays pre-compiled.
powersave() {
  [ "$(settings get global low_power)" = 0 ] || settings put global low_power 0
  [ "$(settings get global low_power_trigger_level)" = 0 ] || settings put global low_power_trigger_level 0
}
# dumpsys goes to a file first: a reader that quits early can break the dump.
compiled() {
  dumpsys package com.softiva.oabl > /data/local/tmp/oabl-box-tune.pkg 2>/dev/null
  case "$(grep 'status=' /data/local/tmp/oabl-box-tune.pkg)" in
    *status=speed*|"") ;;
    *) cmd package compile -m speed -f com.softiva.oabl >/dev/null 2>&1 ;;
  esac
  rm -f /data/local/tmp/oabl-box-tune.pkg
}
# Off: the governor and CPU floor go back to the vendor defaults. The modem and battery saver are
# left as they are (the modem keeps the SIM working; battery saver off is Android's default).
revert() {
  governor schedutil
  echo 1017600 > $LITTLE; echo 1056000 > $BIG
  echo 0 > $BOOST; echo 2 > $CORECTL; echo 0 > $LITTLECTL/enable
  setprop $FRAME 1
  for p in $(pidof admin_open_play); do other "$p"; done
  for t in $(threads audioserver 'AudioOut_*') $(threads android.hardware.audio.service writer); do other "$t"; done
  projection -r 51
  cmd overlay disable --user 0 com.android.shell:oablPipSize >/dev/null 2>&1
}
# Maps' PiP tile while guiding (44.25 %, 0x3ee28f5c). Once: fabricating an existing overlay just replaces it.
pipsize() {
  cmd overlay fabricate --target com.android.systemui --name oablPipSize \
      com.android.systemui:dimen/config_pictureInPictureDefaultSizePercent 0x04 0x3ee28f5c >/dev/null 2>&1
  cmd overlay enable --user 0 com.android.shell:oablPipSize >/dev/null 2>&1
}
pipsize
# Its own loop: the period is seconds, the apply cycle below is 15 s. Only while a head unit has the USB
# gadget configured; with none attached the encoder isn't running and nothing would clear the property.
keyframes() {
  while [ "$(getprop persist.oabl.boxtune.enabled)" = 1 ]; do
    s=$(getprop persist.oabl.keyframe.sec); case "$s" in ''|*[!0-9]*) s=5 ;; esac
    if [ "$s" -gt 0 ] && [ "$(cat /sys/class/udc/*/state 2>/dev/null)" = configured ]; then
      [ "$(getprop sys.suding.force.key.frame)" = 1 ] || setprop sys.suding.force.key.frame 1
      sleep "$s"
    else
      sleep 5
    fi
  done
}
keyframes &
n=0
while [ "$(getprop persist.oabl.boxtune.enabled)" = 1 ]; do
  apply
  [ $((n % 20)) = 0 ] && { powersave; compiled; }
  n=$((n + 1)); sleep 15
done
revert
