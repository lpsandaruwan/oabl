#!/bin/sh
# Installs the oabl-affinity watcher as a boot service on the AI box (root ADB, /system overlay).
# Usage: sh scripts/box-affinity-boot.sh DEVICE install|start|status|off|on|uninstall
#   install    copy /system/bin/oabl-affinity and /system/etc/init/oabl-affinity.rc, enable,
#              and start it now (init reads the .rc from the next boot on)
#   start      start the watcher now by hand (same binary and root context as at boot)
#   status     service state, switch, and the watcher's own placement check
#   off / on   the off switch: persist.oabl.affinity.enabled 0 / 1 (off restores placement)
#   uninstall  off, then remove both files (oabl-wireless-adb.rc is never touched)
# Build the binary first: ANDROID_NDK_HOME=... sh scripts/box-affinity.sh DEVICE build
set -eu
DEVICE=${1:-}
ACTION=${2:-status}
ADB=${ADB:-adb}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BINARY="$SCRIPT_DIR/../build/device-tools/oabl-affinity"
RC="$SCRIPT_DIR/oabl-affinity.rc"
[ -n "$DEVICE" ] || { echo "usage: $0 DEVICE install|start|status|off|on|uninstall" >&2; exit 2; }
run() { "$ADB" -s "$DEVICE" shell "$@"; }
[ "$(run id -u | tr -d '\r')" = 0 ] || { echo 'Root ADB required.' >&2; exit 1; }

start_now() {
  # A watcher started earlier by box-affinity.sh (from /data/local/tmp) is stopped first; it restores.
  run "if [ -f /data/local/tmp/oabl-affinity-state/watch.pid ]; then /system/bin/oabl-affinity stop; fi"
  run "mkdir -p /data/local/tmp/oabl-affinity-state; chmod 700 /data/local/tmp/oabl-affinity-state; nohup /system/bin/oabl-affinity watch </dev/null >/data/local/tmp/oabl-affinity-state/watch.log 2>&1 &"
  sleep 3
  run "cat /data/local/tmp/oabl-affinity-state/watch.log; /system/bin/oabl-affinity status | tail -1"
}

case "$ACTION" in
  install)
    [ -f "$BINARY" ] || { echo 'Build the binary first (box-affinity.sh DEVICE build).' >&2; exit 2; }
    "$ADB" -s "$DEVICE" remount
    "$ADB" -s "$DEVICE" push "$BINARY" /system/bin/oabl-affinity
    "$ADB" -s "$DEVICE" push "$RC" /system/etc/init/oabl-affinity.rc
    run "chown root:shell /system/bin/oabl-affinity; chmod 755 /system/bin/oabl-affinity; chmod 644 /system/etc/init/oabl-affinity.rc; chcon u:object_r:system_file:s0 /system/bin/oabl-affinity /system/etc/init/oabl-affinity.rc"
    run "/system/bin/oabl-affinity self-test"
    run "setprop persist.oabl.affinity.enabled 1"
    start_now
    echo "Installed. init starts it at every boot from now on (after the next boot it runs as service oabl-affinity)."
    ;;
  start) start_now ;;
  status)
    run "echo service=\$(getprop init.svc.oabl-affinity) switch=\$(getprop persist.oabl.affinity.enabled); ls -la /system/bin/oabl-affinity /system/etc/init/oabl-affinity.rc; /system/bin/oabl-affinity status | tail -3"
    ;;
  off)
    run "setprop persist.oabl.affinity.enabled 0; sleep 1; /system/bin/oabl-affinity stop || true"
    ;;
  on)
    run "setprop persist.oabl.affinity.enabled 1"
    run "[ \"\$(getprop init.svc.oabl-affinity)\" = running ] || [ -f /data/local/tmp/oabl-affinity-state/watch.pid ]" || start_now
    ;;
  uninstall)
    run "setprop persist.oabl.affinity.enabled 0; sleep 1; /system/bin/oabl-affinity stop || true"
    "$ADB" -s "$DEVICE" remount
    run "rm -f /system/etc/init/oabl-affinity.rc /system/bin/oabl-affinity"
    echo "Removed. Placement was restored; nothing starts at the next boot."
    ;;
  *) echo "Unknown action: $ACTION" >&2; exit 2 ;;
esac
