#!/bin/sh
# Runtime CPU-affinity controller only. Does not run/change box-tune.sh.
# Build once: ANDROID_NDK_HOME=/path/to/ndk sh scripts/box-affinity.sh DEVICE build
# Then: sh scripts/box-affinity.sh DEVICE deploy|status|start|stop|restore|self-test|probe
# Root ADB is required; no su, boot image, partition or OEM script changes.
set -eu
DEVICE=${1:-}
ACTION=${2:-status}
ADB=${ADB:-adb}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LOCAL_BINARY="$SCRIPT_DIR/../build/device-tools/oabl-affinity"
REMOTE_BINARY=/data/local/tmp/oabl-affinity-helper
STATE=/data/local/tmp/oabl-affinity-state
[ -n "$DEVICE" ] || { echo "usage: $0 DEVICE build|deploy|status|start|stop|restore|self-test|probe" >&2; exit 2; }
case "$ACTION" in
  build)
    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
    case $(uname -s) in Darwin) HOST=darwin-x86_64;; Linux) HOST=linux-x86_64;; *) echo "Unsupported build host" >&2; exit 2;; esac
    mkdir -p "$SCRIPT_DIR/../build/device-tools"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android26-clang" \
      -std=c11 -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
      -fPIE -pie -pthread "$SCRIPT_DIR/oabl-affinity.c" -o "$LOCAL_BINARY"
    echo "Built $LOCAL_BINARY"
    ;;
  deploy)
    [ -f "$LOCAL_BINARY" ] || { echo 'Build the helper first.' >&2; exit 2; }
    [ "$("$ADB" -s "$DEVICE" shell id -u | tr -d '\r')" = 0 ] || { echo 'Root ADB required.' >&2; exit 1; }
    # Never overwrite a live executable; explicitly stop the watcher first when updating.
    "$ADB" -s "$DEVICE" shell "if [ -f $STATE/watch.pid ]; then echo 'Stop the existing watcher before deployment.' >&2; exit 1; fi"
    "$ADB" -s "$DEVICE" push "$LOCAL_BINARY" "$REMOTE_BINARY"
    "$ADB" -s "$DEVICE" shell "chmod 700 $REMOTE_BINARY; $REMOTE_BINARY self-test"
    ;;
  start)
    [ "$("$ADB" -s "$DEVICE" shell id -u | tr -d '\r')" = 0 ] || { echo 'Root ADB required.' >&2; exit 1; }
    "$ADB" -s "$DEVICE" shell "if [ -f $STATE/watch.pid ]; then echo 'Existing watcher state: stop it before starting another.' >&2; exit 1; fi"
    "$ADB" -s "$DEVICE" shell "mkdir -p $STATE; chmod 700 $STATE; nohup $REMOTE_BINARY watch </dev/null >$STATE/watch.log 2>&1 &"
    sleep 3
    "$ADB" -s "$DEVICE" shell "cat $STATE/watch.log; $REMOTE_BINARY status"
    ;;
  status|stop|restore|self-test)
    "$ADB" -s "$DEVICE" shell "$REMOTE_BINARY $ACTION"
    ;;
  probe)
    "$ADB" -s "$DEVICE" shell "exec -a com.softiva.oabl:affinity-probe $REMOTE_BINARY probe"
    ;;
  *) echo "Unknown action: $ACTION" >&2; exit 2;;
esac
