#!/usr/bin/env bash
# Read-only survey of the car's head unit: finds where it keeps battery %, range, odometer and trip data,
# so the OABL Link probe can be chosen (docs/ev-cockpit/energy-companion.md §4).
#
# Nothing is installed, changed, granted, started or stopped on the head unit. No root, no remount.
#
# Usage:
#   scripts/headunit-survey.sh <adb-serial> [--soc 76] [--range 250] [--odo 12345] [--trip 41.2]
#                              [--label parked] [--out DIR]
# Read the values off the car's own screens just before running, so the dumps can be searched for them.
# Run it twice in different states (for example parked, then after a short drive) with different labels.
set -u

if [ $# -lt 1 ] || [ "${1#-}" != "$1" ]; then
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
SERIAL=$1; shift
SOC=""; RANGE=""; ODO=""; TRIP=""; LABEL=$(date +%H%M%S); OUT=""
while [ $# -gt 0 ]; do
    case $1 in
        --soc) SOC=$2; shift 2 ;;
        --range) RANGE=$2; shift 2 ;;
        --odo) ODO=$2; shift 2 ;;
        --trip) TRIP=$2; shift 2 ;;
        --label) LABEL=$2; shift 2 ;;
        --out) OUT=$2; shift 2 ;;
        *) echo "Unknown option $1" >&2; exit 2 ;;
    esac
done
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[ -n "$OUT" ] || OUT="$ROOT/docs/headunit-survey/$(date +%Y-%m-%d)/$LABEL"
mkdir -p "$OUT"

if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "adb can't reach $SERIAL (try: adb connect <ip>:5555, or USB)." >&2
    exit 1
fi

# Runs one read-only shell command on the head unit, with a 25 s limit where the device has `timeout`.
dump() {
    local name=$1; shift
    adb -s "$SERIAL" shell "if command -v timeout >/dev/null 2>&1; then timeout 25 $*; else $*; fi" \
        > "$OUT/$name.txt" 2>&1
    printf '  %-28s %8s lines\n' "$name" "$(wc -l < "$OUT/$name.txt" | tr -d ' ')"
}

echo "Surveying $SERIAL into $OUT"
{
    echo "label=$LABEL"; echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "screen values: soc=$SOC range=$RANGE odo=$ODO trip=$TRIP"
} > "$OUT/survey.txt"

echo "Identity and platform"
dump id 'id; uname -a'
dump getprop 'getprop'
dump features 'pm list features'
dump libraries 'pm list libraries'

echo "Apps and services"
dump packages 'pm list packages -f -i -U'
dump packages-system 'pm list packages -s'
dump packages-user 'pm list packages -3'
dump services 'service list'
dump dumpsys-list 'dumpsys -l'
dump processes 'ps -A -o USER,PID,PPID,NAME'
dump providers 'dumpsys package providers'
dump permissions 'pm list permissions -g -f'
dump framework 'ls -la /system/framework /system_ext/framework /vendor/framework /product/framework'
dump permission-xml 'ls -la /system/etc/permissions /vendor/etc/permissions /product/etc/permissions /system_ext/etc/permissions'

echo "Car API (Android Automotive), if present"
dump car-service 'dumpsys car_service'
dump car-service-help 'cmd car_service -h'
dump hal-list 'lshal list'
dump vhal-aidl 'dumpsys android.hardware.automotive.vehicle.IVehicle/default --list'
# Standard property ids: EV_BATTERY_LEVEL, RANGE_REMAINING, PERF_ODOMETER, INFO_EV_BATTERY_CAPACITY,
# PERF_VEHICLE_SPEED, EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, EV_CHARGE_PORT_CONNECTED,
# ENV_OUTSIDE_TEMPERATURE, GEAR_SELECTION, IGNITION_STATE. Reads only; failures are expected on non-AAOS units.
for id in 0x11600309 0x11600308 0x11600204 0x11600106 0x11600207 0x1160030c 0x1120030b 0x11600703 0x11400400 0x11400409; do
    echo "== $id"
    adb -s "$SERIAL" shell "cmd car_service get-property-value $id 0" 2>&1 | head -5
done > "$OUT/car-properties.txt"
printf '  %-28s %8s lines\n' car-properties "$(wc -l < "$OUT/car-properties.txt" | tr -d ' ')"

echo "Broadcasts, settings and logs"
dump broadcasts 'dumpsys activity broadcasts'
dump settings-global 'settings list global'
dump settings-system 'settings list system'
dump settings-secure 'settings list secure'
dump logcat 'logcat -d -b main,system,events -v time'

echo "Hardware links and network"
dump devices 'ls -la /dev'
dump links 'ip -d link'
dump addresses 'ip addr'
dump wifi 'dumpsys wifi'
dump connectivity 'dumpsys connectivity'

# Where the screen values appear. A value next to a vehicle word is a strong lead.
WORDS='soc|batt|bms|energy|endurance|remain|range|mileage|odo|trip|consum|kwh|power|electric|vehicle|car|can|mcu|ev[_.]'
{
    echo "Hits for the values read off the car's screens (strong = on a line with a vehicle word)."
    for pair in "soc:$SOC" "range:$RANGE" "odo:$ODO" "trip:$TRIP"; do
        name=${pair%%:*}; value=${pair#*:}
        [ -n "$value" ] || continue
        pattern="(^|[^0-9.])$(printf '%s' "$value" | sed 's/\./\\./g')([^0-9]|$)"
        echo
        echo "== $name = $value"
        echo "-- strong"
        grep -n -i -E "$pattern" "$OUT"/*.txt 2>/dev/null | grep -v -E "/(survey|findings)\.txt:" \
            | sed "s#^$OUT/##" | grep -i -E "$WORDS" | cut -c1-200 | head -60
        echo "-- all hits per file"
        grep -c -E "$pattern" "$OUT"/*.txt 2>/dev/null | grep -v ':0$' | grep -v -E "/(survey|findings)\.txt:" | sed "s#^$OUT/##"
    done
    echo
    echo "== Vehicle-looking packages, services, providers and properties"
    grep -h -i -E "vehicle|car[._]|canbus|can_|mcu|bms|energy|battery|mileage|endurance|dongfeng|nammi|dfm|voyah" \
        "$OUT/packages.txt" "$OUT/services.txt" "$OUT/getprop.txt" "$OUT/permissions.txt" 2>/dev/null | sort -u | head -150
    echo
    echo "== Content provider authorities that look vehicle-related"
    grep -i -E "authority|Provider\{" "$OUT/providers.txt" 2>/dev/null | grep -i -E "vehicle|car|can|mcu|energy|battery|trip|mileage" | sort -u | head -60
    echo
    echo "== Sticky broadcasts"
    grep -i -A2 -E "sticky" "$OUT/broadcasts.txt" 2>/dev/null | grep -i -E "Intent|act=" | sort -u | head -60
    echo
    echo "== Wi-Fi: client, hotspot and concurrency"
    grep -h -i -E "Concurrency Supported|SoftApCapability|wifi_softap|feature:android.hardware.wifi" \
        "$OUT/wifi.txt" "$OUT/features.txt" 2>/dev/null | sort -u | cut -c1-200 | head -30
    echo
    echo "== Is this Android Automotive?"
    grep -h -E "android.hardware.type.automotive|android.car" "$OUT/features.txt" "$OUT/libraries.txt" 2>/dev/null \
        || echo "No automotive feature or android.car library: a phone-style Android head unit (vendor vehicle service)."
    grep -E "^\[ro\.(build\.version\.(release|sdk)|product\.(brand|model|manufacturer|board)|board\.platform|build\.type)\]" \
        "$OUT/getprop.txt" 2>/dev/null
} > "$OUT/findings.txt"

echo
echo "Done. Start with $OUT/findings.txt"
echo "Note: logcat, Wi-Fi and settings dumps can include personal data (contacts, network names). They stay on this Mac."
