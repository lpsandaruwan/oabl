#!/system/bin/sh
# Runs only on the opted-in private AI box. Does not change ADB authentication.
[ "$(getprop persist.oabl.usb_adb.enabled)" = 1 ] || exit 0
# The serial of the opted-in box is stored once with: setprop persist.oabl.box_serial <serial>
[ -n "$(getprop persist.oabl.box_serial)" ] && [ "$(getprop ro.serialno)" = "$(getprop persist.oabl.box_serial)" ] || exit 1

settings put global development_settings_enabled 1 || exit 1
settings put global adb_enabled 1 || exit 1
setprop persist.vendor.usb.config adb || exit 1
setprop persist.sys.usb.config adb || exit 1

if [ "$(getprop sys.usb.configfs)" != 1 ] || [ "$(getprop sys.usb.state)" != adb ] \
        || [ -d /config/usb_gadget/g1/configs/b.2 ]; then
    setprop sys.usb.configfs 1 || exit 1
    setprop sys.usb.config none || exit 1
    sleep 1
    # The vendor gadget leaves three Apple configurations behind. macOS can select its
    # audio configuration instead of b.1/ADB unless these runtime configurations go away.
    for config in b.2 b.3 b.4; do
        directory=/config/usb_gadget/g1/configs/$config
        [ -d "$directory" ] || continue
        for function in "$directory"/f*; do
            [ -L "$function" ] && rm "$function"
        done
        rmdir "$directory/strings/0x409" 2>/dev/null
        rmdir "$directory" || exit 1
    done
    printf '%s' "$(getprop ro.serialno)" > /config/usb_gadget/g1/strings/0x409/serialnumber || exit 1
    printf '%s' 'OABL development' > /config/usb_gadget/g1/strings/0x409/manufacturer || exit 1
    printf '%s' 'Bengal ADB' > /config/usb_gadget/g1/strings/0x409/product || exit 1
    setprop sys.usb.config adb || exit 1
fi
setprop sys.oabl.usb_adb.configured 1
