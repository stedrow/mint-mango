# AirPods RTP-Timestamp Fix (Y1)

Makes **AirPods (Pro 2 / Pro 3)** actually play audio through the Innioasis Y1.

## The problem

AirPods pair and connect to the Y1, the player shows a track playing, but **no
sound comes out**. The root cause is in the Y1's MediaTek Bluetooth firmware,
*not* the launcher: the stock A2DP/SBC media stream carries broken RTP
timestamps. Most headphones ignore RTP timestamps, but modern AirPods firmware
validates them and silently drops the stream when they don't progress cleanly.

## The fix

A small shim library (`libbluetoothdrv_proxy.c`) is installed in place of
`/system/lib/libbluetoothdrv.so`. It wraps the five `mtk_bt_*` entry points of
the stock driver (renamed to `libbluetoothdrv_real.so` and loaded at runtime via
`dlopen`), and rewrites each outgoing SBC media packet's RTP timestamp with a
clean, monotonically increasing value (`frame_count × 128` samples per packet).
Everything else is passed straight through.

This is a source-level reimplementation of the approach from
[Semy0nBu/y1-airpods-rtpfix](https://github.com/Semy0nBu/y1-airpods-rtpfix)
(which targets official firmware 3.0.7 + SP Flash Tool). Our version is built
against **this device's own ABI** and installed **live over adb** — no SP Flash
Tool, no `system.img` patching.

### Why the live adb swap is safe on this firmware

Verified on our build (`Y1_001`, Android 4.2.2 / API 17, MT6572):

- The stock `libbluetoothdrv.so` is linked by exactly one consumer,
  `/system/bin/mtkbt`, and it imports **only** the five `mtk_bt_*` functions the
  proxy re-exports.
- The proxy omits the driver's five `bt_*` exports (`bt_send_data`, etc.), but
  nothing on the system resolves those symbols from this library across
  libraries, so omitting them changes nothing.
- `/system` is a normal ext4 partition that remounts read-write, and adb runs as
  root — so we can swap the file and reboot. The stock driver is preserved, so
  the change is fully reversible.

> If you flash a substantially different firmware, re-verify the two bullet
> points above before trusting the swap (`readelf -d`/`nm -D` on the new
> `libbluetoothdrv.so` and `mtkbt`).

## Usage

```bash
# One-time toolchain setup (macOS):
brew install --cask android-ndk        # NDK r29+ (clang)
brew install lld android-platform-tools

# With the Y1 plugged in via USB:
./build.sh      # compile the proxy against the device ABI  -> build/libbluetoothdrv.so
./install.sh    # back up stock driver, install proxy, reboot
./status.sh     # confirm it's mapped + tail the fix log while audio plays
./revert.sh     # restore the stock driver if needed
```

### Verifying it works

Run `./status.sh`, then connect the AirPods and play a track. You should see
`BTRTPFIX` log lines like:

```
BTRTPFIX index=0 len=... seq=... frame_count=5 bitpool=0x23 old_ts=... new_ts=0 increment=640 ...
```

`new_ts` starting at 0 and climbing by `frame_count × 128` each packet is the
fix rewriting the stream. If you hear audio, you're done.

You should also see one `BTLSTO` line each time the AirPods connect, confirming
the supervision timeout was shortened:

```
BTLSTO index=0 handle=0x00b set link_supervision_timeout slots=6400 (~4000ms)
```

To confirm the recovery end-to-end: with a track playing, cover the top of the
Y1 with your hand (or pocket it) until audio drops, then uncover it — it should
reconnect on its own within a few seconds instead of needing a manual Connect.

## Build variants

`build.sh` defaults to the recommended configuration: **RTP timestamp fix on,
SBC bitpool clamp off** (upstream ships the clamp off — it lowers audio quality
and isn't needed once timestamps are fixed).

| Env var | Effect |
| --- | --- |
| `CLAMP_BITPOOL=1` | Also clamp SBC max bitpool 53→35. Only try this if audio still drops on the plain fix. Lower quality. |
| `QUIET=1` | Disable verbose `BTDUMP`/`BTCTRL` packet logging (leaves `BTRTPFIX` on). Use for a "production" build once verified. |
| `LSTO_SLOTS=8000` | Tune the shortened link-supervision timeout (units: 0.625 ms slots; `8000` = 5 s). Raise it if brief signal fades cause needless full reconnects; lower it for faster recovery. Default `6400` = 4 s. |
| `LINK_SUPERVISION_TIMEOUT=0` | Leave the controller's stock ~20 s supervision timeout untouched (disables the connectivity-recovery behavior below). |

## Connectivity recovery (link-supervision timeout)

The RTP fix above makes AirPods *play*; this second behavior makes them
*recover* quickly after a signal drop. Near-field body attenuation — a hand over
the Y1's antenna, or the Y1 in a pocket — drops the 2.4 GHz link even at close
range. That's physics, not software, so the proxy can't prevent the drop. What
it *can* fix is the recovery: the MediaTek controller's default ACL
link-supervision timeout is ~20 s (`0x7D00`), so after the radio link is
physically gone the stack still reports the AirPods as connected for up to 20 s
— audio is silent, Bluetooth settings still says "connected", and the user has
to manually tap Connect.

Because the proxy sits on the raw H4 HCI transport (`mtk_bt_write` /
`mtk_bt_read`), it watches for the `Connection_Complete` event, grabs the new
connection handle, and injects an `HCI_Write_Link_Supervision_Timeout`
(opcode `0x0C37`) that shrinks the window to ~4 s. A dropout is then detected in
a few seconds, and the launcher's reconnect watchdog reconnects automatically —
no manual tap. The injected command's `Command_Complete` is swallowed on the
read path so the host stack never sees an event for a command it didn't send.

## Files

| File | Purpose |
| --- | --- |
| `src/libbluetoothdrv_proxy.c` | The proxy/shim source. |
| `build.sh` | Compile + link against the device ABI. |
| `install.sh` | Back up stock driver, install proxy, reboot. |
| `revert.sh` | Restore the stock driver. |
| `status.sh` | Show install state + tail the fix log. |
| `devlibs/` | Cached crt objects + libs pulled from the device (git-ignored; regenerated by `build.sh`). |
| `build/` | Build output (git-ignored). |

## Credit

RTP-timestamp normalization approach and the original proxy design:
[Semy0nBu/y1-airpods-rtpfix](https://github.com/Semy0nBu/y1-airpods-rtpfix).
