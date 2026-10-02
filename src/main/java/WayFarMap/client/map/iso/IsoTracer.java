package WayFarMap.client.map.iso;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws tiles of the 3D map by following, for every pixel, the line of sight into the world block by block (like
 * Dynmap's HD renderer) until it meets something solid. Faces show the pixel of the block's texture where they are
 * hit, tinted by the biome, shaded by the side they face (like the game) and by the sky and block light in front of
 * them, so overhangs, forests and deep water get darker. Water and stained glass let what is behind shine through.
 * Blocks the game draws in its own way show their sprite taken from the game ({@link FaceRenderer}).
 * <p>
 * Every pixel is worked out twice at once: by day, and by night, when the sky gives little light and torches, lamps
 * and lava light up their surroundings in warm light. One tracer per thread.
 */
final class IsoTracer {

    /** Brightness of the six sides, as the game shades them: down, up, north, south, west, east. */
    private static final float[] SIDE_SHADE = { 0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f };
    /** Brightness of each light level by day (the game's curve, with a floor so caves aren't pitch black). */
    private static final float[] LIGHT = new float[16];
    /** Brightness of each light level at night: darker, so lit places stand out. */
    private static final float[] NIGHT_LIGHT = new float[16];
    /** At night the sky gives this much less light, as in the game. */
    private static final int NIGHT_SKY_DROP = 11;
    /**
     * Color of moonlight (open sky at night comes out like the flat map's night tint), and of torch light.
     */
    private static final float[] MOON = { 0.82f, 0.94f, 1.47f }, WARM = { 1.05f, 0.88f, 0.62f };
    /** Stone, for the ground under chunks where no solid block was stored. */
    private static final int STONE = 1;
    private static final int MAX_STEPS = 8000;
    /**
     * How far (in blocks) a sprite's pixels may stick out of its block's outline and still be the block's own (two
     * pixels of a sprite): only what reaches farther counts as a part of a model past its cell.
     */
    private static final double OUTLINE_MARGIN = 0.03;
    /** The largest place on a side's texture short of its far edge. */
    private static final double EDGE = Math.nextDown(1.0);
    /** How much of what is below the water's surface veils: the rest is the water body (see absorb). */
    private static final float WATER_SURFACE = 0.4f;
    /** Per block of water the ray passes, the share of light that becomes water color. */
    private static final double WATER_ABSORPTION = 0.65;
    /** Water seen in depth is darker than its surface. */
    private static final float WATER_DEPTH_SHADE = 0.62f;
    /** The outward direction of each side: down, up, north, south, west, east. */
    private static final int[][] OFFSETS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };
    /** Results of {@link #sprite}. */
    private static final int SPRITE_NONE = 0, SPRITE_STOP = 1, SPRITE_PASS = 2;
    /** Light of open sky (sky 15, no block light), packed like {@link #light}. */
    private static final int OPEN = 15 << 4;

    static {
        for (int level = 0; level < 16; level++) {
            float f = 1f - level / 15f;
            float game = (1f - f) / (f * 3f + 1f);
            LIGHT[level] = 0.28f + 0.72f * game;
            NIGHT_LIGHT[level] = 0.28f + 0.72f * game;
        }
    }

    private final BlockStore store;
    private IsoProjection projection;
    /** Texture detail: 0 = 16x16 texels per block ... 4 = one average color. */
    private int mip;
    /** Sprite detail: 0 = 64 pixels per block ... 7 = one color. */
    private int spriteMip;
    /** Step by step account of the rays, for the report of {@code /wfmap3d}; null normally. */
    StringBuilder debug;
    private static final String[] SIDE_NAMES = { "down", "up", "north", "south", "west", "east" };
    /**
     * Look key of the cell just passed when the ray met its translucent sprite, else 0: the next cell of the same
     * block (glass next to glass) isn't added again where the two sprites overlap along their shared edge.
     */
    private int spriteRun;
    /** Whether the last {@link #sprite} call met a drawn pixel. */
    private boolean spriteMet;
    /** Detail of the pictures of block sides: 0 = 32x32 per side ... 5 = one color. */
    private int pictureMip;

    /** Chunks looked at lately (direct mapped by position): blocks, or nothing. */
    private static final int CACHE = 1 << 10;
    private final long[] cacheKeys = new long[CACHE];
    private final Object[] cacheData = new Object[CACHE];
    private final int[] cacheTops = new int[CACHE];
    private static final Object NOTHING = new Object();

    // Result of the last ray.
    /** Height of the first surface hit and its side (-1 if none). */
    double hitY;
    int hitSide;
    /**
     * Height where the ray stopped seeing through (met a surface drawn whole, or nearly all the light was taken by
     * what it passed), NaN if it never did: models of mobs are hidden behind it, not behind glass or shallow water.
     */
    double solidY;
    /** Where along the ray the surface being added was met. */
    private double lastHitT;
    /** The color seen at night (the day color is returned by trace), ARGB. */
    int nightColor;
    /** Lowest "toward the viewer" distance any ray got to, for knowing which chunks a tile depends on. */
    double minToward;
    /** A sprite of the palette couldn't be read: the block was drawn from its icons, the tile should be again. */
    boolean incomplete;
    /**
     * For the log, per kind of block: rays that drew it from its icons for want of a picture (none in the copy, one
     * that couldn't be read, a solid cube's side without one); null while the log is off.
     */
    Map<Integer, int[]> fallbacks;

    // State of the current ray.
    private double rayU, rayV;
    private double ox, oy, oz;
    private double accR, accG, accB, nightR, nightG, nightB, transmit;
    private int currentChunkX = Integer.MIN_VALUE, currentChunkZ;
    private Object currentData;
    private int currentTop;
    /** Key of the liquid the ray is under the surface of, 0 if none, and the light over its surface. */
    private int insideLiquid;
    private int waterLight;
    /** Whether the last {@link #boxes} call met a box, and where. */
    private boolean boxHit;
    private double boxHitT;
    /** Index of the cell being looked at in its chunk's cells, -1 for the ground below them. */
    private int cellIndex;
    /** Sprites of blocks as the game draws them; null if there are none. */
    private final FacePalette palette;

    IsoTracer(BlockStore store, FacePalette palette) {
        this.store = store;
        this.palette = palette;
    }

    /**
     * A block's cell as the view sees it, around the block's center on the projection plane: a hexagon, its corners
     * (u, v) in turn. A sprite's pixels outside it are parts of a model larger than its block (the Blood Magic
     * altar), which rays through the block never meet.
     */
    private double[] outline;
    private static final int[] NO_CELLS = new int[0];
    /** Per palette generation, sprite and view side: whether the sprite reaches past its block's cell. */
    private static final Map<Long, Boolean> REACHES_OUT = new ConcurrentHashMap<>();

    /** The cells of a chunk whose sprites reach out, for one view side and palette. */
    private static final class Overhangs {

        final long key;
        final int[] cells;

        Overhangs(long key, int[] cells) {
            this.key = key;
            this.cells = cells;
        }
    }

    void reset(IsoProjection projection, int level) {
        this.projection = projection;
        outline = outline(projection);
        fallbacks = IsoLog.on() ? new HashMap<>() : null;
        double pixelsPerBlock = IsoProjection.pixelsPerBlock(level);
        mip = pixelsPerBlock >= 16 ? 0
            : pixelsPerBlock >= 8 ? 1 : pixelsPerBlock >= 4 ? 2 : pixelsPerBlock >= 2 ? 3 : 4;
        // No further than 8x8: smaller copies mix a block's top with its darker sides, darker than blocks drawn
        // from their icons (the tile's own four rays per pixel smooth the far levels instead).
        spriteMip = pixelsPerBlock >= 64 ? 0
            : pixelsPerBlock >= 32 ? 1 : pixelsPerBlock >= 16 ? 2 : pixelsPerBlock >= 8 ? 3 : 4;
        pictureMip = pixelsPerBlock >= 32 ? 0 : Math.min(5, mip + 1);
        Arrays.fill(cacheKeys, Long.MIN_VALUE);
        Arrays.fill(cacheData, null);
        currentChunkX = Integer.MIN_VALUE;
        minToward = Double.MAX_VALUE;
        incomplete = false;
    }

    /** The chunk data, from the tracer's cache. Sets {@link #currentData} and {@link #currentTop}. */
    private void enterChunk(int chunkX, int chunkZ) {
        currentChunkX = chunkX;
        currentChunkZ = chunkZ;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        // Rays run diagonally over the chunk grid: mix both coordinates well so a line of chunks doesn't collide.
        int slot = (chunkX * 0x9E3779B1 ^ chunkZ * 0x85EBCA6B) >>> 22;
        if (cacheKeys[slot] == key) {
            currentData = cacheData[slot];
            currentTop = cacheTops[slot];
            return;
        }
        Object data = NOTHING;
        int top = -1;
        if (store.top(chunkX, chunkZ) >= 0) {
            ChunkBlocks blocks = store.chunk(chunkX, chunkZ);
            if (blocks != null) {
                data = blocks;
                top = blocks.yMax;
                if (!blocks.looksReady) {
                    // All the chunk's blocks at once: one wait for the render thread, not one per block.
                    blocks.looksReady = BlockLooks.prepare(blocks.lookKeys());
                }
            }
        }
        cacheKeys[slot] = key;
        cacheData[slot] = data;
        cacheTops[slot] = top;
        currentData = data;
        currentTop = top;
        IsoLog.tileChunk(data instanceof ChunkBlocks ? 0 : 2);
    }

    /**
     * Follows the line of sight through the point (u, v) of the projection plane.
     *
     * @return the color seen by day, ARGB with straight alpha (0 where nothing was hit); the night color is in
     *         {@link #nightColor}
     */
    int trace(double u, double v) {
        IsoProjection p = projection;
        rayU = u;
        rayV = v;
        double y0 = IsoProjection.TOP - 1e-3;
        double toward = (v + y0 * IsoProjection.COS) / IsoProjection.SIN;
        ox = u * p.rightX + toward * p.towardX;
        oz = u * p.rightZ + toward * p.towardZ;
        oy = y0;
        double dx = p.rayX, dy = p.rayY, dz = p.rayZ;
        accR = accG = accB = 0;
        nightR = nightG = nightB = 0;
        transmit = 1;
        hitSide = -1;
        solidY = Double.NaN;
        lastHitT = 0;

        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = 1 / Math.abs(dx), deltaY = 1 / Math.abs(dy), deltaZ = 1 / Math.abs(dz);
        double t = 0;
        int x = floor(ox), y = floor(oy), z = floor(oz);
        double maxX = (stepX > 0 ? x + 1 - ox : ox - x) * deltaX;
        double maxY = (oy - y) * deltaY;
        double maxZ = (stepZ > 0 ? z + 1 - oz : oz - z) * deltaZ;
        int side = 1;
        int previousLight = OPEN;
        int previousKey = 0;
        insideLiquid = 0;
        spriteRun = 0;

        for (int steps = 0; steps < MAX_STEPS && y >= 0; steps++) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            if (chunkX != currentChunkX || chunkZ != currentChunkZ) {
                enterChunk(chunkX, chunkZ);
            }
            if (y > currentTop) {
                // Nothing here at this height: jump to where the ray leaves the chunk or comes down into its blocks.
                double exitX = ((stepX > 0 ? (chunkX + 1) << 4 : chunkX << 4) - ox) / dx;
                double exitZ = ((stepZ > 0 ? (chunkZ + 1) << 4 : chunkZ << 4) - oz) / dz;
                double down = currentTop >= 0 ? (currentTop + 1 - oy) / dy : Double.MAX_VALUE;
                double next = Math.min(Math.min(exitX, exitZ), down);
                if (next > t) {
                    t = next + 1e-7;
                    side = next == down ? 1 : next == exitX ? (stepX > 0 ? 4 : 5) : (stepZ > 0 ? 2 : 3);
                    double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
                    x = floor(px);
                    y = floor(py);
                    z = floor(pz);
                    maxX = t + (stepX > 0 ? x + 1 - px : px - x) * deltaX;
                    maxY = t + (py - y) * deltaY;
                    maxZ = t + (stepZ > 0 ? z + 1 - pz : pz - z) * deltaZ;
                    previousLight = OPEN;
                    previousKey = 0;
                    insideLiquid = 0;
                    continue;
                }
            }
            double exit = Math.min(maxX, Math.min(maxY, maxZ));
            if (y <= currentTop) {
                Object data = currentData;
                if (data instanceof ChunkBlocks) {
                    ChunkBlocks blocks = (ChunkBlocks) data;
                    int lx = x & 15, lz = z & 15;
                    if (y < blocks.yMin) {
                        // The ground below what was stored, in the world's usual layers.
                        BlockLooks.Look look = BlockLooks.get(ground(blocks, lx, lz, y));
                        if (debug != null) {
                            debug("  below the stored copy at " + x + "," + y + "," + z + ": ground drawn from icons");
                        }
                        cellIndex = -1;
                        face(look, blocks, lx, lz, side, t, x, y, z, previousLight);
                        break;
                    }
                    int layer = (y - blocks.yMin) >> 2;
                    int air = blocks.airBricks()[layer];
                    int brick = (lz >> 2) << 2 | lx >> 2;
                    // A model reaching past its cell shows in the air around it: that air is gone through block by
                    // block (only in chunks with such models).
                    int[] reaching = overhangs(blocks);
                    if ((air & 1 << brick) != 0 && reaching.length == 0) {
                        // Only air around here: jump to where the ray leaves the empty bricks under it, or the whole
                        // empty part of the chunk (layers where it holds nothing), instead of going block by block.
                        int x0, z0, size, bottom;
                        if ((air & ChunkBlocks.ALL_AIR) == ChunkBlocks.ALL_AIR) {
                            x0 = chunkX << 4;
                            z0 = chunkZ << 4;
                            size = 16;
                            bottom = blocks.yMin + ((air >>> 16) << 2);
                        } else {
                            x0 = x & ~3;
                            z0 = z & ~3;
                            size = 4;
                            bottom = blocks.yMin + (blocks.airFloor(layer, brick) << 2);
                        }
                        double exitX = ((stepX > 0 ? x0 + size : x0) - ox) / dx;
                        double exitZ = ((stepZ > 0 ? z0 + size : z0) - oz) / dz;
                        double down = (bottom - oy) / dy;
                        double next = Math.min(Math.min(exitX, exitZ), down);
                        if (next > t) {
                            // The light of the last block of air passed, as if it had been gone through block by block.
                            double before = Math.max(t, next - 1e-6);
                            int lastX = clampInt(floor(ox + dx * before), x0, x0 + size - 1);
                            int lastY = clampInt(floor(oy + dy * before), bottom, y);
                            int lastZ = clampInt(floor(oz + dz * before), z0, z0 + size - 1);
                            previousLight = light(blocks.cell(lastX & 15, lastY, lastZ & 15));
                            previousKey = 0;
                            insideLiquid = 0;
                            t = next + 1e-7;
                            side = next == down ? 1 : next == exitX ? (stepX > 0 ? 4 : 5) : (stepZ > 0 ? 2 : 3);
                            double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
                            x = floor(px);
                            y = floor(py);
                            z = floor(pz);
                            maxX = t + (stepX > 0 ? x + 1 - px : px - x) * deltaX;
                            maxY = t + (py - y) * deltaY;
                            maxZ = t + (stepZ > 0 ? z + 1 - pz : pz - z) * deltaZ;
                            continue;
                        }
                    }
                    cellIndex = ((y - blocks.yMin) << 8) | (lz << 4) | lx;
                    int cell = blocks.cells[cellIndex];
                    if (reaching.length > 0 && ChunkBlocks.blockId(cell) == 0
                        && reachingOut(blocks, reaching, x, y, z, side, t, previousLight) == SPRITE_STOP) {
                        break;
                    }
                    int key = ChunkBlocks.lookKey(cell);
                    boolean sameRun = spriteRun != 0 && spriteRun == key && previousKey == key;
                    spriteRun = 0;
                    if (key != insideLiquid) {
                        insideLiquid = 0;
                    }
                    if (ChunkBlocks.blockId(cell) != 0) {
                        BlockLooks.Look look = BlockLooks.get(key);
                        // Solid cubes show the game's pictures of their sides (in face); other blocks its sprites.
                        int spriteId = look.opaque || look.noPictures ? 0 : pictureId(blocks, projection.rotation);
                        if (debug != null) {
                            debug(
                                "  cell " + x + "," + y + "," + z + " " + BlockDiag.name(key)
                                    + " entered through " + SIDE_NAMES[side] + String.format(Locale.ROOT, " t=%.4f", t)
                                    + " look[opaque=" + look.opaque + " fullCube=" + look.fullCube + " translucent="
                                    + look.translucent + " complex=" + look.complex + " skipSame=" + look.skipSame
                                    + "] sprite=" + spriteId + (spriteId == FacePalette.EMPTY ? "(EMPTY)" : "")
                                    + " sameRun=" + sameRun + " light=" + Integer.toHexString(lightOf(cell)));
                        }
                        // Some blocks say light passes them while the world keeps none in their cell (GregTech
                        // machines): the brighter of the cell and the light in front of it.
                        int lightHere = look.lightPasses ? brighter(light(cell), previousLight) : previousLight;
                        spriteMet = false;
                        int drawn = spriteId == 0 ? SPRITE_NONE
                            : sprite(spriteId, look, x, y, z, side, t, lightHere, sameRun, true);
                        if (spriteMet && look.translucent) {
                            spriteRun = key;
                        }
                        if (fallbacks != null && look.complex && !look.opaque && !look.noPictures) {
                            if (spriteId == 0) {
                                fallbacks.computeIfAbsent(key, k -> new int[3])[0]++;
                            } else if (drawn == SPRITE_NONE) {
                                fallbacks.computeIfAbsent(key, k -> new int[3])[1]++;
                            }
                        }
                        if (drawn == SPRITE_STOP) {
                            break;
                        }
                        if (drawn == SPRITE_NONE && sample(
                            look,
                            key,
                            blocks,
                            cell,
                            lx,
                            lz,
                            side,
                            t,
                            exit,
                            x,
                            y,
                            z,
                            previousLight,
                            previousKey)) {
                            break;
                        }
                        // Passing over a liquid's surface (in the gap above it) is still in the air: the light of the
                        // water below would darken the next block's surface along every edge.
                        boolean overLiquid = look.shape == BlockLooks.SHAPE_LIQUID && insideLiquid != key;
                        if (look.lightPasses && !overLiquid) {
                            // Through the empty parts of a modded block (a plate, a pipe, a machine's frame): many
                            // keep no light in their cell, which would draw a dark rim on the floor around them.
                            previousLight = look.complex || spriteId != 0 ? brighter(previousLight, light(cell))
                                : light(cell);
                        }
                    } else {
                        previousLight = light(cell);
                    }
                    previousKey = key;
                }
            }
            // Next block along the ray.
            if (maxX < maxY && maxX < maxZ) {
                x += stepX;
                t = maxX;
                maxX += deltaX;
                side = stepX > 0 ? 4 : 5;
            } else if (maxY < maxZ) {
                y--;
                t = maxY;
                maxY += deltaY;
                side = 1;
            } else {
                z += stepZ;
                t = maxZ;
                maxZ += deltaZ;
                side = stepZ > 0 ? 2 : 3;
            }
        }
        if (debug != null) {
            debug(
                String.format(Locale.ROOT,
                    "  END after %s: first surface %s at y=%.3f, solid (hides mobs) at y=%s, light left %.2f",
                    steps(t), hitSide < 0 ? "none" : SIDE_NAMES[hitSide], hitY,
                    Double.isNaN(solidY) ? "none" : String.format(Locale.ROOT, "%.3f", solidY), transmit));
        }
        double reached = projection.toward(ox + dx * t, oz + dz * t);
        if (reached < minToward) {
            minToward = reached;
        }
        double alpha = 1 - transmit;
        if (alpha <= 0.004) {
            nightColor = 0;
            return 0;
        }
        int a = clamp(alpha * 255) << 24;
        nightColor = a | clamp(nightR / alpha) << 16 | clamp(nightG / alpha) << 8 | clamp(nightB / alpha);
        return a | clamp(accR / alpha) << 16 | clamp(accG / alpha) << 8 | clamp(accB / alpha);
    }

    /** The corners of a block's cell seen from the view, around its center, in turn (its convex outline). */
    static double[] outline(IsoProjection p) {
        double[][] points = new double[8][];
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) - 0.5, y = (i >> 1 & 1) - 0.5, z = (i >> 2 & 1) - 0.5;
            points[i] = new double[] { p.u(x, z), p.v(x, y, z) };
        }
        Arrays.sort(points, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        // Monotone chain: the lower and upper hull.
        double[][] hull = new double[16][];
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = n;
            for (int k = 0; k < 8; k++) {
                double[] q = points[pass == 0 ? k : 7 - k];
                while (n >= start + 2 && cross(hull[n - 2], hull[n - 1], q) <= 1e-9) {
                    n--;
                }
                hull[n++] = q;
            }
            n--;
        }
        double[] result = new double[n * 2];
        for (int i = 0; i < n; i++) {
            result[i * 2] = hull[i][0];
            result[i * 2 + 1] = hull[i][1];
        }
        return result;
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /** Whether a point (around a block's center) is within the block's outline, grown by the margin. */
    private boolean withinCell(double u, double v, double margin) {
        return within(outline, u, v, margin);
    }

    /** Whether a point is within an outline ({@link #outline}), grown by the margin (shrunk if negative). */
    static boolean within(double[] o, double u, double v, double margin) {
        int n = o.length / 2;
        for (int i = 0; i < n; i++) {
            double ax = o[i * 2], ay = o[i * 2 + 1];
            double bx = o[(i + 1) % n * 2], by = o[(i + 1) % n * 2 + 1];
            double ex = bx - ax, ey = by - ay;
            double side = ex * (v - ay) - ey * (u - ax);
            if (side < -margin * Math.sqrt(ex * ex + ey * ey)) {
                return false;
            }
        }
        return true;
    }

    /** Cells of the chunk with sprites reaching past them, for this view (worked out once per copy and view). */
    private int[] overhangs(ChunkBlocks blocks) {
        if (palette == null || blocks.faceCells.length == 0 || blocks.faceGeneration != palette.generation) {
            return NO_CELLS;
        }
        int rotation = projection.rotation;
        long key = (long) palette.generation << 2 | rotation;
        Object known = blocks.overhangs;
        if (known instanceof Overhangs && ((Overhangs) known).key == key) {
            return ((Overhangs) known).cells;
        }
        List<Integer> found = new ArrayList<>();
        boolean unsure = false;
        for (int n = 0; n < blocks.faceCells.length; n++) {
            int cell = blocks.cells[blocks.faceCells[n]];
            BlockLooks.Look look = BlockLooks.get(ChunkBlocks.lookKey(cell));
            if (look.opaque || look.noPictures || look.translucent) {
                continue;
            }
            int id = blocks.faceIds[n * ChunkBlocks.PER_CELL + rotation];
            if (id <= 0) {
                continue;
            }
            Boolean out = reachesOut(id, rotation);
            if (out == null) {
                unsure = true;
            } else if (out) {
                found.add(blocks.faceCells[n]);
            }
        }
        int[] cells = found.isEmpty() ? NO_CELLS : new int[found.size()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = found.get(i);
        }
        if (!unsure) {
            // Kept once every sprite could be looked at (one not read yet is looked at again next time).
            blocks.overhangs = new Overhangs(key, cells);
        }
        return cells;
    }

    /**
     * Whether a sprite is drawn a little farther out from its block's center than the point (du, dv) just past its
     * outline: a model reaching out there, not only the edge of its block.
     */
    private boolean reachesOn(int id, double du, double dv) {
        FacePalette.Sprite sprite = id == FacePalette.EMPTY ? null : palette.sprite(id);
        double length = Math.sqrt(du * du + dv * dv);
        if (sprite == null || length < 1e-9) {
            return false;
        }
        double extent = extent(sprite);
        double out = 1 + 2 * OUTLINE_MARGIN / length;
        double su = (du * out / extent + 1) / 2, sv = (dv * out / extent + 1) / 2;
        return (sprite.texel(su, sv, 0) >>> 24) >= 8;
    }

    /**
     * Blocks of the projection plane a sprite covers on each side of its block's center: 1, or 2 for the wide
     * sprites of models reaching far (as sharp, twice the pixels).
     */
    private static double extent(FacePalette.Sprite sprite) {
        return (double) sprite.size / FacePalette.SPRITE_SIZE;
    }

    /** Whether a sprite has drawn pixels outside its block's outline for this view; null if it can't be read yet. */
    private Boolean reachesOut(int id, int rotation) {
        long key = (long) palette.generation << 34 | (long) id << 2 | rotation;
        Boolean known = REACHES_OUT.get(key);
        if (known != null) {
            return known;
        }
        FacePalette.Sprite sprite = palette.sprite(id);
        if (sprite == null) {
            return null;
        }
        boolean out = false;
        int grid = 64;
        double extent = extent(sprite);
        for (int j = 0; j < grid && !out; j++) {
            for (int i = 0; i < grid; i++) {
                double su = (i + 0.5) / grid, sv = (j + 0.5) / grid;
                if ((sprite.texel(su, sv, 0) >>> 24) >= 128
                    && !withinCell((su * 2 - 1) * extent, (sv * 2 - 1) * extent, OUTLINE_MARGIN)) {
                    out = true;
                    break;
                }
            }
        }
        if (REACHES_OUT.size() > 100_000) {
            REACHES_OUT.clear();
        }
        REACHES_OUT.put(key, out);
        return out;
    }

    /**
     * In a cell of air: the parts of models next to it that reach past their own cell (rays through their cells
     * draw the rest). Returns {@link #SPRITE_STOP} if one is met.
     */
    private int reachingOut(ChunkBlocks blocks, int[] reaching, int x, int y, int z, int side, double t,
        int previousLight) {
        int lx = x & 15, lz = z & 15;
        for (int cell : reaching) {
            int oy = blocks.yMin + (cell >> 8), olx = cell & 15, olz = cell >> 4 & 15;
            // Wide sprites (models two blocks tall) reach two blocks.
            if (Math.abs(olx - lx) > 2 || Math.abs(olz - lz) > 2 || Math.abs(oy - y) > 2) {
                continue;
            }
            int ox = (x & ~15) + olx, oz = (z & ~15) + olz;
            double du = rayU - projection.u(ox + 0.5, oz + 0.5);
            double dv = rayV - projection.v(ox + 0.5, oy + 0.5, oz + 0.5);
            if (withinCell(du, dv, 0)) {
                // That part is drawn by rays through the model's own cell.
                continue;
            }
            int id = blocks.pictureId(cell, projection.rotation);
            if (id <= 0) {
                continue;
            }
            if (withinCell(du, dv, OUTLINE_MARGIN) && !reachesOn(id, du, dv)) {
                // Just past the outline: the sprite's pixels on its edge stick out a little, and drew the edges of
                // blocks (the top of a panel below) as dotted lines over the block in front. Taken only where the
                // model goes on farther out (a banner's upper half, a DHD's top): skipping those left a see-through
                // stripe along the block's outline.
                continue;
            }
            int blockCell = blocks.cells[cell];
            BlockLooks.Look look = BlockLooks.get(ChunkBlocks.lookKey(blockCell));
            int light = brighter(light(blockCell), previousLight);
            if (sprite(id, look, ox, oy, oz, side, t, light, false, false) == SPRITE_STOP) {
                return SPRITE_STOP;
            }
        }
        return SPRITE_PASS;
    }

    /** Picture id of the current cell (a side of a solid cube, else a view side), 0 if it has none. */
    private int pictureId(ChunkBlocks blocks, int slot) {
        if (palette == null || cellIndex < 0 || blocks.faceGeneration != palette.generation) {
            return 0;
        }
        return blocks.pictureId(cellIndex, slot);
    }

    /**
     * A block that isn't a solid cube as the game draws it (pipes, crops, beds, fences...), seen along this ray: its
     * sprite's pixel at the ray's place relative to the block's center. Whether the ray meets the block is decided
     * on the full sprite (the reduced copies blur its edges).
     *
     * @return {@link #SPRITE_STOP}, {@link #SPRITE_PASS} (the ray goes on), or {@link #SPRITE_NONE} (draw the block
     *         from its icons instead)
     */
    private int sprite(int id, BlockLooks.Look look, int x, int y, int z, int side, double t, int lightHere,
        boolean sameRun, boolean ownCell) {
        FacePalette.Sprite sprite = id == FacePalette.EMPTY ? null : palette.sprite(id);
        if (sprite == null) {
            if (id == FacePalette.EMPTY) {
                return SPRITE_PASS;
            }
            incomplete |= palette.has(id);
            return SPRITE_NONE;
        }
        double extent = extent(sprite);
        double su = ((rayU - projection.u(x + 0.5, z + 0.5)) / extent + 1) / 2;
        double sv = ((rayV - projection.v(x + 0.5, y + 0.5, z + 0.5)) / extent + 1) / 2;
        int exact = sprite.texel(su, sv, 0);
        int minAlpha = look.translucent ? 8 : 128;
        int exactAlpha = exact >>> 24;
        if (debug != null) {
            debug(
                String.format(Locale.ROOT,
                    "    sprite %d of %d,%d,%d%s at pixel %d,%d of %d: argb=%08x (needs alpha >= %d)",
                    id, x, y, z, ownCell ? "" : " (reaching into this cell)", (int) (su * sprite.size),
                    (int) (sv * sprite.size), sprite.size, exact, minAlpha));
        }
        if (exactAlpha < minAlpha) {
            if (!ownCell) {
                // Only the parts of a model reaching past its cell: a pixel next to the block's own outline would
                // draw its edge (the top of a block below) over the block in front.
                return SPRITE_PASS;
            }
            // The edge of a side lies between pixels of the sprite, at a different place in each block's sprite:
            // where neither of two blocks next to each other has the pixel drawn, a line showed through the wall
            // (glass, connected tile entities). A pixel drawn right next to it stands in.
            double step = 1.0 / sprite.size;
            double[] near = { su - step, sv, su + step, sv, su, sv - step, su, sv + step };
            for (int i = 0; i < near.length && exactAlpha < minAlpha; i += 2) {
                int texel = sprite.texel(near[i], near[i + 1], 0);
                if ((texel >>> 24) >= minAlpha) {
                    exact = texel;
                    exactAlpha = texel >>> 24;
                    su = near[i];
                    sv = near[i + 1];
                    if (debug != null) {
                        debug(
                            String.format(
                                Locale.ROOT,
                                "    empty there: the pixel next to it stands in, argb=%08x",
                                texel));
                    }
                }
            }
            if (exactAlpha < minAlpha) {
                return SPRITE_PASS;
            }
        }
        spriteMet = true;
        if (look.translucent) {
            if (sameRun) {
                // The same glass as the cell just passed, already seen: one layer, not a darker line on the seam.
                if (debug != null) {
                    debug("    same see-through block as the cell just passed: not added again");
                }
                return SPRITE_PASS;
            }
        }
        int pixel = exact;
        if (spriteMip > 0) {
            // The color of the area this pixel of the tile covers, where it has one.
            int reduced = sprite.texel(su, sv, spriteMip);
            if ((reduced >>> 24) >= 64) {
                pixel = reduced;
            }
        }
        hit(1, t);
        float alpha = look.translucent ? Math.max(0.3f, exactAlpha / 255f) : 1f;
        // Sides are already shaded as the game shades them; only the light of the place is added.
        addLit(pixel & 0xFFFFFF, 1f, lightHere, alpha);
        return transmit < 0.02 ? SPRITE_STOP : SPRITE_PASS;
    }

    /**
     * Looks for a face of the block in the cell between entering it at {@code t} and leaving at {@code exit}.
     *
     * @return true when the ray stops here (nothing behind can be seen)
     */
    private boolean sample(BlockLooks.Look look, int key, ChunkBlocks blocks, int cell, int lx, int lz, int side,
        double t, double exit, int x, int y, int z, int previousLight, int previousKey) {
        switch (look.shape) {
            case BlockLooks.SHAPE_BOXES:
                if (look.opaque) {
                    face(look, blocks, lx, lz, side, t, x, y, z, previousLight);
                    return true;
                }
                return boxes(look, key, blocks, lx, lz, side, t, exit, x, y, z, previousLight, previousKey, look.boxes);
            case BlockLooks.SHAPE_LIQUID: {
                boolean water = look.translucent;
                if (insideLiquid == key) {
                    // Under the surface: the water dims what is behind the farther the ray goes through it, so
                    // it is one smooth body getting deeper blue, not a grid of blocks.
                    return water && absorb(look, blocks, lx, lz, exit - t, waterLight);
                }
                boolean full = y + 1 <= blocks.yMax && ChunkBlocks.lookKey(blocks.cell(lx, y + 1, lz)) == key;
                // As high as the game draws it: sources 8/9 of a block, flowing water lower the farther it flows.
                int level = ChunkBlocks.meta(cell);
                float height = full ? 1f : 1f - ((level >= 8 ? 0 : level) + 1) / 9f;
                // A ray can pass over the surface of one block into the next: it is in the liquid only once it
                // went through the surface (or a side under it).
                boxHit = false;
                boolean stop = boxes(
                    look,
                    key,
                    blocks,
                    lx,
                    lz,
                    side,
                    t,
                    exit,
                    x,
                    y,
                    z,
                    previousLight,
                    0,
                    liquidBox(height));
                if (boxHit) {
                    insideLiquid = key;
                    // The whole body of water is lit by the light over its surface: the light kept in each block
                    // of water drops with depth and differs from block to block, which would draw them as a grid.
                    waterLight = previousLight;
                    if (water && !stop) {
                        stop = absorb(look, blocks, lx, lz, exit - boxHitT, waterLight);
                    }
                }
                return stop;
            }
            case BlockLooks.SHAPE_PLANES:
                return planes(look, blocks, cell, lx, lz, t, exit, x, y, z);
            default:
                return false;
        }
    }

    /** Box of a liquid block as high as its surface (reused: one per tracer, used right away). */
    private final float[] liquidBox = { 0, 0, 0, 1, 1, 1 };

    private float[] liquidBox(float height) {
        liquidBox[4] = height;
        return liquidBox;
    }

    /** The nearest box face of the block the ray passes through, if any. */
    private boolean boxes(BlockLooks.Look look, int key, ChunkBlocks blocks, int lx, int lz, int enterSide, double t,
        double exit, int x, int y, int z, int previousLight, int previousKey, float[] boxes) {
        double dx = projection.rayX, dy = projection.rayY, dz = projection.rayZ;
        double bestT = Double.MAX_VALUE;
        int bestSide = -1;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            // Slab test in world coordinates.
            double tx0 = (x + boxes[i] - ox) / dx, tx1 = (x + boxes[i + 3] - ox) / dx;
            double ty0 = (y + boxes[i + 1] - oy) / dy, ty1 = (y + boxes[i + 4] - oy) / dy;
            double tz0 = (z + boxes[i + 2] - oz) / dz, tz1 = (z + boxes[i + 5] - oz) / dz;
            double nearX = Math.min(tx0, tx1), farX = Math.max(tx0, tx1);
            double nearY = Math.min(ty0, ty1), farY = Math.max(ty0, ty1);
            double nearZ = Math.min(tz0, tz1), farZ = Math.max(tz0, tz1);
            double near = Math.max(nearX, Math.max(nearY, nearZ));
            double far = Math.min(farX, Math.min(farY, farZ));
            if (near > far || far < t - 1e-9 || near > exit + 1e-9 || near >= bestT) {
                continue;
            }
            int side;
            if (near == nearY) {
                side = 1;
            } else if (near == nearX) {
                side = dx > 0 ? 4 : 5;
            } else {
                side = dz > 0 ? 2 : 3;
            }
            if (near < t) {
                // The ray starts inside the box (on the cell's edge): it is seen through the side it came in by.
                near = t;
                side = enterSide;
            }
            bestT = near;
            bestSide = side;
        }
        if (bestSide < 0) {
            return false;
        }
        boxHit = true;
        boxHitT = bestT;
        if (look.skipSame && previousKey == key && bestT - t < 1e-6) {
            // Two of the same next to each other (glass, water): no face between them.
            return false;
        }
        return face(look, blocks, lx, lz, bestSide, bestT, x, y, z, previousLight);
    }

    /** Planes of plants, crops, vines, ladders: the nearest one whose texture isn't see-through there. */
    private boolean planes(BlockLooks.Look look, ChunkBlocks blocks, int cell, int lx, int lz, double t, double exit,
        int x, int y, int z) {
        double dx = projection.rayX, dy = projection.rayY, dz = projection.rayZ;
        float[] planes = look.planes;
        double bestT = Double.MAX_VALUE;
        int bestColor = 0;
        int bestSide = 1;
        for (int i = 0; i + 1 < planes.length; i += 2) {
            int kind = (int) planes[i];
            double offset = planes[i + 1];
            double hitT;
            switch (kind) {
                case BlockLooks.PLANE_X:
                    hitT = (x + offset - ox) / dx;
                    break;
                case BlockLooks.PLANE_Z:
                    hitT = (z + offset - oz) / dz;
                    break;
                case BlockLooks.PLANE_Y:
                    hitT = (y + offset - oy) / dy;
                    break;
                case BlockLooks.PLANE_DIAGONAL: // x - z = cell x - cell z
                    if (dx == dz) {
                        continue;
                    }
                    hitT = ((x - z) - (ox - oz)) / (dx - dz);
                    break;
                default: // x + z = cell x + cell z + 1
                    if (dx == -dz) {
                        continue;
                    }
                    hitT = ((x + z + 1) - (ox + oz)) / (dx + dz);
                    break;
            }
            if (hitT < t - 1e-9 || hitT > exit + 1e-9 || hitT >= bestT) {
                continue;
            }
            double px = ox + dx * hitT - x, py = oy + dy * hitT - y, pz = oz + dz * hitT - z;
            if (px < -1e-6 || px > 1 + 1e-6 || py < -1e-6 || py > 1 + 1e-6 || pz < -1e-6 || pz > 1 + 1e-6) {
                continue;
            }
            double texU, texV;
            int textureSide;
            if (kind == BlockLooks.PLANE_Y) {
                texU = px;
                texV = pz;
                textureSide = 1;
            } else {
                texU = kind == BlockLooks.PLANE_X ? pz : px;
                texV = 1 - py;
                textureSide = 2;
            }
            int texel = visible(look.textures[textureSide], texU, texV);
            if (texel == 0) {
                continue;
            }
            bestT = hitT;
            bestColor = tinted(look, blocks, lx, lz, textureSide, texel, texU, texV);
            bestSide = kind == BlockLooks.PLANE_Y ? 1 : 3;
        }
        if (bestT == Double.MAX_VALUE) {
            return false;
        }
        hit(bestSide, bestT);
        addLit(bestColor, bestSide == 1 ? 1f : 0.9f, light(cell), 1f);
        return transmit < 0.02;
    }

    /**
     * Shows the side of the block where the ray hits it at {@code hitT}.
     *
     * @param light light in front of the side, packed like {@link #light}
     * @return true when the ray stops here
     */
    private boolean face(BlockLooks.Look look, ChunkBlocks blocks, int lx, int lz, int side, double hitT, int x, int y,
        int z, int light) {
        double px = ox + projection.rayX * hitT - x;
        double py = oy + projection.rayY * hitT - y;
        double pz = oz + projection.rayZ * hitT - z;
        double texU, texV;
        switch (side) {
            case 0:
            case 1:
                texU = px;
                texV = pz;
                break;
            case 2:
                texU = 1 - px;
                texV = 1 - py;
                break;
            case 3:
                texU = px;
                texV = 1 - py;
                break;
            case 4:
                texU = pz;
                texV = 1 - py;
                break;
            default:
                texU = 1 - pz;
                texV = 1 - py;
                break;
        }
        // Rays meeting a side right at its edge land a hair outside it (rounding); pictures of sides have nothing
        // there, and the side was drawn from its plain icon along every edge: thin light lines between blocks.
        texU = texU < 0 ? 0 : texU >= 1 ? EDGE : texU;
        texV = texV < 0 ? 0 : texV >= 1 ? EDGE : texV;
        if (look.shape == BlockLooks.SHAPE_LIQUID && look.translucent) {
            // The water's surface: its average color, a thin veil over what is below (see absorb).
            int color = tinted(look, blocks, lx, lz, side, look.textures[side].texel(0, 0, 4), texU, texV);
            hit(side, hitT);
            addLit(color, SIDE_SHADE[side], light, WATER_SURFACE);
            return transmit < 0.02;
        }
        if (look.opaque) {
            int id = pictureId(blocks, side);
            FacePalette.Sprite picture = id > 0 ? palette.sprite(id) : null;
            if (fallbacks != null && look.complex && picture == null && cellIndex >= 0) {
                fallbacks.computeIfAbsent(ChunkBlocks.lookKey(blocks.cells[cellIndex]), k -> new int[3])[2]++;
            }
            if (picture == null && id > 0) {
                incomplete |= palette.has(id);
            }
            if (debug != null) {
                debug(
                    String.format(Locale.ROOT,
                        "    solid cube side %s at %.3f,%.3f: picture %d%s", SIDE_NAMES[side], texU, texV, id,
                        picture == null ? " (none: drawn from its icon)"
                            : String.format(Locale.ROOT, " argb=%08x", picture.texel(texU, texV, 0))));
            }
            if (picture != null) {
                // The side as the game draws it here (connected textures, machine fronts): 32 pixels per side.
                int pixel = picture.texel(texU, texV, pictureMip);
                if ((picture.texel(texU, texV, 0) >>> 24) >= 128 && (pixel >>> 24) > 0) {
                    hit(side, hitT);
                    addLit(pixel & 0xFFFFFF, SIDE_SHADE[side], light, 1f);
                    return true;
                }
                // Not drawn there (the side was hidden in the world): its icon.
            }
        }
        BlockLooks.Texture texture = look.textures[side];
        int texel;
        float alpha;
        if (look.translucent) {
            texel = texture.texel(texU, texV, mip);
            alpha = look.alpha > 0 ? look.alpha : Math.max(0.3f, (texel >>> 24) / 255f);
        } else {
            texel = visible(texture, texU, texV);
            if (texel == 0) {
                // A hole in the texture (leaves, glass frames): look further.
                if (debug != null) {
                    debug(
                        String.format(
                            Locale.ROOT,
                            "    icon side %s at %.3f,%.3f: a hole, the ray goes on",
                            SIDE_NAMES[side],
                            texU,
                            texV));
                }
                return false;
            }
            alpha = 1f;
        }
        if (debug != null) {
            debug(
                String.format(
                    Locale.ROOT,
                    "    icon side %s at %.3f,%.3f: argb=%08x",
                    SIDE_NAMES[side],
                    texU,
                    texV,
                    texel));
        }
        hit(side, hitT);
        int color = tinted(look, blocks, lx, lz, side, texel, texU, texV);
        addLit(color, SIDE_SHADE[side], light, alpha);
        return transmit < 0.02;
    }

    /**
     * The texel where a texture with holes is solid, 0 where it has a hole. Solid if it is in the full texture or in
     * the reduced copy: reduced copies blur thin parts (glass frames, rails) into see-through pixels, which made
     * such blocks vanish zoomed out, while leaves stay as full as the reduced copy has them.
     */
    private int visible(BlockLooks.Texture texture, double texU, double texV) {
        int texel = texture.texel(texU, texV, mip);
        if ((texel >>> 24) >= 128) {
            return texel;
        }
        if (mip == 0) {
            return 0;
        }
        int exact = texture.texel(texU, texV, 0);
        return (exact >>> 24) >= 128 ? exact : 0;
    }

    /**
     * Light passing {@code length} blocks through water: part of it is replaced by the water's deep color.
     *
     * @return true when nothing behind can be seen any more
     */
    private boolean absorb(BlockLooks.Look look, ChunkBlocks blocks, int lx, int lz, double length, int light) {
        if (length <= 0) {
            return false;
        }
        int color = tinted(look, blocks, lx, lz, 1, look.textures[1].texel(0, 0, 4), 0, 0);
        addLit(color, WATER_DEPTH_SHADE, light, (float) (1 - Math.exp(-WATER_ABSORPTION * length)));
        return transmit < 0.02;
    }

    /** The texel with the block's tint and overlay applied. */
    private int tinted(BlockLooks.Look look, ChunkBlocks blocks, int lx, int lz, int side, int texel, double texU,
        double texV) {
        int tint = tintColor(look, blocks, lx, lz);
        int color = texel & 0xFFFFFF;
        if (look.tintSide[side] && tint != 0xFFFFFF) {
            color = multiply(color, tint);
        }
        BlockLooks.Texture overlay = look.overlays[side];
        if (overlay != null) {
            int over = overlay.texel(texU, texV, mip);
            int overAlpha = over >>> 24;
            if (overAlpha > 0) {
                int overColor = tint != 0xFFFFFF ? multiply(over & 0xFFFFFF, tint) : over & 0xFFFFFF;
                color = blend(color, overColor, overAlpha / 255f);
            }
        }
        return color;
    }

    private static int tintColor(BlockLooks.Look look, ChunkBlocks blocks, int lx, int lz) {
        int column = (lz << 4) | lx;
        switch (look.tint) {
            case BlockLooks.TINT_GRASS:
                return blocks.grass[column];
            case BlockLooks.TINT_FOLIAGE:
                return blocks.foliage[column];
            case BlockLooks.TINT_WATER:
                return blocks.water[column];
            case BlockLooks.TINT_FIXED:
                return look.tintColor;
            default:
                return 0xFFFFFF;
        }
    }

    /**
     * The column's lowest solid block and its height, {@code key | (y + 1) << 20}, worked out once per chunk. One int,
     * so tile renderers working it out at the same time never see a block with another one's height.
     */
    private static int filler(ChunkBlocks blocks, int lx, int lz) {
        int column = (lz << 4) | lx;
        int filler = blocks.filler[column];
        if (filler != 0) {
            return filler;
        }
        int key = STONE;
        int fillerY = blocks.yMin;
        for (int y = blocks.yMin; y <= blocks.yMax; y++) {
            int cell = blocks.cell(lx, y, lz);
            if (ChunkBlocks.blockId(cell) != 0 && BlockLooks.get(ChunkBlocks.lookKey(cell)).opaque) {
                key = ChunkBlocks.lookKey(cell);
                fillerY = y;
                break;
            }
        }
        filler = key | (fillerY + 1) << 20;
        blocks.filler[column] = filler;
        return filler;
    }

    private static final int DIRT = 3, SANDSTONE = 24, NETHERRACK = 87;

    /**
     * The ground at height y below what was kept of a column (seen at the edge of the map when only the surface was
     * kept): under grass and dirt a few blocks of dirt, under sand some sandstone, then stone; rock stays itself.
     */
    private static int ground(ChunkBlocks blocks, int lx, int lz, int y) {
        int filler = filler(blocks, lx, lz);
        int top = filler & 0xFFFFF;
        int depth = (filler >>> 20) - 1 - y;
        switch (top & 0xFFFF) {
            case 2: // grass
            case 3: // dirt
            case 110: // mycelium
                return depth <= 3 ? DIRT : STONE;
            case 12: // sand
                return depth <= 3 ? top : depth <= 7 && (top >>> 16) == 0 ? SANDSTONE : STONE;
            case 13: // gravel
            case 82: // clay
                return depth <= 3 ? top : STONE;
            case 87: // netherrack
            case 88: // soul sand
            case 153: // nether quartz ore
                return depth <= 3 ? top : NETHERRACK;
            case 1: // stone
            case 4: // cobblestone
            case 7: // bedrock
            case 24: // sandstone
            case 49: // obsidian
            case 121: // end stone
            case 159: // stained clay
            case 172: // hardened clay
                return top;
            default:
                // Something built, or a modded block: natural ground below it.
                return STONE;
        }
    }

    private void hit(int side, double hitT) {
        lastHitT = hitT;
        if (hitSide < 0) {
            hitSide = side;
            hitY = oy + projection.rayY * hitT;
        }
    }

    /**
     * Adds a surface lit by the light of its place: by day the brighter of sky and block light; at night the sky
     * gives little (moonlight) and block light shows warm.
     *
     * @param light sky light << 4 | block light
     */
    private void addLit(int rgb, float shade, int light, float alpha) {
        int sky = light >> 4, block = light & 15;
        int nightSky = Math.max(0, sky - NIGHT_SKY_DROP);
        int night = Math.max(nightSky, block);
        float warmth = block > nightSky ? Math.min(1f, (block - nightSky) / 6f) : 0f;
        add(rgb, shade * LIGHT[Math.max(sky, block)], shade * NIGHT_LIGHT[night], warmth, alpha);
    }

    /** Adds a surface seen through what is in front of it, by day and by night. */
    private void debug(String line) {
        if (debug.length() < 400_000) {
            debug.append(line)
                .append('\n');
        }
    }

    private static String steps(double t) {
        return String.format(Locale.ROOT, "t=%.3f", t);
    }

    private static int lightOf(int cell) {
        return ChunkBlocks.skyLight(cell) << 4 | ChunkBlocks.blockLight(cell);
    }

    private void add(int rgb, float dayShade, float nightShade, float warmth, float alpha) {
        if (debug != null) {
            debug(
                String.format(Locale.ROOT,
                    "    ADDED rgb=%06x alpha=%.2f shade=%.2f (light left before: %.2f, after: %.2f)", rgb & 0xFFFFFF,
                    alpha, dayShade, transmit, transmit * (1 - alpha)));
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        double weight = transmit * alpha;
        accR += r * weight * dayShade;
        accG += g * weight * dayShade;
        accB += b * weight * dayShade;
        double night = weight * nightShade;
        nightR += r * night * (MOON[0] + (WARM[0] - MOON[0]) * warmth);
        nightG += g * night * (MOON[1] + (WARM[1] - MOON[1]) * warmth);
        nightB += b * night * (MOON[2] + (WARM[2] - MOON[2]) * warmth);
        transmit *= 1 - alpha;
        // A surface drawn whole, or so much see-through stuff that little gets past (deep water): one layer of
        // tinted glass (GregTech's, a good half opaque by its texture) still shows what is behind it.
        if (Double.isNaN(solidY) && (alpha >= 0.99f || transmit < 0.1)) {
            solidY = oy + projection.rayY * lastHitT;
        }
    }

    /** The brighter sky light and the brighter block light of two packed lights. */
    private static int brighter(int a, int b) {
        return Math.max(a & 0xF0, b & 0xF0) | Math.max(a & 15, b & 15);
    }

    /** Light of a cell: sky light << 4 | block light. */
    private static int light(int cell) {
        return ChunkBlocks.skyLight(cell) << 4 | ChunkBlocks.blockLight(cell);
    }

    private static int multiply(int color, int tint) {
        int r = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (color & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int clamp(double value) {
        return value <= 0 ? 0 : value >= 255 ? 255 : (int) value;
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
