# AirPods AAP over L2CAP

Apple's Accessory Protocol (AAP) rides on L2CAP PSM `0x1001`. Talking it gives
in-ear detection in **under a millisecond** from the packet arriving, versus
~5s for the BLE proximity-advert route the launcher falls back to.

Both supported devices needed a different vendor bug fixed before a
client-initiated L2CAP connect would work at all. Stock Android can't do this
either way: AOSP 4.4 bluedroid rejects `BTSOCK_L2CAP` outright, and
`BluetoothSocket.TYPE_L2CAP` is a constant with no implementation until
`createInsecureL2capChannel` arrives in Android 10. Everything here works
through MediaTek's own JSR82 bolt-on.

## Y2 (MT6582, Android 4.4.2) — `y2_aap_l2cap_fix.sh`

**Working and measured.** Three bugs in MediaTek's client L2CAP path, all in
`/system/bin/mtkbt`, none in Apple's protocol:

| # | Defect | Patch |
|---|---|---|
| 1 | `btadp_jsr82_connect_req` zeroes `ctx.channel` and never copies `msg->channel` (`+0x0e`), so the Connection Request went out with PSM 0 and the peer refused it | `0x6bdfa` |
| 2 | `BTJSR82_L2capCallback`'s client branch reports a *successful* connect as status 2. Status 1 alone crashes the daemon, because the raiser's success path reads `session_buffer->l2capCtx.channel` (`+0x2f8`) and nothing in the binary ever writes it | `0x47eca` |
| 3 | The TX path takes the CID from the request message's `+0x06`, never populated for a client session, so `L2CAP_Send` got 0, failed its channel lookup and silently requeued the packet | `0x4804c` |

```
./y2_aap_l2cap_fix.sh            # patch and reboot
./y2_aap_l2cap_fix.sh --revert   # restore stock and reboot
```

The blueangel HAL stays **stock** — earlier revisions smuggled the PSM through
the `mtu` argument and needed a HAL patch; that is retired. `--revert` restores
the original bytes and clears the code caves in place, so it needs no pristine
copy of `mtkbt`.

Verified end to end: handshake ACK `01 00 04 00`, features ACK
`04 00 04 00 2b 00`, then ear-detection notifications, with pause/resume firing
in the same millisecond as the packet for both buds and both directions.

## Y1 (MT6572, Android 4.2.2) — `build.sh` / `install.sh`

Different device, different bug, different layer. Y1 uses MediaTek's older JNI
socket service, which passes the Java port straight through as `psm_channel`
and only gets `ps_type` wrong — tagging every client connect `ps_type=1` (the
RFCOMM path) instead of `ps_type=2`. `src/build_patch.py` binary-patches
`/system/lib/libextjsr82.so` so a PSM-looking value (`>= 0x100`) selects the
raw L2CAP path; normal RFCOMM channels (1-30) are untouched.

```
./build.sh     # pull the live lib, produce build/libextjsr82_patched.so
./install.sh   # back up stock -> libextjsr82_real.so, install, reboot
./status.sh    # which lib is active, plus relevant logcat
./revert.sh    # restore stock, reboot
```

Needs `pip3 install keystone-engine lief`. `build_patch.py` refuses to patch if
the bytes at the hardcoded hook don't match, so re-derive the offsets if your
firmware differs. The Y1 patch has nothing to port to the Y2 and vice versa.

## Diagnostics

`mtkbt` ships thousands of internal traces that never reach logcat — its trace
sink targets MediaTek's Catcher transport and its gates are compiled shut.
Opening them is what made this tractable; before that it was patch-and-guess,
and two conclusions came out wrong.

- `y2_trace_to_logcat.sh` — redirect the trace sink to `__android_log_print`
  and open the gates. Text traces appear under `MTKBTD`, numeric ids under
  `MTKID`. **Chatty; diagnostic only, don't leave it installed.**
- `decode_trace_ids.py` — turn those numeric ids back into MediaTek's own
  sentences using `bluetooth_trc.h` from public MT6577 BSP dumps.
  **JSR82 ids need `-8`**; lower ids decode at face value. Getting that wrong
  reads plausibly and inverts conclusions.

```
adb logcat -s MTKID | ./decode_trace_ids.py -8
```

`Y2_INVESTIGATION.md` is the full trail, including the dead ends and the traps
worth not rediscovering (`mtkbt` is a `oneshot` init service, so a crash looks
identical to "never started" — check `/data/tombstones`, and never leave
Bluetooth disabled across a reboot).
