#!/bin/sh
# The settings the vehicle editor writes, the model analysed again after a change, and the cinematic camera (no game, no OpenGL;
# it builds a plain box car). "ALL OK" at the end. Use it after any change to VehicleType.java, VehicleModel.java or VehicleCamera.java.
# Usage (from the DerpCraft folder):  sh tools/vehicle_check/editor.sh
cd "$(dirname "$0")/../.." || exit 1
T=/tmp/dc_vehicle_editor
rm -rf "$T" && mkdir -p "$T/src" "$T/out"
J=src/main/java/derpcraft
mkdir -p "$T/src/derpcraft/model" "$T/src/derpcraft/util" "$T/src/derpcraft/vehicle"
cp $J/model/Model3D.java $J/model/BlockbenchLoader.java $J/model/ObjLoader.java $J/model/ModelSettings.java $J/model/Models.java "$T/src/derpcraft/model/"
cp $J/util/Json.java $J/util/Mat4.java "$T/src/derpcraft/util/"
cp $J/vehicle/VehicleModel.java $J/vehicle/Vehicle.java $J/vehicle/VehicleType.java $J/vehicle/Transports.java $J/vehicle/Surfaces.java $J/vehicle/VehicleCamera.java "$T/src/derpcraft/vehicle/"
cp -r tools/vehicle_check/stubs/derpcraft "$T/src/"
cp tools/vehicle_check/HandlingTest.java tools/vehicle_check/EditorTest.java "$T/src/derpcraft/vehicle/"
javac -nowarn -encoding UTF-8 -d "$T/out" $(find "$T/src" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:"
java -cp "$T/out" derpcraft.vehicle.EditorTest 2>&1 | grep -v "^Picked up"
