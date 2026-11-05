package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.api.DataTag;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.SceneBlock;
import derpcraft.render.GuiRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/** Settings of a Scene Block: which group (scene), which of its NPCs, how it is triggered, once or every time. */
public final class SceneBlockScreen extends Screen {
    private static final int PER_PAGE = 6;
    private final Session s;
    private final int bx, by, bz;
    private Widget.TextField name;
    private int actorPage;
    private int top;

    public SceneBlockScreen(DerpCraft g, Session s, int x, int y, int z) { super(g); this.s = s; bx = x; by = y; bz = z; }

    private DataTag d() { return s.blockAt(bx, by, bz).data(); }

    private String group() { return d().getString("scene", s.npcs.currentScene); }

    /** NPCs of the chosen group that have a take for it. */
    private List<Actor> cast() {
        List<Actor> l = new ArrayList<>();
        for (Actor a : s.npcs.members(group())) if (a.take(group()) != null) l.add(a);
        return l;
    }

    @Override public void init() {
        DataTag d = d();
        if (!d.has("scene")) d.put("scene", s.npcs.currentScene);
        int cx = width / 2, w = 220, x = cx - w / 2, y = top = Math.max(4, height / 2 - 112) + 22;
        name = add(new Widget.TextField(x + 22, y + 2, w - 44, 12));
        name.text = group();
        name.maxLength = 24;
        name.onChange = () -> { DataTag t = d(); t.put("scene", name.text.trim()); t.put("done", false); t.remove("actors"); };
        add(new Widget.Button(x, y, 20, 16, "<", () -> cycle(-1)));
        add(new Widget.Button(x + w - 20, y, 20, 16, ">", () -> cycle(1)));
        y += 30;
        // which NPCs: all, or a selection
        List<Actor> cast = cast();
        add(new Widget.Button(x, y, 60, 14, () -> SceneBlock.actors(d()) == null ? "§aAll" : "§7All", () -> { SceneBlock.setActors(d(), null); d().put("done", false); }));
        int pages = Math.max(1, (cast.size() + PER_PAGE - 1) / PER_PAGE);
        actorPage = Math.min(actorPage, pages - 1);
        if (pages > 1) {
            add(new Widget.Button(x + w - 40, y, 19, 14, "<", () -> { actorPage = Math.max(0, actorPage - 1); game.relayout(); }));
            add(new Widget.Button(x + w - 20, y, 20, 14, ">", () -> { actorPage = Math.min(pages - 1, actorPage + 1); game.relayout(); }));
        }
        y += 16;
        int cw = (w - 2) / 2;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = actorPage * PER_PAGE + i;
            if (idx >= cast.size()) break;
            Actor a = cast.get(idx);
            add(new Widget.Button(x + (i % 2) * (cw + 2), y + (i / 2) * 15, cw, 14, () -> {
                List<Integer> sel = SceneBlock.actors(d());
                boolean on = sel == null || sel.contains(a.id);
                String n = a.name.length() > 14 ? a.name.substring(0, 13) + "." : a.name;
                return (on ? "§a[x] " : "§8[ ] ") + n + (a.enabled && a.sceneEnabled ? "" : " §8off");
            }, () -> toggle(a)));
        }
        y += 3 * 15 + 6;
        add(new Widget.Button(x, y, w, 18, () -> "Trigger: " + SceneBlock.TRIGGER_LABELS[triggerIndex()], () -> {
            DataTag t = d();
            t.put("trigger", SceneBlock.TRIGGERS[(triggerIndex() + 1) % SceneBlock.TRIGGERS.length]);
            t.put("active", false);
            game.relayout();
        }));
        y += 20;
        boolean npcTrigger = "npc".equals(d().getString("trigger", "click"));
        int rw = npcTrigger ? w / 2 - 1 : w;
        Widget.Slider r = add(new Widget.Slider(x, y, rw, 18, "Radius", 1, 32, 1, () -> d().getFloat("radius", 6), v -> d().put("radius", (float) Math.round(v)),
                v -> Math.round(v) + " blocks"));
        r.defaultValue = 6;
        if (npcTrigger) add(new Widget.Button(x + w / 2 + 1, y, w / 2 - 1, 18, () -> "Who: " + SceneBlock.whoLabel(s, d()), this::cycleWho));
        y += 20;
        int tw3 = (w - 4) / 3;
        add(new Widget.Button(x, y, tw3, 18, () -> d().getBool("repeat", true) ? "Every time" : "Only once", () -> d().put("repeat", !d().getBool("repeat", true))));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 18, () -> d().getBool("done") && !d().getBool("repeat", true) ? "Re-arm" : "Armed", () -> d().put("done", false)));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 18, () -> d().getBool("wake", true) ? "Wakes group" : "§7Group as is", () -> d().put("wake", !d().getBool("wake", true))));
        y += 22;
        add(new Widget.Button(x, y, w / 2 - 1, 18, "Play now", () -> { DataTag t = d(); t.put("done", false); onClose(); SceneBlock.fire(s, t, true); }));
        add(new Widget.Button(x + w / 2 + 1, y, w / 2 - 1, 18, "Done", this::onClose));
    }

    /** NPC nearby: the scene's actors -> any NPC -> each NPC by name. */
    private void cycleWho() {
        List<String> opts = new ArrayList<>(List.of("cast", "any"));
        for (Actor a : s.npcs.all()) opts.add(String.valueOf(a.id));
        int i = opts.indexOf(d().getString("who", "cast"));
        d().put("who", opts.get((i + 1) % opts.size()));
        d().put("active", false);
    }

    private void toggle(Actor a) {
        List<Actor> cast = cast();
        List<Integer> sel = SceneBlock.actors(d());
        List<Integer> ids = new ArrayList<>();
        if (sel == null) for (Actor c : cast) ids.add(c.id);
        else ids.addAll(sel);
        if (ids.contains(a.id)) ids.remove((Integer) a.id); else ids.add(a.id);
        if (ids.size() >= cast.size()) ids = null;          // everyone = "All"
        else if (ids.isEmpty()) { ids = new ArrayList<>(); ids.add(a.id); }   // keep at least one
        SceneBlock.setActors(d(), ids);
        d().put("done", false);
    }

    private int triggerIndex() {
        String t = d().getString("trigger", "click");
        for (int i = 0; i < SceneBlock.TRIGGERS.length; i++) if (SceneBlock.TRIGGERS[i].equals(t)) return i;
        return 0;
    }

    private void cycle(int dir) {
        List<String> names = s.npcs.sceneNames();
        int i = -1;
        for (int k = 0; k < names.size(); k++) if (names.get(k).equalsIgnoreCase(name.text.trim())) i = k;
        String g = names.get(Math.floorMod(i + dir, names.size()));
        DataTag t = d();
        t.put("scene", g);
        t.put("done", false);
        t.remove("actors");
        actorPage = 0;
        game.relayout();
    }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
        title(g, "Scene Block", top - 20);
        int cx = width / 2, x = cx - 110;
        g.text("§7Group (scene):", x, top - 10, 0xFFFFFF, false);
        String sc = group();
        List<Actor> cast = cast();
        g.text(cast.isEmpty() ? "§7Actors: none recorded yet" : "§7Actors that play:", x, top + 20, 0xFFFFFF, false);
        String info;
        if (cast.isEmpty()) info = "§cNo takes in \"" + sc + "\" yet (NPC Hub, " + derpcraft.game.Keys.n(derpcraft.game.Keys.NPC_HUB) + ")";
        else {
            List<Integer> sel = SceneBlock.actors(d());
            int n = sel == null ? cast.size() : (int) cast.stream().filter(a -> sel.contains(a.id)).count();
            info = String.format(Locale.ROOT, "§a%d of %d actor(s), %.1f s%s", n, cast.size(), s.npcs.sceneLength(sc) / 20f, s.npcs.groupEnabled(sc) ? "" : " §7(group off)");
        }
        g.centered(info, cx, height - 24, 0xFFFFFF);
        g.centered("§8Sneak + right click the block to open this again", cx, height - 12, 0xFFFFFF);
        super.render(g, mx, my, partial);
    }

    @Override public void keyPressed(int key, int mods) {
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) { onClose(); return; }
        super.keyPressed(key, mods);
    }
}
