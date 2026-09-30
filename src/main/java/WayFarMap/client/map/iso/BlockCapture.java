package WayFarMap.client.map.iso;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import WayFarMap.Config;

/** Copies the blocks of a loaded chunk that the 3D map can see (render thread; the rest is done in the background). */
final class BlockCapture {

    /** How much deeper than its own floor a chunk is stored for a neighbour's lower ground. */
    private static final int MAX_EXTRA_DEPTH = 32;

    private BlockCapture() {}

    /** Lowest floor of the columns next to the chunk in the loaded neighbour chunks; 256 if none. */
    private static int neighbourFloor(World world, Chunk chunk, boolean noSky) {
        int lowest = 256;
        int[][] sides = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        for (int[] side : sides) {
            int cx = chunk.xPosition + side[0], cz = chunk.zPosition + side[1];
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) {
                continue;
            }
            Chunk other = world.getChunkFromChunkCoords(cx, cz);
            if (other == null || other.isEmpty()) {
                continue;
            }
            int top = other.getTopFilledSegment() + 15;
            for (int i = 0; i < 16; i++) {
                // The row of the neighbour that touches this chunk.
                int x = side[0] == 0 ? i : side[0] < 0 ? 15 : 0;
                int z = side[1] == 0 ? i : side[1] < 0 ? 15 : 0;
                lowest = Math.min(lowest, floor(other, x, z, top, noSky));
            }
        }
        return lowest;
    }

    /** A block nothing below can be seen through: solid cubes, and lava. */
    private static boolean hidesBelow(Block block) {
        return block.isOpaqueCube() || block.getMaterial() == Material.lava;
    }

    /** First block from the top (under the ceiling without sky) that hides what is below it; 256 if none. */
    private static int floor(Chunk chunk, int x, int z, int top, boolean noSky) {
        int y = top;
        if (noSky) {
            y = Math.min(top, 127);
            while (y > 0 && chunk.getBlock(x, y, z)
                .getMaterial() != Material.air) {
                y--;
            }
        }
        for (; y >= 0; y--) {
            if (hidesBelow(chunk.getBlock(x, y, z))) {
                return underOverhang(chunk, x, z, y);
            }
        }
        return 256;
    }

    /**
     * The floor of a column whose first block from the top that hides what is below is at {@code y}: under a roof
     * (with open space below it) the floor under that space, down to {@link Config#isoOverhangDepth} blocks, several
     * storeys if there are; {@code y} itself for solid ground. Seen from the side under an overhang, the space would
     * otherwise be drawn as solid ground.
     */
    private static int underOverhang(Chunk chunk, int x, int z, int y) {
        int floor = y;
        boolean open = false;
        int depth = Math.max(0, Config.isoOverhangDepth);
        for (int yy = y - 1; yy >= 0 && yy >= y - depth; yy--) {
            if (!hidesBelow(chunk.getBlock(x, yy, z))) {
                open = true;
            } else if (open) {
                floor = yy;
                open = false;
            }
        }
        return floor;
    }

    /**
     * @param whole down to the bottom of the world (a chunk left at the edge of the map, whose side shows)
     * @return the chunk's blocks, or null if it has nothing to show
     */
    static ChunkBlocks capture(World world, Chunk chunk, boolean whole) {
        boolean noSky = world.provider.hasNoSky;
        int top = chunk.getTopFilledSegment() + 15;
        if (top < 0) {
            return null;
        }
        // Per column: the first open space from the top (under the ceiling where there is no sky), the highest block
        // below it and the floor: the first block that hides what is under it.
        int[] start = new int[256];
        int yMax = -1, yMin = 256;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = top;
                if (noSky) {
                    y = Math.min(top, 127);
                    while (y > 0 && chunk.getBlock(x, y, z)
                        .getMaterial() != Material.air) {
                        y--;
                    }
                }
                start[z * 16 + x] = y;
                int highest = -1;
                int floor = 0;
                for (int yy = y; yy >= 0; yy--) {
                    Block block = chunk.getBlock(x, yy, z);
                    if (block.getMaterial() == Material.air) {
                        continue;
                    }
                    if (highest < 0) {
                        highest = yy;
                    }
                    if (hidesBelow(block)) {
                        floor = underOverhang(chunk, x, z, yy);
                        break;
                    }
                }
                if (highest >= 0) {
                    yMax = Math.max(yMax, highest);
                    yMin = Math.min(yMin, floor);
                }
            }
        }
        if (yMax < 0) {
            return null;
        }
        // A void world (nothing at the bottom, like personal worlds or the End): there is no ground under the
        // floors, platforms hang over empty space. The whole chunk down to the bottom of the world is kept, so what is
        // under the platforms is seen as it is (empty) instead of the solid ground assumed below the stored blocks.
        boolean openBelow = false;
        for (int z = 0; z < 16 && !openBelow; z++) {
            for (int x = 0; x < 16; x++) {
                if (chunk.getBlock(x, 0, z)
                    .getMaterial() == Material.air) {
                    openBelow = true;
                    break;
                }
            }
        }
        if (openBelow) {
            yMin = 0;
        }
        // Cliffs at the chunk's edge are seen from lower ground next door: keep the blocks down to that ground.
        int neighbourFloor = neighbourFloor(world, chunk, noSky);
        if (neighbourFloor < yMin) {
            yMin = Math.max(neighbourFloor, yMin - MAX_EXTRA_DEPTH);
        }
        int picturesFrom = yMin;
        // At the edge of the explored map the 3D map shows the chunk's side, all the way down: the whole chunk is
        // kept, so the edge shows the real ground (layers, ores, caves).
        if (whole) {
            yMin = 0;
        }
        int[] cells = new int[(yMax - yMin + 1) << 8];
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        int i = 0;
        for (int y = yMin; y <= yMax; y++) {
            // Read from the chunk's sections directly: a copy reads tens of thousands of blocks.
            ExtendedBlockStorage storage = storages[y >> 4];
            int ly = y & 15;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    if (y > start[z * 16 + x]) {
                        // The ceiling of a dimension without sky is left out, so the map looks in from above.
                        cells[i] = ChunkBlocks.OPEN_SKY;
                        continue;
                    }
                    if (storage == null) {
                        // An empty section, only air: lit by the sky above the height map, as the game has it.
                        int sky = noSky || y >= chunk.getHeightValue(x, z) ? 15 : 0;
                        cells[i] = sky << 24;
                        continue;
                    }
                    Block block = storage.getBlockByExtId(x, ly, z);
                    int id = Block.getIdFromBlock(block);
                    int sky = noSky ? 15 : storage.getExtSkylightValue(x, ly, z);
                    int light = storage.getExtBlocklightValue(x, ly, z);
                    int meta = id == 0 ? 0 : storage.getExtBlockMetadata(x, ly, z);
                    if ((meta & 8) != 0 && block instanceof BlockDoublePlant
                        && y > 0
                        && chunk.getBlock(x, y - 1, z) == block) {
                        // The top half of a double plant: which plant it is, is kept by the bottom half.
                        meta = 8 | chunk.getBlockMetadata(x, y - 1, z) & 7;
                    }
                    cells[i] = (id & 0xFFFF) | (meta & 15) << 16 | (light & 15) << 20 | (sky & 15) << 24;
                }
            }
        }
        int[] grass = new int[256], foliage = new int[256], water = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = z * 16 + x;
                BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(x, z, world.getWorldChunkManager());
                int wx = chunk.xPosition * 16 + x, wz = chunk.zPosition * 16 + z;
                int y = Math.max(0, Math.min(255, start[column]));
                grass[column] = color(() -> biome.getBiomeGrassColor(wx, y, wz), 0x7FB238);
                foliage[column] = color(() -> biome.getBiomeFoliageColor(wx, y, wz), 0x48B518);
                water[column] = biome == null ? 0xFFFFFF : biome.waterColorMultiplier & 0xFFFFFF;
            }
        }
        ChunkBlocks blocks = new ChunkBlocks(yMin, yMax, cells, grass, foliage, water);
        blocks.picturesFrom = picturesFrom;
        return blocks;
    }

    private interface ColorSource {

        int get();
    }

    /** Biome colors can come from mods' events; one that fails gets the default. */
    private static int color(ColorSource source, int fallback) {
        try {
            return source.get() & 0xFFFFFF;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
