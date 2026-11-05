package derpcraft.render;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.resource.DefaultImages;
import derpcraft.resource.Resources;

import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.Set;

/** Everything loaded from resource packs: block texture array, GUI textures, sky textures, colormaps, font. */
public final class Assets {
    public final Resources resources = new Resources();
    public final BlockTextures blocks = new BlockTextures();
    public Texture2D widgets, icons, background, sun, moon, grassMap, foliageMap;
    public BufferedImage cloudsImage;
    public Font font;
    /** Entity textures (Minecraft 1.12 layouts). */
    public Texture2D chestNormal, chestTrapped, chestEnder, chestNormalDouble, chestTrappedDouble, sign, steve;
    public final Texture2D[] beds = new Texture2D[16];
    /** textures/particle/particles.png (16 x 16 sprites): flames, smoke, sparks... */
    public Texture2D particles;
    /** Container GUI textures. */
    public final java.util.Map<String, Texture2D> gui = new java.util.HashMap<>();
    public Texture2D[] panorama;
    public BufferedImage logo;
    public Texture2D logoTex;
    public RenderTables tables;
    /** Emission strength per block texture layer (R8, width = layer count). */
    public int emissiveTex;
    public int mipLevels = 4;
    public int version;
    public int plainsGrass = 0x79C05A, plainsFoliage = 0x59AE30;

    public BufferedImage image(String path) {
        BufferedImage i = resources.image(path);
        return i != null ? i : DefaultImages.get(path);
    }

    /** Colormap colour for plains (temperature 0.8, rainfall 0.4). */
    private static int sample(BufferedImage img) {
        int x = (int) ((1 - 0.8) * (img.getWidth() - 1)), y = (int) ((1 - 0.4 * 0.8) * (img.getHeight() - 1));
        return img.getRGB(x, y);
    }

    /** game/reflections.txt: which blocks reflect (Shaders -> Effects -> Reflections: Water + Blocks). */
    public java.nio.file.Path reflectionsFile;

    /** Wave classes (blue channel of the material table, see wave.glsl). */
    public static final int WAVE_LEAVES = 1, WAVE_PLANT = 2, WAVE_VINE = 3, WAVE_PLANT_TOP = 4, WAVE_LILY = 5;

    private static int waveClass(Block b) {
        String n = b.name;
        if (n.endsWith("_leaves") || n.startsWith("leaves")) return WAVE_LEAVES;
        if (n.equals("vine")) return WAVE_VINE;
        if (n.equals("waterlily")) return WAVE_LILY;
        if (b instanceof derpcraft.block.FunctionalBlocks.CropBlock) return WAVE_PLANT;
        // grass, ferns, flowers, saplings, dead bushes, tall plants (both halves; the upper one is sorted out per texture).
        // Not: mushrooms, cobwebs, sugar cane (a stack of blocks: each would bend at its own foot and the stalk would zig-zag)
        if (b.shape == Block.Shape.CROSS && b.layer == Block.Layer.CUTOUT && !n.contains("mushroom") && !n.equals("web")
                && !n.equals("sugar_cane") && !n.equals("fire")) return WAVE_PLANT;
        return 0;
    }

    /** Every texture a block can show (all faces and metas, model boxes). */
    private static Set<String> texturesOf(Block b) {
        Set<String> out = new LinkedHashSet<>();
        for (int meta = 0; meta < 16; meta++) {
            for (int f = 0; f < 6; f++) out.add(b.textureName(f, meta));
            try { for (var box : b.boxes(meta, 0)) for (String t : box.tex) out.add(t); } catch (RuntimeException ignored) {}
        }
        out.remove(null);
        return out;
    }

    /** Built-in reflectivity (0..1) per block name ('*' = any text); written to game/reflections.txt the first time. */
    private static final String DEFAULT_REFLECTIONS = """
            # DerpCraft: glossy blocks for Shaders -> Effects -> Reflections: "Water + Blocks".
            # One line per block:  name = strength  (0 = matte ... 1 = mirror; '*' matches any text, e.g. *_concrete).
            # Later lines win. Water always reflects. Block names are the ones of /give and /setblock.
            # After editing: Shaders -> Effects... -> "Reload reflections.txt" (or switch the resource pack).
            # Clear glass reflects only where its texture is solid (the frame); stained glass reflects everywhere.
            *_stained_glass = 0.6
            *_stained_glass_pane = 0.6
            glass = 0.5
            glass_pane = 0.5
            ice = 0.55
            packed_ice = 0.35
            *_concrete = 0.15
            *_glazed_terracotta = 0.3
            quartz_block = 0.3
            chiseled_quartz_block = 0.25
            quartz_pillar = 0.25
            polished_andesite = 0.2
            polished_diorite = 0.2
            polished_granite = 0.2
            iron_block = 0.7
            gold_block = 0.7
            diamond_block = 0.5
            emerald_block = 0.5
            lapis_block = 0.2
            coal_block = 0.2
            redstone_block = 0.25
            obsidian = 0.4
            prismarine = 0.15
            prismarine_bricks = 0.2
            dark_prismarine = 0.25
            sea_lantern = 0.25
            purpur_block = 0.12
            """;

    /** Reads reflections.txt (creating it with the defaults); returns block-name pattern -> strength, in file order. */
    private java.util.List<java.util.Map.Entry<java.util.regex.Pattern, Float>> reflectionRules() {
        String text = DEFAULT_REFLECTIONS;
        if (reflectionsFile != null) {
            try {
                if (java.nio.file.Files.exists(reflectionsFile)) text = java.nio.file.Files.readString(reflectionsFile);
                else {
                    java.nio.file.Files.createDirectories(reflectionsFile.getParent());
                    java.nio.file.Files.writeString(reflectionsFile, DEFAULT_REFLECTIONS);
                }
            } catch (Exception e) { System.err.println("reflections.txt: " + e.getMessage()); }
        }
        java.util.List<java.util.Map.Entry<java.util.regex.Pattern, Float>> rules = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) continue;
            try {
                float v = Float.parseFloat(line.substring(eq + 1).trim().replace(',', '.'));
                if (!Float.isFinite(v)) continue;
                StringBuilder rx = new StringBuilder();
                String[] parts = name.split("\\*", -1);
                for (int i = 0; i < parts.length; i++) { if (i > 0) rx.append(".*"); rx.append(java.util.regex.Pattern.quote(parts[i])); }
                rules.add(java.util.Map.entry(java.util.regex.Pattern.compile(rx.toString()), Math.max(0, Math.min(1, v))));
            } catch (NumberFormatException ignored) {}
        }
        return rules;
    }

    /** Re-reads game/reflections.txt and rebuilds the material table (no texture reload). */
    public void reloadMaterials() { if (tables != null) buildEmissive(); }

    /**
     * Material table, one RGBA8 texel per block texture layer: r = emission, g = flags (255 water, 100 fire),
     * b = wave class (WAVE_*), a = reflectivity (game/reflections.txt).
     */
    private void buildEmissive() {
        int n = Math.max(1, blocks.count());
        byte[] e = new byte[n * 4];
        for (Block b : Blocks.all()) {
            if (b.lightEmission <= 0) continue;
            int v = Math.min(255, b.lightEmission * 17);
            for (int meta = 0; meta < 16; meta++)
                for (int f = 0; f < 6; f++) {
                    String t = b.textureName(f, meta);
                    if (t == null || !blocks.has(t)) continue;
                    int l = blocks.layer(t);
                    // furnaces: only the glowing front
                    if (b.name.equals("lit_furnace") && !t.contains("on")) continue;
                    if (l < n) e[l * 4] = (byte) Math.max(e[l * 4] & 255, v);
                }
        }
        // material flags (g): 1 = water, 100 = fire (drawn at full brightness, a little brighter than the texture)
        for (Block b : Blocks.all())
            if (b.wantsEntityPass()) for (String t : b.extraTextures()) if (blocks.has(t)) { int l = blocks.layer(t); e[l * 4] = (byte) 255; e[l * 4 + 1] = 100; }
        // material flags (g): 255 = water
        for (int meta = 0; meta < 16; meta++)
            for (int f = 0; f < 6; f++) {
                String t = Blocks.WATER.textureName(f, meta);
                if (t != null && blocks.has(t)) e[blocks.layer(t) * 4 + 1] = (byte) 255;
            }
        // wave classes (b): what sways in the wind
        for (Block b : Blocks.all()) {
            int cls = waveClass(b);
            if (cls == 0) continue;
            boolean tall = b instanceof derpcraft.block.FunctionalBlocks.DoublePlantBlock;
            for (String t : texturesOf(b)) {
                if (!blocks.has(t)) continue;
                int c = tall && t.endsWith("_top") ? WAVE_PLANT_TOP : cls;
                int l = blocks.layer(t);
                if (l < n && (e[l * 4 + 2] == 0 || c == WAVE_LEAVES)) e[l * 4 + 2] = (byte) c;
            }
        }
        // reflectivity (a)
        var rules = reflectionRules();
        if (!rules.isEmpty())
            for (Block b : Blocks.all()) {
                float r = -1;
                for (var rule : rules) if (rule.getKey().matcher(b.name).matches()) r = rule.getValue();
                if (r < 0) continue;
                for (String t : texturesOf(b)) {
                    if (!blocks.has(t)) continue;
                    int l = blocks.layer(t);
                    if (l < n) e[l * 4 + 3] = (byte) Math.round(r * 255);
                }
            }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(n * 4);
        buf.put(e).flip();
        if (emissiveTex == 0) emissiveTex = org.lwjgl.opengl.GL45C.glGenTextures();
        org.lwjgl.opengl.GL45C.glBindTexture(org.lwjgl.opengl.GL45C.GL_TEXTURE_2D, emissiveTex);
        org.lwjgl.opengl.GL45C.glTexParameteri(org.lwjgl.opengl.GL45C.GL_TEXTURE_2D, org.lwjgl.opengl.GL45C.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL45C.GL_NEAREST);
        org.lwjgl.opengl.GL45C.glTexParameteri(org.lwjgl.opengl.GL45C.GL_TEXTURE_2D, org.lwjgl.opengl.GL45C.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL45C.GL_NEAREST);
        org.lwjgl.opengl.GL45C.glPixelStorei(org.lwjgl.opengl.GL45C.GL_UNPACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL45C.glTexImage2D(org.lwjgl.opengl.GL45C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL45C.GL_RGBA8, n, 1, 0, org.lwjgl.opengl.GL45C.GL_RGBA, org.lwjgl.opengl.GL45C.GL_UNSIGNED_BYTE, buf);
        org.lwjgl.opengl.GL45C.glPixelStorei(org.lwjgl.opengl.GL45C.GL_UNPACK_ALIGNMENT, 4);
    }

    public void reload() {
        Set<String> names = new LinkedHashSet<>();
        for (Block b : Blocks.all()) {
            for (int meta = 0; meta < 16; meta++) for (int f = 0; f < 6; f++) names.add(b.textureName(f, meta));
            if (b.sideOverlay != null) names.add(b.sideOverlay);
            names.addAll(b.extraTextures());
            for (int meta = 0; meta < 16; meta++)
                for (var box : b.boxes(meta, 0)) for (String t : box.tex) if (t != null) names.add(t);
        }
        for (derpcraft.item.Item it : derpcraft.item.Items.all()) if (it.texture != null) names.add(it.texture);
        for (int i = 0; i < RenderTables.SOOT_VARIANTS; i++) names.add("soot_" + i);
        for (int i = 0; i < EntityRenderer.BLOOD_VARIANTS; i++) names.add("blood_" + i);
        for (int i = 0; i < 3; i++) names.add("items/bow_pulling_" + i);
        for (int i = 0; i < 10; i++) names.add("destroy_stage_" + i);
        names.remove(null);
        blocks.build(resources, names, mipLevels);
        tables = new RenderTables(blocks);
        buildEmissive();
        for (Texture2D t : new Texture2D[]{widgets, icons, background, sun, moon, grassMap, foliageMap}) if (t != null) t.delete();
        widgets = new Texture2D(image("assets/minecraft/textures/gui/widgets.png"), false, false);
        icons = new Texture2D(image("assets/minecraft/textures/gui/icons.png"), false, false);
        background = new Texture2D(image("assets/minecraft/textures/gui/options_background.png"), false, true);
        sun = new Texture2D(image("assets/minecraft/textures/environment/sun.png"), false, false);
        moon = new Texture2D(image("assets/minecraft/textures/environment/moon_phases.png"), false, false);
        BufferedImage gi = image("assets/minecraft/textures/colormap/grass.png"), fi = image("assets/minecraft/textures/colormap/foliage.png");
        grassMap = new Texture2D(gi, false, false);
        foliageMap = new Texture2D(fi, false, false);
        plainsGrass = sample(gi) & 0xFFFFFF;
        plainsFoliage = sample(fi) & 0xFFFFFF;
        cloudsImage = image("assets/minecraft/textures/environment/clouds.png");
        java.util.List<Texture2D> old = new java.util.ArrayList<>(gui.values());
        for (Texture2D t : new Texture2D[]{chestNormal, chestTrapped, chestEnder, chestNormalDouble, chestTrappedDouble, sign, steve}) if (t != null) old.add(t);
        for (Texture2D t : beds) if (t != null) old.add(t);
        if (particles != null) old.add(particles);
        if (panorama != null) for (Texture2D t : panorama) if (t != null) old.add(t);
        for (Texture2D t : old) t.delete();
        gui.clear();
        String E = "assets/minecraft/textures/entity/";
        chestNormal = new Texture2D(image(E + "chest/normal.png"), false, false);
        chestTrapped = new Texture2D(image(E + "chest/trapped.png"), false, false);
        chestEnder = new Texture2D(image(E + "chest/ender.png"), false, false);
        chestNormalDouble = new Texture2D(image(E + "chest/normal_double.png"), false, false);
        chestTrappedDouble = new Texture2D(image(E + "chest/trapped_double.png"), false, false);
        sign = new Texture2D(image(E + "sign.png"), false, false);
        steve = new Texture2D(image(E + "steve.png"), false, false);
        particles = new Texture2D(image("assets/minecraft/textures/particle/particles.png"), false, false);
        for (int c = 0; c < 16; c++) beds[c] = new Texture2D(image(E + "bed/" + derpcraft.block.Blocks.COLORS[c] + ".png"), false, false);
        for (String g : new String[]{"inventory", "crafting_table", "generic_54", "furnace", "dispenser", "hopper",
                "creative_inventory/tabs", "creative_inventory/tab_items", "creative_inventory/tab_item_search", "creative_inventory/tab_inventory"})
            gui.put(g, new Texture2D(image("assets/minecraft/textures/gui/container/" + g + ".png"), false, false));
        // title screen panorama (only if the pack has one)
        panorama = null;
        if (resources.image("assets/minecraft/textures/gui/title/background/panorama_0.png") != null) {
            panorama = new Texture2D[6];
            for (int i = 0; i < 6; i++) panorama[i] = new Texture2D(image("assets/minecraft/textures/gui/title/background/panorama_" + i + ".png"), true, false);
        }
        logo = derpcraft.resource.DefaultImages.logo(this);
        if (logoTex != null) logoTex.delete();
        logoTex = new Texture2D(logo, false, false);
        if (font != null) font.delete();
        font = new Font(image("assets/minecraft/textures/font/ascii.png"));
        version++;
    }
}
