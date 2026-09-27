package WayFarMap.client.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;

/** Turns a loaded chunk into map pixels. */
public final class ChunkScanner {

    private static final int NO_BLOCK = -1;

    private ChunkScanner() {}

    /** A chunk the client actually has block data for. */
    public static boolean isChunkReady(World world, int cx, int cz) {
        if (!world.getChunkProvider()
            .chunkExists(cx, cz)) {
            return false;
        }
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        return chunk != null && !chunk.isEmpty();
    }

    /**
     * Draws the chunk into the map.
     *
     * @param caveLayer -1 for the surface, otherwise the cave layer (blocks {@code layer * 16} to
     *                  {@code layer * 16 + 15}) whose floor is drawn
     */
    public static void scan(World world, Chunk chunk, MapDimension dimension, int caveLayer) {
        scan(world, chunk, dimension, caveLayer, null);
    }

    /**
     * Same as {@link #scan(World, Chunk, MapDimension, int)}, also drawing the biome of each column into
     * {@code biomeMap} when it is not null (surface only).
     */
    public static void scan(World world, Chunk chunk, MapDimension dimension, int caveLayer, MapDimension biomeMap) {
        int cx = chunk.xPosition;
        int cz = chunk.zPosition;
        boolean noSky = world.provider.hasNoSky;
        boolean detailed = Config.mapStyle == Config.STYLE_DETAILED;

        // Heights of the chunk and of the columns right around it (from the neighbouring chunks if loaded), for
        // shading across chunk borders: heights[lx + 1][lz + 1].
        int[][] heights = new int[18][18];
        Chunk north = neighbour(world, cx, cz - 1), south = neighbour(world, cx, cz + 1);
        Chunk west = neighbour(world, cx - 1, cz), east = neighbour(world, cx + 1, cz);
        for (int i = 0; i < 18; i++) {
            heights[i][0] = heights[i][17] = heights[0][i] = heights[17][i] = NO_BLOCK;
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                heights[lx + 1][lz + 1] = findTop(chunk, lx, lz, noSky, caveLayer);
            }
            heights[lx + 1][0] = north != null ? findTop(north, lx, 15, noSky, caveLayer) : NO_BLOCK;
            if (detailed) {
                heights[lx + 1][17] = south != null ? findTop(south, lx, 0, noSky, caveLayer) : NO_BLOCK;
                heights[0][lx + 1] = west != null ? findTop(west, 15, lx, noSky, caveLayer) : NO_BLOCK;
                heights[17][lx + 1] = east != null ? findTop(east, 0, lx, noSky, caveLayer) : NO_BLOCK;
            }
        }

        MapRegion region = dimension.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);
        int baseX = (cx * 16) & (MapRegion.SIZE - 1);
        int baseZ = (cz * 16) & (MapRegion.SIZE - 1);
        MapRegion biomeRegion = biomeMap == null ? null
            : biomeMap.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int y = heights[lx + 1][lz + 1];
                int previousHeight = heights[lx + 1][lz];
                int argb = 0;
                float relief = 1f;
                if (y != NO_BLOCK && previousHeight != NO_BLOCK) {
                    relief = 1.0f + Math.max(-4, Math.min(4, y - previousHeight)) * 0.05f;
                }
                if (y != NO_BLOCK) {
                    int rgb = columnColor(world, chunk, lx, y, lz);
                    if (detailed) {
                        rgb = withPlant(world, chunk, lx, y, lz, rgb);
                    }
                    if (caveLayer >= 0) {
                        // Deeper floors (below the layer) get darker, so drops read as depth.
                        int below = Math.max(0, caveLayer * 16 - y);
                        rgb = BlockColors.shade(rgb, Math.max(0.45f, 1.0f - below * 0.04f));
                    }
                    if (detailed) {
                        rgb = BlockColors.shade(rgb, detailedShade(heights, lx + 1, lz + 1, caveLayer < 0 && !noSky));
                    } else {
                        rgb = BlockColors.shade(rgb, relief);
                    }
                    argb = 0xFF000000 | rgb;
                }
                if (caveLayer < 0) {
                    // The surface also remembers the height to stand on, for teleporting.
                    region.setPixel(baseX + lx, baseZ + lz, argb, y == NO_BLOCK ? 0 : Math.min(255, y + 1));
                } else {
                    region.setPixel(baseX + lx, baseZ + lz, argb);
                }
                if (biomeRegion != null) {
                    BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(lx, lz, world.getWorldChunkManager());
                    int biomeArgb = biome == null ? 0
                        : 0xFF000000 | BlockColors.shade(biomeColor(biome), 1f + (relief - 1f) * 0.6f);
                    int biomeId = biome == null || biome.biomeID >= 255 ? 0 : biome.biomeID + 1;
                    biomeRegion.setPixel(baseX + lx, baseZ + lz, biomeArgb, biomeId);
                }
            }
        }
    }

    private static Chunk neighbour(World world, int cx, int cz) {
        return isChunkReady(world, cx, cz) ? world.getChunkFromChunkCoords(cx, cz) : null;
    }

    /**
     * JourneyMap-like shading: the slope towards the north-west light (both neighbours, not only the north one) makes
     * faces brighter or darker, columns lower than their surroundings get a little shadow (crevices, riverbeds, the
     * foot of cliffs), and on the surface higher ground is slightly lighter, so the terrain reads like a relief map.
     */
    private static float detailedShade(int[][] heights, int i, int j, boolean surface) {
        int y = heights[i][j];
        int north = heightOr(heights[i][j - 1], y), west = heightOr(heights[i - 1][j], y);
        int south = heightOr(heights[i][j + 1], y), east = heightOr(heights[i + 1][j], y);
        int slope = Math.max(-6, Math.min(6, y - north)) + Math.max(-6, Math.min(6, y - west));
        float factor = 1.0f + slope * 0.045f;
        // Neighbours at least two blocks higher cast a soft shadow.
        int higher = (north - y >= 2 ? 1 : 0) + (west - y >= 2 ? 1 : 0)
            + (south - y >= 2 ? 1 : 0)
            + (east - y >= 2 ? 1 : 0);
        factor -= higher * 0.05f;
        if (surface) {
            factor += Math.max(-0.08f, Math.min(0.08f, (y - 64) * 0.0015f));
        }
        return Math.max(0.55f, Math.min(1.35f, factor));
    }

    private static int heightOr(int height, int fallback) {
        return height == NO_BLOCK ? fallback : height;
    }

    /**
     * Plants and small things standing on the block (flowers, tall grass, crops, saplings, rails, redstone, torches,
     * snow) tint its pixel, like JourneyMap's plant layer.
     */
    private static int withPlant(World world, Chunk chunk, int lx, int y, int lz, int rgb) {
        if (y >= 255) {
            return rgb;
        }
        Block above = chunk.getBlock(lx, y + 1, lz);
        Material material = above.getMaterial();
        if (above.getRenderType() == -1 || material != Material.plants && material != Material.vine
            && material != Material.circuits) {
            return rgb;
        }
        int x = chunk.xPosition * 16 + lx;
        int z = chunk.zPosition * 16 + lz;
        int plant = BlockColors.getColor(world, above, chunk.getBlockMetadata(lx, y + 1, lz), x, y + 1, z);
        // Tall grass and vines mostly cover the ground; flowers, crops and rails leave more of it visible.
        float amount = material == Material.vine || above == Blocks.tallgrass ? 0.35f : 0.6f;
        return BlockColors.blend(rgb, plant, amount);
    }

    /** Color of the biome for the biome map; biomes without a color get a stable made-up one. */
    public static int biomeColor(BiomeGenBase biome) {
        int color = biome.color & 0xFFFFFF;
        if (color != 0) {
            return color;
        }
        int hash = biome.biomeID * 0x9E3779B1;
        return 0x404040 | (hash >>> 8) & 0xBFBFBF;
    }

    private static int findTop(Chunk chunk, int lx, int lz, boolean noSky, int caveLayer) {
        return caveLayer >= 0 ? findCaveFloor(chunk, lx, lz, caveLayer) : findSurface(chunk, lx, lz, noSky);
    }

    /**
     * Floor of the open space in the cave layer: rock at the top of the layer is skipped, then the first block below
     * the open space is the floor (searched down to one layer below, for pits). Solid rock gives {@link #NO_BLOCK}.
     */
    private static int findCaveFloor(Chunk chunk, int lx, int lz, int layer) {
        int layerBottom = layer * 16;
        int y = Math.min(255, layerBottom + 15);
        while (y >= layerBottom && isSolid(chunk.getBlock(lx, y, lz))) {
            y--;
        }
        if (y < layerBottom) {
            return NO_BLOCK;
        }
        int lowest = Math.max(0, layerBottom - 16);
        for (; y >= lowest; y--) {
            if (isVisible(chunk.getBlock(lx, y, lz))) {
                return y;
            }
        }
        return NO_BLOCK;
    }

    /** A block that fills the space (not air, plants, torches or liquids). */
    private static boolean isSolid(Block block) {
        return isVisible(block) && !block.getMaterial()
            .isLiquid();
    }

    /** @return y of the topmost block that should be drawn, or {@link #NO_BLOCK}. */
    private static int findSurface(Chunk chunk, int lx, int lz, boolean noSky) {
        int top = chunk.getTopFilledSegment() + 15;
        if (top < 0) {
            return NO_BLOCK;
        }
        int y = top;
        if (noSky) {
            // Under a ceiling (Nether): skip the ceiling, then look for the floor below the first open space.
            top = Math.min(top, 127);
            y = top;
            while (y > 0 && !isAir(chunk.getBlock(lx, y, lz))) {
                y--;
            }
        }
        for (; y >= 0; y--) {
            Block block = chunk.getBlock(lx, y, lz);
            if (isVisible(block)) {
                return y;
            }
        }
        return NO_BLOCK;
    }

    private static boolean isAir(Block block) {
        return block.getMaterial() == Material.air;
    }

    private static boolean isVisible(Block block) {
        Material material = block.getMaterial();
        if (material == Material.air || material == Material.plants
            || material == Material.vine
            || material == Material.circuits
            || material == Material.web
            || material == Material.fire) {
            return false;
        }
        return block.getRenderType() != -1 || material.isLiquid();
    }

    private static int columnColor(World world, Chunk chunk, int lx, int y, int lz) {
        int x = chunk.xPosition * 16 + lx;
        int z = chunk.zPosition * 16 + lz;
        Block block = chunk.getBlock(lx, y, lz);
        int meta = chunk.getBlockMetadata(lx, y, lz);
        int color = BlockColors.getColor(world, block, meta, x, y, z);

        if (block.getMaterial() == Material.water) {
            // Let the floor shine through shallow water.
            int floorY = y - 1;
            while (floorY > 0 && chunk.getBlock(lx, floorY, lz)
                .getMaterial() == Material.water) {
                floorY--;
            }
            int depth = y - floorY;
            Block floor = chunk.getBlock(lx, floorY, lz);
            if (isVisible(floor)) {
                int floorColor = BlockColors
                    .getColor(world, floor, chunk.getBlockMetadata(lx, floorY, lz), x, floorY, z);
                float waterAmount = Math.min(0.95f, 0.55f + depth * 0.05f);
                color = BlockColors.blend(floorColor, color, waterAmount);
            }
            color = BlockColors.shade(color, Math.max(0.6f, 1.0f - depth * 0.015f));
        }
        return color;
    }
}
