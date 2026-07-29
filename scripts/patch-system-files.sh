#!/usr/bin/env bash
# Applies the system-file patches the launcher needs, in place, to a directory holding copies of
# a device's platform.xml and android.policy.jar:
#
#   <workdir>/platform.xml         -- gains gids "input" and "media" on WRITE_MEDIA_STORAGE.
#                                     "input" lets the launcher read /dev/input/event0 and time
#                                     the power key itself (KEYCODE_POWER never reaches an app);
#                                     "media" lets it open /dev/fm (system:media 0660) for the FM
#                                     radio, which otherwise fails with permission denied
#   <workdir>/android.policy.jar   -- loses the showGlobalActionsDialog() call in
#                                     PhoneWindowManager, so the stock Power off / Restart dialog
#                                     never appears. Only that call goes: mPowerKeyHandled is set
#                                     just above it, and without that flag releasing the key
#                                     reads as a short press and sleeps the device
#
# Both edits are idempotent. Callers handle everything around the files: getting them here,
# putting them back, moving android.policy.odex aside (a stale .odex wins over the patched
# classes.dex), and installing the launcher into /system/priv-app -- since Android 4.3 only
# privileged apps are granted the signature|system permission that carries the gid above.
#
# Used by patch-device.sh (live device, over adb) and build-rom.sh (offline, inside a
# mounted system.img).
set -euo pipefail

WORKDIR="${1:?usage: patch-system-files.sh <workdir-with-platform.xml-and-android.policy.jar>}"
# Absolute, since the jar work cds into a staging dir before touching these paths.
WORKDIR="$(cd "$WORKDIR" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/scripts/.smali-cache"
JAVA="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin/java"
SMALI_VERSION="2.5.2"
MAVEN="https://repo1.maven.org/maven2"

PLATFORM_XML="$WORKDIR/platform.xml"
POLICY_JAR="$WORKDIR/android.policy.jar"
[ -f "$PLATFORM_XML" ] || { echo "missing $PLATFORM_XML" >&2; exit 1; }
[ -f "$POLICY_JAR" ] || { echo "missing $POLICY_JAR" >&2; exit 1; }

# --- platform.xml -----------------------------------------------------------------------------
# Per-gid rather than "has this file been touched": a unit patched before the radio fix carries
# the marker comment but only the "input" group, and a whole-file check would skip it forever.
set +e
python3 - "$PLATFORM_XML" <<'PY'
import re
import sys

path = sys.argv[1]
src = open(path).read()
start = src.find('<permission name="android.permission.WRITE_MEDIA_STORAGE" >')
if start < 0:
    sys.stderr.write("WRITE_MEDIA_STORAGE block not found\n")
    sys.exit(1)
end = src.index("</permission>", start)
# rstrip so appended lines don't inherit the closing tag's indent; it's put back below.
block = src[start:end].rstrip() + "\n"

# "input": /dev/input/event0, for timing the power key. "media": /dev/fm, for the radio.
missing = [gid for gid in ("input", "media") if '<group gid="%s" />' % gid not in block]
if not missing:
    sys.exit(3)

if "mint-mango" not in block:
    block += '''        <!-- mint-mango: "input" lets the launcher read /dev/input and time the power key
             itself; "media" lets it open /dev/fm for the radio. WRITE_MEDIA_STORAGE is
             signature|system, so only priv-app holders get these. -->\n'''
block += "".join('        <group gid="%s" />\n' % gid for gid in missing)
open(path, "w").write(src[:start] + block + "    " + src[end:])
sys.stderr.write("added gid(s): %s\n" % ", ".join(missing))
PY
STATUS=$?
set -e
case "$STATUS" in
  0) echo "    platform.xml patched" ;;
  3) echo "    platform.xml already patched" ;;
  *) echo "platform.xml isn't shaped as expected -- not patching" >&2; exit 1 ;;
esac

# --- android.policy.jar -----------------------------------------------------------------------
[ -x "$JAVA" ] || { echo "java not found at $JAVA -- set JAVA_HOME" >&2; exit 1; }

mkdir -p "$CACHE"
for dep in "org/smali/baksmali/$SMALI_VERSION/baksmali-$SMALI_VERSION.jar" \
           "org/smali/smali/$SMALI_VERSION/smali-$SMALI_VERSION.jar" \
           "org/smali/dexlib2/$SMALI_VERSION/dexlib2-$SMALI_VERSION.jar" \
           "org/smali/util/$SMALI_VERSION/util-$SMALI_VERSION.jar" \
           "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar" \
           "com/beust/jcommander/1.78/jcommander-1.78.jar" \
           "commons-cli/commons-cli/1.5.0/commons-cli-1.5.0.jar" \
           "org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar" \
           "org/antlr/ST4/4.3.1/ST4-4.3.1.jar"; do
  [ -f "$CACHE/$(basename "$dep")" ] || curl -fsSL -o "$CACHE/$(basename "$dep")" "$MAVEN/$dep"
done

JARDIR="$WORKDIR/.policy-jar"
rm -rf "$JARDIR" "$WORKDIR/.policy-smali"
mkdir -p "$JARDIR"
(cd "$JARDIR" && unzip -q "$POLICY_JAR")
"$JAVA" -cp "$CACHE/*" org.jf.baksmali.Main d "$JARDIR/classes.dex" -o "$WORKDIR/.policy-smali"

# Exit 0 = edited, 3 = the call is already gone, anything else = the jar isn't shaped the way
# this patch expects. Note the marker comment can't be the "already patched" test: comments don't
# survive being assembled back into a dex, so a patched jar carries no trace of us beyond the
# missing call.
set +e
python3 - "$WORKDIR/.policy-smali/com/android/internal/policy/impl/PhoneWindowManager\$2.smali" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
call = '''    iget-object v1, p0, Lcom/android/internal/policy/impl/PhoneWindowManager$2;->this$0:Lcom/android/internal/policy/impl/PhoneWindowManager;

    invoke-virtual {v1}, Lcom/android/internal/policy/impl/PhoneWindowManager;->showGlobalActionsDialog()V

'''
found = src.count(call)
if found == 0:
    sys.exit(3)
if found != 1:
    sys.stderr.write("expected 1 showGlobalActionsDialog() call site, found %d\n" % found)
    sys.exit(1)
open(path, 'w').write(src.replace(call, '''    # mint-mango: showGlobalActionsDialog() call removed -- the launcher draws its own power
    # menu. mPowerKeyHandled is still set above, so releasing power isn't read as a short press.

'''))
PY
STATUS=$?
set -e

case "$STATUS" in
  0)
    "$JAVA" -cp "$CACHE/*" org.jf.smali.Main a "$WORKDIR/.policy-smali" -o "$JARDIR/classes.dex" --api 19
    rm -f "$POLICY_JAR"
    (cd "$JARDIR" && zip -q -r -X "$POLICY_JAR" META-INF classes.dex)
    echo "    android.policy.jar patched"
    ;;
  3) echo "    android.policy.jar already patched" ;;
  *) echo "android.policy.jar isn't shaped as expected -- not patching" >&2; exit 1 ;;
esac

rm -rf "$JARDIR" "$WORKDIR/.policy-smali"
