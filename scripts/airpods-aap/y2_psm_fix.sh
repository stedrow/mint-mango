#!/usr/bin/env bash
#
# y2_psm_fix.sh — Put a real PSM into the Y2's raw L2CAP client connects by
# patching MediaTek's vendor Bluetooth HAL.
#
# Background (see Y2_INVESTIGATION.md): every Connection Request the Y2 sends for
# a BluetoothSocket L2CAP connect carries PSM 0x0000, which peers correctly
# refuse -- that refusal is the "AAP connect failed" the launcher sees. Stock
# AOSP 4.4 has no L2CAP client sockets at all; MediaTek bolted the path onto
# their JSR82 session layer, and the PSM never survives the trip.
#
# MediaTek's own header (from an MT6582 BSP dump, frameworks/bluetooth/blueangel/
# btadp_ext/include/bt_jsr82_api.h) gives the real signature:
#
#   BT_BOOL btmtk_jsr82_session_connect_req(
#               kal_uint32 transaction_id, kal_uint8 *bd_addr, kal_uint8 ps_type,
#               kal_uint16 psm_channel, kal_uint16 mtu,
#               kal_uint8 security_value, kal_uint8* status_result);
#
# and bluedroid's client side packs those into bt_jsr82_connect_req_struct
# correctly -- psm_channel lands in the message's `channel` field. Forcing that
# argument changes nothing on the wire, because the bug is in the *daemon*:
# mtkbt's connect handler zeroes its context's channel slot and never copies
# msg->channel into it, then builds the L2CAP request from that zero.
#
#   0x6bdfa (mtkbt): str r6,[r5,#0x20]   ctx.channel := 0   (never re-filled)
#   0x6c4e0 (mtkbt): ldr r1,[r4,#0x20]   request.channel := ctx.channel
#
# The context slot can't simply be filled in place: the register holding that
# zero also writes ctx+0x38, which is a live field read elsewhere (clobbering it
# crashed mtkbt). So the PSM travels in the one field the daemon *does* copy --
# mtu -- and the request's channel is sourced from there:
#
#   blueangel 0x27480: f8bd 203c  ldrh.w r2,[sp,#0x3c]  -> f241 0201  movw r2,#0x1001
#                      (the mtu argument becomes the AAP PSM)
#   mtkbt     0x6c4e0: 6a21       ldr  r1,[r4,#0x20]    -> 8ce1       ldrh r1,[r4,#0x26]
#                      (request.channel := ctx.mtu)
#
# Side effect: the L2CAP MTU requested during config becomes 4097 instead of
# 1000, which is negotiated down by the peer.
#
# Verify any change with the transport snoop (`SNOOP=1 scripts/airpods-rtpfix/build.sh`):
# check the Connection Request's PSM, and confirm no BTPSM line is doing the work
# (the proxy has its own PSM rewrite behind PSMFIX=1 -- keep it off when testing
# this patch, or you will credit the wrong mechanism, as happened once already).
#
# This hardcodes every raw L2CAP *client* connect on the device to PSM 0x1001.
# Nothing else on this firmware uses that path -- the stack's own profiles (SDP,
# AVCTP, AVDTP) have their own PSMs and are unaffected.
#
# Usage:
#   ./y2_psm_fix.sh          # patch and reboot
#   ./y2_psm_fix.sh --revert # restore the stock HAL and reboot
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"

HAL_TARGET="/system/vendor/lib/hw/bluetooth.blueangel.so"
BT_TARGET="/system/bin/mtkbt"

# offset:stock:patch
HAL_PATCHES="0x27480:bdf83c20:41f20102"
BT_PATCHES="0x6c4e0:216a:e18c"

adb get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the Y2." >&2; exit 1; }
adb shell 'id' | grep -q 'uid=0' || { echo "ERROR: adb shell is not root on this device." >&2; exit 1; }
[ "$(adb shell getprop ro.product.device | tr -d '\r\n')" = "Y2" ] || {
  echo "ERROR: Y2-only (offsets are specific to these firmware builds)." >&2; exit 1; }

# mtkbt runs whenever Bluetooth is on, and a running binary can't be overwritten
# in place ("Text file busy"). Turning Bluetooth off stops the service.
stop_bluetooth() {
  adb shell 'service call bluetooth_manager 8' >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    adb shell 'ps' | grep -q '/system/bin/mtkbt' || break
    sleep 2
  done
}

# install_file <target> <local_patched> <mode>   (mode: 644 for the HAL, 755 for mtkbt)
install_file() {
  local target="$1" local_file="$2" mode="$3" backup="$1.stock" tmp="/data/local/tmp/patched.bin"
  adb shell "mount -o remount,rw /system && ([ -f $backup ] || cp $target $backup)"
  adb push "$local_file" "$tmp" >/dev/null
  # rename-then-write so a lingering handle on the old file can't block the swap
  adb shell "mv $target $target.busy 2>/dev/null; cat $tmp > $target \
             && chmod $mode $target && rm -f $tmp $target.busy && sync"
  local l d
  l="$(md5 -q "$local_file" 2>/dev/null || md5sum "$local_file" | awk '{print $1}')"
  d="$(adb shell "md5 $target" | awk '{print $1}' | tr -d '\r')"
  [ "$l" = "$d" ] || { echo "ERROR: md5 mismatch on $target ($l vs $d)" >&2; exit 1; }
  echo "   installed $target (md5 $l)"
}

if [ "${1:-}" = "--revert" ]; then
  stop_bluetooth
  for t in "$HAL_TARGET" "$BT_TARGET"; do
    if adb shell "[ -f $t.stock ]" 2>/dev/null; then
      echo ">> Restoring stock $t"
      adb shell "mount -o remount,rw /system && mv $t $t.busy 2>/dev/null; \
                 cat $t.stock > $t && rm -f $t.busy && sync"
    else
      echo ">> No backup for $t, skipping"
    fi
  done
  adb shell "chmod 644 $HAL_TARGET; chmod 755 $BT_TARGET; sync"
  echo ">> Rebooting"
  adb reboot
  exit 0
fi

mkdir -p "$BUILD"

patch_one() {
  local target="$1" stock_out="$2" patched_out="$3"; shift 3
  echo ">> Pulling $target"
  adb pull "$target" "$stock_out" >/dev/null
  python3 - "$stock_out" "$patched_out" "$@" <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
data = bytearray(open(src, 'rb').read())
for spec in sys.argv[3:]:
    off_s, stock, patch = spec.split(":")
    off = int(off_s, 16)
    stock_b, patch_b = bytes.fromhex(stock), bytes.fromhex(patch)
    found = bytes(data[off:off + len(stock_b)])
    if found == patch_b:
        print("   %#x already patched" % off)
        continue
    if found != stock_b:
        # Firmware drift: refuse rather than corrupt the binary.
        sys.exit("ERROR: unexpected bytes at %#x (%s, wanted %s). Re-derive the "
                 "offset before patching." % (off, found.hex(), stock))
    data[off:off + len(patch_b)] = patch_b
    print("   %#x %s -> %s" % (off, stock, patch))
open(dst, 'wb').write(bytes(data))
PY
}

patch_one "$HAL_TARGET" "$BUILD/blueangel_stock.so" "$BUILD/blueangel_patched.so" $HAL_PATCHES
patch_one "$BT_TARGET" "$BUILD/mtkbt_stock" "$BUILD/mtkbt_patched" $BT_PATCHES

stop_bluetooth
install_file "$HAL_TARGET" "$BUILD/blueangel_patched.so" 644
install_file "$BT_TARGET" "$BUILD/mtkbt_patched" 755

echo ">> Rebooting"
adb reboot
