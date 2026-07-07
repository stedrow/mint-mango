#!/usr/bin/env bash
#
# status.sh — Report whether the RTP-fix proxy is installed and active, and
# tail its log so you can confirm timestamps are being rewritten while audio
# plays to the AirPods.
set -euo pipefail

TARGET="/system/lib/libbluetoothdrv.so"
BACKUP="/system/lib/libbluetoothdrv_real.so"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }

echo "== Files on device =="
adb shell "ls -l $TARGET $BACKUP 2>/dev/null"

echo
echo "== Is the proxy mapped by mtkbt? =="
adb shell 'PID=$(ps | grep mtkbt | grep -v grep | head -1 | awk "{print \$2}");
  if [ -n "$PID" ]; then
    echo "mtkbt pid=$PID";
    cat /proc/$PID/maps 2>/dev/null | grep -i bluetoothdrv | awk "{print \$6}" | sort -u;
  else
    echo "mtkbt not running";
  fi'

echo
echo "== Live log (play a track to the AirPods; Ctrl-C to stop) =="
echo "   BTRTPFIX lines = timestamps being rewritten (the fix working)."
echo "   BTLSTO lines   = link-supervision timeout shortened on connect (fast drop recovery)."
adb logcat -c
adb logcat -v time -s BTRTPFIX BTLSTO BTDUMP BTCTRL
