#!/usr/bin/env bash
#
# y2_raise_caller_trace.sh — DIAGNOSTIC. Names the caller that raises session
# event 5 with status 2, the value that kills every AAP connect.
#
# Why (see Y2_INVESTIGATION.md): y2_evt5_caller_trace.sh proved the handler at
# 0x6cecc really does receive event id 5 with the status byte at event+0x22 set
# to 2, dispatched from the `blx r2` at 0x45188 inside the raiser 0x450a0. The
# raiser's event-5 block (0x450d6..0x45124) writes that byte from its third
# argument, so the 2 comes from whoever called 0x450a0.
#
# A `bl`/`b.w` scan finds fourteen call sites. Exactly six pass event id 5, and
# five of those pass a literal 2 -- and all five were already tagged with
# distinct status values in an earlier session without the client's report ever
# changing. So either the call that fires is indirect, or those tagging runs
# were invalid (they predate the discovery that the scripts' revert backups go
# stale, so it is worth re-checking rather than trusting). Logging LR at the
# raiser's entry settles it either way.
#
# Hook site: 0x450a0, the first six bytes
#
#   450a0: e92d 41f0   push.w {r4, r5, r6, r7, r8, lr}
#   450a4: b08a        sub    sp, #0x28
#
# Both are position-independent, so both are relocated into the thunk and the
# hook branches back to 0x450a6. LR is still the caller's at entry, so it is
# read straight from the register. Logging is gated on event id 5 to keep the
# volume down -- this raiser is hot, and an unfiltered log flooded the daemon.
#
# The thunk lives at 0x105f20, clear of the thunks installed by
# y2_trace_to_logcat.sh (0x105e64, 0x105e94) and y2_evt5_caller_trace.sh
# (0x105ed0). Apply this one last.
#
# Usage:
#   ./y2_raise_caller_trace.sh          # patch and reboot, then: adb logcat -s MTKRAIS
#   ./y2_raise_caller_trace.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.raise"

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
adb pull "$TARGET" "$BUILD/mtkbt_raise_stock" >/dev/null

echo ">> Assembling the thunk"
python3 - "$BUILD/mtkbt_raise_stock" "$BUILD/mtkbt_raise_patched" <<'PY'
import struct, sys

CAVE = 0x105f20   # clear of the trace thunks (0x105e64, 0x105e94) and evt5 (0x105ed0)
HOOK = 0x450a0    # push.w {r4-r8,lr} ; sub sp,#0x28   -- both relocated below
RESUME = 0x450a6  # first instruction after the relocated pair
PLT = 0xb720      # __android_log_print PLT stub (ARM state)
STOCK = bytes.fromhex('2de9f0418ab0')   # file order


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
    """Thumb-2 BLX (immediate, T2). PC-relative: mtkbt is a PIE."""
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
SKIP = 0x1c   # where the gated-out path rejoins

t = bytearray()
t += struct.pack('<HH', 0xE92D, 0x500F)   # push.w {r0-r3, r12, lr}
t += struct.pack('<H', 0x2905)            # cmp  r1, #5          event id
t += struct.pack('<H', 0xD100 | ((SKIP - (0x06 + 4)) >> 1))   # bne skip
t += struct.pack('<H', 0x4673)            # mov  r3, lr          caller
t += struct.pack('<H', 0xB406)            # push {r1, r2}        varargs 2,3
t += adr(2, CAVE + len(t), CAVE + FMT_OFF)
t += adr(1, CAVE + len(t), CAVE + TAG_OFF)
t += struct.pack('<H', 0x2003)            # movs r0, #3          ANDROID_LOG_DEBUG
t += struct.pack('<H', 0xBF00)            # nop (keep the blx 4-aligned)
assert len(t) == 0x14, hex(len(t))
t += blx_imm(CAVE + len(t), PLT)
t += struct.pack('<H', 0xB002)            # add  sp, #8          drop the varargs
t += struct.pack('<H', 0xBF00)            # nop
assert len(t) == SKIP, hex(len(t))
t += struct.pack('<HH', 0xE8BD, 0x500F)   # pop.w {r0-r3, r12, lr}
t += struct.pack('<HH', 0xE92D, 0x41F0)   # push.w {r4-r8, lr}   (relocated)
t += struct.pack('<H', 0xB08A)            # sub  sp, #0x28       (relocated)
t += branch(CAVE + len(t), RESUME, link=False)
t += struct.pack('<H', 0xBF00)            # pad to the data offsets
assert len(t) == TAG_OFF, hex(len(t))
t += b'MTKRAIS\0'                         # tag @ CAVE+0x2c
t += b'raise lr=%x ev=%x st=%x\0'         # fmt @ CAVE+0x34
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
    d[HOOK + 4:HOOK + 6] = struct.pack('<H', 0xBF00)
    print("   thunk %d bytes at %#x, hook at %#x redirected" % (len(t), CAVE, HOOK))
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_raise_patched" /data/local/tmp/mtkbt_raise >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_raise > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_raise $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_raise_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_raise_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting. Afterwards:  adb logcat -s MTKRAIS MTKEVT5 MTKBTD MTKID"
adb reboot
