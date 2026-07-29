#!/usr/bin/env python3
"""Turn adb back on in a stock Y2 boot.img.

The 3.1.7 stock ROM ships adb comprehensively off: no /sbin/adbd in the ramdisk at all, and
default.prop carrying ro.secure=1, ro.debuggable=0, ro.adb.secure=1 and a USB config of
mass_storage. Flashing that leaves a device nothing can be pushed to -- no patch-device.sh, no
build-flash, no root shell -- and the only way back in is reflashing a boot image from a machine
that already has one.

None of this lives in system.img, which is the only partition the ROM build otherwise touches,
so it has to be fixed here. Two changes are enough, because the stock init.usb.rc already carries

    on property:sys.usb.config=mtp,adb
        ...
        start adbd

so no init.rc edit is needed -- the service exists and is triggered by the USB config:

  1. add /sbin/adbd (scripts/y2-boot/adbd, taken from the Y2 dev image; byte-identical to the
     adbd running on the development unit)
  2. set the four adb properties in default.prop

MediaTek wraps both the kernel and the ramdisk in a 512-byte header -- magic 0x88168858 then a
name like "ROOTFS" -- ahead of the real gzip stream. That header records the payload size, so it
is rebuilt rather than carried over: the patched ramdisk is a different length, and a stale size
field means a device that doesn't boot.

usage: patch-boot-adb.py <boot.img> <adbd> [output.img]
"""
import gzip
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile

MTK_MAGIC = bytes.fromhex("88168858")
MTK_HEADER_SIZE = 512
BOOT_MAGIC = b"ANDROID!"

WANTED_PROPS = {
    "ro.secure": "0",
    "ro.debuggable": "1",
    "ro.adb.secure": "0",
    "persist.sys.usb.config": "mtp,adb",
}


def read_boot(path):
    """Split an Android boot.img into (header, kernel, ramdisk, tail, page_size)."""
    data = open(path, "rb").read()
    if data[:8] != BOOT_MAGIC:
        raise SystemExit("%s is not an Android boot image" % path)
    kernel_size, _, ramdisk_size, _ = struct.unpack("<IIII", data[8:24])
    page = struct.unpack("<I", data[36:40])[0]

    def pages(n):
        return (n + page - 1) // page * page

    kernel_at = page
    ramdisk_at = kernel_at + pages(kernel_size)
    ramdisk = data[ramdisk_at:ramdisk_at + ramdisk_size]
    return data, ramdisk_at, ramdisk_size, ramdisk, page


def unwrap_mtk(blob):
    """Strip MediaTek's 512-byte header, returning (name, payload). Name is None if absent."""
    if blob[:4] != MTK_MAGIC:
        return None, blob
    name = blob[8:40].split(b"\x00")[0].decode("ascii", "replace")
    return name, blob[MTK_HEADER_SIZE:]


def wrap_mtk(name, payload):
    """Rebuild the MediaTek header around a payload of a new size."""
    header = bytearray(b"\xff" * MTK_HEADER_SIZE)
    header[0:4] = MTK_MAGIC
    header[4:8] = struct.pack("<I", len(payload))
    encoded = name.encode("ascii")
    header[8:8 + len(encoded)] = encoded
    header[8 + len(encoded)] = 0
    return bytes(header) + payload


def patch_default_prop(text):
    """Set the adb properties, replacing existing lines rather than appending duplicates."""
    lines = text.splitlines()
    out = []
    seen = set()
    for line in lines:
        key = line.split("=", 1)[0].strip()
        if key in WANTED_PROPS:
            out.append("%s=%s" % (key, WANTED_PROPS[key]))
            seen.add(key)
        else:
            out.append(line)
    for key, value in WANTED_PROPS.items():
        if key not in seen:
            out.append("%s=%s" % (key, value))
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__.strip().splitlines()[-1])
    boot_path, adbd_path = sys.argv[1], sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else boot_path

    data, ramdisk_at, ramdisk_size, ramdisk_blob, page = read_boot(boot_path)
    mtk_name, gzipped = unwrap_mtk(ramdisk_blob)
    cpio_bytes = gzip.decompress(gzipped)

    work = tempfile.mkdtemp(prefix="y2boot-")
    try:
        root = os.path.join(work, "rd")
        os.makedirs(root)
        subprocess.run(["cpio", "-idm", "--quiet"], cwd=root, input=cpio_bytes, check=True)

        os.makedirs(os.path.join(root, "sbin"), exist_ok=True)
        shutil.copy(adbd_path, os.path.join(root, "sbin", "adbd"))
        os.chmod(os.path.join(root, "sbin", "adbd"), 0o750)

        prop_path = os.path.join(root, "default.prop")
        before = open(prop_path).read() if os.path.exists(prop_path) else ""
        open(prop_path, "w").write(patch_default_prop(before))

        # Newc format, sorted for reproducibility; init needs uid/gid 0 throughout.
        listing = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames.sort()
            for name in sorted(dirnames) + sorted(filenames):
                full = os.path.join(dirpath, name)
                listing.append(os.path.relpath(full, root))
        listing.sort()
        packed = subprocess.run(
            ["cpio", "-o", "-H", "newc", "--quiet", "-R", "0:0"],
            cwd=root, input="\n".join(listing).encode(), stdout=subprocess.PIPE, check=True,
        ).stdout
    finally:
        shutil.rmtree(work, ignore_errors=True)

    buf = io.BytesIO()
    # mtime 0 so the same inputs produce the same image.
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(packed)
    new_ramdisk = buf.getvalue()
    if mtk_name:
        new_ramdisk = wrap_mtk(mtk_name, new_ramdisk)

    def pages(n):
        return (n + page - 1) // page * page

    out = bytearray(data[:ramdisk_at])
    out[16:20] = struct.pack("<I", len(new_ramdisk))  # ramdisk_size in the boot header
    out += new_ramdisk
    out += b"\x00" * (pages(len(new_ramdisk)) - len(new_ramdisk))
    out += data[ramdisk_at + pages(ramdisk_size):]  # second stage / device tree, untouched

    open(out_path, "wb").write(bytes(out))
    print("   ramdisk %d -> %d bytes, adbd added, adb properties set"
          % (ramdisk_size, len(new_ramdisk)))


if __name__ == "__main__":
    main()
