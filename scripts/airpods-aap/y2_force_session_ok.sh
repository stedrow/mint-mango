#!/usr/bin/env bash
#
# y2_force_session_ok.sh — EXPERIMENT. Makes mtkbt report a JSR82 session as
# connected regardless of the internal status byte it computes.
#
# Use together with y2_psm_fix.sh, never alone. Background (Y2_INVESTIGATION.md):
# with the PSM fix applied the Y2 opens a real L2CAP channel to the AAP PSM and
# the AirPods accept it (connect response successful, config exchange completed
# both ways, verified on the wire), yet the session layer still hands the client
# result 2, so BluetoothSocket.connect() throws and the launcher gets no socket.
#
# The status byte that decides this is consumed in three places in the
# channel-connect result handler: once to choose the success path, once to fill
# the confirm sent to the client, and once for the channel value the framework
# reads back (connect() requires it to be > 0, and this path leaves it 0):
#
#   0x6cf42: f894 3022  ldrb.w r3,[r4,#0x22]  ->  0123 00bf  movs r3,#1 ; nop
#   0x6cf8e: f894 3022  ldrb.w r3,[r4,#0x22]  ->  0123 00bf  movs r3,#1 ; nop
#   0x6cf3e: 6169       ldr  r1,[r4,#0x14]    ->  0121       movs r1,#1
#
# An earlier run of this patch *without* a working PSM produced a socket over a
# channel the peer had refused, which then died with EBADF after ~30s. That was
# once read as the two patches being incompatible; they are not -- there simply
# was no PSM fix active at the time. Apply both.
#
# WHY THIS IS AN EXPERIMENT: it claims success for every JSR82 session connect,
# including RFCOMM ones that genuinely failed (MediaTek's own OPP/FTP/DUN use
# this path). Keep it installed only while testing. If AAP data flows over the
# channel, the proper fix is narrower -- find why the status byte is 2 when the
# channel is demonstrably up.
#
# Usage:
#   ./y2_force_session_ok.sh          # patch and reboot
#   ./y2_force_session_ok.sh --revert # restore stock mtkbt and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.session"

PATCHES="0x6cf42:94f82230:012300bf 0x6cf8e:94f82230:012300bf 0x6cf3e:6169:0121"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y2." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only (offsets are specific to this mtkbt build)." >&2; exit 1; }

# mtkbt runs whenever Bluetooth is on and a running binary can't be overwritten
# in place ("Text file busy"), so stop Bluetooth and swap via rename.
stop_bluetooth() {
  adb shell 'service call bluetooth_manager 8' >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    adb shell 'ps' | grep -q '/system/bin/mtkbt' || break
    sleep 2
  done
}

if [ "${1:-}" = "--revert" ]; then
  adb shell "[ -f $BACKUP ]" || { echo "ERROR: no backup at $BACKUP." >&2; exit 1; }
  stop_bluetooth
  echo ">> Restoring the pre-session-patch mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
echo ">> Pulling live mtkbt (expected to already carry the PSM fix)"
adb pull "$TARGET" "$BUILD/mtkbt_session_stock" >/dev/null
python3 - "$BUILD/mtkbt_session_stock" "$BUILD/mtkbt_session_patched" $PATCHES <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
data = bytearray(open(src, 'rb').read())
# The PSM fix must already be in place, else this produces a socket over a
# channel the peer refuses (see the header).
if data[0x6c4e0:0x6c4e2].hex() != 'e18c':
    sys.exit("ERROR: mtkbt lacks the PSM fix at 0x6c4e0. Run y2_psm_fix.sh first.")
for spec in sys.argv[3:]:
    off_s, stock, patch = spec.split(":")
    off = int(off_s, 16)
    stock_b, patch_b = bytes.fromhex(stock), bytes.fromhex(patch)
    found = bytes(data[off:off + len(stock_b)])
    if found == patch_b:
        print("   %#x already patched" % off)
        continue
    if found != stock_b:
        sys.exit("ERROR: unexpected bytes at %#x (%s, wanted %s)." % (off, found.hex(), stock))
    data[off:off + len(patch_b)] = patch_b
    print("   %#x %s -> %s" % (off, stock, patch))
open(dst, 'wb').write(bytes(data))
PY

echo ">> Installing"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_session_patched" /data/local/tmp/mtkbt_session >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_session > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_session $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_session_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_session_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting"
adb reboot
