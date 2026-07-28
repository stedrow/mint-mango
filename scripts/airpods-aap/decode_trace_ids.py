#!/usr/bin/env python3
"""Decode mtkbt's numeric kal_trace ids into MediaTek's own trace text.

y2_trace_to_logcat.sh surfaces the leveled trace helper's ids as `MTKID kal
id=<hex>`, which on their own say nothing. The ids are indices into the
TRC_MSG enum in blueangel's bluetooth_trc.h, which *is* public (MT6577 BSP
dumps -- see Y2_INVESTIGATION.md for the URL). This turns the ids back into
strings, which is how the JSR82 connect path was finally read.

IMPORTANT -- the enum is not aligned across the whole range. The header is from
MT6577 and the device is MT6582, and eight entries were inserted somewhere
between the SDP block and the JSR82 block. Ids below roughly 0x800 decode at
face value; **ids in the JSR82 block (~0xc00 and up) need -8**. Pass the offset
explicitly with -8 for those, or the decode reads plausibly and is wrong -- that
mistake cost a full round trip here, turning "JSR82 L2CAP Client connected" into
"Get L2CAP PSM Index" and inverting the conclusion.

The -8 offset in the JSR82 block is pinned by three independent anchors:
0x47b74 logs a four-argument trace and only BT_JSR82_L2CAPCALL_INFO
("JSR82 L2CAP Callback: session_inx=%d,l2ChnlId=%d,con_id=%d, event=%d") takes
four; 0x47b9a sits on the invalid-session-index branch and decodes to "NO
matched index in context"; 0x47f20 sits on the error branch and decodes to
"JSR82 L2Cap Open Chnl failed".

Calibrated at face value against three ids whose surrounding text traces are
unambiguous:
0x630 -> "L2CapState_OPEN() Cid=0x%x, event=0x%x" (logged exactly at
"l2cap: enter open state"), 0x70f -> "SDP Client: Sending query packet"
(immediately before the SDP request on the wire), 0x636 -> "L2Cap_GetSysPkt".
The header is from MT6577 rather than MT6582, so treat a decode that reads
implausibly as a possible small offset rather than as fact -- check the
argument count against the call site before relying on it.

Usage:
  ./decode_trace_ids.py -8 0xc4c 0xc4d           # JSR82 block: pass -8
  ./decode_trace_ids.py 0x630                    # low block: no offset
  adb logcat -s MTKID | ./decode_trace_ids.py -8 # decode a captured stream
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
    argv = sys.argv[1:]
    offset = 0
    if argv and re.fullmatch(r'[-+]\d+', argv[0]):
        offset = int(argv[0])
        argv = argv[1:]
    if argv:
        for a in argv:
            i = int(a, 16) + offset
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
        i = int(m.group(1), 16) + offset
        text = names[i][1] if i < len(names) else "<out of range>"
        sys.stdout.write(line.rstrip() + "   ; " + text + "\n")


if __name__ == "__main__":
    main()
