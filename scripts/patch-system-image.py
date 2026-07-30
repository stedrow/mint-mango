#!/usr/bin/env python3
"""Apply the launcher and its system patches to a Y2 system.img, offline.

Everything happens through debugfs -- no loop mount, no sudo, no privileged container -- so the
ROM build runs anywhere e2fsprogs does, macOS included. That is not just convenience: the old
mount-and-cp approach silently dropped SELinux labels on any file it *created* rather than
overwrote, and this firmware runs enforcing. An unlabeled APK in /system/priv-app is one the
package manager may refuse to read, on a partition that is never relabeled at boot -- i.e. a
flashed device with no launcher. Labels here are read off the file being replaced and written
back explicitly.

What it changes:
  * /priv-app/<launcher>.apk        installed (and any stale copy in /app removed)
  * /priv-app/MyLauncher.apk        renamed aside, so the stock launcher isn't scanned
  * /etc/permissions/platform.xml   gids "input" and "media" (power key, /dev/fm)
  * /framework/android.policy.jar   stock power dialog removed
  * /framework/android.policy.odex  renamed aside so Dalvik uses the patched jar
  * /bin/mtkbt                      AAP L2CAP fix

usage: patch-system-image.py --image system.img --apk app.apk --scripts <repo>/scripts
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

LAUNCHER_APK = "com.themoon.y1.apk"
STOCK_LAUNCHER = "MyLauncher.apk"
STOCK_LAUNCHER_ODEX = "MyLauncher.odex"
POLICY_JAR = "android.policy.jar"
POLICY_ODEX = "android.policy.odex"
PLATFORM_XML = "platform.xml"
MTKBT = "mtkbt"

# Fallback only. Every replacement copies the label off the file it replaces; this is used when
# a file is created where none existed, and matches what stock /system/priv-app carries.
DEFAULT_CONTEXT = b"u:object_r:system_file:s0\x00"


def tool(name):
    """e2fsprogs is keg-only under Homebrew, so prefer that copy when it exists."""
    brew = "/opt/homebrew/opt/e2fsprogs/sbin/" + name
    return brew if os.path.exists(brew) else name


def debugfs(image, commands, write=False, check=True):
    with tempfile.NamedTemporaryFile("w", suffix=".debugfs", delete=False) as handle:
        handle.write("\n".join(commands) + "\n")
        script = handle.name
    try:
        cmd = [tool("debugfs")]
        if write:
            cmd.append("-w")
        cmd += ["-f", script, image]
        result = subprocess.run(cmd, capture_output=True, text=True)
    finally:
        os.unlink(script)
    if check and result.returncode != 0:
        raise SystemExit("debugfs failed:\n%s\n%s" % (result.stdout, result.stderr))
    # debugfs reports most per-command problems on stdout with a zero exit status, so the
    # output has to be read rather than trusted.
    for noise in ("File not found", "Invalid", "error while", "Illegal"):
        if noise.lower() in result.stdout.lower():
            raise SystemExit("debugfs reported a problem:\n%s" % result.stdout.strip())
    return result.stdout


def exists(image, path):
    out = debugfs(image, ["stat %s" % path], check=False)
    return "Inode:" in out


def dump(image, path, dest):
    debugfs(image, ["dump %s %s" % (path, dest)])
    if not os.path.exists(dest) or os.path.getsize(dest) == 0:
        raise SystemExit("could not read %s out of the image" % path)


def context_of(image, path):
    """The file's SELinux label, so a replacement can be written back with the same one."""
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        out = handle.name
    try:
        debugfs(image, ["ea_get -f %s %s security.selinux" % (out, path)], check=False)
        data = open(out, "rb").read()
    finally:
        os.unlink(out)
    if not data:
        return None
    return data if data.endswith(b"\x00") else data + b"\x00"


def install(image, directory, name, source, mode, uid, gid, context):
    """Replace or create <directory>/<name>, carrying mode, owner and SELinux label."""
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        handle.write(context or DEFAULT_CONTEXT)
        ctx_file = handle.name
    try:
        commands = ["cd %s" % directory]
        if exists(image, "%s/%s" % (directory, name)):
            commands.append("rm %s" % name)
        commands += [
            "write %s %s" % (os.path.abspath(source), name),
            "sif %s mode %s" % (name, mode),
            "sif %s uid %d" % (name, uid),
            "sif %s gid %d" % (name, gid),
            "ea_set -f %s %s security.selinux" % (ctx_file, name),
        ]
        debugfs(image, commands, write=True)
    finally:
        os.unlink(ctx_file)


def rename_aside(image, directory, name):
    """Rename to <name>.bak, keeping the inode -- and so its label -- intact.

    debugfs has no mv: link the inode under the new name, then drop the old entry. The link
    count is untouched by both halves, so it stays correct at 1.
    """
    path = "%s/%s" % (directory, name)
    if not exists(image, path):
        return False
    debugfs(image, [
        "cd %s" % directory,
        "ln %s %s.bak" % (name, name),
        "unlink %s" % name,
    ], write=True)
    return True


def pad_to_declared_size(image):
    """The stock image is short of what its own superblock claims; e2fsck refuses it until fixed."""
    out = subprocess.run([tool("dumpe2fs"), "-h", image], capture_output=True, text=True).stdout
    fields = {}
    for line in out.splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
    try:
        declared = int(fields["Block count"]) * int(fields["Block size"])
    except (KeyError, ValueError):
        raise SystemExit("could not read the ext4 superblock from %s" % image)
    actual = os.path.getsize(image)
    if actual < declared:
        with open(image, "r+b") as handle:
            handle.truncate(declared)
        print("    padded %d -> %d bytes (its declared size)" % (actual, declared))


def fsck(image):
    result = subprocess.run([tool("e2fsck"), "-fy", image], capture_output=True, text=True)
    # 0 = clean, 1 = errors corrected. Higher means damage it could not fix.
    if result.returncode >= 4:
        raise SystemExit("e2fsck reported uncorrected errors (%d):\n%s"
                         % (result.returncode, result.stdout))
    print("    e2fsck exit %d (%s)"
          % (result.returncode, "clean" if result.returncode == 0 else "corrected"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True, help="raw ext4 system.img, edited in place")
    parser.add_argument("--apk", required=True, help="launcher APK to install")
    parser.add_argument("--scripts", required=True, help="repo scripts/ directory")
    args = parser.parse_args()

    image, scripts = args.image, os.path.abspath(args.scripts)
    work = tempfile.mkdtemp(prefix="y2img-")
    try:
        print("==> Preparing the image")
        pad_to_declared_size(image)

        print("==> Installing the launcher into /priv-app")
        # priv-app, not app: since Android 4.3 only privileged apps get signature|system
        # permissions -- REBOOT, SHUTDOWN, and the WRITE_MEDIA_STORAGE carrying gids "input"
        # (power key) and "media" (/dev/fm).
        stale = "/app/%s" % LAUNCHER_APK
        if exists(image, stale):
            debugfs(image, ["rm %s" % stale], write=True)
            print("    removed a stale copy from /app")
        install(image, "/priv-app", LAUNCHER_APK, args.apk, "0100644", 0, 0,
                context_of(image, "/priv-app/%s" % STOCK_LAUNCHER) or DEFAULT_CONTEXT)

        print("==> Standing down the stock launcher")
        # com.innioasis.y2 keeps running alongside ours and draws its own status-bar overlay.
        # PackageManager only scans *.apk, so renaming is enough -- and reversible.
        if rename_aside(image, "/priv-app", STOCK_LAUNCHER):
            rename_aside(image, "/priv-app", STOCK_LAUNCHER_ODEX)
        else:
            print("    already stood down")

        print("==> Patching platform.xml and android.policy.jar")
        for name, path in ((PLATFORM_XML, "/etc/permissions"), (POLICY_JAR, "/framework")):
            dump(image, "%s/%s" % (path, name), os.path.join(work, name))
        subprocess.run(["%s/patch-system-files.sh" % scripts, work], check=True)
        for name, path in ((PLATFORM_XML, "/etc/permissions"), (POLICY_JAR, "/framework")):
            install(image, path, name, os.path.join(work, name), "0100644", 0, 0,
                    context_of(image, "%s/%s" % (path, name)))

        print("==> Moving android.policy.odex aside")
        # A stale .odex wins over the patched classes.dex; Dalvik dexopts the jar on first boot.
        if not rename_aside(image, "/framework", POLICY_ODEX):
            print("    already moved")

        print("==> Baking in the AAP L2CAP fix (mtkbt)")
        dump(image, "/bin/%s" % MTKBT, os.path.join(work, MTKBT))
        env = dict(os.environ, MTKBT_FILE=os.path.join(work, MTKBT))
        shutil.rmtree("%s/airpods-aap/build" % scripts, ignore_errors=True)
        subprocess.run(["%s/airpods-aap/y2_aap_l2cap_fix.sh" % scripts], check=True, env=env)
        # mtkbt is root:shell (AID_SHELL is 2000) and carries its own label, not system_file.
        install(image, "/bin", MTKBT, os.path.join(work, MTKBT), "0100755", 0, 2000,
                context_of(image, "/bin/%s" % MTKBT))

        print("==> Reconciling the filesystem")
        fsck(image)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
