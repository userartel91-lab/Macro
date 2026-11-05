package derpcraft.api;

import derpcraft.block.Block;
import derpcraft.item.ItemStack;

/**
 * A block in the world, as seen by a mod block's handlers: its position, state, saved data, inventory and text.
 * Handlers get one of these; it is only valid during the call.
 */
public interface BlockContext {
    GameApi game();

    int x();
    int y();
    int z();

    Block block();

    /** 0..15. For blocks placed with {@code facesPlayer()} bits 0-1 are the direction (0 south, 1 west, 2 north, 3 east). */
    int meta();

    void setMeta(int meta);

    /** Horizontal direction the front faces: 0 south, 1 west, 2 north, 3 east. */
    default int facing() { return meta() & 3; }

    /** Saved key/value data of this block (needs {@code .data()}, storage, machine or text on the builder). */
    DataTag data();

    /** Slots (storage and machine blocks), or null. */
    InventoryApi inventory();

    /** Text lines (text blocks), or null. Change with {@link #setText}. */
    String[] text();

    void setText(int line, String text);

    /** Machine blocks: true while working (burning / processing). */
    boolean working();

    /** Calls the block's scheduled-tick handler after 'ticks' game ticks. */
    void scheduleTick(int ticks);

    /** Drops an item stack on top of the block. */
    void drop(ItemStack stack);

    /** Replaces this block (null / "air" = remove without drops). */
    void setBlock(Block block, int meta);

    /** Removes the block like a player breaking it (drops included when 'drops'). */
    void breakBlock(boolean drops);

    /** Light level 0..15 at the block (max of sky and block light). */
    int light();

    /** Opens this block's GUI (storage / machine / text editor) for the player. */
    void openGui();
}
