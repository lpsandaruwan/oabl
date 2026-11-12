#!/bin/sh
set -eu
cd "$(dirname "$0")"
bundle="$PWD/build/OABL Bluetooth Lab.app"
mkdir -p "$bundle/Contents/MacOS"
cp Info.plist "$bundle/Contents/Info.plist"
cat BleSecureSession.swift MacPeripheral.swift OablBluetoothLab.swift > build/main.swift
xcrun swiftc -swift-version 5 -O -framework CoreBluetooth -framework CryptoKit -framework AppKit \
    build/main.swift -o "$bundle/Contents/MacOS/OablBluetoothLab"
codesign --force --sign - --identifier com.softiva.oabl.bluetoothlab.mac "$bundle"
printf '%s\n' "$bundle"
