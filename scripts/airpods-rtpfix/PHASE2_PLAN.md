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

#### Middle option discovered during M0 (evaluate before committing to full Path B)

Because the connect **does** traverse the MTK stack and only the `ps_type`
tag is wrong, a smaller intervention than full HCI injection may exist: patch
the closed lib so a TYPE_L2CAP socket emits `ps_type=2`. Candidates:
`libextjsr82.so` (`btmtk_jsr82_session_connect_req`) or the JNI in
`libandroid_runtime.so` that sets `ps_type` from the socket type. **Unproven**
and carries real risk (both run inside `system_server`; a bad patch = boot-loop
of the system process, worse than the isolated `mtkbt`/`libbluetoothdrv` swap
Phase 1 used). It also may not work if `ps_type=2` turns out to be the JSR82
"registered service" path rather than a raw-PSM CoC client. Worth a bounded
static-analysis spike (disassemble the ps_type assignment, confirm the
`ps_type=2` path does a client L2CAP connect to an arbitrary PSM) **before**
choosing between this and Path B.

### M1 — AAP client service (Path A)

- Promote the spike into a proper `AapService` (foreground/bound service):
  connect on AirPods connect, send handshake + enable-notifications, keep the
  socket open alongside A2DP, auto-reconnect on drop.
- Parse incoming packets into a small state object: `{ earLeft, earRight,
  batteryCase, batteryLeft, batteryRight, charging..., ncMode }`.
- Emit state to the rest of the app (LocalBroadcast, or a listener interface).
- Tie lifecycle to the existing BT plumbing in `MainActivity` (it already
  tracks the connected device via the A2DP profile proxy and
  `targetDeviceForAudio`).

### M2 — Ear-detection auto-pause/resume (do first, highest value)

- On "an AirPod removed" → if playing, pause. On "both back in ear" → resume if
  we auto-paused (track that we were the one who paused, so we don't fight the
  user).
- Wire into `AudioPlayerManager`: use `isPlaying()` and `playOrPauseMusic()`
  (`app/src/main/java/com/themoon/y1/managers/AudioPlayerManager.java`). Consider
  adding explicit `pauseForAirpods()` / `resumeForAirpods()` helpers so the
  auto-pause state is separate from user intent.

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

## 9. Kickoff prompt for the next session

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
