package WayFarMap.client.map.iso;

import java.util.Arrays;

import WayFarMap.client.map.MapRegion;

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
    private final SurfaceFallback fallback;
    private IsoProjection projection;
    /** Texture detail: 0 = 16x16 texels per block ... 4 = one average color. */
    private int mip;
    /** Sprite detail: 0 = 16 pixels per block ... 5 = one color. */
    private int spriteMip;

    /** Chunks looked at lately (direct mapped by position): blocks, flat map region, or nothing. */
    private static final int CACHE = 1 << 10;
    private final long[] cacheKeys = new long[CACHE];
    private final Object[] cacheData = new Object[CACHE];
    private final int[] cacheTops = new int[CACHE];
    private static final Object NOTHING = new Object();

    // Result of the last ray.
    /** Height of the first surface hit and its side (-1 if none). */
    double hitY;
    int hitSide;
    /** The color seen at night (the day color is returned by trace), ARGB. */
    int nightColor;
    /** Lowest "toward the viewer" distance any ray got to, for knowing which chunks a tile depends on. */
    double minToward;

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

    IsoTracer(BlockStore store, SurfaceFallback fallback, FacePalette palette) {
        this.store = store;
        this.fallback = fallback;
        this.palette = palette;
    }

    void reset(IsoProjection projection, int level) {
        this.projection = projection;
        double pixelsPerBlock = IsoProjection.pixelsPerBlock(level);
        mip = pixelsPerBlock >= 16 ? 0 : pixelsPerBlock >= 8 ? 1 : pixelsPerBlock >= 4 ? 2 : pixelsPerBlock >= 2 ? 3 : 4;
        // No further than 8x8: smaller copies mix a block's top with its darker sides, darker than blocks drawn
        // from their icons (the tile's own four rays per pixel smooth the far levels instead).
        spriteMip = Math.min(2, level);
        Arrays.fill(cacheKeys, Long.MIN_VALUE);
        Arrays.fill(cacheData, null);
        currentChunkX = Integer.MIN_VALUE;
        minToward = Double.MAX_VALUE;
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
            }
        }
        if (data == NOTHING) {
            MapRegion region = fallback.region(chunkX, chunkZ);
            if (region != null) {
                top = fallback.top(chunkX, chunkZ);
                data = top >= 0 ? region : NOTHING;
            }
        }
        cacheKeys[slot] = key;
        cacheData[slot] = data;
        cacheTops[slot] = top;
        currentData = data;
        currentTop = top;
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
                        // The ground below what was stored: the column's lowest solid block.
                        BlockLooks.Look look = BlockLooks.get(filler(blocks, lx, lz));
                        cellIndex = -1;
                        face(look, blocks, lx, lz, side, t, x, y, z, previousLight);
                        break;
                    }
                    cellIndex = ((y - blocks.yMin) << 8) | (lz << 4) | lx;
                    int cell = blocks.cells[cellIndex];
                    int key = ChunkBlocks.lookKey(cell);
                    if (key != insideLiquid) {
                        insideLiquid = 0;
                    }
                    if (ChunkBlocks.blockId(cell) != 0) {
                        BlockLooks.Look look = BlockLooks.get(key);
                        // Solid cubes show the game's pictures of their sides (in face); other blocks its sprites.
                        int spriteId = look.opaque ? 0 : pictureId(blocks, projection.rotation);
                        // Some blocks say light passes them while the world keeps none in their cell (GregTech
                        // machines): the brighter of the cell and the light in front of it.
                        int lightHere = look.lightPasses ? brighter(light(cell), previousLight) : previousLight;
                        int drawn = spriteId == 0 ? SPRITE_NONE
                            : sprite(spriteId, look, x, y, z, side, t, lightHere);
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
                            previousLight = light(cell);
                        }
                    } else {
                        previousLight = light(cell);
                    }
                    previousKey = key;
                } else if (data instanceof MapRegion) {
                    MapRegion region = (MapRegion) data;
                    int lx = x & (MapRegion.SIZE - 1), lz = z & (MapRegion.SIZE - 1);
                    int pixel = region.getPixel(lx, lz);
                    if ((pixel >>> 24) != 0 && y < region.getExtra(lx, lz)) {
                        // A pillar of the flat map's color; its top already has the map's relief shading.
                        hit(side, t);
                        float shade = side == 1 ? 1f : SIDE_SHADE[side];
                        add(pixel & 0xFFFFFF, shade, shade * NIGHT_LIGHT[15 - NIGHT_SKY_DROP], 0f, 1f);
                        break;
                    }
                    previousLight = OPEN;
                    previousKey = 0;
                    insideLiquid = 0;
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
    private int sprite(int id, BlockLooks.Look look, int x, int y, int z, int side, double t, int lightHere) {
        FacePalette.Sprite sprite = id == FacePalette.EMPTY ? null : palette.sprite(id);
        if (sprite == null) {
            return id != FacePalette.EMPTY ? SPRITE_NONE : SPRITE_PASS;
        }
        double su = (rayU - projection.u(x + 0.5, z + 0.5) + 1) / 2;
        double sv = (rayV - projection.v(x + 0.5, y + 0.5, z + 0.5) + 1) / 2;
        int exact = sprite.texel(su, sv, 0);
        int minAlpha = look.translucent ? 8 : 128;
        int exactAlpha = exact >>> 24;
        if (exactAlpha < minAlpha) {
            return SPRITE_PASS;
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
                    new float[] { 0, 0, 0, 1, height, 1 });
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
            int texel = look.textures[textureSide].texel(texU, texV, mip);
            if ((texel >>> 24) < 128) {
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
            if (picture != null) {
                // The side as the game draws it here (connected textures, machine fronts): 32 pixels per side.
                int pixel = picture.texel(texU, texV, Math.min(5, mip + 1));
                if ((picture.texel(texU, texV, 0) >>> 24) >= 128 && (pixel >>> 24) > 0) {
                    hit(side, hitT);
                    addLit(pixel & 0xFFFFFF, SIDE_SHADE[side], light, 1f);
                    return true;
                }
                // Not drawn there (the side was hidden in the world): its icon.
            }
        }
        BlockLooks.Texture texture = look.textures[side];
        int texel = texture.texel(texU, texV, mip);
        int texelAlpha = texel >>> 24;
        float alpha;
        if (look.translucent) {
            alpha = look.alpha > 0 ? look.alpha : Math.max(0.3f, texelAlpha / 255f);
        } else {
            if (texelAlpha < 128) {
                // A hole in the texture (leaves, glass frames): look further.
                return false;
            }
            alpha = 1f;
        }
        hit(side, hitT);
        int color = tinted(look, blocks, lx, lz, side, texel, texU, texV);
        addLit(color, SIDE_SHADE[side], light, alpha);
        return transmit < 0.02;
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

    /** The column's lowest solid block, worked out once per chunk. */
    private static int filler(ChunkBlocks blocks, int lx, int lz) {
        int column = (lz << 4) | lx;
        int filler = blocks.filler[column];
        if (filler != 0) {
            return filler;
        }
        filler = STONE;
        for (int y = blocks.yMin; y <= blocks.yMax; y++) {
            int cell = blocks.cell(lx, y, lz);
            if (ChunkBlocks.blockId(cell) != 0 && BlockLooks.get(ChunkBlocks.lookKey(cell)).opaque) {
                filler = ChunkBlocks.lookKey(cell);
                break;
            }
        }
        blocks.filler[column] = filler;
        return filler;
    }

    private void hit(int side, double hitT) {
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
    private void add(int rgb, float dayShade, float nightShade, float warmth, float alpha) {
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

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
