# Persistent network ADB — private Bengal box

Target serial: `<serial>`, Android 13. Address at installation:
`<device-ip>`. The user requested reboot persistence and approved a parked
reboot test. The IP is DHCP-managed; port persistence does not reserve the IP.

## Before the change

- Root ADB shell available; no Magisk/service.d startup hook present.
- `persist.adb.tcp.port=5555`, `persist.adb.tls_server.enable=1` and
  `adb_wifi_enabled=1` were already set, but the user reported ADB turning off
  after restart. The exact component resetting it was not isolated.
- The `adbd` init service is disabled by default and USB-mode init actions can
  explicitly stop it. A saved TCP port alone does not keep that service running.
- The firmware has `ro.adb.secure=0` and grants root ADB. This pre-existing
  security policy is unchanged. The user acknowledged using a private network.
- `persist.sys.usb.config=appleSvcTemp`; `sys.usb.config=none`.
- `/system` already used an overlayfs mount, initially read-only. Verity was
  already disabled. No verity, boot-image or firmware modifications are needed.

## Installed rule

Source: `scripts/device-init/oabl-wireless-adb.rc`.
Device: `/system/etc/init/oabl-wireless-adb.rc`, root:root, mode 0644,
`u:object_r:system_file:s0`.

`persist.oabl.adb.enabled=1` opts this particular box into the rule. At boot
completion it selects TCP 5555 and restarts ADB to read that port. Subsequent
daemon-stop events start it again while the opt-in remains set. There is no
polling process, new APK, USB-function change or change to audio/launcher data.
The diagnostic property `sys.oabl.adb.boot_ready=1` confirms the boot action ran;
an actual connection/listener check is still required.

The existing `/system` overlay is temporarily remounted writable to install the
new file, then returned read-only. Android init loads the rule on the next boot,
not when the file is pushed.

## Disable / rollback

First run `setprop persist.oabl.adb.enabled 0` from a root shell so the rule no
longer restarts ADB. This disables the guard, not the independently configured
wireless-debugging settings. To remove the rule, remount `/system` writable,
remove only `/system/etc/init/oabl-wireless-adb.rc`, then return `/system`
read-only. Already loaded init actions remain inert with the opt-in set to zero.
Do not clear the system overlay or replace any existing init files.

## Validation

- Installed file SHA-256:
  `c30221000c41bd40a7213ce2c1b126e34b8d7b69be22be618870eee75cd7ad1c`.
- Actual reboot completed: boot ID changed from
  `95b52e82-7479-4052-b794-c91656fafc1d` to
  `803ae79b-2ff6-4a3e-897f-fa75610c063f`.
- Reconnected automatically to `<device-ip>`; no manual settings changes,
  pairing, or post-reboot `adb tcpip` command were used.
- After boot, `sys.boot_completed=1`, `sys.oabl.adb.boot_ready=1`,
  `persist.oabl.adb.enabled=1`, both TCP-port properties were `5555`,
  and `init.svc.adbd=running`. A root shell on serial `<serial>` worked.
- ADB PID 3326 was listening on `*:5555`. The rule file, owner, mode, context
  and checksum survived the reboot, and `/system` was read-only again.
- Crucially, `persist.adb.tls_server.enable` had reset to `0` during reboot.
  This demonstrates that the usual wireless/TLS enable flag does not survive
  this boot path. The separate legacy TCP 5555 endpoint remained accessible
  because of the installed init action. The exact code resetting TLS was not
  isolated; the Android Wireless debugging toggle is not the status of legacy
  TCP ADB.
- CarPlay USB properties remained `persist.sys.usb.config=appleSvcTemp` and
  `sys.usb.config=none`. No existing init files or user media/settings were
  changed. A later physical power-cycle and an OTA update were not tested.
- A second restart occurred during validation (not issued by the testing agent):
  boot ID `4dc30adc-3035-430d-b60f-c36af01798a8`. Its reported boot reason was
  `reboot`; its trigger is not established. TCP 5555 returned without any
  device-side re-enabling. The Android `adb_wifi_enabled` setting was `0`,
  independently confirming that legacy TCP ADB works with that toggle off.
  A subsequent check at uptime one minute retained the same boot ID and ADB
  PID 3050, `sys.oabl.adb.boot_ready=1`, and a listening TCP 5555 socket.
  The host used `adb connect` to recover its stale/offline transport; this did
  not change device settings.

Reference: [Android init action and property-trigger syntax](https://android.googlesource.com/platform/system/core/+/master/init/README.md).

## Reinstalled — 24 October 2026

- **What was found:** the `/system` overlay and this rule were gone, and `persist.oabl.adb.enabled` was empty. That's consistent with a data reset around 22 October, when OABL's own first-install date also resets. `/` was the plain read-only image.
- **The app-level keeper isn't enough on its own.** On boot 39 OABL's keeper (`WirelessDebugKeeper`) did not re-enable debugging; SystemUI logged `toggle_adb_wireless` when the user switched it on by hand. On the next boot the keeper did re-enable it, after about 20 s. So it works sometimes, not reliably.
- **Steps:**
  1. `adb remount` created overlayfs for `/system`, `/system_ext`, `/vendor` and `/product` (upper dirs in `/mnt/scratch/overlay`).
  2. `setprop persist.oabl.adb.enabled 1`.
  3. Reboot to activate the overlay.
  4. Pushed `oabl-wireless-adb.rc`: root:root, 0644, `system_file`, SHA-256 `c30221000c41…7ad1c` (unchanged from 17 October). `/system` returned to read-only.
- **Verified by a second reboot** (boot ID `43fdb767-c81c-448a-8350-b2a2efbd04b0`). ADB came back at `<device-ip>` about 41 s after the reboot command, with no manual step. `sys.oabl.adb.boot_ready=1`, `service.adb.tcp.port=5555`, adbd running and listening on `[::]:5555`. **`adb_wifi_enabled=0` and no SystemUI toggle**, so the init rule, not the keeper or Android's toggle, brought ADB back.
- USB/CarPlay configuration was unchanged: `persist.sys.usb.config=appleSvcTemp`, `sys.usb.config=none`.
- **Still untested:** a factory reset or `/data` wipe removes the overlay again (repeat these steps), and so might a firmware update. The IP is DHCP; reserve it on the router.
