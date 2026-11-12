#!/bin/sh
# Builds oabl-pip.dex next to this script (git-ignored build output). usage: build.sh
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
OUT="$(mktemp -d)"
javac -source 8 -target 8 -nowarn -cp "$JAR" -d "$OUT" OablPip.java
"$BT/d8" --min-api 30 --lib "$JAR" --output "$OUT" "$OUT"/*.class
cp "$OUT/classes.dex" oabl-pip.dex
rm -rf "$OUT"
