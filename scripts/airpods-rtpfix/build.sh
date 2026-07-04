#!/usr/bin/env bash
#
# build.sh — Compile the libbluetoothdrv.so RTP-timestamp proxy for the Y1.
#
# Produces an ARMv7 shared object that wraps the stock MediaTek Bluetooth
# driver and normalizes A2DP/SBC RTP timestamps so AirPods (Pro 2 / Pro 3)
# stop silently dropping the audio stream.
#
# The proxy is linked against the device's OWN crt objects + libraries (pulled
# via adb) so the ABI matches the Y1's Android 4.2.2 / bionic exactly, rather
# than against a newer NDK sysroot. This is why the Y1 must be connected the
# first time you build (to fetch the link inputs into ./devlibs).
#
# Requirements (macOS / Homebrew):
#   brew install --cask android-ndk      # NDK r29+ (clang)
#   brew install lld                     # ld.lld ELF linker
#   brew install android-platform-tools  # adb
#
# Usage:
#   ./build.sh                 # recommended build (RTP fix on, bitpool clamp off)
#   CLAMP_BITPOOL=1 ./build.sh # also clamp SBC bitpool 53->35 (lower quality;
#                              #   only if audio still drops on the plain fix)
#   QUIET=1 ./build.sh         # disable verbose BTDUMP/BTCTRL logging
#
# Output: ./build/libbluetoothdrv.so
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src/libbluetoothdrv_proxy.c"
OUT_DIR="$HERE/build"
DEVLIBS="$HERE/devlibs"
OUT="$OUT_DIR/libbluetoothdrv.so"

# --- build-time feature flags -------------------------------------------------
RTP_FIX=1
CLAMP="${CLAMP_BITPOOL:-0}"          # upstream ships this OFF for better audio
VERBOSE=1
[ "${QUIET:-0}" = "1" ] && VERBOSE=0

# --- locate the NDK clang -----------------------------------------------------
NDK_CLANG="$(find /opt/homebrew/Caskroom/android-ndk /usr/local/Caskroom/android-ndk \
  -path '*darwin-x86_64/bin/clang' 2>/dev/null | sort | tail -1 || true)"
if [ -z "$NDK_CLANG" ]; then
  echo "ERROR: NDK clang not found. Install with: brew install --cask android-ndk" >&2
  exit 1
fi
NDK_SYSROOT="$(dirname "$(dirname "$NDK_CLANG")")/sysroot"

LLD="$(command -v ld.lld || echo /opt/homebrew/opt/lld/bin/ld.lld)"
if [ ! -x "$LLD" ]; then
  echo "ERROR: ld.lld not found. Install with: brew install lld" >&2
  exit 1
fi

# --- fetch the device's own link inputs (once) --------------------------------
if [ ! -f "$DEVLIBS/crtbegin_so.o" ]; then
  echo ">> Pulling link inputs from the connected Y1 (needed once)..."
  adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }
  mkdir -p "$DEVLIBS"
  for f in crtbegin_so.o crtend_so.o; do
    adb pull "/system/lib/$f" "$DEVLIBS/$f" >/dev/null
  done
  for f in libc.so libdl.so liblog.so libm.so libstdc++.so libhardware_legacy.so; do
    adb pull "/system/lib/$f" "$DEVLIBS/$f" >/dev/null
  done
  echo ">> Cached device libs in $DEVLIBS"
fi

mkdir -p "$OUT_DIR"

echo ">> Compiling (RTP_FIX=$RTP_FIX CLAMP=$CLAMP VERBOSE=$VERBOSE)"
"$NDK_CLANG" --target=armv7a-linux-androideabi21 \
  -isystem "$NDK_SYSROOT/usr/include" \
  -isystem "$NDK_SYSROOT/usr/include/arm-linux-androideabi" \
  -fPIC -O2 \
  -DENABLE_RTP_TIMESTAMP_FIX="$RTP_FIX" \
  -DENABLE_BT_SETCONFIG_REWRITE="$CLAMP" \
  -DENABLE_VERBOSE_BT_MEDIA_LOG="$VERBOSE" \
  -c "$SRC" -o "$OUT_DIR/proxy.o"

echo ">> Linking against device ABI"
"$LLD" -shared -soname libbluetoothdrv.so --hash-style=sysv \
  -dynamic-linker /system/bin/linker \
  -o "$OUT" \
  "$DEVLIBS/crtbegin_so.o" "$OUT_DIR/proxy.o" "$DEVLIBS/crtend_so.o" \
  -L"$DEVLIBS" -llog -ldl -lc -lm

echo ">> Built: $OUT"
file "$OUT"
