package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;
import derpcraft.npc.Recording;
import derpcraft.render.GuiRenderer;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * NPC Hub (F10): place NPCs and dress them, give them an AI and stats, set up which factions fight each other, and
 * record actors for scenes like the Blockbuster mod.
 * <p>
 * Two rows of tabs. The top row is about scenes: <b>Scenes</b> (the groups = scenes, and the NPCs of one), <b>Play</b>
 * (playing the scene, what it leaves in the world), <b>Record</b> (how takes are recorded), <b>Teams</b> (faction
 * relations). The second row is about the selected NPC: <b>NPC</b> (name, place, group), <b>Look</b>, <b>AI</b>,
 * <b>Stats</b> (speed, health, damage, physics) and <b>Takes</b> (record, takes, timeline).
 */
public final class NpcHub extends Screen {
    private final Session s;
    // pages: scene row, then NPC row
    private static final int P_SCENES = 0, P_PLAY = 1, P_REC = 2, P_TEAMS = 3, P_NPC = 4, P_LOOK = 5, P_AI = 6, P_STATS = 7, P_TAKES = 8;
    private static final String[] SCENE_TABS = {"Scenes", "Play", "Record", "Teams"};
    private static final String[] NPC_TABS = {"NPC", "Look", "AI", "Stats", "Takes"};
    private static int page = P_SCENES;
    private static int scroll = 0;
    private static int selectedId = -1;
    /** Scenes page: false = the groups, true = the NPCs of the current group. */
    private static boolean inGroup;
    /** Group waiting for "copy with / without takes". */
    private static String copySource;
    private long deleteArmed, deleteNpcArmed;
    private Widget.TextField groupField;
    private static final int PW = 206;
    /** Rows of the list shown, where it starts, how many entries it has (for the wheel and its hint). */
    private int listRows = 6, listY, listTotal;
    private Widget.TextField nameField;
    private String status = "";
    private int statusTicks;

    public NpcHub(DerpCraft g, Session s, Actor select) {
        super(g);
        this.s = s;
        if (select != null) {
            selectedId = select.id;
            if (!npcPage()) page = P_NPC;
            inGroup = true;
            s.npcs.currentScene = select.group;
        }
    }

    private static boolean npcPage() { return page >= P_NPC; }

    private NpcManager npcs() { return s.npcs; }

    private Actor selected() { return npcs().byId(selectedId); }

    /** The NPC selected in the hub (for the record key), or null. */
    public static Actor selected(NpcManager n) { return n.byId(selectedId); }

    public static void select(Actor a) { selectedId = a == null ? -1 : a.id; }

    private static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }

    private void go(int p) { page = p; scroll = 0; game.relayout(); }

    @Override public void init() {
        groupField = null;
        nameField = null;
        listTotal = 0;
        NpcManager n = npcs();
        Actor a = selected();
        if (npcPage() && a == null) page = P_SCENES;
        // the NPC's pages always work with its own group (= its scene), so its take is the one shown and recorded
        if (npcPage() && !a.group.equalsIgnoreCase(n.currentScene)) n.currentScene = a.group;
        int x = 4, w = PW - 8, y = 16;
        int t4 = (w - 6) / 4;
        for (int i = 0; i < SCENE_TABS.length; i++) {
            final int p = i;
            int bx = x + i * (t4 + 2);
            add(new Widget.Button(bx, y, i == 3 ? x + w - bx : t4, 15, () -> (page == p ? "§e" : "") + SCENE_TABS[p], () -> go(p)));
        }
        y += 17;
        int t5 = (w - 8) / 5;
        for (int i = 0; i < NPC_TABS.length; i++) {
            final int p = P_NPC + i;
            int bx = x + i * (t5 + 2);
            Widget.Button b = add(new Widget.Button(bx, y, i == 4 ? x + w - bx : t5, 15, () -> (page == p ? "§e" : "") + NPC_TABS[p - P_NPC], () -> go(p)));
            b.active = a != null;
        }
        y += 30;          // the line under the tabs says which scene / NPC the pages are about
        int hw = w / 2 - 1;
        switch (page) {
            case P_SCENES -> { if (!inGroup) groupsPage(x, y, w); else membersPage(x, y, w, hw); }
            case P_PLAY -> playPage(x, y, w, hw);
            case P_REC -> recordPage(x, y, w, hw);
            case P_TEAMS -> teamsPage(x, y, w);
            case P_NPC -> npcPage(a, x, y, w, hw);
            case P_LOOK -> lookPage(a, x, y, w, hw);
            case P_AI -> aiPage(a, x, y, w, hw);
            case P_STATS -> statsPage(a, x, y, w, hw);
            default -> takesPage(a, x, y, w, hw);
        }
        add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose));
    }

    // ================================================================ scene row
    private List<Actor> visibleMembers() { return npcs().members(npcs().currentScene); }

    /** Rows that fit between 'y' and the Done button ('reserve' = pixels kept free below the list). */
    private int fitRows(int y, int reserve) { return Math.max(1, (height - 26 - reserve - y) / 14); }

    /** Scenes page, top level: the scene groups. */
    private void groupsPage(int x, int y, int w) {
        NpcManager n = npcs();
        add(new Widget.Button(x, y, w, 16, "+ New scene (group)", () -> {
            n.currentScene = n.newGroup("Scene");
            inGroup = true;
            scroll = 0;
            game.relayout();
        }));
        y += 20;
        List<String> gs = n.groups();
        listRows = fitRows(y, copySource != null ? 32 : 12);
        listY = y;
        listTotal = gs.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, gs.size() - listRows)));
        for (int r = 0; r < listRows; r++) {
            int idx = scroll + r;
            if (idx >= gs.size()) break;
            String g = gs.get(idx);
            int bw = w - 2 * 33;
            add(new Widget.Button(x, y + r * 14, bw, 13, () -> groupRow(g), () -> { n.currentScene = g; inGroup = true; scroll = 0; n.save(); game.relayout(); }));
            add(new Widget.Button(x + bw + 1, y + r * 14, 32, 13, () -> n.groupEnabled(g) ? "§aOn" : "§8Off", () -> {
                n.setGroupEnabled(g, !n.groupEnabled(g));
                flash(n.groupEnabled(g) ? "\"" + g + "\" is back in the world" : "\"" + g + "\" switched off: its NPCs left the world");
            }));
            add(new Widget.Button(x + bw + 34, y + r * 14, 32, 13, "Copy", () -> { copySource = g; game.relayout(); }));
        }
        if (copySource != null) copyPrompt(x, y + Math.min(listRows, gs.size()) * 14 + 14, w);
    }

    private String groupRow(String g) {
        NpcManager n = npcs();
        List<Actor> m = n.members(g);
        int len = n.sceneLength(g);
        String head = (g.equalsIgnoreCase(n.currentScene) ? "§e" : n.groupEnabled(g) ? "" : "§8") + (n.scenePlaying(g) ? "> " : "");
        String name = g.length() > 14 ? g.substring(0, 13) + "." : g;
        return head + name + "  §7" + m.size() + " NPC" + (len > 0 ? " " + time(len) : "");
    }

    private static String time(int ticks) { return String.format(Locale.ROOT, "%d:%02d", ticks / 1200, (ticks / 20) % 60); }

    private void copyPrompt(int x, int y, int w) {
        String src = copySource;
        int tw3 = (w - 4) / 3;
        add(new Widget.Button(x, y, tw3, 16, "§aWith takes", () -> doCopy(src, true)));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Without", () -> doCopy(src, false)));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, "Cancel", () -> { copySource = null; game.relayout(); }));
    }

    private void doCopy(String src, boolean takes) {
        String made = npcs().copyGroup(src, takes);
        copySource = null;
        if (made != null) {
            npcs().currentScene = made;
            flash("\"" + made + "\" made " + (takes ? "with" : "without") + " takes. The copies stand where the originals are; switch one group Off.");
        }
        game.relayout();
    }

    /** Scenes page inside a group: its NPCs and the scene's main buttons. */
    private void membersPage(int x, int y, int w, int hw) {
        NpcManager n = npcs();
        String g = n.currentScene;
        add(new Widget.Button(x, y, 52, 16, "< Scenes", () -> { inGroup = false; scroll = 0; copySource = null; game.relayout(); }));
        groupField = add(new Widget.TextField(x + 55, y + 2, w - 55, 12));
        groupField.text = g;
        groupField.maxLength = 24;
        groupField.hint = "scene name";
        groupField.onEnter = () -> {
            groupField.focused = false;
            String nv = groupField.text.trim();
            if (!nv.isEmpty() && !nv.equals(g)) {
                if (n.groups().stream().anyMatch(o -> o.equalsIgnoreCase(nv) && !o.equalsIgnoreCase(g))) flash("A group called \"" + nv + "\" exists");
                else { n.renameGroup(g, nv); flash("Scene renamed"); }
            }
            game.relayout();
        };
        y += 18;
        int tw3 = (w - 4) / 3;
        Widget.Button play = add(new Widget.Button(x, y, tw3, 16, () -> n.scenePlaying(g) ? "§eStop" : "§aPlay", () -> {
            if (n.scenePlaying(g)) n.stopScene(g);
            else if (n.playScene(g)) onClose();
            else flash("Nothing recorded in \"" + g + "\" yet");
        }));
        play.active = n.sceneLength(g) > 0 || n.scenePlaying(g);
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Timeline", () -> game.setScreen(new Timeline(game, s, this))));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, () -> n.restorable(g) > 0 ? "§bRestore" : "§7Restore", () -> restore(g)));
        y += 18;
        add(new Widget.Button(x, y, hw, 16, "+ New NPC", () -> {
            Actor na = s.spawnNpc("Actor");
            selectedId = na.id;
            if (!n.groupEnabled(g)) flash("This group is off: switch it on to see the NPC");
            go(P_NPC);
        }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, "§cRec§f: new NPC", () -> {
            if (!n.groupEnabled(g)) n.setGroupEnabled(g, true);
            Actor na = s.spawnNpc("Actor");
            na.behavior = Actor.Behavior.STAND;
            selectedId = na.id;
            record(na);
            n.recNewActor = na;
        }));
        y += 18;
        int tw4 = (w - 6) / 4;
        add(new Widget.Button(x, y, tw4, 16, () -> n.groupEnabled(g) ? "§aOn" : "§8Off", () -> n.setGroupEnabled(g, !n.groupEnabled(g))));
        add(new Widget.Button(x + tw4 + 2, y, tw4, 16, () -> crowdOpen ? "§eCrowd" : "Crowd", () -> { crowdOpen = !crowdOpen; game.relayout(); }));
        add(new Widget.Button(x + 2 * (tw4 + 2), y, tw4, 16, "Copy", () -> { copySource = g; game.relayout(); }));
        add(new Widget.Button(x + 3 * (tw4 + 2), y, w - 3 * (tw4 + 2), 16, () -> System.currentTimeMillis() - deleteArmed < 3000 ? "§cSure?" : "Delete", () -> {
            if (System.currentTimeMillis() - deleteArmed < 3000) {
                n.deleteGroup(g);
                inGroup = false;
                flash("Scene \"" + g + "\" deleted");
                game.relayout();
            } else { deleteArmed = System.currentTimeMillis(); flash("Click Delete again to delete \"" + g + "\" with its NPCs and takes"); }
        }));
        y += 20;
        if (crowdOpen) {
            Widget.Slider cs = add(new Widget.Slider(x, y, hw + 30, 16, "Extras", 1, 60, 1, () -> crowdCount, v -> crowdCount = (int) Math.round(v), v -> String.valueOf(Math.round(v))));
            cs.defaultValue = 12;
            add(new Widget.Button(x + hw + 32, y, w - hw - 32, 16, "§aSpawn", () -> { int made = spawnCrowd(); crowdOpen = false; flash(made + " extras spawned in \"" + g + "\""); game.relayout(); }));
            y += 18;
        }
        List<Actor> list = visibleMembers();
        listRows = fitRows(y, copySource != null ? 32 : 12);
        listY = y;
        listTotal = list.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - listRows)));
        for (int r = 0; r < listRows; r++) {
            int idx = scroll + r;
            if (idx >= list.size()) break;
            Actor ac = list.get(idx);
            int bw = w - 2 * 31;
            add(new Widget.Button(x, y + r * 14, bw, 13, () -> (ac.id == selectedId ? "§e> " : ac.enabled ? "" : "§8") + ac.name + rowInfo(ac), () -> { selectedId = ac.id; go(P_NPC); }));
            add(new Widget.Button(x + bw + 1, y + r * 14, 30, 13, () -> ac.enabled ? "§aOn" : "§8Off", () -> n.setEnabled(ac, !ac.enabled)));
            add(new Widget.Button(x + bw + 32, y + r * 14, 30, 13, () -> ac.sceneEnabled ? "§bScn" : "§8Scn", () -> { ac.sceneEnabled = !ac.sceneEnabled; n.save(); }));
        }
        if (copySource != null) copyPrompt(x, y + Math.min(listRows, list.size()) * 14 + 14, w);
    }

    /** Play page: playing the scene, how actors start, and what the scene leaves in the world. */
    private void playPage(int x, int y, int w, int hw) {
        NpcManager n = npcs();
        add(new Widget.Button(x, y, 16, 16, "<", () -> cycleScene(-1)));
        add(new Widget.Button(x + 18, y, w - 36, 16, () -> "Scene: " + n.currentScene, () -> { page = P_SCENES; inGroup = true; game.relayout(); }));
        add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> cycleScene(1)));
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> n.scenePlaying(n.currentScene) ? "§eStop" : "§aPlay scene", () -> {
            if (n.scenePlaying(n.currentScene)) n.stopScene(n.currentScene);
            else if (n.playScene()) onClose();
            else flash("Nothing recorded for \"" + n.currentScene + "\"");
        }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, "To start", () -> { n.resetScene(); flash("Blocks and actors of \"" + n.currentScene + "\" back at the start"); }));
        y += 24;
        worldRows(x, y, w, hw, n.currentScene);
        y += 36 + 6;
        add(new Widget.Button(x, y, w, 16, () -> n.walkToMarks ? "Start: actors walk to their marks" : "Start: actors teleport to their marks", () -> { n.walkToMarks = !n.walkToMarks; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Loop: " + onOff(n.loopScene), () -> { n.loopScene = !n.loopScene; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Camera: " + onOff(n.linkCamera), () -> { n.linkCamera = !n.linkCamera; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Replayed hits & shots: " + (n.replayGunDamage ? "§cdamage" : "§ashow only"), () -> { n.replayGunDamage = !n.replayGunDamage; n.save(); }));
    }

    /** The scene's effect on the world: the switch and the restore button (Play page and Takes page). */
    private void worldRows(int x, int y, int w, int hw, String g) {
        NpcManager n = npcs();
        add(new Widget.Button(x, y, w, 16, () -> n.restoresWorld(g) ? "World after the scene: §brestored" : "World after the scene: §ekept", () -> {
            n.setRestoresWorld(g, !n.restoresWorld(g));
            flash(n.restoresWorld(g) ? "After each play (and take) the blocks the actors changed come back as they were"
                    : "What the actors break or build stays; Restore puts it back");
        }));
        y += 18;
        Widget.Button r = add(new Widget.Button(x, y, w, 16, () -> {
            int k = n.restorable(g);
            return k > 0 ? "§bRestore world §7(" + k + " block" + (k == 1 ? "" : "s") + ")" : "§7Restore world (nothing changed)";
        }, () -> restore(g)));
        r.active = true;
    }

    private void restore(String g) {
        int k = npcs().restorable(g);
        if (k == 0) { flash("Nothing to restore in \"" + g + "\""); return; }
        int done = npcs().restoreWorld(g);
        flash(done + " block(s) of \"" + g + "\" back as they were before the scene");
    }

    /** Record page: how takes are recorded. */
    private void recordPage(int x, int y, int w, int hw) {
        NpcManager n = npcs();
        Widget.Slider cd = add(new Widget.Slider(x, y, w, 16, "Countdown", 0, 5, 1, () -> n.countdownSeconds, v -> { n.countdownSeconds = (int) Math.round(v); n.save(); },
                v -> Math.round(v) == 0 ? "none" : Math.round(v) + " s"));
        cd.defaultValue = 3;
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Record as the NPC: " + onOff(n.recordAsNpc), () -> {
            n.recordAsNpc = !n.recordAsNpc;
            n.save();
            flash(n.recordAsNpc ? "You start where the NPC stands, in its skin, and come back to your place after the take" : "Takes start where you stand");
        }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Other actors play along: " + onOff(n.othersPlayWhileRecording), () -> { n.othersPlayWhileRecording = !n.othersPlayWhileRecording; n.save(); }));
        y += 24;
        add(new Widget.Button(x, y, hw, 16, () -> "Blocks: " + onOff(n.recordBlocks), () -> { n.recordBlocks = !n.recordBlocks; n.save(); }));
        Widget.Button wb = add(new Widget.Button(x + hw + 2, y, hw, 16, () -> n.recordWorld ? "+ world: §aON" : "+ world: §7OFF", () -> {
            n.recordWorld = !n.recordWorld;
            n.save();
            flash(n.recordWorld ? "Explosions, fire, water, falling sand you set off are recorded too" : "Only the blocks you click are recorded");
        }));
        wb.active = n.recordBlocks;
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Chat lines: " + onOff(n.recordChat), () -> { n.recordChat = !n.recordChat; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Scene's NPCs with AI: " + (n.recordAi ? "§arecorded" : "§7not"), () -> {
            n.recordAi = !n.recordAi;
            n.save();
            flash(n.recordAi ? "NPCs of this scene that live by their AI (following you, guards...) get their own takes with yours"
                    : "Only the actor you play is recorded");
        }));
    }

    private void teamsPage(int x, int y, int w) {
        NpcManager n = npcs();
        // faction relations: row = attacker, column = target
        int cw = (w - 56) / 3;
        y += 12;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final int fi = i, fj = j;
                Widget.Button b = add(new Widget.Button(x + 56 + j * (cw + 1), y + i * 18, cw, 16, () -> fi == fj ? "-" : n.attacks[fi][fj] ? "§cattack" : "§7peace", () -> {
                    if (fi != fj) { n.attacks[fi][fj] = !n.attacks[fi][fj]; n.save(); }
                }));
                b.active = i != j;
            }
        }
        y += 3 * 18 + 6;
        add(new Widget.Button(x, y, w, 16, () -> "Attack creative player: " + onOff(n.attackCreative), () -> { n.attackCreative = !n.attackCreative; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "You can hurt Friendly: " + onOff(n.playerHurtsFriendly), () -> {
            n.playerHurtsFriendly = !n.playerHurtsFriendly;
            n.save();
            flash(n.playerHurtsFriendly ? "Your hits and shots hurt and kill Friendly NPCs too" : "Friendly NPCs ignore your hits");
        }));
    }

    // ================================================================ NPC row
    /** NPC page: name, place, presence, group. */
    private void npcPage(Actor a, int x, int y, int w, int hw) {
        NpcManager n = npcs();
        nameField = add(new Widget.TextField(x + 1, y + 1, w - 2, 12));
        nameField.text = a.name;
        nameField.maxLength = 32;
        nameField.hint = "name";
        nameField.onChange = () -> n.rename(a, nameField.text.trim());
        nameField.onEnter = () -> { nameField.focused = false; n.save(); };
        y += 17;
        int tw3 = (w - 4) / 3;
        add(new Widget.Button(x, y, tw3, 16, "Move here", () -> {
            a.place(s.player.x, s.player.y, s.player.z, s.player.yaw);
            a.setHome();
            n.save();
            flash("Moved " + a.name + " to you");
        }));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Face me", () -> {
            double dx = s.player.x - a.x(), dz = s.player.z - a.z();
            a.place(a.x(), a.y(), a.z(), (float) Math.toDegrees(Math.atan2(-dx, dz)));
            a.setHome();
            n.save();
        }));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, "Go to", () -> {
            double yr = Math.toRadians(a.yaw());
            s.player.setPos(a.x() - Math.sin(yr) * 2.5, a.y(), a.z() + Math.cos(yr) * 2.5);
            s.player.yaw = a.yaw() + 180;
            s.player.pitch = 10;
        }));
        y += 24;
        add(new Widget.Button(x, y, hw, 16, () -> a.enabled ? "In world: §aON" : "In world: §cOFF", () -> {
            n.setEnabled(a, !a.enabled);
            flash(a.enabled ? a.name + " is back in the world" : a.name + " is switched off (kept, not in the world)");
        }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> a.sceneEnabled ? "In scene: §aON" : "In scene: §cOFF", () -> {
            a.sceneEnabled = !a.sceneEnabled;
            n.save();
            flash(a.sceneEnabled ? a.name + " plays in the scene" : a.name + " skips the scene (keeps its AI)");
        }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Scene group: " + a.group + "  §7(click: move)", () -> {
            List<String> gs = n.groups();
            String to = gs.get(Math.floorMod(gs.indexOf(a.group) + 1, gs.size()));
            n.moveToGroup(a, to);
            n.currentScene = a.group;
            flash(a.name + " moved to \"" + a.group + "\"" + (n.groupEnabled(a.group) ? "" : " (that group is off)"));
        }));
        y += 24;
        add(new Widget.Button(x, y, tw3, 16, () -> a.dead() ? "§aRevive" : "Heal", () -> { a.revive(); flash(a.name + " is fine"); }));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Duplicate", () -> {
            Actor c = n.duplicate(a);
            selectedId = c.id;
            flash(c.name + ": a copy of " + a.name + " (same look, AI and stats; no takes)");
            game.relayout();
        }));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, () -> System.currentTimeMillis() - deleteNpcArmed < 3000 ? "§cSure?" : "§cDelete", () -> {
            if (System.currentTimeMillis() - deleteNpcArmed < 3000) {
                n.remove(a);
                selectedId = -1;
                page = P_SCENES;
                inGroup = true;
                flash(a.name + " deleted");
                game.relayout();
            } else { deleteNpcArmed = System.currentTimeMillis(); flash("Click again to delete " + a.name + " with its takes"); }
        }));
    }

    private void lookPage(Actor a, int x, int y, int w, int hw) {
        NpcManager n = npcs();
        add(new Widget.Button(x, y, 16, 16, "<", () -> cycleSkin(a, -1)));
        add(new Widget.Button(x + 18, y, w - 36, 16, () -> "Skin: " + a.skin, () -> cycleSkin(a, 1)));
        add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> cycleSkin(a, 1)));
        y += 18;
        add(new Widget.Button(x, y, 16, 16, "<", () -> cyclePose(a, -1)));
        add(new Widget.Button(x + 18, y, w - 36 - 34, 16, () -> "Pose: " + (a.customPose.isEmpty() ? a.pose.label : a.customPose), () -> cyclePose(a, 1)));
        add(new Widget.Button(x + w - 16 - 34, y, 16, 16, ">", () -> cyclePose(a, 1)));
        add(new Widget.Button(x + w - 32, y, 32, 16, "Edit", () -> game.setScreen(new PoseEditor(game, s, this, a))));
        y += 18;
        add(new Widget.Button(x, y, 16, 16, "<", () -> { a.emotion = Math.floorMod(a.emotion - 1, derpcraft.npc.Emotions.NAMES.length); n.save(); }));
        add(new Widget.Button(x + 18, y, hw - 18, 16, () -> "Face: " + derpcraft.npc.Emotions.NAMES[a.emotion], () -> { a.emotion = (a.emotion + 1) % derpcraft.npc.Emotions.NAMES.length; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Blinks: " + onOff(a.blink), () -> { a.blink = !a.blink; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Main hand: " + (a.held == null ? "§7- (gives yours)" : a.held.item.displayName), () -> {
            ItemStack h = s.heldStack();
            a.held = h == null ? null : new ItemStack(h.item, 1);
            n.save();
            flash(h == null ? "Main hand emptied" : "Gave " + h.item.displayName + " (the item in your hand)");
        }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "Off hand: " + (a.offhand == null ? "§7- (gives yours)" : a.offhand.item.displayName), () -> {
            ItemStack h = s.heldStack();
            a.offhand = h == null ? null : new ItemStack(h.item, 1);
            n.save();
        }));
        y += 18;
        int tw3 = (w - 4) / 3;
        add(new Widget.Button(x, y, tw3, 16, "Your armor", () -> {
            for (int i = 0; i < 4; i++) { ItemStack st = s.inv.get(derpcraft.game.PlayerInventory.ARMOR + i); a.armor[i] = st == null ? null : new ItemStack(st.item, 1); }
            n.save();
            flash("Dressed " + a.name + " in your armor");
        }));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Hat: hand", () -> {
            ItemStack h = s.heldStack();
            a.armor[0] = h == null ? null : new ItemStack(h.item, 1);
            n.save();
            flash(h == null ? "Hat removed" : h.item.displayName + " on the head");
        }));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, "No armor", () -> { java.util.Arrays.fill(a.armor, null); n.save(); }));
        y += 18;
        Widget.Slider sc = add(new Widget.Slider(x, y, w, 16, "Size", 0.25f, 3, 0.05f, () -> a.scale, v -> { a.scale = (float) v; n.save(); },
                v -> String.format(Locale.ROOT, "%.2fx", v)));
        sc.defaultValue = 1;
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Name tag: " + onOff(a.showName), () -> { a.showName = !a.showName; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Color: " + nameColorLabel(a), () -> { a.nameColor = (a.nameColor + 1) % Actor.NAME_COLORS.length; n.save(); }));
    }

    private static String nameColorLabel(Actor a) {
        if (a.nameColor <= 0) return "auto";
        return String.format("§%s%s", new String[]{"", "f", "e", "a", "b", "6", "d", "7"}[a.nameColor], Actor.NAME_COLOR_NAMES[a.nameColor]);
    }

    private void aiPage(Actor a, int x, int y, int w, int hw) {
        NpcManager n = npcs();
        add(new Widget.Button(x, y, hw, 16, () -> a.behavior.label, () -> {
            a.behavior = next(a.behavior);
            if (a.behavior == Actor.Behavior.DRIVE && s.vehicles != null && s.vehicles.byId(a.vehicleId) == null) {
                var v = s.vehicles.nearest(a.x(), a.z(), 48);
                if (v != null) a.vehicleId = v.id;
            }
            a.setHome(); n.save();
            game.relayout();          // the row below depends on the behaviour
        }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "§" + factionColor(a.faction) + a.faction.label, () -> { a.faction = next(a.faction); a.calm(); n.save(); }));
        y += 18;
        behaviourRow(a, x, y, w);
        y += 24;
        slider(x, y, w, "Attack range (sees enemies)", 2, 64, 1, () -> a.aggroRange, v -> a.aggroRange = (float) v, v -> Math.round(v) + " blocks", 10);
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Hit: " + a.onHit.label.toLowerCase(Locale.ROOT), () -> { a.onHit = next(a.onHit); n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> a.guard.label, () -> { a.guard = next(a.guard); n.save(); }));
        y += 18;
        Widget.Slider fs = add(new Widget.Slider(x, y, hw, 16, "Follow", 1, 24, 0.5f, () -> a.followStart, v -> { a.followStart = (float) v; a.followStop = Math.min(a.followStop, a.followStart); n.save(); },
                v -> String.format(Locale.ROOT, ">%.1f", v)));
        fs.defaultValue = 6;
        Widget.Slider fst = add(new Widget.Slider(x + hw + 2, y, hw, 16, "Stop", 0.5f, 12, 0.5f, () -> a.followStop, v -> { a.followStop = (float) Math.min(v, a.followStart); n.save(); },
                v -> String.format(Locale.ROOT, "<%.1f", v)));
        fst.defaultValue = 2.5;
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Doors: " + (a.openDoors ? "§aopens" : "§7no"), () -> { a.openDoors = !a.openDoors; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Break blocks: " + onOff(a.breakBlocks), () -> { a.breakBlocks = !a.breakBlocks; n.save(); }));
        y += 18;
        slider(x, y, hw, "Look", 2, 32, 1, () -> a.lookRange, v -> a.lookRange = (float) v, v -> Math.round(v) + " bl", 10);
        slider(x + hw + 2, y, hw, "Wander", 2, 32, 1, () -> a.wanderRadius, v -> a.wanderRadius = (float) v, v -> Math.round(v) + " bl", 8);
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Help allies: " + onOff(a.helpAllies), () -> {
            a.helpAllies = !a.helpAllies;
            n.save();
            flash(a.helpAllies ? a.name + " attacks whoever attacks a " + a.faction.label + " NPC within its attack range" : a.name + " minds its own fights");
        }));
        Widget.Button as = add(new Widget.Button(x + hw + 2, y, hw, 16, () -> (a.behavior == Actor.Behavior.FOLLOW ? "" : "§7") + "Assist me: " + onOff(a.assistPlayer), () -> {
            a.assistPlayer = !a.assistPlayer;
            n.save();
            flash(a.assistPlayer ? "Following you, " + a.name + " attacks and shoots whatever you attack (any faction)" : a.name + " does not join your fights");
        }));
        as.active = true;
    }

    /** Stats page: speed, health, damage, how the body reacts. */
    private void statsPage(Actor a, int x, int y, int w, int hw) {
        NpcManager n = npcs();
        slider(x, y, w, "Speed", 0.25f, 3, 0.05f, () -> a.speed, v -> a.speed = (float) v, v -> String.format(Locale.ROOT, "%.2fx", v), 1);
        y += 18;
        Widget.Slider hp = add(new Widget.Slider(x, y, hw, 16, "HP", 1, 200, 1, () -> a.maxHealth, v -> { a.maxHealth = (float) v; if (!a.dead()) a.health = a.maxHealth; n.save(); },
                v -> String.valueOf(Math.round(v))));
        hp.defaultValue = 20;
        slider(x + hw + 2, y, hw, "Dmg", 0, 40, 0.5f, () -> a.damage, v -> a.damage = (float) v, v -> v == 0 ? "item" : String.format(Locale.ROOT, "%.1f", v), 0);
        y += 18;
        slider(x, y, hw, "Hit every", 4, 40, 1, () -> a.attackDelay, v -> a.attackDelay = (int) Math.round(v), v -> String.format(Locale.ROOT, "%.2fs", v / 20f), 12);
        slider(x + hw + 2, y, hw, "Regen", 0, 4, 0.25f, () -> a.regen, v -> a.regen = (float) v, v -> v == 0 ? "off" : String.format(Locale.ROOT, "%.2f/s", v), 0);
        y += 22;
        add(new Widget.Button(x, y, hw, 16, () -> a.invulnerable ? "Immortal: §aON" : "Immortal: §7OFF", () -> { a.invulnerable = !a.invulnerable; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Knockback: " + onOff(a.knockback), () -> { a.knockback = !a.knockback; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Pushable: " + onOff(a.pushable), () -> { a.pushable = !a.pushable; n.save(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Fall damage: " + onOff(a.takesFallDamage), () -> { a.takesFallDamage = !a.takesFallDamage; n.save(); }));
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> a.respawn ? "Death: §arespawns" : "Death: §7stays", () -> {
            a.respawn = !a.respawn;
            n.save();
            flash(a.respawn ? "After death " + a.name + " stands up at home after 5 s" : "After death " + a.name + " stays down until Revive (NPC page)");
        }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, "Reset stats", () -> {
            a.respawnSeconds = 5;
            a.speed = 1; a.maxHealth = 20; a.health = Math.min(a.health, 20); a.damage = 0; a.attackDelay = 12; a.regen = 0;
            a.invulnerable = false; a.knockback = true; a.pushable = true; a.takesFallDamage = true; a.respawn = true;
            n.save();
            flash(a.name + ": default stats");
        }));
        y += 18;
        Widget.Slider rs = add(new Widget.Slider(x, y, w, 16, "Respawn after", 1, 300, 1, () -> a.respawnSeconds, v -> { a.respawnSeconds = (float) v; n.save(); },
                v -> v >= 60 ? String.format(Locale.ROOT, "%d:%02d", Math.round(v) / 60, Math.round(v) % 60) : Math.round(v) + " s"));
        rs.defaultValue = 5;
        rs.active = true;
    }

    /** Takes page: record, pick the take, timeline, and what the scene does to the world. */
    private void takesPage(Actor a, int x, int y, int w, int hw) {
        NpcManager n = npcs();
        String g = n.currentScene;
        Recording t = a.take(g);
        add(new Widget.Button(x, y, hw, 16, t == null ? "§cRecord" : "§cRecord new take", () -> record(a)));
        Widget.Button play = add(new Widget.Button(x + hw + 2, y, hw, 16, () -> n.scenePlaying(g) ? "§eStop" : "§aPlay scene", () -> {
            if (n.scenePlaying(g)) n.stopScene(g); else if (n.playScene()) onClose();
        }));
        play.active = n.sceneLength() > 0 || n.scenePlaying(g);
        y += 18;
        derpcraft.npc.Track tr = a.track(g);
        int nt = tr == null ? 0 : tr.takes.size();
        add(new Widget.Button(x, y, 16, 16, "<", () -> cycleTake(a, -1))).active = nt > 1;
        add(new Widget.Button(x + 18, y, hw - 36, 16, () -> { var tt = a.track(g); return tt == null || tt.takes.isEmpty() ? "No takes" : "Take " + (tt.active + 1) + " / " + tt.takes.size(); }, () -> cycleTake(a, 1)));
        add(new Widget.Button(x + hw - 16, y, 16, 16, ">", () -> cycleTake(a, 1))).active = nt > 1;
        add(new Widget.Button(x + hw + 2, y, hw, 16, "Timeline...", () -> game.setScreen(new Timeline(game, s, this))));
        y += 18;
        Widget.Button clear = add(new Widget.Button(x, y, w, 16, () -> System.currentTimeMillis() - deleteArmed < 3000 ? "§cSure? (click again)" : "Delete this take", () -> {
            if (System.currentTimeMillis() - deleteArmed < 3000) {
                deleteArmed = 0;
                n.clearTake(a, g);
                flash("Take of " + a.name + " in \"" + g + "\" deleted");
                game.relayout();
            } else deleteArmed = System.currentTimeMillis();
        }));
        clear.active = t != null;
        y += 18 + 34;          // the take's info lines
        worldRows(x, y, w, hw, g);
    }

    private static String onOff(boolean b) { return b ? "§aON" : "§7OFF"; }

    private static boolean crowdOpen;
    private static int crowdCount = 12;

    /**
     * Extras: random skins and names, walking around (or patrolling between random points). Area: the world edit
     * selection (//pos1, //pos2) if there is one, else 14 blocks around you.
     */
    private int spawnCrowd() {
        NpcManager n = npcs();
        java.util.Random r = new java.util.Random();
        int[] sel = s.edit.selection();
        double x0, z0, x1, z1;
        if (sel != null) { x0 = sel[0]; z0 = sel[2]; x1 = sel[3] + 1; z1 = sel[5] + 1; }
        else { x0 = s.player.x - 14; z0 = s.player.z - 14; x1 = s.player.x + 14; z1 = s.player.z + 14; }
        List<String> skins = n.skins.names();
        int made = 0;
        for (int i = 0; i < crowdCount; i++) {
            double x = x0 + r.nextDouble() * (x1 - x0), z = z0 + r.nextDouble() * (z1 - z0);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!s.world.isLoaded(bx, bz)) continue;
            int y = s.world.topSolidY(bx, bz) + 1;
            Actor e = n.spawn("Extra", x, y, z, r.nextFloat() * 360);
            e.skin = skins.get(r.nextInt(skins.size()));
            e.showName = false;
            e.faction = Actor.Faction.FRIENDLY;
            e.guard = r.nextInt(3) == 0 ? Actor.Guard.FLEE : Actor.Guard.IGNORE;
            if (r.nextBoolean()) {
                e.behavior = Actor.Behavior.PATROL;
                e.patrolLoop = r.nextBoolean();
                e.patrolWait = 10 + r.nextInt(60);
                for (int k = 0; k < 3; k++) {
                    double px = x0 + r.nextDouble() * (x1 - x0), pz = z0 + r.nextDouble() * (z1 - z0);
                    e.waypoints.add(new double[]{px, s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz)) + 1, pz});
                }
            } else e.behavior = r.nextInt(4) == 0 ? Actor.Behavior.LOOK : Actor.Behavior.WANDER;
            made++;
        }
        n.save();
        return made;
    }

    /** Extra controls for patrol / seat / watch / ride. */
    private void behaviourRow(Actor a, int x, int y, int w) {
        NpcManager n = npcs();
        int tw3 = (w - 4) / 3;
        switch (a.behavior) {
            case PATROL -> {
                add(new Widget.Button(x, y, tw3, 16, "+ Point here", () -> {
                    a.waypoints.add(new double[]{s.player.x, s.player.y, s.player.z});
                    n.save();
                    flash("Point " + a.waypoints.size() + " at your feet");
                }));
                add(new Widget.Button(x + tw3 + 2, y, tw3, 16, () -> "Clear (" + a.waypoints.size() + ")", () -> { a.waypoints.clear(); n.save(); }));
                add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, () -> a.patrolLoop ? "Loop" : "Back+forth", () -> { a.patrolLoop = !a.patrolLoop; n.save(); }));
            }
            case SEAT -> add(new Widget.Button(x, y, w, 16, () -> a.seat == null ? "Seat: the block you look at" : "Seat: " + a.seat[0] + " " + a.seat[1] + " " + a.seat[2] + " (set again)", () -> {
                var t = s.target;
                if (t == null) { flash("Look at a chair (stairs, slab...) before opening the hub"); return; }
                a.seat = new int[]{t.x, t.y, t.z};
                a.homeYaw = s.player.yaw + 180;
                n.save();
                flash(a.name + " will sit on " + t.block.displayName);
            }));
            case WATCH -> add(new Widget.Button(x, y, w, 16, () -> { Actor t = n.byId(a.watchId); return "Watches: " + (t == null ? "nobody (click)" : t.name); }, () -> {
                List<Actor> l = new java.util.ArrayList<>(n.members(a.group));
                l.remove(a);
                if (l.isEmpty()) return;
                int i = -1;
                for (int k = 0; k < l.size(); k++) if (l.get(k).id == a.watchId) i = k;
                a.watchId = l.get((i + 1) % l.size()).id;
                n.save();
            }));
            case RIDE -> add(new Widget.Button(x, y, w, 16, () -> rideLabel(a), () -> cycleRide(a)));
            case DRIVE -> {
                // the vehicle (click = next one near the NPC), the seat, the route (= patrol points; Shift + click clears)
                add(new Widget.Button(x, y, tw3, 16, () -> {
                    var v = s.vehicles == null ? null : s.vehicles.byId(a.vehicleId);
                    return v == null ? "Car: none" : "Car #" + v.id;
                }, () -> cycleVehicle(a)));
                add(new Widget.Button(x + tw3 + 2, y, tw3, 16, () -> a.vehicleSeat < 0 ? "Seat: driver" : "Seat " + (a.vehicleSeat + 1), () -> {
                    var v = s.vehicles == null ? null : s.vehicles.byId(a.vehicleId);
                    int seats = v == null || v.vm() == null ? 4 : v.vm().seats.size(), driver = v == null || v.vm() == null ? 0 : v.vm().driverSeat;
                    int next = a.vehicleSeat + 1;
                    if (next == driver) next++;                 // the driver's seat is "Seat: driver"
                    a.vehicleSeat = next >= seats ? -1 : next;
                    n.save();
                }));
                add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, () -> a.waypoints.isEmpty() ? "Route: follow me" : "Route: " + a.waypoints.size() + " pts", () -> {
                    boolean shift = org.lwjgl.glfw.GLFW.glfwGetKey(s.game.window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    if (shift) { a.waypoints.clear(); a.driveWp = 0; n.save(); flash("Route cleared: " + a.name + " drives after you"); return; }
                    a.waypoints.add(new double[]{s.player.x, s.player.y, s.player.z});
                    n.save();
                    flash("Route point " + a.waypoints.size() + " at your feet (Shift + click: clear)");
                }));
            }
            default -> add(new Widget.Button(x, y, w, 16, () -> "§7" + behaviourHelp(a.behavior), () -> {})).active = false;
        }
    }

    private static String behaviourHelp(Actor.Behavior b) {
        return switch (b) {
            case STAND -> "stays on its spot";
            case LOOK -> "turns its head to you";
            case WANDER -> "walks around its spot";
            case FOLLOW -> "follows you (distances below)";
            case DRIVE -> "gets into its car and drives";
            default -> "";
        };
    }

    /** DRIVE: the next vehicle within 48 blocks of the NPC (nearest first). */
    private void cycleVehicle(Actor a) {
        if (s.vehicles == null || s.vehicles.list().isEmpty()) { flash("No vehicles: take a Car from the Transport tab or type /vehicle"); return; }
        List<derpcraft.vehicle.Vehicle> l = new java.util.ArrayList<>();
        for (var v : s.vehicles.list()) if (v.distSq(a.x(), a.z()) < 48 * 48) l.add(v);
        if (l.isEmpty()) { flash("No vehicle within 48 blocks of " + a.name); return; }
        l.sort(java.util.Comparator.comparingDouble(v -> v.distSq(a.x(), a.z())));
        int i = -1;
        for (int k = 0; k < l.size(); k++) if (l.get(k).id == a.vehicleId) i = k;
        a.vehicleId = l.get((i + 1) % l.size()).id;
        npcs().save();
        flash(a.name + " will drive car #" + a.vehicleId);
    }

    private String rideLabel(Actor a) {
        if (a.rideCreature) {
            for (var c : s.mobs.list()) if (c.id == a.rideId) return "Rides: " + c.type.displayName;
            return "Rides: (creature gone, click)";
        }
        Actor t = npcs().byId(a.rideId);
        return "Rides: " + (t == null ? "nothing (click)" : t.name);
    }

    /** Ride targets: NPCs of the group, then creatures within 16 blocks. */
    private void cycleRide(Actor a) {
        List<int[]> opts = new java.util.ArrayList<>();
        for (Actor o : npcs().members(a.group)) if (o != a) opts.add(new int[]{o.id, 0});
        for (var c : s.mobs.list()) if (c.distSq(a.x(), a.y(), a.z()) < 16 * 16) opts.add(new int[]{c.id, 1});
        if (opts.isEmpty()) { flash("Nothing to ride: other NPCs of this group or creatures within 16 blocks"); return; }
        int i = -1;
        for (int k = 0; k < opts.size(); k++) if (opts.get(k)[0] == a.rideId && (opts.get(k)[1] == 1) == a.rideCreature) i = k;
        int[] o = opts.get((i + 1) % opts.size());
        a.rideId = o[0]; a.rideCreature = o[1] == 1;
        npcs().save();
    }

    private void cyclePose(Actor a, int dir) {
        // the 4 simple poses, then the pose library
        List<String> names = new java.util.ArrayList<>();
        for (Actor.Pose p : Actor.Pose.values()) names.add("#" + p.name());
        names.addAll(derpcraft.npc.Poses.names());
        String cur = a.customPose.isEmpty() ? "#" + a.pose.name() : a.customPose;
        int i = 0;
        for (int k = 0; k < names.size(); k++) if (names.get(k).equalsIgnoreCase(cur)) i = k;
        String nx = names.get(Math.floorMod(i + dir, names.size()));
        if (nx.startsWith("#")) { a.customPose = ""; a.pose = Actor.Pose.valueOf(nx.substring(1)); }
        else { a.customPose = nx; a.pose = Actor.Pose.NORMAL; }
        npcs().save();
    }

    private void cycleTake(Actor a, int dir) {
        var t = a.track(npcs().currentScene);
        if (t == null || t.takes.size() < 2) return;
        t.active = Math.floorMod(t.active + dir, t.takes.size());
        npcs().save();
    }

    private static char factionColor(Actor.Faction f) { return switch (f) { case AGGRESSIVE -> 'c'; case NEUTRAL -> 'e'; default -> 'a'; }; }

    private void slider(int x, int y, int w, String name, float min, float max, float step, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set,
                        java.util.function.Function<Float, String> fmt, double def) {
        Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, name, min, max, step, get, v -> { set.accept(v); npcs().save(); }, fmt));
        sl.defaultValue = def;
    }

    private void cycleSkin(Actor a, int dir) {
        List<String> names = npcs().skins.names();
        int i = names.indexOf(a.skin);
        a.skin = names.get(Math.floorMod(i + dir, names.size()));
        npcs().save();
    }

    private void cycleScene(int dir) {
        List<String> names = npcs().groups();
        int i = names.indexOf(npcs().currentScene);
        npcs().currentScene = names.get(Math.floorMod(i + dir, names.size()));
        npcs().save();
        game.relayout();
    }

    private String rowInfo(Actor a) {
        String f = "§" + factionColor(a.faction);
        if (a.dead()) return "  §8dead";
        Recording t = a.take(npcs().currentScene);
        if (t != null) return String.format(Locale.ROOT, "  §c%.1fs", t.seconds());
        return "  " + f + a.behavior.label.toLowerCase(Locale.ROOT);
    }

    private void record(Actor a) {
        npcs().currentScene = a.group;
        npcs().startRecording(a);
        game.setScreen(null);
        int cd = npcs().countdownSeconds;
        s.chat("§eRecording " + a.name + " for scene \"" + npcs().currentScene + "\"" + (cd > 0 ? " in " + cd + " s" : "") + ". Act! " + Keys.n(Keys.RECORD) + " = stop, " + Keys.n(Keys.RECORD_CANCEL) + " = cancel"
                + (npcs().othersPlayWhileRecording ? ", the scene's other actors play along" : "") + (npcs().recordAsNpc ? " §7(as " + a.name + ")" : ""));
    }

    private void flash(String msg) { status = msg; statusTicks = 90; }

    @Override public void tick() { super.tick(); if (statusTicks > 0) statusTicks--; }

    @Override public void scrolled(double amount) {
        if (page != P_SCENES) return;
        int old = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount), Math.max(0, listTotal - listRows)));
        if (scroll != old) game.relayout();
    }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xB0000000);
        g.text("NPC Hub", 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.NPC_HUB) + ": close";
        g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        NpcManager n = npcs();
        Actor a = npcPage() ? selected() : null;
        // what the pages below are about
        String ctx = a != null ? "§7" + clip(a.group, 12) + " > §f" + clip(a.name, 18) + (a.dead() ? " §8(dead)" : "")
                : page == P_TEAMS ? "§7Factions: who attacks whom" : "§7Scene: §f" + clip(n.currentScene, 22) + (n.scenePlaying(n.currentScene) ? " §a(playing)" : "");
        g.fill(4, 51, PW - 4, 61, 0x50000000);
        g.text(ctx, 6, 52, 0xFFFFFF, false);
        if (page == P_SCENES) {
            if (inGroup && visibleMembers().isEmpty()) {
                g.text("§7No NPCs here yet: + New NPC,", 6, listY + 2, 0xFFFFFF, false);
                g.text("§7or Record new to act one.", 6, listY + 12, 0xFFFFFF, false);
            }
            int shown = Math.min(listRows, listTotal);
            if (listTotal > listRows) g.text("§7wheel: scroll (" + (scroll + 1) + "-" + (scroll + shown) + " of " + listTotal + ")", 6, listY + shown * 14 + 1, 0xFFFFFF, false);
            if (copySource != null) g.text("§eCopy \"" + clip(copySource, 18) + "\" with its takes?", 6, listY + shown * 14 + (listTotal > listRows ? 11 : 3), 0xFFFFFF, false);
            String[] help = inGroup ? new String[]{"A group = one scene with its NPCs.", "Play / Timeline / Restore: the scene.",
                    "Restore: blocks the actors changed", "  back as before the scene.", "Click an NPC to set it up (2nd row).",
                    "On / Off: NPC in the world or not.", "Scn: plays in the scene (or only", "  keeps its AI).", "Rename: type a new name above."}
                    : new String[]{"Scenes: each group has its own NPCs", "and takes. Click one to open it.", "On / Off: all its NPCs in the", "  world or put away.",
                    "Copy: the same NPCs again, with", "  their takes or without."};
            helpBox(g, help, 24, 0xD0D0D0);
        }
        super.render(g, mx, my, partial);
        if (a != null) {
            if (page == P_TAKES) takeInfo(g, a, 63 + 54);
            else {
                String[] help = switch (page) {
                    case P_NPC -> new String[]{"Right click any NPC in the world", "to open it here.", "In world: OFF keeps it, not shown.",
                            "In scene: OFF = skips the scene,", "  keeps living by its AI.", "Duplicate: same look, AI, stats."};
                    case P_LOOK -> new String[]{"Hands / hat take what is in yours.", "Pose Edit: the pose editor.", "Name color: auto = red when", "  aggressive, white otherwise."};
                    case P_AI -> new String[]{a.name + ": " + a.faction.label,
                            a.faction == Actor.Faction.AGGRESSIVE ? "Attacks the player within range" : a.faction == Actor.Faction.NEUTRAL ? "Only reacts when hit" : n.playerHurtsFriendly ? "Friendly (you can still hurt it)" : "Can't be hurt by the player",
                            "Attack range: enemies it can see", "  this close are attacked.", "Help allies: attacks whoever attacks", "  an NPC of its faction nearby.",
                            "Assist me (Follow): attacks what", "  you attack, any faction."};
                    default -> new String[]{String.format(Locale.ROOT, "Health %.0f / %.0f%s", a.health, a.maxHealth, a.dead() ? " (dead)" : ""),
                            "Speed 1 = walks like you.", "Dmg 0 = from the item in its hand.", "Regen heals after 5 s without hits.",
                            "Immortal: nothing hurts it.", "Pushable: others push it aside.", "Death: respawn at home in 5 s,", "  or stay down until Revive."};
                };
                helpBox(g, help, 124, 0xE0E0E0);
            }
            if (a.hasTakes()) g.text("§7Scenes: " + String.join(", ", a.scenes()), PW + 6, 114, 0xFFFFFF, true);
            var skin = n.skins.get(a.skin);
            float spin = (float) ((System.nanoTime() / 1e9) % 12.0 * 30.0);   // 30 degrees a second, also while the world is paused
            g.fill(PW + 6, 24, PW + 66, 112, 0x60000000);
            if (skin != null) g.playerModel(n.skins.face(a.skin, a.emotion), PW + 36, 104, 36, spin, 0, 0, a.currentPose());
        } else if (page == P_PLAY) {
            int len = n.sceneLength();
            var cast = n.cast(n.currentScene);
            g.text(String.format(Locale.ROOT, "§eScene \"%s\": %d actor(s), %s", n.currentScene, cast.size(), time(len)), PW + 6, 24, 0xFFFFFF, true);
            String[] help = {"Play: everyone walks to their mark,", "plays, then goes back to their AI.",
                    "World after the scene:", " §brestored§r = what actors broke or", "   built comes back after each play.",
                    " §ekept§r = it stays; Restore world", "   puts it back later (also after", "   restarting the game).",
                    "To start: restore + actors on marks.", "Keys: " + Keys.n(Keys.SCENE_PLAY) + " play / stop."};
            int yy = helpBox(g, help, 40, 0xD0D0D0);
            if (!cast.isEmpty()) {
                StringBuilder sb = new StringBuilder("§7Cast: ");
                for (int i = 0; i < cast.size() && i < 6; i++) sb.append(i > 0 ? ", " : "").append(cast.get(i).name);
                g.text(sb.toString(), PW + 6, yy + 6, 0xFFFFFF, true);
            }
        } else if (page == P_REC) {
            String[] help = {"A take records everything you do:", "walking, looking, postures (Alt+1..4),", "sneaking, flying, cars, items in hand,",
                    "blocks, doors, items picked up and", "dropped, shots, reloads, throws, hits", "on NPCs and creatures, fist fights,",
                    "getting hurt, gestures and faces.", "+ world: also explosions, fire, water", "  you set off (48 blocks around you).",
                    "As the NPC: you start in its place", "  and skin, and come back after.",
                    "Scene's NPCs with AI: the other NPCs", "  of this scene (following you, guards,", "  drivers) get takes too. Other scenes'", "  NPCs are never recorded into it.",
                    "Keys: " + Keys.n(Keys.RECORD) + " record / stop, " + Keys.n(Keys.RECORD_CANCEL) + " cancel,",
                    Keys.n(Keys.GESTURE) + "+1..9 gesture, " + Keys.n(Keys.FACE) + "+1..7 face."};
            helpBox(g, help, 24, 0xD0D0D0);
        } else if (page == P_TEAMS) {
            int x = 4, y = 63, w = PW - 8, cw = (w - 56) / 3;
            g.text("§7attacks:", x, y + 2, 0xFFFFFF, false);
            Actor.Faction[] f = Actor.Faction.values();
            for (int j = 0; j < 3; j++) {
                String l = "§" + factionColor(f[j]) + new String[]{"Aggr", "Neut", "Frnd"}[j];
                g.text(l, x + 56 + j * (cw + 1) + cw / 2f - g.textWidth(l) / 2f, y + 2, 0xFFFFFF, false);
            }
            for (int i = 0; i < 3; i++) g.text("§" + factionColor(f[i]) + f[i].label, x, y + 16 + i * 18, 0xFFFFFF, false);
            String[] help = {"Aggressive: attacks the player", "  within its range (AI page).", "Neutral: hits back or runs", "  away after being hit.",
                    "Friendly: the player can't hurt", "  it (unless 'You can hurt Friendly').", "Rows: who attacks whom.", "Guards (AI page) defend you", "  or flee when you're attacked."};
            helpBox(g, help, 24, 0xD0D0D0);
        }
        if (statusTicks > 0) {
            int sw = g.textWidth(status);
            g.fill(PW + 3, height - 17, PW + 9 + sw, height - 4, 0x90000000);
            g.text(status, PW + 6, height - 14, 0xFFFFFF, true);
        }
    }

    /** What the active take holds (Takes page). */
    private void takeInfo(GuiRenderer g, Actor a, int y) {
        NpcManager n = npcs();
        var tr = a.track(n.currentScene);
        var tk = tr == null ? null : tr.take();
        if (tk == null) {
            g.text("§7No take in \"" + clip(n.currentScene, 14) + "\" yet:", 6, y, 0xFFFFFF, false);
            g.text("§7Record and act the part.", 6, y + 10, 0xFFFFFF, false);
            return;
        }
        Recording t = tk.rec;
        int shots = 0, hits = 0, items = 0;
        for (var it : t.items) { if (it.kind() == 3 || it.kind() == 2) shots++; else if (it.kind() == 6) hits++; else if (it.kind() <= 1) items++; }
        g.text(String.format(Locale.ROOT, "§7%.1f s, starts %s, speed %.2fx", tk.used() / 20f / tk.speed, time(tk.offset), tk.speed), 6, y, 0xFFFFFF, false);
        g.text(String.format(Locale.ROOT, "§7%d blocks, %d shots, %d hits", t.blocks.size(), shots, hits), 6, y + 10, 0xFFFFFF, false);
        g.text(String.format(Locale.ROOT, "§7%d items, %d chat lines", items, t.chat.size()), 6, y + 20, 0xFFFFFF, false);
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "." : s; }

    /** Help lines on a dark backing so they stay readable over the world. Returns the y below them. */
    private int helpBox(GuiRenderer g, String[] lines, int y, int color) {
        int w = 0, n = 0;
        for (String h : lines) if (!h.isEmpty()) { w = Math.max(w, g.textWidth(h)); n++; }
        if (n > 0) g.fill(PW + 3, y - 3, PW + 9 + w, y + lines.length * 10 - 1, 0x90000000);
        for (String h : lines) { g.text(h, PW + 6, y, color, true); y += 10; }
        return y;
    }

    @Override public void keyPressed(int key, int mods) {
        if (Keys.NPC_HUB.matches(key)) { onClose(); return; }
        boolean typing = (nameField != null && nameField.focused) || (groupField != null && groupField.focused);
        if (!typing && Keys.RECORD.matches(key)) { Actor a = selected(); commitFields(-1, -1); game.setScreen(null); s.recordKey(a != null && a.group.equalsIgnoreCase(npcs().currentScene) ? a : null); return; }
        if (!typing && Keys.SCENE_PLAY.matches(key)) { commitFields(-1, -1); game.setScreen(null); s.playSceneKey(); return; }
        if (key == GLFW_KEY_ENTER && nameField != null && nameField.focused) { nameField.onEnter.run(); return; }
        if (key == GLFW_KEY_ENTER && groupField != null && groupField.focused) { groupField.onEnter.run(); return; }
        super.keyPressed(key, mods);
    }

    /** A name typed in the group / NPC name field counts when you click elsewhere or close the hub (not only with Enter). */
    private void commitFields(int mx, int my) {
        if (groupField != null && groupField.focused && !groupField.contains(mx, my)) groupField.onEnter.run();
        if (nameField != null && nameField.focused && !nameField.contains(mx, my)) nameField.onEnter.run();
    }

    @Override public void mouseClicked(int mx, int my, int button) {
        commitFields(mx, my);
        super.mouseClicked(mx, my, button);
    }

    @Override public void onClose() {
        commitFields(-1, -1);
        npcs().save();
        game.setScreen(null);
    }
}
