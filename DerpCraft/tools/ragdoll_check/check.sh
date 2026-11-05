#!/bin/sh
# Offline check of the NPC ragdoll (derpcraft.npc.Ragdoll): numbers + pictures (<out>/sheet.png, default /tmp/ragdoll_check).
# Usage (from the DerpCraft folder):  sh tools/ragdoll_check/check.sh [out dir]
cd "$(dirname "$0")/../.." || exit 1
OUT=${1:-/tmp/ragdoll_check}
T=/tmp/dc_ragdoll
rm -rf "$T" && mkdir -p "$T/src/derpcraft/npc" "$T/src/derpcraft/util" "$T/out"
J=src/main/java/derpcraft
cp $J/npc/Ragdoll.java "$T/src/derpcraft/npc/" && cp $J/util/Mat4.java "$T/src/derpcraft/util/"
cp tools/ragdoll_check/RagdollTest.java "$T/src/derpcraft/npc/"
javac -nowarn -encoding UTF-8 -d "$T/out" $(find "$T/src" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp "$T/out" derpcraft.npc.RagdollTest "$T/frames.txt" 2>&1 | grep -v "^Picked up"
python3 -I tools/gun_check/preview.py "$T/frames.txt" src/main/resources/guns/ak12/ak12.png "$OUT" > /dev/null && echo "pictures: $OUT/sheet.png"
