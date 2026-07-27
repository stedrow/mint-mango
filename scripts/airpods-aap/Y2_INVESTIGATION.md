# AAP on Y2 — investigation notes (in-ear detection / stem control)

**Status: in-ear detection works — but not over L2CAP.** The raw L2CAP AAP
channel is still dead on Y2 and its root cause is still unfound (everything
below the "Symptom" heading remains accurate and worth reading before
attempting it again). It was sidestepped entirely: ear state and battery now
come from Apple's proximity-pairing BLE advertisement, which needs no
connection at all. See "The BLE advertisement route" below.

Verified on-device with AirPods Pro 3: pulling either bud pauses playback
~2s later, reinserting it resumes.

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

## Historical: the L2CAP dead end

Everything below documents the L2CAP channel that is still broken, kept so a
future attempt doesn't repeat the same dead ends.

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
