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

Cheap next probe along the same lines: point the confirm's copy at the event's
`+0x20` instead of `+0x22` (`ldrb.w r3,[r4,#0x22]` -> `ldrb.w r3,[r4,#0x20]`).
Case 5 of `FUN_000550a0` explicitly zeroes `+0x20`, so `result:00` would prove
the event came from there and the offsets are shifted, while an unchanged `02`
would prove a different producer.

### Next step

Better than more guess-and-reboot: get a real trace. Two dead ends found while
trying to get one:

- mtkbt's internal traces never reach logcat: both trace helpers end in a sink
  gated off on a `user` build, headed for MTK's mobile-log daemon.
- **Do not** hook those helpers by patching mtkbt's code in-process from the
  proxy (the proxy is loaded into mtkbt, so it is tempting). Tried it: mtkbt
  crashes with `SEGV_ACCERR` and Bluetooth then never finishes enabling. Also
  beware `__builtin___clear_cache` in proxy code — it needs `__clear_cache`,
  which this bionic lacks, so the library fails to load *and takes the audio HAL
  and system_server down with it*. Use the `__ARM_NR_cacheflush` syscall.

A safer route to the same answer is a static patch of mtkbt that redirects the
trace sink to its own `__android_log_print` PLT entry, or simply patching the
sink's gate and checking whether `mobile_log_d` then captures to `/sdcard/mtklog`.

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
