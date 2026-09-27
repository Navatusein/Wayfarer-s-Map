package WayFarMap.client.map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
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

    public static void scan(World world, Chunk chunk, MapDimension dimension) {
        int cx = chunk.xPosition;
        int cz = chunk.zPosition;
        boolean noSky = world.provider.hasNoSky;

        // Heights of the row just north of this chunk, for relief shading across the chunk border.
        int[] northHeights = new int[16];
        Chunk north = isChunkReady(world, cx, cz - 1) ? world.getChunkFromChunkCoords(cx, cz - 1) : null;
        for (int lx = 0; lx < 16; lx++) {
            northHeights[lx] = north != null ? findSurface(north, lx, 15, noSky) : NO_BLOCK;
        }

        MapRegion region = dimension
            .getRegion((cx * 16) >> MapRegion.SHIFT, (cz * 16) >> MapRegion.SHIFT, true);
        int baseX = (cx * 16) & (MapRegion.SIZE - 1);
        int baseZ = (cz * 16) & (MapRegion.SIZE - 1);

        for (int lx = 0; lx < 16; lx++) {
            int previousHeight = northHeights[lx];
            for (int lz = 0; lz < 16; lz++) {
                int y = findSurface(chunk, lx, lz, noSky);
                int argb = 0;
                if (y != NO_BLOCK) {
                    int rgb = columnColor(world, chunk, lx, y, lz);
                    if (previousHeight != NO_BLOCK) {
                        int diff = Math.max(-4, Math.min(4, y - previousHeight));
                        rgb = BlockColors.shade(rgb, 1.0f + diff * 0.05f);
                    }
                    argb = 0xFF000000 | rgb;
                }
                region.setPixel(baseX + lx, baseZ + lz, argb);
                previousHeight = y;
            }
        }
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
