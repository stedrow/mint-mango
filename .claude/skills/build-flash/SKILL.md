---
name: build-flash
description: Build the launcher APK from source and flash it to a connected Y1 or Y2 device. Use when the user asks to "build the app", "flash it", "install my changes on the device", "push a new build", or similar — anything that means compiling Java/layout changes and getting them running on real hardware (not theme-only edits, see the theme-editor skill for those).
---

# Build and flash

Full reference: `README.md` under "Building from Source". This skill is the
condensed, repeatable version of those same steps.

## Prerequisites

- Android Studio installed (provides the JDK; no separate JDK install needed)
- ADB on PATH (`brew install android-platform-tools` if missing)
- Device connected via USB — confirm with `adb devices -l` before doing
  anything else. If nothing lists, stop and tell the user; don't proceed
  assuming a device will appear.

## Build

`gradlew` needs `JAVA_HOME` pointed at Android Studio's bundled JDK — this
environment has no standalone `java` on PATH otherwise:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
bash gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

A clean incremental build is a few seconds if nothing changed; expect longer
on the first run or after touching Gradle files.

## Flash

**This is a privileged system app** (`applicationId com.themoon.y1`, installed at
`/system/priv-app/com.themoon.y1.apk`) — a plain `adb install` does not work here.
It lives in `priv-app`, not `app`: since Android 4.3 only privileged apps are granted
`signature|system` permissions (REBOOT, SHUTDOWN, and the WRITE_MEDIA_STORAGE that
carries gid `input` for the power-key menu). If a unit still has the APK at
`/system/app/com.themoon.y1.apk`, delete that copy — otherwise both are scanned.
It requires remounting `/system` read-write and overwriting the system APK
directly, then rebooting:

```bash
adb shell mount -o remount,rw /system
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/com.themoon.y1.apk
adb shell chmod 644 /system/priv-app/com.themoon.y1.apk
adb reboot
```

ADB shell is already root on this device/firmware — no `su` needed.

One-liner for repeat builds:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && \
bash gradlew assembleDebug && \
adb shell mount -o remount,rw /system && \
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/com.themoon.y1.apk && \
adb shell chmod 644 /system/priv-app/com.themoon.y1.apk && \
adb reboot
```

### One-time per device: power-menu system patches

The long-press-power menu needs three device-side changes the APK can't make itself
(priv-app install, gid `input` via platform.xml, and the stock global-actions dialog
stripped out of `android.policy.jar`). Run once per unit, after a normal flash:

```bash
bash scripts/patch-device-power-menu.sh
```

It backs up every file it replaces to `scripts/backups/` and `/data/local/tmp`. Without
it the launcher still runs — long-pressing power just gets the stock Android menu.

### Y2 units: disable the stock launcher

On a Y2, the stock launcher (`com.innioasis.y2`, at `/system/priv-app/MyLauncher.apk`)
is a completely separate package from mint-mango's install target above — flashing
mint-mango never touches it. It keeps running in the background and draws its own
persistent status-bar overlay (`SYSTEM_ALERT_WINDOW`, battery/Bluetooth/clock) on top
of mint-mango's UI. Disable it once per device (reversible, doesn't delete the file):

```bash
adb shell pm disable-user --user 0 com.innioasis.y2
```

Not needed on Y1 (no such package exists there). If mint-mango's own status row ever
looks doubled-up or has stray icons overlapping it on a Y2 unit, check
`adb shell dumpsys window windows | grep -i innioasis` first — that package re-running
is the likely cause, e.g. after a full stock-firmware reflash re-enables it.

**Remounting `/system` rw and overwriting the system APK is invasive and
hard to reverse** (bad push = broken launcher until re-flashed). Confirm
with the user before running the flash steps rather than doing it as a
routine, unprompted action — building (`assembleDebug`) alone is safe and
doesn't need confirmation.

## After reboot

`adb reboot` drops the ADB connection; wait for it to come back before doing
anything else:
```bash
adb wait-for-device && sleep 5   # give the launcher time to actually start
```

To verify without waiting for the user to look at the device themselves,
screenshot it (screen may be asleep after boot — wake it first):
```bash
adb shell dumpsys power | grep mWakefulness   # check Asleep/Awake
adb shell input keyevent KEYCODE_WAKEUP        # only if Asleep
adb shell screencap -p /sdcard/check.png && adb pull /sdcard/check.png ./check.png && adb shell rm /sdcard/check.png
```
Delete the local screenshot after reviewing it — don't leave it in the repo
tree.

## Useful ADB commands

```bash
adb devices                        # confirm device connected
adb logcat | grep com.themoon.y1   # live logs from the launcher
adb shell pm path com.themoon.y1   # confirm install location
adb shell dumpsys package com.themoon.y1 | grep -i versionName  # confirm which build is running
```

## Scope note

Only rebuild/reflash for actual code changes (Java, layouts, manifest,
resources). Theme-only edits (colors, icons, layout JSON under
`/storage/sdcard0/Y1_Themes/`) don't need a rebuild — see the
`theme-editor` skill, which is a much faster loop (push files + restart the
app, no compile step).
