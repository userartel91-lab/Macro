package derpcraft.item;

import derpcraft.api.ItemBuilder;
import derpcraft.game.Raycast;
import derpcraft.game.Session;

/** An item made with {@link ItemBuilder}: properties plus an optional right-click handler. */
public class ModItem extends Item {
    public ItemBuilder.Use onUse;
    public boolean consumeOnUse;

    public ModItem(String name) { super(name); }

    @Override public boolean use(Session s, ItemStack stack, Raycast.Hit hit) {
        if (onUse != null) {
            boolean[] r = {false};
            Session.safe(() -> r[0] = onUse.onUse(s, stack, hit == null ? null : s.blockAt(hit.x, hit.y, hit.z)));
            if (r[0]) {
                if (consumeOnUse && !s.creative()) {
                    stack.count--;
                    s.inv.set(s.inv.selected, stack.count <= 0 ? null : stack);
                }
                return true;
            }
        }
        return super.use(s, stack, hit);
    }
}
