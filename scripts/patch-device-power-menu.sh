#!/usr/bin/env bash
# Prepares a connected Y1/Y2 for the launcher's own long-press-power menu (PowerMenuInterceptor).
#
# Three device-side changes, none of which the APK can make for itself:
#   1. Move the launcher to /system/priv-app. Since Android 4.3 a signature|system permission is
#      only granted to privileged apps, and /system/app is not privileged -- that is why REBOOT,
#      SHUTDOWN and WRITE_MEDIA_STORAGE were silently ungranted before.
#   2. platform.xml: add gid "input" to WRITE_MEDIA_STORAGE, so the launcher's uid can read
#      /dev/input/event0 and time the power key itself (KEYCODE_POWER never reaches an app).
#      That permission is signature|system, so only priv-app holders pick the gid up.
#   3. android.policy.jar: drop the showGlobalActionsDialog() call out of PhoneWindowManager's
#      long-press handler, so the stock Power off / Restart dialog never appears. Only the call
#      goes -- mPowerKeyHandled is still set just above it, and without that flag releasing the
#      key reads as a short press and sleeps the device. (Patching
#      config_longPressOnPowerBehavior to 0 in framework-res kills the dialog too, but it skips
#      the whole branch including that flag, so the screen sleeps the moment you let go.)
#
# Every replaced file is backed up next to the script under backups/ and in /data/local/tmp on
# the device. To undo: push the .orig files back, restore android.policy.odex.bak, reboot.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
BACKUPS="$ROOT/scripts/backups"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

JAVA="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin/java"
SMALI_VERSION="2.5.2"
MAVEN="https://repo1.maven.org/maven2"

[ -f "$APK" ] || { echo "APK not found: $APK (build it first, or pass a path)" >&2; exit 1; }
adb devices | grep -q "device$" || { echo "No device in 'adb devices'" >&2; exit 1; }
[ -x "$JAVA" ] || { echo "java not found at $JAVA -- set JAVA_HOME" >&2; exit 1; }

mkdir -p "$BACKUPS"
adb shell mount -o remount,rw /system

echo "==> [1/3] Moving the launcher into /system/priv-app"
adb push "$APK" /system/priv-app/com.themoon.y1.apk
adb shell chmod 644 /system/priv-app/com.themoon.y1.apk
adb shell "rm -f /system/app/com.themoon.y1.apk"

echo "==> [2/3] Granting gid input via platform.xml"
adb pull /system/etc/permissions/platform.xml "$WORKDIR/platform.xml" >/dev/null
cp "$WORKDIR/platform.xml" "$BACKUPS/platform.xml.orig"
if grep -q 'mint-mango' "$WORKDIR/platform.xml"; then
  echo "    already patched, skipping"
else
  python3 - "$WORKDIR/platform.xml" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
anchor = '''    <permission name="android.permission.WRITE_MEDIA_STORAGE" >
        <group gid="media_rw" />'''
assert src.count(anchor) == 1, "WRITE_MEDIA_STORAGE block not found as expected"
open(path, 'w').write(src.replace(anchor, anchor + '''
        <!-- mint-mango: lets the launcher read /dev/input and time the power key itself.
             WRITE_MEDIA_STORAGE is signature|system, so only priv-app holders get this. -->
        <group gid="input" />'''))
PY
  adb push "$WORKDIR/platform.xml" /system/etc/permissions/platform.xml
  adb shell chmod 644 /system/etc/permissions/platform.xml
fi

echo "==> [3/3] Removing the stock power dialog from android.policy.jar"
if adb shell "ls /system/framework/android.policy.odex.bak" 2>/dev/null | grep -q odex.bak; then
  echo "    already patched, skipping"
else
  for dep in "org/smali/baksmali/$SMALI_VERSION/baksmali-$SMALI_VERSION.jar" \
             "org/smali/smali/$SMALI_VERSION/smali-$SMALI_VERSION.jar" \
             "org/smali/dexlib2/$SMALI_VERSION/dexlib2-$SMALI_VERSION.jar" \
             "org/smali/util/$SMALI_VERSION/util-$SMALI_VERSION.jar" \
             "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar" \
             "com/beust/jcommander/1.78/jcommander-1.78.jar" \
             "commons-cli/commons-cli/1.5.0/commons-cli-1.5.0.jar" \
             "org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar" \
             "org/antlr/ST4/4.3.1/ST4-4.3.1.jar"; do
    curl -fsSL -o "$WORKDIR/$(basename "$dep")" "$MAVEN/$dep"
  done

  adb pull /system/framework/android.policy.jar "$WORKDIR/android.policy.jar" >/dev/null
  adb pull /system/framework/android.policy.odex "$WORKDIR/android.policy.odex" >/dev/null
  cp "$WORKDIR/android.policy.jar" "$BACKUPS/android.policy.jar.orig"
  cp "$WORKDIR/android.policy.odex" "$BACKUPS/android.policy.odex.orig"
  adb push "$BACKUPS/android.policy.jar.orig" /data/local/tmp/ >/dev/null
  adb push "$BACKUPS/android.policy.odex.orig" /data/local/tmp/ >/dev/null

  mkdir -p "$WORKDIR/jar"
  (cd "$WORKDIR/jar" && unzip -q ../android.policy.jar)
  "$JAVA" -cp "$WORKDIR/*" org.jf.baksmali.Main d "$WORKDIR/jar/classes.dex" -o "$WORKDIR/smali"

  python3 - "$WORKDIR/smali/com/android/internal/policy/impl/PhoneWindowManager\$2.smali" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
call = '''    iget-object v1, p0, Lcom/android/internal/policy/impl/PhoneWindowManager$2;->this$0:Lcom/android/internal/policy/impl/PhoneWindowManager;

    invoke-virtual {v1}, Lcom/android/internal/policy/impl/PhoneWindowManager;->showGlobalActionsDialog()V

'''
assert src.count(call) == 1, "showGlobalActionsDialog() call site not found as expected"
open(path, 'w').write(src.replace(call, '''    # mint-mango: showGlobalActionsDialog() call removed -- the launcher draws its own power
    # menu. mPowerKeyHandled is still set above, so releasing power isn't read as a short press.

'''))
PY

  "$JAVA" -cp "$WORKDIR/*" org.jf.smali.Main a "$WORKDIR/smali" -o "$WORKDIR/jar/classes.dex" --api 19
  rm -f "$WORKDIR/android.policy.patched.jar"
  (cd "$WORKDIR/jar" && zip -q -r -X ../android.policy.patched.jar META-INF classes.dex)

  adb push "$WORKDIR/android.policy.patched.jar" /system/framework/android.policy.jar
  adb shell chmod 644 /system/framework/android.policy.jar
  # The stale .odex wins over classes.dex if it's left in place; moving it aside makes Dalvik
  # dexopt the patched jar into /data/dalvik-cache on the next boot.
  adb shell "mv /system/framework/android.policy.odex /system/framework/android.policy.odex.bak"
fi

adb shell sync
echo "==> Rebooting (first boot is slower -- android.policy.jar gets dexopted)"
adb reboot
adb wait-for-device
sleep 45
adb shell dumpsys package com.themoon.y1 | grep gids
echo "==> Done. gids above should include 1004 (input)."
