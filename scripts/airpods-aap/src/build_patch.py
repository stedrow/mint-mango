#!/usr/bin/env python3
"""
Patches /system/lib/libextjsr82.so (pulled fresh from the device) so that a
client L2CAP connect to an arbitrary PSM (e.g. AAP's 0x1001) is tagged
ps_type=2 (the real connection-alloc path) instead of ps_type=1 (the
RFCOMM/channel path the stock JNI always uses, which the AirPods reject).

Background: scripts/airpods-rtpfix/PHASE2_PLAN.md, "Middle option" section.

How the patch works (see that doc for the full disassembly writeup):
btmtk_jsr82_session_connect_req's ps_type argument is a straight passthrough
onto the wire message; ps_type==2 uses a plain free-slot allocator with no
pre-registration requirement, so it's a legitimate raw-PSM client path. We
can't fix this from the app side (the JNI hardcodes ps_type=1 for
TYPE_L2CAP sockets, and that collapse happens somewhere in
libandroid_runtime.so, a zygote-loaded library too risky to touch). Instead
this hooks the decision point *inside* libextjsr82.so itself — a leaf
Bluetooth-extension library loaded only by processes that open JSR82
sockets, not zygote — same risk class as the Phase 1 libbluetoothdrv.so
proxy.

The hook overwrites 6 bytes at the `cmp r5,#1` decision (session_connect_req)
with a `b.w` to a new code cave appended as its own PT_LOAD segment. The cave:
  - reads the PSM/channel arg (already in `sl`)
  - if it looks like an RFCOMM channel (< 0x100): does nothing extra, replays
    the original two instructions we overwrote, and takes the *original*
    branch — zero behavior change for every non-AAP JSR82 caller (headset,
    OBEX, SPP, ...).
  - if it looks like an L2CAP PSM (>= 0x100, true for AAP's 0x1001): forces
    ps_type=2 before replaying those two instructions, which now routes into
    the allocate/connect path instead of the RFCOMM-channel check.

Requires: pip3 install keystone-engine lief
"""
import argparse
import sys

import keystone
import lief

# Addresses in the ORIGINAL (unpatched) libextjsr82.so, from disassembly.
# Re-verify these with objdump/capstone if you pull a libextjsr82.so from a
# different firmware build -- do not assume they still hold.
ORIG_HOOK_ADDR = 0x7b3e          # cmp r5,#1  (the ps_type decision point)
ORIG_FALL_L2CAP_OK = 0x7b44      # original fallthrough when r5==1
ORIG_BRANCH_RFCOMM = 0x7b56      # original branch target when r5!=1
CAVE_SIZE = 24

# The exact 6 bytes we expect at ORIG_HOOK_ADDR in the original, unpatched
# lib: cmp r5,#1 / str r4,[sp,#0x1c] / bne <ORIG_BRANCH_RFCOMM>. If a
# different firmware build doesn't match this, refuse to patch rather than
# silently corrupting whatever actually lives there.
EXPECTED_HOOK_BYTES = bytes.fromhex("012d079408d1")

ks = keystone.Ks(keystone.KS_ARCH_ARM, keystone.KS_MODE_THUMB)


def text_addr(binary):
    return [s for s in binary.sections if s.name == '.text'][0].virtual_address


def build_cave(cave_addr, branch_rfcomm, fall_l2cap_ok):
    cave_asm = f"""
        mov  r0, sl
        lsrs r0, r0, #8
        cmp  r0, #0
        beq  L1
        movs r5, #2
    L1:
        str  r4, [sp, #0x1c]
        cmp  r5, #1
        beq  L2
        b.w  {hex(branch_rfcomm)}
    L2:
        b.w  {hex(fall_l2cap_ok)}
    """
    cave_bytes, _ = ks.asm(cave_asm, cave_addr)
    cave_bytes = bytes(cave_bytes)
    assert len(cave_bytes) == CAVE_SIZE, f"cave size changed: {len(cave_bytes)}"
    return cave_bytes


def build_hook(hook_addr, cave_addr):
    hook_asm = f"b.w {hex(cave_addr)}\nnop"
    hook_bytes, _ = ks.asm(hook_asm, hook_addr)
    hook_bytes = bytes(hook_bytes)
    assert len(hook_bytes) == 6, f"hook stub size changed: {len(hook_bytes)}"
    return hook_bytes


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('src', help='path to a pristine libextjsr82.so pulled from the device')
    ap.add_argument('out', help='path to write the patched .so to')
    args = ap.parse_args()

    orig = lief.parse(args.src)
    orig_text_addr = text_addr(orig)

    actual_hook_bytes = bytes(orig.get_content_from_virtual_address(ORIG_HOOK_ADDR, len(EXPECTED_HOOK_BYTES)))
    if actual_hook_bytes != EXPECTED_HOOK_BYTES:
        print(
            f"ERROR: bytes at {hex(ORIG_HOOK_ADDR)} don't match what this patch expects "
            f"(got {actual_hook_bytes.hex()}, expected {EXPECTED_HOOK_BYTES.hex()}).\n"
            "This firmware's libextjsr82.so differs from the one this patch was reverse-engineered "
            "against -- re-verify the offsets with objdump/capstone before patching, or you will "
            "corrupt unrelated code.",
            file=sys.stderr,
        )
        sys.exit(1)

    b = lief.parse(args.src)

    seg = lief.ELF.Segment()
    seg.type = lief.ELF.Segment.TYPE.LOAD
    seg.flags = lief.ELF.Segment.FLAGS.R | lief.ELF.Segment.FLAGS.X
    seg.content = [0] * CAVE_SIZE
    seg.alignment = 0x1000
    new_seg = b.add(seg)
    cave_addr = new_seg.virtual_address

    new_text_addr = text_addr(b)
    shift = new_text_addr - orig_text_addr
    print(f"orig .text={hex(orig_text_addr)} new .text={hex(new_text_addr)} shift={hex(shift)}")

    hook_addr = ORIG_HOOK_ADDR + shift
    branch_rfcomm = ORIG_BRANCH_RFCOMM + shift
    fall_l2cap_ok = ORIG_FALL_L2CAP_OK + shift
    print(f"cave placed at {hex(cave_addr)}, hook at {hex(hook_addr)}")

    cave_bytes = build_cave(cave_addr, branch_rfcomm, fall_l2cap_ok)
    hook_bytes = build_hook(hook_addr, cave_addr)
    print("cave bytes:", cave_bytes.hex())
    print("hook bytes:", hook_bytes.hex())

    b.patch_address(hook_addr, list(hook_bytes))
    b.patch_address(cave_addr, list(cave_bytes))

    b.write(args.out)
    print("wrote", args.out)

    # verify by reading back what actually landed at those addresses
    check = lief.parse(args.out)
    got_hook = bytes(check.get_content_from_virtual_address(hook_addr, 6))
    got_cave = bytes(check.get_content_from_virtual_address(cave_addr, CAVE_SIZE))
    if got_hook != hook_bytes or got_cave != cave_bytes:
        print("ERROR: readback mismatch, patched file does not contain the intended bytes", file=sys.stderr)
        sys.exit(1)
    print("readback OK")


if __name__ == '__main__':
    main()
