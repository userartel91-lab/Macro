import derpcraft.block.Blocks;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;

/**
 * NPC scenes in the real game: a take recorded "as the NPC" (walking, a placed block, a broken block, a chest with
 * items broken, an explosion), the world switch of the scene (kept / restored), the "Restore world" button and the
 * pages of the NPC Hub. Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static NpcManager N() { return S().npcs; }
    static Actor bob;
    static int gy;
    static int[] P1, P2, P3, CH;
    static int fails;

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    static String name(int[] p) { return Blocks.byId(S().world.getState(p[0], p[1], p[2]) >> 4).name; }

    static int diamondsInChest() {
        var be = S().world.getBlockEntity(CH[0], CH[1], CH[2]);
        if (be == null || be.inventory() == null) return -1;
        int n = 0;
        for (int i = 0; i < be.inventory().size(); i++) { var st = be.inventory().get(i); if (st != null && st.item.name.equals("diamond")) n += st.count; }
        return n;
    }

    static int diamondsOnGround() {
        int n = 0;
        for (var e : S().world.entities) if (e instanceof derpcraft.entity.ItemEntity ie && !ie.dead && ie.stack.item.name.equals("diamond")) n += ie.stack.count;
        return n;
    }

    /** Air blocks in a 5x3x5 box around the explosion (a crater = many). */
    static int craterAir() {
        int n = 0;
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 0; y++) for (int z = -2; z <= 2; z++) if (S().world.getState(P3[0] + x, P3[1] + y, P3[2] + z) >> 4 == 0) n++;
        return n;
    }

    static void state(String when) {
        System.out.printf("[smoke] %s: P1 %s, P2 %s, chest %s (%d diamonds inside, %d on the ground), crater air %d, restorable %d%n",
                when, name(P1), name(P2), name(CH), diamondsInChest(), diamondsOnGround(), craterAir(), N().restorable(bob.group));
    }

    static void hub(int page) {
        try {
            var c = Class.forName("derpcraft.gui.NpcHub");
            var f = c.getDeclaredField("page"); f.setAccessible(true); f.setInt(null, page);
            var pg = derpcraft.gui.Screen.class.getDeclaredField("pausesGame"); pg.setAccessible(true); pg.setBoolean(S().game.screen, false);
            S().game.relayout();
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.gameMode = "creative";
            s.player.yaw = 0; s.player.pitch = 20;
            int px = (int) Math.floor(s.player.x), pz = (int) Math.floor(s.player.z);
            gy = s.world.topSolidY(px, pz);
            bob = N().spawn("Bob", px + 0.5, gy + 1, pz + 4.5, 180);
            bob.skin = "Police";
            N().countdownSeconds = 0;
            N().recordAsNpc = true;
            N().walkToMarks = false;
            N().setRestoresWorld(bob.group, false);
            P1 = new int[]{px + 1, gy + 1, pz + 7};        // a block the take places
            P2 = new int[]{px - 1, gy, pz + 7};            // a ground block the take breaks
            CH = new int[]{px + 2, gy + 1, pz + 8};        // a chest with 5 diamonds the take breaks
            P3 = new int[]{px - 6, gy, pz + 10};           // an explosion the take sets off
            s.world.setBlock(CH[0], CH[1], CH[2], Blocks.byName("chest"), 0);
            s.world.getBlockEntity(CH[0], CH[1], CH[2]).inventory().set(0, new ItemStack(Items.byName("diamond"), 5));
            s.view = 1;
            state("start");
        });
        // ---- the hub, every page
        SmokeMain.wait(10, () -> { S().game.setScreen(new derpcraft.gui.NpcHub(S().game, S(), bob)); hub(0); });
        String[] pages = {"scenes", "play", "record", "teams", "npc", "look", "ai", "stats", "takes"};
        for (int i = 0; i < pages.length; i++) {
            final int p = i;
            SmokeMain.wait(2, () -> hub(p));
            SmokeMain.wait(3, () -> SmokeMain.shot("hub_" + p + "_" + pages[p]));
        }
        SmokeMain.wait(2, () -> { try { var c = Class.forName("derpcraft.gui.NpcHub"); var f = c.getDeclaredField("inGroup"); f.setAccessible(true); f.setBoolean(null, false); } catch (Exception e) { e.printStackTrace(); } hub(0); });
        SmokeMain.wait(3, () -> SmokeMain.shot("hub_0_groups"));
        SmokeMain.wait(2, () -> S().game.setScreen(null));
        // ---- record as the NPC
        double[] before = new double[3];
        SmokeMain.wait(5, () -> {
            before[0] = S().player.x; before[1] = S().player.y; before[2] = S().player.z;
            N().startRecording(bob);
            check("as-NPC start", Math.abs(S().player.x - bob.x()) < 0.01 && Math.abs(S().player.z - bob.z()) < 0.01, "player at Bob's mark, skin " + S().playerSkin().name());
            S().player.yaw = 0;
            SmokeMain.hold(Keys.FORWARD, true);
        });
        SmokeMain.wait(15, () -> { SmokeMain.shot("rec_1_acting_as_bob"); S().player.stance = derpcraft.game.Stance.CRAWL; });
        SmokeMain.wait(5, () -> {
            NpcManager n = N();
            n.playerActing = true;                       // like the player's clicks
            S().world.setBlock(P1[0], P1[1], P1[2], Blocks.byName("stone"), 0);
            S().world.setBlock(P2[0], P2[1], P2[2], 0);
            S().world.setBlock(CH[0], CH[1], CH[2], 0);
            n.playerActing = false;
            state("take: clicked");
        });
        SmokeMain.wait(5, () -> { S().player.stance = derpcraft.game.Stance.STAND; S().explode(P3[0] + 0.5, P3[1] + 0.5, P3[2] + 0.5, 3, true); state("take: exploded"); });
        SmokeMain.wait(10, () -> SmokeMain.shot("rec_2_after_explosion"));
        SmokeMain.wait(5, () -> {
            SmokeMain.hold(Keys.FORWARD, false);
            S().stopRecordingKey();
            var t = bob.take(bob.group);
            check("take saved", t != null && t.length() > 20, t == null ? "none" : t.length() + " frames, " + t.blocks.size() + " blocks, " + t.items.size() + " actions");
            check("explosion recorded", t != null && t.blocks.size() > 10, "blocks in take: " + (t == null ? 0 : t.blocks.size()));
            check("player back", Math.abs(S().player.x - before[0]) < 0.01 && Math.abs(S().player.z - before[2]) < 0.01, "own skin again: " + S().playerSkin().name());
            state("after take (world kept)");
            check("kept after take", name(P1).equals("stone") && name(CH).equals("air"), "");
            check("restorable", N().restorable(bob.group) > 10, "" + N().restorable(bob.group));
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("rec_3_kept"));
        // ---- the button: Restore world
        SmokeMain.wait(5, () -> {
            int k = N().restoreWorld(bob.group);
            state("after Restore world (" + k + ")");
            check("restore button", name(P1).equals("air") && !name(P2).equals("air"), "P2 " + name(P2));
            check("chest back full", name(CH).equals("chest") && diamondsInChest() == 5, diamondsInChest() + " inside");
            check("no duplicate diamonds", diamondsOnGround() == 0, diamondsOnGround() + " on the ground");
            check("crater filled", craterAir() == 0, "air " + craterAir());
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("rec_4_restored"));
        // ---- play with "restored after the scene"
        SmokeMain.wait(5, () -> { N().setRestoresWorld(bob.group, true); check("play (restore on)", N().playScene(bob.group), ""); });
        SmokeMain.wait(40, () -> { state("playing"); SmokeMain.shot("play_1_restore_on_mid"); });
        SmokeMain.step(() -> !N().scenePlaying(bob.group), 600, () -> {
            state("after play (restore on)");
            check("restored after play", name(P1).equals("air") && name(CH).equals("chest") && diamondsInChest() == 5 && craterAir() == 0, "");
            check("nothing left to restore", N().restorable(bob.group) == 0, "" + N().restorable(bob.group));
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("play_2_restore_on_after"));
        // ---- play with "kept", then To start
        SmokeMain.wait(5, () -> { N().setRestoresWorld(bob.group, false); N().playScene(bob.group); S().player.yaw = 0; S().player.pitch = 15; });
        SmokeMain.wait(19, () -> {
            var t = bob.take(bob.group);
            int crawl = 0;
            for (int i = 0; i < t.length(); i++) if (t.frame(i).stance == derpcraft.game.Stance.CRAWL.ordinal()) crawl++;
            check("posture recorded", crawl >= 8, crawl + " crawling frames");
            S().player.setPos(bob.x() - 3, bob.y() + 0.5, bob.z() + 1);
            S().player.yaw = -110; S().player.pitch = 20; S().player.flying = true; S().view = 0;
        });
        SmokeMain.wait(2, () -> SmokeMain.shot("play_kept_mid_bob_crawls"));
        SmokeMain.wait(12, () -> S().view = 1);
        SmokeMain.step(() -> !N().scenePlaying(bob.group), 600, () -> {
            state("after play (kept)");
            check("kept after play", name(P1).equals("stone") && name(CH).equals("air") && craterAir() > 5, "");
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("play_3_kept_after"));
        SmokeMain.wait(5, () -> {
            N().resetScene(bob.group);
            state("after To start");
            check("To start restores", name(P1).equals("air") && diamondsInChest() == 5 && craterAir() == 0, "");
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("play_4_to_start"));
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
