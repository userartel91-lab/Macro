#!/bin/sh
# Take playback: the binary search of a take's actions (Track.Take.forEachAt) against the plain pass over the whole take,
# on random takes (offset, trim, speed), plus the time per tick of both. No game window.
# Usage (from the DerpCraft folder):  sh tools/npc_check/take_equiv.sh
cd "$(dirname "$0")/../.." || exit 1
sh tools/perf_check/check.sh compile-only > /dev/null 2>&1        # compiles the game into /tmp/dc_perf/out
mkdir -p /tmp/dc_take && javac -nowarn -cp /tmp/dc_perf/out -d /tmp/dc_take tools/npc_check/TakeEquiv.java 2>&1 | grep -v "^Picked up"
java -cp /tmp/dc_perf/out:/tmp/dc_take TakeEquiv 2>&1 | grep -v "^Picked up"
