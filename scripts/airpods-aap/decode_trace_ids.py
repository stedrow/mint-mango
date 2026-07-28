#!/usr/bin/env python3
"""Decode mtkbt's numeric kal_trace ids into MediaTek's own trace text.

y2_trace_to_logcat.sh surfaces the leveled trace helper's ids as `MTKID kal
id=<hex>`, which on their own say nothing. The ids are indices into the
TRC_MSG enum in blueangel's bluetooth_trc.h, which *is* public (MT6577 BSP
dumps -- see Y2_INVESTIGATION.md for the URL). This turns the ids back into
strings, which is how the JSR82 connect path was finally read.

Calibrated against three ids whose surrounding text traces are unambiguous:
0x630 -> "L2CapState_OPEN() Cid=0x%x, event=0x%x" (logged exactly at
"l2cap: enter open state"), 0x70f -> "SDP Client: Sending query packet"
(immediately before the SDP request on the wire), 0x636 -> "L2Cap_GetSysPkt".
The header is from MT6577 rather than MT6582, so treat a decode that reads
implausibly as a possible small offset rather than as fact -- check the
argument count against the call site before relying on it.

Usage:
  ./decode_trace_ids.py 0xc4c 0xc4d          # decode specific ids
  adb logcat -s MTKID | ./decode_trace_ids.py  # decode a live/captured stream
"""
import re
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = os.path.join(HERE, "bluetooth_trc.h")


def load():
    names = []
    with open(HEADER, errors="ignore") as f:
        for line in f:
            m = re.match(r'\s*TRC_MSG\(\s*([A-Za-z0-9_]+)\s*,\s*"(.*)"\s*\)', line)
            if m:
                names.append((m.group(1), m.group(2)))
    return names


def main():
    names = load()
    if len(sys.argv) > 1:
        for a in sys.argv[1:]:
            i = int(a, 16)
            if i < len(names):
                print("%#06x  %s  %s" % (i, names[i][0], names[i][1]))
            else:
                print("%#06x  <out of range>" % i)
        return
    for line in sys.stdin:
        m = re.search(r'kal id=([0-9a-f]+)', line)
        if not m:
            sys.stdout.write(line)
            continue
        i = int(m.group(1), 16)
        text = names[i][1] if i < len(names) else "<out of range>"
        sys.stdout.write(line.rstrip() + "   ; " + text + "\n")


if __name__ == "__main__":
    main()
