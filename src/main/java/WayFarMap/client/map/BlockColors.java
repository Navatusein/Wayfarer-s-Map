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
            tint = block == Blocks.grass ? grassTint(world, x, y, z)
                : block.colorMultiplier(world, x, y, z) & 0xFFFFFF;
        } catch (Throwable ignored) {
            // Some modded blocks expect a real render context here.
        }
        if (tint == 0xFFFFFF) {
            return base;
        }
        return enhance(multiply(base, tint), Config.biomeColorSaturation);
    }

    /**
     * The grass block's tint taken from the biomes themselves, averaged over the 3x3 around it as the game does. In
     * modpacks the block's own color lookup can be changed and give some biomes (plains among them) another biome's
     * bluer green, while the tall grass on it, which reads the biome directly, keeps the right one: the map without
     * grass and flowers then showed those biomes wrong.
     */
    private static int grassTint(IBlockAccess world, int x, int y, int z) {
        int r = 0, g = 0, b = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int color = world.getBiomeGenForCoords(x + dx, z + dz)
                    .getBiomeGrassColor(x + dx, y, z + dz);
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
            }
        }
        return (r / 9) << 16 | (g / 9) << 8 | b / 9;
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
