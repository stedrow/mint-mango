#!/usr/bin/env bash
#
# status.sh — Report whether the ps_type patch is installed and active, and
# tail logcat so you can watch the AapSpikeService connect attempt.
set -euo pipefail

TARGET="/system/lib/libextjsr82.so"
BACKUP="/system/lib/libextjsr82_real.so"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }

echo "== Files on device =="
adb shell "ls -l $TARGET $BACKUP 2>/dev/null"

echo
echo "== md5 of active lib =="
adb shell "md5 $TARGET"

echo
echo "== Live log (trigger the AAP L2CAP connect; Ctrl-C to stop) =="
adb logcat -c
adb logcat -v time -s AapSpikeService JSR82 BluetoothSocket
