# AAP on Y2 — investigation notes (in-ear detection / stem control)

**Status: not working, root cause not found.** This documents how far the
trace got, so the next attempt doesn't repeat the same dead ends.

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
