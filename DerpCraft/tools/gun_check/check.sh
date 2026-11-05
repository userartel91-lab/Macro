#!/bin/sh
# Offline check of gun folders with the game's own code (model parts, hand points, sounds, settings, animation), no LWJGL.
# Usage (from the DerpCraft folder):  sh tools/gun_check/check.sh <gun folder> [more folders ...]
#   a gun folder is what goes into game/guns/: ak12/ with ak12.obj, ak12.png, ak12_item.png, ak12_mag.png, ak12.json, shoot1..3.ogg
cd "$(dirname "$0")/../.." || exit 1
T=/tmp/dc_gun_check
rm -rf "$T" && mkdir -p "$T/src" "$T/out"
J=src/main/java/derpcraft
mkdir -p "$T/src/derpcraft/model" "$T/src/derpcraft/util" "$T/src/derpcraft/gun"
cp $J/model/Model3D.java $J/model/BlockbenchLoader.java $J/model/ObjLoader.java $J/model/ModelSettings.java $J/model/Models.java "$T/src/derpcraft/model/"
cp $J/util/Json.java $J/util/Mat4.java "$T/src/derpcraft/util/"
cp $J/gun/GunType.java $J/gun/GunAnimator.java $J/gun/GunHold3P.java $J/gun/FistAnimator.java "$T/src/derpcraft/gun/"
mkdir -p "$T/src/derpcraft/render" && cp tools/vehicle_check/stubs/derpcraft/render/Texture2D.java "$T/src/derpcraft/render/"
cp tools/gun_check/GunCheck.java "$T/src/derpcraft/gun/"
ABS=""
for f in "$@"; do ABS="$ABS $(cd "$f" && pwd)"; done
javac -nowarn -encoding UTF-8 -d "$T/out" $(find "$T/src" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp "$T/out" derpcraft.gun.GunCheck $ABS 2>&1 | grep -v "^Picked up"
