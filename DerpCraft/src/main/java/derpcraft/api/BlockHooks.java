package derpcraft.api;

import java.util.Random;

/** Handler types for mod blocks (set them on the {@link BlockBuilder}). */
public final class BlockHooks {
    private BlockHooks() {}

    /** Right click. Return true when handled (otherwise the held item is used / placed). */
    public interface Use { boolean onUse(BlockContext block, GameApi game); }

    /** Placed, removed, stepped on, ticked... */
    public interface Simple { void handle(BlockContext block); }

    public interface RandomTick { void onRandomTick(BlockContext block, Random random); }

    /** Whether the block may be placed at a position. */
    public interface CanPlace { boolean canPlace(GameApi game, int x, int y, int z); }

    /** Which items a storage / machine slot accepts. */
    public interface SlotFilter { boolean accepts(int slot, derpcraft.item.ItemStack stack); }
}
