#!/bin/sh
# Builds liboabltrace.so for the AI box's 32-bit userland (armeabi-v7a, API 29).
set -e
cd "$(dirname "$0")"
NDK=${NDK:-$HOME/Library/Android/sdk/ndk/27.1.12297006}
BIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
$BIN/armv7a-linux-androideabi29-clang -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
  -Wl,-z,max-page-size=4096 -Wl,--no-undefined -o liboabltrace.so oabltrace.c -ldl
$BIN/llvm-strip --strip-unneeded liboabltrace.so -o liboabltrace.stripped.so 2>/dev/null || true
ls -l liboabltrace.so
