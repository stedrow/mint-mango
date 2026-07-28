# AAP on Y2 — investigation notes (in-ear detection / stem control)

**Status: in-ear detection works over BLE adverts (~2s). L2CAP is half solved:
the PSM bug is found and fixed, the session verdict is not.** Ear state and battery come from Apple's
proximity-pairing BLE advertisement, which needs no connection at all — see
"The BLE advertisement route" below. The remaining reason to care about L2CAP
is latency: real AAP notifications are sub-second, the advert route is ~2s.

Verified on-device with AirPods Pro 3: pulling either bud pauses playback
~2s later, reinserting it resumes.

**The old conclusion below ("every PSM is refused", "the raw L2CAP client path
is non-functional") was wrong** — it was inferred from the vendor's internal
result code without ever looking at the wire. See "What the wire actually
shows" for the correction. Two facts now measured directly, on air:

- The stack was sending `ConnectionRequest psm=0x0000`. An invalid PSM, which
  every peer correctly refuses — that refusal was the "failure", not a broken
  L2CAP client path.
- With the PSM fixed in the vendor stack (`y2_psm_fix.sh`), **the AirPods accept
  the channel and it configures successfully in both directions.** The wire side
  works, natively, with no proxy involved.

What still fails is one layer up: MTK's session layer reports `msg->result:02`
to Java anyway, ~135ms after the channel is up, so `BluetoothSocket.connect()`
still throws and the launcher never gets a usable socket.

## What the wire actually shows

The device's standard `btsnoop_hci.log` hook doesn't work on this firmware (see
the tooling notes), so the HCI stream was captured by hooking the transport
instead: build the rtpfix proxy with `SNOOP=1 ./build.sh` and it hex-dumps
every non-media read/write under the `BTSNOOP` tag. Reassemble per direction by
concatenating in order (the transport fragments each packet across several
calls; oversized packets appear as `skip=N` gap markers).

One capture during a live connect attempt, before any fix:

```
TX L2CAP-SIG ConnectionRequest  psm=0x0000 scid=0x0043
RX L2CAP-SIG ConnectionResponse scid=0x0043 result=0x0002 (Refused-PSM not supported)
```

while the stack's own profiles, over the very same ACL link, were fine:

```
TX L2CAP-SIG ConnectionRequest psm=0x0019 scid=0x0040   -> Successful   (AVCTP)
TX L2CAP-SIG ConnectionRequest psm=0x0001 scid=0x0044   -> Successful   (SDP)
```

So `msg->result:02` was just the peer's `0x0002` relayed up, and the earlier
"every PSM including SDP is refused" experiment was really "every attempt sent
PSM 0". Nothing about AAP, Apple, or raw L2CAP support was involved.

### Where the PSM was lost, and the real fix

No `BluetoothSocket` argument reaches the PSM field. Probed five ctor variants
on-device (`port=0x1001`, `port=1`, `port=2`, `port=-1`, and a 16-bit-alias
`ParcelUuid` of `0x1001`, with and without auth/encrypt): every one emitted
`psm=0x0000` on the wire.

MediaTek BSP source (MT6582 dumps on GitHub) settled where it goes. The real
signature, from `frameworks/bluetooth/blueangel/btadp_ext/include/bt_jsr82_api.h`:

```c
BT_BOOL btmtk_jsr82_session_connect_req(
            kal_uint32 transaction_id, kal_uint8 *bd_addr, kal_uint8 ps_type,
            kal_uint16 psm_channel, kal_uint16 mtu,
            kal_uint8 security_value, kal_uint8* status_result);
```

and `bluetooth_jsr82_struct.h` gives the message the HAL sends to `mtkbt`
(`bt_jsr82_connect_req_struct`: `bd_addr`, `ps_type`, `mtu`, `channel`,
`identify`, `security_value`). The HAL packs all of it correctly — so the PSM
does leave the client side.

**The bug is in the daemon.** `mtkbt`'s connect handler zeroes its context's
channel slot and never copies `msg->channel` into it, then builds the L2CAP
Connection Request from that zero:

```
0x6bdfa (mtkbt): str r6,[r5,#0x20]   ctx.channel := 0     (never re-filled)
0x6c4e0 (mtkbt): ldr r1,[r4,#0x20]   request.channel := ctx.channel
0x6c4e8 (mtkbt): strh.w r1,[sp,#0x12]  -> message field `channel`
```

That slot can't be filled in place: the register holding the zero also writes
`ctx+0x38`, a live field read elsewhere (clobbering it crashed mtkbt). So
`scripts/airpods-aap/y2_psm_fix.sh` routes the PSM through the one field the
daemon *does* copy — `mtu` — and sources the request's channel from it:

```
blueangel 0x27480: ldrh.w r2,[sp,#0x3c]  -> movw r2,#0x1001   (mtu arg := PSM)
mtkbt     0x6c4e0: ldr  r1,[r4,#0x20]    -> ldrh r1,[r4,#0x26] (channel := ctx.mtu)
```

**Verified natively:** with the proxy's own PSM rewrite compiled out (`PSMFIX=0`,
zero `BTPSM` lines in the capture), the Y2 sends `ConnectionRequest psm=0x1001`,
the AirPods answer `result=0x0000`, and the config exchange completes both ways.
Side effect: the requested L2CAP MTU becomes 4097 instead of 1000, negotiated
down by the peer.

> Earlier notes in this file credited a HAL-only patch for this. That was wrong
> twice over: the first version patched the `mtu` argument believing it was the
> PSM, and the `psm=0x1001` seen at the time was really the proxy's HCI rewrite.
> **Always check for `BTPSM` lines next to a `psm=0x1001` request before
> crediting a patch.**

### Why stock Android can't do this at all

Confirmed against AOSP: stock 4.4 bluedroid rejects `BTSOCK_L2CAP` outright
("l2cap socket type not supported") — `BluetoothSocket.TYPE_L2CAP` exists as a
constant with no implementation until the public `createInsecureL2capChannel`
API arrives in Android 10. Everything working here is MediaTek's own bolt-on via
their JSR82 session layer, which is why it is half-implemented.

This also explains the Y1/Y2 split. Y1 uses MediaTek's older JNI socket service
(`android_server_BluetoothSocketService.cpp`, present in the BSP dumps), which
passes the Java port straight through as `psm_channel` and only gets `ps_type`
wrong — hence the Y1 fix is a one-value patch. Y2 replaced that path with
bluedroid's `btsock` glue, where `ps_type` is already right but the daemon drops
the channel. Different bug, different layer; the Y1 patch has nothing to port.

### What still blocks the socket

The refusal now comes from `mtkbt`'s own bookkeeping, after the channel exists.
Traced in the server binary:

- `FUN_0007d208` case 5 is the channel-connect result event. It copies a status
  byte straight from the event into the confirm it sends the client
  (`FUN_0007cc8a(0xa39, ...)`), and only calls the success path when that byte
  is 1. Ours is 2, so `msg->result:02` is a relayed internal status, not a
  decision made here.
- Of the seven callers of the session-event raiser `FUN_000550a0`, exactly one
  raises event 5 with status 1 (success): `FUN_00057620` case 2, the
  channel-open state-machine event. Every other site raises event 5 status 2.
- `bt_jsr82_rfcomm_connect` (`FUN_00058914`) is one of those failure sites, and
  it validates a *RFCOMM channel number* (`channel-1 < 0x20`, i.e. 1..32) before
  proceeding. That looked like the culprit — an L2CAP connect being completed
  through an RFCOMM-flavoured path whose range check a PSM can never satisfy —
  but feeding a legal small channel via the Java `port` (1 and 2, with the HAL
  patch still supplying PSM `0x1001`) did **not** change the result. So either
  that check reads a field the port doesn't feed, or the failure is at one of
  the other event-5-status-2 sites.

Note that failed attempts **leak**: with the PSM rewrite on, the channels are
established and never disconnected (CIDs climb `0x4a, 0x4b, 0x4c...` across
retries), and after a few tries later attempts fail in ~3ms without any wire
traffic at all — so always judge a change by the *first* attempt after a fresh
service start. This is also why `PSMFIX` ships **off**: without a working socket
it only leaves channels open on the AirPods.

### Two patch experiments, both tried and reverted

**1. Force the result byte (`y2_force_session_ok.sh`, first version).** Patched
the three places in `mtkbt` that consume the channel-connect status: the two
reads of the event's status byte and the channel value handed back to the client
(`movs r3,#1` / `movs r1,#1`). This *did* get a socket into Java's hands:
`msg->result:01`, the client allocated its ring buffer ("SPP con req"), and
`AapService` logged **"AAP L2CAP connected"**. So the whole client-side plumbing
(session handoff, ring buffer, the channel int `connect()` insists must be > 0)
works fine.

But it is mutually exclusive with the PSM fix: with the forced-success path
taken, every request went out with PSM 0 again (peer refused it), so the socket
rode a dead channel and died ~30s later with `IOException: Bad file number`.
Forcing success evidently bypasses whatever would have carried a real PSM.

**2. Fix the success gate instead (`y2_force_session_ok.sh`, second version).**
`FUN_00057620` case 2 (channel open) only raises the "connected" event when the
session's state field is 0:

```
4791a: ldr  r2, [r4]
4791c: cbnz r2, +0xc      <- skips the success raise when state != 0
4791e: ...  raise(event 5, status 1)
```

An outbound session is state 2 ("connecting") there, which looked like exactly
the bug. Nop-ing the `cbnz` (`32b9` -> `00bf`) still produced `msg->result:02` — tested
twice: once with only the proxy's wire-level PSM rewrite, and again on top of the
native PSM fix, so the daemon had the correct PSM in its own bookkeeping. So
either that code path isn't reached for a ps_type=2 session, or the status-2
event is raised elsewhere first. The remaining suspects are the other four
event-5-status-2 sites (`FUN_00055fdc`, `FUN_000562ec`, `FUN_0005595c`,
`FUN_00057b38`), and identifying which one fires still needs a trace.

The force-success variant is gone (it was mutually exclusive with a real PSM);
the gate variant's offsets are recorded above if someone wants to retry it in
combination with something else.

### Ruled out: every event-5 raiser (measured, not inferred)

The status byte the client reports is `FUN_000550a0`'s third argument, landing at
the event struct's `+0x22` (confirmed in its decompiled case 5). So each raiser
was given a *distinct* status value, turning `msg->result:0X` into a name tag for
whichever site fires (`scripts/airpods-aap/y2_probe_fail_site.sh`, with the PSM
fix applied so the failure under test is the real one):

| tagged site | code | result |
|---|---|---|
| `0x46000` (Ghidra `0x55fdc`) | 3 | not it |
| `0x4650c` (Ghidra `0x562ec`) | 4 | not it |
| `0x45b08` (Ghidra `0x5595c`) | 5 | not it |
| `0x47d4c` (Ghidra `0x57b38`) | 6 | not it |
| `0x462ca` (tail-call raiser) | 7 | not it |
| `0x6be5a` (`btadp_jsr82_connect_req`'s own rejection tail) | 8 | not it |

Every attempt still reported `result:02`. Those are *all* the event-5 raisers —
the other `FUN_000550a0` call sites pass their status in a register and raise
events 3, 4 and 6, checked individually. And the confirm-building copy is
definitely the one observed, because forcing that copy to 1 (the earlier
force-success patch) did change the client's report to `result:01`.

So the event reaching `FUN_0007d208` case 5 carries a 2 that no tagged raiser
produced. Either a second producer sends the same message with its own struct
layout, or the receiver's `+0x22` is not the field the sender thinks it is (a
header offset mismatch, of the same kind as the channel/mtu confusion found
earlier in this file).

That probe was run (`y2_probe_fail_site.sh --offset`, pointing the confirm's copy
at the event's `+0x20`): the client reported **`result:00`**. Combined with the
raiser's own code this pins the layout down rather than the culprit:

- `FUN_000550a0` memsets the whole 0x24-byte event before filling it, so `+0x20`
  reads 0 for *any* event -- `result:00` only confirms the offsets line up, it
  does not identify the producer.
- Exactly one instruction writes the status field: `strb.w r5,[sp,#0x26]` at
  `0x4511e` (struct base is `sp+4`, so `sp+0x26` is `+0x22`).
- `r5` really is the status argument, not something derived: the `cmp r5,#1` at
  `0x450f6` is the decompiled `else if (param_3 == 1)`.

So the receiver reads the right field, the field holds the raiser's third
argument, and yet every static event-5 call site was tagged with a non-2 value
while the client still reported 2. The only reading left is that the raise which
fires is reached through **indirect dispatch** -- a registered callback or
function-pointer table rather than a `bl` -- which static tagging cannot reach.
Patch-and-observe is exhausted here; the next attempt needs a real trace.

Note also that "just force success" is not a shortcut: PSM fix + forced success
together do produce a socket that `connect()` accepts and that stays open (no
EBADF), but **no AAP data ever arrives on it**. The vendor's state machine took
its failure path, so it never routes channel data to the session -- reporting
success does not undo that.

## The actual cause, from mtkbt's own traces

`y2_trace_to_logcat.sh` makes the daemon's internal traces visible (tag
`MTKBTD`), and one connect attempt shows the whole failure -- which is **not an
L2CAP problem at all**:

```
[JSR82][ADP]btadp_jsr82_connect_req: sessionid[220442]
CMGR add handler:0xb79d51e8
[CMGR][CON] CMGR_CreateDataLink=0xb79d51e8
ME_FindRemoteDeviceP: 0xA4,0xFC,0x77,0x86,0x77,0x74,   -> found
[ME][CON] ME_CreateLink : handler=0xb79d5204
ME_FindRemoteDeviceP: 0xA4,0xFC,0x77,0x86,0x77,0x74,   -> found
[JSR82]btadp_jsr82_session_disconnected :id[220442], conn_id[0], identify[8]
[JSR82] btadp_jsr82_session_deinit
```

All of it lands in the same millisecond, with **no HCI traffic in between**, so
nothing is asked of the peer and nothing times out. The session connect goes
straight to `CMGR_CreateDataLink` -> `ME_CreateLink`, i.e. "bring up an ACL
baseband link to this address" -- but an ACL link to the AirPods already exists
and is carrying A2DP. The connect dies on a local state check inside link
creation, long before any L2CAP Connection Request would be built.

That explains every earlier observation at once: why the confirm always carries
2 regardless of which event-5 raiser is tagged (the failure never reaches those
paths), why the PSM fix visibly works on the wire yet changes no outcome (the
request that reaches the air comes from the *stock* profile paths, not ours),
and why forcing success produced a socket with no data (the session was already
deinitialised).

### Confirmed by trace ids, and how far the patches get

Hooking the leveled `kal_trace` helper too (same script logs `MTKID kal id=...`)
names the branch taken. A connect logs **`id=15a`** and neither `0x28d` nor
`0x25e`, which in `ME_CreateLink` is exactly this exit:

```c
InsertTailList(dev + 0xd4, param_1);
if (dev[0xfe] != 3) { dev[0x11a] = 1; return 2; }   // <- taken
```

`2` means "pending, wait for the link". mtkbt's device record is not in state 3
(connected) even though A2DP is streaming, and the JSR82 layer treats the 2 as
failure and tears the session down in the same millisecond. The L2CAP machinery
below keeps running afterwards, which is why a channel still appears on the wire
and then leaks.

`y2_link_state_test.sh` forces that comparison to match (`cmp r0,#3` ->
`cmp r0,r0`). With it, the flow changes completely and now does real work
(~90ms instead of same-millisecond):

```
CMGR Connected
l2cap: remote psm:0x1001 outMode:1 inMode:1
L2Cap: ConnectReq r-psm:0x1001
l2cap conn_rsp result:1        (pending)
l2cap conn_rsp result:0        (success)
LLC_CONFIG_REQ ... handleconfigrsp result:0
l2cap: enter open state        <- the AAP channel is fully open
```

The channel opens, then JSR82 logs trace `0xc83` -- case 5 of its event
dispatcher, the channel-connected notification -- and disconnects the session
anyway. Adding `y2_force_session_ok.sh` on top (which patches exactly that case)
finally yields `msg->result:01` and **`AAP L2CAP connected`** with the session
staying alive rather than dying of `EBADF`, because this time the channel under
it is real.

**Tested with the corrected handshake: still no AAP data.** The socket connects
and stays alive, but nothing is ever received. The likely reason is that the
force-success patch is self-defeating for data: it makes JSR82 *report* the
session connected while its own case-5 path had already decided to disconnect
it, so Java holds a socket whose session context is dead -- writes go nowhere
and no reads arrive, regardless of whether the payloads are right. Testing the
protocol properly needs the session to survive on its own merits, i.e. the real
fix below, not the forced status byte.

**Original note on the payloads.** No ear-detection notifications arrive on the open
channel, so the launcher gains nothing yet. Either the handshake bytes in
`AapService.sessionLoop()` are not what this firmware expects, or the writes
never reach the channel. The payload half of that has since been settled: our
packets were checked against LibrePods and the notification request was wrong
(a byte short, with 0xfe where the fifth mask byte should be 0xff), which is
now fixed along with the missing SET_SPECIFIC_FEATURES and an ack-driven
sequence. So the remaining suspect is the dead session context.

### Next step: the state machine, located

The link-state byte lives at `dev+0xfe` in the ME device record. Its writers were
enumerated (`strb.w rX, [rY, #0xfe]`, note objdump separates mnemonic and
operands with a tab -- a space-only regex silently finds nothing):

```
0x99538: movs r1, #3      0x9953a: strb.w r1, [r5, #0xfe]   <- "connected"
0x991e8: movs r2, #4      0x991ec: strb.w r2, [r4, #0xfe]
0x94b32: movs r3, #0      0x94b34: strb.w r3, [r5, #0xfe]
0x981fa, 0x992c6, 0x99580, 0x99f72, 0x9a5b8: further transitions
```

So the transition to 3 exists and simply never runs for the ACL our AirPods are
already using. Decompile the function containing `0x99538` (it should be the
connection-complete / link-up handler) and find out why: most likely it updates a
different device record than the one `ME_FindRemoteDeviceP` returns to
`ME_CreateLink`, or it is only reached for links this stack itself initiated.
Fixing it there -- so the record honestly reflects an established ACL -- is the
correct change, and it makes `y2_link_state_test.sh`'s blunt compare patch
unnecessary.

Older note: find why `ME_CreateLink` refuses when the link is already up -- the
state byte at `dev+0xfe` is stale for a device whose ACL was established by the other stack.
Forcing the compare is a blunt instrument: `ME_CreateLink` is core Management
Entity code used by every profile, so the proper fix is either to correct that
state when an ACL already exists, or to make the JSR82 caller honour `2` as
"pending" and wait for the callback instead of tearing down. Likely candidates,
in order of cheapness: the JSR82 connect path may need to *reuse* the existing
CMGR handler (there is an `ME_FindRemoteDeviceP` hit right before the teardown,
so the device is found -- the question is what it does with a device already in
`connected` state), or the ACL link's role/state disqualifies a second data-link
request. `CMGR_CreateDataLink` and `ME_CreateLink` are both named symbols, so
this is now a bounded read of two functions rather than a search.

Everything below predates the trace and is kept for the reasoning trail. Two
dead ends found while trying to get tracing working:

- mtkbt's internal traces never reach logcat: both trace helpers end in a sink
  gated off on a `user` build, headed for MTK's mobile-log daemon.
- **Do not** hook those helpers by patching mtkbt's code in-process from the
  proxy (the proxy is loaded into mtkbt, so it is tempting). Tried it: mtkbt
  crashes with `SEGV_ACCERR` and Bluetooth then never finishes enabling. Also
  beware `__builtin___clear_cache` in proxy code — it needs `__clear_cache`,
  which this bionic lacks, so the library fails to load *and takes the audio HAL
  and system_server down with it*. Use the `__ARM_NR_cacheflush` syscall.

### The trace route, researched and ready to build

Enabling MediaTek's own logging is the wrong lever. `mtkbt`'s traces are MAUI
`kal_trace` calls (`FUN_000829ac(level, id, fmt, ...)`) whose ids index a trace
map like `blueangel/btadp_int/include/bluetooth_trc.h` in the BSP dumps, and the
sink ships them over MTK's Catcher transport. The device does run `mobile_log_d`
and `mdlogger` and ships `com.mediatek.mtklogger` (reachable via `*#*#3646633#*#*`,
logs land in `/sdcard/mtklog`), but MobileLog is just logcat capture -- which
these traces never reach -- and the Catcher stream needs MediaTek's trace
database for this exact firmware to decode. Two unknowns chained, for binary
output.

Redirecting the sink to logcat is better, and every address needed is resolved:

- **Patch point.** `FUN_00082714` (file `0x72714`) is the plain-text trace helper:
  it formats the message with its own mini-printf into a stack buffer, then calls
  the sink at file `0x72548` from `0x7283c` (`bl`). Its argument is a struct whose
  `+0x04` holds the pointer to the formatted text (decompiled as
  `local_c0 = &local_d0; local_bc = abStack_a4;`). Patch that one call.
- **Logging is already linked in.** `__android_log_print` has a real PLT stub at
  `0xb720` (ARM state, so reach it with `blx`), verified against its GOT slot
  `0x10cc84` -- and 41 existing call sites already use it, so nothing new is
  needed at link level.
- **Somewhere to put the thunk.** `.text` has no zero run of even 32 bytes, but
  the executable segment's last page does: `LOAD1` ends at `0x105e64` while the
  page maps to `0x106000`, giving **412 bytes of mapped, executable, all-zero
  space** at vaddr `0x105e64` (this PIE maps vaddr == file offset). No program
  header edits, because the loader maps whole pages.
- **Thunk sketch** (Thumb, literals in its own pool inside the cave):
  `ldr r3,[r0,#4]` (text) / `r2 = "%s"` / `r1 = "MTKBT"` / `movs r0,#3` /
  `blx 0xb720` / restore / tail-call the original sink at `0x72548`.

That yields every internal `FUN_00065084` trace in logcat with its real arguments
-- including the JSR82 session paths -- which is exactly what is needed to catch
the indirect dispatch that static tagging could not reach.

**Do not** hook this in-process from the transport proxy instead; that was tried
and crashes mtkbt (see the trap notes below).

Or skip the vendor entirely: the proxy sits on the HCI path and can drive the AAP
handshake on the established channel itself, delivering ear state to the launcher
out-of-band. Sub-second, no vendor cooperation, at the cost of hand-rolled ACL
bookkeeping and controller flow control.

## Why the BLE advert route needs an advertiser lock (not a MAC match)

The obvious question is why the advert path doesn't just match our AirPods' MAC.
It can't: the address we know (`74:77:86:77:FC:A4`) is the *classic BR/EDR*
address, while the proximity advert comes from a rotating BLE random address.
Measured on-device, that address is a **resolvable private address** (top bits
`01`, e.g. `70:E5:10:AA:44:25`), so matching it would need the AirPods' IRK — and
an IRK is only distributed during LE SMP pairing. Our bond is classic-only, so no
IRK exists locally; BT 4.2 cross-transport derivation yields an LE LTK, not the
peer's IRK. Implementing the match itself would be trivial (RPA is
`prand ‖ ah(IRK, prand)`, a few lines of AES), so this is blocked purely on key
acquisition, not on code.

Hence `AapService` follows a single advertiser instead: lock onto one address,
accept only it while the lock is alive, re-lock to the nearest advertiser when it
goes quiet (rotation), and let a clearly closer advertiser steal the lock. The
symptom that forced this: a **single AirPod worn two rooms away** (`status=22`,
one bud "out", `battery=f6` with an unreported bud) was interleaving with ours
and flapping the auto-pause, which reads as random pause/play. A plain RSSI floor
of -70 nowhere near separates them (-54 for that pod against -44 for ours), and
even a relative margin was too tight to be reliable.

An alternative that avoids MTK's session layer entirely: the channel is up and
left open, and the proxy already sits on the HCI path, so it could drive the AAP
handshake itself and hand ear state to the launcher out-of-band. Sub-second and
needs no vendor cooperation, at the cost of doing L2CAP/ACL bookkeeping (and
controller flow control) by hand in the proxy.

## The BLE advertisement route (what actually works)

AirPods continuously broadcast an Apple "proximity pairing" BLE
advertisement — manufacturer data, company `0x004C`, message type `0x07`,
27 bytes — whose first 11 bytes are plaintext (the remaining 16 are
encrypted and rotate every few seconds). Ear state and battery live in the
plaintext head, so no AAP connection, no vendor patch, and no root is
needed. `BluetoothAdapter.startLeScan()` exists since API 18, so Y2's API 19
is fine, and the Y2 reports `android.hardware.bluetooth_le`.

`AapService` scans for it and parses it in `applyAdvert()`. The L2CAP path is
still attempted first (it works on Y1, where the `ps_type` patch applies) and
simply loses the race on Y2; the service no longer stops itself when L2CAP
gives up, since the BLE scan needs it alive.

### Byte layout, derived on-device

Captured by logging raw adverts through a known sequence of pod positions
(both in ears → left out → both out → into the case). Payload indices are
from the `0x07` type byte:

| index | meaning |
|---|---|
| 0–1 | `07 19` — message type and length |
| 3–4 | device model (`27 20` on AirPods Pro 3) |
| 5 | status: ear/primary bits |
| 6 | battery, one nibble per bud, tens of percent, `0xF` = unreported |
| 7 | high nibble charging flags, low nibble case battery |
| 11–26 | encrypted, rotates constantly — ignored |

Status byte (index 5) observed across the sequence:

| pod position | status |
|---|---|
| both in ears | `0x2b` |
| left (primary) out | `0x29` — bit `0x02` cleared |
| both out | `0x21` — bit `0x08` cleared |
| into the case | `0x71`/`0x11`, charge nibble `0x8`→`0x9`→`0xa` |

So bit `0x02` = primary bud in ear, `0x08` = secondary in ear, `0x20` =
primary is the left bud. Which bud counts as "primary" **flips** when one is
stowed, which is why in-case detection reads the charging flags (both buds
charging = both seated) rather than a status bit.

Known rough edge: in-case detection is imprecise during the transition while
only one bud is seated — it reports that bud as merely out-of-ear. Harmless
for auto-pause, which only cares about "not in an ear"; worth tightening only
if `isLikelyStowed()` starts misbehaving.

## Historical: the L2CAP investigation before the wire capture

Everything below predates the HCI snoop above. Its *observations* still hold and
its ruled-out items are still worth reading, but its central conclusion — that
raw L2CAP client connects are refused for every PSM — is wrong: every one of
those probes was really sending PSM 0. Read the correction above first.

## Symptom

`AapService.java`'s raw L2CAP connect to PSM `0x1001` fails immediately,
both with and without auth/encryption:

```
D/AapService: AAP connect attempt failed (auth=false): java.io.IOException: read failed, socket might closed or timeout, read ret: -1
D/AapService: AAP connect attempt failed (auth=true): java.io.IOException: read failed, socket might closed or timeout, read ret: -1
```

Only ~66ms elapses between the two attempts starting (the second only
starts after the first's `connect()` throws) — fast enough to suggest a
local/synchronous rejection rather than a genuine over-the-air round trip
to the AirPods, though not fully conclusive.

Bluetooth *audio* (A2DP/AVRCP) to the same AirPods works fine over the same
link — this is specifically the raw L2CAP AAP channel failing.

**The Y1 RTP fix does nothing on Y2 — don't install it here.** Tried it: the
proxy loads and `libbluetoothdrv_real.so` maps, but `mtk_bt_write` is never
called once while A2DP actively streams, so not a single `BTRTPFIX` line
appears. Y2's media path doesn't go through `libbluetoothdrv.so` at all; the
SBC packetizer lives in `/system/vendor/lib/hw/audio.a2dp.blueangel.so`,
inside `mediaserver`. Y1's hook point simply doesn't exist in this stack.
(Note `libbluetoothdrv.so` *is* mapped by zygote on Y2 via
`libaudio.primary.default.so`, which imports `mtk_bt_op` — so a bad proxy
here is a boot risk, unlike on Y1 where only `mtkbt` loads it.)

Also: if the Y2 goes silent while `[A2DP] a2dp_write count:10240` keeps
repeating in logcat, **check the volume on the AirPods stem first.** AVRCP
absolute volume at zero is indistinguishable from a broken stack at every
layer the device can see — audio routes correctly, bytes flow, wired output
is fine. It cost an afternoon once already.

## Ruled out

**1. The `ps_type` bug the original Y1 `airpods-aap` patch targets does not
apply to Y2.** Confirmed via logcat during a live connect attempt:

```
JBT bt_handle_session_connect_req_cnf parms.ps_type:02
```

`ps_type=2` is exactly the value Y1's patch exists to *force* (Y1 defaults
to `ps_type=1`, which AirPods reject). Y2's stack already does this
correctly, natively. **`libextjsr82.so` (the Y1 patch's target file) does
not exist on Y2 at all** — Y2 uses a different, newer Bluedroid-based stack
(`libbtsession.so`, `libbluetooth_mtk.so`, `libbt-hci.so`), not Y1's older
JSR82 socket layer. The whole Y1 patch approach is moot here; don't try to
port `build_patch.py`'s byte offsets to Y2's `libextjsr82.so` — it isn't
there to patch.

**2. `BluetoothSocket` constructor signature drift** (the thing
[LibrePods](https://github.com/librepods-org/librepods)'s
`BluetoothConnectionManager.kt` guards against by trying 5 different
parameter orderings) is not the issue on Y2. Dumped this build's actual
declared constructors via reflection:

```
BluetoothSocket has 3 declared constructors:
  ctor[0]: (int, int, boolean, boolean, BluetoothDevice, int, ParcelUuid)
  ctor[1]: (int, int, boolean, boolean, String, int)
  ctor[2]: (BluetoothSocket)
```

`ctor[0]` is exactly the 7-arg layout `AapService.tryConnect()` already
calls, same types, same order. No mismatch.

**3. `mtkbt`'s session-monitor loop logging
`"[Session]no any more session is in list"` right after our connect
attempt is a coincidence, not a rejection.** Decompiled the containing
function (`FUN_00013a94` in `libbtsession.so`, entry `0x13a94`) — it's a
generic background poll-loop that periodically walks every active session
to rebuild an fd list for `poll()`. That log line just means "reached the
end of the session list this poll cycle" (mislabeled at error level in the
binary); it fires on every cycle regardless of what we're doing, and
happened to land near our attempt in the log only because that thread polls
frequently.

**4. `jbt_check_already_connect_chnl_and_addr` (a duplicate-connection
guard) does not block us.** It's gated by `if (param_4 == 1)` in
`btmtk_jsr82_session_connect_req` (`param_4` is `ps_type`); since our
`ps_type == 2`, that branch is skipped entirely via short-circuit
evaluation. (This function decompiles as a trivial `return 0;` stub anyway
— possibly disabled/dead code in this build, not investigated further
since it's unreachable for us regardless.)

## Traced code path (confirmed clean, no rejection found)

Full chain from the Java `connect()` call down through two real vendor
binaries, with no artificial PSM whitelist or rejection found anywhere in
it:

```
AapService.tryConnect()  [Java, reflection into hidden BluetoothSocket ctor]
  -> BluetoothSocket.connect()
    -> com.android.bluetooth (JSR82 socket layer, tag "[JSR82][JBT]")
      -> libbtsession.so: session_connect / on_session_connected (Unix-domain
         socket multiplexer, group "bt.session.default")
        -> /system/vendor/lib/hw/bluetooth.blueangel.so:
             btmtk_jsr82_session_connect_req  (0x373b0)
               -> jbt_allocate_one_available_session_entry (0x36824)
               -> jbt_allocate_one_available_subsession_entry (0x3685c)
               -> jbt_session_attach (0x36e04) -> bt_session_connect / bt_session_get_fd
               -> jbt_session_general_connect (0x36ab8)
                    packs session fields into a struct, calls:
                    JSR82_SendMessage(0xa38, 0, payload, 0x30)  (0x36fc4)
                      -> btmtk_sendmsg(0xd, payload, len)   <- request leaves this HAL here
```

`btmtk_sendmsg(0xd, ...)`'s return value determines whether
`btmtk_jsr82_session_connect_req` reports success (`return 1`) or failure
(`return 0`, which is what we observe) — but `btmtk_sendmsg` itself is
where the trace currently stops. The actual accept/reject decision is
**below** this vendor HAL, in whatever processes command type `0xd`.

## The failure is not AirPods-specific: every PSM is refused

> **Superseded.** The probes below all sent PSM 0 on the wire regardless of the
> PSM asked for, so they measured one bug, not a per-PSM refusal. Keep the
> conclusion "not AirPods-specific" (true), drop "non-functional for every PSM"
> (false — see the wire capture above).

Measured directly by making the probe PSM settable and pointing it at PSMs
that cannot legitimately be refused:

| PSM | what it is | result |
|---|---|---|
| `0x0001` | SDP — every Bluetooth device answers this | `msg->result:02` |
| `0x0003` | RFCOMM | `msg->result:02` |
| `0x1001` | AAP | `msg->result:02` |

Caveat on how far this was verified: every one of these probes ran against
the AirPods, plus one unpaired device that may simply have been unreachable.
The evidence is still strong — the AirPods had an active ACL link (A2DP was
streaming at the time) and SDP is mandatory on every BR/EDR device, so PSM
`0x0001` had no legitimate reason to refuse — but it was never confirmed
against a second *bonded*, known-reachable peer. If you pick this up and have
one paired, that is the cheapest way to make the conclusion airtight. (Note
that pairing a phone to a Y2 makes the launcher's audio-reconnect watchdog
retry A2DP against it forever, since it treats any bonded device as an audio
sink.)

**SDP failing identically proves the AirPods are not refusing anything** —
Y2's raw L2CAP *client* path is non-functional for every PSM. So
`msg->result:02` is an internal MTK status, not the L2CAP spec's
"connection refused - PSM not supported" (`0x0002`), and chasing this as an
Apple/AAP compatibility problem is a dead end. Whatever is broken is broken
for all raw L2CAP client connects on this stack.

Two corrections to the original notes above, both from watching a live
attempt with full logcat rather than the `AapService` tag alone:

```
[JSR82][JBT] JBT jbt_session_connect_req
[BTSOCK]btsock_connect ret[1], fd[87]              <- socket layer ACCEPTS
[JSR82][JBT] bt_handle_session_connect_req_cnf parms.ps_type:02
[JSR82][JBT] bt_handle_session_connect_req_cnf msg->result:02   <- refused here
```

- `btsock_connect` returns **1 (success)**; nothing rejects us locally, and
  the refusal arrives later as a *confirmation message*. The reject code does
  surface in logcat — it is not buried below the HAL unreachably.
- The gap between request and confirmation is **~28ms**, not the ~66ms the
  "Symptom" section measures between the two *attempts*. 28ms is a plausible
  round trip on an active ACL link, so the original inference that this looks
  like a synchronous local rejection is not supported.

Also worth ruling out for whoever picks this up: Y2's Bluedroid **does** ship
an L2CAP socket layer (`bt-l2cap`, `btsock_l2cap`, `BTA_JvL2capConnect` all
present in `/system/lib/hw/bluetooth.default.so`), unlike stock AOSP 4.4
where `btsock_connect` implements RFCOMM only. So "Android 4.4 has no L2CAP
sockets" is *not* the explanation here either.

## Where the trace stops, and why

`/system/lib/libbluetooth_jni.so` (60KB, the JNI bridge `com.android.bluetooth`
loads) contains none of the expected core L2CAP strings
(`L2CA_ConnectReq`, `L2CA_Register`, etc.). Searched **every** `.so` in
`/system/lib` on-device for `L2CA_ConnectReq` (the standard Bluedroid L2CAP
function name) — zero matches anywhere. Two explanations, both meaning the
next step needs full disassembly rather than string search:

- The core Bluedroid stack's debug strings are stripped in this build, or
- It's compiled directly into `/system/bin/mtkbt` (no separate `bluetoothd`
  process exists on this device — checked `ps`, confirmed) without the
  function name surviving as a readable string.

Finding which binary actually owns command `0xd`'s handling, and what
happens to it, is genuinely a multi-day reverse-engineering task (disassemble
`mtkbt` and/or the remaining unexamined `.so`s for the `0xd`/L2CAP dispatch
table), not a quick continuation of this session's approach.

## Tooling notes for whoever continues

- Ghidra 12.1.2 is installed via Homebrew (`/opt/homebrew/Cellar/ghidra`).
  `analyzeHeadless` needs `JAVA_HOME` pointed at a JDK 21 — Android
  Studio's bundled JBR works
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Headless post-scripts must be **Java** (`.java`), not Python: this build
  fails Python scripts with "Ghidra was not started with PyGhidra". Import once
  without `-deleteProject`, then re-run repeatedly with
  `-process <file> -noanalysis -postScript X.java` (seconds instead of minutes
  per query).
- For HCI-level questions, the transport snoop (`SNOOP=1` in
  `scripts/airpods-rtpfix/build.sh`) beats any amount of disassembly — it is
  what turned a "multi-day RE task" into a one-line root cause here. Note
  `mtkbt`'s own internal logs do **not** reach logcat; only the client-side
  `[JSR82]` lines from `com.android.bluetooth` do.
- When importing a vendor `.so` for decompilation, **pull its dependency
  libraries too** (`libcutils.so`, `libutils.so`, `libc.so`, etc. — check
  `grep -l <libname> /system/bin/* /system/lib/*` for consumers, and each
  binary's own needed-library list) and pass
  `-librarySearchPaths <dir>` on import, or dozens of functions fail to
  decompile with `EXTERNAL block` errors.
- ARM PIC pointer loads (`add rX, pc`) to a data table often don't resolve
  into named symbol references in the decompiled C output even with full
  library resolution — the decompiler shows raw `DAT_xxx + 0xyyy` arithmetic
  instead. To find the real reference, dump every `Instruction`'s
  `getReferencesFrom()` at the **raw disassembly level** (not decompiled
  text) and grep for the target address — that catches references the
  decompiler's symbolic propagation misses. See `add r1,pc` at `0x13b74`
  in `libbtsession.so` as the working example of this (resolved via
  `getReferencesFrom()`, invisible in decompiled C).
- Enabling Android's standard `persist.bluetooth.btsnoopenable` HCI snoop
  property (plus a reboot) did **not** produce a `btsnoop_hci.log` on this
  firmware — `/data/misc/bluetooth/` and `/data/misc/bluetoothd/` stayed
  empty. This vendor's Bluedroid build doesn't wire up the standard snoop
  hook the usual way; don't rely on it without confirming it actually
  writes a file first.

## Files pulled from the device (not committed — device-specific binaries)

For reference, these were pulled during the investigation (regenerate with
`adb pull` if picking this back up):
`/system/lib/libbtsession.so`, `/system/vendor/lib/hw/bluetooth.blueangel.so`,
`/system/lib/libbluetooth_jni.so`, plus dependency libs
(`libcutils.so`, `libutils.so`, `libextsys.so`, `libc.so`, `libstdc++.so`,
`libm.so`).

## The ACL connection-complete handler, and what it means

`FUN_000a94c8` (file `0x994c8`) is the handler containing the `state = 3` store.
Its transition is correct and unconditional:

```c
if (*(char *)(iVar5 + 0x29) == '\0') {     // HCI status == success
    *(undefined1 *)(param_1 + 0xfe) = 3;   // state = CONNECTED
} else {
    *(undefined1 *)(param_1 + 0xfe) = 4;   // state = failed
}
```

So the AirPods' record is not having its state *reset* -- it never passes through
this handler at all, meaning the state was never *set*. That fits the two-stack
layout on this device: bluedroid + `bluetooth.blueangel.so` own the A2DP ACL
while mtkbt relays HCI for it, so mtkbt's ME layer never sees a
connection-complete for that link.

If that holds, "repair the stale state" is the wrong framing: there is no ME link
object to repair, and writing 3 into the record only lets `ME_CreateLink` proceed
against a link ME does not own -- which is exactly what the compare patch does,
and it does get the channel open. The honest options:

1. **Adopt the existing ACL.** Find where ME keeps the connection handle and
   populate the record from the live link instead of creating one. Correct, and
   the most work.
2. **Narrow the compare patch to `ps_type == 2`** (raw L2CAP) so profiles that
   legitimately need a new link are unaffected. Pragmatic, small, testable.
3. **Verify the premise first.** Install the trace patch and connect audio: if no
   connection-complete (trace id `0x9d`, `FUN_000a94c8`'s neighbourhood) reaches
   mtkbt's ME for that link, the two-stack explanation is confirmed.

Option 3 is cheap and decides between 1 and 2.

### Option 3 result: ME is not blind to the ACL

Ran the trace patch alone and connected audio. ME clearly *does* process the
link: `MeCallLinkHandlers` three times, plus `MeDevEventHandler 9` and `99`. So
the "mtkbt's ME never sees this ACL" theory is **wrong** -- discard it.

What is missing is narrower: no `kal id=9d`, and the AirPods' BR/EDR address
(`0xA4,0xFC,0x77,...`) never appears in the whole capture -- while the log is
full of their *BLE* advert traffic (`BTEVENT_INQUIRY_RESULT`, `BLE adv report of
dual mode device`, `devType is LE and COD is 0`).

New hypothesis, better supported: there are two device records for the AirPods --
one created/refreshed by BLE advert handling, one for the BR/EDR link -- and
`ME_FindRemoteDeviceP` inside `ME_CreateLink` matches the LE one, which never has
a BR/EDR link state and so never equals 3. Note the launcher itself runs a
continuous LE scan for ear detection, which keeps that LE record constantly
refreshed, so this is self-inflicted and testable.

Next, in order of cheapness:

1. Log the record pointer and `dev+0xfe` from both sites -- `ME_CreateLink`'s
   lookup and the connection-complete handler `FUN_000a94c8` -- and compare. If
   the pointers differ, the duplicate-record theory is confirmed outright.
2. Stop the launcher's BLE scan (or run AapService with ear detection disabled)
   and retry the L2CAP connect. If it then succeeds unpatched, the LE scan is
   what poisons the lookup, and the fix may be as simple as not scanning while
   connecting.

### Test 2 result: the LE scan is not the cause either

Ran the connect on **completely stock firmware** with the launcher's advert scan
disabled (no `AAP-BLE` lines in the capture confirm it never started). Result was
unchanged: `msg->result:02` on every attempt.

So the LE-record theory is disproven as well. Neither "ME never sees the ACL" nor
"our own LE scan poisons the device lookup" survives contact with the device.
What is established, and worth not re-testing:

- ME *does* process the ACL (`MeCallLinkHandlers`, `MeDevEventHandler 9`/`99`).
- The connection-complete handler sets state 3 unconditionally on success.
- Yet `ME_CreateLink` finds a record whose state is not 3, with or without any
  LE scanning by us.

The remaining way to settle it is direct rather than inferential: log the record
pointer and the state byte from both sites -- `ME_FindRemoteDeviceP`'s return
inside `ME_CreateLink`, and `param_1` in `FUN_000a94c8` -- and compare them for
the same device. Same pointer means the state is being changed between link-up
and our connect (look for the other `dev+0xfe` writers, e.g. the `= 4` store at
`0x991ec`); different pointers means duplicate records after all, just not caused
by our scanning. The trace thunk already in `y2_trace_to_logcat.sh` is the
vehicle: extend it to log `r0`/the record pointer at those two call sites.

## What the stack actually is (and why return 2 is not an error)

The symbol names give it away: `ME_CreateLink`, `CMGR_CreateDataLink`,
`ME_FindRemoteDeviceP`, `BTEVENT_*`, `MeCallLinkHandlers` are **Extended Systems
"Blue SDK"** (later OpenSynergy) naming, not MediaTek's own. blueangel/mtkbt is a
derivative of that commercial stack, which is why no MTK BSP dump contains this
source -- searches for these symbols return nothing because the code is licensed
from a third party.

That matters because Blue SDK's conventions explain the values we reverse
engineered:

- `BtStatus`: `BT_STATUS_SUCCESS = 0`, `BT_STATUS_FAILED = 1`,
  **`BT_STATUS_PENDING = 2`**.
- `BtDeviceState`: `BDS_DISCONNECTED = 0`, `BDS_OUTCONNECT = 1`,
  `BDS_INCONNECT = 2`, **`BDS_CONNECTED = 3`**, `BDS_DISCONNECTING = 4`.

So `ME_CreateLink` returning **2 is not a failure at all** -- it is
`BT_STATUS_PENDING`, the documented "link is being established, expect a
callback" return. The state byte at `dev+0xfe` is `BtDeviceState`, and the
handler we decompiled setting 3/4 is setting `BDS_CONNECTED`/`BDS_DISCONNECTING`
exactly as the SDK intends.

**This relocates the bug.** ME is behaving correctly; the defect is in
MediaTek's JSR82 ADP layer, which treats `BT_STATUS_PENDING` as fatal and tears
the session down instead of waiting for the link callback. It also explains why
forcing the state compare "works" (it converts a pending link into an immediate
success) and why forcing the session status produced a socket with no data (the
session was already deinitialised).

The correct fix is therefore in the JSR82 connect path, not in ME: on
`BT_STATUS_PENDING`, keep the session and wait for the connect callback that ME
will deliver.

> **Superseded.** This paragraph used to name `btadp_jsr82_connect_req`
> (`FUN_0007bd84`, file `0x6bd84`) as the place that consumes the return and
> performs the teardown. Disassembly says otherwise -- see "Where the teardown
> actually lives" below. Do not spend another session on `0x6bd84`.

Useful for whoever continues: Blue SDK headers (`me.h`, `bttypes.h`,
`conmgr.h`) circulate in various vendor SDK trees and document these enums and
the callback contract in full. Matching against them beats further guessing at
struct offsets.

## Where the teardown actually lives

`btadp_jsr82_connect_req` (`0x6bd84`) consumes no status at all. Read end to
end it logs the session id, looks up or allocates a session record, copies the
request into it, and tail-calls `0x6c4a8` -- which only marshals a message and
posts it via `0x4686c`. Nothing there inspects a `BtStatus`, so there is no
`return 2` to intercept and no teardown to bypass. Its only failure tail
(`0x6be2e`, the hardcoded `strb #2` into the confirm at `0x6be5a`) is the site
the earlier probe already tagged and ruled out.

The teardown is `btadp_jsr82_session_disconnected` at **`0x6cac4`** -- it logs
the `[JSR82]btadp_jsr82_session_disconnected` line, writes state 4 into the
record, and calls `bt_session_destroy`. It has exactly two callers:

- **`0x6cf54`**, inside the handler at **`0x6cecc`** (trace id `0xc83`, the
  channel-connect result). This is the one that matters: it reads the status
  byte at `event+0x22`, calls the success path `0x6c970` only when it is 1, and
  otherwise calls the teardown -- then builds the `0xa39` confirm copying that
  same byte at `0x6cf8e`. That is precisely the shape `y2_force_session_ok.sh`
  patches, and it is why `msg->result:02` and the disconnect always arrive
  together.
- `0x6d012`, in the handler at `0x6cfac` (trace id `0xc7b`), which sends a
  `0xa45` confirm instead -- not the message the client reports.

Nothing branches into `0x6cee0..0x6cee5`, so that pair of instructions is a safe
hook site, and `r4` already holds the event pointer there.

So the open question is unchanged from "Ruled out: every event-5 raiser": which
indirect caller raises the event carrying status 2. `y2_evt5_caller_trace.sh`
answers it by logging the handler's caller LR, the status byte and the session
id under tag `MTKEVT5`.

### Traps found while setting that up

- **The revert backups go stale.** Every patch script creates its backup with
  `[ -f $BACKUP ] || cp`, so a backup made in an earlier session is never
  refreshed -- `/system/bin/mtkbt.stock.trace` was still holding an old
  *patched* build (`bf9c69...`) while true stock is `1737c6d3...`
  (`build/mtkbt_stock`). Reverting through the script would silently have
  installed the old patched binary. **Always `md5` a backup against
  `build/mtkbt_stock` before trusting a `--revert`.**
- **The cave is more crowded than the comments imply.** The second trace thunk
  at `0x105e94` is 56 bytes, so it runs to `0x105ecc`; `0x105ec8` is not free.
  `y2_evt5_caller_trace.sh` uses `0x105ed0`. The zero-check in each script
  catches this, but only if the new thunk is added after the trace one.
- **`AapService` will not attempt a connect at all if the AirPods were not
  already connected when it started.** `runLoop` counts `bootstrapFailures` and
  `break`s out for good after `MAX_BOOTSTRAP_ATTEMPTS`; the service then stays
  alive purely for the BLE scan, so connecting the AirPods later produces
  nothing. Any test of the L2CAP path must (re)start the service *after* the
  ACL link is up -- `am force-stop` plus restarting `MainActivity` is not
  enough, because the service is started from the `ACL_CONNECTED` receiver, not
  by the activity. Toggling the AirPods off and on again is the reliable
  trigger. The first attempt at this trace produced an empty log for exactly
  this reason.
- **Do not leave the trace patch installed while listening to music.** With the
  gates open mtkbt logs tens of thousands of lines per minute; audio stopped
  during this run. The force-stop of the launcher is a confounder so this is not
  proven to be the cause, but the trace build is a diagnostic, not something to
  leave on.

## The real verdict site, found by tracing (supersedes everything above it)

Two hooks settled this: `y2_evt5_caller_trace.sh` (logs the event-5 handler's
caller, status byte and event id) and `y2_raise_caller_trace.sh` (logs who calls
the raiser `0x450a0`, gated on event id 5). Run with `y2_trace_to_logcat.sh` so
the vendor's own text traces are visible alongside.

**Without the PSM fix** the traces show the failure is not internal at all:

```
L2Cap: ProcessRsp opcode:0x3() psm:0x0
l2cap conn_rsp result:2          <- the peer refusing PSM 0
L2EVENT_DISCONNECTED
remDev->state:3                  <- the device record IS BDS_CONNECTED
kal id=c49 -> c83 -> evt5 st=2 -> btadp_jsr82_session_disconnected
```

So the `msg->result:02` in that configuration is the AirPods' own
"Refused – PSM not supported" relayed upward. Note `remDev->state:3`: the
`ME_CreateLink` "returns PENDING because the device record is not in state 3"
theory does **not** reproduce here. It was a real observation in an earlier
session but it is not the steady-state failure, and it is not what needs fixing.

**With the PSM fix** the channel opens properly --
`ConnectReq r-psm:0x1001`, `conn_rsp result:1` (pending), `conn_rsp result:0`
(success), config exchange both ways, `l2cap: enter open state` -- and the
session is *still* torn down. The raiser hook names the culprit exactly:

```
MTKRAIS  raise lr=0x47f04 ev=5 st=2
MTKEVT5  evt5  lr=0x4518a st=2 ev=5
[JSR82]btadp_jsr82_session_disconnected
```

### Why static tagging could never find it

`0x47f04` is the return of the `bl` at `0x47f00`, inside JSR82's L2CAP callback
`0x47b38`. That call site *looks* like an event-4 raiser -- its own preceding
instructions are `movs r1,#4 ; mov r2,r4`. But a branch from `0x47ed6` jumps
**into** it with different arguments already loaded:

```
47eb0: ldrb.w r1, [r8, #0x84]   ; r8 = L2CAP channel record
47eb4: cbnz   r1, 0x47ed8       ; inbound  -> trace c4d, raise(ev=4, status=1)
47eb6: ...                      ; outbound -> trace c4c
47eca: movs   r2, #1
47ecc: strb.w r2, [r7, #0x2f4]
47ed0: mov    r0, r5
47ed2: movs   r1, #5            ; event 5
47ed4: movs   r2, #2            ; status 2  <- the fatal literal
47ed6: b      0x47f00           ; jumps into the "event 4" call site
47f00: bl     0x450a0
```

That is why every earlier probe run came back "not it": the tagging patched
literals adjacent to each `bl`, and this status literal sits 44 bytes away in a
different basic block. The site was never a distinct raiser to tag.

### What the byte at channel+0x84 means

`0x8e9f0` maps a CID to its channel record (`base + 0x198*(cid-0x40) + 0x50`).
Two places write `+0x84`:

- `0x8941e` writes **0**, in the function logging
  `l2cap: remote psm:0x%x outMode:%d inMode:%d` -- the *outgoing* connect setup.
- `0x8cdd6` writes **1**, in the `L2CapState_CLOSED event:%d` handler, on the
  events that mean the peer opened the channel.

So `+0x84` is an **inbound flag**, and JSR82's callback reports success only for
channels the peer initiated. A client-initiated channel is told status 2 no
matter how cleanly it opened.

Crucially, this whole block is only entered on a *successful* connect:
`0x47e6e` checks the result halfword at `event+0x02` and diverts to the error
trace `c4e` otherwise. There is no path here that distinguishes success from
failure -- the status is simply hardcoded.

**Conclusion: MediaTek's JSR82 session layer never implemented the client side
of L2CAP.** It completes accepted channels and hardcodes a failure for the ones
it opens itself. That is one coherent bug that explains the PSM-0 request, the
relayed refusal, the leaked channels, and the `result:02` that survived every
downstream patch.

### The candidate fix, and why it is not yet proven

`y2_jsr82_outbound_fix.sh` changes the status literal only:

```
0x47ed4: 2202 (movs r2,#2)  ->  2201 (movs r2,#1)
```

Event 5 is already the right message -- it is the connect *confirm* for our own
request. Forcing the `cbnz` instead would be wrong: event 4 is the connect
*indication* and its handler `0x6ce0c` looks up a listening session, which an
outgoing connect does not have.

**Not yet verified end to end.** After installing it, `init.svc.mtkbt` came up
`stopped` and the daemon never started, so no connect was attempted; the device
was restored to stock before the cause was found. The binary itself is fine --
launched by hand from a shell it runs and stays up -- so the two-byte change is
not what stops it. Most likely the daemon simply was not started by the stack
that boot; check `getprop init.svc.mtkbt` and toggle Bluetooth before concluding
anything. **Re-test this before trusting or discarding the patch.**

### Corrections to earlier sections

- The `ME_CreateLink` / `BT_STATUS_PENDING` theory is not the live failure
  (`remDev->state:3` in every traced run). Leave `y2_link_state_test.sh` alone.
- The doc's "the status byte lands at +0x22, confirmed in its decompiled case 5"
  is right; an intermediate reading here that called it wrong was itself wrong,
  caused by misreading objdump's halfword-swapped display of a `tbb` table.
  When decoding an inline jump table, read the bytes out of the file, not out of
  the disassembly listing.
- `AapService` gives up permanently after `MAX_BOOTSTRAP_ATTEMPTS`; connect
  attempts only happen if the AirPods are toggled *after* the daemon is up.

## The trace ids are decodable — and they overturn the section above

MediaTek's `bluetooth_trc.h` is public in MT6577 BSP dumps (e.g.
`andr3jx/MTK6577`, path
`mediatek/source/external/bluetooth/blueangel/btadp_int/include/bluetooth_trc.h`).
Its `TRC_MSG(...)` list is the enum the leveled `kal_trace` helper indexes, so
the `MTKID kal id=<hex>` lines that `y2_trace_to_logcat.sh` produces turn back
into the vendor's own sentences. A copy is checked in next to
`decode_trace_ids.py`.

Calibrated on three ids whose position is unambiguous: `0x630` =
`L2CapState_OPEN() Cid=0x%x, event=0x%x` (logged exactly at
`l2cap: enter open state`), `0x70f` = `SDP Client: Sending query packet`
(immediately before the SDP request appears on the wire), `0x636` =
`L2Cap_GetSysPkt`. The header is MT6577 and the device is MT6582, so a small
index offset is possible — check a decode's `%` count against the call site's
argument count before relying on it.

Decoding the ids seen in the failing connect:

| id | text |
|---|---|
| `0xc44` | `JSR82 L2CAP Client connected inx=%d` |
| `0xc49` | `JSR82 Client Cmgr Callback: con_id=%d,event=%d,status=%d` |
| `0xc4b` | `JSR82 LINK CON CNF then Try Open RFChnl` |
| `0xc4c` | `JSR82 LINK CON CNF: Get L2CAP PSM Index;%02x` |
| `0xc4d` | `JSR82 LINK CON CNF then Try Open L2cap Chnl with cid=%04X` |

So `0x47b38` is not a generic channel callback: it is JSR82's **client**
connect path — the CMGR link-connected confirm that decides whether to open an
RFCOMM or an L2CAP channel. The branch this file called "inbound vs outbound"
sits between "Get L2CAP PSM Index" and "Try Open L2cap Chnl with cid", which is
a PSM-table lookup, not a direction test.

**That makes the previous section's conclusion unsafe.** MediaTek did *not*
omit the client side. The public header
`blueangel/btcore/btprofiles/include/jsr82_session.h` shows the whole designed
path, including a CLIENT/SERVER discriminator added specifically to tell the two
apart on the same PSM:

```c
typedef enum { BT_JSR82_SESSION_CLIENT = 0, BT_JSR82_SESSION_SERVER } bt_jsr82_session_type;
void     bt_jsr82_HandleSessionApL2capConnectReq(bt_jsr82_connect_req_struct *ptr);
U8       bt_jsr82_get_L2capPSMIndex(U16 channel, U16 mtu, U8 security_level,
                                    bt_jsr82_session_type client_server);
BtStatus bt_jsr82_AddCreateL2capToContext(...);   /* client-side channel */
BtStatus bt_jsr82_AddNewL2capToContext(...);      /* server-side channel */
```

and `bluetooth_trc.h` carries the matching failure messages, including
`BT_JSR82_L2CAP_CON_REQ empty Channel find` and
`BT_JSR82_L2CAP_CON_REQ:open channel failed`.

**New prime suspect:** `bt_jsr82_get_L2capPSMIndex` (very likely the
`bl 0x45628` whose result is what `0xc4c` prints) fails to find a CLIENT-role
PSM entry for `0x1001`, so the client channel can never be attached to a
context. Verify by logging that return value — the `%02x` in the trace is
already the answer, so decoding one more captured run may settle it without a
new patch.

**Do not use `y2_jsr82_outbound_fix.sh`.** Beyond being built on the wrong
model, it correlates with `mtkbt` failing to start at boot (`init.svc.mtkbt`
`stopped` on two separate boots, running again as soon as stock is restored),
which a two-byte `movs` literal should not cause and which is unexplained.

### Other corrections from the decode

- `0x15a` is `MeSec: Starting Set Connection Encryption command`, **not** an
  `ME_CreateLink` exit. The earlier reading of the `ME_CreateLink` branch ids
  was guesswork and should be re-derived with the decoder before reuse.
- `mtkbt` is an `oneshot` init service with no `disabled` flag: it starts once
  at boot and init never restarts it. If Bluetooth is off at boot it exits and
  stays gone, and `service call bluetooth_manager 8` turns Bluetooth off
  *persistently*. That, not any patch, is why the daemon was missing after
  several test boots — check `settings get global bluetooth_on` before blaming
  a binary.

### The MTK JNI RFCOMM bug (probably not our path, recorded anyway)

`android_server_BluetoothSocketService.cpp` in the MT6582 source hardcodes
RFCOMM in the client connect while `initSocketNative` and `bindListenNative`
handle `ps_type` correctly:

```c
bConnectResult = btmtk_jsr82_session_connect_req(..., JSR82_SESSION_PS_RFCOMM, ...);
```

On a device using that JNI, an L2CAP client connect never reaches
`bt_jsr82_HandleSessionApL2capConnectReq` at all. The Y2 goes through
bluedroid's `btsock` glue instead and its traces show `parms.ps_type:02`
(L2CAP), so this is almost certainly not the Y2's bug — but it is exactly the
Y1-style one-value defect and worth checking if this work is ever ported.

## Reading the decoded connect: the PSM lookup is fine

`y2_trace_to_logcat.sh`'s id thunk now logs the trace's first two arguments as
well as the id (`kal id=%x a=%x b=%x`; `a` is the format-string pointer, `b` is
the first real argument), so `decode_trace_ids.py` produces the vendor's own
narration of a failing connect:

```
c44  JSR82 L2CAP Client connected inx=%d
c4b  JSR82 LINK CON CNF then Try Open RFChnl
c30  JSR82CheckAndDisconnectAclNo(): L2CAP con req is ongoing
c32  bt_jsr82_ACLCheckDisconnectTimer(): Still has pending_conreq_no=%d      b=5
c92  BT_JSR82_Disable_Service Deregister channel :%02x
c1d  bt_jsr82_get_L2capPSMIndex():Find allocated L2CAP PSM=%d and index:%02x  b=0
c1e  bt_jsr82_get_L2capPSMIndex(): find empty inx=%02x, RegisterPsm status:%02x
c1d  ... b=1        c1e ...
c1d  ... b=2        c1e ...
c1d  ... b=3        c1e ...
c1f  bt_jsr82_free_L2capPSMIndex:%08x, %d                                     b=3
c4c  JSR82 LINK CON CNF: Get L2CAP PSM Index;%02x                             b=3
c83  (session teardown)
```

**The PSM-index hypothesis is dead.** `bt_jsr82_get_L2capPSMIndex` walks the
table (`JSR82_MAX_PSM_NO = 10`), finds its entries, and `0xc4c` reports index
**3** — a perfectly valid slot. Nothing fails to allocate.

It also corrects an identification made earlier in this file: `0x45628` is not
the index lookup. It logs `0xc92` at its own entry, so it is
`BT_JSR82_Disable_Service` — deregister-channel plus `free_L2capPSMIndex`. In
other words the whole `0xc4c` branch is the **cleanup path**: by the time it
runs the decision to fail has already been taken, it releases the PSM entry and
then raises event 5 with status 2.

So the verdict really is the branch at `0x47eb4` on `[channel + 0x84]`, one
step earlier, exactly where this file placed it — but the branch is
"proceed to *Try Open L2cap Chnl with cid*" (`0xc4d`) versus "tear down", not
"inbound versus outbound success". The remaining question is narrow and
concrete: **what is supposed to set `channel+0x84` for a client channel, and
why is it still 0 when the channel has already reached L2CAP open state?**

### What the headers say about the client role

From the public MTK headers and the Blue SDK `l2cap.h` (see the research links
above):

- `BT_JSR82_L2CAP_PSM_struct_t` carries `used`, `used_no` (a refcount) and
  `client_server`, and the get/free pair is
  `bt_jsr82_get_L2capPSMIndex(channel, mtu, security_level, client_server)` /
  `bt_jsr82_free_L2capPSMIndex(channel, security_level, client_server)`. The
  free takes no `mtu`, so identity is `(channel, security, role)` and `mtu` is
  only a creation-time value written into `L2capPsm.localMtu` — the mtu smuggle
  is therefore *not* corrupting the lookup.
- Blue SDK requires a registered `L2capPsm` even for a purely outgoing channel,
  and provides `#define BT_CLIENT_ONLY_PSM 0x0000` for exactly that — "PSMs of
  this type cannot receive a connection. Only clients establishing outbound
  L2CAP connections can use it." MediaTek's context has a matching
  `L2capPsm dummyL2capPsm` with a `BTJSR82_L2CapDummyCallback`.
- `L2CAP_ConnectReq(protocol, psm, ...)` takes the remote PSM as its own
  argument; `mtu` never influences which PSM is dialed.

### A cleaner alternative to the mtu smuggle (untested)

Reading the request builder at `0x6bd84`, the message layout is `+0x04` bdaddr,
`+0x0a` ps_type, `+0x0c` mtu, `+0x0e` channel, `+0x10` identify, `+0x14`
security. Nothing ever reads `+0x0e` — that is the dropped channel this file
identified at the very beginning. The current fix instead hijacks the mtu field,
which leaves `ctx+0x20` zero and sets `localMtu` to 4097 against a JSR82 layer
whose `JSR82_SESSION_PS_L2CAP_MTU` is 339.

So a tidier patch is to read the field that was meant to carry it:

```
0x6be12: ldrh r0,[r4,#0xc]  ->  ldrh r0,[r4,#0xe]     (ctx+0x26 := msg->channel)
```

which keeps the existing `0x6c4e0` patch and makes the HAL patch unnecessary.
It does not by itself fix `channel+0x84`, so it is a tidy-up, not the fix —
worth doing only alongside whatever resolves the real gate.

## The decoder needs an offset — and with it, the client path reads clearly

The MT6577 header and the MT6582 firmware do not share one enum alignment:
eight entries were inserted somewhere between the SDP block and the JSR82 block.
Ids below roughly `0x800` decode at face value (that is why `0x630` and `0x70f`
calibrated perfectly), but **ids in the JSR82 block need `-8`**. Decoding them
at face value reads plausibly and is wrong; that is what produced the previous
section's "the PSM index is fine, `0x45628` is `Disable_Service`" reading.
`decode_trace_ids.py` now takes an offset argument.

The `-8` alignment is pinned by three independent anchors:

- `0x47b74` passes four arguments, and the only nearby message taking four is
  `JSR82 L2CAP Callback: session_inx=%d,l2ChnlId=%d,con_id=%d, event=%d`. So
  **`0x47b38` is `BTJSR82_L2capCallback`**.
- `0x47b9a` sits on the branch taken when the session index exceeds `0x13`, and
  decodes to `JSR82 L2CAP Callback: NO matched index in context`.
- `0x47f20` sits on the branch taken when the result halfword at `event+0x02`
  is non-zero, and decodes to `JSR82 L2Cap Open Chnl failed`.

Re-decoded, the failing connect is:

```
c3c  JSR82 L2CAP Callback: session_inx,l2ChnlId,con_id,event   (entry)
c43  JSR82 L2CAP CONNECTED with chnl=%08X
c8a  bt_jsr82_SearchL2capContext                               (0x45628)
c15  bt_jsr82_SearchL2capContext():inx,status,ps_type,chnl,cli_srv_type
c16  bt_jsr82_SearchL2capContext():inx,l2cap_con_state,l2capCid
c17  bt_jsr82_SearchL2capContext():jsr82 find l2cap id,l2capCid
c44  JSR82 L2CAP Client connected inx=%d                       b=3
c7b  BT_JSR82_SessionApConnectCfn
```

So `0x45628` is `bt_jsr82_SearchL2capContext`, not `BT_JSR82_Disable_Service`,
and the branch reads:

```
47eb4: cbnz r1, 0x47ed8   ; +0x84 != 0 -> "JSR82 L2Cap Connected con_id=%d" ; raise(ev4, 1)
47eb8: bl   bt_jsr82_SearchL2capContext(cid)
47ebc: trace "JSR82 L2CAP Client connected inx=%d"   <- inx = 3, context FOUND
47ed4: movs r2, #2                                    <- reports failure regardless
```

The zero branch is explicitly the **client** branch, the context lookup
succeeds, and the status is then hardcoded to 2. Independent header work
supports this: `struct _L2CAP_Channel` has no role field in the public v3.x
generation, but offset-fitting the target's `0x198`-byte record against that
header reproduces every measured anchor (`state@0x04`, `link@0x08`,
`psmInfo@0x2c`, `localCid@0x30`), and `+0x84` sits in the block this generation
appended alongside the `outMode`/`inMode` connection tracking. `0` is the
correct, permanent value for a locally-initiated channel — nothing in the SDK
flips it later. So the branch is a legitimate client/server split and only the
status literal is wrong.

### But the two-byte patch stops the daemon, reproducibly

Controlled, with `bluetooth_on = 1` verified after boot in every row:

| build | `init.svc.mtkbt` |
|---|---|
| stock | running |
| stock + PSM fix + trace | running |
| stock + PSM fix + trace + `0x47ed4` | **stopped** |

Two boots each. A `movs` literal inside an L2CAP callback cannot plausibly
affect daemon startup, so something about `0x47ed4` is not what a linear
disassembly suggests — the surrounding bytes may be reached as data, or the
instruction boundary may differ from what objdump's sweep shows. **Resolve that
before using the patch**; an earlier note in this file that blamed the missing
daemon purely on the Bluetooth-off trap was too quick. The trap is real and did
cause some of the occurrences, but it does not explain these.

Suggested isolation, one reboot each: install `0x47ed4` **alone** on otherwise
stock mtkbt (no PSM patch, no trace thunks) and check `init.svc.mtkbt`. If it
still dies, dump the bytes around `0x47ec0..0x47ef0` from the live binary and
re-derive the instruction boundaries rather than trusting the sweep.

## Isolation result: the status literal is right, but it is not sufficient

Two experiments settled the "the patch stops the daemon" question, and the
answer is neither of the two guesses in the section above.

1. **`0x47ed4` alone on otherwise-stock mtkbt: the daemon runs.** So the patch
   does not break startup, and the instruction boundaries are fine —
   `0x47ed0` reads `28 46 | 05 21 | 02 22 | 13 e0`, exactly
   `mov r0,r5 / movs r1,#5 / movs r2,#2 / b`, all 2-byte aligned.
2. **PSM fix + `0x47ed4`, no trace thunks: `init.svc.mtkbt` is `stopped` after
   boot — and `/data/tombstones` holds a fresh SIGSEGV timestamped to the exact
   second of the AAP connect attempts.**

`mtkbt` is a `oneshot` init service, so a crash at *any* point looks identical
to "never started" via `getprop`. It was never a startup problem: the daemon
dies on the first AAP connect and init never brings it back, which then takes
Bluetooth and audio down with it. **Always check `/data/tombstones` before
concluding a patch prevented startup.**

The tombstone:

```
pid: 156, name: mtkbt  >>> /system/bin/mtkbt <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 00000024
    r2 00000000   r5 00000001   r8 00000005
backtrace:
    #00  pc 000450fe  /system/bin/mtkbt
    #01  pc 00047f01  /system/bin/mtkbt      <- the bl at 0x47f00
```

`#01` is the raise call, and `0x450fe` is inside the event-5 block of the
raiser `0x450a0`:

```
450f6: cmp   r5, #1            ; r5 is the status argument we changed
450f8: bne   0x45104
450fa: ldr.w r2, [r6, #0x2f8]  ; r6 = ctx->[+0x30]; this is NULL for a client
450fe: ldrh  r3, [r2, #0x24]   ; SIGSEGV, fault addr 0x24
```

So the raiser has a **status-1-only branch** that reaches through
`[r6 + 0x2f8]` to copy a halfword at `+0x24` into the event. With status 2 that
branch is skipped, which is why the stock firmware never crashes here. With
status 1 it runs and dereferences a pointer the client path never populated.

**This is the strongest evidence yet that the diagnosis is right and the fix is
incomplete.** The hardcoded 2 is not an arbitrary lie — it is load-bearing,
because the success path depends on state that MediaTek's client flow never
sets up. The header names the missing step: `bt_jsr82_AddCreateL2capToContext`
(client) versus `bt_jsr82_AddNewL2capToContext` (server). Something equivalent
to the former has to run — attaching the opened channel to the session context
— before an event-5 status 1 can be raised safely.

### Next steps

- Identify what `ctx->[+0x30] + 0x2f8` points at, and which function populates
  it on the *server* path. That is the state the client path is missing.
  `0x450a8` (`ldr r6,[r0,#0x30]`) gives the base; find the writer of `+0x2f8`.
- Only then consider raising status 1, and expect to have to populate that
  pointer first — or to jump to whatever the server path does after
  `bt_jsr82_AddNewL2capToContext`.
- Reminder for whoever tests: `AapService` gives up after
  `MAX_BOOTSTRAP_ATTEMPTS`, so a single boot only ever produces about three
  connect attempts.

## Result: the client L2CAP connect works

With `y2_jsr82_client_l2cap_fix.sh` plus the HAL half of `y2_psm_fix.sh`:

```
I/AapService: AAP connect: calling connect() auth=false encrypt=false bondState=12
I/AapService: AAP L2CAP connected to 74:77:86:77:FC:A4
```

**`BluetoothSocket.connect()` returns successfully for the first time in this
investigation**, `mtkbt` stays alive, and `/data/tombstones` gains no new entry.
The session survives rather than being torn down in the same millisecond.

That confirms the whole chain end to end: the PSM never reached the wire
(`y2_psm_fix.sh`), and then JSR82's client path reported its own successful
connect as a failure because the success branch needed
`session_buffer->l2capCtx.channel`, which nothing ever populated. Supplying that
pointer and the success status makes the vendor's own code work.

The public headers confirm the field exactly (`jsr82_session.h`):

```c
typedef struct _BT_JSR82_L2cap_struct_t {
    U8              l2cap_con_state;   /* +0x2f4 */
    U16             l2capLocalCid;     /* +0x2f6 */
    L2CAP_Channel  *channel;           /* +0x2f8  <- the missing write */
} BT_JSR82_L2cap_struct_t;
```

and `r6` is the record context's `U8 *session_buffer`, with `l2capCtx` pushed out
to ~`0x2f4` by the `data[JSR82_SESSION_MAX_RX_DATA]` (339) buffer ahead of it.
`+0x24` on the channel is `rxMtu`: strict BES field order puts it at `+0x20`,
but every measured anchor in this build sits exactly `+4` from BES
(`link` `0x04->0x08`, `psmInfo` `0x28->0x2c`, `localCid` `0x2c->0x30`), so the
same shift lands `rxMtu` on `+0x24`.

### Still open: no AAP data on the connected socket

The socket connects and stays open, but nothing is received — no `AAP rx` lines
— and one earlier session ended with `bt socket closed, read return: -1`.
So the remaining problem is the data path, not the connect.

Leading suspects, in order:

1. **The MTU smuggle.** `y2_psm_fix.sh` carries the PSM in the `mtu` argument,
   so `localMtu` becomes 4097 while JSR82's receive buffer is
   `data[JSR82_SESSION_MAX_RX_DATA]` = **339**. The connect confirm now reports
   that 4097 upward too (it is read from `channel->rxMtu`). Fixing this properly
   means carrying the PSM in `msg->channel` at `+0x0e` — which nothing currently
   reads — instead of hijacking `mtu`. See the tidier patch noted earlier
   (`0x6be12: ldrh r0,[r4,#0xc] -> +0xe`), which would let both fields hold
   their intended values.
2. **Further `l2capCtx` wiring.** `AddCreateL2capToContext` may also be expected
   to set up `mainRecord` / the RX ring buffers that route inbound L2CAP data to
   the session. Only the channel pointer was supplied here.
3. The AAP handshake bytes, still never exercised against a live socket.

Next step: reinstall `y2_trace_to_logcat.sh` alongside this fix and watch whether
inbound data reaches L2CAP (`Notified data`, `L2CAP_RX_DATA_IND`) but is not
routed to the session — that separates suspect 2 from suspect 3.

## The mtu smuggle is gone, and the send path is now the frontier

The PSM no longer has to be smuggled. `msg->channel` really is at `+0x0e` — the
field nothing ever read — so a thunk at the original dropped-channel site does
what the vendor meant to do:

```
6bdfa (stock): str  r6, [r5, #0x20]     ; ctx.channel = 0   <- the original bug
       thunk : ldrh r3, [r4, #0x0e]     ; msg->channel
               str  r3, [r5, #0x20]     ; ctx.channel
               bl   memcpy              ; (relocated, r0-r2 already set)
```

With this, **`0x6c4e0` and the HAL are both back to stock** and the wire still
shows `L2Cap: ConnectReq r-psm:0x1001` followed by `conn_rsp result:0` and
`enter open state`. `localMtu` is no longer 4097. That retires
`y2_psm_fix.sh`'s HAL half entirely.

JSR82 now reports the channel properly, which it never did before:

```
[JSR82]btadp_jsr82_channel_connected :id[210438], conn_id[3], identify[7]
```

### Where the data actually stops

The Java write reaches L2CAP and then vanishes:

```
[JSR82]bt_session_upper_data_incoming: session id[210438]
[JSR82]btadp_jsr82_session_send
[JSR82]jsr82_session_PutBytes
c96  BT_JSR82_TX_REQ Find jsr82 channel :3
c67  BT_JSR82_sendToL2Cap(): remove a free pkt to send data
[JSR82] jsr82_session_fetchTxPacket <ptr>
c77  BT_JSR82_sendToL2Cap After jbt_session_DevTX() with PS_L2CAP: get bytes=10
```

16 bytes (the AAP handshake) are fetched and handed to the L2CAP send — and
**no `PutByte` ever follows**. Nothing goes out on the air. There is exactly one
TX attempt in the whole capture, which is consistent with `AapService` sending
the handshake and then blocking on a read that never completes.

So the remaining defect is in the JSR82 -> L2CAP transmit path, not in the
connect and not in the AAP payload. Candidates:

- `BtPacket txPacket` in the record context (`jsr82_session.h`) may need fields
  the client path never fills, so `L2CAP_Send` rejects or drops it.
- The channel may not be marked transmit-ready — the A2DP channels log
  `l2cap triggerHciSend` and `Almost empty` around their sends, and our channel
  logs neither.
- Flow/mode state: the connect logs `outMode:1 inMode:1` and
  `Channel->psmInfo->lockStepNeeded:0`; if the packet is queued against a
  psmInfo the client path did not populate, it would sit forever.

Next: trace inside `BT_JSR82_sendToL2Cap` past the `get bytes` point, and find
what the server/RFCOMM path does after `fetchTxPacket` that the L2CAP client
path does not. `L2CAP_Send`'s return value is the thing to capture.

## Why the handshake never reaches the air: L2CAP_Send is called with cid 0

Hooking `L2CAP_Send`'s return (`0x47a7a`, tag `MTKSND`) gave `L2CAP_Send=1`
(`BT_STATUS_FAILED`) — and the caller only accepts **2** (`BT_STATUS_PENDING`):

```
47a76: bl   0x88d98        ; L2CAP_Send
47a7a: cmp  r0, #2         ; PENDING == success
47a7c: beq  0x47af4
47a7e: ...                 ; anything else -> put the packet back, drop it
```

That is the third place in this stack where `PENDING` is the success value.

`L2CAP_Send` (`0x88d98`) has three failure exits. The packet-flags `tst r3,#0xf6`
passes (the caller sets flags to 1), and the exit that logs
`"L2CAP_SendData state:%d return:%d"` never appears in the capture — so the
failure is the CID lookup (`0x8e9f0`) returning NULL. Logging the CID confirmed
it outright:

```
MTKSND  L2CAP_Send cid=0
```

while the channel's real CID is `0x43` (`l2cap Channel psm:0x1001 ... cid:0x43`),
and `bt_jsr82_AddCreateL2capToContext():l2cap_id=0x43` had already stored it in
`l2capCtx.l2capLocalCid`.

### Where the zero comes from

The CID travels in the TX request message, not from `l2capCtx`:

```
481d8: ldrb r0, [r4, #0x05]   ; session index
481da: ldrh r1, [r4, #0x06]   ; the CID  <- 0 for our session
481dc: bl   0x47fdc           ; BT_JSR82_TX_REQ  (trace c96)
...
4803c: ldrb r3, [r4, #0x0a]   ; ps_type
48040: cmp  r3, #1
48044: b.w  0x47424           ; RFCOMM sender
4804c: mov  r1, r7            ; L2CAP: pass the message's CID through
48052: b.w  0x479a8           ; L2CAP sender -> L2CAP_Send(cid, packet)
```

So `msg+0x06` is never populated on the client L2CAP path — the same species of
omission as the `l2capCtx.channel` pointer found earlier. RFCOMM does not care
because its sender takes a different route.

**Candidate fix (not yet built):** at `0x4804c`, instead of
`mov r1, r7`, source the CID from the session's own context — `r4` already holds
the session record there (`mla r4, #0x1a0, r4, r6` at `0x48038`), so
`ldr r0,[r4,#0x30]` gives `session_buffer` and `ldrh r1,[r0,#0x2f6]` gives
`l2capCtx.l2capLocalCid`. That is the value `AddCreateL2capToContext` already
stores, and it is only reached on the `ps_type != RFCOMM` branch, so RFCOMM is
untouched.

Worth doing at the same time: `AapService` should re-send the handshake at 0,
+200 ms and +5 s rather than waiting for an ACK. LibrePods' Android client does
exactly that because the AirPods sometimes ignore the first handshake, and its
`FEATURES_ACK` constant is annotated "only tested with AirPods Pro 2" — so an
ACK-gated state machine can stall on newer models.
