package WayFarMap.client.map.iso;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.client.map.ChunkScanner;

/**
 * Whether a block can be seen on the 3D map at all: the map has no cut-away, its rays come down from the sky at 30
 * degrees and stop at the first solid block, so a machine on the floor of a hall under a roof is never seen from any
 * side, yet its pictures are taken like any other. For the 3D log for now (how many such blocks there are, and of which
 * kinds), to see whether leaving their pictures out is worth it. Render thread.
 * <p>
 * On the safe side: a block counts as hidden only if every line of sight from the points of its surface toward the
 * viewer, from each of the four view sides, meets a solid cube (an opaque full block) first. A line reaching a chunk
 * that isn't loaded, or above the highest blocks around, or passing only see-through blocks (glass, leaves, water)
 * counts as seeing the sky.
 */
final class MapVisibility {

    /** Points of a block's surface the lines of sight start from: its corners and the middles of its sides. */
    private static final double[][] POINTS;

    static {
        double lo = 0.02, hi = 0.98, mid = 0.5;
        POINTS = new double[][] { { lo, lo, lo }, { hi, lo, lo }, { lo, hi, lo }, { hi, hi, lo }, { lo, lo, hi },
            { hi, lo, hi }, { lo, hi, hi }, { hi, hi, hi }, { mid, hi, mid }, { mid, lo, mid }, { lo, mid, mid },
            { hi, mid, mid }, { mid, mid, lo }, { mid, mid, hi } };
    }

    /** At most this many cells a line of sight goes through before it counts as reaching the sky. */
    private static final int MAX_CELLS = 600;

    private final World world;
    /** Highest block of the chunks within two of the chunk looked at (plus one): above it nothing hides anything. */
    private final int top;
    private int lastChunkX = Integer.MIN_VALUE, lastChunkZ;
    private boolean lastChunkReady;

    MapVisibility(World world, Chunk chunk) {
        this.world = world;
        int highest = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int cx = chunk.xPosition + dx, cz = chunk.zPosition + dz;
                if (ChunkScanner.isChunkReady(world, cx, cz)) {
                    highest = Math.max(
                        highest,
                        world.getChunkFromChunkCoords(cx, cz)
                            .getTopFilledSegment() + 16);
                }
            }
        }
        top = Math.min(256, highest);
    }

    /** Whether the block can't be seen from any view side of the map. */
    boolean hidden(int x, int y, int z) {
        for (int rotation = 0; rotation < 4; rotation++) {
            IsoProjection p = IsoProjection.of(rotation);
            for (double[] point : POINTS) {
                if (!blocked(x + point[0], y + point[1], z + point[2], -p.rayX, -p.rayY, -p.rayZ, x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the line of sight from the point toward the viewer meets a solid cube (other than the block's own). */
    private boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, int sx, int sy, int sz) {
        int x = floor(ox), y = floor(oy), z = floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = Math.abs(1 / dx), deltaY = 1 / dy, deltaZ = Math.abs(1 / dz);
        double maxX = (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
        double maxY = (y + 1 - oy) * deltaY;
        double maxZ = (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;
        for (int cells = 0; cells < MAX_CELLS; cells++) {
            if (x != sx || y != sy || z != sz) {
                if (y >= top) {
                    return false;
                }
                if (!ready(x >> 4, z >> 4)) {
                    return false;
                }
                Block block = world.getBlock(x, y, z);
                if (block.getMaterial() != Material.air) {
                    int key = Block.getIdFromBlock(block) | world.getBlockMetadata(x, y, z) << 16;
                    if (BlockLooks.get(key).opaque) {
                        return true;
                    }
                }
            }
            if (maxX < maxY && maxX < maxZ) {
                x += stepX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                y++;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }
        }
        return false;
    }

    private boolean ready(int chunkX, int chunkZ) {
        if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            lastChunkReady = ChunkScanner.isChunkReady(world, chunkX, chunkZ);
        }
        return lastChunkReady;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
