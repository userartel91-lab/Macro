#!/bin/sh
# Offline check of vehicle .bbmodel files with the game's own code (loader, VehicleModel, Vehicle physics), no LWJGL needed.
# Usage (from the DerpCraft folder):  sh tools/vehicle_check/check.sh <vehicle folder> [more folders ...]
#   a vehicle folder is what goes into game/transport/: nexia/ with nexia.obj|.bbmodel|.json, textures, nexia.json, *.wav
cd "$(dirname "$0")/../.." || exit 1
T=/tmp/dc_vehicle_check
rm -rf "$T" && mkdir -p "$T/src" "$T/out"
J=src/main/java/derpcraft
mkdir -p "$T/src/derpcraft/model" "$T/src/derpcraft/util" "$T/src/derpcraft/vehicle"
cp $J/model/Model3D.java $J/model/BlockbenchLoader.java $J/model/ObjLoader.java $J/model/ModelSettings.java $J/model/Models.java "$T/src/derpcraft/model/"
cp $J/util/Json.java $J/util/Mat4.java "$T/src/derpcraft/util/"
cp $J/vehicle/VehicleModel.java $J/vehicle/Vehicle.java $J/vehicle/VehicleType.java $J/vehicle/Transports.java $J/vehicle/Surfaces.java "$T/src/derpcraft/vehicle/"
cp -r tools/vehicle_check/stubs/derpcraft "$T/src/"
cp tools/vehicle_check/VehicleCheck.java "$T/src/derpcraft/vehicle/"
ABS=""
for f in "$@"; do ABS="$ABS $(cd "$f" && pwd)"; done
javac -nowarn -encoding UTF-8 -d "$T/out" $(find "$T/src" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp "$T/out" derpcraft.vehicle.VehicleCheck $ABS 2>&1 | grep -v "^Picked up"
