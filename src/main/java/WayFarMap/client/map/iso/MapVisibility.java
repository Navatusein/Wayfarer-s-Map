package WayFarMap.client.map.iso;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.client.map.ChunkScanner;

/**
 * Which pictures of a block the 3D map can show at all. The map has no cut-away: its rays come down from the sky at 30
 * degrees and stop where they meet something solid, so a machine on the floor of a hall under a roof is never seen
 * from any side, and one standing against a wall not from the side of the wall; their pictures from there are not
 * drawn (drawing the blocks of bases took most of the time of copying chunks). Render thread.
 * <p>
 * On the safe side, like the tracer: only what stops its rays hides anything. That is a solid cube (opaque, its whole
 * cell) whose icons are drawn on every texel of every side; through glass, leaves, bars, water, a texture with holes,
 * a block whose icons couldn't be read or a model that isn't a full cube the rays go on, and what is behind counts as
 * seen. A line of sight that leaves the chunks within {@link #RADIUS} of the block, goes into a chunk not loaded, or
 * rises above the highest blocks around counts as seeing the sky. A picture is left out only if every line of sight
 * from a grid of points on the block's surfaces facing that view meets such a cube first.
 */
final class MapVisibility {

    /** Chunks around the block's whose blocks may hide it; a change in one of them looks again (see IsoMap). */
    static final int RADIUS = 2;
    /** Points of a side the lines of sight start from, across it and along it. */
    private static final double[] GRID = { 0.02, 0.26, 0.5, 0.74, 0.98 };
    /**
     * Room around a block that isn't a cube: models of tile entities reach a little past their cell (the pictures
     * are cut to the block's column with this margin); farther ones are not looked at (always drawn).
     */
    private static final double MARGIN = 0.25;
    /** At most this many cells a line of sight goes through before it counts as reaching the sky. */
    private static final int MAX_CELLS = 600;
    /** Per look key: whether it stops the tracer's rays (see the class). */
    private static final Map<Integer, Boolean> STOPS = new HashMap<>();

    /** Blocks across the chunks within {@link #RADIUS}. */
    private static final int SPAN = (2 * RADIUS + 1) * 16;
    /**
     * Per cell of those chunks, whether it stops the rays (1), lets them through (2), or isn't looked at yet (0):
     * the lines of sight of a chunk's blocks go through the same cells again and again, and looking up the block each
     * time took 65 microseconds a block (more than drawing some). Shared, cleared for each chunk (render thread).
     */
    private static final byte[] CELLS = new byte[SPAN * SPAN * 256];
    private static final byte STOPS_RAYS = 1, LETS_THROUGH = 2;

    private final int chunkX, chunkZ, baseX, baseZ;
    /** The chunks within {@link #RADIUS}, null where not loaded. */
    private final Chunk[] chunks = new Chunk[(2 * RADIUS + 1) * (2 * RADIUS + 1)];
    /** Highest block of the chunks within {@link #RADIUS} (plus one): above it nothing hides anything. */
    private final int top;

    MapVisibility(World world, Chunk chunk) {
        this.chunkX = chunk.xPosition;
        this.chunkZ = chunk.zPosition;
        this.baseX = (chunkX - RADIUS) << 4;
        this.baseZ = (chunkZ - RADIUS) << 4;
        int highest = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int cx = chunkX + dx, cz = chunkZ + dz;
                if (ChunkScanner.isChunkReady(world, cx, cz)) {
                    Chunk other = world.getChunkFromChunkCoords(cx, cz);
                    chunks[(dz + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] = other;
                    highest = Math.max(highest, other.getTopFilledSegment() + 16);
                }
            }
        }
        top = Math.min(256, highest);
        Arrays.fill(CELLS, 0, top * SPAN * SPAN, (byte) 0);
    }

    /** Resource packs changed: icons may have holes now or none. */
    static void clear() {
        STOPS.clear();
    }

    /**
     * The block's pictures the map can't show, as bits: for a solid cube its sides (0 down .. 5 east, as
     * {@link ChunkBlocks#PER_CELL}), else its view sides (0..3, {@link IsoProjection#rotation}).
     */
    int hidden(int x, int y, int z, boolean cube) {
        int mask = 0;
        if (cube) {
            for (int side = 0; side < 6; side++) {
                if (!sideSeen(x, y, z, side)) {
                    mask |= 1 << side;
                }
            }
        } else {
            for (int rotation = 0; rotation < ChunkBlocks.VIEWS; rotation++) {
                if (!boxSeen(x, y, z, IsoProjection.of(rotation))) {
                    mask |= 1 << rotation;
                }
            }
        }
        return mask;
    }

    /** Normals of the sides, by side number. */
    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    /** Whether a side of a solid cube can be seen from any view side. */
    private boolean sideSeen(int x, int y, int z, int side) {
        int[] n = NORMALS[side];
        for (int rotation = 0; rotation < ChunkBlocks.VIEWS; rotation++) {
            IsoProjection p = IsoProjection.of(rotation);
            double dx = -p.rayX, dy = -p.rayY, dz = -p.rayZ;
            if (n[0] * dx + n[1] * dy + n[2] * dz <= 1e-9) {
                // Facing away from this view (the bottom from all of them).
                continue;
            }
            if (faceSeen(x, y, z, 0, 0, 0, 1, 1, 1, side, dx, dy, dz)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any part of the block (with the margin) can be seen from the view side. */
    private boolean boxSeen(int x, int y, int z, IsoProjection p) {
        double dx = -p.rayX, dy = -p.rayY, dz = -p.rayZ;
        double x0 = -MARGIN, y0 = 0, z0 = -MARGIN, x1 = 1 + MARGIN, y1 = 1 + MARGIN, z1 = 1 + MARGIN;
        // A line of sight through the box leaves it through one of the sides facing the viewer: the top, and one
        // side along x and one along z.
        return faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, 1, dx, dy, dz)
            || faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, dx > 0 ? 5 : 4, dx, dy, dz)
            || faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, dz > 0 ? 3 : 2, dx, dy, dz);
    }

    /** Whether a line of sight from any point of the grid on the side of the box (relative to the block) is free. */
    private boolean faceSeen(int x, int y, int z, double x0, double y0, double z0, double x1, double y1, double z1,
        int side, double dx, double dy, double dz) {
        for (double a : GRID) {
            for (double b : GRID) {
                double px, py, pz;
                switch (side) {
                    case 0:
                    case 1:
                        px = x0 + (x1 - x0) * a;
                        py = side == 1 ? y1 : y0;
                        pz = z0 + (z1 - z0) * b;
                        break;
                    case 2:
                    case 3:
                        px = x0 + (x1 - x0) * a;
                        py = y0 + (y1 - y0) * b;
                        pz = side == 3 ? z1 : z0;
                        break;
                    default:
                        px = side == 5 ? x1 : x0;
                        py = y0 + (y1 - y0) * b;
                        pz = z0 + (z1 - z0) * a;
                        break;
                }
                // A hair outside the side, so the line starts in the cell it looks into.
                int[] n = NORMALS[side];
                if (!blocked(x + px + n[0] * 1e-4, y + py + n[1] * 1e-4, z + pz + n[2] * 1e-4, dx, dy, dz, x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the line of sight toward the viewer meets something that stops the tracer's rays (not the block). */
    private boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, int sx, int sy, int sz) {
        int x = floor(ox), y = floor(oy), z = floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = Math.abs(1 / dx), deltaY = 1 / dy, deltaZ = Math.abs(1 / dz);
        double maxX = (dx > 0 ? x + 1 - ox : ox - x) * deltaX;
        double maxY = (y + 1 - oy) * deltaY;
        double maxZ = (dz > 0 ? z + 1 - oz : oz - z) * deltaZ;
        for (int cells = 0; cells < MAX_CELLS; cells++) {
            if (x != sx || y != sy || z != sz) {
                if (y >= top || y < 0) {
                    return false;
                }
                int gx = x - baseX, gz = z - baseZ;
                if (gx < 0 || gz < 0 || gx >= SPAN || gz >= SPAN) {
                    return false;
                }
                int index = (y * SPAN + gz) * SPAN + gx;
                byte known = CELLS[index];
                if (known == 0) {
                    Chunk chunk = chunks[(gz >> 4) * (2 * RADIUS + 1) + (gx >> 4)];
                    if (chunk == null) {
                        // Not loaded: what is there isn't known, counts as open.
                        return false;
                    }
                    Block block = chunk.getBlock(x & 15, y, z & 15);
                    boolean stops = block.getMaterial() != Material.air
                        && stops(Block.getIdFromBlock(block) | chunk.getBlockMetadata(x & 15, y, z & 15) << 16);
                    known = stops ? STOPS_RAYS : LETS_THROUGH;
                    CELLS[index] = known;
                }
                if (known == STOPS_RAYS) {
                    return true;
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

    /** Whether the tracer's rays stop at the block wherever they meet it (see the class). */
    private static boolean stops(int key) {
        Boolean known = STOPS.get(key);
        if (known != null) {
            return known;
        }
        BlockLooks.Look look = BlockLooks.get(key);
        boolean stops = look.opaque && !look.translucent && look.shape != BlockLooks.SHAPE_LIQUID;
        for (int side = 0; stops && side < 6; side++) {
            BlockLooks.Texture texture = look.textures[side];
            stops = texture != null && texture.solid();
        }
        if (STOPS.size() > 100_000) {
            STOPS.clear();
        }
        STOPS.put(key, stops);
        return stops;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
