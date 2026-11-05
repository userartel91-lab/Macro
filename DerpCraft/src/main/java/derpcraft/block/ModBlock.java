package derpcraft.block;

import derpcraft.api.BlockHooks;
import derpcraft.game.BlockContextImpl;
import derpcraft.game.Session;
import derpcraft.item.Inventory;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;
import derpcraft.world.be.ModBlockEntity;

import java.util.*;

/**
 * A block made with {@link derpcraft.api.BlockBuilder}: plain properties plus optional features — storage (1-54 slots,
 * optionally "persistent" so the contents travel with the item), a furnace-like machine, text (signs, screens),
 * saved data, a multi-box model and handler lambdas. Vanilla blocks never pay for any of this.
 */
public class ModBlock extends Block {
    public enum TextStyle {
        /** A thin board: on a wall when placed against a side, on a post when placed on the ground (modern signs). */
        PANEL,
        /** Text on the front face of the block's model (screens, shop fronts, labels). */
        FACE
    }

    // ---------------------------------------------------------------- handlers
    public BlockHooks.Use use;
    public BlockHooks.Simple placed, removed, tick, stepped, scheduled;
    public BlockHooks.RandomTick random;
    public BlockHooks.CanPlace canPlace;

    // ---------------------------------------------------------------- storage
    public int storageSlots;
    public String storageTitle;
    /** Contents stay in the item when the block is broken (suitcases, safes, shulker-like boxes). */
    public boolean persistent;
    public BlockHooks.SlotFilter slotFilter;

    // ---------------------------------------------------------------- machine
    public String machineType;
    public int machineTicks = 200, machineInputs = 1;
    public boolean machineFuel = true;
    public float fuelEfficiency = 1;
    /** Lit / unlit twin blocks of a machine (swapped while working), null when the machine doesn't light up. */
    public ModBlock litVariant, unlitVariant;

    // ---------------------------------------------------------------- text
    public int textLines;
    public int textMaxWidth = 90;
    public int textColor = 0x000000;
    public boolean textGlow, editOnPlace = true;
    public TextStyle textStyle = TextStyle.FACE;
    public float textScale = 1;
    /** FACE text area in pixels of the south-facing model: centre x, centre y, z of the front plane, width. */
    public float textX = 8, textY = 8, textZ = 16, textWidth = 14;

    // ---------------------------------------------------------------- other
    public boolean wantsData;
    /** Custom model (pixel boxes of the south-facing block), rotated with the facing when placement is FACING. */
    public List<Box> model;
    private String beType;

    public ModBlock(String name) { super(name); }

    // ================================================================ features
    public boolean hasEntity() { return storageSlots > 0 || machineType != null || textLines > 0 || wantsData || tick != null; }

    public ModBlock base() { return unlitVariant != null ? unlitVariant : this; }

    public String entityType() {
        ModBlock b = base();
        if (b.beType == null) b.beType = "mod:" + b.name;
        return b.beType;
    }

    /** Slot count of the block entity inventory. */
    public int inventorySize() {
        if (machineType != null) return machineInputs + (machineFuel ? 1 : 0) + 1;
        return storageSlots;
    }

    /** Registers the block entity type (called when the block is registered). */
    public void registerEntityType() {
        if (!hasEntity()) return;
        ModBlock b = base();
        BlockEntity.register(entityType(), () -> new ModBlockEntity(b));
    }

    @Override public BlockEntity createBlockEntity() { return hasEntity() ? new ModBlockEntity(base()) : null; }

    @Override public String blockEntityType() { return hasEntity() ? entityType() : null; }

    // ================================================================ shape
    @Override public List<Box> boxes(int meta, int conn) {
        if (textLines > 0 && textStyle == TextStyle.PANEL) return panelBoxes(meta);
        if (model == null) return super.boxes(meta, conn);
        int q = placement == Placement.FACING ? meta & 3 : 0;
        List<Box> out = new ArrayList<>(model.size());
        for (Box b : model) out.add(b.rotateY(q));
        return out;
    }

    private List<Box> panelBoxes(int meta) {
        int q = meta & 3;
        boolean standing = (meta & 4) != 0;
        List<Box> l = new ArrayList<>(2);
        Box board = standing ? new Box(1, 6, 7, 15, 16, 9) : new Box(1, 3, 0, 15, 13, 2);
        faces(board);
        l.add(board.rotateY(q));
        if (standing) {
            Box post = new Box(7, 0, 7, 9, 6, 9);
            faces(post);
            l.add(post.rotateY(q));
        }
        return l;
    }

    private void faces(Box b) {
        b.face(Dir.SOUTH, textures[Dir.NORTH]).face(Dir.NORTH, textures[Dir.EAST]).face(Dir.WEST, textures[Dir.EAST]).face(Dir.EAST, textures[Dir.EAST])
                .face(Dir.UP, textures[Dir.UP]).face(Dir.DOWN, textures[Dir.DOWN]);
    }

    @Override public String textureName(int face, int meta) {
        if (placement == Placement.FACING && face >= 2 && model == null) {
            int front = Dir.HORIZONTAL[meta & 3];
            if (face == front) return textures[Dir.NORTH];
            if (face == Dir.OPPOSITE[front]) return textures[Dir.SOUTH] != null ? textures[Dir.SOUTH] : textures[Dir.EAST];
            return textures[Dir.EAST];
        }
        return super.textureName(face, meta);
    }

    @Override public int placementMeta(int face, float hitY, float yaw) {
        if (textLines > 0 && textStyle == TextStyle.PANEL) {
            if (face >= 2) for (int q = 0; q < 4; q++) if (Dir.HORIZONTAL[q] == face) return q;
            int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
            return ((quarter + 2) & 3) | 4;
        }
        return super.placementMeta(face, hitY, yaw);
    }

    /** Text plane: {centre x, centre y, front z, width} in pixels of the south-facing block. */
    public float[] textArea(int meta) {
        if (textStyle == TextStyle.PANEL) return (meta & 4) != 0 ? new float[]{8, 11, 9, 13} : new float[]{8, 8, 2, 13};
        return new float[]{textX, textY, textZ, textWidth};
    }

    // ================================================================ behaviour
    private BlockContextImpl ctx(World w, int x, int y, int z) { return new BlockContextImpl(Session.current, w, x, y, z); }

    @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
        if (use != null) {
            boolean[] r = {false};
            Session.safe(() -> r[0] = use.onUse(ctx(s.world, x, y, z), s));
            if (r[0]) return true;
        }
        if (storageSlots > 0 || machineType != null) { s.openModBlock(x, y, z); return true; }
        if (textLines > 0 && s.player.sneaking == false && s.heldStack() == null) { s.openModBlock(x, y, z); return true; }
        return false;
    }

    @Override public void onPlaced(World w, int x, int y, int z, int meta) {
        if (placed != null) Session.safe(() -> placed.handle(ctx(w, x, y, z)));
    }

    @Override public void onRemoved(World w, int x, int y, int z, int meta) {
        if (removed != null) Session.safe(() -> removed.handle(ctx(w, x, y, z)));
    }

    @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
        if (canPlace == null || Session.current == null) return true;
        boolean[] r = {true};
        Session.safe(() -> r[0] = canPlace.canPlace(Session.current, x, y, z));
        return r[0];
    }

    @Override public boolean ticksRandomly() { return random != null; }

    @Override public void randomTick(World w, int x, int y, int z, int meta, Random r) {
        if (random != null) Session.safe(() -> random.onRandomTick(ctx(w, x, y, z), r));
    }

    @Override public void scheduledTick(World w, int x, int y, int z, int meta) {
        if (scheduled != null) Session.safe(() -> scheduled.handle(ctx(w, x, y, z)));
    }

    /** Called by the session every tick while the player stands on the block. */
    public void steppedOn(World w, int x, int y, int z) {
        if (stepped != null) Session.safe(() -> stepped.handle(ctx(w, x, y, z)));
    }

    /** Block entity tick handler. */
    public void entityTick(World w, int x, int y, int z) {
        if (tick != null) Session.safe(() -> tick.handle(ctx(w, x, y, z)));
    }

    // ================================================================ persistent storage
    @Override public List<ItemStack> dropsWithEntity(World w, int x, int y, int z, int meta, Random r, BlockEntity be) {
        if (!persistent || !(be instanceof ModBlockEntity mbe)) return drops(meta, r);
        Item it = base().item != null ? base().item : derpcraft.item.Items.byName(base().name);
        if (it == null) return drops(meta, r);
        ItemStack st = new ItemStack(it, 1);
        mbe.writeTo(st);
        return new ArrayList<>(List.of(st));
    }

    @Override public void placedFromItem(World w, int x, int y, int z, ItemStack stack) {
        if (!persistent || stack == null || !stack.hasTag()) return;
        if (w.getBlockEntity(x, y, z) instanceof ModBlockEntity mbe) mbe.readFrom(stack);
    }

    /** Tooltip lines for an item of this block carrying stored contents. */
    public static List<String> contentsTooltip(ItemStack st) {
        List<String> l = new ArrayList<>();
        if (!st.hasTag() || !st.tag().has("items")) return l;
        Inventory inv = new Inventory(54);
        inv.deserialize(st.tag().getString("items"));
        int shown = 0, more = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s == null) continue;
            if (shown < 5) { l.add("§7" + s.displayName() + " x" + s.count); shown++; }
            else more++;
        }
        if (more > 0) l.add("§7§oand " + more + " more...");
        return l;
    }

}
