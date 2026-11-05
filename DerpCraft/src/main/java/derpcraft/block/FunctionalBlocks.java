package derpcraft.block;

import derpcraft.block.Block.*;
import derpcraft.game.Session;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.world.TerrainGenerator;
import derpcraft.world.World;
import derpcraft.world.be.*;

import java.util.*;

/**
 * Working Minecraft 1.12.2 blocks: doors, trapdoors, gates, ladders, vines, levers, buttons, pressure plates, crops,
 * farmland, saplings, double plants, chests, signs, beds, furnaces, storage blocks, rails, fire and decorative models.
 * Geometry is defined for "facing south" (q = 0) and rotated clockwise for other directions.
 */
public final class FunctionalBlocks {
    private FunctionalBlocks() {}

    // ------------------------------------------------------------------------------------------ helpers
    /** Quarter turn the player looks at: 0 south, 1 west, 2 north, 3 east. */
    static int lookQ(float yaw) { return Math.floorMod(Math.round(yaw / 90f), 4); }

    /** Quarter turns that rotate the north side onto the given horizontal direction. */
    static int qWall(int dir) {
        return switch (dir) { case Dir.EAST -> 1; case Dir.SOUTH -> 2; case Dir.WEST -> 3; default -> 0; };
    }

    static int wallDir(int q) { return switch (q & 3) { case 1 -> Dir.EAST; case 2 -> Dir.SOUTH; case 3 -> Dir.WEST; default -> Dir.NORTH; }; }

    static List<Box> rotated(List<Box> in, int q) {
        if ((q & 3) == 0) return in;
        List<Box> out = new ArrayList<>(in.size());
        for (Box b : in) out.add(b.rotateY(q));
        return out;
    }

    static Block reg(Block b, float hardness, String tab) {
        b.hardness = hardness;
        b.tab = tab;
        return Blocks.register(b);
    }

    static void tex(Block b, String t) { Arrays.fill(b.textures, t); }

    /** Toggles doors, trapdoors, gates and lamps around a redstone source (simple "redstone-lite"). */
    public static void power(World w, int x, int y, int z, boolean on) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int bx = x + dx, by = y + dy, bz = z + dz;
                    Block b = w.getBlock(bx, by, bz);
                    if (b instanceof Openable o) o.setOpen(w, bx, by, bz, on);
                    else if (b.name.equals("redstone_lamp") && on) w.setBlock(bx, by, bz, Blocks.byName("lit_redstone_lamp"), 0);
                    else if (b.name.equals("lit_redstone_lamp") && !on) w.setBlock(bx, by, bz, Blocks.byName("redstone_lamp"), 0);
                }
    }

    interface Openable { void setOpen(World w, int x, int y, int z, boolean open); }

    // ------------------------------------------------------------------------------------------ doors
    public static class DoorBlock extends Block implements Openable {
        /** Iron doors: not opened by hand (nor broken by bullets). */
        public final boolean iron;
        final String lower, upper;

        DoorBlock(String name, String texBase, boolean iron) {
            super(name);
            this.iron = iron;
            lower = texBase + "_lower";
            upper = texBase + "_upper";
            tex(this, lower);
            textures[Dir.UP] = upper;
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; inInventory = false; needsSupport = true;
            effectiveTool = iron ? Item.ToolType.PICKAXE : Item.ToolType.AXE;
            requiresTool = iron;
        }

        @Override public String textureName(int face, int meta) { return (meta & 8) != 0 ? upper : lower; }

        @Override public int neighborInfo(BlockGetter g, int x, int y, int z, int meta) {
            boolean up = (meta & 8) != 0;
            int other = g.getState(x, y + (up ? -1 : 1), z);
            return (other >> 4) == id ? other & 15 : (up ? 0 : 0);
        }

        @Override public List<Box> boxes(int meta, int conn) {
            boolean top = (meta & 8) != 0;
            int q = top ? conn & 3 : meta & 3;
            boolean open = ((top ? conn : meta) & 4) != 0;
            boolean rightHinge = ((top ? meta : conn) & 1) != 0;
            String t = top ? upper : lower;
            Box b;
            if (!open) b = new Box(0, 0, 0, 16, 16, 3);
            else if (!rightHinge) b = new Box(13, 0, 0, 16, 16, 16);
            else b = new Box(0, 0, 0, 3, 16, 16);
            b.all(t);
            if (open != rightHinge) { b.mirror(Dir.NORTH); b.mirror(Dir.SOUTH); b.mirror(Dir.WEST); b.mirror(Dir.EAST); }
            return List.of(b.rotateY(q));
        }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
            return w.getBlock(x, y + 1, z).replaceable && w.getBlock(x, y - 1, z).solid;
        }

        @Override public int placementMeta(int face, float hitY, float yaw) { return lookQ(yaw); }

        @Override public void onPlaced(World w, int x, int y, int z, int meta) {
            // hinge on the right when a door is already standing to the left
            int q = meta & 3;
            int leftDir = Dir.HORIZONTAL[(q + 3) & 3];
            boolean right = w.getBlock(x + Dir.DX[leftDir], y, z + Dir.DZ[leftDir]) instanceof DoorBlock;
            w.setBlock(x, y + 1, z, this, 8 | (right ? 1 : 0));
        }

        @Override public void onRemoved(World w, int x, int y, int z, int meta) {
            int oy = (meta & 8) != 0 ? y - 1 : y + 1;
            if (w.getBlock(x, oy, z) == this) w.setBlock(x, oy, z, 0);
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if (iron) return false;
            int ly = (meta & 8) != 0 ? y - 1 : y;
            int lm = s.world.getMeta(x, ly, z);
            setOpen(s.world, x, ly, z, (lm & 4) == 0);
            return true;
        }

        @Override public void setOpen(World w, int x, int y, int z, boolean open) {
            int m = w.getMeta(x, y, z);
            if ((m & 8) != 0) { y--; m = w.getMeta(x, y, z); }
            if (w.getBlock(x, y, z) != this) return;
            int nm = open ? m | 4 : m & ~4;
            if (nm != m) { w.setBlock(x, y, z, this, nm); w.markAround(x, y + 1, z, true); derpcraft.audio.Sfx.openClose(iron ? "iron_door" : "wooden_door", open, x, y, z); }
        }

        @Override public List<ItemStack> drops(int meta, Random r) {
            Item it = Items.byName(name);
            return it == null ? List.of() : List.of(new ItemStack(it));
        }
    }

    // ------------------------------------------------------------------------------------------ trapdoors
    public static class TrapdoorBlock extends Block implements Openable {
        final boolean iron;

        TrapdoorBlock(String name, String tex, boolean iron) {
            super(name);
            this.iron = iron;
            tex(this, tex);
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0;
            effectiveTool = iron ? Item.ToolType.PICKAXE : Item.ToolType.AXE;
            requiresTool = iron;
        }

        @Override public List<Box> boxes(int meta, int conn) {
            int q = meta & 3;
            boolean open = (meta & 4) != 0, top = (meta & 8) != 0;
            Box b;
            if (open) b = new Box(0, 0, 13, 16, 16, 16);
            else b = top ? new Box(0, 13, 0, 16, 16, 16) : new Box(0, 0, 0, 16, 3, 16);
            b.all(textures[0]);
            if (open) b.rotate(Dir.NORTH, 0);
            return List.of(b.rotateY(q));
        }

        @Override public int placementMeta(int face, float hitY, float yaw) {
            int q;
            if (face >= 2) q = qWallFromFace(face);
            else q = (lookQ(yaw) + 2) & 3;
            boolean top = face == Dir.DOWN || (face >= 2 && hitY > 0.5f);
            return q | (top ? 8 : 0);
        }

        /** Hinge against the clicked block: the open panel lies against it. */
        static int qWallFromFace(int face) {
            // open panel is at the south edge for q=0, i.e. hinged on a block to the south
            int wall = Dir.OPPOSITE[face];
            return switch (wall) { case Dir.SOUTH -> 0; case Dir.WEST -> 1; case Dir.NORTH -> 2; default -> 3; };
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if (iron) return false;
            setOpen(s.world, x, y, z, (meta & 4) == 0);
            return true;
        }

        @Override public void setOpen(World w, int x, int y, int z, boolean open) {
            int m = w.getMeta(x, y, z);
            int nm = open ? m | 4 : m & ~4;
            if (nm != m) { w.setBlock(x, y, z, this, nm); derpcraft.audio.Sfx.openClose(iron ? "iron_trapdoor" : "wooden_trapdoor", open, x, y, z); }
        }
    }

    // ------------------------------------------------------------------------------------------ fence gates
    public static class FenceGateBlock extends Block implements Openable {
        FenceGateBlock(String name, String tex) {
            super(name);
            tex(this, tex);
            shape = Shape.CUSTOM; lightOpacity = 0; connectGroup = "gate";
            effectiveTool = Item.ToolType.AXE;
        }

        @Override public int neighborInfo(BlockGetter g, int x, int y, int z, int meta) { return 0; }

        @Override public List<Box> boxes(int meta, int conn) {
            int q = meta & 3;
            boolean open = (meta & 4) != 0;
            String t = textures[0];
            List<Box> l = new ArrayList<>();
            l.add(new Box(0, 5, 7, 2, 16, 9).all(t));
            l.add(new Box(14, 5, 7, 16, 16, 9).all(t));
            if (!open) {
                l.add(new Box(6, 6, 7, 8, 15, 9).all(t));
                l.add(new Box(8, 6, 7, 10, 15, 9).all(t));
                l.add(new Box(2, 6, 7, 6, 9, 9).all(t));
                l.add(new Box(2, 12, 7, 6, 15, 9).all(t));
                l.add(new Box(10, 6, 7, 14, 9, 9).all(t));
                l.add(new Box(10, 12, 7, 14, 15, 9).all(t));
            } else {
                l.add(new Box(0, 6, 13, 2, 15, 15).all(t));
                l.add(new Box(14, 6, 13, 16, 15, 15).all(t));
                l.add(new Box(0, 6, 9, 2, 9, 13).all(t));
                l.add(new Box(0, 12, 9, 2, 15, 13).all(t));
                l.add(new Box(14, 6, 9, 16, 9, 13).all(t));
                l.add(new Box(14, 12, 9, 16, 15, 13).all(t));
            }
            return rotated(l, q);
        }

        @Override public List<float[]> collisionBoxes(int meta, int conn) {
            if ((meta & 4) != 0) return List.of();
            boolean x = (meta & 1) == 0;
            return List.of(x ? new float[]{0, 0, 0.375f, 1, 1.5f, 0.625f} : new float[]{0.375f, 0, 0, 0.625f, 1.5f, 1});
        }

        @Override public int placementMeta(int face, float hitY, float yaw) { return lookQ(yaw); }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            int nm = meta ^ 4;
            if ((nm & 4) != 0) nm = (nm & ~3) | lookQ(s.player.yaw); // opens away from the player
            s.world.setBlock(x, y, z, this, nm);
            derpcraft.audio.Sfx.at(s, (nm & 4) != 0 ? "block/fence_gate/open" : "block/fence_gate/close", x + 0.5, y + 0.5, z + 0.5, 1f);
            return true;
        }

        @Override public void setOpen(World w, int x, int y, int z, boolean open) {
            int m = w.getMeta(x, y, z);
            int nm = open ? m | 4 : m & ~4;
            if (nm != m) w.setBlock(x, y, z, this, nm);
        }
    }

    // ------------------------------------------------------------------------------------------ wall attached
    /** Thin block on a wall: ladder or wall sign geometry; meta = quarter turn from north. */
    public static class LadderBlock extends Block {
        LadderBlock() {
            super("ladder");
            tex(this, "ladder");
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; climbable = true; needsSupport = false;
            effectiveTool = Item.ToolType.AXE;
        }

        @Override public List<Box> boxes(int meta, int conn) {
            Box b = new Box(0, 0, 0, 16, 16, 1).all("ladder").only(Dir.NORTH, Dir.SOUTH).noCull();
            return List.of(b.rotateY(meta & 3));
        }

        @Override public List<float[]> collisionBoxes(int meta, int conn) {
            float[] b = switch (wallDir(meta)) {
                case Dir.NORTH -> new float[]{0, 0, 0, 1, 1, 0.1875f};
                case Dir.SOUTH -> new float[]{0, 0, 0.8125f, 1, 1, 1};
                case Dir.WEST -> new float[]{0, 0, 0, 0.1875f, 1, 1};
                default -> new float[]{0.8125f, 0, 0, 1, 1, 1};
            };
            return List.of(b);
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) { return collisionBoxes(meta, conn); }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
            int d = wallDir(meta);
            return w.getBlock(x + Dir.DX[d], y, z + Dir.DZ[d]).isOpaqueCube();
        }

        @Override public int placementMeta(int face, float hitY, float yaw) {
            if (face < 2) return qWall(Dir.HORIZONTAL[(lookQ(yaw)) & 3]);
            return qWall(Dir.OPPOSITE[face]);
        }
    }

    public static class VineBlock extends Block {
        VineBlock() {
            super("vine");
            tex(this, "vine");
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; solid = false; climbable = true; replaceable = true;
            tint = Tint.FOLIAGE; hardness = 0.2f; effectiveTool = Item.ToolType.SHEARS; tab = "decoration";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            List<Box> l = new ArrayList<>();
            int[] dirs = {Dir.SOUTH, Dir.WEST, Dir.NORTH, Dir.EAST};
            for (int i = 0; i < 4; i++) {
                if ((meta & (1 << i)) == 0) continue;
                Box b = new Box(0, 0, 1, 16, 16, 1).all("vine").only(Dir.NORTH, Dir.SOUTH).noCull().tint();
                l.add(b.rotateY(qWall(dirs[i])));
            }
            if (l.isEmpty()) l.add(new Box(0, 0, 1, 16, 16, 1).all("vine").only(Dir.NORTH, Dir.SOUTH).noCull().tint());
            return l;
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{0, 0, 0, 1, 1, 1}); }

        @Override public int placementMeta(int face, float hitY, float yaw) {
            int wall = face >= 2 ? Dir.OPPOSITE[face] : Dir.HORIZONTAL[lookQ(yaw)];
            return switch (wall) { case Dir.SOUTH -> 1; case Dir.WEST -> 2; case Dir.NORTH -> 4; default -> 8; };
        }

        @Override public List<ItemStack> drops(int meta, Random r) { return List.of(); }
    }

    // ------------------------------------------------------------------------------------------ redstone-lite
    public static class LeverBlock extends Block {
        LeverBlock() {
            super("lever");
            tex(this, "cobblestone");
            textures[Dir.UP] = "lever";
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; solid = false; needsSupport = true;
        }

        /** The handle is tilted 45 degrees like in vanilla; boxes are axis-aligned, so it is a 1 px staircase of 2x2 boxes. */
        private static void handle(List<Box> l, boolean floor, int dir) {
            for (int i = 0; i < 7; i++) {
                Box b = floor ? new Box(7, 3 + i, 7 + dir * i, 9, 5 + i, 9 + dir * i)
                              : new Box(7, 7 + dir * i, 3 + i, 9, 9 + dir * i, 5 + i);
                for (int f = 0; f < 6; f++) b.face(f, "lever", 7, 14 - i, 9, 16 - i);
                if (i == 6) b.face(Dir.UP, "lever", 7, 6, 9, 8);
                l.add(b.noCull());
            }
        }

        @Override public List<Box> boxes(int meta, int conn) {
            boolean on = (meta & 8) != 0;
            int m = meta & 7;
            List<Box> l = new ArrayList<>();
            if (m >= 4) { // on the floor
                l.add(new Box(5, 0, 4, 11, 3, 12).all("cobblestone"));
                handle(l, true, on ? -1 : 1);
                return rotated(l, m == 5 ? 1 : 0);
            }
            l.add(new Box(5, 4, 0, 11, 12, 3).all("cobblestone"));
            handle(l, false, on ? 1 : -1);   // on: handle up, off: handle down
            return rotated(l, m);
        }

        @Override public int placementMeta(int face, float hitY, float yaw) {
            if (face >= 2) return qWall(Dir.OPPOSITE[face]);
            return (lookQ(yaw) & 1) == 0 ? 4 : 5;
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            boolean on = (meta & 8) == 0;
            s.world.setBlock(x, y, z, this, meta ^ 8);
            derpcraft.audio.Sfx.at(s, "random/click", x + 0.5, y + 0.5, z + 0.5, 0.8f, on ? 0.6f : 0.5f);
            power(s.world, x, y, z, on);
            return true;
        }
    }

    public static class ButtonBlock extends Block {
        final int pressTicks;

        ButtonBlock(String name, String tex, int pressTicks) {
            super(name);
            tex(this, tex);
            this.pressTicks = pressTicks;
            shape = Shape.CUSTOM; lightOpacity = 0; solid = false; needsSupport = true; tab = "redstone";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            boolean pressed = (meta & 8) != 0;
            int m = meta & 7;
            int depth = pressed ? 1 : 2;
            if (m == 4) return List.of(new Box(5, 0, 6, 11, depth, 10).all(textures[0]));
            if (m == 5) return List.of(new Box(5, 16 - depth, 6, 11, 16, 10).all(textures[0]));
            return List.of(new Box(5, 6, 0, 11, 10, depth).all(textures[0]).rotateY(m));
        }

        @Override public int placementMeta(int face, float hitY, float yaw) {
            if (face == Dir.UP) return 4;
            if (face == Dir.DOWN) return 5;
            return qWall(Dir.OPPOSITE[face]);
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if ((meta & 8) != 0) return true;
            s.world.setBlock(x, y, z, this, meta | 8);
            derpcraft.audio.Sfx.at(s, name.contains("wood") ? "random/wood_click" : "random/click", x + 0.5, y + 0.5, z + 0.5, 0.8f, 0.9f);
            power(s.world, x, y, z, true);
            s.world.scheduleTick(x, y, z, pressTicks);
            return true;
        }

        @Override public void scheduledTick(World w, int x, int y, int z, int meta) {
            if ((meta & 8) == 0) return;
            w.setBlock(x, y, z, this, meta & ~8);
            power(w, x, y, z, false);
        }
    }

    public static class PlateBlock extends Block {
        PlateBlock(String name, String tex) {
            super(name);
            tex(this, tex);
            shape = Shape.CUSTOM; lightOpacity = 0; solid = false; needsSupport = true; tab = "redstone";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            return List.of(new Box(1, 0, 1, 15, (meta & 1) != 0 ? 1 : 1, 15).all(textures[0]));
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{1 / 16f, 0, 1 / 16f, 15 / 16f, 1 / 16f, 15 / 16f}); }

        /** Called by the player each tick while standing on it. */
        public void stepOn(World w, int x, int y, int z) {
            int m = w.getMeta(x, y, z);
            lastStep = w.gameTime;
            if ((m & 1) == 0) { w.setBlock(x, y, z, this, 1); derpcraft.audio.Sfx.click(x, y, z); power(w, x, y, z, true); w.scheduleTick(x, y, z, 20); }
        }

        @Override public void scheduledTick(World w, int x, int y, int z, int meta) {
            if ((meta & 1) == 0) return;
            if (w.gameTime - lastStep < 19) { w.scheduleTick(x, y, z, 20); return; }
            w.setBlock(x, y, z, this, 0);
            power(w, x, y, z, false);
        }

        public long lastStep;
    }

    // ------------------------------------------------------------------------------------------ farming
    public static class FarmlandBlock extends Block {
        FarmlandBlock() {
            super("farmland");
            tex(this, "dirt");
            textures[Dir.UP] = "farmland_dry";
            shape = Shape.CUSTOM; lightOpacity = 15; inInventory = false; hardness = 0.6f;
            effectiveTool = Item.ToolType.SHOVEL; dropItem = "dirt";
        }

        @Override public String textureName(int face, int meta) { return face == Dir.UP ? (meta > 0 ? "farmland_wet" : "farmland_dry") : "dirt"; }

        @Override public List<Box> boxes(int meta, int conn) { return List.of(new Box(0, 0, 0, 16, 15, 16).tex(this, meta)); }

        @Override public boolean ticksRandomly() { return true; }

        @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) {
            boolean water = false;
            for (int dx = -4; dx <= 4 && !water; dx++)
                for (int dz = -4; dz <= 4 && !water; dz++)
                    for (int dy = 0; dy <= 1; dy++) if (w.getBlock(x + dx, y + dy, z + dz) == Blocks.WATER) { water = true; break; }
            if (water) { if (meta != 7) w.setBlock(x, y, z, this, 7); }
            else if (meta > 0) w.setBlock(x, y, z, this, meta - 1);
            else if (!(w.getBlock(x, y + 1, z) instanceof CropBlock)) w.setBlock(x, y, z, Blocks.DIRT, 0);
        }
    }

    public static class CropBlock extends Block {
        final String texPrefix;
        final int maxAge, stages;
        final String product, seed;

        CropBlock(String name, String texPrefix, int maxAge, int stages, String product, String seed) {
            super(name);
            this.texPrefix = texPrefix; this.maxAge = maxAge; this.stages = stages; this.product = product; this.seed = seed;
            tex(this, texPrefix + "0");
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; solid = false; inInventory = false; hardness = 0;
            needsSupport = true;
        }

        int stage(int meta) { return Math.min(stages - 1, Math.min(meta, maxAge) * stages / (maxAge + 1)); }

        @Override public String textureName(int face, int meta) {
            int s = Math.min(meta, maxAge) == maxAge ? stages - 1 : stage(meta);
            return texPrefix + s;
        }

        @Override public List<Box> boxes(int meta, int conn) {
            String t = textureName(0, meta);
            return List.of(
                    new Box(4, 0, 0, 4, 16, 16).all(t).only(Dir.WEST, Dir.EAST).noCull(),
                    new Box(12, 0, 0, 12, 16, 16).all(t).only(Dir.WEST, Dir.EAST).noCull(),
                    new Box(0, 0, 4, 16, 16, 4).all(t).only(Dir.NORTH, Dir.SOUTH).noCull(),
                    new Box(0, 0, 12, 16, 16, 12).all(t).only(Dir.NORTH, Dir.SOUTH).noCull());
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) {
            float h = 0.125f + 0.875f * Math.min(meta, maxAge) / maxAge;
            return List.of(new float[]{0, 0, 0, 1, h, 1});
        }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
            return w.getBlock(x, y - 1, z) instanceof FarmlandBlock;
        }

        @Override public boolean ticksRandomly() { return true; }

        @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) {
            if (meta < maxAge && w.getSky(x, y + 1, z) >= 9 || meta < maxAge && w.getBlockLight(x, y, z) >= 9) {
                int moist = w.getMeta(x, y - 1, z);
                if (r.nextInt(moist > 0 ? 3 : 6) == 0) w.setBlock(x, y, z, this, meta + 1);
            }
        }

        public boolean grow(World w, int x, int y, int z, int meta, Random r) {
            if (meta >= maxAge) return false;
            w.setBlock(x, y, z, this, Math.min(maxAge, meta + 2 + r.nextInt(4)));
            return true;
        }

        @Override public List<ItemStack> drops(int meta, Random r) {
            List<ItemStack> out = new ArrayList<>();
            Item s = Items.byName(seed), p = Items.byName(product);
            if (meta >= maxAge) {
                if (p != null) out.add(new ItemStack(p, p == s ? 2 + r.nextInt(3) : 1));
                if (s != null && s != p) out.add(new ItemStack(s, 1 + r.nextInt(3)));
            } else if (s != null) out.add(new ItemStack(s, 1));
            return out;
        }
    }

    public static class SaplingBlock extends Block {
        final int kind; // 0 oak, 1 spruce, 2 birch, 3 jungle, 4 acacia, 5 dark oak

        SaplingBlock(String name, String tex, int kind) {
            super(name);
            this.kind = kind;
            tex(this, tex);
            shape = Shape.CROSS; layer = Layer.CUTOUT; solid = false; lightOpacity = 0; hardness = 0; needsSupport = true; tab = "decoration";
        }

        @Override public boolean ticksRandomly() { return true; }

        @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) {
            if (w.getSky(x, y + 1, z) >= 9 && r.nextInt(7) == 0) {
                if (meta == 0) w.setBlock(x, y, z, this, 1);
                else grow(w, x, y, z, r);
            }
        }

        public void grow(World w, int x, int y, int z, Random r) {
            w.setBlock(x, y, z, 0);
            TerrainGenerator.Sink sink = (bx, by, bz, st, replaceSolid) -> {
                Block cur = w.getBlock(bx, by, bz);
                if (cur.isAir() || cur.replaceable || cur.shape == Shape.CROSS || (replaceSolid && cur.layer == Layer.CUTOUT))
                    w.setBlock(bx, by, bz, st);
            };
            if (kind == 1) TerrainGenerator.spruce(sink, x, y, z, r);
            else TerrainGenerator.oak(sink, x, y, z, r, kind == 2);
        }
    }

    public static class GrassBlock extends Block {
        GrassBlock() { super("grass"); }

        @Override public boolean ticksRandomly() { return true; }

        @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) {
            if (w.getBlock(x, y + 1, z).isOpaqueCube()) { w.setBlock(x, y, z, Blocks.DIRT, 0); return; }
            if (w.getSky(x, y + 1, z) < 9 && w.getBlockLight(x, y + 1, z) < 9) return;
            for (int i = 0; i < 4; i++) {
                int bx = x + r.nextInt(3) - 1, by = y + r.nextInt(5) - 3, bz = z + r.nextInt(3) - 1;
                if (w.getBlock(bx, by, bz) == Blocks.DIRT && w.getMeta(bx, by, bz) == 0 && !w.getBlock(bx, by + 1, bz).isOpaqueCube()
                        && w.getSky(bx, by + 1, bz) >= 4) w.setBlock(bx, by, bz, this, 0);
            }
        }
    }

    public static class DoublePlantBlock extends Block {
        final String base;

        DoublePlantBlock(String name, String texName, Tint tint) {
            super(name);
            this.base = "double_plant_" + texName;
            tex(this, base + "_bottom");
            textures[Dir.UP] = base + "_top";
            shape = Shape.CROSS; layer = Layer.CUTOUT; solid = false; lightOpacity = 0; hardness = 0; needsSupport = true;
            this.tint = tint; tab = "decoration"; replaceable = tint != Tint.NONE;
        }

        @Override public String textureName(int face, int meta) { return (meta & 8) != 0 ? base + "_top" : base + "_bottom"; }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) { return w.getBlock(x, y + 1, z).replaceable; }

        @Override public void onPlaced(World w, int x, int y, int z, int meta) { w.setBlock(x, y + 1, z, this, 8); }

        @Override public void onRemoved(World w, int x, int y, int z, int meta) {
            int oy = (meta & 8) != 0 ? y - 1 : y + 1;
            if (w.getBlock(x, oy, z) == this) w.setBlock(x, oy, z, 0);
        }

        @Override public List<ItemStack> drops(int meta, Random r) {
            if (tint != Tint.NONE) return List.of();
            return super.drops(meta, r);
        }
    }

    // ------------------------------------------------------------------------------------------ containers
    public static class ChestBlock extends Block {
        public final String texture;

        ChestBlock(String name, String texture) {
            super(name);
            this.texture = texture;
            tex(this, "planks_oak");
            shape = Shape.ENTITY; lightOpacity = 0; effectiveTool = Item.ToolType.AXE; tab = "decoration";
        }

        @Override public List<Box> boxes(int meta, int conn) { return List.of(new Box(1, 0, 1, 15, 14, 15).all("planks_oak")); }

        @Override public BlockEntity createBlockEntity() {
            return name.equals("ender_chest") ? new ContainerEntity("ender_chest", 0) : new ContainerEntity("chest", 27);
        }

        @Override public int placementMeta(int face, float hitY, float yaw) { return (lookQ(yaw) + 2) & 3; }

        /** Neighbouring chest of the same kind that forms a double chest, or null. Returns {x,z}. */
        public int[] partner(BlockGetter w, int x, int y, int z) {
            if (name.equals("ender_chest")) return null;
            int[] dirs = {Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST};
            int meta = w.getMeta(x, y, z) & 3;
            for (int d : dirs) {
                int nx = x + Dir.DX[d], nz = z + Dir.DZ[d];
                if (w.getBlock(nx, y, nz) == this && (w.getMeta(nx, y, nz) & 3) == meta) {
                    // only side by side (not front/back)
                    boolean alongX = meta == 0 || meta == 2;
                    if (alongX == (Dir.DX[d] != 0)) return new int[]{nx, nz};
                }
            }
            return null;
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if (s.world.getBlock(x, y + 1, z).isOpaqueCube()) return true;
            if (name.equals("ender_chest")) { s.openEnderChest((ContainerEntity) s.world.getBlockEntity(x, y, z)); return true; }
            ContainerEntity a = (ContainerEntity) s.world.getBlockEntity(x, y, z);
            int[] p = partner(s.world, x, y, z);
            ContainerEntity b = p == null ? null : (ContainerEntity) s.world.getBlockEntity(p[0], y, p[1]);
            if (b != null) {
                // the north/west chest is the top half of the double chest GUI
                boolean aFirst = p[0] > x || p[1] > z;
                s.openDoubleChest(aFirst ? a : b, aFirst ? b : a);
            } else s.openChest(a, displayName);
            return true;
        }
    }

    public static class StorageBlock extends Block {
        final String kind;

        StorageBlock(String name, String kind) {
            super(name);
            this.kind = kind;
            requiresTool = true; effectiveTool = Item.ToolType.PICKAXE; hardness = 3.5f; tab = "redstone";
        }

        @Override public BlockEntity createBlockEntity() { return BlockEntity.create(kind); }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            BlockEntity be = s.world.getBlockEntity(x, y, z);
            if (be instanceof FurnaceEntity f) s.openFurnace(f);
            else if (be instanceof ContainerEntity c) s.openStorage(c, displayName);
            return be != null;
        }
    }

    public static class CraftingTableBlock extends Block {
        CraftingTableBlock() { super("crafting_table"); }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) { s.openCrafting(); return true; }
    }

    // ------------------------------------------------------------------------------------------ signs & beds
    public static class SignBlock extends Block {
        public final boolean wall;

        SignBlock(String name, boolean wall) {
            super(name);
            this.wall = wall;
            tex(this, "planks_oak");
            shape = Shape.ENTITY; solid = false; lightOpacity = 0; inInventory = false; hardness = 1; needsSupport = !wall;
            effectiveTool = Item.ToolType.AXE; dropItem = "sign";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            if (!wall) return List.of(new Box(4, 0, 4, 12, 16, 12).all("planks_oak"));
            return List.of(new Box(0, 4, 0, 16, 12, 2).all("planks_oak").rotateY(meta & 3));
        }

        @Override public BlockEntity createBlockEntity() { return new SignEntity(); }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) { return false; }
    }

    /**
     * Item frame (1.12): hangs on any solid block face, shows one item that turns by 45 degrees with each right click.
     * Meta = the direction from the frame to the block it hangs on. A punch takes the item out first, the next breaks the frame.
     */
    public static class ItemFrameBlock extends Block {
        ItemFrameBlock() {
            super("item_frame");
            tex(this, "planks_oak");
            shape = Shape.ENTITY; solid = false; lightOpacity = 0; inInventory = false; hardness = 0;
            dropItem = "item_frame";
        }

        /** Direction (Dir) from the frame to the block it hangs on. */
        public static int back(int meta) { return Math.min(5, meta & 7); }

        @Override public java.util.List<String> extraTextures() { return java.util.List.of("itemframe_background"); }

        /** The frame is 12 x 12 pixels and 2 deep, flat against its wall. */
        @Override public List<Box> boxes(int meta, int conn) {
            Box b = switch (back(meta)) {
                case Dir.DOWN -> new Box(2, 0, 2, 14, 2, 14);
                case Dir.UP -> new Box(2, 14, 2, 14, 16, 14);
                case Dir.NORTH -> new Box(2, 2, 0, 14, 14, 2);
                case Dir.SOUTH -> new Box(2, 2, 14, 14, 14, 16);
                case Dir.WEST -> new Box(0, 2, 2, 2, 14, 14);
                default -> new Box(14, 2, 2, 16, 14, 14);
            };
            return List.of(b.all("planks_oak"));
        }

        @Override public int placementMeta(int face, float hitY, float yaw) { return Dir.OPPOSITE[face]; }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
            int d = back(meta);
            Block n = w.getBlock(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]);
            return n.solid && !n.isAir();
        }

        @Override public BlockEntity createBlockEntity() { return new ItemFrameEntity(); }

        /** Empty: puts one of the held item in. Filled: turns it. */
        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if (!(s.world.getBlockEntity(x, y, z) instanceof ItemFrameEntity fe)) return false;
            if (fe.item() != null) {
                fe.rotation = (fe.rotation + 1) & 7;
                fe.markDirty();
                derpcraft.audio.Sfx.at(s, "entity/itemframe/rotate_item", x + 0.5, y + 0.5, z + 0.5, 0.8f);
                return true;
            }
            ItemStack held = s.inv.held();
            if (held == null) return false;
            fe.rotation = 0;
            fe.items.set(0, held.copy(1));
            derpcraft.audio.Sfx.at(s, "entity/itemframe/add_item", x + 0.5, y + 0.5, z + 0.5, 0.8f);
            s.consumeHeld(1);
            return true;
        }
    }

    public static class BedBlock extends Block {
        public final int color;

        BedBlock(String name, int color) {
            super(name);
            this.color = color;
            tex(this, "planks_oak");
            shape = Shape.ENTITY; lightOpacity = 0; inInventory = false; hardness = 0.2f; tab = "decoration";
        }

        @Override public List<Box> boxes(int meta, int conn) { return List.of(new Box(0, 0, 0, 16, 9, 16).all("planks_oak")); }

        @Override public int placementMeta(int face, float hitY, float yaw) { return lookQ(yaw); }

        @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
            int d = Dir.HORIZONTAL[meta & 3];
            return w.getBlock(x + Dir.DX[d], y, z + Dir.DZ[d]).replaceable && w.getBlock(x, y - 1, z).solid;
        }

        @Override public void onPlaced(World w, int x, int y, int z, int meta) {
            int d = Dir.HORIZONTAL[meta & 3];
            w.setBlock(x + Dir.DX[d], y, z + Dir.DZ[d], this, (meta & 3) | 8);
        }

        @Override public void onRemoved(World w, int x, int y, int z, int meta) {
            int d = Dir.HORIZONTAL[meta & 3];
            int s = (meta & 8) != 0 ? -1 : 1;
            int ox = x + Dir.DX[d] * s, oz = z + Dir.DZ[d] * s;
            if (w.getBlock(ox, y, oz) == this) w.setBlock(ox, y, oz, 0);
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) { s.trySleep(x, y, z); return true; }

        @Override public List<ItemStack> drops(int meta, Random r) {
            Item it = Items.byName(name);
            return it == null ? List.of() : List.of(new ItemStack(it));
        }
    }

    // ------------------------------------------------------------------------------------------ decorative models
    public static class ModelBlock extends Block {
        private final java.util.function.IntFunction<List<Box>> model;
        public boolean rotates;

        ModelBlock(String name, java.util.function.IntFunction<List<Box>> model) {
            super(name);
            this.model = model;
            shape = Shape.CUSTOM; lightOpacity = 0;
        }

        @Override public List<Box> boxes(int meta, int conn) { return model.apply(meta); }

        @Override public int placementMeta(int face, float hitY, float yaw) { return rotates ? (lookQ(yaw) + 1) & 3 : super.placementMeta(face, hitY, yaw); }
    }

    public static class RailBlock extends Block {
        final String straight, turned;

        RailBlock(String name, String straight, String turned) {
            super(name);
            this.straight = straight; this.turned = turned;
            tex(this, straight);
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; solid = false; lightOpacity = 0; hardness = 0.7f; needsSupport = true; tab = "transport";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            Box b = new Box(0, 1, 0, 16, 1, 16).only().noCull();
            b.tex[Dir.UP] = meta >= 6 && turned != null ? turned : straight;
            b.tex[Dir.DOWN] = b.tex[Dir.UP];
            if (meta == 1) { b.rotate(Dir.UP, 90); b.rotate(Dir.DOWN, 90); }
            if (meta >= 6 && turned != null) { b.rotate(Dir.UP, (meta - 6) * 90); b.rotate(Dir.DOWN, (meta - 6) * 90); }
            return List.of(b);
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{0, 0, 0, 1, 0.125f, 1}); }

        @Override public int placementMeta(int face, float hitY, float yaw) { return lookQ(yaw) & 1; }
    }

    /**
     * Fire. Meta: bits 0-2 = age (0..7), bit 3 = eternal (admin flint: burns for ever, never spreads, never destroys anything).
     * Ticks every ~1-2 s: gets older, burns flammable neighbours away (or spreads into them), spreads through the air next to
     * fuel, and leaves soot on every solid block next to it (also on stone: those never burn). The flames are 8 copies of
     * the fire texture whose animation starts at different moments (chosen by position), so many fires do not look alike.
     */
    /** TNT: lit with flint and steel (or by fire / another explosion), it turns into a primed TNT that explodes after 4 seconds. */
    public static class TntBlock extends Block {
        TntBlock() {
            super("tnt");
            Arrays.fill(textures, "tnt_side");
            textures[Dir.UP] = "tnt_top"; textures[Dir.DOWN] = "tnt_bottom";
            category = "Redstone";
        }
    }

    public static class FireBlock extends Block {
        public static FireBlock INSTANCE;
        public static final int ETERNAL = 8;
        private static final String BASE = "fire_layer_0";

        FireBlock() {
            super("fire");
            tex(this, BASE);
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; solid = false; lightOpacity = 0; lightEmission = 15;
            replaceable = true; hardness = 0; inInventory = false; dropItem = "";
            INSTANCE = this;
        }

        @Override public List<String> extraTextures() {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < 8; i++) l.add(BASE + "#" + i);
            return l;
        }

        @Override public boolean wantsEntityPass() { return true; }
        @Override public boolean positionPhased() { return true; }
        @Override public boolean ticksRandomly() { return true; }

        @Override public List<Box> boxes(int meta, int conn) {
            String t = BASE + "#" + (conn & 7);
            return List.of(
                    new Box(0, 0, 1, 16, 16, 1).all(t).only(Dir.NORTH, Dir.SOUTH).noCull(),
                    new Box(0, 0, 15, 16, 16, 15).all(t).only(Dir.NORTH, Dir.SOUTH).noCull(),
                    new Box(1, 0, 0, 1, 16, 16).all(t).only(Dir.WEST, Dir.EAST).noCull(),
                    new Box(15, 0, 0, 15, 16, 16).all(t).only(Dir.WEST, Dir.EAST).noCull(),
                    new Box(8, 0, 0, 8, 16, 16).all(t).only(Dir.WEST, Dir.EAST).noCull(),
                    new Box(0, 0, 8, 16, 16, 8).all(t).only(Dir.NORTH, Dir.SOUTH).noCull());
        }

        @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{0, 0, 0, 1, 1 / 16f, 1}); }

        // ---------------------------------------------------------------- flammability (like Minecraft's BlockFire)
        /** How easily the block burns away (0 = not flammable). */
        public static int burnOdds(Block b) {
            if (b == null || b.isAir()) return 0;
            String n = b.name;
            if (n.equals("fire")) return 0;
            if (n.endsWith("_leaves") || n.endsWith("_wool") || n.equals("bookshelf")) return n.equals("bookshelf") ? 20 : 60;
            if (n.endsWith("_log") || n.equals("coal_block")) return 5;
            if (n.endsWith("_planks") || n.equals("crafting_table") || n.equals("chest") || n.equals("trapped_chest") || n.equals("ladder")
                    || n.equals("jukebox") || n.equals("note_block") || n.equals("bookshelf")) return 20;
            if (isWoodName(n) && (n.endsWith("_slab") || n.endsWith("_stairs") || n.endsWith("_fence") || n.endsWith("_fence_gate")
                    || n.endsWith("_door") || n.endsWith("_trapdoor") || n.endsWith("_sign"))) return 20;
            if (n.endsWith("_carpet") || n.equals("hay_block")) return 20;
            if (b.shape == Shape.CROSS && b.layer == Layer.CUTOUT && !n.contains("mushroom") && !n.contains("sapling") && !n.equals("fire")) return 100;   // grass, flowers, ferns, bushes
            if (n.equals("vine")) return 100;
            if (n.equals("tnt")) return 15;
            return 0;
        }

        /** How strongly the block pulls fire towards itself (flames spread into air next to it). */
        public static int spreadOdds(Block b) {
            if (b == null || b.isAir()) return 0;
            String n = b.name;
            if (n.endsWith("_leaves") || n.endsWith("_wool")) return 30;
            if (n.equals("bookshelf")) return 30;
            if (n.endsWith("_log") || n.equals("coal_block")) return 5;
            if (n.endsWith("_carpet") || n.equals("hay_block")) return 60;
            if (burnOdds(b) >= 100) return 60;
            return burnOdds(b) > 0 ? 5 : 0;
        }

        private static boolean isWoodName(String n) {
            return n.startsWith("oak") || n.startsWith("spruce") || n.startsWith("birch") || n.startsWith("jungle") || n.startsWith("acacia") || n.startsWith("dark_oak");
        }

        // ---------------------------------------------------------------- lighting fires
        private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

        /** Sets fire at x,y,z (air or something replaceable). 'eternal' = admin fire. Returns true if there is fire afterwards. */
        public static boolean ignite(World w, int x, int y, int z, boolean eternal) {
            FireBlock f = INSTANCE;
            if (f == null) return false;
            Block at = w.getBlock(x, y, z);
            if (at == f) {
                int meta = w.getMeta(x, y, z);
                if (eternal && (meta & ETERNAL) == 0) w.setBlock(x, y, z, f, ETERNAL);
                schedule(w, x, y, z, 1);
                return true;
            }
            if (!(at.isAir() || at.replaceable && at.shape != Shape.LIQUID)) return false;
            if (!w.setBlock(x, y, z, f, eternal ? ETERNAL : 0)) return false;
            schedule(w, x, y, z, 5 + w.random.nextInt(10));
            return true;
        }

        private static void schedule(World w, int x, int y, int z, int delay) {
            long k = key(x, y, z);
            Long due = w.fireDue.get(k);
            if (due != null && due >= w.gameTime) return;   // already waiting
            w.fireDue.put(k, w.gameTime + delay);
            w.scheduleTick(x, y, z, delay);
        }

        @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) { schedule(w, x, y, z, 1 + r.nextInt(20)); }

        @Override public void scheduledTick(World w, int x, int y, int z, int meta) {
            w.fireDue.remove(key(x, y, z));
            if (w.getBlock(x, y, z) != this) return;
            Random r = w.random;
            boolean eternal = (meta & ETERNAL) != 0;
            depositSoot(w, x, y, z, r, eternal ? 0.45f : 0.65f);
            if (eternal) { schedule(w, x, y, z, 30 + r.nextInt(30)); return; }
            Session ses = Session.current;
            boolean spreadOn = ses == null || ses.game.settings.fireSpread;
            int age = meta & 7;
            boolean fuel = false;
            for (int d = 0; d < 6 && !fuel; d++) fuel = burnOdds(w.getBlock(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d])) > 0;
            Block below = w.getBlock(x, y - 1, z);
            if (!fuel && !below.isOpaqueCube()) { w.setBlock(x, y, z, 0); return; }       // nothing to stand on or to burn
            if (age < 7 && r.nextInt(3) == 0) { age++; w.setBlock(x, y, z, this, age); }
            if (age >= 7 && r.nextInt(4) == 0 && (!fuel || burnOdds(below) == 0)) { w.setBlock(x, y, z, 0); return; }   // burnt out
            if (spreadOn) {
                // burn what is next to it (or let the flames take over its place)
                for (int d = 0; d < 6; d++) catchFire(w, x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d], d >= 2 ? 150 : 125, r, age);
                // jump through the air to cells beside fuel
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        for (int dy = -1; dy <= 4; dy++) {
                            if (dx == 0 && dz == 0 && dy == 0) continue;
                            int nx = x + dx, ny = y + dy, nz = z + dz;
                            if (!w.getBlock(nx, ny, nz).isAir()) continue;
                            int enc = 0;
                            for (int d = 0; d < 6; d++) enc = Math.max(enc, spreadOdds(w.getBlock(nx + Dir.DX[d], ny + Dir.DY[d], nz + Dir.DZ[d])));
                            if (enc <= 0) continue;
                            int k = dy > 1 ? 100 + (dy - 1) * 100 : 100;
                            int l = (enc + 40) * 2 / (age + 30);
                            if (l > 0 && r.nextInt(k) <= l) {
                                w.setBlock(nx, ny, nz, this, Math.min(7, age + r.nextInt(5) / 4));
                                schedule(w, nx, ny, nz, 5 + r.nextInt(10));
                            }
                        }
            }
            schedule(w, x, y, z, 20 + r.nextInt(15));
        }

        private void catchFire(World w, int x, int y, int z, int chance, Random r, int age) {
            int fl = burnOdds(w.getBlock(x, y, z));
            if (fl <= 0 || r.nextInt(chance) >= fl) return;
            if (w.getBlock(x, y, z) == Blocks.TNT) {         // TNT does not burn away: it goes off
                Session ses = Session.current;
                if (ses != null) ses.primeTnt(x, y, z, 80);
                return;
            }
            if (r.nextInt(age + 10) < 5) {
                w.setBlock(x, y, z, this, Math.min(7, age + r.nextInt(5) / 4));
                schedule(w, x, y, z, 5 + r.nextInt(10));
            } else w.setBlock(x, y, z, 0);                       // burnt away
        }

        /** Soot on the faces of solid blocks that face this fire, and on a ceiling a few blocks above it. */
        private static void depositSoot(World w, int x, int y, int z, Random r, float chance) {
            for (int d = 0; d < 6; d++) {
                int nx = x + Dir.DX[d], ny = y + Dir.DY[d], nz = z + Dir.DZ[d];
                if (w.getBlock(nx, ny, nz).isOpaqueCube() && r.nextFloat() < chance) w.addSoot(nx, ny, nz, 1 << Dir.OPPOSITE[d]);
            }
            for (int dy = 2; dy <= 3; dy++) {
                Block b = w.getBlock(x, y + dy, z);
                if (b.isOpaqueCube()) { if (r.nextFloat() < chance * 0.5f) w.addSoot(x, y + dy, z, 1 << Dir.DOWN); break; }
                if (!b.isAir() && !(b instanceof FireBlock)) break;
            }
        }
    }

    public static class FlowerPotBlock extends Block {
        public static final String[] PLANTS = {"", "poppy", "dandelion", "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
                "red_mushroom", "brown_mushroom", "cactus", "dead_bush", "fern", "acacia_sapling", "roofed_oak_sapling", "blue_orchid", "allium"};

        FlowerPotBlock() {
            super("flower_pot");
            tex(this, "flower_pot");
            shape = Shape.CUSTOM; layer = Layer.CUTOUT; lightOpacity = 0; hardness = 0; inInventory = false; needsSupport = true;
            dropItem = "flower_pot";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            List<Box> l = new ArrayList<>();
            l.add(new Box(5, 0, 5, 6, 6, 11).all("flower_pot"));
            l.add(new Box(10, 0, 5, 11, 6, 11).all("flower_pot"));
            l.add(new Box(6, 0, 5, 10, 6, 6).all("flower_pot"));
            l.add(new Box(6, 0, 10, 10, 6, 11).all("flower_pot"));
            l.add(new Box(6, 0, 6, 10, 4, 10).all("dirt").only(Dir.UP));
            if (meta > 0 && meta < PLANTS.length) {
                Block p = Blocks.byName(PLANTS[meta]);
                if (p != null) {
                    String t = p.textures[Dir.NORTH];
                    if (p == Blocks.CACTUS) l.add(new Box(6, 4, 6, 10, 15, 10).all(t));
                    else {
                        Box a = new Box(8, 4, 4, 8, 16, 12).all(t).only(Dir.WEST, Dir.EAST).noCull();
                        Box b = new Box(4, 4, 8, 12, 16, 8).all(t).only(Dir.NORTH, Dir.SOUTH).noCull();
                        if (p.tint != Tint.NONE) { a.tint(); b.tint(); }
                        l.add(a); l.add(b);
                    }
                }
            }
            return l;
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            ItemStack held = s.heldStack();
            if (meta != 0 || held == null || held.item.block == null) return false;
            for (int i = 1; i < PLANTS.length; i++) {
                if (PLANTS[i].equals(held.item.block.name)) {
                    s.world.setBlock(x, y, z, this, i);
                    s.consumeHeld(1);
                    return true;
                }
            }
            return false;
        }

        @Override public List<ItemStack> drops(int meta, Random r) {
            List<ItemStack> out = new ArrayList<>(super.drops(meta, r));
            if (meta > 0 && meta < PLANTS.length) { Item p = Items.byName(PLANTS[meta]); if (p != null) out.add(new ItemStack(p)); }
            return out;
        }
    }

    public static class CakeBlock extends Block {
        CakeBlock() {
            super("cake");
            tex(this, "cake_side");
            textures[Dir.UP] = "cake_top"; textures[Dir.DOWN] = "cake_bottom";
            shape = Shape.CUSTOM; lightOpacity = 0; hardness = 0.5f; dropItem = ""; tab = "food";
        }

        @Override public List<Box> boxes(int meta, int conn) {
            int bites = Math.min(6, meta);
            Box b = new Box(1 + bites * 2, 0, 1, 15, 8, 15).tex(this, 0);
            if (bites > 0) b.face(Dir.WEST, "cake_inner");
            return List.of(b);
        }

        @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
            if (!s.eat(2, 0.4f, false)) return false;
            if (meta >= 6) s.world.setBlock(x, y, z, 0); else s.world.setBlock(x, y, z, this, meta + 1);
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------ registration
    public static void register() {
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
        String[] planksTex = {"planks_oak", "planks_spruce", "planks_birch", "planks_jungle", "planks_acacia", "planks_big_oak"};
        String[] doorTex = {"door_wood", "door_spruce", "door_birch", "door_jungle", "door_acacia", "door_dark_oak"};

        Blocks.TNT = reg(new TntBlock(), 0f, "redstone");

        // containers & workstations
        reg(new ChestBlock("chest", "normal"), 2.5f, "decoration");
        reg(new ChestBlock("trapped_chest", "trapped"), 2.5f, "redstone");
        Block ender = reg(new ChestBlock("ender_chest", "ender"), 22.5f, "decoration");
        ender.lightEmission = 7; ender.requiresTool = true; ender.effectiveTool = Item.ToolType.PICKAXE; ender.dropItem = "obsidian"; ender.dropMin = ender.dropMax = 8;

        StorageBlock furnace = new StorageBlock("furnace", "furnace");
        furnace.textures[Dir.NORTH] = "furnace_front_off"; furnace.textures[Dir.EAST] = "furnace_side"; furnace.textures[Dir.SOUTH] = "furnace_side";
        furnace.textures[Dir.WEST] = "furnace_side"; furnace.textures[Dir.UP] = "furnace_top"; furnace.textures[Dir.DOWN] = "furnace_top";
        furnace.placement = Placement.FACING;
        reg(furnace, 3.5f, "decoration");
        StorageBlock lit = new StorageBlock("lit_furnace", "furnace");
        System.arraycopy(furnace.textures, 0, lit.textures, 0, 6);
        lit.textures[Dir.NORTH] = "furnace_front_on";
        lit.placement = Placement.FACING; lit.lightEmission = 13; lit.inInventory = false; lit.dropItem = "furnace";
        reg(lit, 3.5f, "decoration");
        for (String[] d : new String[][]{{"dispenser", "dispenser_front_horizontal"}, {"dropper", "dropper_front_horizontal"}}) {
            StorageBlock s = new StorageBlock(d[0], d[0]);
            Arrays.fill(s.textures, "furnace_side");
            s.textures[Dir.NORTH] = d[1]; s.textures[Dir.UP] = "furnace_top"; s.textures[Dir.DOWN] = "furnace_top";
            s.placement = Placement.FACING;
            reg(s, 3.5f, "redstone");
        }
        StorageBlock hopper = new StorageBlock("hopper", "hopper") {
            @Override public List<Box> boxes(int meta, int conn) {
                List<Box> l = new ArrayList<>();
                l.add(new Box(0, 10, 0, 16, 16, 2).all("hopper_outside").face(Dir.UP, "hopper_top"));
                l.add(new Box(0, 10, 14, 16, 16, 16).all("hopper_outside").face(Dir.UP, "hopper_top"));
                l.add(new Box(0, 10, 2, 2, 16, 14).all("hopper_outside").face(Dir.UP, "hopper_top"));
                l.add(new Box(14, 10, 2, 16, 16, 14).all("hopper_outside").face(Dir.UP, "hopper_top"));
                l.add(new Box(2, 10, 2, 14, 11, 14).all("hopper_outside").face(Dir.UP, "hopper_inside"));
                l.add(new Box(4, 4, 4, 12, 10, 12).all("hopper_outside"));
                l.add(new Box(6, 0, 6, 10, 4, 10).all("hopper_outside"));
                return l;
            }
        };
        hopper.shape = Shape.CUSTOM; hopper.lightOpacity = 0;
        reg(hopper, 3, "redstone");

        // doors, trapdoors, gates
        for (int i = 0; i < 6; i++) reg(new DoorBlock(woods[i] + "_door", doorTex[i], false), 3, "redstone");
        reg(new DoorBlock("iron_door", "door_iron", true), 5, "redstone");
        reg(new TrapdoorBlock("oak_trapdoor", "trapdoor", false), 3, "redstone");
        reg(new TrapdoorBlock("iron_trapdoor", "iron_trapdoor", true), 5, "redstone");
        for (int i = 0; i < 6; i++) reg(new FenceGateBlock(woods[i] + "_fence_gate", planksTex[i]), 2, "redstone");

        // wall stuff & redstone-lite
        reg(new LadderBlock(), 0.4f, "decoration");
        reg(new VineBlock(), 0.2f, "decoration");
        reg(new LeverBlock(), 0.5f, "redstone");
        reg(new ButtonBlock("stone_button", "stone", 20), 0.5f, "redstone");
        reg(new ButtonBlock("wooden_button", "planks_oak", 30), 0.5f, "redstone");
        reg(new PlateBlock("stone_pressure_plate", "stone"), 0.5f, "redstone");
        reg(new PlateBlock("wooden_pressure_plate", "planks_oak"), 0.5f, "redstone");
        reg(new PlateBlock("light_weighted_pressure_plate", "gold_block"), 0.5f, "redstone");
        reg(new PlateBlock("heavy_weighted_pressure_plate", "iron_block"), 0.5f, "redstone");
        Block lampOff = Blocks.byName("redstone_lamp");
        Arrays.fill(lampOff.textures, "redstone_lamp_off");
        lampOff.lightEmission = 0; lampOff.tab = "redstone"; lampOff.hardness = 0.3f;
        Block lampOn = new Block("lit_redstone_lamp");
        Arrays.fill(lampOn.textures, "redstone_lamp_on");
        lampOn.lightEmission = 15; lampOn.dropItem = "redstone_lamp";
        reg(lampOn, 0.3f, "redstone");
        Block rTorch = new Block("redstone_torch");
        Arrays.fill(rTorch.textures, "redstone_torch_on");
        rTorch.shape = Shape.TORCH; rTorch.layer = Layer.CUTOUT; rTorch.solid = false; rTorch.lightOpacity = 0;
        rTorch.lightEmission = 7; rTorch.placement = Placement.TORCH; rTorch.needsSupport = true;
        reg(rTorch, 0, "redstone");
        Block rOre = new Block("redstone_ore");
        Arrays.fill(rOre.textures, "redstone_ore");
        rOre.dropItem = "redstone"; rOre.dropMin = 4; rOre.dropMax = 5;
        reg(rOre, 3, "building");
        Block note = new Block("note_block") {
            @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
                int nm = (meta + 1) & 15;
                s.world.setBlock(x, y, z, this, nm);
                derpcraft.audio.Sfx.note(s, s.world.getBlock(x, y - 1, z), nm, x, y, z);
                return true;
            }
        }; Arrays.fill(note.textures, "noteblock"); reg(note, 0.8f, "redstone");
        Block juke = new Block("jukebox"); Arrays.fill(juke.textures, "jukebox_side"); juke.textures[Dir.UP] = "jukebox_top"; reg(juke, 2, "decoration");
        Block wetSponge = new Block("wet_sponge"); Arrays.fill(wetSponge.textures, "sponge_wet"); reg(wetSponge, 0.6f, "building");

        // farming
        reg(new FarmlandBlock(), 0.6f, "decoration");
        reg(new CropBlock("wheat", "wheat_stage_", 7, 8, "wheat", "wheat_seeds"), 0, "materials");
        reg(new CropBlock("carrots", "carrots_stage_", 7, 4, "carrot", "carrot"), 0, "food");
        reg(new CropBlock("potatoes", "potatoes_stage_", 7, 4, "potato", "potato"), 0, "food");
        reg(new CropBlock("beetroots", "beetroots_stage_", 3, 4, "beetroot", "beetroot_seeds"), 0, "food");
        for (String stem : new String[]{"pumpkin_stem", "melon_stem"}) {
            Block s = new Block(stem);
            Arrays.fill(s.textures, "pumpkin_stem_disconnected");
            s.shape = Shape.CROSS; s.layer = Layer.CUTOUT; s.solid = false; s.lightOpacity = 0; s.inInventory = false;
            s.tint = Tint.FIXED; s.fixedTint = 0x30A010; s.needsSupport = true; s.dropItem = stem.equals("pumpkin_stem") ? "pumpkin_seeds" : "melon_seeds";
            reg(s, 0, "materials");
        }

        // plants
        reg(new DoublePlantBlock("sunflower", "sunflower", Tint.NONE), 0, "decoration");
        reg(new DoublePlantBlock("lilac", "syringa", Tint.NONE), 0, "decoration");
        reg(new DoublePlantBlock("double_tallgrass", "grass", Tint.GRASS), 0, "decoration");
        reg(new DoublePlantBlock("large_fern", "fern", Tint.GRASS), 0, "decoration");
        reg(new DoublePlantBlock("rose_bush", "rose", Tint.NONE), 0, "decoration");
        reg(new DoublePlantBlock("peony", "paeonia", Tint.NONE), 0, "decoration");
        Block lily = new ModelBlock("waterlily", m -> List.of(new Box(0, 0, 0, 16, 0, 16).all("waterlily").only(Dir.UP, Dir.DOWN).noCull().tint()));
        Arrays.fill(lily.textures, "waterlily");
        lily.layer = Layer.CUTOUT; lily.tint = Tint.FIXED; lily.fixedTint = 0x208030; lily.displayName = "Lily Pad";
        reg(lily, 0, "decoration");
        Block web = new Block("web");
        Arrays.fill(web.textures, "web");
        web.shape = Shape.CROSS; web.layer = Layer.CUTOUT; web.solid = false; web.lightOpacity = 1; web.displayName = "Cobweb";
        web.requiresTool = true; web.effectiveTool = Item.ToolType.SWORD; web.dropItem = "string";
        reg(web, 4, "decoration");

        // signs, beds, misc models
        reg(new SignBlock("standing_sign", false), 1, "decoration");
        reg(new SignBlock("wall_sign", true), 1, "decoration");
        reg(new ItemFrameBlock(), 0, "decoration");
        for (int c = 0; c < 16; c++) reg(new BedBlock(Blocks.COLOR_NAMES[c] + "_bed", c), 0.2f, "decoration");
        reg(new FireBlock(), 0, "decoration");
        reg(new FlowerPotBlock(), 0, "decoration");
        reg(new CakeBlock(), 0.5f, "food");
        reg(new RailBlock("rail", "rail_normal", "rail_normal_turned"), 0.7f, "transport");
        reg(new RailBlock("golden_rail", "rail_golden_powered", null), 0.7f, "transport").displayName = "Powered Rail";
        reg(new RailBlock("detector_rail", "rail_detector", null), 0.7f, "transport");
        reg(new RailBlock("activator_rail", "rail_activator", null), 0.7f, "transport");

        ModelBlock table = new ModelBlock("enchanting_table", m -> List.of(new Box(0, 0, 0, 16, 12, 16)
                .all("enchanting_table_side").face(Dir.UP, "enchanting_table_top").face(Dir.DOWN, "enchanting_table_bottom")));
        table.lightEmission = 0; table.requiresTool = true; table.effectiveTool = Item.ToolType.PICKAXE;
        Arrays.fill(table.textures, "enchanting_table_side"); table.textures[Dir.UP] = "enchanting_table_top"; table.textures[Dir.DOWN] = "enchanting_table_bottom";
        reg(table, 5, "decoration");
        ModelBlock anvil = new ModelBlock("anvil", m -> rotated(List.of(
                new Box(2, 0, 2, 14, 4, 14).all("anvil_base"),
                new Box(4, 4, 3, 12, 5, 13).all("anvil_base"),
                new Box(6, 5, 4, 10, 10, 12).all("anvil_base"),
                new Box(3, 10, 0, 13, 16, 16).all("anvil_base").face(Dir.UP, "anvil_top_damaged_0")), m & 3));
        anvil.rotates = true; anvil.requiresTool = true; anvil.effectiveTool = Item.ToolType.PICKAXE;
        Arrays.fill(anvil.textures, "anvil_base"); anvil.textures[Dir.UP] = "anvil_top_damaged_0";
        reg(anvil, 5, "decoration");
        ModelBlock cauldron = new ModelBlock("cauldron", m -> List.of(
                new Box(0, 3, 0, 2, 16, 16).all("cauldron_side").face(Dir.UP, "cauldron_top").face(Dir.EAST, "cauldron_inner"),
                new Box(14, 3, 0, 16, 16, 16).all("cauldron_side").face(Dir.UP, "cauldron_top").face(Dir.WEST, "cauldron_inner"),
                new Box(2, 3, 0, 14, 16, 2).all("cauldron_side").face(Dir.UP, "cauldron_top").face(Dir.SOUTH, "cauldron_inner"),
                new Box(2, 3, 14, 14, 16, 16).all("cauldron_side").face(Dir.UP, "cauldron_top").face(Dir.NORTH, "cauldron_inner"),
                new Box(2, 3, 2, 14, 4, 14).all("cauldron_inner").face(Dir.DOWN, "cauldron_bottom"),
                new Box(0, 0, 0, 4, 3, 2).all("cauldron_side"), new Box(0, 0, 2, 2, 3, 4).all("cauldron_side"),
                new Box(12, 0, 0, 16, 3, 2).all("cauldron_side"), new Box(14, 0, 2, 16, 3, 4).all("cauldron_side"),
                new Box(0, 0, 14, 4, 3, 16).all("cauldron_side"), new Box(0, 0, 12, 2, 3, 14).all("cauldron_side"),
                new Box(12, 0, 14, 16, 3, 16).all("cauldron_side"), new Box(14, 0, 12, 16, 3, 14).all("cauldron_side")));
        cauldron.requiresTool = true; cauldron.effectiveTool = Item.ToolType.PICKAXE; cauldron.inInventory = false;
        Arrays.fill(cauldron.textures, "cauldron_side");
        reg(cauldron, 2, "brewing");
        ModelBlock stand = new ModelBlock("brewing_stand", m -> List.of(
                new Box(7, 0, 7, 9, 14, 9).all("brewing_stand").noCull(),
                new Box(9, 0, 5, 15, 2, 11).all("brewing_stand_base"),
                new Box(2, 0, 1, 8, 2, 7).all("brewing_stand_base"),
                new Box(2, 0, 9, 8, 2, 15).all("brewing_stand_base"),
                new Box(8, 0, 0, 8, 16, 16).all("brewing_stand").only(Dir.WEST, Dir.EAST).noCull()));
        stand.layer = Layer.CUTOUT; stand.lightEmission = 1; stand.requiresTool = true; stand.effectiveTool = Item.ToolType.PICKAXE; stand.inInventory = false;
        Arrays.fill(stand.textures, "brewing_stand");
        reg(stand, 0.5f, "brewing");
        ModelBlock beacon = new ModelBlock("beacon", m -> List.of(
                new Box(0, 0, 0, 16, 16, 16).all("glass"),
                new Box(2, 0, 2, 14, 3, 14).all("obsidian"),
                new Box(3, 3, 3, 13, 14, 13).all("beacon")));
        beacon.layer = Layer.CUTOUT; beacon.lightEmission = 15;
        Arrays.fill(beacon.textures, "beacon");
        reg(beacon, 3, "misc");
        ModelBlock rod = new ModelBlock("end_rod", m -> List.of(
                new Box(7, 1, 7, 9, 16, 9).all("end_rod").face(Dir.UP, "end_rod", 2, 0, 4, 2).noCull(),
                new Box(6, 0, 6, 10, 1, 10).all("end_rod")));
        rod.layer = Layer.CUTOUT; rod.lightEmission = 14; rod.solid = false;
        Arrays.fill(rod.textures, "end_rod");
        reg(rod, 0, "decoration");
        reg(new CraftingTableBlock() {{
            Arrays.fill(textures, "crafting_table_side");
            textures[Dir.UP] = "crafting_table_top"; textures[Dir.DOWN] = "planks_oak";
            textures[Dir.NORTH] = "crafting_table_front"; textures[Dir.SOUTH] = "crafting_table_front";
            effectiveTool = Item.ToolType.AXE;
        }}, 2.5f, "decoration");
    }
}
