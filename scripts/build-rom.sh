#!/usr/bin/env bash
# Builds flashable Y1 rom.zip images (type A + type B) for Mint Mango Launcher:
# injects the launcher APK into the rockbox-y1 base firmware, and bakes in the
# AirPods RTP fix (libbluetoothdrv.so) + AAP in-ear-detection patch
# (libextjsr82.so), both built against each base image's own stock libs so no
# physical device is needed.
set -euo pipefail

TAG="${1:?usage: build-rom.sh <tag> <apk-path>}"
APK_PATH="${2:?usage: build-rom.sh <tag> <apk-path>}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RTPFIX_DIR="$ROOT/scripts/airpods-rtpfix"
AAP_DIR="$ROOT/scripts/airpods-aap"

WORKDIR="$(mktemp -d)"
DIST="$ROOT/dist/${TAG}"
mkdir -p "$DIST"

cleanup() {
  for m in "$WORKDIR"/type-*/mnt; do
    mountpoint -q "$m" 2>/dev/null && sudo umount "$m" || true
  done
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

for TYPE in a b; do
  BASE_URL="https://github.com/rockbox-y1/rockbox/releases/download/type-${TYPE}-base/rom.zip"
  TYPE_DIR="$WORKDIR/type-$TYPE"
  MNT="$TYPE_DIR/mnt"
  mkdir -p "$TYPE_DIR" "$MNT"

  echo "==> [$TYPE] Fetching base image"
  curl -fsSL "$BASE_URL" -o "$TYPE_DIR/rom.zip"
  unzip -q "$TYPE_DIR/rom.zip" -d "$TYPE_DIR"

  IMG="$(find "$TYPE_DIR" -name 'system.img' | head -1)"
  [ -n "$IMG" ] || { echo "system.img not found in type-$TYPE base image" >&2; exit 1; }

  echo "==> [$TYPE] Mounting system.img"
  sudo mount -t ext4 -o loop "$IMG" "$MNT"

  echo "==> [$TYPE] Building AirPods RTP fix against this image's stock libs"
  rm -rf "$RTPFIX_DIR/devlibs" "$RTPFIX_DIR/build"
  DEVLIBS_SRC="$MNT/lib" "$RTPFIX_DIR/build.sh"

  echo "==> [$TYPE] Building AAP in-ear-detection patch against this image's stock lib"
  rm -rf "$AAP_DIR/build"
  STOCK_LIB="$MNT/lib/libextjsr82.so" "$AAP_DIR/build.sh"

  echo "==> [$TYPE] Injecting launcher APK + AirPods patches"
  sudo cp "$APK_PATH" "$MNT/app/com.themoon.y1.apk"
  sudo chmod 644 "$MNT/app/com.themoon.y1.apk"
  sudo chown root:root "$MNT/app/com.themoon.y1.apk"

  sudo cp "$MNT/lib/libbluetoothdrv.so" "$MNT/lib/libbluetoothdrv_real.so"
  sudo cp "$RTPFIX_DIR/build/libbluetoothdrv.so" "$MNT/lib/libbluetoothdrv.so"
  sudo chmod 644 "$MNT/lib/libbluetoothdrv.so" "$MNT/lib/libbluetoothdrv_real.so"
  sudo chown root:root "$MNT/lib/libbluetoothdrv.so" "$MNT/lib/libbluetoothdrv_real.so"

  sudo cp "$MNT/lib/libextjsr82.so" "$MNT/lib/libextjsr82_real.so"
  sudo cp "$AAP_DIR/build/libextjsr82_patched.so" "$MNT/lib/libextjsr82.so"
  sudo chmod 644 "$MNT/lib/libextjsr82.so" "$MNT/lib/libextjsr82_real.so"
  sudo chown root:root "$MNT/lib/libextjsr82.so" "$MNT/lib/libextjsr82_real.so"

  sudo umount "$MNT"

  echo "==> [$TYPE] Repacking rom.zip"
  OUT_NAME="rom.zip"
  [ "$TYPE" = "b" ] && OUT_NAME="rom_type_b.zip"
  (cd "$TYPE_DIR" && zip -qr "$DIST/$OUT_NAME" . -x "rom.zip" -x "mnt/*")
done

echo "==> Done. Output in $DIST"
ls -la "$DIST"
