#!/usr/bin/env bash
#
# y2_trace_to_logcat.sh — DIAGNOSTIC. Routes mtkbt's internal text traces into
# logcat, so the vendor stack's own decisions become visible.
#
# Why (see Y2_INVESTIGATION.md): mtkbt's traces never reach logcat. Its
# plain-text trace helper formats each message and hands it to a sink that ships
# it over MediaTek's Catcher transport, which needs their per-firmware trace
# database to decode. That blindness is what stalled the L2CAP session
# investigation: the status byte that fails a connect arrives through indirect
# dispatch, so no static patch can identify the source -- but the traces name it.
#
# What this does: mtkbt already imports __android_log_print (real PLT stub at
# 0xb720, ARM state), so the sink call inside the trace helper is redirected to a
# small thunk that logs the already-formatted string under the tag "MTKBTD" and
# then returns. The helper's own gate is opened as well, otherwise it formats and
# emits nothing at all -- which is exactly why these traces never surface.
#
#   0x7283c: bl 0x72548        ->  bl 0x105e64   (the thunk)
#
# The thunk lives in the executable segment's last page: LOAD1 ends at 0x105e64
# but the loader maps whole pages, so 0x105e64..0x105fff is mapped, executable
# and all zeroes (412 bytes free). No program headers change, and this PIE maps
# vaddr == file offset.
#
#   push.w {r0-r3, r12, lr}   ; sink args; 6 regs keeps sp 8-aligned for libc
#   ldr  r3, [r0, #4]         ; the formatted text (struct field +0x04)
#   ldr  r2, [r0, #0x10]      ; its length -- the buffer is not NUL-terminated,
#   strb (terminate)          ;   the sink takes an explicit length instead
#   adr  r2, "%s"
#   adr  r1, "MTKBTD"
#   movs r0, #3               ; ANDROID_LOG_DEBUG
#   blx  <PLT>                ; __android_log_print, reached PC-relatively
#   pop.w {r0-r3, r12, lr}
#   bx   lr                   ; return; the original sink is skipped
#
# Only the plain-text helper is redirected; the leveled kal_trace helper builds a
# different struct and is left alone (its ids need MediaTek's database anyway).
#
# Pair with y2_psm_fix.sh when diagnosing the L2CAP session failure, so the
# traces describe a connect the peer actually accepted.
#
# Usage:
#   ./y2_trace_to_logcat.sh          # patch and reboot, then: adb logcat -s MTKBTD
#   ./y2_trace_to_logcat.sh --revert # restore and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
TARGET="/system/bin/mtkbt"
BACKUP="/system/bin/mtkbt.stock.trace"

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
  echo ">> Restoring the pre-trace mtkbt"
  adb shell "mount -o remount,rw /system && mv $TARGET $TARGET.busy 2>/dev/null; \
             cat $BACKUP > $TARGET && chmod 755 $TARGET && rm -f $TARGET.busy && sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"
echo ">> Pulling live mtkbt"
adb pull "$TARGET" "$BUILD/mtkbt_trace_stock" >/dev/null

echo ">> Assembling the thunk"
python3 - "$BUILD/mtkbt_trace_stock" "$BUILD/mtkbt_trace_patched" <<'PY'
import struct, sys

CAVE = 0x105e64   # mapped, executable, zeroed tail of the LOAD1 page
SINK = 0x72548    # original trace sink
CALL = 0x7283c    # the sink call inside the plain-text trace helper
# The helper is gated: it formats and emits nothing unless two globals are clear,
# which they are not on this build -- that is why its traces never appear
# anywhere. Both guard branches are nop'd so the body always runs.
# The second guard's register is reused immediately after it: `str r2,[sp]`
# seeds the formatter's character counter, and the original code only reached
# that store when r2 was proven zero. So zero r2 explicitly rather than nop the
# compare, or the formatter starts writing at a bogus index.
GATES = {0x72736: ('40f08380', 'aff30080'),   # bne.w skip  -> nop.w
         0x72740: ('002a', '0022'),           # cmp r2,#0   -> movs r2,#0
         0x72742: ('7dd1', '00bf')}           # bne   skip  -> nop
PLT  = 0xb720     # __android_log_print PLT stub (ARM state)


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
    """Thumb-2 BLX (immediate, T2): PC-relative call that switches to ARM state.
    Must be used instead of an absolute address -- mtkbt is a PIE and loads at a
    random base, so a hardcoded PLT address faults (learned the hard way)."""
    off = target - (at + 4)
    assert off % 4 == 0, "BLX target must be word-aligned: %#x" % target
    assert -(1 << 24) <= off < (1 << 24), hex(off)
    imm = off >> 1
    s_bit = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    j1, j2 = (~(i1 ^ s_bit)) & 1, (~(i2 ^ s_bit)) & 1
    hw1 = 0xF000 | (s_bit << 10) | ((imm >> 11) & 0x3FF)
    hw2 = 0xC000 | (j1 << 13) | (j2 << 11) | (imm & 0x7FE)
    return struct.pack('<HH', hw1, hw2)


def adr(rd, at, target):
    """Thumb ADR (T1): Rd = Align(PC,4) + imm8*4."""
    base = (at + 4) & ~3
    off = target - base
    assert 0 <= off <= 1020 and off % 4 == 0, hex(off)
    return struct.pack('<H', 0xA000 | (rd << 8) | (off >> 2))


# Fixed layout, so the PC-relative operands can be computed against it. The blx
# sits at 0x18 because BLX(imm) resolves against Align(PC,4) -- keeping it
# 4-aligned avoids an off-by-two in the encoding.
TAG_OFF, FMT_OFF, TOTAL = 0x24, 0x2c, 0x30

thunk = bytearray()
thunk += struct.pack('<HH', 0xE92D, 0x500F)   # push.w {r0-r3, r12, lr}  (6 regs keeps sp 8-aligned for libc)
thunk += struct.pack('<H', 0x6843)            # ldr  r3, [r0, #4]     text
thunk += struct.pack('<H', 0x6902)            # ldr  r2, [r0, #0x10]  length
thunk += struct.pack('<H', 0x2100)            # movs r1, #0
# mtkbt's mini-printf never terminates the buffer -- it hands the sink an
# explicit length -- so terminate it here or logcat prints trailing stack junk.
thunk += struct.pack('<H', 0x2A7F)            # cmp  r2, #0x7f        (buffer is 128 bytes)
thunk += struct.pack('<H', 0xD800)            # bhi  +0               skip if implausible
thunk += struct.pack('<H', 0x5499)            # strb r1, [r3, r2]     NUL-terminate
thunk += adr(2, CAVE + len(thunk), CAVE + FMT_OFF)   # adr r2, "%s"
thunk += adr(1, CAVE + len(thunk), CAVE + TAG_OFF)   # adr r1, "MTKBTD"
thunk += struct.pack('<H', 0x2003)            # movs r0, #3           ANDROID_LOG_DEBUG
thunk += struct.pack('<H', 0xBF00)            # nop                   (align the blx)
assert len(thunk) == 0x18, hex(len(thunk))
thunk += blx_imm(CAVE + len(thunk), PLT)      # blx  __android_log_print
thunk += struct.pack('<HH', 0xE8BD, 0x500F)   # pop.w {r0-r3, r12, lr}
# Return straight to the caller instead of tail-calling the original sink: with
# the gate opened the sink would now run for every trace, and on this build it
# has clearly never been exercised (its transport is MediaTek's Catcher stream).
thunk += struct.pack('<H', 0x4770)            # bx   lr
thunk += struct.pack('<H', 0xBF00)            # nop  (pad to the data offsets)
assert len(thunk) == TAG_OFF, hex(len(thunk))
thunk += b'MTKBTD\0\0'                        # tag @ CAVE+0x24 (distinct from the
                                              #   MTKBT tag libbtsession already uses)
thunk += b'%s\0\0'                           # fmt @ CAVE+0x2c
assert len(thunk) == TOTAL, hex(len(thunk))

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
if d[CALL:CALL + 4] == branch(CALL, CAVE, True):
    print("   already patched")
else:
    if d[CALL:CALL + 4] != branch(CALL, SINK, True):
        sys.exit("ERROR: unexpected bytes at %#x (%s); re-derive the offsets."
                 % (CALL, d[CALL:CALL + 4].hex()))
    if any(b != 0 for b in d[CAVE:CAVE + len(thunk)]):
        sys.exit("ERROR: the cave at %#x is not free; pick another." % CAVE)
    d[CAVE:CAVE + len(thunk)] = thunk
    d[CALL:CALL + 4] = branch(CALL, CAVE, True)
    print("   thunk %d bytes at %#x, call at %#x redirected" % (len(thunk), CAVE, CALL))
    for off, (stock, patch) in sorted(GATES.items()):
        stock_b, patch_b = bytes.fromhex(stock), bytes.fromhex(patch)
        if bytes(d[off:off + len(stock_b)]) == patch_b:
            print("   gate %#x already open" % off)
            continue
        if bytes(d[off:off + len(stock_b)]) != stock_b:
            sys.exit("ERROR: unexpected gate bytes at %#x (%s, wanted %s)."
                     % (off, d[off:off + len(stock_b)].hex(), stock))
        d[off:off + len(patch_b)] = patch_b
        print("   gate %#x opened (%s -> %s)" % (off, stock, patch))
open(dst, 'wb').write(bytes(d))
PY

echo ">> Installing"
adb shell "mount -o remount,rw /system && ([ -f $BACKUP ] || cp $TARGET $BACKUP)"
adb push "$BUILD/mtkbt_trace_patched" /data/local/tmp/mtkbt_trace >/dev/null
stop_bluetooth
adb shell "mv $TARGET $TARGET.busy 2>/dev/null; cat /data/local/tmp/mtkbt_trace > $TARGET \
           && chown root.shell $TARGET && chmod 755 $TARGET \
           && rm -f /data/local/tmp/mtkbt_trace $TARGET.busy && sync"
LOCAL_MD5="$(md5 -q "$BUILD/mtkbt_trace_patched" 2>/dev/null || md5sum "$BUILD/mtkbt_trace_patched" | awk '{print $1}')"
DEV_MD5="$(adb shell "md5 $TARGET" | awk '{print $1}' | tr -d '\r')"
[ "$LOCAL_MD5" = "$DEV_MD5" ] || { echo "ERROR: md5 mismatch after push ($LOCAL_MD5 vs $DEV_MD5)" >&2; exit 1; }
echo "   md5 ok: $LOCAL_MD5"

echo ">> Rebooting. Afterwards:  adb logcat -s MTKBTD"
adb reboot
