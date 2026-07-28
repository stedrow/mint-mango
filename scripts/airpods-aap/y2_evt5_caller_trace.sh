#!/usr/bin/env bash
#
# y2_evt5_caller_trace.sh — DIAGNOSTIC. Names the code that raises the
# channel-connect event carrying the fatal status 2.
#
# Why (see Y2_INVESTIGATION.md, "Ruled out: every event-5 raiser"): the handler
# at 0x6cecc (trace id 0xc83) copies a status byte from the event into the
# connect confirm and, when it is not 1, calls the session teardown at 0x6cac4.
# Every static raiser was tagged with a distinct status and none of them fired,
# so the raise that matters is reached through indirect dispatch -- a registered
# callback or function-pointer table that no `bl` scan can find. The one thing
# that always identifies an indirect caller is its return address, so hook the
# handler and log LR.
#
# Correction to an earlier note in Y2_INVESTIGATION.md: the teardown is *not*
# performed by btadp_jsr82_connect_req (0x6bd84). That function consumes no
# status at all -- it fills a session record and tail-calls 0x6c4a8, which only
# marshals a message and posts it. The teardown is 0x6cac4, and its only two
# callers are 0x6cf54 (this handler) and 0x6d012.
#
# Hook site: 0x6cee0, six bytes of
#
#   6cee0: 69e0        ldr r0, [r4, #0x1c]
#   6cee2: f7ff fa11   bl  0x6c308
#
# chosen because it is past the handler's own PC-relative literal setup (which
# cannot be relocated into a cave at a different PC) and because r4 already
# holds the event pointer there. The handler's `push {r4,r5,lr}` saved the
# caller's LR but the `bl` at 0x6cedc has since clobbered the register, so LR is
# read back off the stack: entry sp - 4, which after the push, the `sub sp,#0x14`
# and the thunk's own 24-byte push is sp+0x34.
#
# The thunk lives at 0x105ed0, after the two thunks y2_trace_to_logcat.sh
# installs at 0x105e64 and 0x105e94, so both can be applied together -- do that,
# since the surrounding traces are what make the LR meaningful.
#
# Usage:
#   ./y2_evt5_caller_trace.sh          # patch and reboot, then: adb logcat -s MTKEVT5
#   ./y2_evt5_caller_trace.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.evt5"

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
  echo ">> Restoring the pre-hook mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
echo ">> Pulling live mtkbt"
adb pull "$TARGET" "$BUILD/mtkbt_evt5_stock" >/dev/null

echo ">> Assembling the thunk"
python3 - "$BUILD/mtkbt_evt5_stock" "$BUILD/mtkbt_evt5_patched" <<'PY'
import struct, sys

CAVE = 0x105ed0   # clear of y2_trace_to_logcat.sh's thunks (0x105e64+0x30, 0x105e94+0x38)
HOOK = 0x6cee0    # ldr r0,[r4,#0x1c] ; bl 0x6c308   -- both relocated below
CALLEE = 0x6c308  # the relocated bl's target (session lookup)
RESUME = 0x6cee6  # first instruction after the relocated pair
PLT = 0xb720      # __android_log_print PLT stub (ARM state)
STOCK = bytes.fromhex('e069fff711fa')   # file order; objdump shows it halfword-swapped


def branch(at, target, link):
    """Thumb-2 BL (T1) / B.W (T4) encoding."""
    off = target - (at + 4)
    assert -(1 << 24) <= off < (1 << 24) and off % 2 == 0, hex(off)
    imm = off >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    j1, j2 = (~(i1 ^ s)) & 1, (~(i2 ^ s)) & 1
    hw1 = 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF)
    hw2 = (0xD000 if link else 0x9000) | (j1 << 13) | (j2 << 11) | (imm & 0x7FF)
    return struct.pack('<HH', hw1, hw2)


def blx_imm(at, target):
    """Thumb-2 BLX (immediate, T2). PC-relative: mtkbt is a PIE, so an absolute
    PLT address would fault."""
    off = target - (at + 4)
    assert off % 4 == 0, "BLX target must be word-aligned: %#x" % target
    imm = off >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    j1, j2 = (~(i1 ^ s)) & 1, (~(i2 ^ s)) & 1
    hw1 = 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF)
    hw2 = 0xC000 | (j1 << 13) | (j2 << 11) | (imm & 0x7FE)
    return struct.pack('<HH', hw1, hw2)


def adr(rd, at, target):
    """Thumb ADR (T1): Rd = Align(PC,4) + imm8*4."""
    base = (at + 4) & ~3
    off = target - base
    assert 0 <= off <= 1020 and off % 4 == 0, hex(off)
    return struct.pack('<H', 0xA000 | (rd << 8) | (off >> 2))


TAG_OFF, FMT_OFF, TOTAL = 0x2c, 0x34, 0x4c

t = bytearray()
t += struct.pack('<HH', 0xE92D, 0x500F)   # push.w {r0-r3, r12, lr}   (24 bytes)
t += struct.pack('<H', 0x9B0D)            # ldr  r3, [sp, #0x34]      caller LR
t += struct.pack('<HH', 0xF894, 0x1022)   # ldrb.w r1, [r4, #0x22]    status byte
t += struct.pack('<H', 0x6822)            # ldr  r2, [r4]             event id (struct+0)
t += struct.pack('<H', 0xB406)            # push {r1, r2}             varargs 2,3
t += adr(2, CAVE + len(t), CAVE + FMT_OFF)
t += adr(1, CAVE + len(t), CAVE + TAG_OFF)
t += struct.pack('<H', 0x2003)            # movs r0, #3               ANDROID_LOG_DEBUG
t += struct.pack('<HH', 0xBF00, 0xBF00)   # nops (keep the blx 4-aligned)
assert len(t) == 0x18, hex(len(t))
t += blx_imm(CAVE + len(t), PLT)
t += struct.pack('<H', 0xB002)            # add  sp, #8               drop the varargs
t += struct.pack('<HH', 0xE8BD, 0x500F)   # pop.w {r0-r3, r12, lr}
t += struct.pack('<H', 0x69E0)            # ldr  r0, [r4, #0x1c]      (relocated)
t += branch(CAVE + len(t), CALLEE, link=True)              # bl 0x6c308 (relocated)
t += branch(CAVE + len(t), RESUME, link=False)             # b.w back
assert len(t) == TAG_OFF, hex(len(t))
t += b'MTKEVT5\0'                         # tag @ CAVE+0x2c
t += b'evt5 lr=%x st=%x ev=%x\0\0'        # fmt @ CAVE+0x34
assert len(t) == TOTAL, hex(len(t))

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
if bytes(d[HOOK:HOOK + 4]) == branch(HOOK, CAVE, False):
    print("   already patched")
else:
    if bytes(d[HOOK:HOOK + 6]) != STOCK:
        sys.exit("ERROR: unexpected bytes at %#x (%s, wanted %s); re-derive."
                 % (HOOK, d[HOOK:HOOK + 6].hex(), STOCK.hex()))
    if any(b != 0 for b in d[CAVE:CAVE + len(t)]):
        sys.exit("ERROR: the cave at %#x is not free; pick another." % CAVE)
    d[CAVE:CAVE + len(t)] = t
    d[HOOK:HOOK + 4] = branch(HOOK, CAVE, False)
    d[HOOK + 4:HOOK + 6] = struct.pack('<H', 0xBF00)   # nop out the relocated bl's tail
    print("   thunk %d bytes at %#x, hook at %#x redirected" % (len(t), CAVE, HOOK))
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_evt5_patched" /data/local/tmp/mtkbt_evt5 >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_evt5 > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_evt5 $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_evt5_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_evt5_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting. Afterwards:  adb logcat -s MTKEVT5 MTKBTD MTKID"
adb reboot
