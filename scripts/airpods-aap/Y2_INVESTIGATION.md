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
