#!/usr/bin/env bash
#
# y2_jsr82_outbound_fix.sh — CANDIDATE FIX. Makes mtkbt's JSR82 layer report a
# successful *outgoing* L2CAP connect as success instead of failure.
#
# Requires y2_psm_fix.sh, otherwise the connect never gets far enough to matter.
#
# The bug, traced end to end (see Y2_INVESTIGATION.md):
#
#   0x47b38 is JSR82's L2CAP callback. Its event-2 case (channel connected) is
#   only entered when the connect actually succeeded -- 0x47e6e checks the
#   result halfword at event+0x02 and diverts to an error trace otherwise. So
#   everything below this point is a *successful* channel.
#
#   0x47eb0: ldrb.w r1, [r8, #0x84]   ; r8 = channel record, +0x84 = inbound flag
#   0x47eb4: cbnz   r1, 0x47ed8       ; inbound  -> raise(ev=4, status=1)
#            ...                      ; outbound -> raise(ev=5, status=2)
#   0x47ed2: movs   r1, #5
#   0x47ed4: movs   r2, #2            ; <- reports failure on a channel that
#   0x47ed6: b      0x47f00           ;    just reached "enter open state"
#
#   The inbound flag is set to 1 by the CLOSED-state handler when the peer
#   opens the channel (0x8cdd6) and to 0 by the outgoing-connect setup
#   (0x8941e). So MediaTek only ever completes server-side channels; a
#   client-initiated one is told status 2 no matter how well it went.
#
#   Status 2 then rides all the way out: the raiser 0x450a0 stores it at
#   event+0x22, the handler 0x6cecc sees it is not 1, tears the session down via
#   0x6cac4 and copies the 2 into the 0xa39 confirm -- which is the
#   `msg->result:02` the Java client has always reported.
#
# The fix is the status literal, not the branch. Routing an outbound channel
# into the inbound path (forcing the cbnz) would be wrong: event 4 is the
# connect *indication* for a listening session and its handler at 0x6ce0c looks
# up a listener, which an outgoing connect does not have. Event 5 is the connect
# *confirm* for our own request and is already the right message -- only its
# status is wrong.
#
#   0x47ed4: 2202 (movs r2,#2)  ->  2201 (movs r2,#1)
#
# Scope: reached only from JSR82's own callback, and only after a successful
# channel open, so profiles using L2CAP directly (SDP, AVCTP, A2DP) are not on
# this path.
#
# Usage:
#   ./y2_jsr82_outbound_fix.sh          # patch and reboot
#   ./y2_jsr82_outbound_fix.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.outbound"
PATCHES="0x47ed4:0222:0122"

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

if [ "${1:-}" = "--revert" ]; then
  adb shell "[ -f $BACKUP ]" || { echo "ERROR: no backup at $BACKUP." >&2; exit 1; }
  stop_bluetooth
  echo ">> Restoring the pre-fix mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
echo ">> Pulling live mtkbt"
adb pull "$TARGET" "$BUILD/mtkbt_outbound_stock" >/dev/null

python3 - "$BUILD/mtkbt_outbound_stock" "$BUILD/mtkbt_outbound_patched" $PATCHES <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
for spec in sys.argv[3:]:
    off_s, stock, patch = spec.split(":")
    off = int(off_s, 16)
    stock_b, patch_b = bytes.fromhex(stock), bytes.fromhex(patch)
    found = bytes(d[off:off + len(stock_b)])
    if found == patch_b:
        print("   %#x already patched" % off)
        continue
    if found != stock_b:
        sys.exit("ERROR: unexpected bytes at %#x (%s, wanted %s). Re-derive the "
                 "offset before patching." % (off, found.hex(), stock))
    d[off:off + len(patch_b)] = patch_b
    print("   %#x %s -> %s" % (off, stock, patch))
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_outbound_patched" /data/local/tmp/mtkbt_outbound >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_outbound > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_outbound $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_outbound_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_outbound_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting"
adb reboot
