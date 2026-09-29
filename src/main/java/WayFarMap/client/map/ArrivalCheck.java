package WayFarMap.client.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

/**
 * For the flat map log only: what a chunk looked like from above when it arrived, compared with what it looks like
 * when it is first mapped. Tells whether chunks come unfinished (snow, ice, trees added after) and whether the snow
 * and ice could have been drawn at once from the biome's temperature, as world generation puts them.
 */
final class ArrivalCheck {

    private ArrivalCheck() {}

    /** Below this biome temperature (at the height of the block) snow falls and water freezes, as in the game. */
    private static final float FREEZING = 0.15f;

    static final int OTHER = 0, SNOW = 1, ICE = 2, TREE = 3, WATER = 4, NONE = 5;

    static final class Snapshot {

        final long at = System.nanoTime();
        final int[] top = new int[256];
        final int[] height = new int[256];
        /** Snow or ice the game's generation would put there (or already there). */
        final boolean[] coldPredicted = new boolean[256];
    }

    static Snapshot take(World world, Chunk chunk) {
        Snapshot snapshot = new Snapshot();
        boolean noSky = world.provider.hasNoSky;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                int y = ChunkScanner.surfaceY(chunk, lx, lz, noSky);
                snapshot.height[i] = y;
                if (y < 0) {
                    snapshot.top[i] = -1;
                    continue;
                }
                Block block = chunk.getBlock(lx, y, lz);
                snapshot.top[i] = Block.getIdFromBlock(block) << 4 | chunk.getBlockMetadata(lx, y, lz);
                int kind = kind(block);
                boolean predicted = kind == SNOW || kind == ICE;
                if (!predicted && !noSky
                    && (kind == WATER || block.getMaterial()
                        .isSolid() || kind == TREE)) {
                    try {
                        BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(lx, lz, world.getWorldChunkManager());
                        predicted = biome != null
                            && biome.getFloatTemperature(chunk.xPosition * 16 + lx, y + 1, chunk.zPosition * 16 + lz)
                                < FREEZING;
                    } catch (RuntimeException e) {
                        predicted = false;
                    }
                }
                snapshot.coldPredicted[i] = predicted;
            }
        }
        return snapshot;
    }

    static int kind(Block block) {
        Material material = block.getMaterial();
        if (material == Material.snow || material == Material.craftedSnow) {
            return SNOW;
        }
        if (material == Material.ice || material == Material.packedIce) {
            return ICE;
        }
        if (material == Material.leaves || material == Material.wood) {
            return TREE;
        }
        if (material == Material.water) {
            return WATER;
        }
        return OTHER;
    }

    /**
     * Compares the chunk now with how it arrived.
     *
     * @param counts filled with: columns changed, snow added, ice added, tree added, other changes, snow/ice
     *               predicted and there, there but not predicted, predicted and not there
     */
    static void compare(Chunk chunk, Snapshot before, boolean noSky, int[] counts) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                int y = ChunkScanner.surfaceY(chunk, lx, lz, noSky);
                int top = -1;
                int kindNow = NONE;
                if (y >= 0) {
                    Block block = chunk.getBlock(lx, y, lz);
                    top = Block.getIdFromBlock(block) << 4 | chunk.getBlockMetadata(lx, y, lz);
                    kindNow = kind(block);
                }
                if (top != before.top[i] || y != before.height[i]) {
                    counts[0]++;
                    int kindBefore = before.top[i] < 0 ? NONE : kind(Block.getBlockById(before.top[i] >> 4));
                    if (kindNow == SNOW && kindBefore != SNOW) {
                        counts[1]++;
                    } else if (kindNow == ICE && kindBefore != ICE) {
                        counts[2]++;
                    } else if (kindNow == TREE && kindBefore != TREE) {
                        counts[3]++;
                    } else {
                        counts[4]++;
                    }
                }
                boolean coldNow = kindNow == SNOW || kindNow == ICE;
                if (coldNow && before.coldPredicted[i]) {
                    counts[5]++;
                } else if (coldNow) {
                    counts[6]++;
                } else if (before.coldPredicted[i]) {
                    counts[7]++;
                }
            }
        }
    }
}
