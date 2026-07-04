#!/usr/bin/env bash
#
# install.sh — Install the patched libextjsr82.so onto a connected Y1 over adb.
#
# LIVE swap of /system/lib/libextjsr82.so, same pattern as
# scripts/airpods-rtpfix/install.sh: the stock lib is preserved as
# libextjsr82_real.so, so this is fully reversible with ./revert.sh.
#
# Safe to re-run: the backup is only created once.
#
# Usage:  ./install.sh          (builds first if ./build/libextjsr82_patched.so missing)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PATCHED="$HERE/build/libextjsr82_patched.so"
TARGET="/system/lib/libextjsr82.so"
BACKUP="/system/lib/libextjsr82_real.so"

[ -f "$PATCHED" ] || { echo ">> No build found, running build.sh"; "$HERE/build.sh"; }

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y1." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }

echo ">> Remounting /system read-write"
adb shell 'mount -o remount,rw /system'

echo ">> Backing up stock lib -> libextjsr82_real.so (once)"
adb shell "[ -f $BACKUP ] || cp $TARGET $BACKUP"

echo ">> Pushing patched lib"
adb push "$PATCHED" /data/local/tmp/libextjsr82_patched.so >/dev/null
adb shell "cat /data/local/tmp/libextjsr82_patched.so > $TARGET && chown root.root $TARGET && chmod 644 $TARGET && rm -f /data/local/tmp/libextjsr82_patched.so"

echo ">> Verifying (local vs on-device md5)"
LOCAL_MD5="$(md5 -q "$PATCHED" 2>/dev/null || md5sum "$PATCHED" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
echo "   local:  $LOCAL_MD5"
echo "   device: $DEV_MD5"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push!" >&2; exit 1; }

adb shell sync
echo ">> Rebooting to reload the patched lib"
adb reboot
adb wait-for-device
echo ">> Done. Confirm the patch is active with: ./status.sh"
echo "   If anything looks wrong, revert immediately with: ./revert.sh"
