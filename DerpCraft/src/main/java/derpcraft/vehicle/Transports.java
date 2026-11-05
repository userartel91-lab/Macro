package derpcraft.vehicle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The vehicle kinds: every folder in game/transport is one (its name is the vehicle's name). Read when the game starts
 * and again with /vehicle reload — new cars need no compiling, only files.
 */
public final class Transports {
    private Transports() {}

    private static Path dir;
    private static final Map<String, VehicleType> TYPES = new LinkedHashMap<>();
    private static int version;

    /** Changes whenever the vehicles are reloaded (cached meshes and analyses are rebuilt). */
    public static int version() { return version; }

    /** The vehicle analyses changed (the editor): cached meshes and analyses are rebuilt, nothing is read from disk. */
    public static synchronized void bump() { version++; }

    public static void init(Path gameDir) {
        dir = gameDir.resolve("transport");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        writeHelp();
        rescan();
    }

    /** Looks at the folders again; every vehicle kind loads fresh on next use. */
    public static synchronized void rescan() {
        TYPES.clear();
        if (dir != null && Files.isDirectory(dir)) {
            try (var st = Files.list(dir)) {
                List<Path> ds = new ArrayList<>(st.filter(Files::isDirectory).toList());
                ds.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
                for (Path d : ds) {
                    String n = d.getFileName().toString();
                    if (n.startsWith(".") || n.startsWith("_")) continue;
                    TYPES.put(n.toLowerCase(Locale.ROOT), new VehicleType(n, d));
                }
            } catch (IOException ignored) {}
        }
        version++;
    }

    /** Only for tools and tests: a kind from any folder. */
    public static synchronized VehicleType add(Path folder) {
        String n = folder.getFileName().toString();
        VehicleType t = new VehicleType(n, folder);
        TYPES.put(n.toLowerCase(Locale.ROOT), t);
        version++;
        return t;
    }

    public static synchronized VehicleType get(String name) { return name == null ? null : TYPES.get(name.toLowerCase(Locale.ROOT)); }

    /** Names of all vehicle folders (also the ones that do not load). */
    public static synchronized List<String> names() {
        List<String> l = new ArrayList<>();
        for (VehicleType t : TYPES.values()) l.add(t.name);
        return l;
    }

    /** A help file and a settings template in game/transport (written once, the player may edit or delete them). */
    private static void writeHelp() {
        try {
            Path help = dir.resolve("README.txt");
            if (!Files.exists(help)) Files.writeString(help, HELP, StandardCharsets.UTF_8);
            Path tpl = dir.resolve("_template.json");
            if (!Files.exists(tpl)) Files.writeString(tpl, TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    static final String TEMPLATE = """
            {
              "name": "My car",
              "maxSpeed": 86,
              "reverseSpeed": 25,
              "acceleration": 23,
              "braking": 72,
              "handbrake": 86,
              "steering": 32,
              "stepHeight": 1.05,
              "weight": 1200,
              "waterDrag": 0.85,
              "scale": 1,
              "width": 1,
              "height": 1,
              "length": 1,
              "turn": 0,
              "objUnitsPerBlock": 1,
              "suspension": { "stiffness": 0.18, "damping": 0.72, "maxPitch": 3.5, "maxRoll": 4, "rideHeight": 0, "heaveLimit": 0.18 },
              "handling": { "grip": 0.5, "driftGrip": 0.04, "driftYaw": 1.6, "driftMinSpeed": 21, "policeSpeed": 63, "policeRate": 9, "rollover": 0.033, "rollDamage": 1, "smashSpeed": 30, "rolloverEnabled": true, "rolloverSpeed": 50 },
              "blocks": [],
              "lights": [ { "start": [-0.6, 0.6, -2.0], "end": [-0.5, 0.2, -14], "width": 3, "intensity": 1, "color": [1, 0.95, 0.8] } ],
              "surfaces": { "ice": { "grip": 0.12, "speed": 1 }, "sand": { "grip": 0.75, "speed": 0.8 } },
              "open": { "door": 70, "hood": 60, "trunk": 65 },
              "parts": {
                "_example_door1": { "angle": 70, "pivot": [-1.0, 0.6, -1.0] }
              },
              "roles": { "_example_bag": "trunk" },
              "seats": [],
              "sounds": {
                "volume": 1,
                "enginePitchMin": 0.7,
                "enginePitchMax": 1.8,
                "gears": 4,
                "engine": "engine.wav",
                "idle": "idle.wav",
                "start": "start.wav",
                "horn": "horn.wav",
                "door_open": "door_open.wav",
                "door_close": "door_close.wav",
                "skid": "skid.wav",
                "crash": "crash.wav"
              }
            }
            """;

    static final String HELP = """
            DerpCraft: vehicles
            ===================
            Every folder here is a vehicle. The folder name is its name:  transport/nexia/  ->  /vehicle nexia
            After adding or changing files type  /vehicle reload  in the game (no restart, no compiling).

            In the folder (only the model is needed):
              nexia.obj (+ nexia.mtl)  or  nexia.bbmodel  or  nexia.json (a Blockbench "Java Block/Item" model export)
              textures (.png)          OBJ: the .mtl map_Kd texture, else <material>.png, <object>.png,
                                       <object without its number>.png (wheel1 -> wheel.png), else nexia.png
              nexia.json               settings (copy _template.json and rename it). If nexia.json is the Blockbench
                                       model, put the settings into vehicle.json instead.
              Shared by all cars: game/cars/sfx/ dooropen1-5, doorclose1-5, brake1-5 (as long as the braking), drift1-5
                                       (as long as the drift); .ogg / .wav, a random one each time; else the car's own door sounds
              sounds (.wav)            engine.wav idle.wav start.wav horn.wav door_open.wav door_close.wav (or door.wav)
                                       skid.wav crash.wav  - found by these names, or named in "sounds" of the settings

            Parts are found by the names of the model's groups (Blockbench) or objects / groups (OBJ "o" / "g"):
              wheel1, wheel2...  wheels (the front ones, nearer -Z, steer)
              door1..., hood, trunk (also bonnet, boot, hatch, lid)  open with a left click
              steering_wheel     turns with the steering
              seat1, seat2...    seats (the one nearest the steering wheel is the driver's); each seat opens with its nearest door
              block1..block4     places for blocks (in OBJ a small box: the block stands on the middle of its bottom)
              everything else    the body
            The front of the car is -Z, y is up, 1 unit = 1 block (Blockbench: 16 pixels). Use "turn" / "scale" /
            "objUnitsPerBlock" in the settings if the model is turned or in other units.
            OBJ has no pivots: wheels turn around their middle, doors around their front edge on the outside, the hood around
            its back edge, the trunk around its front edge. Other pivots: "parts": { "door1": { "pivot": [x, y, z] } }
            in model units.

            Vehicle editor: Left Alt + End (or /vehicle edit) tunes the vehicle you ride / look at / stand next to: speed, seats,
            block places, size, suspension, off-road ability, weight and handling. Every change is written to the settings file
            at once (<name>.json, or vehicle.json when the .json is the model).
            Driving: Space = handbrake. With gas and steering at speed it is a drift (tyre marks and smoke); at high speed with a
            sharp turn it is a police turn (the car swings round). Sharp turns at speed can roll the car over.
            Pushing: walk up behind a parked car and hold Alt (Left Alt, "Push vehicle" in Controls): you push it slowly.
            Cinematic camera: Right Alt + F8 in a vehicle: third person cameras that follow the car (the key cuts / changes the shot with Shift).

            Settings (all optional, see _template.json): maxSpeed / reverseSpeed in km/h, acceleration / braking / handbrake
            in km/h per second, steering = front wheel angle, stepHeight in blocks, suspension springs, opening angles,
            sounds (volume, engine pitch at idle and at top speed, number of gears).
            """;
}
