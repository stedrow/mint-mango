#!/usr/bin/env bash
#
# install.sh — Install the RTP-timestamp proxy onto a connected Y1 over adb.
#
# This does a LIVE swap of /system/lib/libbluetoothdrv.so. No SP Flash Tool and
# no system.img patching required, because this firmware ships a root adb shell.
# The stock driver is preserved as libbluetoothdrv_real.so (the proxy dlopen()s
# it at runtime), so the change is fully reversible with ./revert.sh.
#
# Safe to re-run: the backup is only created once, so repeated installs never
# clobber the genuine stock driver.
#
# Usage:  ./install.sh          (builds first if ./build/libbluetoothdrv.so missing)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROXY="$HERE/build/libbluetoothdrv.so"
TARGET="/system/lib/libbluetoothdrv.so"
BACKUP="/system/lib/libbluetoothdrv_real.so"

[ -f "$PROXY" ] || { echo ">> No build found, running build.sh"; "$HERE/build.sh"; }

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }
[ "$(adb shell 'id -u' | tr -d '\r')" = "0" ] || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }

echo ">> Remounting /system read-write"
adb shell 'mount -o remount,rw /system'

echo ">> Backing up stock driver -> libbluetoothdrv_real.so (once)"
adb shell "[ -f $BACKUP ] || cp $TARGET $BACKUP"

echo ">> Pushing proxy"
adb push "$PROXY" /data/local/tmp/libbluetoothdrv_proxy.so >/dev/null
adb shell "cat /data/local/tmp/libbluetoothdrv_proxy.so > $TARGET && chown root.root $TARGET && chmod 644 $TARGET && rm -f /data/local/tmp/libbluetoothdrv_proxy.so"

echo ">> Verifying (local vs on-device md5)"
LOCAL_MD5="$(md5 -q "$PROXY" 2>/dev/null || md5sum "$PROXY" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
echo "   local:  $LOCAL_MD5"
echo "   device: $DEV_MD5"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push!" >&2; exit 1; }

adb shell sync
echo ">> Rebooting to reload the Bluetooth driver"
adb reboot
adb wait-for-device
echo ">> Done. Connect your AirPods and play a track."
echo "   Confirm the fix is active with:  ./status.sh"
