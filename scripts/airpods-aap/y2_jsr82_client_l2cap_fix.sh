#!/usr/bin/env bash
#
# y2_jsr82_client_l2cap_fix.sh — CANDIDATE FIX for client-initiated AAP L2CAP.
# Requires y2_psm_fix.sh's HAL half (the mtkbt half is included here).
#
# See Y2_INVESTIGATION.md. Summary of the chain this closes:
#
#   BTJSR82_L2capCallback (0x47b38), on L2EVENT_CONNECTED for a channel we
#   opened, takes its client branch, finds the session context
#   ("JSR82 L2CAP Client connected inx=3"), and then raises session event 5 with
#   a hardcoded status 2 -- which the handler treats as failure and tears the
#   session down.
#
#   Simply changing that literal to 1 crashes mtkbt, because the raiser
#   (0x450a0) has a status-1-only branch for L2CAP sessions:
#
#     450da: cmp   r1, #1          ; ps_type: 1 = RFCOMM, 2 = L2CAP
#     450e8: ldrh  r0, [r6, #0x248]        ; RFCOMM MTU
#     450f6: cmp   r5, #1                  ; L2CAP + success:
#     450fa: ldr.w r2, [r6, #0x2f8]        ;   session's L2CAP channel pointer
#     450fe: ldrh  r3, [r2, #0x24]         ;   its rxMtu, into the event
#
#   and **nothing in the binary ever writes +0x2f8**. The two attach functions
#   both leave it alone -- bt_jsr82_AddNewL2capToContext (0x456f0, server) and
#   bt_jsr82_AddCreateL2capToContext (0x45728, client) each set only
#   +0x2f4 (l2cap_con_state) and +0x2f6 (l2cap_cid). The only writers of +0x2f8
#   anywhere belong to an unrelated struct. So that branch is dead code the
#   vendor never exercised, and the hardcoded 2 is what keeps it unreachable.
#
# The fix therefore has to supply the missing pointer as well as the status.
# In the client branch r8 still holds the L2CAP channel record returned by
# 0x8e9f0(cid) (r8 is callee-saved, so the intervening bl preserves it), which
# is exactly the object the raiser wants. 14 bytes at 0x47eca are redirected to
# a cave thunk that does:
#
#     movs   r2, #1
#     strb.w r2, [r7, #0x2f4]     ; l2cap_con_state = connected
#     str.w  r8, [r7, #0x2f8]     ; l2cap channel pointer  <- the missing write
#     mov    r0, r5
#     movs   r1, #5               ; session event 5
#     movs   r2, #1               ; status success
#     b.w    0x47f00
#
# The server branch at 0x47ed8 is untouched.
#
# RISK: if +0x2f8 is not what this reads it to be, mtkbt will fault again on the
# first connect. mtkbt is a `oneshot` init service, so a crash looks exactly
# like "never started" -- **check /data/tombstones, not just
# `getprop init.svc.mtkbt`.** Keep Bluetooth enabled across the reboot;
# `service call bluetooth_manager 8` disables it persistently and the daemon
# will then be legitimately absent.
#
# Usage:
#   ./y2_jsr82_client_l2cap_fix.sh          # patch and reboot
#   ./y2_jsr82_client_l2cap_fix.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.clientfix"
STOCK_REF="$BUILD/mtkbt_TRUESTOCK_1737c6d3"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y2." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only." >&2; exit 1; }

if [ "${1:-}" = "--revert" ]; then
  [ -f "$STOCK_REF" ] || { echo "ERROR: no stock reference at $STOCK_REF." >&2; exit 1; }
  echo ">> Restoring stock mtkbt from the verified local reference"
  adb push "$STOCK_REF" /data/local/tmp/m >/dev/null
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat /data/local/tmp/m > $TARGET && chown root.shell $TARGET && chmod 755 $TARGET \
             && rm -f $TARGET.busy /data/local/tmp/m && sync"
  adb shell settings put global bluetooth_on 1 >/dev/null 2>&1 || true
  adb reboot
  exit 0
fi

[ -f "$STOCK_REF" ] || { echo "ERROR: no stock reference at $STOCK_REF." >&2; exit 1; }
mkdir -p "$BUILD"

python3 - "$STOCK_REF" "$BUILD/mtkbt_clientfix.bin" <<'PY'
import struct, sys

CAVE, SITE, RESUME = 0x105ee0, 0x47eca, 0x47f00
STOCK = bytes.fromhex('012287f8f42228460521022213e0')   # 14 bytes at SITE


def branch(at, target):
    off = target - (at + 4)
    assert -(1 << 24) <= off < (1 << 24) and off % 2 == 0, hex(off)
    imm = off >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    j1, j2 = (~(i1 ^ s)) & 1, (~(i2 ^ s)) & 1
    return struct.pack('<HH', 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF),
                       0x9000 | (j1 << 13) | (j2 << 11) | (imm & 0x7FF))


t = bytearray()
t += struct.pack('<H', 0x2201)             # movs   r2, #1
t += struct.pack('<HH', 0xF887, 0x22F4)    # strb.w r2, [r7, #0x2f4]
t += struct.pack('<HH', 0xF8C7, 0x82F8)    # str.w  r8, [r7, #0x2f8]
t += struct.pack('<H', 0x4628)             # mov    r0, r5
t += struct.pack('<H', 0x2105)             # movs   r1, #5
t += struct.pack('<H', 0x2201)             # movs   r2, #1
t += branch(CAVE + len(t), RESUME)

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
if bytes(d[SITE:SITE + 4]) != branch(SITE, CAVE):
    if bytes(d[SITE:SITE + 14]) != STOCK:
        sys.exit("ERROR: unexpected bytes at %#x (%s); re-derive."
                 % (SITE, d[SITE:SITE + 14].hex()))
    if any(b != 0 for b in d[CAVE:CAVE + len(t)]):
        sys.exit("ERROR: cave at %#x is not free." % CAVE)
    d[CAVE:CAVE + len(t)] = t
    d[SITE:SITE + 4] = branch(SITE, CAVE)
    d[SITE + 4:SITE + 14] = struct.pack('<H', 0xBF00) * 5
    print("   thunk %d bytes at %#x, site %#x redirected" % (len(t), CAVE, SITE))
# mtkbt half of the PSM fix: request channel := ctx.mtu
if bytes(d[0x6c4e0:0x6c4e2]) == bytes.fromhex('216a'):
    d[0x6c4e0:0x6c4e2] = bytes.fromhex('e18c')
    print("   0x6c4e0 216a -> e18c (PSM)")
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing (Bluetooth is left enabled on purpose)"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_clientfix.bin" /data/local/tmp/m >/dev/null
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/m > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/m $TARGET.busy && sync"
L="$(md5 -q "$BUILD/mtkbt_clientfix.bin" 2>/dev/null || md5sum "$BUILD/mtkbt_clientfix.bin" | awk '{print $1}')"
D="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$L" = "$D" ] || { echo "ERROR: md5 mismatch ($L vs $D)" >&2; exit 1; }
echo "   md5 ok: $L"
adb shell settings put global bluetooth_on 1 >/dev/null 2>&1 || true
echo ">> Rebooting. Then: getprop init.svc.mtkbt AND ls -l /data/tombstones"
adb reboot
