#!/bin/sh
# Runs Suding's AA daemon under the oabltrace shim on the AI box, and puts it back.
#   box-trace.sh start [ADB_SERIAL]   stop the sd-reverse service, start sdAutoReverse with LD_PRELOAD
#   box-trace.sh stop  [ADB_SERIAL]   kill the traced daemon, start the stock sd-reverse service again
#   box-trace.sh log   [ADB_SERIAL]   copy /data/local/tmp/oabl-trace.log here as oabl-trace.log
# Nothing is written under /system. A reboot also restores the stock service. Only for the owner's box.
cd "$(dirname "$0")"
cmd=${1:-}
dev=${2:-}
adbd() { if [ -n "$dev" ]; then adb -s "$dev" "$@"; else adb "$@"; fi; }
case "$cmd" in
start)
  [ -f liboabltrace.so ] || ./build.sh
  adbd push liboabltrace.so /data/local/tmp/liboabltrace.so >/dev/null
  adbd shell "TRACE_ENV='$TRACE_ENV'; "'rm -f /data/local/tmp/oabl-trace.log
    [ "$(cat /sys/devices/platform/soc/soc:qpdata_driver/cp_aa)" = 2000 ] || { echo "box is not in Android Auto mode (cp_aa)"; exit 1; }
    setprop ctl.stop sd-reverse; sleep 2
    for p in $(pidof sdAutoReverse) $(pidof sd_mdnsd); do kill $p; done; sleep 1
    setprop persist.carplay.auto 2
    setprop persist.vendor.usb.config autoReverseTemp
    insmod /system/lib/modules/snd-aloop.ko 2>/dev/null
    cd /system/bin
    nohup setsid sh -c "./sd_mdnsd & $TRACE_ENV LD_PRELOAD=/data/local/tmp/liboabltrace.so exec ./sdAutoReverse -c sd_msm4250" >/dev/null 2>&1 &
    sleep 3; pidof sdAutoReverse; head -5 /data/local/tmp/oabl-trace.log'
  ;;
stop)
  adbd shell 'for p in $(pidof sdAutoReverse) $(pidof sd_mdnsd); do kill $p; done; sleep 1
    setprop ctl.start sd-reverse; sleep 3; getprop init.svc.sd-reverse; pidof sdAutoReverse'
  ;;
log)
  adbd pull /data/local/tmp/oabl-trace.log ./oabl-trace.log
  ;;
*) echo "usage: $0 start|stop|log [adb serial]"; exit 2 ;;
esac
