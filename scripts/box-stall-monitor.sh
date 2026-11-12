#!/bin/sh
# Per-second timeline of the AI box while you listen, to find what chokes when audio breaks up
# and the dashboard freezes at the same moment.
#   scripts/box-stall-monitor.sh <ip:port> [minutes] [output-dir]
#
# Three streams run side by side and are merged into <output-dir>/timeline.txt when the time is
# up (or on Ctrl-C):
#   box sampler  frames drawn (SurfaceFlinger), busiest core and its interrupt share, threads
#                blocked on I/O or drivers, CPU/IO/memory pressure, and the CarPlay audio
#                capture (pcmC0D0c: frames captured vs read by sdCarplaySvc, worst backlog)
#   logcat       HAL underruns, CarPlay audio rate and video heartbeats (sdCarplaySvc), libVLC
#                drops ("playback way too late" etc.)
#   kernel log   audio DSP errors (q6asm/msm_pcm), display controller (sde) and I2C errors,
#                USB, MMC (memory card) and other driver errors or resets
# Seconds where something went wrong are tagged so they can be read side by side:
#   NO-FRAMES (nothing drawn), HAL-UNDERRUN, CAPTURE-SHORT (CarPlay capture under 45000 frames),
#   CP-AUDIO-DIP (CarPlay audio under 190 kB/s), VLC-DROP, KERNEL (driver message),
#   BLOCKED (2+ threads waiting on I/O or drivers).
# Read-only on the box: it only reads /proc, /sys, dumpsys-free counters and the logs.
DEVICE="$1"
MINUTES="${2:-5}"
OUT="${3:-box-monitor-$(date +%Y%m%d-%H%M%S)}"
[ -z "$DEVICE" ] && { echo "usage: $0 <ip:port> [minutes] [output-dir]" >&2; exit 2; }
mkdir -p "$OUT"

adb -s "$DEVICE" shell '
while :; do
  e=$(date +%s); u=$(cut -d" " -f1 /proc/uptime)
  f=$(service call SurfaceFlinger 1013 | sed -n "s/.*Parcel(\([0-9a-f]*\).*/\1/p"); f=$((0x${f:-0}))
  b=$(sed -n "s/procs_blocked //p" /proc/stat)
  p=""; for k in cpu io memory; do p="$p$(sed -n "s/^some avg10=\([0-9.]*\).*/\1/p" /proc/pressure/$k 2>/dev/null):"; done
  c=$(awk "/^state:/{s=\$2} /avail_max/{m=\$3} /^hw_ptr/{h=\$3} /^appl_ptr/{a=\$3} END{print s\":\"h\":\"a\":\"m}" /proc/asound/card0/pcm0c/sub0/status 2>/dev/null)
  cpu=$(awk "/^cpu[0-9]/{busy=\$2+\$3+\$4+\$7+\$8; printf \"%s:%d:%d:%d \", \$1, busy, \$7+\$8, busy+\$5+\$6}" /proc/stat)
  echo "S $e $u $f $b $p $c $cpu"
  sleep 1
done' > "$OUT/samples.txt" 2>&1 &
SAMPLER=$!
adb -s "$DEVICE" logcat -v epoch -T 1 audio_hw_primary:W sdCarplaySvc:V VLC:W AudioFlinger:W '*:S' > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
adb -s "$DEVICE" shell 'dmesg -w' > "$OUT/kernel.txt" 2>&1 &
KERNEL=$!

stop() {
  kill $SAMPLER $LOGCAT $KERNEL 2>/dev/null
  wait 2>/dev/null
}
trap 'stop; merge; exit 0' INT TERM

merge() {
  awk -v OUT="$OUT" '
  FILENAME ~ /logcat/ {
    if ($1 !~ /^[0-9]+\./) next
    sec = int($1)
    if ($0 ~ /out_write: underrun/) hal[sec]++
    if (match($0, /speaker out:[0-9]+/)) cp[sec] = substr($0, RSTART + 12, RLENGTH - 12)
    if ($0 ~ /video heartbeat/) vid[sec]++
    if ($0 ~ /way too late|way too early|timing screwed/) vlc[sec]++
    next
  }
  FILENAME ~ /kernel/ {
    if (!match($0, /^\[ *[0-9]+\.[0-9]+\]/)) next
    up = substr($0, RSTART + 1, RLENGTH - 2) + 0
    if ($0 ~ /audit|avc:|wlan: \[[0-9]+:I:/) next
    if (tolower($0) ~ /q6asm|adsp|msm_pcm|sde error|i2c error|usb|xhci|dwc3|mmc|sdhci|reset|timeout|thermal|throttl/) {
      msg = substr($0, RLENGTH + 2); gsub(/  +/, " ", msg)
      kup[++nk] = up; kmsg[nk] = substr(msg, 1, 70)
    }
    next
  }
  $1 == "S" {
    n++; E[n] = $2; U[n] = $3; F[n] = $4; B[n] = $5; P[n] = $6; C[n] = $7
    cores = 0
    for (i = 8; i <= NF; i++) { split($i, x, ":"); cores++; BU[n, cores] = x[2]; IR[n, cores] = x[3]; TO[n, cores] = x[4] }
    NC = cores; sub(/:$/, "", P[n])
  }
  END {
    off = (n ? E[1] - U[1] : 0)
    for (k = 1; k <= nk; k++) { s = int(kup[k] + off); ke[s] = (s in ke ? ke[s] " | " : "") kmsg[k] }
    printf "%-8s %6s %8s %7s %3s %-14s %-24s %5s %8s %3s  %s\n", "time", "frames", "busiest", "irq", "D", "psi cpu:io:mem", "capture hw/read/backlog", "hal", "cp B/s", "vlc", "tags"
    tagged = 0
    for (i = 2; i <= n; i++) {
      t = strftime_fallback(E[i])
      frames = F[i] - F[i - 1]
      top = 0; topc = 0; topirq = 0
      for (c = 1; c <= NC; c++) {
        dt = TO[i, c] - TO[i - 1, c]; if (dt <= 0) continue
        pct = 100 * (BU[i, c] - BU[i - 1, c]) / dt; ipct = 100 * (IR[i, c] - IR[i - 1, c]) / dt
        if (pct > top) { top = pct; topc = c - 1 }
        if (ipct > topirq) topirq = ipct
      }
      split(C[i], a, ":"); split(C[i - 1], b, ":")
      cap = "closed"
      if (a[1] != "" && a[1] != "closed" && b[2] != "") cap = sprintf("%s %d/%d/%d", a[1], a[2] - b[2], a[3] - b[3], a[4])
      s = E[i]
      tags = ""
      if (frames == 0) tags = tags " NO-FRAMES"
      if (hal[s]) tags = tags " HAL-UNDERRUN"
      if (cap != "closed" && a[2] - b[2] < 45000) tags = tags " CAPTURE-SHORT"
      if (s in cp && cp[s] + 0 < 190000) tags = tags " CP-AUDIO-DIP"
      if (vlc[s]) tags = tags " VLC-DROP"
      if (s in ke) tags = tags " KERNEL: " ke[s]
      if (B[i] + 0 >= 2) tags = tags " BLOCKED"
      if (tags != "") tagged++
      printf "%-8s %6d %4.0f%%/c%d %6.0f%% %3d %-14s %-24s %5d %8s %3d %s\n", t, frames, top, topc, topirq, B[i], P[i], cap, hal[s], (s in cp ? cp[s] : "-"), vlc[s], tags
    }
    printf "\n%d seconds sampled, %d with something tagged.\n", n - 1, tagged
  }
  function strftime_fallback(e,   cmd, r) { cmd = "date -r " e " +%H:%M:%S"; cmd | getline r; close(cmd); return r }
  ' "$OUT/logcat.txt" "$OUT/kernel.txt" "$OUT/samples.txt" > "$OUT/timeline.txt"
  echo "Timeline: $OUT/timeline.txt"
  grep -E "NO-FRAMES|UNDERRUN|SHORT|DIP|DROP|KERNEL|BLOCKED" "$OUT/timeline.txt" | head -40
}

echo "Monitoring $DEVICE for $MINUTES minute(s) into $OUT (Ctrl-C stops early)..."
sleep $((MINUTES * 60))
stop
merge
