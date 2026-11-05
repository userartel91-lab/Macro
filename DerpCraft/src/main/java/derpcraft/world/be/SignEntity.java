package derpcraft.world.be;

import java.util.Map;

/** Four lines of sign text. */
public final class SignEntity extends BlockEntity {
    public final String[] lines = {"", "", "", ""};

    @Override public String type() { return "sign"; }

    @Override public void save(Map<String, String> out) {
        for (int i = 0; i < 4; i++) out.put("line" + i, lines[i]);
    }

    @Override public void load(Map<String, String> in) {
        for (int i = 0; i < 4; i++) lines[i] = in.getOrDefault("line" + i, "");
    }
}
