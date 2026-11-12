#!/bin/sh
# Logs underruns at both ends of the AI box's audio output, to tell which side is starving.
#   scripts/audio-underruns.sh <ip:port> [interval-seconds] [minutes] > underruns.log
#
# One line per active output thread per sample (host clock, so it can be matched to what you hear):
#   hal_underruns  the HAL's "Fifo frame underruns" count: the output device ran dry.
#   delayed        mixer writes that reached the HAL late.
#   track=...      every active track on that thread: id, client PID (tagged "oabl" for OABL),
#                  buffer frames, frames ready, and the frames the track was short because the
#                  app didn't write in time (48 frames = 1 ms). PcmPlaybackProbe's clientUnderruns
#                  counts the same starvation as events instead.
# Counters are cumulative; (+n) is the change since the previous sample. A line that saw a change
# ends in CLIENT-UNDERRUN and/or HAL-UNDERRUN, with the size of the last HAL gap. A track id seen
# for the first time is tagged "new" (LibVLC may open a new track for each song).
#
# Read-only: runs dumpsys and pidof. "Normal mixer raw underrun counters" are not used; they
# count FastMixer underruns, and the music output (AudioOut_15, deep buffer) has no FastMixer.
DEVICE="$1"
INTERVAL="${2:-1}"
MINUTES="${3:-20}"
[ -z "$DEVICE" ] && { echo "usage: $0 <ip:port> [interval-seconds] [minutes]" >&2; exit 2; }
END=$(( $(date +%s) + MINUTES * 60 ))

while [ "$(date +%s)" -lt "$END" ]; do
  echo "@@SAMPLE $(date +%H:%M:%S)"
  adb -s "$DEVICE" shell 'echo "@@PIDS $(pidof com.softiva.oabl com.softiva.oabl:services)"; dumpsys media.audio_flinger' 2>/dev/null
  sleep "$INTERVAL"
done | awk '
function delta(key, value,   d) {
  d = (key in prev) ? value - prev[key] : 0
  prev[key] = value
  return d
}
function flush(   i, tags, d, dd, t) {
  for (i = 1; i <= nthreads; i++) {
    t = threads[i]
    d = delta(t ":fifo", fifo[t]); dd = delta(t ":delayed", delayed[t])
    # Idle threads are skipped unless their HAL counter moved.
    if (tracks[t] == "" && d <= 0) continue
    tags = ""
    if (clientUnderrun[t]) tags = tags " CLIENT-UNDERRUN"
    if (d > 0) tags = tags " HAL-UNDERRUN(last=" lastMs[t] "ms)"
    print stamp " " t " hal_underruns=" fifo[t] "(+" d ") delayed=" delayed[t] "(+" dd ")" tracks[t] tags
  }
  fflush()
}
function reset() {
  nthreads = 0; thread = ""; table = 0
  split("", threads); split("", tracks); split("", fifo); split("", delayed); split("", lastMs); split("", clientUnderrun)
}
{ sub(/\r$/, "") }
/^@@SAMPLE / { if (stamp != "") flush(); stamp = $2; samples++; reset(); next }
/^@@PIDS/ { split("", oabl); for (i = 2; i <= NF; i++) oabl[$i] = 1; next }
# Input and other non-output threads have track tables with different columns.
/^[A-Za-z]+ thread 0x/ && !/^Output thread / { thread = ""; table = 0; next }
/^Output thread / {
  table = 0
  thread = ""
  for (i = 1; i < NF; i++) if ($i == "name") { thread = $(i + 1); sub(/,$/, "", thread) }
  if (thread != "") { threads[++nthreads] = thread; fifo[thread] = 0; rate[thread] = 48000 }
  next
}
thread == "" { next }
/^  Sample rate: / { rate[thread] = $3; next }
/^  Delayed writes: / { delayed[thread] = $3; next }
/Fifo frame underruns: \(/ {
  for (i = 1; i < NF; i++) {
    if ($i == "n:") { v = $(i + 1); sub(/\)$/, "", v); fifo[thread] = v + 0 }
    if ($i == "last:") lastMs[thread] = int($(i + 1) * 1000 / rate[thread] + 0.5)
  }
  next
}
/Active Client Session/ { table = 1; next }
table {
  # Data rows: [Type] Id Active Client Session ... Server FrmCnt FrmRdy F Underruns Flushed Latency
  o = ($2 == "yes" || $2 == "no") ? 0 : (($3 == "yes" || $3 == "no") ? 1 : -1)
  if (o < 0 || NF < 23 + o) { table = 0; next }
  id = $(1 + o); pid = $(3 + o)
  if ($(2 + o) != "yes" && !(pid in oabl)) next
  u = $(22 + o) + 0
  key = thread ":" id
  # A track that appears after the first sample started at zero, so all of its underruns are new.
  isNew = samples > 1 && !(key in prev)
  if (isNew) prev[key] = 0
  d = delta(key, u)
  if (d > 0) clientUnderrun[thread] = 1
  tracks[thread] = tracks[thread] " track=" id ":" ((pid in oabl) ? "oabl" : "pid" pid) ":" (($(2 + o) == "yes") ? "" : "inactive:") \
    "frames" $(19 + o) ":ready" $(20 + o) ":short_frames" u "(+" d ")" (isNew ? ":new" : "")
  next
}
END { if (stamp != "") flush() }
'
