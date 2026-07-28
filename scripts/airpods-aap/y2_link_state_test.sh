#!/usr/bin/env bash
#
# y2_link_state_test.sh — EXPERIMENT. Tests whether the Y2's AAP connect fails
# only because mtkbt thinks the ACL link isn't up yet.
#
# Traces (y2_trace_to_logcat.sh) show a session connect reaching ME_CreateLink,
# finding the device, logging kal id=0x15a, and then taking this branch:
#
#   9bc90: ldrb.w r0, [r4, #0xfe]   ; link state
#   9bc94: cmp    r0, #3            ; connected?
#   9bc96: beq.w  0x9bdcc           ;   yes -> proceed
#   9bc9a: movs   r1, #1            ;   no  -> mark pending
#   9bc9c: strb.w r1, [r4, #0x11a]
#   9bca0: b      0x9bdc4           ;       -> return 2
#
# Return 2 means "pending, wait for the link". The JSR82 layer above treats it
# as failure and tears the session down in the same millisecond -- which is why
# no event-5 raiser was ever involved, and why the L2CAP channel still appears
# on the wire moments later and then leaks: the lower machinery keeps going
# after the session is already dead.
#
# The link *is* usable (A2DP is streaming over it, and with y2_psm_fix.sh the
# AirPods accept an AAP channel on it), so mtkbt's state byte is simply stale.
# This forces the comparison to always match, taking the "already connected"
# path:
#
#   cmp r0, #3   (2803)  ->  cmp r0, r0   (8042)
#
# RISK: ME_CreateLink is core Management Entity code used by every profile, not
# just JSR82, so this also affects genuine connection setup to devices that are
# really idle. It is a diagnostic, not a fix -- if it works, the proper change is
# to make the JSR82 caller treat 2 as "pending" instead of failure. Watch that
# normal Bluetooth connect/pair still behaves while this is installed.
#
# Usage:
#   ./y2_link_state_test.sh          # patch and reboot
#   ./y2_link_state_test.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.linkstate"
PATCHES="0x9bc94:0328:8042"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only." >&2; exit 1; }

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
  echo ">> Restoring the pre-test mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
adb pull "$TARGET" "$BUILD/mtkbt_ls_stock" >/dev/null
python3 - "$BUILD/mtkbt_ls_stock" "$BUILD/mtkbt_ls_patched" $PATCHES <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
for spec in sys.argv[3:]:
    off_s, stock, patch = spec.split(":")
    off = int(off_s, 16)
    sb, pb = bytes.fromhex(stock), bytes.fromhex(patch)
    found = bytes(d[off:off + len(sb)])
    if found == pb:
        print("   %#x already patched" % off); continue
    if found != sb:
        sys.exit("ERROR: unexpected bytes at %#x (%s, wanted %s)." % (off, found.hex(), stock))
    d[off:off + len(pb)] = pb
    print("   %#x %s -> %s  (cmp r0,#3 -> cmp r0,r0)" % (off, stock, patch))
open(dst, 'wb').write(bytes(d))
PY

adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_ls_patched" /data/local/tmp/mtkbt_ls >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_ls > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_ls $TARGET.busy && sync"
L="$(md5 -q "$BUILD/mtkbt_ls_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_ls_patched" | awk '{print $1}')"
D="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$L" = "$D" ] || { echo "ERROR: md5 mismatch ($L vs $D)" >&2; exit 1; }
echo "   md5 ok: $L"
echo ">> Rebooting"
adb reboot
