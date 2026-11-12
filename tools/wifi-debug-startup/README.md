# Wireless debugging startup helper

For the AI box with hardware serial `<serial>`, Android 13. Package `com.softiva.wifidebugstartup`.

The reboot log showed `AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.` A persistent TCP-port property did not restore Android’s Wireless debugging toggle once that startup check failed.

Version 1.1 receives BOOT_COMPLETED and runs a persistent foreground service. It waits without a startup timeout, watches Wi-Fi/network changes and `adb_wifi_enabled`, and restores only `Settings.Global.adb_wifi_enabled=1`. A passive 30-second check supplements callbacks. It stops retrying after four consecutive Android refusals until a network change or Run now. It holds no wake lock. The device’s already configured `persist.adb.tcp.port=5555` makes the TCP endpoint available when the Android framework starts adbd. The app does not run root commands, alter USB configuration, approve new networks/keys, or change authentication policy. It does not turn Wi-Fi or the hotspot on.

A one-time ADB permission grant is required:

```sh
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE shell pm grant com.softiva.wifidebugstartup android.permission.WRITE_SECURE_SETTINGS
adb -s DEVICE shell pm grant com.softiva.wifidebugstartup android.permission.POST_NOTIFICATIONS
adb -s DEVICE shell am start -n com.softiva.wifidebugstartup/.SetupActivity
```

Run install with the correct path from this subproject. Open the app once after installation to activate its boot receiver. Its switch disables future startup enabling; uninstalling the app removes the helper and its permission grant. It does not disable currently running wireless debugging when the switch is turned off.

Build from the OABL project root:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME='$HOME/Library/Android/sdk' ./gradlew -p tools/wifi-debug-startup :app:assembleDebug
```

The attempted system init-file approach was not installed: the system partition is read-only, and staging files were removed. No verity changes, system init files, boot scripts, firmware writes or USB settings were applied.

Installed on the physical AI box, WRITE_SECURE_SETTINGS and notification permissions granted, and its initial run succeeded. Physical reboot verification is recorded in `docs/device-backups/2026-10-22-wifi-adb/` in the OABL project.

## Earlier physical observation (superseded by 23 October investigation)

Actual reboot completed and the helper enabled Wireless debugging automatically after Wi-Fi connected. ADB reconnected on `<device-ip>`; a changed boot ID, `adb_wifi_enabled=1`, a positive dynamic TLS port, and new-boot helper logs were saved in the backup directory. Reboot and reconnection took approximately 135 seconds. No manual switch activation was used in the verification sequence.


## Current status — 23 October 2026

Version 1.1 passed setting-toggle recovery but did not provide timely automatic access after the reboot test. On the subsequently observed boot, the ordered BOOT_COMPLETED queue took over two minutes, including a Qualcomm RCS receiver timeout. This helper started at elapsed 163,940 ms, after the user had already restored access. Later Android force-stopped it with reason `set debug app`. These are separate observed events; neither proves every earlier failure had the same cause.

OABL 0.1.9 now provides an opt-in watcher started by the default Home application process, independently of the boot broadcast. The separate helper is installed but its startup option is disabled, and its process is stopped, on AI box <serial>. See [current evidence](../../docs/device-backups/2026-10-23-wireless-debug/oabl-0.1.9/README.md). Home process startup and setting recovery passed; a full reboot of 0.1.9 has deliberately not been tested yet.
