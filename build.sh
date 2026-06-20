#!/usr/bin/env bash
# Build script for the Minecraft rd-132211 S500i port.
#
# Produces dist/rd132211.jar (MIDlet suite) and dist/rd132211.jad.
# Requires a JDK 8 (for -target 1.3 bytecode) and ProGuard (for J2ME preverification).
#
# Override the JDK location with JAVA8_HOME if needed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAVA8_HOME="${JAVA8_HOME:-/opt/jdk8u402-b06}"
JAVAC="$JAVA8_HOME/bin/javac"
JAR="$JAVA8_HOME/bin/jar"

CLDC="libs/cldcapi11-2.0.4.jar"
MIDP="libs/midpapi20-2.0.4.jar"
BOOT="$CLDC:$MIDP"

NAME="mc-c0.0.11a"
BUILD="build"
STUBS_BUILD="stubs-build"
PRE="preverified"
DIST="dist"

echo ">> clean"
rm -rf "$BUILD" "$STUBS_BUILD" "$PRE" "$DIST"
mkdir -p "$BUILD" "$STUBS_BUILD" "$PRE" "$DIST"

echo ">> compile Mascot Capsule stubs (compile-only; NOT packaged)"
"$JAVAC" -source 1.3 -target 1.3 \
  -bootclasspath "$BOOT" \
  -d "$STUBS_BUILD" \
  $(find stubs -name "*.java")

echo ">> compile (game sources against Mascot Capsule + MIDP stubs)"
# The Mascot Capsule stubs are ONLY for compilation; they are
# provided natively by the phone and must NOT be packaged into the JAR.
"$JAVAC" -source 1.3 -target 1.3 \
  -bootclasspath "$BOOT" \
  -classpath "$STUBS_BUILD" \
  -d "$BUILD" \
  $(find src -name "*.java")

echo ">> preverify (ProGuard, -microedition adds CLDC StackMap attributes)"
proguard \
  -injars "$BUILD" \
  -outjars "$PRE" \
  -libraryjars "$BOOT" \
  -dontwarn -dontoptimize -dontobfuscate -dontshrink \
  -microedition \
  -keep 'public class * extends javax.microedition.midlet.MIDlet' \
  -keep 'public class com.mojang.** { *; }' >/dev/null

echo ">> assemble JAR"
STAGE="$(mktemp -d)"
cp -r "$PRE"/* "$STAGE"/ 2>/dev/null || true
# If ProGuard produced a jar instead of a dir, unzip it.
if [ -f "$PRE" ]; then unzip -oq "$PRE" -d "$STAGE"; fi
cp res/terrain.bmp "$STAGE"/terrain.bmp
cp res/icon.png "$STAGE"/icon.png

"$JAR" cfm "$DIST/$NAME.jar" res/MANIFEST.MF -C "$STAGE" .
rm -rf "$STAGE"

JARSIZE=$(stat -c%s "$DIST/$NAME.jar")
echo ">> write JAD"
{
  grep -v '^Manifest-Version' res/MANIFEST.MF
  echo "MIDlet-Jar-URL: $NAME.jar"
  echo "MIDlet-Jar-Size: $JARSIZE"
} > "$DIST/$NAME.jad"

echo ">> done: $DIST/$NAME.jar ($JARSIZE bytes), $DIST/$NAME.jad"
