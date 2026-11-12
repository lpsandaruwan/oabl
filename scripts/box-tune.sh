#!/bin/sh
# Performance tuning for the Dongfeng AI box (Snapdragon 662) over wireless adb.
# The box's adb shell runs as root; apps (including OABL) do not, so these settings are applied
# from a computer and reset when the box restarts. Run again after each start.
#
#   scripts/box-tune.sh <ip:port> apply    big cores for audio, real-time audio keep-alive,
#                                          mixer and HAL writer, 1.3 GHz minimum on cores 0-3,
#                                          CarPlay video at 30 fps
#   scripts/box-tune.sh <ip:port> revert   back to the firmware defaults
#   scripts/box-tune.sh <ip:port> status   current limits, core pinning and temperatures
#
# Cores 0-3 are the efficiency cluster (policy0), cores 4-7 the performance cluster (policy4).
set -e

DEVICE="$1"
ACTION="${2:-status}"
if [ -z "$DEVICE" ]; then
  echo "usage: $0 <ip:port> [apply|revert|status]" >&2
  exit 2
fi

# The audio chain: Android's mixer (which also runs the OABL equalizer effect), the audio HAL,
# the CarPlay bridge that sends sound to the car, Android's software decoders (media.swcodec:
# FLAC, MP3, Vorbis, Opus) and OABL's player process (com.softiva.oabl:music). The launcher UI
# no longer decodes audio and stays on Android's own placement. OABL's playback thread also
# pins itself to cores 4-7.
# System processes keep their pinning until the box restarts. App processes are reset to all
# cores by Android whenever they move between foreground and background, so their pinning only
# lasts until the next app switch; the mixer is what matters most.
AUDIO_PROCESSES="audioserver android.hardware.audio.service sdCarplaySvc media.swcodec com.softiva.oabl:music org.videolan.vlc"
BIG=f0      # cores 4-7
ALL=ff      # cores 0-7
LITTLE_MIN_FAST=1305600
LITTLE_MIN_DEFAULT=1017600
# The car asks Suding for 60 fps (persist.suding.reverse.fps is rewritten on every connect), but
# the box's screen only runs at 30 Hz. Encoding every second frame halves the encoder's CPU.
FRAME_INTERVAL_PROP=suding.carplay.reverse.video.frame.interval

adb -s "$DEVICE" shell "
pin() {
  for name in $AUDIO_PROCESSES; do
    pids=\$(pidof \$name)
    if [ -z \"\$pids\" ]; then echo \"  \$name: not running\"; continue; fi
    for pid in \$pids; do
    # Thread by thread: taskset -a stops at the first thread it cannot move, which left
    # audioserver's mixer thread on all cores.
    ok=0; for t in /proc/\$pid/task/*; do taskset -p \$1 \${t##*/} >/dev/null 2>&1 && ok=\$((ok+1)); done
    echo \"  \$name (\$pid): \$ok threads on cores \$1\"
    done
  done
}
# The audio HAL's writer thread hands every mix to the DSP. As an ordinary thread it waited for
# a CPU longer than it ran, and the HAL counted underruns; it gets real-time priority 2, like the
# mixer threads that feed it. Its thread id changes on every start: find it by name.
writer() {
  hal=\$(pidof android.hardware.audio.service)
  for t in /proc/\$hal/task/*; do [ \"\$(cat \$t/comm)\" = writer ] && echo \${t##*/}; done
}
# The HAL creates a writer thread per open output stream, so there may be none yet, or several
# (after audioserver and the HAL restart, the music stream and another one each have one).
writer_priority() {
  ts=\$(writer)
  if [ -z \"\$ts\" ]; then echo '  Audio HAL writer thread not running yet: start music, then run apply again'; return; fi
  for t in \$ts; do chrt -\$1 -p \$t \$2; done
}
# AudioFlinger's output mixer threads (AudioOut_<handle>) mix every app's audio and run the
# equalizer effect chains (OABL's Dynamics Processing) in the same loop, then write to the HAL.
# Android leaves them ordinary threads at nice -19 (only FastMixer is real-time), so a busy core
# can delay a whole mix. Real-time priority 2 puts them level with the HAL writer they feed;
# FastMixer stays above at 3. Effects can't get a thread of their own without replacing
# AudioFlinger, so the mixer thread is where the equalizer's timing is set.
mixer() {
  af=\$(pidof audioserver)
  for t in /proc/\$af/task/*; do case \"\$(cat \$t/comm)\" in AudioOut_*) echo \${t##*/};; esac; done
}
mixer_priority() {
  ts=\$(mixer)
  if [ -z \"\$ts\" ]; then echo '  audioserver mixer threads not found'; return; fi
  for t in \$ts; do chrt -\$1 -p \$t \$2; done
}
# admin_open_play (a vendor init service) writes silence to codec output 0xB030 forever so the
# output, and the CarPlay capture fed from it, stays running. Its buffer is only 10 ms and it was
# an ordinary thread: it underran and restarted about 12 times a minute, and some restarts timed
# out in the DSP and made the kernel close and reopen 0xB030, a gap for music and CarPlay audio.
# At real-time priority 2: 0 restarts and 0 DSP timeouts in the same 2-minute test (2026-10-17).
keepalive_priority() {
  p=\$(pidof admin_open_play)
  if [ -z \"\$p\" ]; then echo '  admin_open_play not running'; return; fi
  chrt -\$1 -p \$p \$2
}
show() {
  p=\$(pidof admin_open_play)
  [ -n \"\$p\" ] && echo \"Audio keep-alive (admin_open_play): \$(chrt -p \$p | sed -n 's/.*policy: //p') priority \$(chrt -p \$p | sed -n 's/.*priority: //p')\"
  ts=\$(writer)
  if [ -z \"\$ts\" ]; then echo 'Audio HAL writer thread: not running (no music output open)'; fi
  for t in \$ts; do echo \"Audio HAL writer thread \$t: \$(chrt -p \$t | sed -n 's/.*policy: //p') priority \$(chrt -p \$t | sed -n 's/.*priority: //p')\"; done
  for t in \$(mixer); do echo \"Audio mixer \$(cat /proc/\$(pidof audioserver)/task/\$t/comm) (\$t): \$(chrt -p \$t | sed -n 's/.*policy: //p') priority \$(chrt -p \$t | sed -n 's/.*priority: //p')\"; done
  echo 'CPU limits:'
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    echo \"  \$(basename \$p) cores \$(cat \$p/related_cpus): min \$(cat \$p/scaling_min_freq) max \$(cat \$p/scaling_max_freq) now \$(cat \$p/scaling_cur_freq)\"
  done
  echo \"CarPlay video: every \$(getprop $FRAME_INTERVAL_PROP) frame(s) of \$(getprop persist.suding.reverse.fps) fps\"
  echo 'Audio processes (allowed cores, control group):'
  for name in $AUDIO_PROCESSES; do
    pid=\$(pidof \$name | cut -d' ' -f1)
    [ -z \"\$pid\" ] && continue
    mask=\$(taskset -p \$pid 2>/dev/null | sed 's/.*: //')
    echo \"  \$name (\$pid): mask \$mask, cpuset \$(cat /proc/\$pid/cpuset 2>/dev/null)\"
  done
  echo 'Temperatures:'
  for z in /sys/class/thermal/thermal_zone*; do
    case \$(cat \$z/type) in cpuss-2-usr|cpu-1-0-usr|gpu-usr|xo-therm-usr) echo \"  \$(cat \$z/type): \$((\$(cat \$z/temp)/1000)) C\";; esac
  done
}
case $ACTION in
  apply)
    echo $LITTLE_MIN_FAST > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
    setprop $FRAME_INTERVAL_PROP 2
    echo 'Pinning the audio chain to the performance cores (4-7):'
    pin $BIG
    keepalive_priority f 2
    mixer_priority f 2
    writer_priority f 2
    show ;;
  revert)
    echo $LITTLE_MIN_DEFAULT > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
    setprop $FRAME_INTERVAL_PROP 1
    keepalive_priority o 0
    mixer_priority o 0
    writer_priority o 0
    echo 'Audio chain back on all cores:'
    pin $ALL
    show ;;
  status)
    show ;;
  *)
    echo 'action must be apply, revert or status' ;;
esac
"
