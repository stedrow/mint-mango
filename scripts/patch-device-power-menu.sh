#!/usr/bin/env bash
# Prepares a connected Y1/Y2 for the launcher's own long-press-power menu (PowerMenuInterceptor).
#
# Three device-side changes, none of which the APK can make for itself:
#   1. Move the launcher to /system/priv-app. Since Android 4.3 a signature|system permission is
#      only granted to privileged apps, and /system/app is not privileged -- that is why REBOOT,
#      SHUTDOWN and WRITE_MEDIA_STORAGE were silently ungranted before.
#   2/3. platform.xml and android.policy.jar -- see patch-power-menu-files.sh, which does both
#      edits and is shared with the ROM build. Plus moving android.policy.odex aside, since a
#      stale .odex wins over the patched classes.dex.
#
# Every replaced file is backed up next to the script under backups/ and in /data/local/tmp on
# the device. To undo: push the .orig files back, restore android.policy.odex.bak, reboot.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
BACKUPS="$ROOT/scripts/backups"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

[ -f "$APK" ] || { echo "APK not found: $APK (build it first, or pass a path)" >&2; exit 1; }
adb devices | grep -q "device$" || { echo "No device in 'adb devices'" >&2; exit 1; }

mkdir -p "$BACKUPS"
adb shell mount -o remount,rw /system

echo "==> [1/3] Moving the launcher into /system/priv-app"
adb push "$APK" /system/priv-app/com.themoon.y1.apk
adb shell chmod 644 /system/priv-app/com.themoon.y1.apk
adb shell "rm -f /system/app/com.themoon.y1.apk"

echo "==> [2/3] Patching platform.xml and android.policy.jar"
adb pull /system/etc/permissions/platform.xml "$WORKDIR/platform.xml" >/dev/null
adb pull /system/framework/android.policy.jar "$WORKDIR/android.policy.jar" >/dev/null
# Never overwrite an existing backup: a re-run against an already-patched device would pull the
# patched files and save them as the "originals", losing the only copy of the stock ones.
for f in platform.xml android.policy.jar; do
  if [ -f "$BACKUPS/$f.orig" ]; then
    echo "    keeping existing backup of $f"
  else
    cp "$WORKDIR/$f" "$BACKUPS/$f.orig"
    adb push "$BACKUPS/$f.orig" /data/local/tmp/ >/dev/null
  fi
done

"$ROOT/scripts/patch-power-menu-files.sh" "$WORKDIR"

adb push "$WORKDIR/platform.xml" /system/etc/permissions/platform.xml
adb push "$WORKDIR/android.policy.jar" /system/framework/android.policy.jar
adb shell chmod 644 /system/etc/permissions/platform.xml /system/framework/android.policy.jar

echo "==> [3/3] Moving android.policy.odex aside"
# Patched state is "the plain .odex is gone" (it gets renamed to .bak here). Don't probe for the
# .bak by name: `ls` on a missing file echoes the name back in its error message, so any grep for
# it matches whether or not the file is there.
if adb shell ls /system/framework 2>/dev/null | tr -d '\r' | grep -qx "android.policy.odex"; then
  adb pull /system/framework/android.policy.odex "$BACKUPS/android.policy.odex.orig" >/dev/null
  adb push "$BACKUPS/android.policy.odex.orig" /data/local/tmp/ >/dev/null
  adb shell "mv /system/framework/android.policy.odex /system/framework/android.policy.odex.bak"
else
  echo "    already moved, skipping"
fi

adb shell sync
echo "==> Rebooting (first boot is slower -- android.policy.jar gets dexopted)"
adb reboot
adb wait-for-device
sleep 45
adb shell dumpsys package com.themoon.y1 | grep gids
echo "==> Done. gids above should include 1004 (input)."
