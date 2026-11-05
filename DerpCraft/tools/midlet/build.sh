#!/bin/sh
# Builds DerpRemote.jar + DerpRemote.jad (MIDP 2.0 / CLDC 1.1) without the Sun Wireless Toolkit:
#   JDK 8 (javac -target 1.3) compiles against the API stubs in stubs/, ProGuard -microedition preverifies.
# Usage: JDK8=/path/to/jdk8 PROGUARD=/path/to/proguard.jar sh build.sh
set -e
cd "$(dirname "$0")"
HERE="$(pwd)"
: "${JDK8:?set JDK8 to a JDK 8 folder (javac 8 still compiles for Java 1.3)}"
: "${PROGUARD:?set PROGUARD to proguard.jar (ProGuard 7.x, lib/proguard.jar)}"
VERSION=1.2.0
rm -rf build && mkdir -p build/stubs build/classes dist
"$JDK8/bin/javac" -nowarn -source 1.3 -target 1.3 -d build/stubs $(find stubs/src -name '*.java')
"$JDK8/bin/javac" -nowarn -encoding UTF-8 -source 1.3 -target 1.3 -bootclasspath "$JDK8/jre/lib/rt.jar" -cp build/stubs -d build/classes src/*.java
cp res/icon.png res/icons22.png res/icons16.png build/classes/
cat > build/MANIFEST.MF <<MF
MIDlet-Name: DerpCraft Remote
MIDlet-Vendor: DerpCraft
MIDlet-Version: $VERSION
MIDlet-1: DerpCraft Remote,/icon.png,DerpRemote
MIDlet-Icon: /icon.png
MIDlet-Description: Wi-Fi remote for DerpCraft
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions-Opt: javax.microedition.io.Connector.http,javax.microedition.io.Connector.socket
Nokia-MIDlet-App-Orientation: landscape
Nokia-MIDlet-Original-Display-Size: 432,240
Nokia-MIDlet-On-Screen-Keypad: no
MF
"$JDK8/bin/jar" cfm build/raw.jar build/MANIFEST.MF -C build/classes .
"$JDK8/bin/jar" cf build/stubs.jar -C build/stubs .
cat > build/pg.pro <<PG
-injars $HERE/build/raw.jar
-outjars $HERE/dist/DerpRemote.jar
-libraryjars $HERE/build/stubs.jar
-libraryjars $JDK8/jre/lib/rt.jar
-microedition
-target 1.3
-dontoptimize
-dontobfuscate
-dontnote
-keep public class DerpRemote
PG
java -jar "$PROGUARD" @build/pg.pro
SIZE=$(wc -c < dist/DerpRemote.jar | tr -d ' ')
{ cat build/MANIFEST.MF; echo "MIDlet-Jar-URL: DerpRemote.jar"; echo "MIDlet-Jar-Size: $SIZE"; } > dist/DerpRemote.jad
echo "dist/DerpRemote.jar ($SIZE bytes) and dist/DerpRemote.jad"
