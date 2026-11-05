package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.gun.GunController;
import derpcraft.gun.GunType;
import derpcraft.gun.Guns;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.render.GuiRenderer;

import java.util.*;
import java.util.function.Predicate;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Gun editor (Alt + Home, /guns edit): every number of the gun in hand on sliders, the model's points with a marker on
 * the gun, and test buttons. The world keeps running behind it so the gun animates while you tune it; every change is
 * written to the gun's json at once (a moment after the last change), so nothing needs a restart.
 *
 * <p>Laid out like the NPC Hub: a panel on the left, pages on top, the world on the right. Each page (and each pose of
 * the View page) is split into small tabs, so a tab fits on the screen without scrolling. Sliders: drag (Shift = fine),
 * right click = default, Ctrl + click = type a value, Ctrl + wheel = one step (Shift: ten); - / + one step (Shift: ten).
 * Changed values are tinted. Undo / redo (Ctrl+Z / Ctrl+Y) for every change; a pose can be copied to another (Ctrl+C /
 * Ctrl+V); the reload and bolt poses can be held at any moment ("Hold at"), and the animations played slower.
 */
public final class GunEditor extends Screen {
    private final Session s;
    private static int page = 0, scroll = 0, pointIndex = 0;
    /** View page: 0 = general (sizes, 3rd person), 1.. = the poses hip, aim, aim 2, sprint, reload, bolt, then melee. Arms page: 0 general, 1 hands. */
    private static int viewSection = 1, armsSection = 0;
    private static final String[] VIEW_SECTIONS = {"General", "Hip", "Aim", "Aim 2", "Sprint", "Reload", "Bolt", "Melee"};
    private static final String[] ARMS_SECTIONS = {"General", "Hands"};
    /** The pose (GunType.POSES index) of each view section (-1 = general, MELEE = the melee section: its own kinds and poses). */
    private static final int MELEE = -2;
    private static final int[] SECTION_POSE = {-1, 0, 1, GunType.AIM2, 2, 3, 4, MELEE};
    /**
     * Melee section: the kind (0..2 = jab, swing, smash; 3 = setup: reach, knockback, combo), its part (0 = wind-up pose,
     * 1 = hit pose, 2 = times and damage) and where in the strike the pose is held (0..1 of the whole strike).
     */
    private static int meleeKind = 0, meleePart = 1;
    private static float meleeAt = -1;
    private static final String[] MELEE_PARTS = {"Wind-up", "Hit", "Timing"};
    /** The tab picked in each page / section (key: page + section). */
    private static final Map<String, Integer> TABS = new HashMap<>();
    /** Where the held reload / bolt pose stops (0..1 of that part) and where the slide is shown on the Points page. */
    private static float reloadAt = 0.2f, boltAt = 0.3f, slideTest = 1;
    /** A copied pose: suffix of the json key ("Position", "GunRotation"...) + "#" + index -> value. */
    private static Map<String, Float> clipboard;
    private static String clipboardFrom;
    /** Undo / redo per gun: the json text before each change. */
    private static final Map<String, Deque<String>> UNDO = new HashMap<>(), REDO = new HashMap<>();
    private static final int UNDO_MAX = 200;

    /** Pose tabs: the json key ends of each ("All" = the whole rig, "Gun" = the gun with the hands on it, ...). */
    private static final String[] POSE_TABS = {"All", "Gun", "Arms", "R arm", "L arm"};
    private static final String[][] POSE_TAB_KEYS = {{"Position", "Rotation"}, {"GunPosition", "GunRotation"}, {"ArmsPosition"},
            {"RightArmPosition", "RightArmRotation"}, {"LeftArmPosition", "LeftArmRotation"}};

    private static final int PW = 196, ROW = 16;
    private int rows = 8;
    private String status = "";
    private int statusTicks;
    private long dirtySince;
    private boolean dirty;
    private String committed;          // the json as last saved (what an undo goes back to)
    private Widget.TextField nameField, valueField;
    private Widget.Slider valueSlider;
    private final List<Widget.Slider> sliders = new ArrayList<>();
    private int totalRows;
    private String shownGun = "";

    public GunEditor(DerpCraft g, Session s) {
        super(g);
        this.s = s;
        pausesGame = false;              // the gun keeps animating while it is tuned
    }

    @Override public boolean recorded() { return false; }

    /** The gun whose magazine is in the hand (Alt + Home then tunes where that magazine sits in the belt pouches), or null. */
    private GunType magGun() {
        ItemStack h = s.heldStack();
        GunType t = h == null ? null : Guns.ofMag(h.item);
        return t != null && t.model() != null && t.mag != null ? t : null;       // (the model loads on first use: it has to be asked first)
    }

    private GunType gun() {
        GunType m = magGun();
        if (m != null) return m;
        GunType t = Guns.of(s.heldStack());
        if (t == null || t.model() == null) t = Guns.of(s.backStack());     // the gun on the back (Left Alt + F): its back place can be tuned in third person
        return t != null && t.model() != null ? t : null;
    }

    // ------------------------------------------------------------------ layout
    @Override public void init() {
        nameField = null;
        if (valueField != null && valueSlider == null) valueField = null;
        sliders.clear();
        int x = 4, w = PW - 8, y = 16;
        GunType t = gun();
        String now = t == null ? "" : t.name + "@" + Guns.version();
        if (t != null && (committed == null || !shownGun.startsWith(t.name + "@"))) committed = t.toJson();
        shownGun = now;
        GunController gc = s.guns;
        gc.editorMarker = null;
        gc.previewPose = null;
        gc.previewAt = -1;
        gc.previewBolt = -1;
        s.pouchPreview = null;
        if (t == null) { initNoGun(x, y, w); return; }
        if (magGun() != null) { initMag(x, y, w, t); return; }
        String[] pages = GunType.PAGES;
        int tw = (w - 2 * (pages.length - 1)) / pages.length;
        for (int i = 0; i < pages.length; i++) {
            final int p = i;
            add(new Widget.Button(x + i * (tw + 2), y, i == pages.length - 1 ? w - i * (tw + 2) : tw, ROW, () -> (page == p ? "§e" : "") + (pages[p].length() > 6 ? pages[p].substring(0, 5) + "." : pages[p]), () -> { page = p; scroll = 0; game.relayout(); }));
        }
        y += ROW + 2;
        int bottom = height - 4 * (ROW + 2) - 14;            // the test buttons: 4 rows
        List<Runnable> rowsToAdd = new ArrayList<>();
        int[] yy = {y};
        String pg = GunType.PAGES[page];
        String tabKey = pg;
        GunType def = GunType.defaults();

        if (pg.equals("View") || pg.equals("Arms")) {
            boolean view = pg.equals("View");
            String[] secs = view ? VIEW_SECTIONS : ARMS_SECTIONS;
            int n = secs.length, perRow = 4;
            for (int i = 0; i < n; i++) {
                final int k = i;
                int row = i / perRow, col = i % perRow, per = Math.min(perRow, n - row * perRow), sw = (w - 2 * (per - 1)) / per;
                add(new Widget.Button(x + col * (sw + 2), y + row * (ROW + 2), col == per - 1 ? w - col * (sw + 2) : sw, ROW,
                        () -> ((view ? viewSection : armsSection) == k ? "§a" : "") + secs[k] + (view && k > 0 && sectionChanged(gun(), SECTION_POSE[k]) ? "§7*" : ""),
                        () -> { if (view) viewSection = k; else armsSection = k; scroll = 0; game.relayout(); }));
            }
            y += ((n + perRow - 1) / perRow) * (ROW + 2);
            tabKey = pg + (view ? viewSection : armsSection);
        }
        final String tk = tabKey;

        if (pg.equals("View") && SECTION_POSE[viewSection] == MELEE) {
            // ---- melee: the kind (Jab / Swing / Smash / Setup), its part (Wind-up / Hit pose, Timing) and a test button
            y = meleeHeader(x, y, w, t);
            yy[0] = y;
            if (meleeKind == 3) {
                gc.previewPose = null;
                rowsToAdd.add(() -> meleePlayRow(x, yy[0], w));
                for (GunType.Param p : GunType.PARAMS) if (p.page().equals("Melee")) paramRow(rowsToAdd, yy, x, w, p, def, p.label());
            } else if (meleePart == 2) {
                gc.previewPose = "melee:" + meleeKind;
                gc.previewAt = meleeHold(t);
                rowsToAdd.add(() -> meleeAtSlider(x, yy[0], w));
                for (GunType.Param p : GunType.PARAMS) if (p.page().equals(GunType.meleePage(meleeKind))) paramRow(rowsToAdd, yy, x, w, p, def, p.label());
            } else {
                y = poseSection(rowsToAdd, yy, x, y, w, tk, GunType.meleePose(meleeKind, meleePart == 1), def);
            }
        } else if (pg.equals("View") && viewSection > 0) {
            y = poseSection(rowsToAdd, yy, x, y, w, tk, SECTION_POSE[viewSection], def);
        } else if (pg.equals("Points")) {
            pointsPage(rowsToAdd, yy, x, w, t);
        } else if (pg.equals("Arms") && armsSection == 1) {
            // ---- the hands: which hand changes the magazine / pulls the bolt; the touch points
            handChoice(rowsToAdd, yy, x, w, false);
            handChoice(rowsToAdd, yy, x, w, true);
            String[] hands = {"rag", "lag", "ram", "las"}, names = {"Grip", "Guard", "Mag", "Bolt"};
            int tab = tab(tk, hands.length);
            rowsToAdd.add(() -> tabs(x, yy[0], w, tk, names, i -> gun() != null && gun().pointOverrides.containsKey(hands[i])));
            String h = hands[tab];
            gc.editorMarker = h;
            if (h.equals("las")) { gc.previewPose = "bolt"; gc.previewAt = boltAt; }
            if (h.equals("ram")) { gc.previewPose = "reload"; gc.previewAt = t.pistol ? 0.7f : reloadAt; }   // a pistol's hand holds the new one
            rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "§e" + pointName(h) + " §7" + h + (gun() != null && gun().pointOverrides.containsKey(h) ? " (set) - click: auto" : " (auto)"), () -> {
                GunType g2 = gun();
                if (g2 != null && g2.pointOverrides.containsKey(h)) { begin(); g2.pointOverrides.remove(h); g2.applyPoints(); changed(); }
            })));
            pointRows(rowsToAdd, yy, x, w, t, h);
            // the hand's turn there (yaw, pitch, roll around the palm): grip / guard = that arm's own turn, magazine / bolt =
            // the turn of whichever hand holds it, only while it holds it
            String turnKey = switch (h) { case "rag" -> "armRightRotation"; case "lag" -> "armLeftRotation"; case "ram" -> "magHandRotation"; default -> "boltHandRotation"; };
            for (GunType.Param p : GunType.PARAMS) if (p.key().equals(turnKey)) paramRow(rowsToAdd, yy, x, w, p, def, "Hand turn " + p.label().substring(p.label().lastIndexOf(' ') + 1));
        } else {
            // ---- pages of plain numbers, split into tabs
            String[] tabNames = pageTabs(pg);
            int tab = tabNames == null ? 0 : tab(tk, tabNames.length);
            if (tabNames != null) {
                y = tabs(x, y, w, tk, tabNames, i -> pageTabChanged(gun(), pg, tabNames[i]));
                yy[0] = y;
            }
            String tabName = tabNames == null ? null : tabNames[tab];
            if (pg.equals("FX")) gc.editorMarker = "flash";        // the yellow cube: where the flash (and smoke, tracers) start
            if (pg.equals("Arms")) { handChoice(rowsToAdd, yy, x, w, false); handChoice(rowsToAdd, yy, x, w, true); }
            if (pg.equals("Stats")) {
                rowsToAdd.add(() -> {
                    nameField = add(new Widget.TextField(x + 40, yy[0], w - 40, ROW));
                    nameField.text = t.displayName;
                    nameField.onEnter = () -> { GunType g2 = gun(); if (g2 != null && !nameField.text.isBlank() && !nameField.text.trim().equals(g2.displayName)) { begin(); g2.displayName = nameField.text.trim(); changed(); } };
                });
                rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "Fire mode: " + (gun() != null && gun().auto ? "§aauto" : "§esemi"), () -> { GunType g2 = gun(); if (g2 != null) { begin(); g2.auto = !g2.auto; changed(); } })));
            }
            if (pg.equals("Anim") && "Bolt & style".equals(tabName)) {
                rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "Reload style: " + (gun() != null && gun().pistol ? "§apistol" : "§erifle"),
                        () -> { GunType g2 = gun(); if (g2 != null) { begin(); g2.pistol = !g2.pistol; changed(); } })));
                rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "Slide stays back when empty: " + (gun() != null && gun().slideLock ? "§aon" : "§7off"),
                        () -> { GunType g2 = gun(); if (g2 != null) { begin(); g2.slideLock = !g2.slideLock; changed(); } })));
                rowsToAdd.add(() -> {
                    int bw = (w - 2) / 2;
                    add(new Widget.Button(x, yy[0], bw, ROW, "Rifle poses", () -> preset(false)));
                    add(new Widget.Button(x + bw + 2, yy[0], w - bw - 2, ROW, "Pistol poses", () -> preset(true)));
                });
            }
            for (GunType.Param p : GunType.PARAMS) {
                if (!p.page().equals(pg)) continue;
                if (tabName != null && !inPageTab(pg, tabName, p.key())) continue;
                paramRow(rowsToAdd, yy, x, w, p, def, p.label());
            }
        }
        rows = Math.max(3, (bottom - yy[0] - 4) / (ROW + 2));
        scroll = Math.max(0, Math.min(scroll, rowsToAdd.size() - rows));
        for (int i = scroll; i < rowsToAdd.size() && i < scroll + rows; i++) { rowsToAdd.get(i).run(); yy[0] += ROW + 2; }
        totalRows = rowsToAdd.size();
        testButtons(x, w);
        if (valueField != null) widgets.add(valueField);       // a value being typed stays over its slider
    }

    /**
     * A magazine in the hand: only where this kind of magazine sits in the belt pouches (move, turn, size). It shows in all
     * six pouches in the third person view from the front; the same place is used for the pouches of every actor.
     */
    private void initMag(int x, int y, int w, GunType t) {
        Item mi = Guns.magItem(t);
        s.pouchPreview = mi;
        if (s.view == 0) s.view = 2;                                // the belt is seen from the front
        GunType def = GunType.defaults();
        List<Runnable> rowsToAdd = new ArrayList<>();
        int[] yy = {y + 2};
        for (GunType.Param p : GunType.PARAMS) if (p.page().equals("Pouch")) paramRow(rowsToAdd, yy, x, w, p, def, p.label());
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "Reset the pouch place", () -> {
            GunType g2 = gun();
            if (g2 == null) return;
            begin();
            for (GunType.Param p : GunType.PARAMS) if (p.page().equals("Pouch")) p.apply(g2, p.value(def));
            changed();
            game.relayout();
        })));
        int bottom = height - 3 * (ROW + 2) - 14;
        rows = Math.max(3, (bottom - yy[0] - 4) / (ROW + 2));
        scroll = Math.max(0, Math.min(scroll, rowsToAdd.size() - rows));
        for (int i = scroll; i < rowsToAdd.size() && i < scroll + rows; i++) { rowsToAdd.get(i).run(); yy[0] += ROW + 2; }
        totalRows = rowsToAdd.size();
        int by = height - 3 * (ROW + 2) - 4, bw2 = (w - 2) / 2;
        add(new Widget.Button(x, by, bw2, ROW, () -> s.view == 0 ? "1st pers." : s.view == 1 ? "3rd pers." : "3rd front", () -> s.cycleView()));
        add(new Widget.Button(x + bw2 + 2, by, w - bw2 - 2, ROW, "Close", this::onClose));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw2, ROW, () -> (undoStack().isEmpty() && !dirty ? "§7" : "") + "Undo", this::undo));
        add(new Widget.Button(x + bw2 + 2, by, w - bw2 - 2, ROW, () -> (redoStack().isEmpty() ? "§7" : "") + "Redo", this::redo));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw2, ROW, "Reload files", () -> { flush(); say(Guns.reload()); game.relayout(); }));
        add(new Widget.Button(x + bw2 + 2, by, w - bw2 - 2, ROW, "Save", this::save));
    }

    /** The test buttons at the bottom (4 rows). */
    private void testButtons(int x, int w) {
        GunController gc = s.guns;
        int by = height - 4 * (ROW + 2) - 4, bw4 = (w - 6) / 4;
        add(new Widget.Button(x, by, bw4, ROW, "Shoot", () -> gc.previewFire(1)));
        add(new Widget.Button(x + bw4 + 2, by, bw4, ROW, "Burst", () -> gc.previewFire(8)));
        add(new Widget.Button(x + 2 * (bw4 + 2), by, bw4, ROW, "Reload", () -> gc.previewReload(false)));
        add(new Widget.Button(x + 3 * (bw4 + 2), by, w - 3 * (bw4 + 2), ROW, "Empty", () -> gc.previewReload(true)));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw4, ROW, () -> (gc.previewAim ? "§a" : "") + "Aim", () -> gc.previewAim = !gc.previewAim));
        add(new Widget.Button(x + bw4 + 2, by, bw4, ROW, () -> (gc.previewWalk ? "§a" : "") + "Walk", () -> { gc.previewWalk = !gc.previewWalk; if (gc.previewWalk) gc.previewSprint = false; }));
        add(new Widget.Button(x + 2 * (bw4 + 2), by, bw4, ROW, () -> (gc.previewSprint ? "§a" : "") + "Run", () -> { gc.previewSprint = !gc.previewSprint; if (gc.previewSprint) gc.previewWalk = false; }));
        add(new Widget.Button(x + 3 * (bw4 + 2), by, w - 3 * (bw4 + 2), ROW, "Equip", gc::previewEquip));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw4, ROW, () -> s.view == 0 ? "1st pers." : s.view == 1 ? "3rd pers." : "3rd front", () -> s.cycleView()));
        add(new Widget.Button(x + bw4 + 2, by, bw4, ROW, () -> (gc.previewSpeed < 1 ? "§b" : "") + "Speed " + speedName(gc.previewSpeed), this::cycleSpeed));
        add(new Widget.Button(x + 2 * (bw4 + 2), by, bw4, ROW, () -> (undoStack().isEmpty() && !dirty ? "§7" : "") + "Undo", this::undo));
        add(new Widget.Button(x + 3 * (bw4 + 2), by, w - 3 * (bw4 + 2), ROW, () -> (redoStack().isEmpty() ? "§7" : "") + "Redo", this::redo));
        by += ROW + 2;
        int bw2 = (w - 2) / 2;
        add(new Widget.Button(x, by, bw2, ROW, "Reload files", () -> { flush(); say(Guns.reload()); game.relayout(); }));
        add(new Widget.Button(x + bw2 + 2, by, w - bw2 - 2, ROW, "Save", this::save));
    }

    /**
     * One pose: its tabs (All / Gun / Arms / R arm / L arm), copy / paste / reset, the extras of the reload, bolt and melee
     * poses, and the sliders of the tab. Returns the y below the fixed rows.
     */
    private int poseSection(List<Runnable> rowsToAdd, int[] yy, int x, int y, int w, String tk, int pose, GunType def) {
        GunController gc = s.guns;
        String pp = GunType.posePage(pose), pn = GunType.POSES[pose];
        boolean melee = GunType.meleePose(pose);
        if (melee) { gc.previewPose = "melee:" + meleeKind; gc.previewAt = meleeHold(gun()); }
        else gc.previewPose = pn;
        if (pose == GunType.AIM2) gc.editorMarker = "sight2";
        if (pose == 3) gc.previewAt = reloadAt;
        if (pose == 4) gc.previewAt = boltAt;
        int tab = tab(tk, POSE_TABS.length);
        y = tabs(x, y, w, tk, POSE_TABS, i -> groupChanged(gun(), pp, pn, POSE_TAB_KEYS[i]));
        // tools: copy / paste the whole pose, reset this tab
        int bw = (w - 4) / 3;
        add(new Widget.Button(x, y, bw, ROW, "Copy pose", () -> copyPose(pose)));
        add(new Widget.Button(x + bw + 2, y, bw, ROW, () -> clipboard == null ? "§7Paste" : "Paste", () -> pastePose(pose)));
        add(new Widget.Button(x + 2 * (bw + 2), y, w - 2 * (bw + 2), ROW, "Reset tab", () -> resetGroup(pp, pn, POSE_TAB_KEYS[tab])));
        y += ROW + 2;
        yy[0] = y;
        // the reload and bolt poses: where they are held, and which hand works
        if (pose == 3 || pose == 4) {
            boolean bolt = pose == 4;
            rowsToAdd.add(() -> slider(x, yy[0], w, bolt ? "Hold at (bolt)" : "Hold at (reload)", 0, 1, 0.01f,
                    () -> bolt ? boltAt : reloadAt, v -> { if (bolt) boltAt = (float) v; else reloadAt = (float) v; s.guns.previewAt = (float) v; },
                    bolt ? 0.3 : 0.2, null));
            handChoice(rowsToAdd, yy, x, w, bolt);
        }
        // a melee pose: where in the strike it is held (the wind-up's end / the hit at first)
        if (melee) rowsToAdd.add(() -> meleeAtSlider(x, yy[0], w));
        for (GunType.Param p : GunType.PARAMS) {
            if (!p.page().equals(pp)) continue;
            String suf = suffix(p.key(), pn);
            boolean inTab = Arrays.asList(POSE_TAB_KEYS[tab]).contains(suf), inAny = false;
            for (String[] ks : POSE_TAB_KEYS) if (Arrays.asList(ks).contains(suf)) inAny = true;
            if (inTab || !inAny) paramRow(rowsToAdd, yy, x, w, p, def, inTab ? shortLabel(p.label()) : p.label());
        }
        return y;
    }

    /** The melee section's fixed rows: the kinds (and Setup), the parts of a kind with a Play button. Returns the y below. */
    private int meleeHeader(int x, int y, int w, GunType t) {
        String[] kinds = {GunType.MELEE_NAMES[0], GunType.MELEE_NAMES[1], GunType.MELEE_NAMES[2], "Setup"};
        int n = kinds.length, sw = (w - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            final int k = i;
            add(new Widget.Button(x + i * (sw + 2), y, i == n - 1 ? w - i * (sw + 2) : sw, ROW,
                    () -> (meleeKind == k ? "§a" : "") + kinds[k] + (meleeChanged(gun(), k) ? "§7*" : ""),
                    () -> { meleeKind = k; meleeAt = -1; scroll = 0; game.relayout(); }));
        }
        y += ROW + 2;
        if (meleeKind < 3) {
            int pw = (w - 2 * 3) * 3 / 10, playW = w - 3 * (pw + 2);
            for (int i = 0; i < 3; i++) {
                final int k = i;
                add(new Widget.Button(x + i * (pw + 2), y, pw, ROW,
                        () -> (meleePart == k ? "§a" : "") + MELEE_PARTS[k] + (partChanged(gun(), meleeKind, k) ? "§7*" : ""),
                        () -> { meleePart = k; meleeAt = -1; scroll = 0; game.relayout(); }));
            }
            add(new Widget.Button(x + 3 * (pw + 2), y, playW, ROW, "§ePlay", () -> s.guns.previewMelee(meleeKind, false)));
            y += ROW + 2;
        }
        return y;
    }

    /** Setup: a test button for each kind and the combo (all three one after another). */
    private void meleePlayRow(int x, int y, int w) {
        int n = 4, bw = (w - 2 * (n - 1)) / n;
        for (int i = 0; i < 3; i++) {
            final int k = i;
            add(new Widget.Button(x + i * (bw + 2), y, bw, ROW, "§e" + GunType.MELEE_NAMES[k], () -> s.guns.previewMelee(k, false)));
        }
        add(new Widget.Button(x + 3 * (bw + 2), y, w - 3 * (bw + 2), ROW, "§eCombo", () -> s.guns.previewMelee(0, true)));
    }

    /** Where the strike is held (0..1 of it): at first the end of the wind-up (Wind-up) or the impact (Hit, Timing). */
    private float meleeHold(GunType t) {
        if (meleeAt >= 0 || t == null || meleeKind > 2) return Math.max(0, meleeAt);
        float len = derpcraft.gun.GunAnimator.meleeLength(t, meleeKind);
        float at = meleePart == 0 ? t.meleeTime[meleeKind][0] : derpcraft.gun.GunAnimator.meleeImpact(t, meleeKind);
        return len <= 0 ? 0 : at / len;
    }

    private void meleeAtSlider(int x, int y, int w) {
        slider(x, y, w, "Hold at (strike)", 0, 1, 0.01f, () -> meleeHold(gun()), v -> { meleeAt = (float) v; s.guns.previewAt = (float) v; },
                Double.NaN, null);
    }

    private static boolean meleeChanged(GunType t, int kind) {
        if (t == null) return false;
        if (kind == 3) return pageChanged(t, "Melee");
        for (int part = 0; part < 3; part++) if (partChanged(t, kind, part)) return true;
        return false;
    }

    private static boolean partChanged(GunType t, int kind, int part) {
        if (t == null || kind > 2) return false;
        return part == 2 ? pageChanged(t, GunType.meleePage(kind)) : pageChanged(t, GunType.posePage(GunType.meleePose(kind, part == 1)));
    }

    private static boolean pageChanged(GunType t, String page) {
        for (GunType.Param p : GunType.PARAMS) if (p.page().equals(page) && differs(p, t, dfl(p, t))) return true;
        return false;
    }

    /** A view section's '*': a pose differs from the defaults, or (melee) any of its numbers. */
    private static boolean sectionChanged(GunType t, int pose) {
        if (pose == MELEE) { for (int k = 0; k < 4; k++) if (meleeChanged(t, k)) return true; return false; }
        return poseChanged(t, pose);
    }

    /** The default of a number for this gun: melee numbers follow the gun's style (a pistol whip for pistols). */
    private static GunType dfl(GunType.Param p, GunType t) { return p.melee() ? GunType.defaultsFor(t) : GunType.defaults(); }

    private static final float[] SPEEDS = {1, 0.5f, 0.25f, 0.1f};

    private static String speedName(float v) { return v >= 1 ? "1x" : v >= 0.5f ? "1/2" : v >= 0.25f ? "1/4" : "1/10"; }

    private void cycleSpeed() {
        float v = s.guns.previewSpeed;
        int i = 0;
        while (i < SPEEDS.length && SPEEDS[i] > v + 1e-4f) i++;
        s.guns.previewSpeed = SPEEDS[(i + 1) % SPEEDS.length];
        say("Animation speed " + speedName(s.guns.previewSpeed));
    }

    // ------------------------------------------------------------------ tabs
    private static int tab(String key, int n) {
        int v = TABS.getOrDefault(key, 0);
        return v >= 0 && v < n ? v : 0;
    }

    /** A row of tab buttons (the picked one green; '*' = something in it is not the default). Returns the y below it. */
    private int tabs(int x, int y, int w, String key, String[] names, Predicate<Integer> changed) {
        int n = names.length, sw = (w - 2 * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            final int k = i;
            add(new Widget.Button(x + i * (sw + 2), y, i == n - 1 ? w - i * (sw + 2) : sw, ROW,
                    () -> (tab(key, n) == k ? "§a" : "") + names[k] + (changed.test(k) ? "§7*" : ""),
                    () -> { TABS.put(key, k); scroll = 0; game.relayout(); }));
        }
        return y + ROW + 2;
    }

    /**
     * A row "Mag hand: [Left] [Right]" (or the bolt's): which hand changes the magazine / pulls the bolt; the one in use is
     * green. Shown on Arms -> General, Arms -> Hands (Mag / Bolt tabs) and in the Reload / Bolt poses.
     */
    private void handChoice(List<Runnable> rowsToAdd, int[] yy, int x, int w, boolean bolt) {
        rowsToAdd.add(() -> {
            int lw = 58, bw = (w - lw - 4) / 2;
            add(new Widget.Button(x, yy[0], lw, ROW, bolt ? "Bolt hand:" : "Mag hand:", () -> {}));
            for (int i = 0; i < 2; i++) {
                final boolean left = i == 0;
                add(new Widget.Button(x + lw + 2 + i * (bw + 2), yy[0], i == 1 ? w - lw - 2 - bw - 2 : bw, ROW, () -> {
                    GunType g2 = gun();
                    boolean on = g2 != null && (bolt ? g2.boltRight != left : g2.magLeft == left);
                    return (on ? "§a> " : "§7") + (left ? "Left" : "Right");
                }, () -> {
                    GunType g2 = gun();
                    if (g2 == null) return;
                    boolean now = bolt ? !g2.boltRight : g2.magLeft;
                    if (now == left) return;
                    begin();
                    if (bolt) g2.boltRight = !left; else { g2.magLeft = left; g2.applyPoints(); }
                    changed();
                    say((bolt ? "Bolt pulled by the " : "Magazine changed by the ") + (left ? "left" : "right") + " hand");
                }));
            }
        });
    }

    /** Tabs of the plain pages (null = one list). */
    private static String[] pageTabs(String pg) {
        return switch (pg) {
            case "Handling" -> new String[]{"Moving", "Spread", "Recoil & aim"};
            case "Anim" -> new String[]{"Motion", "Bolt & style"};
            case "FX" -> new String[]{"Tracers", "Flash"};
            case "View" -> new String[]{"1st person", "3rd person"};
            case "Arms" -> new String[]{"Size", "R arm", "L arm"};
            default -> null;
        };
    }

    private static boolean inPageTab(String pg, String tab, String key) {
        return switch (pg) {
            case "Handling" -> switch (tab) {
                case "Moving" -> key.endsWith("Speed");
                case "Spread" -> key.startsWith("spread");
                default -> !key.endsWith("Speed") && !key.startsWith("spread");
            };
            case "Anim" -> tab.equals("Bolt & style") == key.equals("boltShot");
            case "FX" -> tab.equals("Flash") == key.startsWith("flash");
            case "View" -> tab.equals("3rd person") == key.startsWith("thirdPerson");
            case "Arms" -> switch (tab) {
                case "Size" -> key.equals("armSize") || key.equals("armLength") || key.equals("armThickness");
                case "R arm" -> key.contains("Right");
                default -> key.contains("Left");
            };
            default -> true;
        };
    }

    private static boolean pageTabChanged(GunType t, String pg, String tab) {
        if (t == null) return false;
        GunType d = GunType.defaults();
        for (GunType.Param p : GunType.PARAMS)
            if (p.page().equals(pg) && inPageTab(pg, tab, p.key()) && differs(p, t, dfl(p, t))) return true;
        if (pg.equals("Anim") && tab.equals("Bolt & style")) return t.pistol || t.slideLock;
        return false;
    }

    private static boolean differs(GunType.Param p, GunType t, GunType d) { return Math.abs(p.value(t) - p.value(d)) > p.step() * 0.5f; }

    /** The json key without its pose's name ("reloadGunRotation" -> "GunRotation"), or the key when it is not the pose's. */
    private static String suffix(String key, String pose) { return key.startsWith(pose) ? key.substring(pose.length()) : key; }

    private static boolean groupChanged(GunType t, String pp, String pn, String[] keys) {
        if (t == null) return false;
        GunType d = GunType.defaults();
        for (GunType.Param p : GunType.PARAMS)
            if (p.page().equals(pp) && Arrays.asList(keys).contains(suffix(p.key(), pn)) && differs(p, t, dfl(p, t))) return true;
        return false;
    }

    private static boolean poseChanged(GunType t, int pose) {
        if (t == null) return false;
        GunType d = GunType.defaults();
        for (GunType.Param p : GunType.PARAMS) if (p.page().equals(GunType.posePage(pose)) && differs(p, t, dfl(p, t))) return true;
        return false;
    }

    /** "R arm move X" -> "Move X" (the tab already says whose). */
    private static String shortLabel(String l) {
        int i = l.indexOf("move"), j = l.indexOf("turn");
        int k = i >= 0 ? i : j;
        if (k < 0) return l;
        String r = l.substring(k);
        return Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }

    // ------------------------------------------------------------------ rows
    private void paramRow(List<Runnable> rowsToAdd, int[] yy, int x, int w, GunType.Param p, GunType def, String label) {
        rowsToAdd.add(() -> {
            GunType d = p.melee() ? GunType.defaultsFor(gun()) : def;          // a pistol's melee defaults are a pistol whip
            slider(x, yy[0], w, label, p.min(), p.max(), p.step(),
                    () -> { GunType g2 = gun(); return g2 == null ? 0 : p.value(g2); },
                    v -> { GunType g2 = gun(); if (g2 != null) { begin(); p.apply(g2, v); changed(); } },
                    p.value(d), () -> { GunType g2 = gun(); return g2 != null && differs(p, g2, d); });
        });
    }

    /** Points page: every point as a button, the picked one moved by X / Y / Z (the yellow cube shows it). */
    private void pointsPage(List<Runnable> rowsToAdd, int[] yy, int x, int w, GunType t) {
        String[] pts = GunType.POINTS;
        pointIndex = Math.max(0, Math.min(pointIndex, pts.length - 1));
        int cols = 4;
        for (int r0 = 0; r0 < pts.length; r0 += cols) {
            final int r = r0;
            rowsToAdd.add(() -> {
                int n = Math.min(cols, pts.length - r), bw = (w - 2 * (cols - 1)) / cols;
                for (int i = 0; i < n; i++) {
                    final int k = r + i;
                    add(new Widget.Button(x + i * (bw + 2), yy[0], i == cols - 1 ? w - i * (bw + 2) : bw, ROW,
                            () -> (pointIndex == k ? "§a" : "") + pts[k] + (gun() != null && gun().pointOverrides.containsKey(pts[k]) ? "§7*" : ""),
                            () -> { pointIndex = k; game.relayout(); }));
                }
            });
        }
        String pn = pts[pointIndex];
        GunController gc = s.guns;
        gc.editorMarker = pn;
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "§e" + pointName(pn) + (gun() != null && gun().pointOverrides.containsKey(pn) ? " §7(set) - click: auto" : " §7(auto)"), () -> {
            GunType g2 = gun();
            if (g2 != null && g2.pointOverrides.containsKey(pn)) { begin(); g2.pointOverrides.remove(pn); g2.applyPoints(); changed(); }
        })));
        pointRows(rowsToAdd, yy, x, w, t, pn);
        if (pn.startsWith("bolt")) {
            gc.previewBolt = slideTest;
            rowsToAdd.add(() -> slider(x, yy[0], w, "Bolt shown at", 0, 1, 0.01f, () -> slideTest,
                    v -> { slideTest = (float) v; s.guns.previewBolt = slideTest; }, 1, null));
        }
        if (pn.equals("las")) { gc.previewPose = "bolt"; gc.previewAt = boltAt; }
    }

    private static String pointName(String p) {
        return switch (p) {
            case "rag" -> "Right hand on the grip";
            case "lag" -> "Left hand on the guard";
            case "las" -> "Hand on the bolt";
            case "ram" -> "Hand on the magazine";
            case "sight" -> "Sight (aim)";
            case "sight2" -> "Second sight (aim 2)";
            case "muzzle" -> "Muzzle";
            case "magPivot" -> "Magazine rocks around";
            case "eject" -> "Casings fly from";
            case "boltFront" -> "Bolt at rest (front limit)";
            case "boltBack" -> "Bolt all the way back";
            default -> p;
        };
    }

    /** Three rows X / Y / Z for a point of the model (an override in the json); the marker follows the point being moved. */
    private void pointRows(List<Runnable> rowsToAdd, int[] yy, int x, int w, GunType t, String pn) {
        float[] bb = t.bodyBounds();
        String[] ax = {"X", "Y", "Z"};
        for (int k = 0; k < 3; k++) {
            final int c = k;
            float lo = (bb == null ? -1 : bb[k]) - 0.3f, hi = (bb == null ? 1 : bb[k + 3]) + 0.3f;
            rowsToAdd.add(() -> slider(x, yy[0], w, pn + " " + ax[c], lo, hi, 0.0025f,
                    () -> { GunType g2 = gun(); return g2 == null ? 0 : point(g2, pn)[c]; },
                    v -> {
                        GunType g2 = gun();
                        if (g2 == null) return;
                        begin();
                        float[] pt = point(g2, pn).clone(); pt[c] = (float) v;
                        g2.pointOverrides.put(pn, pt); g2.applyPoints();
                        s.guns.editorMarker = pn;
                        changed();
                    },
                    Double.NaN, () -> gun() != null && gun().pointOverrides.containsKey(pn)));
        }
    }

    private static float[] point(GunType t, String n) {
        return switch (n) {
            case "rag" -> t.rag; case "lag" -> t.lag; case "las" -> t.las; case "ram" -> t.ram;
            case "sight" -> t.sightPoint; case "sight2" -> t.sight2Point; case "muzzle" -> t.muzzlePoint; case "magPivot" -> t.magPivot;
            case "boltFront" -> t.boltFront; case "boltBack" -> t.boltBack;
            default -> t.ejectPoint;
        };
    }

    /** A row: [-] slider [+]; right click on the slider = the default value; Shift on - / + = ten steps. */
    private void slider(int x, int y, int w, String label, float min, float max, float step,
                        java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set, double def,
                        java.util.function.BooleanSupplier highlight) {
        int bw = 14;
        add(new Widget.Button(x, y, bw, ROW, "-", () -> set.accept(clamp(get.getAsDouble() - step * mult(), min, max))));
        Widget.Slider sl = add(new Widget.Slider(x + bw + 1, y, w - 2 * bw - 2, ROW, label, min, max, step, get, set, v -> fmt(v, step)));
        sl.defaultValue = def;
        sl.highlight = highlight;
        sliders.add(sl);
        add(new Widget.Button(x + w - bw, y, bw, ROW, "+", () -> set.accept(clamp(get.getAsDouble() + step * mult(), min, max))));
    }

    private boolean down(int key) { return glfwGetKey(game.window, key) == GLFW_PRESS; }
    private boolean shift() { return down(GLFW_KEY_LEFT_SHIFT) || down(GLFW_KEY_RIGHT_SHIFT); }
    private boolean ctrl() { return down(GLFW_KEY_LEFT_CONTROL) || down(GLFW_KEY_RIGHT_CONTROL); }
    private int mult() { return shift() ? 10 : 1; }

    private static double clamp(double v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String fmt(float v, float step) {
        int d = step >= 1 ? 0 : step >= 0.1f ? 1 : step >= 0.01f ? 2 : step >= 0.001f ? 3 : 4;
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    /** No gun in hand: the guns to take. */
    private void initNoGun(int x, int y, int w) {
        y += 24;
        for (GunType t : Guns.all()) {
            Item it = Items.byName(Guns.PREFIX + t.name);
            if (it == null) continue;
            add(new Widget.Button(x, y, w, ROW, "Take " + t.displayName + " §7(" + t.name + ")", () -> {
                ItemStack st = new ItemStack(it, 1);
                if (s.creative() && s.heldStack() == null) s.inv.set(s.selected(), st); else s.giveOrDrop(st);
                s.inv.changed();
                game.relayout();
            }));
            y += ROW + 2;
            if (y > height - 40) break;
        }
        add(new Widget.Button(x, height - ROW - 4, w, ROW, "Reload files", () -> { say(Guns.reload()); game.relayout(); }));
    }

    // ------------------------------------------------------------------ poses: copy, paste, reset, presets
    private void copyPose(int pose) {
        GunType t = gun();
        if (t == null) return;
        String pp = GunType.posePage(pose), pn = GunType.POSES[pose];
        clipboard = new LinkedHashMap<>();
        for (GunType.Param p : GunType.PARAMS) if (p.page().equals(pp)) clipboard.put(suffix(p.key(), pn) + "#" + p.index(), p.value(t));
        clipboardFrom = t.displayName + " " + poseName(pose);
        say("Copied pose " + clipboardFrom);
    }

    private void pastePose(int pose) {
        GunType t = gun();
        if (t == null || clipboard == null) { say("§7Nothing copied yet (Copy pose)"); return; }
        String pp = GunType.posePage(pose), pn = GunType.POSES[pose];
        begin();
        int n = 0;
        for (GunType.Param p : GunType.PARAMS) {
            if (!p.page().equals(pp)) continue;
            Float v = clipboard.get(suffix(p.key(), pn) + "#" + p.index());
            if (v != null) { p.apply(t, v); n++; }
        }
        changed();
        say("Pasted " + clipboardFrom + " into " + poseName(pose) + " (" + n + " values)");
    }

    private static String poseName(int pose) {
        if (GunType.meleePose(pose)) {
            int k = (pose - GunType.MELEE_POSE) / 2;
            return GunType.MELEE_NAMES[k] + " " + ((pose - GunType.MELEE_POSE) % 2 == 0 ? "wind-up" : "hit");
        }
        for (int i = 0; i < SECTION_POSE.length; i++) if (SECTION_POSE[i] == pose) return VIEW_SECTIONS[i];
        return GunType.POSES[pose];
    }

    /** The pose shown in the View page now (-1 = none: General, or a melee part that is not a pose). */
    private static int shownPose() {
        int sp = SECTION_POSE[viewSection];
        if (sp == MELEE) return meleeKind < 3 && meleePart < 2 ? GunType.meleePose(meleeKind, meleePart == 1) : -1;
        return sp;
    }

    private void resetGroup(String pp, String pn, String[] keys) {
        GunType t = gun();
        if (t == null) return;
        begin();
        for (GunType.Param p : GunType.PARAMS)
            if (p.page().equals(pp) && Arrays.asList(keys).contains(suffix(p.key(), pn))) p.apply(t, p.value(dfl(p, t)));
        changed();
        say("Reset to the defaults (Undo brings it back)");
    }

    private void preset(boolean pistol) {
        GunType t = gun();
        if (t == null) return;
        begin();
        GunType.posePreset(t, pistol);
        changed();
        say((pistol ? "Pistol" : "Rifle") + " poses, reload style and bolt set (Undo brings yours back)");
    }

    // ------------------------------------------------------------------ saving, undo
    private Deque<String> undoStack() { GunType t = gun(); return UNDO.computeIfAbsent(t == null ? "" : t.name, k -> new ArrayDeque<>()); }
    private Deque<String> redoStack() { GunType t = gun(); return REDO.computeIfAbsent(t == null ? "" : t.name, k -> new ArrayDeque<>()); }

    /** Before a change: the first change after a pause starts a new undo step (a slider drag is one step). */
    private void begin() {
        if (dirty || committed == null) return;
        Deque<String> u = undoStack();
        if (u.isEmpty() || !u.peek().equals(committed)) u.push(committed);
        while (u.size() > UNDO_MAX) u.removeLast();
        redoStack().clear();
    }

    private void changed() {
        if (!dirty) begin();
        dirty = true;
        dirtySince = System.currentTimeMillis();
    }

    private void undo() {
        GunType t = gun();
        if (t == null) return;
        flush();
        Deque<String> u = undoStack();
        if (u.isEmpty()) { say("§7Nothing to undo"); return; }
        redoStack().push(t.toJson());
        t.fromJson(u.pop());
        writeQuiet(t);
        say("Undo §7(" + u.size() + " more)");
        game.relayout();
    }

    private void redo() {
        GunType t = gun();
        if (t == null) return;
        flush();
        Deque<String> r = redoStack();
        if (r.isEmpty()) { say("§7Nothing to redo"); return; }
        undoStack().push(t.toJson());
        t.fromJson(r.pop());
        writeQuiet(t);
        say("Redo §7(" + r.size() + " more)");
        game.relayout();
    }

    private void writeQuiet(GunType t) {
        try { t.save(); Guns.savedByEditor(); Guns.registerItems(); committed = t.toJson(); dirty = false; }
        catch (Exception e) { say("§cCannot save: " + e.getMessage()); }
    }

    private void flush() { if (dirty) save(); }

    private void save() {
        GunType t = gun();
        if (t == null) return;
        try {
            t.save();
            Guns.savedByEditor();
            Guns.registerItems();          // name and tooltip of the item
            committed = t.toJson();
            dirty = false;
            say("§aSaved " + t.jsonFile.getFileName());
        } catch (Exception e) { say("§cCannot save: " + e.getMessage()); }
    }

    private void say(String m) { status = m; statusTicks = 80; }

    @Override public void tick() {
        super.tick();
        if (statusTicks > 0) statusTicks--;
        if (dirty && System.currentTimeMillis() - dirtySince > 400) save();     // saved at once, a moment after the last change
        GunType t = gun();
        String now = t == null ? "" : t.name + "@" + Guns.version();
        if (!now.equals(shownGun)) { committed = null; game.relayout(); }       // another gun in hand, or the guns were reloaded
    }

    // ------------------------------------------------------------------ input
    private Widget.Slider sliderAt(int mx, int my) {
        for (Widget.Slider sl : sliders) if (sl.contains(mx, my)) return sl;
        return null;
    }

    @Override public void scrolled(double amount) {
        int mx = game.mouseGuiX(), my = game.mouseGuiY();
        Widget.Slider sl = ctrl() ? sliderAt(mx, my) : null;
        if (sl != null) { sl.setValue(sl.value() + Math.signum(amount) * sl.step() * mult()); return; }
        int old = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount), Math.max(0, totalRows - rows)));
        if (scroll != old) game.relayout();
    }

    @Override public void mouseClicked(int mx, int my, int button) {
        if (nameField != null && nameField.focused && !nameField.contains(mx, my)) nameField.onEnter.run();
        if (valueField != null && !valueField.contains(mx, my)) closeValueField(false);
        if (button == 0 && ctrl() && valueField == null) {
            Widget.Slider sl = sliderAt(mx, my);
            if (sl != null) { openValueField(sl); return; }
        }
        super.mouseClicked(mx, my, button);
        if (valueField != null) valueField.focused = true;
    }

    /** Ctrl + click on a slider: type its value (Enter = set, Esc = cancel). */
    private void openValueField(Widget.Slider sl) {
        valueSlider = sl;
        valueField = new Widget.TextField(sl.x, sl.y, sl.w, sl.h);
        valueField.text = fmt((float) sl.value(), Math.min(sl.step(), 0.001f)).replaceAll("0+$", "").replaceAll("\\.$", "");
        valueField.focused = true;
        valueField.onEnter = () -> closeValueField(true);
        widgets.add(valueField);
    }

    private void closeValueField(boolean apply) {
        if (valueField == null) return;
        if (apply && valueSlider != null) {
            try { valueSlider.setValue(Double.parseDouble(valueField.text.trim().replace(',', '.'))); }
            catch (NumberFormatException e) { say("§cNot a number: " + valueField.text); }
        }
        widgets.remove(valueField);
        valueField = null;
        valueSlider = null;
    }

    @Override public void keyPressed(int key, int mods) {
        if (valueField != null) {
            if (key == GLFW_KEY_ESCAPE) { closeValueField(false); return; }
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) { closeValueField(true); return; }
            valueField.key(key, mods);
            return;
        }
        boolean typing = nameField != null && nameField.focused;
        if (typing) {
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) { nameField.onEnter.run(); nameField.focused = false; return; }
            if (key == GLFW_KEY_ESCAPE) { nameField.focused = false; return; }
            super.keyPressed(key, mods);
            return;
        }
        boolean c = (mods & GLFW_MOD_CONTROL) != 0, sh = (mods & GLFW_MOD_SHIFT) != 0;
        if (Keys.GUN_EDITOR.matches(key)) { onClose(); return; }
        if (c && key == GLFW_KEY_Z) { if (sh) redo(); else undo(); return; }
        if (c && key == GLFW_KEY_Y) { redo(); return; }
        if (c && key == GLFW_KEY_S) { save(); return; }
        if (c && (key == GLFW_KEY_C || key == GLFW_KEY_V)) {
            if (GunType.PAGES[page].equals("View") && shownPose() >= 0) { if (key == GLFW_KEY_C) copyPose(shownPose()); else pastePose(shownPose()); }
            else say("§7Copy / paste: open a pose (View page)");
            return;
        }
        if (key == GLFW_KEY_TAB) { page = (page + (sh ? GunType.PAGES.length - 1 : 1)) % GunType.PAGES.length; scroll = 0; game.relayout(); return; }
        if (key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN) {
            int d = key == GLFW_KEY_PAGE_DOWN ? 1 : -1;
            String pg = GunType.PAGES[page];
            if (pg.equals("View")) viewSection = Math.floorMod(viewSection + d, VIEW_SECTIONS.length);
            else if (pg.equals("Arms")) armsSection = Math.floorMod(armsSection + d, ARMS_SECTIONS.length);
            scroll = 0;
            game.relayout();
            return;
        }
        if (key == GLFW_KEY_SPACE) { s.guns.previewFire(sh ? 8 : 1); return; }
        if (Keys.RELOAD.matches(key)) { s.guns.previewReload(sh); return; }
        if (Keys.MELEE.matches(key)) { s.guns.previewMelee(meleeKind < 3 ? meleeKind : 0, meleeKind == 3); return; }
        super.keyPressed(key, mods);
    }

    @Override public void onClose() {
        if (valueField != null) closeValueField(false);
        if (nameField != null && nameField.focused) nameField.onEnter.run();
        flush();
        GunController gc = s.guns;
        gc.editorMarker = null;
        gc.previewAim = gc.previewWalk = gc.previewSprint = false;
        gc.previewPose = null;
        gc.previewAt = -1;
        gc.previewBolt = -1;
        gc.previewSpeed = 1;
        s.pouchPreview = null;
        super.onClose();
    }

    // ------------------------------------------------------------------ drawing
    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xB0000000);
        GunType t = gun();
        boolean mag = magGun() != null;
        g.text((mag ? "Magazine mount" : "Gun Editor") + (t != null ? " §7- §f" + t.displayName : ""), 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.GUN_EDITOR) + ": close";
        g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        if (t == null) {
            g.text("§7Take a gun in hand to tune it:", 6, 24, 0xFFFFFF, false);
        } else if (mag) {
            if (totalRows > rows) g.text("§7wheel: scroll (" + (scroll + 1) + "-" + Math.min(totalRows, scroll + rows) + " of " + totalRows + ")", 6, height - 3 * (ROW + 2) - 14, 0xFFFFFF, false);
            helpBox(g, new String[]{"Where this magazine sits in the belt", "pouches (Alt + E): the same for all six",
                    "(the right ones mirrored), for every gun", "of this kind, players and actors.", "",
                    "Move out: away from the belt middle,", "up, forward. Turn around the magazine's",
                    "middle. Size: x the 3rd person size.", "",
                    "§7Drag (Shift: fine), right click: default,", "§7Ctrl+click: type, Ctrl+Z / Ctrl+Y: undo.",
                    "§7Take a gun in hand to tune the gun."}, 24, 0xD0D0D0);
        } else {
            if (GunType.PAGES[page].equals("Stats") && nameField != null) g.text("§7Name", 6, nameField.y + 4, 0xFFFFFF, false);
            if (totalRows > rows) g.text("§7wheel: scroll (" + (scroll + 1) + "-" + Math.min(totalRows, scroll + rows) + " of " + totalRows + ")", 6, height - 4 * (ROW + 2) - 14, 0xFFFFFF, false);
            List<String> help = new ArrayList<>(Arrays.asList(help(t)));
            help.add("");
            help.add("§7Drag (Shift: fine), right click: default,");
            help.add("§7Ctrl+click: type, Ctrl+wheel: step.");
            help.add("§7Ctrl+Z / Ctrl+Y: undo / redo, Tab: page,");
            help.add("§7Space: shoot, " + Keys.n(Keys.RELOAD) + " / Shift+" + Keys.n(Keys.RELOAD) + ": reloads.");
            helpBox(g, help.toArray(new String[0]), 24, 0xD0D0D0);
            int below = 24 + help.size() * 10 + 8;
            if (t.shootSounds.isEmpty()) { g.text("§7No shoot1..3.ogg yet: silent shots", PW + 6, below, 0xFFFFFF, true); below += 10; }
            if (s.guns.previewSpeed < 1) g.text("§bSlow motion " + speedName(s.guns.previewSpeed), PW + 6, below, 0xFFFFFF, true);
        }
        super.render(g, mx, my, partial);
        if (statusTicks > 0) g.text(status, PW + 6, height - 14, 0xFFFFFF, true);
    }

    private String[] help(GunType t) {
        String pg = GunType.PAGES[page];
        return switch (pg) {
            case "Stats" -> new String[]{"Rate, magazine, damage, range,", "reload times, volume."};
            case "Handling" -> new String[]{"Walk / aim / run speed with the gun,", "spread (degrees), recoil of the view,", "aim zoom and how fast the sights come up."};
            case "View" -> viewSection == 0 ? new String[]{"General: 1st person size,", "3rd person size and position."}
                    : poseHelp();
            case "Anim" -> new String[]{"Motion: how strong each motion is", "(1 = normal), Anim speed: how fast.", "Bolt & style: reload style (rifle: the",
                    "mag rocks out; pistol: it slides out", "of the grip, the slide is racked), the", "slide lock, how far the bolt goes back",
                    "per shot (1 = all the way), presets."};
            case "FX" -> new String[]{"Tracers: the share of bullets that glow", "(0.3 = about every third), speed in", "blocks a second, length, width, glow,",
                    "colour. Flash: the muzzle flash point", "(yellow cube; smoke and tracers start", "there too) and its size (0 = none)."};
            case "Arms" -> armsSection == 1 ? new String[]{"Mag / Bolt: which hand changes the", "magazine and which pulls the bolt.", "Touch points of the hands (yellow cube):",
                    "the palm goes there (X / Y / Z), and", "the hand's turn there (yaw, pitch, roll", "around the palm). Mag / Bolt tabs show",
                    "the reload / bolt pose (Hold at: View)."}
                    : new String[]{"The player's arms (normal Minecraft", "arms, never stretched): size, length,", "thickness; each arm: move (palm off its",
                    "point), turn around the hand, where it", "comes from. Per pose: View, a pose."};
            default -> {
                String pn = GunType.POINTS[Math.max(0, Math.min(pointIndex, GunType.POINTS.length - 1))];
                if (pn.startsWith("bolt")) yield new String[]{"Bolt limits: yellow = the point picked,", "cyan = the other end. The bolt (slide)",
                        "moves only along that line, never past", String.format(Locale.ROOT, "boltBack. Travel now: %.3f.", t.boltTravel),
                        "Bolt shown at: drag the bolt to check."};
                yield new String[]{"Points of the model (model units),", "yellow cube = the point picked;", "* = set by hand (click the name: auto).",
                        "rag / lag: hands on grip / guard,", "las / ram: hands on bolt / magazine."};
            }
        };
    }

    private String[] poseHelp() {
        String sec = VIEW_SECTIONS[viewSection];
        List<String> l = new ArrayList<>(List.of("Pose \"" + sec + "\" (held on screen). Tabs:", "All: the gun with the hands,",
                "Gun: the gun, the hands stay on it,", "Arms: both arms swing (hands stay),", "R / L arm: one arm: move = palm off",
                "its point, turn = around the hand.", "Move: x right, y up, z forward = minus."));
        if (SECTION_POSE[viewSection] == MELEE) return meleeHelp();
        switch (viewSection) {
            case 1 -> l.add("Hip: where the gun is when not aiming.");
            case 2 -> l.add("Aim: the sight stays on the centre.");
            case 3 -> { l.add("Aim 2: hold " + Keys.n(Keys.AIM2) + " while aiming;"); l.add("its sight: point sight2 (Points)."); }
            case 5 -> l.add("Hold at: the moment of the reload shown.");
            case 6 -> l.add("Hold at: the moment of the bolt shown.");
            default -> l.add("Added on top while this pose is on.");
        }
        l.add("Copy / Paste: the whole pose (any gun).");
        return l.toArray(new String[0]);
    }

    private String[] meleeHelp() {
        if (meleeKind == 3) return new String[]{"Melee setup: reach (blocks), knockback,", "combo window: pressed again within it",
                "(or while the gun goes back), the next", "kind follows: Jab -> Swing -> Smash.", "Play a kind or the combo above.",
                "In game: " + Keys.n(Keys.MELEE) + " with a gun in hand."};
        String k = GunType.MELEE_NAMES[meleeKind];
        if (meleePart == 2) return new String[]{k + ": times (seconds) and damage.", "Wind-up: the gun drawn back; Strike:",
                "to the hit, speeding up (it hits at the", "end); Hold: stays at the hit; Back: to", "the normal hold. Hold at: the moment",
                "shown (Play: the whole strike)."};
        return new String[]{k + " - " + MELEE_PARTS[meleePart] + " pose (held on screen),", "added on top of the hold. Tabs:",
                "All: the gun with the hands,", "Gun: the gun, the hands stay on it,", "Arms: both arms swing (hands stay),",
                "R / L arm: move = the palm off the gun", "(a pistol whip: the left hand lets go).", "Hold at: the moment of the strike shown.",
                "Play (or " + Keys.n(Keys.MELEE) + "): the whole strike;", "Speed: slow motion."};
    }

    private void helpBox(GuiRenderer g, String[] lines, int y, int color) {
        int w = 0;
        for (String h : lines) w = Math.max(w, g.textWidth(h));
        g.fill(PW + 3, y - 3, PW + 9 + w, y + lines.length * 10 - 1, 0x90000000);
        for (String h : lines) { g.text(h, PW + 6, y, color, true); y += 10; }
    }
}
