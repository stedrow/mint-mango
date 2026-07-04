# AirPods AAP — ps_type L2CAP patch (Phase 2, unverified)

Companion to `scripts/airpods-rtpfix/` (which fixed audio). This folder
targets Phase 2: talking Apple's AAP protocol over L2CAP PSM `0x1001` for
ear-detection/battery/noise-control, per
`scripts/airpods-rtpfix/PHASE2_PLAN.md`.

**Status: patch built and verified by disassembly, NOT yet flashed/tested on
a device.** Milestone 0 (see `PHASE2_PLAN.md`) found that the stock JSR82
socket layer on this MTK 4.2.2 stack tags every client L2CAP connect
`ps_type=1` (the RFCOMM/channel path), which the AirPods reject. A
static-analysis spike then confirmed `ps_type=2` is a genuine, ungated
raw-PSM client-connect path — just nothing in the app can select it.

## What this patch does

Binary-patches `/system/lib/libextjsr82.so` (the closed MTK JSR82 native
library) so that a client connect to a PSM-looking value (>= `0x100`, true for
AAP's `0x1001`; RFCOMM channels are 1-30) is tagged `ps_type=2` instead of
`ps_type=1`. Every other JSR82 caller (headset RFCOMM, OBEX, SPP — anything
with a normal channel number) is untouched: the override only fires when the
channel/PSM value looks like an L2CAP PSM, not an RFCOMM channel.

Mechanically: `src/build_patch.py` uses `capstone`-verified disassembly
offsets to append a small code cave (a new PT_LOAD segment) with the
extra logic, and rewrites 6 bytes at the original decision point
(`btmtk_jsr82_session_connect_req`'s `cmp r5,#1`) to jump into it. See the
docstring in that file and the "Middle option" section of
`../airpods-rtpfix/PHASE2_PLAN.md` for the full reverse-engineering
writeup (disassembly excerpts, why `libandroid_runtime.so` was ruled out as
the patch target, etc).

**Risk class**: same as the Phase 1 `libbluetoothdrv.so` swap — a leaf
Bluetooth-extension library, not zygote-loaded, fully reversible via
`revert.sh`. Not the `system_server`/zygote-wide risk the original Phase 2
plan worried about.

## Usage

```
./build.sh      # pulls the live libextjsr82.so, produces build/libextjsr82_patched.so
./install.sh    # backs up the stock lib -> libextjsr82_real.so, installs patched, reboots
./status.sh     # confirm which lib is active, tail relevant logcat tags
./revert.sh     # restore the stock lib, reboot
```

`build.sh` requires `pip3 install keystone-engine lief` (and `pyelftools` +
`capstone` if you want to re-verify the disassembly offsets rather than trust
the hardcoded ones in `build_patch.py`).

`build_patch.py` refuses to patch if the bytes at the hardcoded hook address
don't match what was reverse-engineered — if your firmware's
`libextjsr82.so` differs, re-derive the offsets before trusting this.

## Testing

The `AapSpikeService` from the M0 spike (same branch, `spike/m0-aap-l2cap`)
already attempts exactly the connect this patch targets (`TYPE_L2CAP`,
`port=0x1001`) — no app changes needed to test. Install the patch, launch the
spike service, and watch for the connect actually succeeding and AAP
handshake bytes coming back (see `M0_RESULT_l2cap_trace.txt` for what
*failure* looked like, for comparison).

If it works: promote to `M1` in `PHASE2_PLAN.md` (a real `AapService`). If it
doesn't: fall back to Path B (L2CAP client inside the `libbluetoothdrv.so`
HCI proxy).
