package derpcraft.game;

import derpcraft.api.ModContext;
import derpcraft.block.Blocks;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Built-in chat commands (registered through the same API mods use) and the dispatcher. */
public final class Commands {
    private final Session s;
    private static boolean registered;

    public Commands(Session s) {
        this.s = s;
        if (!registered) { registerBuiltins(); registered = true; }
    }

    public void run(String line) {
        line = line.trim();
        while (line.startsWith("/")) line = line.substring(1);
        if (line.isEmpty()) return;
        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase(Locale.ROOT);
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        if (derpcraft.api.EventBus.has(derpcraft.api.Events.CommandRun.class)
                && derpcraft.api.EventBus.post(s, new derpcraft.api.Events.CommandRun(name, args)).isCancelled()) return;
        ModContext.CommandEntry e = ModContext.COMMANDS.get(name);
        if (e == null) { s.chat("§cUnknown command. Type /help for help."); return; }
        try {
            e.handler().run(s, args);
        } catch (IllegalArgumentException ex) {
            s.chat("§c" + (ex.getMessage() != null ? ex.getMessage() : "Usage: /" + e.name() + " " + e.usage()));
        } catch (Throwable t) {
            s.chat("§cCommand failed: " + t);
            t.printStackTrace();
        }
    }

    private static Session sess(derpcraft.api.GameApi g) { return (Session) g; }

    private static double coord(String arg, double base) {
        if (arg.startsWith("~")) return base + (arg.length() > 1 ? Double.parseDouble(arg.substring(1)) : 0);
        return Double.parseDouble(arg);
    }

    private static derpcraft.npc.Actor need(derpcraft.npc.NpcManager n, String name) {
        var a = n.byName(name);
        if (a == null) throw new IllegalArgumentException("No NPC named " + name);
        return a;
    }

    private static void need(String[] a, int n, String usage) { if (a.length < n) throw new IllegalArgumentException("Usage: " + usage); }

    private static void registerBuiltins() {
        ModContext c = new ModContext("derpcraft");
        c.addCommand("help", "[page]", (g, a) -> {
            g.chat("§e--- Commands ---");
            for (ModContext.CommandEntry e : ModContext.COMMANDS.values()) g.chat("§7/" + e.name() + " §f" + e.usage());
        });
        c.addCommand("sound", "<name> [volume] [pitch] | list [filter]  (game\\sounds, e.g. /sound mob/zombie/say)", (g, a) -> {
            if (a.length == 0) throw new IllegalArgumentException();
            if (a[0].equalsIgnoreCase("list")) {
                var l = derpcraft.audio.Sfx.names(a.length > 1 ? a[1] : "");
                g.chat("§e" + l.size() + " sounds" + (l.size() > 40 ? " (first 40, narrow with /sound list <filter>)" : ""));
                for (int i = 0; i < Math.min(40, l.size()); i++) g.chat("§7" + l.get(i));
                return;
            }
            float vol = a.length > 1 ? Float.parseFloat(a[1]) : 1, pitch = a.length > 2 ? Float.parseFloat(a[2]) : 1;
            if (!derpcraft.audio.Sfx.play(a[0], vol, pitch)) g.chat("§cNo files for '" + a[0] + "' in game\\sounds");
        });
        c.addCommand("npc", "spawn [name] | list | remove <name> | skin <name> <skin> | faction <name> <aggressive|neutral|friendly> | record <name> | stop | play [scene] | reset [scene] | scene <name> | group [name] | groups | on|off <name> | revive <name> | hub", (g, a) -> {
            Session s = sess(g);
            var n = s.npcs;
            String sub = a.length == 0 ? "hub" : a[0].toLowerCase(java.util.Locale.ROOT);
            String rest = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "";
            switch (sub) {
                case "spawn" -> { var ac = s.spawnNpc(rest.isEmpty() ? "Actor" : rest); g.chat("Spawned " + ac.name); }
                case "list" -> {
                    var all = n.members(n.currentScene);
                    if (all.isEmpty()) g.chat("No NPCs in group \"" + n.currentScene + "\". /npc spawn [name]");
                    for (var ac : all) g.chat(String.format(java.util.Locale.ROOT, "§e%s§7  %s, %s, %s at %.0f %.0f %.0f%s%s", ac.name, ac.skin, ac.faction.label, ac.behavior.label,
                            ac.x(), ac.y(), ac.z(), ac.hasTakes() ? ", scenes: " + String.join(", ", ac.scenes()) : "", ac.enabled ? "" : " §8(off)"));
                }
                case "remove" -> { var ac = need(n, rest); n.remove(ac); g.chat("Removed " + rest); }
                case "skin" -> {
                    need(a, 3, "/npc skin <name> <skin>   skins: " + String.join(", ", n.skins.names()));
                    var ac = need(n, a[1]);
                    String skin = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                    String found = null;
                    for (String sk : n.skins.names()) if (sk.equalsIgnoreCase(skin)) found = sk;
                    if (found == null) throw new IllegalArgumentException("Skins: " + String.join(", ", n.skins.names()));
                    ac.skin = found; n.save(); g.chat(ac.name + " now wears " + found);
                }
                case "faction" -> {
                    need(a, 3, "/npc faction <name> <aggressive|neutral|friendly>");
                    var ac = need(n, a[1]);
                    ac.faction = derpcraft.npc.Actor.Faction.valueOf(a[2].toUpperCase(java.util.Locale.ROOT));
                    n.save(); g.chat(ac.name + " is " + ac.faction.label);
                }
                case "record" -> {
                    var ac = rest.isEmpty() ? null : n.byName(rest);
                    if (ac == null) ac = s.spawnNpc(rest.isEmpty() ? "Actor" : rest);
                    n.startRecording(ac);
                    g.chat("§eRecording " + ac.name + " for scene \"" + n.currentScene + "\"" + (n.countdownSeconds > 0 ? " in " + n.countdownSeconds + " s" : "") + ". " + Keys.n(Keys.RECORD) + " or /npc stop to finish, " + Keys.n(Keys.RECORD_CANCEL) + " cancels");
                }
                case "stop" -> {
                    if (n.recording()) { var ac = n.stopRecording(); g.chat(ac != null && ac.take(n.currentScene) != null ? "§aTake saved: " + ac.name : "Recording cancelled"); }
                    else { n.stopScene(); g.chat("Scenes stopped"); }
                }
                case "play" -> { String sc = rest.isEmpty() ? n.currentScene : rest; g.chat(n.playScene(sc) ? "Playing scene " + sc : "Nothing recorded for scene " + sc); }
                case "reset" -> { String sc = rest.isEmpty() ? n.currentScene : rest; n.resetScene(sc); g.chat("Scene " + sc + " back at the start"); }
                case "restore" -> { String sc = rest.isEmpty() ? n.currentScene : rest; int k = n.restoreWorld(sc); g.chat(k > 0 ? k + " block(s) of scene " + sc + " back as before it" : "Nothing to restore in scene " + sc); }
                case "world" -> {
                    // /npc world restore|keep [scene]: what a scene leaves in the world after it played
                    String[] wv = rest.split(" ", 2);
                    String sc = wv.length > 1 && !wv[1].isBlank() ? wv[1].trim() : n.currentScene;
                    if (wv[0].equalsIgnoreCase("restore")) n.setRestoresWorld(sc, true);
                    else if (wv[0].equalsIgnoreCase("keep")) n.setRestoresWorld(sc, false);
                    else throw new IllegalArgumentException("/npc world restore|keep [scene]");
                    g.chat("Scene " + sc + ": the world is " + (n.restoresWorld(sc) ? "restored after each play" : "kept as the actors left it (/npc restore puts it back)"));
                }
                case "scene" -> { if (!rest.isEmpty()) { n.currentScene = rest; n.save(); } g.chat("Current scene: " + n.currentScene + "   (scenes: " + String.join(", ", n.sceneNames()) + ")"); }
                case "group" -> {
                    if (!rest.isEmpty()) {
                        String found = null;
                        for (String gr : n.groups()) if (gr.equalsIgnoreCase(rest)) found = gr;
                        n.currentScene = found != null ? found : n.newGroup(rest);
                        n.save();
                    }
                    g.chat("Current group: " + n.currentScene + " (" + n.members(n.currentScene).size() + " NPCs)");
                }
                case "groups" -> {
                    for (String gr : n.groups()) g.chat((gr.equalsIgnoreCase(n.currentScene) ? "§e> " : "§7  ") + gr + "§7  " + n.members(gr).size() + " NPCs" + (n.groupEnabled(gr) ? "" : " §8(off)"));
                }
                case "on", "off" -> {
                    boolean on = sub.equals("on");
                    String found = null;
                    for (String gr : n.groups()) if (gr.equalsIgnoreCase(rest)) found = gr;
                    if (found != null) { n.setGroupEnabled(found, on); g.chat("Group " + found + (on ? " is in the world" : " switched off")); }
                    else { var ac = need(n, rest); n.setEnabled(ac, on); g.chat(ac.name + (on ? " is in the world" : " switched off")); }
                }
                case "revive" -> { var ac = need(n, rest); ac.revive(); g.chat(ac.name + " is back on their feet"); }
                case "hub" -> s.game.setScreen(new derpcraft.gui.NpcHub(s.game, s, null));
                default -> throw new IllegalArgumentException("Usage: /npc spawn|list|remove|skin|faction|record|stop|play|reset|restore|world|scene|group|groups|on|off|revive|hub");
            }
        });
        derpcraft.vehicle.Vehicles.registerCommand(c);
        derpcraft.gun.Guns.registerCommand(c);
        c.addCommand("transition", "| <seconds> | from|to <shader preset> [/ <color preset>] | show from|to | list", (g, a) -> {
            Session s = sess(g);
            var game = s.game;
            Settings st = game.settings;
            if (a.length == 0) { g.chat(game.transition.toggle(game)); return; }
            String sub = a[0].toLowerCase(Locale.ROOT);
            String rest = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).trim();
            switch (sub) {
                case "list" -> {
                    g.chat("Shader presets: " + String.join(", ", ShaderLook.names(game.shadersDir, false)));
                    g.chat("Color presets: " + LookTransition.COLOR_OFF + ", " + String.join(", ", derpcraft.render.post.Grading.presetNames(game.gradingDir)));
                }
                case "from", "to" -> {
                    String[] parts = rest.split("/", 2);
                    String sh = parts[0].trim(), col = parts.length > 1 ? parts[1].trim() : null;
                    boolean from = sub.equals("from");
                    if (!sh.isEmpty()) {
                        String found = null;
                        for (String n : ShaderLook.names(game.shadersDir, from)) if (n.equalsIgnoreCase(sh)) found = n;
                        if (found == null) throw new IllegalArgumentException("No shader preset " + sh + " (/transition list)");
                        if (from) st.transFromShader = found; else st.transToShader = found;
                    }
                    if (col != null && !col.isEmpty()) {
                        String found = null;
                        java.util.List<String> cols = new java.util.ArrayList<>(derpcraft.render.post.Grading.presetNames(game.gradingDir));
                        cols.add(LookTransition.COLOR_OFF);
                        if (from) cols.add(ShaderLook.CURRENT);
                        for (String n : cols) if (n.equalsIgnoreCase(col)) found = n;
                        if (found == null) throw new IllegalArgumentException("No colour preset " + col + " (/transition list)");
                        if (from) st.transFromColor = found; else st.transToColor = found;
                    }
                    game.transition.reset(game);
                    st.save();
                    g.chat("Transition: " + st.transFromShader + " / " + st.transFromColor + " -> " + st.transToShader + " / " + st.transToColor);
                }
                case "show" -> {
                    String err = game.transition.show(game, rest.equalsIgnoreCase("to"));
                    g.chat(err != null ? err : "Showing the " + (rest.equalsIgnoreCase("to") ? "To" : "From") + " look");
                }
                default -> {
                    float sec;
                    try { sec = Float.parseFloat(sub.replace(',', '.')); } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Usage: /transition [seconds] | from|to <shader> [/ <color>] | show from|to | list");
                    }
                    st.transSeconds = Math.max(0.5f, Math.min(600, sec));
                    st.save();
                    g.chat(game.transition.toggle(game));
                }
            }
        });
        c.addCommand("selfie", "[on|off|free] | <distance 0.4-3> [right -1..1] | reset", (g, a) -> {
            Session s = sess(g);
            var cam = s.cam;
            if (a.length == 0) {
                g.chat(String.format(Locale.ROOT, "Selfie camera: %s, %.2f m from the face, %.2f m to the right. /selfie <distance> [right], /selfie reset",
                        s.selfie ? (s.selfieFree ? "ON (free)" : "ON") : "OFF", cam.selfieDistance, cam.selfieSide));
                return;
            }
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "on" -> s.setSelfie(true, false);
                case "off" -> s.setSelfie(false, false);
                case "free" -> s.setSelfie(true, true);
                case "reset" -> { cam.selfieDistance = CameraRig.SELFIE_DISTANCE; cam.selfieSide = CameraRig.SELFIE_SIDE; s.saveCamera(); g.chat("Selfie camera: default distance"); }
                default -> {
                    cam.selfieDistance = Math.max(0.4f, Math.min(3f, Float.parseFloat(a[0].replace(',', '.'))));
                    if (a.length > 1) cam.selfieSide = Math.max(-1f, Math.min(1f, Float.parseFloat(a[1].replace(',', '.'))));
                    s.saveCamera();
                    g.chat(String.format(Locale.ROOT, "Selfie camera: %.2f m from the face, %.2f m to the right", cam.selfieDistance, cam.selfieSide));
                }
            }
        });
        c.addCommand("tp", "<x> <y> <z>", (g, a) -> {
            need(a, 3, "/tp <x> <y> <z>  (~ for relative)");
            g.teleport(coord(a[0], g.playerX()), coord(a[1], g.playerY()), coord(a[2], g.playerZ()));
        });
        c.addCommand("time", "set <day|noon|night|midnight|ticks> | add <ticks> | stop | start", (g, a) -> {
            need(a, 1, "/time set <day|noon|night|midnight|n> | add <n> | stop | start");
            Session s = sess(g);
            switch (a[0]) {
                case "set" -> {
                    need(a, 2, "/time set <value>");
                    long day = g.time() / 24000 * 24000;
                    long t = switch (a[1]) { case "day" -> 1000; case "noon" -> 6000; case "sunset" -> 12000; case "night" -> 13000; case "midnight" -> 18000; case "sunrise" -> 23000; default -> Long.parseLong(a[1]); };
                    g.setTime(day + t);
                    g.chat("Set the time to " + t);
                }
                case "add" -> { need(a, 2, "/time add <n>"); g.setTime(g.time() + Long.parseLong(a[1])); }
                case "stop", "freeze" -> { s.world.info.daylightCycle = false; g.chat("Daylight cycle stopped"); }
                case "start", "resume" -> { s.world.info.daylightCycle = true; g.chat("Daylight cycle started"); }
                default -> throw new IllegalArgumentException("Usage: /time set|add|stop|start");
            }
        });
        c.addCommand("gamemode", "<survival|creative|spectator>", (g, a) -> {
            need(a, 1, "/gamemode <survival|creative|spectator>");
            Session s = sess(g);
            String m = switch (a[0].toLowerCase(Locale.ROOT)) {
                case "survival", "s", "0" -> "survival";
                case "creative", "c", "1" -> "creative";
                case "spectator", "sp", "3" -> "spectator";
                default -> throw new IllegalArgumentException("Unknown game mode");
            };
            s.setGameMode(m);
            g.chat("Your game mode has been updated to " + Character.toUpperCase(m.charAt(0)) + m.substring(1) + " Mode");
        });
        c.addCommand("speed", "<0.1-10>", (g, a) -> {
            need(a, 1, "/speed <multiplier>");
            float v = Math.max(0.05f, Math.min(20f, Float.parseFloat(a[0])));
            sess(g).player.flySpeedMultiplier = v;
            g.chat("Fly speed set to " + v + "x");
        });
        c.addCommand("seed", "", (g, a) -> g.chat("Seed: " + sess(g).world.info.seed));
        c.addCommand("give", "<item> [count]", (g, a) -> {
            need(a, 1, "/give <item> [count]");
            derpcraft.item.Item it = derpcraft.item.Items.byName(a[0]);
            if (it == null) it = Session.itemFor(Blocks.byName(a[0]));
            if (it == null) throw new IllegalArgumentException("Unknown item: " + a[0]);
            int n = a.length > 1 ? Integer.parseInt(a[1]) : 1;
            Session s = sess(g);
            int left = n;
            while (left > 0) { int k = Math.min(left, it.maxStack); s.giveOrDrop(new derpcraft.item.ItemStack(it, k)); left -= k; }
            g.chat("Gave " + n + " [" + it.displayName + "] to Player");
        });
        c.addCommand("mob", "summon <type> [count] | list | kill [type|all] | types | spawning on|off | explode [power]", (g, a) -> mob(sess(g), a));
        c.addCommand("summon", "<type> [count]", (g, a) -> { String[] b = new String[a.length + 1]; b[0] = "summon"; System.arraycopy(a, 0, b, 1, a.length); mob(sess(g), b); });
        c.addCommand("kill", "", (g, a) -> { Session s = sess(g); if (!s.creative()) { s.stats.invulnerable = 0; s.stats.damage(1000, "void", 0, s.ticks); } });
        c.addCommand("heal", "", (g, a) -> { Session s = sess(g); s.stats.health = 20; s.stats.food = 20; s.stats.saturation = 5; g.chat("Healed"); });
        c.addCommand("setblock", "<x> <y> <z> <block[:meta]>", (g, a) -> {
            need(a, 4, "/setblock <x> <y> <z> <block>");
            int st = WorldEdit.parseState(a[3]);
            int x = (int) Math.floor(coord(a[0], g.playerX())), y = (int) Math.floor(coord(a[1], g.playerY())), z = (int) Math.floor(coord(a[2], g.playerZ()));
            sess(g).world.setBlock(x, y, z, st);
        });
        c.addCommand("fill", "<x1> <y1> <z1> <x2> <y2> <z2> <block[:meta]>", (g, a) -> {
            need(a, 7, "/fill <x1> <y1> <z1> <x2> <y2> <z2> <block>");
            int st = WorldEdit.parseState(a[6]);
            double px = g.playerX(), py = g.playerY(), pz = g.playerZ();
            int[] b = {(int) Math.floor(coord(a[0], px)), (int) Math.floor(coord(a[1], py)), (int) Math.floor(coord(a[2], pz)),
                    (int) Math.floor(coord(a[3], px)), (int) Math.floor(coord(a[4], py)), (int) Math.floor(coord(a[5], pz))};
            int n = sess(g).edit.apply(new int[]{Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]), Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])}, cur -> st);
            g.chat(n + " blocks filled");
        });

        // ---- WorldEdit-style tools (usable as /pos1 or //pos1)
        c.addCommand("pos1", "  (corner 1 = block you look at, or your feet)", (g, a) -> {
            Session s = sess(g);
            s.edit.pos1 = here(s);
            s.edit.showFrame();
            g.chat("§dFirst position set to " + str(s.edit.pos1) + sizeInfo(s));
        });
        c.addCommand("pos2", "  (corner 2)", (g, a) -> {
            Session s = sess(g);
            s.edit.pos2 = here(s);
            s.edit.showFrame();
            g.chat("§dSecond position set to " + str(s.edit.pos2) + sizeInfo(s));
        });
        c.addCommand("desel", "  clear selection", (g, a) -> { sess(g).edit.pos1 = null; sess(g).edit.pos2 = null; });
        c.addCommand("set", "<block[:meta]>  fill selection", (g, a) -> {
            need(a, 1, "//set <block>");
            Session s = sess(g);
            int st = WorldEdit.parseState(a[0]);
            g.chat("§d" + s.edit.apply(sel(s), cur -> st) + " blocks changed");
        });
        c.addCommand("replace", "<from> <to>", (g, a) -> {
            need(a, 2, "//replace <from> <to>");
            Session s = sess(g);
            int from = WorldEdit.parseState(a[0]), to = WorldEdit.parseState(a[1]);
            boolean anyMeta = !a[0].matches(".*:\\d+$");
            g.chat("§d" + s.edit.apply(sel(s), cur -> (anyMeta ? (cur >> 4) == (from >> 4) : cur == from) ? to : -1) + " blocks replaced");
        });
        c.addCommand("walls", "<block>", (g, a) -> {
            need(a, 1, "//walls <block>");
            Session s = sess(g);
            g.chat("§d" + s.edit.walls(sel(s), WorldEdit.parseState(a[0])) + " blocks changed");
        });
        c.addCommand("floor", "<block>  bottom layer of the selection", (g, a) -> {
            need(a, 1, "//floor <block>");
            Session s = sess(g);
            int[] b = sel(s);
            int st = WorldEdit.parseState(a[0]);
            g.chat("§d" + s.edit.apply(new int[]{b[0], b[1], b[2], b[3], b[1], b[5]}, cur -> st) + " blocks changed");
        });
        c.addCommand("copy", "  copy selection (relative to you)", (g, a) -> {
            Session s = sess(g);
            int n = s.edit.copy((int) Math.floor(g.playerX()), (int) Math.floor(g.playerY()), (int) Math.floor(g.playerZ()));
            g.chat("§d" + n + " blocks copied");
        });
        c.addCommand("paste", "[-a]  paste at your position (-a skips air)", (g, a) -> {
            Session s = sess(g);
            boolean skip = a.length > 0 && a[0].equals("-a");
            int n = s.edit.paste((int) Math.floor(g.playerX()), (int) Math.floor(g.playerY()), (int) Math.floor(g.playerZ()), skip);
            g.chat("§d" + n + " blocks pasted");
        });
        c.addCommand("rotate", "  rotate clipboard 90 degrees", (g, a) -> { sess(g).edit.rotateClipboard(); g.chat("§dClipboard rotated"); });
        c.addCommand("undo", "  undo last edit", (g, a) -> g.chat(sess(g).edit.undo() ? "§dUndo successful" : "§cNothing to undo"));
        c.addCommand("count", "<block>", (g, a) -> {
            need(a, 1, "//count <block>");
            Session s = sess(g);
            g.chat("§dCounted: " + s.edit.count(sel(s), WorldEdit.parseState(a[0]) >> 4));
        });

        // ---- film camera from the chat or the Wi-Fi remote (same actions as the camera keys and the F9 panel)
        c.addCommand("cam", "player|free|orbit [radius height speed]|orbitme [r h speed]|tripod [on|off]|key|undo|clear|play [seconds] [loop]|stop"
                + "|lens <mm|0>|zoom <+mm|-mm>|roll <deg>|tilt <+-deg>|level|dof [on|off]|f <f-number>|focus <m|here|center|player>|letterbox <0-4>"
                + "|shake <0-1>|blur <0-1>|flares <0-1>|grid [on|off]|speed <blocks/s>|easing <0-3>|info", (g, a) -> cam(sess(g), a));
        c.addCommand("option", "[name] [value|on|off|+|-]  game options (list without arguments)", (g, a) -> {
            Session s = sess(g);
            if (a.length == 0) {
                StringBuilder sb = new StringBuilder();
                for (var o : derpcraft.remote.RemoteOptions.ALL) sb.append(sb.length() == 0 ? "" : ", ").append(o.name()).append('=').append(derpcraft.remote.RemoteOptions.get(s.game, o));
                g.chat("§eOptions: §f" + sb);
                return;
            }
            var o = derpcraft.remote.RemoteOptions.byName(a[0]);
            if (o == null) throw new IllegalArgumentException("Unknown option " + a[0] + " (/option lists them)");
            if (a.length == 1) { g.chat(o.label() + ": " + derpcraft.remote.RemoteOptions.get(s.game, o)); return; }
            g.chat(o.label() + " = " + derpcraft.remote.RemoteOptions.set(s.game, o, a[1]));
        });
        c.addCommand("remote", "[on|off|pin <digits>|port <n>]  Wi-Fi remote for the phone (MIDlet)", (g, a) -> {
            var r = sess(g).game.remote;
            if (r == null) throw new IllegalArgumentException("Remote is not available");
            if (a.length > 0) switch (a[0].toLowerCase(Locale.ROOT)) {
                case "on" -> { r.enabled = true; r.save(); r.start(); }
                case "off" -> { r.enabled = false; r.save(); r.stop(); }
                case "pin" -> {
                    need(a, 2, "/remote pin <4-8 digits>");
                    if (!a[1].matches("\\d{4,8}")) throw new IllegalArgumentException("PIN: 4 to 8 digits");
                    r.pin = a[1]; r.save();
                }
                case "port" -> {
                    need(a, 2, "/remote port <1024-65535>");
                    int p = Integer.parseInt(a[1]);
                    if (p < 1024 || p > 65535) throw new IllegalArgumentException("Port: 1024..65535");
                    r.port = p; r.save(); r.stop(); if (r.enabled) r.start();
                }
                default -> throw new IllegalArgumentException("Usage: /remote [on|off|pin <digits>|port <n>]");
            }
            g.chat(r.info());
            if (r.running() && !r.lastClient.isEmpty())
                g.chat("§7Last phone: " + r.lastClient + ", " + Math.max(0, (System.currentTimeMillis() - r.lastSeen) / 1000) + " s ago");
        });
    }

    private static boolean onOff(String[] a, int i, boolean cur) {
        if (a.length <= i) return !cur;
        return switch (a[i].toLowerCase(Locale.ROOT)) {
            case "on", "1", "true", "вкл" -> true;
            case "off", "0", "false", "выкл" -> false;
            default -> !cur;
        };
    }

    private static float num(String s) { return Float.parseFloat(s.replace(',', '.')); }

    private static void cam(Session s, String[] a) {
        var api = s.camera();
        var rig = s.cam;
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> s.chat(String.format(Locale.ROOT, "Camera: %s%s, lens %.0f mm, roll %.0f, DOF %s f/%.1f, focus %.1f m, keys %d%s",
                    api.mode(), api.tripod() ? " (tripod)" : "", api.focalLength(), api.roll(), api.depthOfField() ? "on" : "off", rig.aperture,
                    api.focusDistance(), api.keyframeCount(), api.pathPlaying() ? " (playing)" : ""));
            case "player" -> { api.playerView(); s.chat("Camera: player view"); }
            case "free" -> { if (!rig.detached()) s.cameraAction(Keys.FREE_CAM, false); else if (!api.mode().equals("free")) { api.freeCamera(); s.chat("Free camera"); } }
            case "orbit" -> {
                if (a.length >= 4) api.orbit(s.player.x, s.player.y + 1, s.player.z, num(a[1]), num(a[2]), num(a[3]));
                else s.cameraAction(Keys.ORBIT, false);      // like the key: around the block you look at; again = off
            }
            case "orbitme" -> { api.orbitPlayer(a.length > 1 ? num(a[1]) : 4, a.length > 2 ? num(a[2]) : 1.5f, a.length > 3 ? num(a[3]) : 20); s.chat("Orbit around the player"); }
            case "tripod" -> {
                boolean on = onOff(a, 1, api.tripod());
                if (on && !rig.detached()) throw new IllegalArgumentException("Tripod needs the free or orbit camera first (/cam free)");
                api.setTripod(on);
                s.chat(on ? "Tripod: the camera stays, you control the player" : "Controlling the camera");
            }
            case "key", "add" -> s.cameraAction(Keys.PATH_KEY, false);
            case "undo", "delete" -> s.cameraAction(Keys.PATH_DELETE, false);
            case "clear" -> s.cameraAction(Keys.PATH_DELETE, true);
            case "play" -> {
                if (a.length > 1) rig.pathDuration = Math.max(0.5f, Math.min(600, num(a[1])));
                if (a.length > 2) rig.pathLoop = onOff(a, 2, rig.pathLoop) || a[2].equalsIgnoreCase("loop");
                if (api.pathPlaying()) api.stopPath();
                if (!rig.play()) throw new IllegalArgumentException("Add at least 2 keyframes first (/cam key)");
                s.chat(String.format(Locale.ROOT, "Path: %.1f s%s", rig.pathDuration, rig.pathLoop ? ", loop" : ""));
            }
            case "stop" -> { api.stopPath(); s.chat("Path stopped"); }
            case "lens" -> { need(a, 2, "/cam lens <mm> (0 = FOV option)"); float mm = num(a[1]); api.setFocalLength(mm <= 0 ? 0 : Math.max(8, Math.min(300, mm))); }
            case "zoom" -> { need(a, 2, "/cam zoom <+mm|-mm>"); api.setFocalLength(Math.max(8, Math.min(300, api.focalLength() + num(a[1])))); }
            case "roll" -> { need(a, 2, "/cam roll <degrees>"); api.setRoll(Math.max(-180, Math.min(180, num(a[1])))); }
            case "tilt" -> { need(a, 2, "/cam tilt <+-degrees>"); api.setRoll(Math.max(-180, Math.min(180, api.roll() + num(a[1])))); }
            case "level" -> api.setRoll(0);
            case "dof" -> { boolean on = onOff(a, 1, api.depthOfField()); api.setDepthOfField(on); s.chat("Depth of field " + (on ? "ON" : "OFF")); }
            case "f", "aperture" -> { need(a, 2, "/cam f <0.95-22>"); api.setAperture(Math.max(0.95f, Math.min(22, num(a[1])))); api.setDepthOfField(true); s.chat("f/" + a[1]); }
            case "focus" -> {
                need(a, 2, "/cam focus <metres|here|center|player>");
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "here" -> s.cameraAction(Keys.FOCUS, false);
                    case "center", "auto" -> { api.setAutofocus("center"); s.chat("Autofocus: center"); }
                    case "player" -> { api.setAutofocus("player"); s.chat("Autofocus: player"); }
                    default -> { api.setFocusDistance(Math.max(0.1f, num(a[1]))); api.setDepthOfField(true); }
                }
            }
            case "letterbox" -> {
                int i = a.length > 1 ? Math.floorMod(Integer.parseInt(a[1]), CameraRig.LETTERBOX.length) : (rig.letterbox + 1) % CameraRig.LETTERBOX.length;
                rig.letterbox = i;
                s.chat("Letterbox: " + CameraRig.LETTERBOX[i]);
            }
            case "shake" -> { need(a, 2, "/cam shake <0-1> [speed]"); api.setShake(Math.max(0, Math.min(1, num(a[1]))), a.length > 2 ? num(a[2]) : rig.shakeSpeed); }
            case "blur" -> { need(a, 2, "/cam blur <0-1>"); api.setMotionBlur(Math.max(0, Math.min(1, num(a[1])))); }
            case "flares" -> { need(a, 2, "/cam flares <0-1> [length] [blue|amber|neutral|purple]"); api.setFlares(Math.max(0, Math.min(1, num(a[1]))), a.length > 2 ? num(a[2]) : rig.flareLength, a.length > 3 ? a[3] : "blue"); }
            case "grid" -> api.setGrid(onOff(a, 1, rig.grid));
            case "speed" -> { need(a, 2, "/cam speed <blocks per second>"); api.setFlySpeed(Math.max(0.2f, Math.min(200, num(a[1]))), rig.smoothing); s.chat("Camera speed " + a[1]); }
            case "easing" -> { need(a, 2, "/cam easing <0-3>"); rig.easing = Math.floorMod(Integer.parseInt(a[1]), CameraRig.EASINGS.length); s.chat("Easing: " + CameraRig.EASINGS[rig.easing]); }
            default -> throw new IllegalArgumentException("Usage: /cam player|free|orbit|tripod|key|undo|clear|play|stop|lens|zoom|roll|level|dof|f|focus|letterbox|shake|blur|flares|grid|speed|easing|info");
        }
        s.saveCamera();
    }

    private static int[] here(Session s) {
        if (s.target != null) return new int[]{s.target.x, s.target.y, s.target.z};
        return new int[]{(int) Math.floor(s.player.x), (int) Math.floor(s.player.y), (int) Math.floor(s.player.z)};
    }

    private static int[] sel(Session s) {
        int[] b = s.edit.selection();
        if (b == null) throw new IllegalArgumentException("Make a selection first: look at a block and type //pos1, then //pos2");
        return b;
    }

    private static String str(int[] p) { return "(" + p[0] + ", " + p[1] + ", " + p[2] + ")"; }

    private static String sizeInfo(Session s) {
        int[] b = s.edit.selection();
        return b == null ? "" : " (" + WorldEdit.volume(b) + " blocks)";
    }

    private static void mob(Session s, String[] a) {
        if (a.length == 0) throw new IllegalArgumentException();
        var types = derpcraft.entity.MobType.REGISTRY;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "types" -> s.chat(types.isEmpty() ? "No creature types (install mods that add some)" : "Creatures: " + String.join(", ", types.keySet()));
            case "summon", "spawn" -> {
                if (a.length < 2) throw new IllegalArgumentException("Usage: /mob summon <type> [count]");
                var t = derpcraft.entity.MobType.get(a[1]);
                if (t == null) { s.chat("\u00a7cUnknown creature " + a[1] + " (see /mob types)"); return; }
                int n = a.length > 2 ? Math.max(1, Math.min(100, Integer.parseInt(a[2]))) : 1;
                double yr = Math.toRadians(s.player.yaw);
                double x = s.player.x - Math.sin(yr) * 3, z = s.player.z + Math.cos(yr) * 3;
                int made = 0;
                for (int i = 0; i < n; i++) {
                    double ox = n == 1 ? 0 : (Math.random() - 0.5) * 3, oz = n == 1 ? 0 : (Math.random() - 0.5) * 3;
                    if (s.mobs.spawn(t, x + ox, s.player.y, z + oz, s.player.yaw + 180, false) != null) made++;
                }
                s.chat("Summoned " + made + " " + t.displayName);
            }
            case "list" -> {
                Map<String, Integer> count = new TreeMap<>();
                for (var m : s.mobs.list()) count.merge(m.type.id, 1, Integer::sum);
                s.chat(count.isEmpty() ? "No creatures loaded" : "Creatures: " + count);
            }
            case "kill" -> {
                String type = a.length > 1 ? a[1] : "all";
                int n = 0;
                for (var m : s.mobs.list()) if (type.equals("all") || m.type.id.equals(type) || m.type.id.endsWith(":" + type)) { m.kill(); n++; }
                s.chat("Killed " + n + " creature(s)");
            }
            case "spawning" -> {
                if (a.length > 1) s.mobs.spawning = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
                s.chat("Natural creature spawning: " + (s.mobs.spawning ? "ON" : "OFF"));
            }
            case "explode" -> {
                float p = a.length > 1 ? Float.parseFloat(a[1]) : 4;
                double yr = Math.toRadians(s.player.yaw);
                derpcraft.entity.Explosions.explode(s, s.player.x - Math.sin(yr) * 6, s.player.y, s.player.z + Math.cos(yr) * 6, p, true);
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
