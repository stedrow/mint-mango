#!/usr/bin/env bash
#
# y2_probe_fail_site.sh — DIAGNOSTIC. Identifies which internal mtkbt site
# rejects a raw L2CAP session connect.
#
# Background (Y2_INVESTIGATION.md): with y2_psm_fix.sh applied the AirPods accept
# the L2CAP channel and the config exchange completes, yet the session layer
# hands the client `msg->result:02`. That 2 is a status byte copied straight from
# an internal "session event 5" into the confirm the client receives. Four call
# sites raise event 5 with status 2, and exactly one raises it with status 1
# (success), so the status byte is a courier -- whichever site fires decides the
# outcome, and logs tell us nothing about which.
#
# So make the couriers distinguishable: give each failing site its own status
# value. The value lands in the client's log line unchanged, meaning one connect
# attempt now names the culprit:
#
#   result:03  -> 0x46000  (in the function at Ghidra 0x55fdc)
#   result:04  -> 0x4650c  (Ghidra 0x562ec -- the RFCOMM-flavoured connect path)
#   result:05  -> 0x45b08  (Ghidra 0x5595c)
#   result:06  -> 0x47d4c  (Ghidra 0x57b38)
#   result:07  -> 0x462ca  (a tail-call raiser in the same region as 0x4650c)
# Run with --offset for the follow-up probe described next to OFFSET_PATCHES.
#
#   result:08  -> 0x6be5a  (btadp_jsr82_connect_req's own rejection tail, which
#                           answers the client *without* attempting a channel --
#                           reached on "identify conflicts with existing context"
#                           or "no available session context")
#
# Each patch is one Thumb immediate (`movs r2,#2` -> `movs r2,#N`), so behaviour
# is unchanged apart from the reported number: every one of these paths already
# means "failed", and nothing compares the byte against 2 (the success path tests
# for 1). Purely an observation aid -- revert once the site is known.
#
# Run y2_psm_fix.sh first, so the failure being diagnosed is the real one (a
# channel the peer accepted) rather than a refused-PSM connect.
#
# Usage:
#   ./y2_probe_fail_site.sh          # patch and reboot
#   ./y2_probe_fail_site.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.probe"

# --offset mode: instead of tagging raisers, repoint the confirm's copy at the
# event's +0x20 (which FUN_000550a0's case 5 explicitly zeroes) rather than +0x22.
# result:00 means the event really does come from that raiser and the sender's and
# receiver's field offsets disagree; an unchanged result:02 means a different
# producer built the event.
OFFSET_PATCHES="0x6cf8e:94f82230:94f82030"

PATCHES="0x46000:0222:0322 0x4650c:0222:0422 0x45b08:0222:0522 0x47d4c:0222:0622 0x462ca:0222:0722 0x6be5a:0221:0821"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y2." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only (offsets are specific to this mtkbt build)." >&2; exit 1; }

stop_bluetooth() {
  adb shell 'service call bluetooth_manager 8' >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    adb shell 'ps' | grep -q '/system/bin/mtkbt' || break
    sleep 2
  done
}

if [ "${1:-}" = "--offset" ]; then
  PATCHES="$OFFSET_PATCHES"
  echo ">> Mode: confirm reads event+0x20 instead of +0x22"
fi

if [ "${1:-}" = "--revert" ]; then
  adb shell "[ -f $BACKUP ]" || { echo "ERROR: no backup at $BACKUP." >&2; exit 1; }
  stop_bluetooth
  echo ">> Restoring the pre-probe mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
echo ">> Pulling live mtkbt"
adb pull "$TARGET" "$BUILD/mtkbt_probe_stock" >/dev/null
python3 - "$BUILD/mtkbt_probe_stock" "$BUILD/mtkbt_probe_patched" $PATCHES <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
data = bytearray(open(src, 'rb').read())
if data[0x6c4e0:0x6c4e2].hex() != 'e18c':
    print("   WARNING: mtkbt lacks the PSM fix; you will be diagnosing a "
          "refused-PSM connect, not the real failure.")
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
adb push "$BUILD/mtkbt_probe_patched" /data/local/tmp/mtkbt_probe >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_probe > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_probe $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_probe_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_probe_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting"
adb reboot
