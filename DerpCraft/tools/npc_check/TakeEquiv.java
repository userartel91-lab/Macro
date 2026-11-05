import derpcraft.npc.Recording;
import derpcraft.npc.Track;
import java.util.*;

public class TakeEquiv {
    public static void main(String[] a) {
        Random r = new Random(1);
        long checks = 0, mism = 0;
        for (int trial = 0; trial < 300; trial++) {
            Recording rec = new Recording();
            int len = 50 + r.nextInt(3000);
            for (int i = 0; i < len; i++) rec.frames.add(new Recording.Frame());
            int nb = r.nextInt(400);
            for (int i = 0; i < nb; i++) rec.blocks.add(new Recording.BlockAction(r.nextInt(len), i, 0, 0, 0, 1));
            rec.sortActions();
            Track.Take tk = new Track.Take(rec, null);
            tk.offset = r.nextInt(100); tk.trimStart = r.nextInt(len / 3); tk.trimEnd = r.nextInt(len / 3);
            float[] sp = {0.25f, 0.5f, 0.75f, 1, 1.25f, 1.5f, 2, 3, 4, 0.33f, 1.7f};
            tk.speed = sp[r.nextInt(sp.length)];
            for (int tick = 0; tick < tk.end() + 5; tick++) {
                List<Recording.BlockAction> old = new ArrayList<>(), nw = new ArrayList<>();
                for (var b : rec.blocks) if (tk.inside(b.tick()) && (int) Math.floor(tk.sceneTick(b.tick())) == tick) old.add(b);
                tk.forEachAt(rec.blocks, Recording.BlockAction::tick, tick, nw::add);
                checks++;
                if (!old.equals(nw)) mism++;
            }
        }
        System.out.println("ticks compared " + checks + ", different " + mism);
        // speed: a 10 minute take with 5000 block changes, every tick
        Recording rec = new Recording();
        for (int i = 0; i < 12000; i++) rec.frames.add(new Recording.Frame());
        for (int i = 0; i < 5000; i++) rec.blocks.add(new Recording.BlockAction(r.nextInt(12000), i, 0, 0, 0, 1));
        rec.sortActions();
        Track.Take tk = new Track.Take(rec, null);
        int[] sink = {0};
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            for (int tick = 0; tick < 12000; tick++) for (var b : rec.blocks) if (tk.inside(b.tick()) && (int) Math.floor(tk.sceneTick(b.tick())) == tick) sink[0]++;
            long t1 = System.nanoTime();
            for (int tick = 0; tick < 12000; tick++) tk.forEachAt(rec.blocks, Recording.BlockAction::tick, tick, b -> sink[0]++);
            long t2 = System.nanoTime();
            System.out.printf("whole take: old %.1f ms (%.1f us/tick), new %.2f ms (%.3f us/tick)%n", (t1 - t0) / 1e6, (t1 - t0) / 1e3 / 12000, (t2 - t1) / 1e6, (t2 - t1) / 1e3 / 12000);
        }
    }
}
