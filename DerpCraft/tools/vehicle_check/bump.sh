#!/bin/sh
# How rough is the ride: drives a car over stepped, Minecraft-like hills and long slopes with the game's own physics and prints
# the jumps of the body and the nose per tick (no game, no OpenGL). Compare numbers before / after a change to Vehicle.java.
# Usage (from the DerpCraft folder):  sh tools/vehicle_check/bump.sh <vehicle folder> [alpha beta sink float angles [terrainNoise]]
#   default smoothing is in Vehicle.java (SM_ALPHA, SM_BETA, SM_SINK, SM_FLOAT, ANGLE_FOLLOW); terrainNoise 0 = smooth hills, 0.6 = rough
cd "$(dirname "$0")/../.." || exit 1
T=/tmp/dc_vehicle_bump
rm -rf "$T" && mkdir -p "$T/src" "$T/out"
J=src/main/java/derpcraft
mkdir -p "$T/src/derpcraft/model" "$T/src/derpcraft/util" "$T/src/derpcraft/vehicle"
cp $J/model/Model3D.java $J/model/BlockbenchLoader.java $J/model/ObjLoader.java $J/model/ModelSettings.java $J/model/Models.java "$T/src/derpcraft/model/"
cp $J/util/Json.java $J/util/Mat4.java "$T/src/derpcraft/util/"
cp $J/vehicle/VehicleModel.java $J/vehicle/Vehicle.java $J/vehicle/VehicleType.java $J/vehicle/Transports.java $J/vehicle/Surfaces.java "$T/src/derpcraft/vehicle/"
cp -r tools/vehicle_check/stubs/derpcraft "$T/src/"
cp tools/vehicle_check/BumpTest.java "$T/src/derpcraft/vehicle/"
F="$1"; shift
ABS="$(cd "$F" && pwd)"
javac -nowarn -encoding UTF-8 -d "$T/out" $(find "$T/src" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp "$T/out" derpcraft.vehicle.BumpTest "$ABS" "$@" 2>&1 | grep -v "^Picked up"
