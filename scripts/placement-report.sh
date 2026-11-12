#!/system/bin/sh
# Read-only: where the audio, OABL and Suding CarPlay threads may run (allowed cores per thread
# and their cpusets), plus which cores are awake. Same process list as scripts/oabl-affinity.c.
# Usage on the box (root): sh placement-report.sh
targets="audioserver android.hardware.audio.service media.swcodec media.codec sdCarplaySvc sd_carplay carnetos.usbmedia android.hardware.bluetooth@1.0-service-qti"
echo "awake cores: $(cat /sys/devices/system/cpu/online)  fast cores kept awake by core_ctl: $(cat /sys/devices/system/cpu/cpu4/core_ctl/min_cpus 2>/dev/null)"
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  n=$( (tr '\0' ' ' < $d/cmdline) 2>/dev/null | cut -d' ' -f1)
  [ -z "$n" ] && continue
  b=${n##*/}
  hit=0
  case "$b" in com.softiva.oabl|com.softiva.oabl:*|com.suding.speedplay|com.suding.speedplay:*|com.android.bluetooth|com.carsyso.bluetooth) hit=1;; esac
  for t in $targets; do [ "$b" = "$t" ] && hit=1; done
  [ $hit = 1 ] || continue
  summary=$(for t in $d/task/*; do echo "$(grep Cpus_allowed_list $t/status 2>/dev/null | awk '{print $2}')@$(cat $t/cpuset 2>/dev/null)"; done | sort | uniq -c | awk '{printf "%s×%s  ", $2, $1}')
  echo "$b ($p): $summary"
done
