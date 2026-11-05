#!/bin/sh
# Pictures of the gun without the game: first person (hip, equip, shot, aim, sprint, strafe, reload stages) -> sheet.png,
# third person (the player model holding it from behind / front / side, looking straight, up, down, turned) -> 3p/sheet.png.
# Usage (from the DerpCraft folder):  sh tools/gun_check/preview.sh <gun folder> [out dir]   -> <out dir>/sheet.png
cd "$(dirname "$0")/../.." || exit 1
G=$(cd "$1" && pwd); OUT=${2:-/tmp/gun_preview}
sh tools/gun_check/check.sh "$G" > /dev/null
cp tools/gun_check/GunPoseDump.java tools/gun_check/GunPose3PDump.java /tmp/dc_gun_check/src/derpcraft/gun/
javac -nowarn -encoding UTF-8 -d /tmp/dc_gun_check/out $(find /tmp/dc_gun_check/src -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
N=$(basename "$G" | tr 'A-Z' 'a-z')
java -cp /tmp/dc_gun_check/out derpcraft.gun.GunPoseDump "$G" /tmp/gun_poses.txt 2>&1 | grep -v "^Picked up"
python3 -I tools/gun_check/preview.py /tmp/gun_poses.txt "$G/$N.png" "$OUT"
# third person: the gun in the player's hands (out dir/3p)
java -cp /tmp/dc_gun_check/out derpcraft.gun.GunPose3PDump "$G" /tmp/gun_poses3p.txt 2>&1 | grep -v "^Picked up"
python3 -I tools/gun_check/preview.py /tmp/gun_poses3p.txt "$G/$N.png" "$OUT/3p"
