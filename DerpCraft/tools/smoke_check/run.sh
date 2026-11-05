#!/bin/sh
# Smoke test of the vehicle features in the REAL game: starts DerpCraft under software OpenGL (Xvfb + Mesa llvmpipe, no video card
# needed), makes a flat world with a plain test car, and plays Scenario.java (drift, police turn, rolling over, the cinematic
# camera, the vehicle editor, pushing). Screenshots land in /tmp/dc_smoke/shots (named in the order of the script), the log in
# /tmp/dc_smoke/smoke.log ("[smoke] CHECK ..." lines are numbers worth reading).
# Needs: Xvfb (xvfb-run), Mesa (libgl1-mesa-dri), curl, JDK 21. The LWJGL jars (with the Linux natives) come from Maven Central
# into /tmp/dc_libs on the first run (retries when it answers 429).
# Usage (from the DerpCraft folder):  sh tools/smoke_check/run.sh [seconds, default 600]
cd "$(dirname "$0")/../.." || exit 1
L=/tmp/dc_libs; T=/tmp/dc_smoke
mkdir -p "$L"
get() {
  f="$L/$(basename "$1")"; [ -s "$f" ] && return 0
  for d in 2 4 8 16 32; do curl -sSf -o "$f" "$1" 2>/dev/null && return 0; sleep $d; done
  echo "could not download $1"; rm -f "$f"; return 1
}
M=https://repo1.maven.org/maven2
for a in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-stb; do get $M/org/lwjgl/$a/3.3.4/$a-3.3.4.jar || exit 1; get $M/org/lwjgl/$a/3.3.4/$a-3.3.4-natives-linux.jar || exit 1; done
for a in asm asm-tree; do get $M/org/ow2/asm/$a/9.7.1/$a-9.7.1.jar || exit 1; done
CP=$(ls $L/*.jar | tr '\n' ':')
rm -rf "$T" && mkdir -p "$T/out" "$T/tool" "$T/game" "$T/shots"
find src/main/java -name '*.java' > "$T/srcs.txt"
javac -nowarn -encoding UTF-8 --release 21 -cp "$CP" -d "$T/out" @"$T/srcs.txt" 2>&1 | grep -v "^Picked up\|^Note:"
cp -r src/main/resources/. "$T/out/"
javac -nowarn -encoding UTF-8 --release 21 -cp "$T/out:$CP" -d "$T/tool" tools/smoke_check/SmokeMain.java tools/smoke_check/Scenario.java 2>&1 | grep -v "^Picked up\|^Note:"
export LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
timeout "${1:-600}" xvfb-run -a -s "-screen 0 1280x720x24 +extension GLX" java -Xmx3G -Dderpcraft.dir="$T/game" --enable-native-access=ALL-UNNAMED -cp "$T/tool:$T/out:$CP" SmokeMain "$T/game" > "$T/smoke.log" 2>&1
echo "exit $?"
# screenshots are named by the time: put the script's names on them (same order)
set -- $(grep "^\[smoke\] shot" "$T/smoke.log" | awk '{print $3}')
for f in $(ls "$T/game/screenshots" 2>/dev/null | sort); do n=$1; shift; [ -n "$n" ] && cp "$T/game/screenshots/$f" "$T/shots/$n.png"; done
grep "^\[smoke\]" "$T/smoke.log" | grep -v " shot "
grep -q "DONE" "$T/smoke.log" && echo "SMOKE OK ($(ls "$T/shots" | wc -l) screenshots in $T/shots)" || { echo "SMOKE FAILED: see $T/smoke.log"; exit 1; }
