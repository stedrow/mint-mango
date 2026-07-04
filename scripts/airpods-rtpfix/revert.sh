#!/usr/bin/env bash
#
# revert.sh — Restore the stock Bluetooth driver, undoing install.sh.
#
# Copies libbluetoothdrv_real.so back over libbluetoothdrv.so and removes the
# backup, returning /system to its factory state. Use this if Bluetooth audio
# misbehaves after installing the proxy.
set -euo pipefail

TARGET="/system/lib/libbluetoothdrv.so"
BACKUP="/system/lib/libbluetoothdrv_real.so"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }
[ "$(adb shell 'id -u' | tr -d '\r')" = "0" ] || { echo "ERROR: adb shell is not root." >&2; exit 1; }

if [ "$(adb shell "[ -f $BACKUP ] && echo yes" | tr -d '\r')" != "yes" ]; then
  echo "Nothing to revert: no $BACKUP on device (proxy was never installed)."
  exit 0
fi

echo ">> Remounting /system read-write"
adb shell 'mount -o remount,rw /system'

echo ">> Restoring stock driver"
adb shell "cat $BACKUP > $TARGET && chown root.root $TARGET && chmod 644 $TARGET && rm -f $BACKUP && sync"

echo ">> Rebooting"
adb reboot
adb wait-for-device
echo ">> Stock Bluetooth driver restored."
