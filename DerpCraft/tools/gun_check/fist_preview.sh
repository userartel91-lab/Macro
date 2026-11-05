#!/bin/sh
# Pictures of the first person fist fighting (Alt + Q) without the game: guard, coming up, walk, sprint, every stage of a
# left straight, a right straight and a right hook -> <out dir>/sheet.png (each frame also a PNG). A red mark = the knuckles.
# Usage (from the DerpCraft folder):  sh tools/gun_check/fist_preview.sh [out dir]   (default /tmp/fist_preview)
cd "$(dirname "$0")/../.." || exit 1
OUT=${1:-/tmp/fist_preview}
sh tools/gun_check/check.sh src/main/resources/guns/ak12 > /dev/null          # compiles the game classes
cp src/main/java/derpcraft/gun/FistAnimator.java tools/gun_check/GunPoseDump.java tools/gun_check/FistPoseDump.java /tmp/dc_gun_check/src/derpcraft/gun/
javac -nowarn -encoding UTF-8 -d /tmp/dc_gun_check/out $(find /tmp/dc_gun_check/src -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp /tmp/dc_gun_check/out derpcraft.gun.FistPoseDump /tmp/fist_poses.txt 2>&1 | grep -v "^Picked up"
python3 -I tools/gun_check/preview.py /tmp/fist_poses.txt src/main/resources/guns/ak12/ak12.png "$OUT"
# numbers: rigid arms, one hit per punch, the combo, up / down
javac -nowarn -encoding UTF-8 -cp /tmp/dc_gun_check/out -d /tmp/dc_gun_check/out tools/gun_check/tests/FistTest.java tools/gun_check/tests/HandsTest.java 2>&1 | grep -v "^Picked up\|^Note:"
java -cp /tmp/dc_gun_check/out derpcraft.gun.FistTest 2>&1 | grep -v "^Picked up"
