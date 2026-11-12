#!/bin/bash
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "Refusing: Android emulator serial required" >&2; exit 2;; esac
adb_bin="${OABL_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
hardware="$("$adb_bin" -s "$serial" shell getprop ro.hardware | tr -d '\r')"
case "$hardware" in ranchu|goldfish) ;; *) echo "Refusing non-emulator hardware" >&2; exit 2;; esac
repo_dir="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
controller="$repo_dir/oabl-link/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$controller" ]]; then
  echo "Build the simulator controller first; see tools/simulation/README.md" >&2
  exit 1
fi
"$adb_bin" -s "$serial" install -r "$repo_dir/dist/oabl-link-0.1.2/oabl-link-headunit-0.1.2.apk"
"$adb_bin" -s "$serial" install -r "$repo_dir/dist/oabl-launcher-0.1.7/oabl-launcher-0.1.7.apk"
"$adb_bin" -s "$serial" install -r "$controller"
"$adb_bin" -s "$serial" shell pm grant com.koushikdutta.vysor android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
"$adb_bin" -s "$serial" shell pm grant com.koushikdutta.vysor android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
"$adb_bin" -s "$serial" shell am start -n com.softiva.oabl/.MainActivity
echo "Starting the emulator-only controller for two hours. Select phases with scenario.py."
exec "$adb_bin" -s "$serial" shell am instrument -w -e elapsed_seconds 300 -e duration_seconds 7200 \
  com.koushikdutta.vysor.test/com.softiva.oabllink.app.SimulationScenarioRunner
