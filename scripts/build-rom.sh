#!/usr/bin/env bash
# Builds a flashable rom_y2.zip for Mint Mango Launcher: injects the launcher APK
# into the Innioasis Y2 stock firmware and bakes in everything a fresh unit would
# otherwise need applied by hand --
#   * the AAP L2CAP fix in /system/bin/mtkbt (sub-second in-ear detection)
#   * the system patches platform.xml + android.policy.jar (power menu, FM radio),
#     the same ones patch-device.sh applies over adb
#   * adbd and its properties in boot.img, without which a flashed device has no
#     adb at all and nothing can be pushed to it
#   * a first-boot time zone, since stock ships none and lands on GMT
# -- with no physical device involved.
#
# The system image is edited with debugfs rather than a loop mount, so this needs
# no root, no sudo and no privileged container: it runs anywhere e2fsprogs does,
# macOS included. See scripts/patch-system-image.py, which also explains why that
# matters beyond convenience (the mount-and-cp approach dropped SELinux labels on
# files it created, and this firmware runs enforcing).
#
# The AirPods RTP-timestamp proxy is deliberately absent: that one fixes a Y1
# Bluetooth firmware bug, and the README is explicit that applying it to a Y2
# changes nothing. The Y1's libextjsr82.so isn't in this firmware at all.
#
# usage: build-rom.sh <tag> <apk-path>
#   ROM_TIMEZONE=Europe/London build-rom.sh ...   # first-boot zone, default America/New_York
set -euo pipefail

TAG="${1:?usage: build-rom.sh <tag> <apk-path>}"
APK_PATH="${2:?usage: build-rom.sh <tag> <apk-path>}"

# The Y2 stock ROM: a raw ext4 system.img alongside bootloader/kernel bits, which
# get repacked untouched apart from boot.img.
BASE_URL="https://github.com/y1-community/y1-stock-rom/releases/download/3.1.7/rom_y2.zip"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WORKDIR="$(mktemp -d)"
BUILD_DIR="$WORKDIR/y2"
DIST="$ROOT/dist/${TAG}"
mkdir -p "$BUILD_DIR" "$DIST"
trap 'rm -rf "$WORKDIR"' EXIT

[ -f "$APK_PATH" ] || { echo "APK not found: $APK_PATH" >&2; exit 1; }

echo "==> Fetching the Y2 base image"
curl -fsSL "$BASE_URL" -o "$BUILD_DIR/base.zip"
unzip -q "$BUILD_DIR/base.zip" -d "$BUILD_DIR"

IMG="$(find "$BUILD_DIR" -maxdepth 1 -name 'system.img' | head -1)"
[ -n "$IMG" ] || { echo "system.img not found in the Y2 base image" >&2; exit 1; }

echo "==> Patching system.img"
python3 "$ROOT/scripts/patch-system-image.py" \
    --image "$IMG" --apk "$APK_PATH" --scripts "$ROOT/scripts" \
    --timezone "${ROM_TIMEZONE:-America/New_York}"

echo "==> Turning adb back on in boot.img"
# Stock 3.1.7 ships no /sbin/adbd at all and default.prop locked down (ro.secure=1,
# ro.adb.secure=1, USB as mass_storage), so a flash of the untouched boot image leaves a device
# nothing can be pushed to -- and no way to run patch-device.sh or install the next build.
BOOT_IMG="$(find "$BUILD_DIR" -maxdepth 1 -name 'boot.img' | head -1)"
[ -n "$BOOT_IMG" ] || { echo "boot.img not found in the Y2 base image" >&2; exit 1; }
python3 "$ROOT/scripts/patch-boot-adb.py" "$BOOT_IMG" "$ROOT/scripts/y2-boot/adbd"

echo "==> Repacking rom_y2.zip"
# base.zip is our download, not part of the ROM.
(cd "$BUILD_DIR" && zip -qr "$DIST/rom_y2.zip" . -x "base.zip")

echo "==> Done. Output in $DIST"
ls -la "$DIST"
