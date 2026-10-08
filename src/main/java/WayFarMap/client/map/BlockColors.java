package WayFarMap.client.map;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import WayFarMap.Config;
import WayFarMap.WayFarMap;

/**
 * Resolves the color a block shows on the map: the average color of its top texture (or its vanilla map color as a
 * fallback), multiplied by the block's tint (biome color of grass, leaves, water...), as the game colors them. Tinted
 * colors can be made more muted or more vivid ({@link Config#biomeColorSaturation}), still blending smoothly between
 * biomes as the game's tint does.
 */
public final class BlockColors {

    /** Base (untinted) color per block id and metadata. */
    private static final Map<Integer, Integer> BASE_COLORS = new HashMap<>();
    /** Average color per texture name; -1 when the texture could not be read. */
    private static final Map<String, Integer> TEXTURE_COLORS = new HashMap<>();

    private BlockColors() {}

    public static void clearCache() {
        BASE_COLORS.clear();
        TEXTURE_COLORS.clear();
    }

    /** @return opaque RGB color of the block at the given position, including its tint. */
    public static int getColor(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        int base = getBaseColor(block, meta);
        int tint = 0xFFFFFF;
        try {
            tint = vanillaTint(world, block, meta, x, y, z);
            if (tint < 0) {
                tint = block.colorMultiplier(world, x, y, z) & 0xFFFFFF;
            }
        } catch (Throwable ignored) {
            // Some modded blocks expect a real render context here.
        }
        if (tint == 0xFFFFFF) {
            return base;
        }
        return enhance(multiply(base, tint), Config.biomeColorSaturation);
    }

    /** Biome colors, as the game reads them for grass and for leaves. */
    private static final int GRASS = 0, FOLIAGE = 1;

    /**
     * The tint of the game's own tinted blocks, taken from the biomes as the game draws them, or -1 for any other
     * block (its own {@code colorMultiplier}). In modpacks the blocks' own color lookups can be changed and give
     * colors far from what the world shows: swamps came out on the map nearly as green as the forest next to them,
     * their olive grass and leaves barely seen. Water keeps its own lookup (what darkens it in some biomes isn't
     * known yet: see {@link #describe}).
     */
    private static int vanillaTint(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        if (block == Blocks.grass || block == Blocks.tallgrass) {
            return biomeTint(world, x, y, z, GRASS);
        }
        if (block == Blocks.double_plant) {
            // Its upper half says only that it is one: the kind is the lower half's.
            int kind = (meta & 8) != 0 ? world.getBlockMetadata(x, y - 1, z) & 7 : meta & 7;
            return kind == 2 || kind == 3 ? biomeTint(world, x, y, z, GRASS) : 0xFFFFFF;
        }
        if (block == Blocks.leaves) {
            // Spruce and birch have colors of their own, the others the biome's.
            int kind = meta & 3;
            return kind == 1 ? 0x619961 : kind == 2 ? 0x80A755 : biomeTint(world, x, y, z, FOLIAGE);
        }
        if (block == Blocks.leaves2 || block == Blocks.vine) {
            return biomeTint(world, x, y, z, FOLIAGE);
        }
        return -1;
    }

    /** A biome color averaged over the 3x3 around the place, as the game blends it from biome to biome. */
    private static int biomeTint(IBlockAccess world, int x, int y, int z, int which) {
        int r = 0, g = 0, b = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                net.minecraft.world.biome.BiomeGenBase biome = world.getBiomeGenForCoords(x + dx, z + dz);
                int color = which == GRASS ? biome.getBiomeGrassColor(x + dx, y, z + dz)
                    : biome.getBiomeFoliageColor(x + dx, y, z + dz);
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
        }
        return (r / 9) << 16 | (g / 9) << 8 | b / 9;
    }

    /** For the block report ({@code /wfmap3d}): how the 2D map colors the block there, step by step. */
    public static String describe(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        StringBuilder b = new StringBuilder();
        net.minecraft.world.biome.BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        b.append("biome ")
            .append(biome == null ? "?" : biome.biomeName + " (id " + biome.biomeID + ")");
        if (biome != null) {
            b.append(" grass=")
                .append(hex(biome.getBiomeGrassColor(x, y, z)))
                .append(" foliage=")
                .append(hex(biome.getBiomeFoliageColor(x, y, z)))
                .append(" water=")
                .append(hex(biome.getWaterColorMultiplier()));
        }
        b.append("\nbase (top texture or map color) ")
            .append(hex(getBaseColor(block, meta)));
        try {
            b.append(" | block's colorMultiplier ")
                .append(hex(block.colorMultiplier(world, x, y, z)));
        } catch (Throwable t) {
            b.append(" | block's colorMultiplier failed: ")
                .append(t);
        }
        try {
            b.append(" | renderColor ")
                .append(hex(block.getRenderColor(meta)));
        } catch (Throwable t) {
            b.append(" | renderColor failed");
        }
        try {
            int tint = vanillaTint(world, block, meta, x, y, z);
            b.append(" | biome tint used ")
                .append(tint < 0 ? "none (colorMultiplier used)" : hex(tint));
        } catch (Throwable t) {
            b.append(" | biome tint failed: ")
                .append(t);
        }
        b.append("\non the map ")
            .append(hex(getColor(world, block, meta, x, y, z)))
            .append(" (biomeColorSaturation ")
            .append(Config.biomeColorSaturation)
            .append(")");
        return b.toString();
    }

    private static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }

    /** Gray level that contrast pushes away from: about the middle of the map's colors. */
    private static final float CONTRAST_PIVOT = 118f;

    /**
     * Saturation (away from the color's own gray) times {@code amount}, and contrast (away from
     * {@link #CONTRAST_PIVOT}) changed by half as much; 1 leaves the color as it is, below 1 mutes it.
     */
    static int enhance(int rgb, double amount) {
        if (amount == 1.0) {
            return rgb;
        }
        float saturation = (float) amount, contrast = 1f + (float) (amount - 1.0) * 0.5f;
        float r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        float gray = 0.299f * r + 0.587f * g + 0.114f * b;
        r = CONTRAST_PIVOT + (gray + (r - gray) * saturation - CONTRAST_PIVOT) * contrast;
        g = CONTRAST_PIVOT + (gray + (g - gray) * saturation - CONTRAST_PIVOT) * contrast;
        b = CONTRAST_PIVOT + (gray + (b - gray) * saturation - CONTRAST_PIVOT) * contrast;
        return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int getBaseColor(Block block, int meta) {
        int key = (Block.getIdFromBlock(block) << 4) | (meta & 15);
        Integer cached = BASE_COLORS.get(key);
        if (cached != null) {
            return cached;
        }
        int color = -1;
        if (Config.useTextureColors) {
            color = getTextureColor(block, meta);
        }
        if (color == -1) {
            color = getMapColor(block, meta);
        }
        BASE_COLORS.put(key, color);
        return color;
    }

    private static int getMapColor(Block block, int meta) {
        try {
            MapColor mapColor = block.getMapColor(meta);
            if (mapColor != null && mapColor.colorValue != 0) {
                return mapColor.colorValue & 0xFFFFFF;
            }
        } catch (Throwable ignored) {}
        return 0x808080;
    }

    private static int getTextureColor(Block block, int meta) {
        IIcon icon;
        try {
            icon = block.getIcon(1, meta);
        } catch (Throwable t) {
            return -1;
        }
        if (icon == null || icon.getIconName() == null) {
            return -1;
        }
        String name = icon.getIconName();
        Integer cached = TEXTURE_COLORS.get(name);
        if (cached == null) {
            cached = readTextureColor(name);
            TEXTURE_COLORS.put(name, cached);
        }
        return cached;
    }

    private static int readTextureColor(String iconName) {
        String domain = "minecraft";
        String path = iconName;
        int colon = iconName.indexOf(':');
        if (colon >= 0) {
            domain = iconName.substring(0, colon);
            path = iconName.substring(colon + 1);
        }
        ResourceLocation location = new ResourceLocation(domain, "textures/blocks/" + path + ".png");
        try (InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(location)
            .getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return -1;
            }
            // Animated textures are vertical strips; the first square frame is enough.
            int size = Math.min(image.getWidth(), image.getHeight());
            long r = 0, g = 0, b = 0, count = 0;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    int argb = image.getRGB(px, py);
                    if ((argb >>> 24) < 32) {
                        continue;
                    }
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                    count++;
                }
            }
            if (count == 0) {
                return -1;
            }
            return (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
        } catch (Exception e) {
            WayFarMap.LOG.debug("Could not read texture {} for map colors", location);
            return -1;
        }
    }

    public static int multiply(int color, int tint) {
        int r = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (color & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }

    public static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    public static int shade(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }
}
