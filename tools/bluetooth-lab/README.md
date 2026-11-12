# OABL Bluetooth Lab — 0.2.0

Phone/Mac tests for the Bluetooth transport now integrated into both production OABL apps. The Android lab uses the same `:bluetooth` GATT server/client and `:wire` framing/encrypted sessions as production. Its data is explicitly `simulated`; the Swift Mac app independently implements the wire protocol using CoreBluetooth and CryptoKit.

Authenticated OS Bluetooth pairing **and** the six-digit OABL code are required. Unlock both screens and confirm matching system pairing numbers when prompted. The Mac discovers the custom BLE service directly; it need not appear in Android's ordinary discovery list first. Tests require no hotspot. The phone lab has no Internet permission or boot receiver.

## Build

From the repository root, build serially because these Gradle projects share module output directories:

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew -p tools/bluetooth-lab :app:assembleDebug :app:assembleDebugAndroidTest :core:test :wire:test
sh tools/bluetooth-lab/mac/build.sh
```

Install only on the authorized Android test phone:

```sh
sh tools/bluetooth-lab/install-phone.sh PHONE_ADB_SERIAL
```

The installer grants nearby-device and notification permissions, then opens the lab. It does not change Home, USB modes, Wi-Fi or the Bluetooth name. System bonding requires user confirmation. Tap **Stop BLE test** when finished, or force-stop only `com.softiva.oabl.bluetoothlab`.

## Android peripheral → Mac central

Start the phone lab. Read its six-digit code and run:

```sh
open -n 'tools/bluetooth-lab/mac/build/OABL Bluetooth Lab.app' --args \
  --code 123456 --duration 65 --log /tmp/oabl-bluetooth-lab.log
```

Allow Bluetooth for **OABL Bluetooth Lab** on macOS. Confirm system pairing on both devices if asked. Success ends with `RESULT PASS`; timeouts and failures must not be counted as successes.

Service UUID: `650f0001-b763-4f40-a619-4d88e9107310`; RX write characteristic `650f0002-…`; TX indication characteristic `650f0003-…`. Encrypted authenticated GATT writes and subscriptions are required. Bounded fragments support ATT MTU 23. Fresh P-256 sessions and directional AES-GCM records supplement the signed, timestamped, nonce-protected read protocol.

## Mac peripheral → Android central

Stop the phone's advertising test, then start the Mac advertiser:

```sh
open -n 'tools/bluetooth-lab/mac/build/OABL Bluetooth Lab.app' --args \
  --peripheral --code 123456 --log /tmp/oabl-mac-peripheral.log
```

Wait until its log says advertising is active. Install and invoke the companion instrumentation:

```sh
adb -s PHONE_ADB_SERIAL install -r tools/bluetooth-lab/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s PHONE_ADB_SERIAL shell am instrument -w -e code 123456 \
  com.softiva.oabl.bluetoothlab.test/com.softiva.oabl.bluetoothlab.ClientTestRunner
```

To exercise server advertise/retry/close without a Mac connection, add `-e lifecycle_only true`. Force-stop the phone lab before this check so its foreground service does not compete for the advertiser.

## Verified results — 23 October 2026

Samsung SM-S928B (S24 Ultra), Android 17, and this Mac's real Bluetooth controller:

- Android peripheral ↔ Mac central: **78 checks, 32 fresh reads, two connections, 65 seconds, BLE only**.
- Android central ↔ Mac peripheral: **27 checks**, including authentication, wrong-code/replay/stale rejection, changing SOC and remembered-address reconnect with a fresh session.
- Android server lifecycle: **four checks**, including setup, advertise, retry and cleanup.

See [integrated transport report](../../docs/ev-cockpit/bluetooth-integration-2026-10-23/README.md). The earlier 68-check unencrypted v1 result remains in `docs/ev-cockpit/bluetooth-lab-2026-10-23/` as historical evidence.

The production APKs are in [the 0.1.2 bundle](../../dist/oabl-link-0.1.2/README.md). Vehicle BLE roles, OS pairing UI, real telemetry, audio coexistence, energy use and reboot recovery still require the head unit/AI box. This custom session layer has not had an independent security audit. Classic RFCOMM is not implemented.

To remove the phone app, run `adb -s PHONE_ADB_SERIAL uninstall com.softiva.oabl.bluetoothlab`. The companion package is `com.softiva.oabl.bluetoothlab.test`. The Mac app exits after a run; manage its Bluetooth permission in System Settings.
