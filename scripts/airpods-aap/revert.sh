#!/usr/bin/env bash
#
# revert.sh — Restore the stock libextjsr82.so, undoing install.sh.
#
# Copies libextjsr82_real.so back over libextjsr82.so and removes the
# backup, returning /system to its factory state.
set -euo pipefail

TARGET="/system/lib/libextjsr82.so"
BACKUP="/system/lib/libextjsr82_real.so"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root." >&2; exit 1; }

if [ "$(adb shell "[ -f $BACKUP ] && echo yes" | tr -d '\r')" != "yes" ]; then
  echo "Nothing to revert: no $BACKUP on device (patch was never installed)."
  exit 0
fi

echo ">> Remounting /system read-write"
adb shell 'mount -o remount,rw /system'

echo ">> Restoring stock lib"
adb shell "cat $BACKUP > $TARGET && chown root.root $TARGET && chmod 644 $TARGET && rm -f $BACKUP && sync"

echo ">> Rebooting"
adb reboot
adb wait-for-device
echo ">> Stock libextjsr82.so restored."
