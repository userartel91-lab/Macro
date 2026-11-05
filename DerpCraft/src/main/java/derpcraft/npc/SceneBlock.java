package derpcraft.npc;

import derpcraft.api.BlockContext;
import derpcraft.api.DataTag;
import derpcraft.api.ModContext;
import derpcraft.block.Block;
import derpcraft.block.Dir;
import derpcraft.block.FunctionalBlocks;
import derpcraft.game.Session;
import derpcraft.world.World;

/**
 * Scene Block: plays a named NPC scene. Trigger: right click, the player coming near (radius), or a lever / button /
 * pressure plate next to it. Once or every time. Sneak + right click (or any click when not in click mode) opens
 * its settings. Built with the same block API mods use.
 */
public final class SceneBlock {
    public static final String[] TRIGGERS = {"click", "near", "npc", "lever"};
    public static final String[] TRIGGER_LABELS = {"Right click", "Player nearby", "NPC nearby", "Lever / button / plate"};

    private SceneBlock() {}

    public static void register() {
        ModContext ctx = new ModContext("minecraft");
        ctx.registerBlock("scene_block")
                .texture("command_block_side").face(Dir.NORTH, "command_block_front").face(Dir.SOUTH, "command_block_back")
                .facesPlayer().displayName("Scene Block").data().hardness(-1).tab("redstone")
                .onUse(SceneBlock::use)
                .onPlaced(b -> { if (Session.current != null) Session.current.game.setScreen(new derpcraft.gui.SceneBlockScreen(Session.current.game, Session.current, b.x(), b.y(), b.z())); })
                .onTick(SceneBlock::tick)
                .register();
    }

    private static boolean use(BlockContext b, derpcraft.api.GameApi game) {
        Session s = (Session) game;
        DataTag d = b.data();
        if (game.playerSneaking() || !"click".equals(d.getString("trigger", "click")) || d.getString("scene").isEmpty()) {
            s.game.setScreen(new derpcraft.gui.SceneBlockScreen(s.game, s, b.x(), b.y(), b.z()));
            return true;
        }
        fire(s, d, true);
        return true;
    }

    /** Plays the block's scene (respecting "once"). */
    public static boolean fire(Session s, DataTag d, boolean feedback) {
        String scene = d.getString("scene");
        if (scene.isEmpty()) return false;
        if (!d.getBool("repeat", true) && d.getBool("done")) { if (feedback) s.chat("§7Scene \"" + scene + "\" already played (once). Sneak + click to re-arm."); return false; }
        if (!s.npcs.groupEnabled(scene) && d.getBool("wake", true)) s.npcs.setGroupEnabled(scene, true);
        java.util.List<Integer> only = actors(d);
        boolean ok = s.npcs.playScene(scene, only);
        if (ok) d.put("done", true);
        else if (feedback) s.chat(s.npcs.scenePlaying(scene) ? "§7Scene \"" + scene + "\" is playing"
                : !s.npcs.groupEnabled(scene) ? "§cGroup \"" + scene + "\" is switched off" : "§cNo actors recorded for scene \"" + scene + "\"");
        return ok;
    }

    /** NPC ids chosen on the block (null = the whole group). */
    public static java.util.List<Integer> actors(DataTag d) {
        String v = d.getString("actors", "");
        if (v.isBlank()) return null;
        java.util.List<Integer> l = new java.util.ArrayList<>();
        for (String p : v.split(",")) try { l.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) {}
        return l.isEmpty() ? null : l;
    }

    public static void setActors(DataTag d, java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) { d.remove("actors"); return; }
        StringBuilder sb = new StringBuilder();
        for (int id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        d.put("actors", sb.toString());
    }

    private static void tick(BlockContext b) {
        Session s = Session.current;
        if (s == null || (s.ticks + b.x() + b.z()) % 5 != 0) return;
        DataTag d = b.data();
        String trigger = d.getString("trigger", "click");
        if (d.getString("scene").isEmpty() || "click".equals(trigger)) return;
        boolean on;
        if ("near".equals(trigger)) {
            double r = d.getFloat("radius", 6);
            double dx = s.player.x - (b.x() + 0.5), dy = s.player.y - b.y(), dz = s.player.z - (b.z() + 0.5);
            on = dx * dx + dy * dy + dz * dz < r * r && !s.spectator();
        } else if ("npc".equals(trigger)) on = npcNear(s, d, b.x() + 0.5, b.y(), b.z() + 0.5, d.getFloat("radius", 6));
        else on = powered(s.world, b.x(), b.y(), b.z());
        boolean was = d.getBool("active");
        if (on != was) d.put("active", on);
        if (on && !was) fire(s, d, false);   // rising edge: entered the radius / switched on
    }

    /**
     * "NPC nearby": who = "cast" (the scene's actors chosen on the block), "any" (any NPC in the world) or an NPC id.
     */
    static boolean npcNear(Session s, DataTag d, double x, double y, double z, double r) {
        String who = d.getString("who", "cast");
        String scene = d.getString("scene");
        java.util.List<Integer> only = actors(d);
        for (Actor a : s.npcs.actors) {
            if (a.dead()) continue;
            boolean match = switch (who) {
                case "any" -> true;
                case "cast" -> a.track(scene) != null && a.track(scene).take() != null && (only == null || only.contains(a.id));
                default -> String.valueOf(a.id).equals(who);
            };
            if (!match) continue;
            double dx = a.x() - x, dy = a.y() - y, dz = a.z() - z;
            if (dx * dx + dy * dy + dz * dz < r * r) return true;
        }
        return false;
    }

    /** Label of the "who" setting. */
    public static String whoLabel(Session s, DataTag d) {
        String who = d.getString("who", "cast");
        if (who.equals("cast")) return "its actors";
        if (who.equals("any")) return "any NPC";
        try { Actor a = s.npcs.byId(Integer.parseInt(who)); return a == null ? "(gone)" : a.name; } catch (NumberFormatException e) { return who; }
    }

    /** A lever, button or pressure plate next to the block is on. */
    static boolean powered(World w, int x, int y, int z) {
        for (int f = 0; f < 6; f++) {
            int nx = x + Dir.DX[f], ny = y + Dir.DY[f], nz = z + Dir.DZ[f];
            Block n = w.getBlock(nx, ny, nz);
            int m = w.getMeta(nx, ny, nz);
            if ((n instanceof FunctionalBlocks.LeverBlock || n instanceof FunctionalBlocks.ButtonBlock) && (m & 8) != 0) return true;
            if (n instanceof FunctionalBlocks.PlateBlock && (m & 1) != 0) return true;
        }
        return false;
    }
}
