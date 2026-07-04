# AirPods Phase 2 — AAP Feature Support (Plan)

Phase 1 (done, shipped in this folder) fixed **audio** for AirPods Pro 2/3 on the
Y1 via the `libbluetoothdrv.so` RTP-timestamp proxy. Phase 2 adds the
"Apple-only" convenience features on top, by speaking Apple's **AAP** (Apple
Accessory Protocol) to the AirPods.

This is a **planning document** for a future session — nothing here is
implemented yet.

---

## 1. Goal & scope

Add, in priority order:

1. **Ear-detection auto-pause/resume** — pause when an AirPod is removed, resume
   when re-inserted. Highest daily value; do this first.
2. **Battery display** — case / left / right levels + charging state, shown in
   the launcher UI.
3. **Noise-control toggle** — cycle Off / ANC / Transparency / Adaptive from the
   Bluetooth settings screen.

**Explicitly out of scope** (no value on a dedicated music player, or need a
phone/mic context the Y1 lacks): head gestures, conversational awareness,
hearing-aid/audiogram, renaming, multipoint auto-switching, find-my.

---

## 2. What we already know (AAP)

Source: [librepods-org/librepods](https://github.com/librepods-org/librepods)
(`docs/AAP Definitions.md`, `docs/control_commands.md`, `docs/opcodes.md`).

- AAP runs over an **L2CAP** channel on **PSM 0x1001 (4097)**, separate from the
  A2DP audio channel — the two coexist, so opening it won't disturb playback.
- **Handshake** (must be sent first, or AirPods ignore everything after):
  `00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00`
- **Enable notifications:** `04 00 04 00 0F 00 FF FF FE FF`
  (or `04 00 04 00 0F 00 FF FF FF FF`)
- **Optional feature enable** (adaptive transparency / conv. awareness):
  `04 00 04 00 4D 00 FF 00 00 00 00 00 00 00`
- **Incoming status packets** (unsolicited once notifications enabled):
  - Battery: component ids case=`0x08`, left=`0x04`, right=`0x02`, each with a
    charge level + charging-state byte.
  - Ear detection: in-ear=`0x00`, out-of-ear=`0x01`, in-case=`0x02`.
  - Noise-control state: Off=`0x01`, ANC=`0x02`, Transparency=`0x03`,
    Adaptive=`0x04`.
- **Setting** noise control is an outgoing command — confirm the exact opcode
  framing from LibrePods' `control_commands.md` / `opcodes.md` during M4 (we
  only have the *state* byte mapping above, not the verified set-command bytes).

**The blocker:** Android 4.2.2 (API 17) exposes no public L2CAP socket API, and
the Y1 runs MediaTek's proprietary pre-Fluoride BT stack. So the whole question
is *how* to get an L2CAP channel to PSM 0x1001. That decides the architecture.

---

## 3. Architecture decision — two candidate paths

### Path A (preferred): app-level L2CAP via reflection

On API 17, `android.bluetooth.BluetoothSocket` has an internal
`TYPE_L2CAP = 3` and a hidden constructor
`BluetoothSocket(int type, int fd, boolean auth, boolean encrypt,
BluetoothDevice device, int port, ParcelUuid uuid)` where `port` is the L2CAP
**PSM**. Older "AirPods on Android" projects opened AAP this way. If the MTK
stack honors an app-initiated L2CAP connect to PSM 0x1001, **everything stays in
the launcher app** — a background `Service`, pure Java, no firmware changes,
fast iteration, low risk.

This is the target. But whether this old MTK stack allows it is **unknown** —
hence Milestone 0 is a spike to find out before committing.

### Path B (fallback): L2CAP client inside the firmware proxy

If Path A is blocked, extend the `libbluetoothdrv.so` proxy (which already sits
on the raw HCI read/write path) to implement a minimal L2CAP client: inject an
`L2CAP_ConnectReq` for PSM 0x1001 on the signaling channel, complete
config, then carry AAP over that CID, and bridge decoded state up to the
launcher via a local unix socket or a system property the app polls.

This is **much** harder and riskier — you're injecting L2CAP frames into a link
the host stack owns, with CID-accounting conflicts a real hazard — and it means
firmware changes + reflashing to iterate. Only pursue if Path A fails.

**Recommendation:** do M0 first; proceed on Path A if it works. Do not start
Path B without a failed M0.

---

## 4. Milestones

### M0 — L2CAP feasibility spike (decides everything)

- In a throwaway branch, add a tiny Service that, when AirPods are connected,
  reflects the hidden `BluetoothSocket` L2CAP constructor with `port = 0x1001`
  against the connected `BluetoothDevice`, connects **off the main thread**,
  sends the handshake, then reads the socket.
- **Success = any bytes come back** (ideally recognizable AAP status). Log raw
  hex via `adb logcat`.
- If it connects → Path A confirmed, continue to M1.
- If it throws / never connects → capture the exact failure, then evaluate Path
  B. (The Fluoride L2CAP bug LibrePods documents is newer-stack-specific; this
  pre-Fluoride MTK stack may behave differently either way — measure, don't
  assume.)

#### M0 RESULT (2026-07-03): ❌ FAILED — Path A is not viable on this stack

Ran on Scott's Y1 (`Y1_001`, Android 4.2.2 / MT6572), AirPods Pro 3 bonded,
A2DP connected and audio playing (rtpfix live). Spike = `AapSpikeService`
(built on branch `spike/m0-aap-l2cap`). Full trace:
[`M0_RESULT_l2cap_trace.txt`](M0_RESULT_l2cap_trace.txt).

What works:
- The hidden API-17 ctor
  `BluetoothSocket(int type, int fd, boolean auth, boolean encrypt,
  BluetoothDevice, int port, ParcelUuid)` **is reflectable** and constructs.
- `TYPE_L2CAP == 3`. `initSocketNative` **accepts** `type=3, port=4097` (no
  "Invalid type") and returns a valid fd handle. So a TYPE_L2CAP socket is
  constructable and initializable.

What fails (the blocker):
- `socket.connect()` throws `IOException: [JSR82] connect: Connection is not
  created (failed or aborted)` — **reproducibly**, on every attempt, both
  `auth/encrypt = false/false` and `true/true`, in ~1.1 s each.
- On the wire the MTK stack **does** emit a real session-connect to the AirPods
  (`jbt_session_connect_req … addr:74:77:86:77:fc:a4 psm_channel:1001`), so the
  request reaches the peer. But it is tagged **`ps_type:01`**, and the confirm
  returns **`result:02`** (fail), `l2cap_id:0000` (channel never allocated).
- Root cause: MTK's JSR82 socket layer (`android.server.BluetoothSocketService`
  + `libextjsr82.so`) does **not** honor `TYPE_L2CAP` on the client connect
  path. In `btmtk_jsr82_session_connect_req` (libextjsr82, `0x7aac`),
  `ps_type==1` takes a channel/RFCOMM-style path and `ps_type==2` is the
  distinct path that actually allocates the connection entry — but our
  TYPE_L2CAP socket goes out as `ps_type=1`, i.e. it is treated as an
  RFCOMM/JSR82 session connect to "channel" 0x1001, which is invalid, so the
  stack rejects it. There is **no app-level lever** to force `ps_type=2`; the
  mapping lives in the closed system libraries. **No bytes ever return; the AAP
  handshake is never sent.**

Conclusion: the stock socket API cannot open an L2CAP CoC to PSM 0x1001 on this
pre-Fluoride MTK stack. **Path A (pure-app) is dead.** Proceed to evaluate
Path B, or the middle option noted below.

#### Middle option: static-analysis result (2026-07-03) — CONFIRMED, and lower-risk than scoped

Disassembled `libextjsr82.so` (pulled live from the device, ARM/Thumb2, `objdump`
+ `capstone` w/ pyelftools since it's Thumb code) to answer the two open
questions from the M0 write-up:

**1. Does `ps_type=2` do a genuine client L2CAP connect to an arbitrary PSM, or
is it the JSR82 "registered service" path?** Confirmed genuine client connect.
In `btmtk_jsr82_session_connect_req` (`0x7aac`), `ps_type` is simply the
function's 3rd argument (`r5`), written byte-for-byte into the outbound JBT
wire message (`strb r5, [sp, #0x46]`) — it's a passthrough, not something the
function derives. `psm_channel` (the PSM, `sl`/4th arg = `0x1001` in our trace)
is written into the same message **unconditionally, regardless of `ps_type`**,
so `ps_type=2` does not swap in some other "channel number" semantic — it's
the same arbitrary-PSM connect, just tagged differently on the wire.

The only difference between `ps_type==1` and `ps_type==2` is which guard runs
before the shared send path:
- `ps_type==1` → `jbt_check_already_connect_chnl_and_addr(addr, 1, psm)` (an
  anti-duplicate check against the RFCOMM-side table) — this is what actually
  runs today and is *why* our real connect got as far as the wire before
  being rejected.
- `ps_type==2` → `jbt_allocate_one_available_entry(table+8, 2)` — disassembled
  this too: it just scans a fixed-size table (10 slots) for a free entry
  (`byte[8] == 0`) and marks it used. **No pre-registration check, no lookup
  against `btmtk_jsr82_session_service_registration` state** — it's a generic
  slot allocator, symmetric with the type-1 path. This rules out the
  "registered service only" concern; `ps_type=2` is unconditionally available
  to any caller, capped only by the 10-slot table.

**2. Where does `ps_type` get set to 1, and what's the real blast radius of
patching it?** Traced the caller: `btmtk_jsr82_session_connect_req` is called
via PLT from `libandroid_runtime.so` (confirmed by relocation-table lookup —
reloc index 1742 → PLT slot `0x443a0`), the library that hosts the
`BluetoothSocket`/`BluetoothSocketService` JNI. **This changes the risk
picture from what this doc said above.** `libandroid_runtime.so` is not
scoped to `system_server` — it's the core native-methods library loaded by
**every** Dalvik process via zygote. A bad patch there risks crashing zygote
itself (unrecoverable boot-loop, worse than a `system_server`-only crash).
I was not able to pin down the exact instruction that collapses the app's
`TYPE_L2CAP` request down to `ps_type=1` inside that library in the time
spent (it's Thumb2 with no symbol/mapping-symbol table, so disassembly
requires manual mode-tracking through ~800KB of code — tractable but not
"bounded spike" tractable).

**Revised recommendation:** don't patch `libandroid_runtime.so`. Patch
`libextjsr82.so` instead, right at `btmtk_jsr82_session_connect_req`
(`0x7aac`), before the `cmp r5, #1` branch at `0x7b3e`: override `r5` (the
`ps_type` arg) to `2` whenever `sl` (the PSM/channel arg) looks like an L2CAP
PSM rather than an RFCOMM channel number (RFCOMM channels are 1–30; L2CAP CoC
PSMs are ≥ `0x1000` in practice, and ours is `0x1001`) — e.g. `if (sl >=
0x100) r5 = 2`. This is a small, local patch to a leaf Bluetooth-extension
library that is **not** zygote-loaded — same risk class as the Phase 1
`libbluetoothdrv.so` swap (isolated to whichever process opens the JSR82
socket), not the `system_server`/zygote-wide risk this doc previously
assumed for both candidates. Full disassembly excerpts are worth re-deriving
from a fresh `adb pull` of `/system/lib/libextjsr82.so` +
`/system/lib/libandroid_runtime.so` if the next session wants to re-verify
before patching (not committed here — binaries, and this analysis is
reproducible in ~10 minutes with `objdump`/`capstone`+`pyelftools`).

**Still open before writing the patch:** locating and neutralizing whatever
in the JSR82 JNI (or possibly `libextjsr82.so` itself, unseen in the reachable
code above — check for other entry points besides
`btmtk_jsr82_session_connect_req`) currently forces the value to 1 in the
first place, since the Java-level call sends `TYPE_L2CAP=3` and something
between the JNI and this function already collapses it to `1` today. If that
collapse also happens inside `libextjsr82.so` (plausible — this lib exports
its own JNI-facing entry points, see `btmtk_jsr82_setExtSockAddress` /
`setSockFd` above), the whole fix can stay inside this one leaf library.

#### M0.5 — patch built and validated on-device (2026-07-03): ✅ SUCCESS — Path A is ALIVE

Built the patch exactly as scoped above: `scripts/airpods-aap/` (build/install/
revert/status scripts + `src/build_patch.py`). Mechanically it's a code-cave
binary patch — a new PT_LOAD segment holding the extra branch logic, plus a
6-byte hook (`b.w` to the cave) overwriting the original `cmp r5,#1` /
`str r4,[sp,#0x1c]` / `bne` at `0x7b3e`, built with `keystone` (Thumb2
assembler) + `LIEF` (ELF segment injection), and **verified by disassembling
the actual output bytes with capstone** before ever touching the device (not
just trusting the assembler).

Installed on Scott's Y1 (backup preserved as `libextjsr82_real.so`, revertible
via `revert.sh`). Device rebooted clean — `system_server` and `mtkbt` both
came up normally, audio (rtpfix) kept working, no bootloop. Ran the
`AapSpikeService` from M0 unchanged (same `TYPE_L2CAP`/PSM `0x1001` reflection
call) against the same AirPods Pro 3. Result, full trace at
[`../airpods-aap/M0.5_patch_success_trace.txt`](../airpods-aap/M0.5_patch_success_trace.txt):

- `RESULT: L2CAP CONNECTED in 75ms` — where M0 failed every time in ~1.1s with
  `IOException: connect: Connection is not created`, it now connects cleanly.
- AAP handshake sent, and the AirPods immediately responded with real AAP
  packets: a device-info packet decoding in plaintext (`Scotty's AirPods Pro
  3`, model `A3064`, `Apple Inc.`, serial numbers, firmware `1.0.0`), a stream
  of component/status update packets, and continued sending packets for the
  full 90s listen window after "enable notifications" was sent — a stable,
  ongoing AAP session, not a one-shot fluke.

**Conclusion: Path A (app-level, no HCI injection) is the way forward.**
Path B is no longer needed. The `ps_type` fix in `libextjsr82.so` is the only
system patch required — everything else (parsing packets, sending the
noise-control set-command, wiring into playback/UI) is pure app-level Java,
same as any other Android feature.

**Left in place on the device**: patched `libextjsr82.so` (needed for M1+),
and the `spike/m0-aap-l2cap` branch's launcher build (a normal launcher plus
the one inert `AapSpikeService`, per the M0 note). Move to M1 to replace the
spike service with a real always-on `AapService` before this needs revisiting.

### M1 — AAP client service (Path A) ✅ DONE (2026-07-03, branch `feature/aap-service-m1-m2`)

Implemented `app/src/main/java/com/themoon/y1/AapService.java`, replacing
`AapSpikeService` entirely (removed from the manifest and repo). It's a plain
(non-foreground) `Service` started/stopped by `MainActivity`'s A2DP
`CONNECTION_STATE_CHANGED` handler (`AapService.deviceConnected(ctx, device)` /
`AapService.deviceDisconnected(ctx)`), and also stopped when Bluetooth itself
turns off. Not gated to AirPods specifically — any device fails the AAP
connect a few times (`MAX_BOOTSTRAP_ATTEMPTS = 3`) and the service quietly
gives up, assuming non-Apple hardware.

Connect/handshake bytes are unchanged from the M0 spike (same reflected
`TYPE_L2CAP`/PSM `0x1001` ctor, same handshake + enable-notifications frames).
Once connected it stays open indefinitely with auto-reconnect on drop
(distinct from the A2DP audio connection, which can stay up while the AAP
L2CAP channel drops independently).

**Packet parsing**: incoming bytes accumulate in a buffer and are peeled off
packet-by-packet. Every real AAP packet observed on the wire is prefixed with
`04 00 04 00` followed by a 2-byte LE opcode (verified against the M0.5 trace
— e.g. opcode `0x1D` device-info, `0x2B` paired-devices, `0x53` EQ data, `0x09`
generic control-command-carrying notifications, all matched this framing
exactly). Two opcodes have deterministic length and are fully parsed:
- `0x0006` ear detection — fixed 8 bytes, payload = `[left][right]` raw state
  bytes (`00`=in-ear, `01`=out-of-ear, `02`=in-case).
- `0x0004` battery — `[count]` byte then `count` × 5-byte records
  `[component][0x01][level][status][0x01]` (component `0x04`=left,
  `0x02`=right, `0x08`=case; status `0x01`=charging).

Every other opcode's length is unknown, so the parser resyncs by scanning
forward for the *next* `04 00 04 00` marker and treats everything up to there
as that packet's payload (skipped, not parsed) — the same trick community AAP
clients use since there's no universal length prefix. This means battery/ear
packets parse deterministically and correctly regardless of what other packet
types are interleaved around them.

State is exposed via `AapService.Listener` (`onAapStateChanged` /
`onAapConnectionChanged`, register with `AapService.addListener`) plus static
`AapService.getLastState()` / `isConnected()` for polling — ready for M3
(battery UI) to consume without further plumbing.

**Verified live on Scott's Y1**: rebuilt, flashed, rebooted; AirPods Pro 3
auto-connected and logcat showed `AAP L2CAP connected to 74:77:86:77:FC:A4`
from the real `AapService` (not the spike).

### M2 — Ear-detection auto-pause/resume ✅ DONE (2026-07-03, same branch)

Wired directly into `AapService`'s ear-detection packet handler
(`handleEarDetectionForAutoPause`): tracks a `bothInEar` transition — pauses
on the *first* transition away from both-in-ear (removing one AirPod already
pauses, matching real AirPods behavior), resumes only when transitioning back
to both-in-ear **and** only if the auto-pause was the one that fired.

Added `pauseForAirpods()` / `resumeForAirpods()` to `AudioPlayerManager`
(`app/src/main/java/com/themoon/y1/managers/AudioPlayerManager.java`) as
planned, backed by a private `pausedByAirpods` flag — separate from
`isPausedByHand`. `playOrPauseMusic()` (any real user tap) now resets
`pausedByAirpods = false` at entry, so an explicit user pause or resume always
wins and a stale auto-pause never fires an unwanted resume later.

**Verified live**: played a track, removed one AirPod — playback paused;
reinserted it — playback resumed. Confirmed by the user directly on-device.

### M3 — Battery display

- Surface case/left/right + charging in the UI. The Bluetooth screen lives in
  `MainActivity` (`STATE_BLUETOOTH`, `layoutBluetoothMode`,
  `ivStatusBluetooth`); there are existing battery views to reuse/model
  (`views/BatteryIconView.java`, `views/CircularBatteryView.java`,
  `views/WidgetBatteryBarView.java`).

### M4 — Noise-control toggle

- Add a control on the Bluetooth settings screen to cycle Off/ANC/Transparency/
  Adaptive; send the set-command over the AAP socket; reflect confirmed mode
  from the status packet.
- **First** confirm the exact set-command bytes from LibrePods
  `control_commands.md` / `opcodes.md` (we only have the state-byte mapping).

---

## 5. Integration points (verified in the current code)

- Playback: `managers/AudioPlayerManager.java` — `playOrPauseMusic()` (L289),
  `isPlaying()` (L597), `playTrackList(...)` (L209). ExoPlayer-based.
- Bluetooth/connection state: `MainActivity.java` — imports
  `BluetoothAdapter/Device/Profile`, gets the A2DP profile proxy
  (`getProfileProxy`, ~L789), tracks `targetDeviceForAudio` (L86),
  `STATE_BLUETOOTH` screen (L243), status icon `ivStatusBluetooth`.
- Package `com.themoon.y1`, Java, `minSdk 17`, `targetSdk/compileSdk 35`.
- App is a **system app** with `WRITE_SECURE_SETTINGS`; already holds
  `BLUETOOTH`/`BLUETOOTH_ADMIN` (it drives A2DP today).

---

## 6. Risks & mitigations

- **L2CAP reflection blocked on this stack (Path A fails).** Mitigate: M0 spike
  before any real build; Path B documented as fallback.
- **AirPods drop the AAP channel** if the handshake isn't first or notifications
  aren't enabled. Mitigate: follow the exact byte sequences above; log raw hex.
- **Auto-pause fighting the user** (resume when they wanted it stopped).
  Mitigate: only auto-resume if *we* auto-paused; never override an explicit
  user pause.
- **Reconnect churn / battery drain** from a persistent socket. Mitigate: tie
  the socket strictly to AirPods-connected state; close on disconnect.
- **Model differences (Pro 3 vs Pro 2)** in packet layout. Mitigate: parse
  defensively by component id, tolerate unknown fields.

---

## 7. Testing

- Same loop as Phase 1: drive from `adb logcat` while exercising the AirPods.
- M0/M1: log raw AAP hex, verify handshake response and that ear/battery/nc
  packets arrive on physical actions (remove an AirPod, put it in the case,
  toggle a mode from an Apple device to observe the state packet).
- M2: remove/insert AirPod, confirm pause/resume; verify no fight with manual
  pause.
- Keep changes on a branch; the launcher installs as a system app (see main
  README "Building from Source" → "Flash to device").

---

## 8. References

- Phase 1 fix + why the firmware swap is safe here:
  [`README.md`](README.md) in this folder.
- Project memory: `project-y1-airpods` (verified device/firmware facts, build
  recipe, revert procedure).
- LibrePods protocol docs: <https://github.com/librepods-org/librepods> →
  `docs/AAP Definitions.md`, `docs/control_commands.md`, `docs/opcodes.md`.
- Phase-1 upstream: <https://github.com/Semy0nBu/y1-airpods-rtpfix>.

---

## 9. Kickoff prompt used for M0 (superseded — kept for history)

> We shipped Phase 1 of AirPods support on the Y1 launcher — the
> `libbluetoothdrv.so` RTP-timestamp proxy in `scripts/airpods-rtpfix/` that got
> AirPods Pro 3 audio working (see that folder's README and the
> `project-y1-airpods` memory). Now start **Phase 2**: AAP feature support,
> following `scripts/airpods-rtpfix/PHASE2_PLAN.md`.
>
> Begin with **Milestone 0**, the L2CAP feasibility spike: on a throwaway
> branch, add a small Service that reflects the hidden API-17 `BluetoothSocket`
> L2CAP constructor to connect to the connected AirPods on PSM 0x1001, sends the
> AAP handshake (`00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00`), enables
> notifications (`04 00 04 00 0F 00 FF FF FE FF`), and logs all returned bytes as
> hex via logcat. My Y1 is connected over USB (root adb) with AirPods Pro 3
> paired. Build/flash the launcher as a system app per the main README, exercise
> it, and report whether the L2CAP channel opens and what comes back — that
> result decides whether we continue on the app-level path (Path A) or fall back
> to the firmware-proxy path (Path B). Don't start Path B unless M0 fails.

## 10. Kickoff prompt for M1 (superseded — kept for history; M1+M2 done, see sections above)

> Continuing AirPods support on the Y1 launcher. Phase 1 (audio,
> `scripts/airpods-rtpfix/`) and Phase 2's M0/M0.5 (AAP feasibility,
> `scripts/airpods-rtpfix/PHASE2_PLAN.md`) are both done — read that plan doc in
> full plus the `project-y1-airpods` memory before starting, they have the
> complete history.
>
> **Where things stand:** the stock JSR82 socket layer on this MTK 4.2.2 stack
> tags every client L2CAP connect `ps_type=1` (an RFCOMM path), which the
> AirPods reject. `scripts/airpods-aap/` binary-patches the closed
> `libextjsr82.so` so a connect to a PSM-looking channel value (>= 0x100) is
> tagged `ps_type=2` instead — the genuine raw-PSM client-connect path — while
> every other JSR82 caller (RFCOMM channels 1-30: headset, OBEX, SPP) is
> untouched. This patch is **already built, flashed to my Y1, and validated**:
> the M0 `AapSpikeService` (still on branch `spike/m0-aap-l2cap`,
> `app/src/main/java/com/themoon/y1/AapSpikeService.java`) connects in 75ms and
> receives a live stream of real AAP packets (device info, battery/status
> updates) for the full 90s listen window. Full trace at
> `scripts/airpods-aap/M0.5_patch_success_trace.txt`. **Path A (pure app-level
> AAP client) is confirmed viable — Path B is dead, don't build it.**
>
> My Y1 is connected over USB (root adb). Confirm current device state before
> assuming anything: `scripts/airpods-aap/status.sh` shows whether the patched
> `libextjsr82.so` is still active (it should be — it survives reboots but not
> a factory restore/re-flash of `/system`), and `adb shell pm path
> com.themoon.y1` / a logcat check shows whether the spike-branch launcher (with
> the inert `AapSpikeService`) is still installed. If the patched lib isn't
> active, re-run `scripts/airpods-aap/build.sh && ./install.sh` before doing
> anything else — nothing below works without it.
>
> **Do M1**: promote the `AapSpikeService` spike into a real, permanent
> `AapService` (see PHASE2_PLAN.md's M1 section for the shape: connect when
> AirPods connect via the existing A2DP profile-proxy plumbing in
> `MainActivity.java`, `TYPE_L2CAP`/PSM `0x1001`, send the handshake then
> enable-notifications — same bytes the spike already uses and already proved
> work — keep the socket open alongside A2DP audio, auto-reconnect on drop).
> Parse the incoming packet stream into a small state object (ear L/R,
> battery case/left/right + charging, noise-control mode) and expose it to the
> rest of the app (LocalBroadcast or a listener interface) — you have real
> captured packets in `M0.5_patch_success_trace.txt` to reverse-engineer the
> exact byte layout against LibrePods' `docs/AAP Definitions.md` if the mapping
> isn't 1:1 obvious.
>
> Once `AapService` is solid, wire up **M2 first** (ear-detection auto-pause —
> highest daily value): pause on AirPod-removed, resume only if *we* auto-paused
> (never fight an explicit user pause), via `playOrPauseMusic()`/`isPlaying()`
> in `managers/AudioPlayerManager.java`. Battery display (M3) and the
> noise-control toggle (M4, needs the set-command bytes confirmed from
> LibrePods' `control_commands.md`/`opcodes.md` first) come after — see
> PHASE2_PLAN.md for those milestones' integration points.
>
> Work on a new branch off `spike/m0-aap-l2cap` (it already has the working
> spike code and this plan doc) rather than off `master`. Build/flash the
> launcher as a system app per the main README's "Building from Source"
> section; watch logcat for the `AAPSPIKE`-style tags (rename for the real
> service) while exercising the AirPods.
