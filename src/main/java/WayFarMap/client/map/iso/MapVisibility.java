package WayFarMap.client.map.iso;

import java.util.Arrays;
import java.util.function.LongPredicate;

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
 * seen. Only the blocks of chunks the map has (stored, or the chunk being copied) hide anything: a chunk loaded but
 * never put on the map (the ring around an area loaded with {@code /wf chunkload}) is empty for the tracer. A line of
 * sight that leaves the chunks within {@link #RADIUS} of the block, goes into a chunk the map doesn't have, or rises
 * above the highest blocks around counts as seeing the sky. A picture is left out only if every line of sight from a
 * grid of points on the block's surfaces facing that view meets such a cube first.
 * <p>
 * Two steps, so that it costs far less than drawing: first, per view side, which cells open space from the sky reaches
 * at all, stepping only up and toward the viewer as every line of sight does ({@link #lit}, worked out once per cell
 * and chunk): a block none of whose surrounding cells is reached is hidden without following a single line. Only the
 * blocks that may be seen are looked at line by line, and a line starting in a cell not reached isn't followed.
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
    /**
     * Per look key (id and metadata, 20 bits): whether it stops the tracer's rays (see the class), {@link #STOPS_RAYS}
     * or {@link #LETS_THROUGH}, 0 not worked out yet.
     */
    private static final byte[] STOPS = new byte[1 << 20];

    /** Blocks across the chunks within {@link #RADIUS}. */
    private static final int SPAN = (2 * RADIUS + 1) * 16;
    /**
     * Per cell of those chunks, whether it stops the rays (1), lets them through (2), or isn't looked at yet (0):
     * the lines of sight of a chunk's blocks go through the same cells again and again. Shared, cleared for each chunk
     * (render thread).
     */
    private static final byte[] CELLS = new byte[SPAN * SPAN * 256];
    private static final byte STOPS_RAYS = 1, LETS_THROUGH = 2;
    /**
     * Per cell, two bits per view side: whether open space from the sky reaches it ({@link #REACHED}), not
     * ({@link #NOT_REACHED}), or not worked out yet (0). Shared, cleared for each chunk.
     */
    private static final byte[] LIT = new byte[SPAN * SPAN * 256];
    private static final int REACHED = 1, NOT_REACHED = 2;
    /**
     * Cells being worked out by {@link #lit} and the next step of each: a path of steps up and toward the viewer
     * crosses each of the region's layers once, so it is never longer than this.
     */
    private static final int[] STACK = new int[SPAN * 2 + 256 + 8];
    private static final byte[] STACK_STEP = new byte[STACK.length];

    /** Per view side: the step toward the viewer along x and along z (+1 or -1), and the line of sight's direction. */
    private static final int[] STEP_X = new int[ChunkBlocks.VIEWS], STEP_Z = new int[ChunkBlocks.VIEWS];
    private static final double[][] DIRECTION = new double[ChunkBlocks.VIEWS][];

    static {
        for (int rotation = 0; rotation < ChunkBlocks.VIEWS; rotation++) {
            IsoProjection p = IsoProjection.of(rotation);
            DIRECTION[rotation] = new double[] { -p.rayX, -p.rayY, -p.rayZ };
            STEP_X[rotation] = -p.rayX > 0 ? 1 : -1;
            STEP_Z[rotation] = -p.rayZ > 0 ? 1 : -1;
        }
    }

    private final int chunkX, chunkZ, baseX, baseZ;
    /** The chunks within {@link #RADIUS} the map has, null where it hasn't (or they aren't loaded). */
    private final Chunk[] chunks = new Chunk[(2 * RADIUS + 1) * (2 * RADIUS + 1)];
    /** Highest block of the chunks within {@link #RADIUS} (plus one): above it nothing hides anything. */
    private final int top;

    /** For the log: blocks found hidden by the first step alone, by following lines, and lines followed. */
    int hiddenByReach, hiddenByLines, seen;
    long linesFollowed, linesSkipped;
    long reachNanos, linesNanos;

    /**
     * @param onMap whether the map has a chunk (by its key, x in the high half): only those hide anything; the chunk
     *              itself always counts
     */
    MapVisibility(World world, Chunk chunk, LongPredicate onMap) {
        this.chunkX = chunk.xPosition;
        this.chunkZ = chunk.zPosition;
        this.baseX = (chunkX - RADIUS) << 4;
        this.baseZ = (chunkZ - RADIUS) << 4;
        int highest = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int cx = chunkX + dx, cz = chunkZ + dz;
                boolean counts = dx == 0 && dz == 0
                    || onMap != null && onMap.test(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                if (counts && ChunkScanner.isChunkReady(world, cx, cz)) {
                    Chunk other = world.getChunkFromChunkCoords(cx, cz);
                    chunks[(dz + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] = other;
                    highest = Math.max(highest, other.getTopFilledSegment() + 16);
                }
            }
        }
        top = Math.min(256, highest);
        Arrays.fill(CELLS, 0, top * SPAN * SPAN, (byte) 0);
        Arrays.fill(LIT, 0, top * SPAN * SPAN, (byte) 0);
    }

    /** Resource packs changed: icons may have holes now or none. */
    static void clear() {
        Arrays.fill(STOPS, (byte) 0);
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
                if (!boxSeen(x, y, z, rotation)) {
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
        boolean facing = false, reached = false;
        long t0 = System.nanoTime();
        for (int rotation = 0; rotation < ChunkBlocks.VIEWS; rotation++) {
            double[] d = DIRECTION[rotation];
            if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] <= 1e-9) {
                // Facing away from this view (the bottom from all of them).
                continue;
            }
            facing = true;
            // Every line of sight from this side starts in the cell next to it.
            if (lit(x + n[0], y + n[1], z + n[2], rotation)) {
                reached = true;
                break;
            }
        }
        long t1 = System.nanoTime();
        reachNanos += t1 - t0;
        if (!facing) {
            return false;
        }
        if (!reached) {
            hiddenByReach++;
            return false;
        }
        boolean seenHere = false;
        for (int rotation = 0; rotation < ChunkBlocks.VIEWS && !seenHere; rotation++) {
            double[] d = DIRECTION[rotation];
            if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] <= 1e-9) {
                continue;
            }
            if (!lit(x + n[0], y + n[1], z + n[2], rotation)) {
                continue;
            }
            seenHere = faceSeen(x, y, z, 0, 0, 0, 1, 1, 1, side, rotation);
        }
        linesNanos += System.nanoTime() - t1;
        if (seenHere) {
            seen++;
        } else {
            hiddenByLines++;
        }
        return seenHere;
    }

    /** Whether any part of the block (with the margin) can be seen from the view side. */
    private boolean boxSeen(int x, int y, int z, int rotation) {
        long t0 = System.nanoTime();
        boolean reached = boxReached(x, y, z, rotation);
        long t1 = System.nanoTime();
        reachNanos += t1 - t0;
        if (!reached) {
            hiddenByReach++;
            return false;
        }
        double[] d = DIRECTION[rotation];
        double x0 = -MARGIN, y0 = 0, z0 = -MARGIN, x1 = 1 + MARGIN, y1 = 1 + MARGIN, z1 = 1 + MARGIN;
        // A line of sight through the box leaves it through one of the sides facing the viewer: the top, and one
        // side along x and one along z.
        boolean seenHere = faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, 1, rotation)
            || faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, d[0] > 0 ? 5 : 4, rotation)
            || faceSeen(x, y, z, x0, y0, z0, x1, y1, z1, d[2] > 0 ? 3 : 2, rotation);
        linesNanos += System.nanoTime() - t1;
        if (seenHere) {
            seen++;
        } else {
            hiddenByLines++;
        }
        return seenHere;
    }

    /**
     * Whether open space from the sky reaches one of the cells the lines of sight from the box's sides facing the
     * viewer start in: those around it one layer up (the top, with the margin reaching into the cells next to it),
     * and the ones beside it toward the viewer along x and along z (its height and one up, with the margin).
     */
    private boolean boxReached(int x, int y, int z, int rotation) {
        int sx = STEP_X[rotation], sz = STEP_Z[rotation];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (lit(x + dx, y + 1, z + dz, rotation)) {
                    return true;
                }
            }
        }
        for (int dy = 0; dy <= 1; dy++) {
            for (int d = -1; d <= 1; d++) {
                if (lit(x + sx, y + dy, z + d, rotation) || lit(x + d, y + dy, z + sz, rotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a line of sight from any point of the grid on the side of the box (relative to the block) is free;
     * lines starting in a cell open space from the sky doesn't reach are blocked without being followed.
     */
    private boolean faceSeen(int x, int y, int z, double x0, double y0, double z0, double x1, double y1, double z1,
        int side, int rotation) {
        double[] d = DIRECTION[rotation];
        int[] n = NORMALS[side];
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
                double ox = x + px + n[0] * 1e-4, oy = y + py + n[1] * 1e-4, oz = z + pz + n[2] * 1e-4;
                int cx = floor(ox), cy = floor(oy), cz = floor(oz);
                if ((cx != x || cy != y || cz != z) && !lit(cx, cy, cz, rotation)) {
                    // Every free line from here goes through cells open space reaches: this one is blocked.
                    linesSkipped++;
                    continue;
                }
                linesFollowed++;
                if (!blocked(ox, oy, oz, d[0], d[1], d[2], x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a cell stops the rays: 1 if so, 2 if not, 0 if a line of sight reaching it counts as seeing the sky
     * (above the blocks around, outside the chunks looked at, a chunk the map doesn't have).
     */
    private int cell(int x, int y, int z) {
        if (y >= top || y < 0) {
            return 0;
        }
        int gx = x - baseX, gz = z - baseZ;
        if (gx < 0 || gz < 0 || gx >= SPAN || gz >= SPAN) {
            return 0;
        }
        int index = (y * SPAN + gz) * SPAN + gx;
        byte known = CELLS[index];
        if (known == 0) {
            Chunk chunk = chunks[(gz >> 4) * (2 * RADIUS + 1) + (gx >> 4)];
            if (chunk == null) {
                return 0;
            }
            Block block = chunk.getBlock(x & 15, y, z & 15);
            // Metadata as the map keeps it (4 bits; some mods give blocks more).
            int meta = chunk.getBlockMetadata(x & 15, y, z & 15) & 15;
            boolean stops = block.getMaterial() != Material.air
                && stops(Block.getIdFromBlock(block) & 0xFFFF | meta << 16);
            known = stops ? STOPS_RAYS : LETS_THROUGH;
            CELLS[index] = known;
        }
        return known;
    }

    /**
     * Whether open space from the sky reaches the cell, for a view side: it doesn't stop the rays, and the cell above
     * it or the one next to it toward the viewer (along x or z) is reached, or counts as the sky. Every line of sight
     * toward the viewer steps from cell to cell only that way, so a line from a cell not reached meets a cell that
     * stops the rays (more cells are reached than any line sees: it is on the safe side). Worked out once per cell and
     * chunk; the cells along the way are worked out with it (render thread).
     */
    private boolean lit(int x, int y, int z, int rotation) {
        int first = cell(x, y, z);
        if (first == 0) {
            return true;
        }
        if (first == STOPS_RAYS) {
            return false;
        }
        int shift = rotation * 2;
        int start = index(x, y, z);
        int state = LIT[start] >> shift & 3;
        if (state != 0) {
            return state == REACHED;
        }
        int sx = STEP_X[rotation], sz = STEP_Z[rotation];
        int depth = 0;
        STACK[0] = start;
        STACK_STEP[0] = 0;
        while (depth >= 0) {
            int at = STACK[depth];
            int step = STACK_STEP[depth];
            int gx = at % SPAN, gz = at / SPAN % SPAN, gy = at / (SPAN * SPAN);
            int result = 0;
            while (step < 3) {
                int nx = gx + baseX + (step == 1 ? sx : 0), ny = gy + (step == 0 ? 1 : 0),
                    nz = gz + baseZ + (step == 2 ? sz : 0);
                int next = cell(nx, ny, nz);
                if (next == 0) {
                    result = REACHED;
                    break;
                }
                if (next == STOPS_RAYS) {
                    step++;
                    continue;
                }
                int nextIndex = index(nx, ny, nz);
                int nextState = LIT[nextIndex] >> shift & 3;
                if (nextState == REACHED) {
                    result = REACHED;
                    break;
                }
                if (nextState == NOT_REACHED) {
                    step++;
                    continue;
                }
                // Not worked out yet: that one first, then back here to the same step.
                STACK_STEP[depth] = (byte) step;
                depth++;
                STACK[depth] = nextIndex;
                STACK_STEP[depth] = 0;
                result = -1;
                break;
            }
            if (result == -1) {
                continue;
            }
            if (result == 0) {
                result = NOT_REACHED;
            }
            LIT[at] = (byte) (LIT[at] & ~(3 << shift) | result << shift);
            depth--;
        }
        return (LIT[start] >> shift & 3) == REACHED;
    }

    /** Index of a cell inside the chunks looked at (see {@link #cell}). */
    private int index(int x, int y, int z) {
        return (y * SPAN + (z - baseZ)) * SPAN + (x - baseX);
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
                int here = cell(x, y, z);
                if (here == 0) {
                    return false;
                }
                if (here == STOPS_RAYS) {
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
        byte known = STOPS[key];
        if (known != 0) {
            return known == STOPS_RAYS;
        }
        BlockLooks.Look look = BlockLooks.get(key);
        boolean stops = look.opaque && !look.translucent && look.shape != BlockLooks.SHAPE_LIQUID;
        for (int side = 0; stops && side < 6; side++) {
            BlockLooks.Texture texture = look.textures[side];
            stops = texture != null && texture.solid();
        }
        STOPS[key] = stops ? STOPS_RAYS : LETS_THROUGH;
        return stops;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
