#!/usr/bin/env bash
# Builds a flashable rom_y2.zip for Mint Mango Launcher: injects the launcher APK
# into the Innioasis Y2 stock firmware and bakes in everything a fresh unit would
# otherwise need applied by hand --
#   * the AAP L2CAP fix in /system/bin/mtkbt (sub-second in-ear detection)
#   * the system patches platform.xml + android.policy.jar, the same ones
#     patch-device.sh applies over adb (power menu, FM radio)
# -- with no physical device involved.
#
# The AirPods RTP-timestamp proxy is deliberately absent: that one fixes a Y1
# Bluetooth firmware bug, and the README is explicit that applying it to a Y2
# changes nothing. The Y1's libextjsr82.so isn't in this firmware at all.
#
# usage: build-rom.sh <tag> <apk-path>
set -euo pipefail

TAG="${1:?usage: build-rom.sh <tag> <apk-path>}"
APK_PATH="${2:?usage: build-rom.sh <tag> <apk-path>}"

# The Y2 stock ROM: a raw ext4 system.img alongside bootloader/kernel bits, which
# get repacked untouched.
BASE_URL="https://github.com/y1-community/y1-stock-rom/releases/download/3.1.7/rom_y2.zip"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAP_DIR="$ROOT/scripts/airpods-aap"

WORKDIR="$(mktemp -d)"
BUILD_DIR="$WORKDIR/y2"
MNT="$BUILD_DIR/mnt"
DIST="$ROOT/dist/${TAG}"
mkdir -p "$BUILD_DIR" "$MNT" "$DIST"

cleanup() {
  mountpoint -q "$MNT" 2>/dev/null && sudo umount "$MNT" || true
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "==> Fetching the Y2 base image"
curl -fsSL "$BASE_URL" -o "$BUILD_DIR/base.zip"
unzip -q "$BUILD_DIR/base.zip" -d "$BUILD_DIR"

IMG="$(find "$BUILD_DIR" -name 'system.img' | head -1)"
[ -n "$IMG" ] || { echo "system.img not found in the Y2 base image" >&2; exit 1; }

echo "==> Mounting system.img"
# The stock image is a couple of blocks shorter than its own superblock says it is, and the
# kernel won't touch a filesystem that claims to run past the end of its device:
#   EXT4-fs (loop0): bad geometry: block count 209920 exceeds size of device (209918 blocks)
# Pad it back out to the declared size -- the missing tail is empty space the packer trimmed,
# and e2fsck refuses to even look at the image until the geometry agrees.
BLOCK_COUNT="$(dumpe2fs -h "$IMG" 2>/dev/null | awk -F: '/^Block count:/ {gsub(/ /,"",$2); print $2}')"
BLOCK_SIZE="$(dumpe2fs -h "$IMG" 2>/dev/null | awk -F: '/^Block size:/ {gsub(/ /,"",$2); print $2}')"
[ -n "$BLOCK_COUNT" ] && [ -n "$BLOCK_SIZE" ] || { echo "couldn't read the ext4 superblock from $IMG" >&2; exit 1; }
DECLARED_SIZE=$((BLOCK_COUNT * BLOCK_SIZE))
if [ "$(stat -c %s "$IMG")" -lt "$DECLARED_SIZE" ]; then
  echo "    padding system.img to its declared $DECLARED_SIZE bytes"
  truncate -s "$DECLARED_SIZE" "$IMG"
fi
sudo mount -t ext4 -o loop "$IMG" "$MNT"

# Everything below patches copies rather than the mounted files themselves, so the patch
# scripts never need sudo of their own. Kept outside BUILD_DIR, which is repacked wholesale --
# scratch files under there ship inside the ROM.
PATCH_DIR="$WORKDIR/system-patches"
mkdir -p "$PATCH_DIR"

echo "==> Injecting the launcher APK"
# priv-app, not app: since Android 4.3 only privileged apps are granted signature|system
# permissions -- REBOOT, SHUTDOWN, and the WRITE_MEDIA_STORAGE carrying gids "input" (power
# menu) and "media" (/dev/fm, the radio).
sudo mkdir -p "$MNT/priv-app"
sudo rm -f "$MNT/app/com.themoon.y1.apk"
sudo cp "$APK_PATH" "$MNT/priv-app/com.themoon.y1.apk"
sudo chmod 644 "$MNT/priv-app/com.themoon.y1.apk"
sudo chown root:root "$MNT/priv-app/com.themoon.y1.apk"

echo "==> Standing down the stock launcher"
# com.innioasis.y2 is a separate package that keeps running alongside ours and draws its own
# persistent status-bar overlay (battery/Bluetooth/clock) on top of the launcher's UI. On a live
# unit that's `pm disable-user`; in an image, renaming is the equivalent -- PackageManager only
# scans *.apk, and the stock file stays there to rename back.
if [ -f "$MNT/priv-app/MyLauncher.apk" ]; then
  sudo mv "$MNT/priv-app/MyLauncher.apk" "$MNT/priv-app/MyLauncher.apk.bak"
  [ -f "$MNT/priv-app/MyLauncher.odex" ] && \
    sudo mv "$MNT/priv-app/MyLauncher.odex" "$MNT/priv-app/MyLauncher.odex.bak"
fi

echo "==> Baking in the AAP L2CAP fix (mtkbt)"
# The three client-L2CAP defects behind sub-second in-ear detection; see
# scripts/airpods-aap/README.md. MTKBT_FILE patches a file instead of a live device.
# `sudo cat >` rather than `sudo cp` so the copy belongs to us, not root, and the
# patch script can rewrite it without sudo of its own.
rm -rf "$AAP_DIR/build"
sudo cat "$MNT/bin/mtkbt" > "$PATCH_DIR/mtkbt"
MTKBT_FILE="$PATCH_DIR/mtkbt" "$AAP_DIR/y2_aap_l2cap_fix.sh"
sudo cp "$PATCH_DIR/mtkbt" "$MNT/bin/mtkbt"
# Numeric: mtkbt is root:shell on the device, but "shell" (Android's AID_SHELL, 2000) is not a
# group on the build host. The image stores ids, not names, so 0:2000 is what lands either way.
sudo chown 0:2000 "$MNT/bin/mtkbt"
sudo chmod 755 "$MNT/bin/mtkbt"

echo "==> Turning adb back on in boot.img"
# Stock 3.1.7 ships no /sbin/adbd at all and default.prop locked down (ro.secure=1,
# ro.adb.secure=1, USB as mass_storage), so a flash of the untouched boot image leaves a device
# nothing can be pushed to -- and no way to run patch-device.sh or install the next build.
BOOT_IMG="$(find "$BUILD_DIR" -maxdepth 1 -name 'boot.img' | head -1)"
[ -n "$BOOT_IMG" ] || { echo "boot.img not found in the Y2 base image" >&2; exit 1; }
python3 "$ROOT/scripts/patch-boot-adb.py" "$BOOT_IMG" "$ROOT/scripts/y2-boot/adbd"

echo "==> Baking in the system patches"
cp "$MNT/etc/permissions/platform.xml" "$PATCH_DIR/platform.xml"
cp "$MNT/framework/android.policy.jar" "$PATCH_DIR/android.policy.jar"
"$ROOT/scripts/patch-system-files.sh" "$PATCH_DIR"
sudo cp "$PATCH_DIR/platform.xml" "$MNT/etc/permissions/platform.xml"
sudo cp "$PATCH_DIR/android.policy.jar" "$MNT/framework/android.policy.jar"
sudo chmod 644 "$MNT/etc/permissions/platform.xml" "$MNT/framework/android.policy.jar"
sudo chown root:root "$MNT/etc/permissions/platform.xml" "$MNT/framework/android.policy.jar"
# A stale .odex wins over the patched classes.dex, so it has to go; Dalvik dexopts the jar into
# /data/dalvik-cache on first boot instead.
[ -f "$MNT/framework/android.policy.odex" ] && \
  sudo mv "$MNT/framework/android.policy.odex" "$MNT/framework/android.policy.odex.bak"

sudo umount "$MNT"

echo "==> Repacking rom_y2.zip"
# base.zip is our download, not part of the ROM; mnt/ is the (now unmounted) mountpoint.
(cd "$BUILD_DIR" && zip -qr "$DIST/rom_y2.zip" . -x "base.zip" -x "mnt/*")

echo "==> Done. Output in $DIST"
ls -la "$DIST"
