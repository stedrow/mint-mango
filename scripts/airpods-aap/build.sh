#!/usr/bin/env bash
#
# build.sh — Pull the device's live libextjsr82.so and produce a patched copy
# that routes arbitrary-PSM L2CAP client connects (ps_type=2) instead of the
# broken RFCOMM-channel path (ps_type=1). See src/build_patch.py for the why.
#
# Requires: adb (root shell), python3, pip3 install keystone-engine lief
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"

# STOCK_LIB lets CI point at a stock libextjsr82.so extracted from a mounted
# base-firmware image instead of requiring a physical Y1 over adb.
if [ -n "${STOCK_LIB:-}" ]; then
  echo ">> Using stock lib from $STOCK_LIB"
  cp "$STOCK_LIB" "$HERE/build/libextjsr82_stock.so"
else
  adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1 (or set STOCK_LIB)." >&2; exit 1; }
  adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }

  echo ">> Pulling live /system/lib/libextjsr82.so"
  adb pull /system/lib/libextjsr82.so "$HERE/build/libextjsr82_stock.so" >/dev/null
fi

echo ">> Checking python deps"
python3 -c "import keystone, lief" 2>/dev/null || {
  echo "ERROR: missing deps. Run: pip3 install keystone-engine lief" >&2
  exit 1
}

echo ">> Building patched lib"
python3 "$HERE/src/build_patch.py" "$HERE/build/libextjsr82_stock.so" "$HERE/build/libextjsr82_patched.so"

echo ">> Done: build/libextjsr82_patched.so"
echo "   Install with ./install.sh"
