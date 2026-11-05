#!/bin/sh
# Headless performance numbers and "same result" digests: block reads, body physics (collision), rays, pathfinding,
# chunk meshing. No game window, no OpenGL. Compiles the whole game with javac, so it needs the LWJGL / ASM jars:
# they are downloaded once from Maven Central into /tmp/dc_libs (retries when it answers 429 "too many requests").
# Usage (from the DerpCraft folder):
#   sh tools/perf_check/check.sh bench [all|state|phys|ray|path]   timings (-DR=32 in JOPTS for a bigger world)
#   sh tools/perf_check/check.sh equiv                              digests of bodies / rays / boxes on a mixed-block world
#   sh tools/perf_check/check.sh mesh                               digest + timing of all section meshes
# Before / after a change: run 'equiv' and 'mesh' on both copies; the digests must be equal.
cd "$(dirname "$0")/../.." || exit 1
L=/tmp/dc_libs; T=/tmp/dc_perf
mkdir -p "$L"
get() {
  f="$L/$(basename "$1")"; [ -s "$f" ] && return 0
  for d in 2 4 8 16 32; do curl -sSf -o "$f" "$1" 2>/dev/null && return 0; sleep $d; done
  echo "could not download $1"; rm -f "$f"; return 1
}
M=https://repo1.maven.org/maven2
for a in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-stb; do get $M/org/lwjgl/$a/3.3.4/$a-3.3.4.jar || exit 1; done
for a in asm asm-tree; do get $M/org/ow2/asm/$a/9.7.1/$a-9.7.1.jar || exit 1; done
CP=$(ls $L/*.jar | tr '\n' ':')
rm -rf "$T" && mkdir -p "$T/out" "$T/tool"
find src/main/java -name '*.java' > "$T/srcs.txt"
javac -nowarn -encoding UTF-8 --release 21 -cp "$CP" -d "$T/out" @"$T/srcs.txt" 2>&1 | grep -v "^Picked up\|^Note:"
cp -r src/main/resources/. "$T/out/"
javac -nowarn -cp "$T/out:$CP" -d "$T/tool" tools/perf_check/*.java 2>&1 | grep -v "^Picked up\|^Note:"
case "${1:-bench}" in
  bench) shift 2>/dev/null; java $JOPTS -Xmx4G -cp "$T/tool:$T/out:$CP" Bench "${1:-all}" ;;
  equiv) java -cp "$T/tool:$T/out:$CP" Equiv ;;
  mesh)  java -cp "$T/tool:$T/out:$CP" MeshEquiv ;;
  *) echo "usage: check.sh bench|equiv|mesh" ;;
esac 2>&1 | grep -v "^Picked up"
