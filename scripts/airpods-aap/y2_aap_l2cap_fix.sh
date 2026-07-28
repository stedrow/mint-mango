#!/usr/bin/env bash
#
# y2_aap_l2cap_fix.sh — THE FIX. Makes client-initiated AAP L2CAP work on the Y2.
#
# Supersedes y2_psm_fix.sh and y2_jsr82_client_l2cap_fix.sh. No HAL patch is
# needed: bluetooth.blueangel.so stays stock.
#
# Three bugs in MediaTek's JSR82 layer, all on the client (outgoing) L2CAP path,
# all found by decoding mtkbt's own traces (see Y2_INVESTIGATION.md):
#
#  1. The PSM never reached the wire. btadp_jsr82_connect_req zeroes
#     ctx.channel and never copies msg->channel (+0x0e), so the Connection
#     Request went out with PSM 0 and the peer refused it.
#
#       0x6bdfa: str r6,[r5,#0x20]  ->  ldrh r3,[r4,#0xe] ; str r3,[r5,#0x20]
#
#  2. A successful connect was reported as a failure. BTJSR82_L2capCallback's
#     client branch finds its session context and then raises session event 5
#     with a hardcoded status 2. Status 1 alone crashes the daemon, because the
#     raiser's success branch reads session_buffer->l2capCtx.channel (+0x2f8)
#     and *nothing in the binary ever writes it*. So supply the channel record
#     (still live in r8 from the CID lookup) as well as the status.
#
#       0x47eca: con_state=1 ; l2capCtx.channel=r8 ; raise(event 5, status 1)
#
#  3. Data never reached the air. The TX path passes the CID from the request
#     message's +0x06, which is never populated for a client L2CAP session, so
#     L2CAP_Send got cid 0, failed its channel lookup, returned BT_STATUS_FAILED
#     and the packet was silently requeued. Take the CID from the session's own
#     l2capCtx.l2capLocalCid instead, on the already-L2CAP-only branch.
#
#       0x4804c: mov r1,r7  ->  ldr r1,[r4,#0x30] ; ldrh r1,[r1,#0x2f6]
#
# Verified end to end: connect succeeds, the handshake goes out, and the AirPods
# answer -- handshake ACK (01 00 04 00 ...), features ACK (04 00 04 00 2b 00),
# then ear-detection notifications parsed as "AAP-L2CAP ear primary/secondary".
#
# mtkbt is a `oneshot` init service: a crash at any point looks identical to
# "never started". Check /data/tombstones, not just `getprop init.svc.mtkbt`.
# Never disable Bluetooth around a reboot (`service call bluetooth_manager 8`
# turns it off persistently and the daemon then legitimately never starts).
#
# Usage:
#   ./y2_aap_l2cap_fix.sh          # patch and reboot
#   ./y2_aap_l2cap_fix.sh --revert # restore stock and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
STOCK_REF="$BUILD/mtkbt_TRUESTOCK_1737c6d3"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only." >&2; exit 1; }
[ -f "$STOCK_REF" ] || { echo "ERROR: no stock reference at $STOCK_REF." >&2; exit 1; }

install() {
  adb push "$1" /data/local/tmp/m >/dev/null
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat /data/local/tmp/m > $TARGET && chown root.shell $TARGET && chmod 755 $TARGET \
             && rm -f /data/local/tmp/m $TARGET.busy && sync"
  local l d
  l="$(md5 -q "$1" 2>/dev/null || md5sum "$1" | awk '{print $1}')"
  d="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
  [ "$l" = "$d" ] || { echo "ERROR: md5 mismatch ($l vs $d)" >&2; exit 1; }
  echo "   md5 ok: $l"
  adb shell settings put global bluetooth_on 1 >/dev/null 2>&1 || true
  adb reboot
}

if [ "${1:-}" = "--revert" ]; then
  echo ">> Restoring stock mtkbt"
  install "$STOCK_REF"
  exit 0
fi

python3 - "$STOCK_REF" "$BUILD/mtkbt_aapfix.bin" <<'PY'
import struct, sys


def br(at, target, link=False):
    off = target - (at + 4)
    assert -(1 << 24) <= off < (1 << 24) and off % 2 == 0, hex(off)
    imm = off >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    j1, j2 = (~(i1 ^ s)) & 1, (~(i2 ^ s)) & 1
    return struct.pack('<HH', 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF),
                       (0xD000 if link else 0x9000) | (j1 << 13) | (j2 << 11) | (imm & 0x7FF))


src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())

# --- 1. carry the PSM in msg->channel (+0x0e) into ctx.channel (+0x20) -------
CAVE, SITE = 0x105f00, 0x6bdfa
STOCK = bytes.fromhex('2e62e9f764f8')          # str r6,[r5,#0x20] ; bl memcpy
t = bytearray()
t += struct.pack('<H', 0x89E3)                 # ldrh r3,[r4,#0xe]
t += struct.pack('<H', 0x622B)                 # str  r3,[r5,#0x20]
t += br(CAVE + len(t), 0x54ec8, link=True)     # bl memcpy (relocated; r0-r2 set)
t += br(CAVE + len(t), 0x6be00)
assert bytes(d[SITE:SITE + 6]) == STOCK, d[SITE:SITE + 6].hex()
assert all(b == 0 for b in d[CAVE:CAVE + len(t)])
d[CAVE:CAVE + len(t)] = t
d[SITE:SITE + 4] = br(SITE, CAVE)
d[SITE + 4:SITE + 6] = struct.pack('<H', 0xBF00)
print("   1/3 PSM -> ctx.channel   thunk %d B @ %#x" % (len(t), CAVE))

# --- 2. report the client connect as success, with the channel pointer ------
CAVE, SITE = 0x105ee0, 0x47eca
STOCK = bytes.fromhex('012287f8f42228460521022213e0')
t = bytearray()
t += struct.pack('<H', 0x2201)                 # movs   r2,#1
t += struct.pack('<HH', 0xF887, 0x22F4)        # strb.w r2,[r7,#0x2f4]  con_state
t += struct.pack('<HH', 0xF8C7, 0x82F8)        # str.w  r8,[r7,#0x2f8]  channel*
t += struct.pack('<H', 0x4628)                 # mov    r0,r5
t += struct.pack('<H', 0x2105)                 # movs   r1,#5
t += struct.pack('<H', 0x2201)                 # movs   r2,#1  (success)
t += br(CAVE + len(t), 0x47f00)
assert bytes(d[SITE:SITE + 14]) == STOCK, d[SITE:SITE + 14].hex()
assert all(b == 0 for b in d[CAVE:CAVE + len(t)])
d[CAVE:CAVE + len(t)] = t
d[SITE:SITE + 4] = br(SITE, CAVE)
d[SITE + 4:SITE + 14] = struct.pack('<H', 0xBF00) * 5
print("   2/3 connect success      thunk %d B @ %#x" % (len(t), CAVE))

# --- 3. send with the session's own CID, not the empty message field --------
CAVE, SITE = 0x105f70, 0x4804c
STOCK = bytes.fromhex('3946bde8f840fff7a9bc')
t = bytearray()
t += struct.pack('<H', 0x6B21)                 # ldr  r1,[r4,#0x30]    session_buffer
t += struct.pack('<HH', 0xF8B1, 0x12F6)        # ldrh r1,[r1,#0x2f6]   l2capLocalCid
t += struct.pack('<HH', 0xE8BD, 0x40F8)        # pop.w {r3-r7,lr}      (relocated)
t += br(CAVE + len(t), 0x479a8)                # tail call the L2CAP sender
assert bytes(d[SITE:SITE + 10]) == STOCK, d[SITE:SITE + 10].hex()
assert all(b == 0 for b in d[CAVE:CAVE + len(t)])
d[CAVE:CAVE + len(t)] = t
d[SITE:SITE + 4] = br(SITE, CAVE)
d[SITE + 4:SITE + 10] = struct.pack('<H', 0xBF00) * 3
print("   3/3 TX uses real CID     thunk %d B @ %#x" % (len(t), CAVE))
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing (Bluetooth left enabled)"
install "$BUILD/mtkbt_aapfix.bin"
echo ">> Rebooting. Then check: adb logcat -s AapService | grep 'AAP rx'"
