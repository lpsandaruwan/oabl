#!/bin/sh
# Builds oabl-enc.dex (OABL's own screen encoder for the AI box) with javac and d8.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JAR=$SDK/platforms/android-34/android.jar
BT=$(ls -d $SDK/build-tools/* | sort -V | tail -1)
rm -rf out && mkdir -p out/classes
javac -source 8 -target 8 -Xlint:-options -cp "$JAR" -d out/classes $(find src -name '*.java')
$BT/d8 --lib "$JAR" --min-api 29 --output out $(find out/classes -name '*.class')
mv out/classes.dex oabl-enc.dex && rm -rf out
ls -l oabl-enc.dex
