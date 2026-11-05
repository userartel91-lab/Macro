#!/bin/sh
# Offline tests of the gun code on a gun folder, with the game's own classes (no LWJGL). Prints numbers to compare
# before / after a change of GunAnimator / GunHold3P / GunType. The folder is copied for SaveTest (it writes the json).
# Usage (from the DerpCraft folder):  sh tools/gun_check/tests.sh <gun folder>
cd "$(dirname "$0")/../.." || exit 1
G=$(cd "$1" && pwd); N=$(basename "$G")
sh tools/gun_check/check.sh "$G" > /dev/null          # compiles the game classes to /tmp/dc_gun_check/out
javac -nowarn -encoding UTF-8 -cp /tmp/dc_gun_check/out -d /tmp/dc_gun_check/out tools/gun_check/tests/*.java 2>&1 | grep -v "^Picked up\|^Note:"
for t in SplitTest HandsTest SyncTest SmoothTest Hold3PTest ArmPoseTest MeleeTest; do
  echo "== $t"
  java -cp /tmp/dc_gun_check/out derpcraft.gun.$t "$G" 2>&1 | grep -v "^Picked up"
done
T=/tmp/dc_gun_tests; rm -rf "$T"; mkdir -p "$T/$N"; cp -r "$G"/. "$T/$N/"
echo "== SaveTest (on a copy in $T)"
java -cp /tmp/dc_gun_check/out derpcraft.gun.SaveTest "$T/$N" 2>&1 | grep -v "^Picked up\|^ \|^{\|^}"
