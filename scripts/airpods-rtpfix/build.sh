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
#   ./build.sh                 # recommended build (RTP fix on, bitpool clamp off,
#                              #   link-supervision timeout shortened to 4s)
#   CLAMP_BITPOOL=1 ./build.sh # also clamp SBC bitpool 53->35 (lower quality;
#                              #   only if audio still drops on the plain fix)
#   QUIET=1 ./build.sh         # disable verbose BTDUMP/BTCTRL logging
#   LSTO_SLOTS=8000 ./build.sh # tune the supervision timeout (0.625ms slots; 8000=5s)
#   LINK_SUPERVISION_TIMEOUT=0 ./build.sh  # leave the stock ~20s timeout untouched
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
LSTO="${LINK_SUPERVISION_TIMEOUT:-1}"  # shorten the ACL supervision timeout for fast drop recovery
LSTO_SLOTS="${LSTO_SLOTS:-6400}"       # 0.625ms slots; 6400 = 4s (default is 0x7D00 = 20s)

# --- locate the NDK clang (macOS Homebrew cask or Linux $ANDROID_NDK_HOME) ----
NDK_CLANG="$(find /opt/homebrew/Caskroom/android-ndk /usr/local/Caskroom/android-ndk \
  "${ANDROID_NDK_HOME:-/nonexistent}" \
  -path '*/bin/clang' 2>/dev/null | sort | tail -1 || true)"
if [ -z "$NDK_CLANG" ]; then
  echo "ERROR: NDK clang not found. Install with: brew install --cask android-ndk (or set ANDROID_NDK_HOME)" >&2
  exit 1
fi
NDK_SYSROOT="$(dirname "$(dirname "$NDK_CLANG")")/sysroot"

LLD="$(command -v ld.lld || echo /opt/homebrew/opt/lld/bin/ld.lld)"
if [ ! -x "$LLD" ]; then
  # Fall back to the linker bundled with the same NDK toolchain as clang.
  LLD="$(dirname "$NDK_CLANG")/ld.lld"
fi
if [ ! -x "$LLD" ]; then
  echo "ERROR: ld.lld not found. Install with: brew install lld (or use the NDK's bundled lld)" >&2
  exit 1
fi

# --- link inputs: pull from a connected device, or copy from a local source ---
# DEVLIBS_SRC lets CI point at a mounted base-firmware image's /system/lib
# instead of requiring a physical Y1 over adb (device pull is the local/dev path).
if [ ! -f "$DEVLIBS/crtbegin_so.o" ]; then
  mkdir -p "$DEVLIBS"
  LIBS="crtbegin_so.o crtend_so.o libc.so libdl.so liblog.so libm.so libstdc++.so libhardware_legacy.so"
  if [ -n "${DEVLIBS_SRC:-}" ]; then
    echo ">> Copying link inputs from $DEVLIBS_SRC"
    for f in $LIBS; do cp "$DEVLIBS_SRC/$f" "$DEVLIBS/$f"; done
  else
    echo ">> Pulling link inputs from the connected Y1 (needed once)..."
    adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1 (or set DEVLIBS_SRC)." >&2; exit 1; }
    for f in $LIBS; do adb pull "/system/lib/$f" "$DEVLIBS/$f" >/dev/null; done
  fi
  echo ">> Cached device libs in $DEVLIBS"
fi

mkdir -p "$OUT_DIR"

echo ">> Compiling (RTP_FIX=$RTP_FIX CLAMP=$CLAMP VERBOSE=$VERBOSE LSTO=$LSTO LSTO_SLOTS=$LSTO_SLOTS)"
"$NDK_CLANG" --target=armv7a-linux-androideabi21 \
  -isystem "$NDK_SYSROOT/usr/include" \
  -isystem "$NDK_SYSROOT/usr/include/arm-linux-androideabi" \
  -fPIC -O2 \
  -DENABLE_RTP_TIMESTAMP_FIX="$RTP_FIX" \
  -DENABLE_BT_SETCONFIG_REWRITE="$CLAMP" \
  -DENABLE_VERBOSE_BT_MEDIA_LOG="$VERBOSE" \
  -DENABLE_LINK_SUPERVISION_TIMEOUT="$LSTO" \
  -DLINK_SUPERVISION_TIMEOUT_SLOTS="$LSTO_SLOTS" \
  -c "$SRC" -o "$OUT_DIR/proxy.o"

echo ">> Linking against device ABI"
"$LLD" -shared -soname libbluetoothdrv.so --hash-style=sysv \
  -dynamic-linker /system/bin/linker \
  -o "$OUT" \
  "$DEVLIBS/crtbegin_so.o" "$OUT_DIR/proxy.o" "$DEVLIBS/crtend_so.o" \
  -L"$DEVLIBS" -llog -ldl -lc -lm

echo ">> Built: $OUT"
file "$OUT"
