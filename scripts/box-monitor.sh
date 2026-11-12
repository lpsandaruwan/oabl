#!/bin/sh
# Logs what the AI box is doing every 2 s, to find the cause of audio/video stutter.
#   scripts/box-monitor.sh <ip:port> [minutes] > monitor.log
# Each line: time, mixer underruns (partial/empty, cumulative), CarPlay audio rate, temperatures,
# CPU frequencies (little/big) and the three busiest processes.
DEVICE="$1"
MINUTES="${2:-20}"
[ -z "$DEVICE" ] && { echo "usage: $0 <ip:port> [minutes]" >&2; exit 2; }
adb -s "$DEVICE" logcat -c 2>/dev/null
END=$(( $(date +%s) + MINUTES * 60 ))
while [ "$(date +%s)" -lt "$END" ]; do
  adb -s "$DEVICE" shell '
    u=$(dumpsys media.audio_flinger 2>/dev/null | grep "raw underrun counters" | sed "s/.*partial=\([0-9]*\) empty=\([0-9]*\)/\1 \2/" | { p=0; e=0; while read a b; do p=$((p+a)); e=$((e+b)); done; echo "$p/$e"; })
    r=$(logcat -d -s sdCarplaySvc 2>/dev/null | grep "HU audio type" | tail -1 | sed "s/.*rateAvg://")
    t=""; for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in cpuss-2-usr|cpu-1-0-usr|gpu-usr) t="$t$(( $(cat $z/temp) / 1000 ))/";; esac; done
    f="$(( $(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq) / 1000 ))/$(( $(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq) / 1000 ))"
    top=$(top -b -n 1 -d 1 -o %CPU,NAME -s 1 | sed -n "6,8p" | tr -s " " | tr "\n" ",")
    echo "$(date +%H:%M:%S) underruns=$u carplay_rate=$r temp=${t%/} mhz=$f top=$top"
  ' 2>/dev/null
  sleep 2
done
