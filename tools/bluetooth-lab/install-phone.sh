#!/bin/sh
set -eu
if [ "$#" -ne 1 ]; then printf '%s\n' 'Usage: sh install-phone.sh PHONE_ADB_SERIAL' >&2; exit 2; fi
cd "$(dirname "$0")"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
serial="$1"
# Serials of the vehicle and AI box, space separated, that the lab must not touch: export LAB_REFUSE_SERIALS='abc123 def456'
case " ${LAB_REFUSE_SERIALS:-} " in *" $serial "*) printf '%s\n' 'Use the phone, not the vehicle or AI box, for the lab.' >&2; exit 2;; esac
model="$($adb_bin -s "$serial" shell getprop ro.product.model | tr -d '\r')"
printf 'Installing the standalone Bluetooth lab on %s (%s)\n' "$model" "$serial"
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
api="$($adb_bin -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$api" -ge 31 ]; then
    "$adb_bin" -s "$serial" shell pm grant com.softiva.oabl.bluetoothlab android.permission.BLUETOOTH_CONNECT
    "$adb_bin" -s "$serial" shell pm grant com.softiva.oabl.bluetoothlab android.permission.BLUETOOTH_ADVERTISE
    "$adb_bin" -s "$serial" shell pm grant com.softiva.oabl.bluetoothlab android.permission.BLUETOOTH_SCAN
fi
if [ "$api" -ge 33 ]; then "$adb_bin" -s "$serial" shell pm grant com.softiva.oabl.bluetoothlab android.permission.POST_NOTIFICATIONS; fi
"$adb_bin" -s "$serial" shell am start -n com.softiva.oabl.bluetoothlab/.MainActivity
