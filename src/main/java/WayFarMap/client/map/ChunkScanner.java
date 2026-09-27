package WayFarMap.client.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

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

        // Heights of the chunk and of the two rows north and west of it (from the neighbouring chunks if loaded),
        // for shading across chunk borders: heights[lx + 2][lz + 2] for lx, lz from -2 to 15.
        int[][] heights = new int[18][18];
        Chunk north = neighbour(world, cx, cz - 1);
        Chunk west = neighbour(world, cx - 1, cz);
        Chunk northWest = neighbour(world, cx - 1, cz - 1);
        for (int i = -2; i < 16; i++) {
            for (int j = -2; j < 16; j++) {
                Chunk source = i >= 0 ? (j >= 0 ? chunk : north) : (j >= 0 ? west : northWest);
                heights[i + 2][j + 2] = source == null ? NO_BLOCK : findTop(source, i & 15, j & 15, noSky, caveLayer);
            }
        }

        MapRegion region = dimension.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);
        int baseX = (cx * 16) & (MapRegion.SIZE - 1);
        int baseZ = (cz * 16) & (MapRegion.SIZE - 1);
        MapRegion biomeRegion = biomeMap == null ? null
            : biomeMap.getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int y = heights[lx + 2][lz + 2];
                int northHeight = heights[lx + 2][lz + 1];
                int argb = 0;
                // Light relief for the biome map: a step up from the north is lighter, a step down darker.
                float biomeRelief = 1f;
                if (y != NO_BLOCK && northHeight != NO_BLOCK) {
                    biomeRelief = 1.0f + Math.max(-4, Math.min(4, y - northHeight)) * 0.03f;
                }
                if (y != NO_BLOCK) {
                    int rgb = columnColor(world, chunk, lx, y, lz);
                    rgb = detailedColor(world, chunk, heights, lx, y, lz, rgb);
                    if (caveLayer >= 0) {
                        // Deeper floors (below the layer) get darker, so drops read as depth.
                        int below = Math.max(0, caveLayer * 16 - y);
                        rgb = BlockColors.shade(rgb, Math.max(0.45f, 1.0f - below * 0.04f));
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
                        : 0xFF000000 | BlockColors.shade(biomeColor(biome), biomeRelief);
                    int biomeId = biome == null || biome.biomeID >= 255 ? 0 : biome.biomeID + 1;
                    biomeRegion.setPixel(baseX + lx, baseZ + lz, biomeArgb, biomeId);
                }
            }
        }
    }

    private static Chunk neighbour(World world, int cx, int cz) {
        return isChunkReady(world, cx, cz) ? world.getChunkFromChunkCoords(cx, cz) : null;
    }

    // JourneyMap's shading ("black magic that serves as the stand-in for true bump-mapping"), same numbers.
    private static final int[][] PRIMARY_SLOPE = { { 0, -1 }, { -1, -1 }, { -1, 0 } };
    private static final int[][] SECONDARY_SLOPE = { { -1, -2 }, { -2, -1 }, { -2, -2 }, { -2, 0 }, { 0, -2 } };
    private static final float DOWNSLOPE = 0.65f, UPSLOPE = 1.2f;
    private static final float SECONDARY_DOWNSLOPE = 0.95f, SECONDARY_UPSLOPE = 1.05f;
    private static final float SLOPE_MIN = 0.2f, SLOPE_MAX = 1.7f;
    /** Daylight brightening of the surface, like JourneyMap's day map. */
    private static final float DAYLIGHT = 1.06f;

    /**
     * The JourneyMap look of a column: a plant, crop, rail or redstone on the block is drawn instead of
     * it (without a bevel, like JourneyMap without plant shadows); otherwise the block is beveled by its slope to the
     * north-west, with shadows turning a little blue.
     */
    private static int detailedColor(World world, Chunk chunk, int[][] heights, int lx, int y, int lz, int rgb) {
        int plant = plantColor(world, chunk, lx, y, lz);
        if (plant >= 0) {
            return BlockColors.shade(plant, DAYLIGHT);
        }
        if (chunk.getBlock(lx, y, lz)
            .getMaterial()
            .isLiquid()) {
            // Water and lava are flat: no bevel, as in JourneyMap.
            return BlockColors.shade(rgb, DAYLIGHT);
        }
        return bevel(BlockColors.shade(rgb, DAYLIGHT), slope(heights, lx + 2, lz + 2));
    }

    /** Slope factor of the column: above 1 facing the light (brighter), below 1 facing away (darker). */
    private static float slope(int[][] heights, int i, int j) {
        int y = heights[i][j];
        if (y <= 0) {
            return 1f;
        }
        float primary = averageRatio(heights, i, j, y, PRIMARY_SLOPE);
        float slope = primary < 1f ? primary * DOWNSLOPE : primary > 1f ? primary * UPSLOPE : 1f;
        if (primary == 1f) {
            // Flat next to the block: look one block further to soften the edges (JourneyMap's antialiasing).
            float secondary = averageRatio(heights, i, j, y, SECONDARY_SLOPE);
            if (secondary > 1f) {
                slope *= SECONDARY_UPSLOPE;
            } else if (secondary < 1f) {
                slope *= SECONDARY_DOWNSLOPE;
            }
        }
        return Math.max(SLOPE_MIN, Math.min(SLOPE_MAX, slope));
    }

    /** Average of the column's height divided by each neighbour's (unknown neighbours count as level). */
    private static float averageRatio(int[][] heights, int i, int j, int y, int[][] offsets) {
        float sum = 0;
        for (int[] offset : offsets) {
            int other = heights[i + offset[0]][j + offset[1]];
            sum += other == NO_BLOCK || other <= 0 ? 1f : (float) y / other;
        }
        return sum / offsets.length;
    }

    /** Darkens or lightens by the slope; shadows get a blue tint (JourneyMap's bevelSlope). */
    private static int bevel(int rgb, float factor) {
        float bluer = factor < 1f ? 0.85f : 1f;
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * bluer * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * bluer * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    /**
     * Color of a plant, crop, sapling, rail, redstone or torch standing on the block, or -1 if there is none.
     * JourneyMap draws these instead of the block below them.
     */
    private static int plantColor(World world, Chunk chunk, int lx, int y, int lz) {
        if (y >= 255) {
            return -1;
        }
        Block above = chunk.getBlock(lx, y + 1, lz);
        Material material = above.getMaterial();
        if (above.getRenderType() == -1 || material != Material.plants && material != Material.vine
            && material != Material.circuits) {
            return -1;
        }
        int x = chunk.xPosition * 16 + lx;
        int z = chunk.zPosition * 16 + lz;
        return BlockColors.getColor(world, above, chunk.getBlockMetadata(lx, y + 1, lz), x, y + 1, z);
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
