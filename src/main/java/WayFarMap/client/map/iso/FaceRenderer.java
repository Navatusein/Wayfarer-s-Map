package WayFarMap.client.map.iso;

import java.lang.reflect.Method;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.ChunkScanner;

/**
 * Takes sprites of blocks as the game draws them in place, for blocks the 3D map can't draw by itself: connected
 * textures (Chisel), tile entities with their own renderer (chests, signs, heads, modded machines), GregTech pipes and
 * cables, crops on sticks, beds, rails, redstone, fences, modded block renderers, machine fronts and other sides that
 * depend on the world. Each block is drawn by the game's own renderers into an off-screen buffer, from exactly the
 * direction the 3D map looks from (one sprite per view side), with the world around it (so connected textures
 * connect and pipes join) and without lighting; the tracer shows the sprite's pixel where its ray meets the block.
 * Render thread only.
 */
final class FaceRenderer {

    /** Room for one picture (sprites fill it, pictures of sides a corner), and the buffer: 64 per read back. */
    private static final int SLOT = FacePalette.SPRITE_SIZE, SIZE = 1024, PER_ROW = SIZE / SLOT,
        SLOTS = PER_ROW * PER_ROW;
    /** How far outside the block the clip planes are (less than the gap to a chest's other half, 1/16). */
    private static final double CLIP_MARGIN = 1 / 32.0;
    /**
     * The same for a block's own tile entity model: some are larger than the block (the Blood Magic altar, about
     * 1.3 blocks wide), and cut to its column they lost every upright face.
     */
    private static final double OWN_MODEL_MARGIN = 0.25;
    /**
     * How far inside the block a side hidden by its neighbour is cut off: only what lies on that side is lost (a
     * thousandth of a block of the rest, far less than a pixel).
     */
    private static final double COVERED_INSET = 1 / 1024.0;
    /** Brightness the game gives each side; taken out of pictures of sides, the tracer shades sides itself. */
    private static final float[] SIDE_SHADE = { 0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f };
    /**
     * Per side, the camera looking straight at it: from the block (relative to its center) to eye coordinates, the
     * side being the near plane and its picture laid out like the tracer reads a side's texture (row-major 3x4: x,
     * y, z rows with a translation).
     */
    private static final float[][] SIDE_VIEWS = { { 1, 0, 0, 0, 0, 0, -1, 0, 0, -1, 0, -0.5f }, // down
        { 1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, -0.5f }, // up
        { -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, -0.5f }, // north
        { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -0.5f }, // south
        { 0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, -0.5f }, // west
        { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, -0.5f } }; // east
    private static final int[][] OFFSETS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static int[] readPixels;
    private static FloatBuffer matrixBuffer;
    private static DoubleBuffer planeBuffer;
    private static boolean broken;
    /** Failures in a row; pictures are given up only after several (one mod's renderer failing once is enough). */
    private static int failures;
    /**
     * Pictures of blocks with a tile entity, by place: they depend on what is in it, so they aren't shared between
     * places, but the chunks near the player are copied every few seconds and are not taken again each time.
     */
    private static final Map<Long, Cached> BY_PLACE = lru(400_000);
    /**
     * Blocks of chunks whose pictures are being taken, by chunk: a chunk with thousands of machines takes many ticks,
     * and finding its blocks again each tick (their surroundings above all) took half of each tick's time.
     */
    private static final Map<Long, Session> SESSIONS = lru(8);

    /** A map that lets go of the entries used longest ago past the size (render thread only). */
    private static <V> Map<Long, V> lru(int size) {
        return new LinkedHashMap<Long, V>(256, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > size;
            }
        };
    }

    /** The blocks of a chunk that need pictures, found once, and how far their pictures were taken. */
    private static final class Session {

        final long signature;
        final int generation;
        final boolean[] around;
        final List<Pending> found, toDraw;
        final Map<Long, List<Pending>> waiting;
        int from;

        Session(long signature, int generation, boolean[] around, List<Pending> found, List<Pending> toDraw,
            Map<Long, List<Pending>> waiting) {
            this.signature = signature;
            this.generation = generation;
            this.around = around;
            this.found = found;
            this.toDraw = toDraw;
            this.waiting = waiting;
        }
    }

    private static final class Cached {

        final long surroundings;
        final int[] ids;
        final long time;

        Cached(long surroundings, int[] ids, long time) {
            this.surroundings = surroundings;
            this.ids = ids;
            this.time = time;
        }
    }

    /** Sprites of blocks without a tile entity, by block and everything around it. */
    private static final Map<Long, int[]> BY_SURROUNDINGS = new HashMap<>();
    private static int cacheGeneration;
    /** Whether a block class draws sides depending on the world (overrides the world-aware getIcon). */
    private static final Map<Class<?>, Boolean> WORLD_ICONS = new HashMap<>();

    private FaceRenderer() {}

    /** A block to take sprites of. */
    private static final class Pending {

        final int cellIndex, x, y, z;
        final Block block;
        final TileEntity tileEntity;
        final long surroundings;
        /** A solid cube: pictures of its six sides; otherwise sprites from the four view sides. */
        final boolean cube;
        final boolean ownRenderer;
        final int[] ids = new int[ChunkBlocks.PER_CELL];
        /** A chunk it touches wasn't loaded: its pictures may be wrong at the chunk's edge. */
        boolean unsure;
        /** For the log: its open sides, and its pictures kept before they were too old. */
        int exposed;
        int[] oldIds;
        /** For the log: its look key, why it needs pictures, and how they came out. */
        int lookKey;
        String why;
        BlockDiag.Shot shot;
        /** Sides (bits by {@link #OFFSETS}) hidden by a solid block next to it: left out of its sprites. */
        int covered;

        Pending(int cellIndex, int x, int y, int z, Block block, TileEntity tileEntity, long surroundings, boolean cube,
            boolean ownRenderer) {
            this.cellIndex = cellIndex;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.tileEntity = tileEntity;
            this.surroundings = surroundings;
            this.cube = cube;
            this.ownRenderer = ownRenderer;
        }

        int views() {
            return cube ? 6 : ChunkBlocks.VIEWS;
        }
    }

    /** Whether sprites can be taken (off-screen buffers are available and nothing went wrong). */
    static boolean available() {
        return !broken && OpenGlHelper.isFramebufferEnabled();
    }

    /**
     * For {@link IsoLog}, about the last {@link #addFaces} (render thread): blocks needing pictures, blocks whose
     * pictures had to be taken, taken this time, not taken (no room), and whether the palette was full.
     */
    static int lastFound, lastToDraw, lastDrawn, lastMissing;
    static boolean lastPaletteFull;
    /**
     * More about the last {@link #addFaces}, for {@link IsoLog}: why blocks needed pictures (complex look, own
     * renderer, glass touching glass, sides depending on the world), what the caches gave (tile entity pictures by
     * place: hit, too old, surroundings changed; by surroundings: hit, same as another block of this copy), batches,
     * and where the time went (finding the blocks, drawing, reading back from the graphics card, storing sprites).
     */
    static int whyComplex, whyOwnRenderer, whyGlass, whySides, placeHit, placeExpired, placeChanged, surroundingsHit,
        surroundingsShared, batches, slotsUsed, tileEntities;
    static long findNanos, drawNanos, readNanos, storeNanos, setupNanos;
    /**
     * Checks of what could be skipped, for {@link IsoLog}. Where the finding time goes: sides covered, tile entity
     * lookups, surroundings; blocks looked at. Pictures taken again (kept by place) that came out the same as before
     * (or not). Tile entities drawn whose pictures are the same as another's with the same block and
     * surroundings (or not), and the same with its data too. Blocks only open at the bottom (never seen from the
     * views) and blocks whose pictures all came out empty. Time turning pixels into sprites apart from looking them
     * up.
     */
    /**
     * For the log: pictures taken another way to compare (0 the usual way; 1 not cut to the block's column, 2 with
     * back faces culled for the tile entity too, 3 with the game's item lighting for the tile entity). Not stored.
     */
    private static int variant;
    private static final String[] VARIANTS = { "usual", "noClip", "cullBackFaces", "itemLighting" };
    private static boolean cullLogged;
    static int sessionReused;
    /** For the log: blocks of the chunk's list of pictures to take done so far, and in all. */
    static int progressDone, progressTotal;
    static int blocksLooked, expiredSame, expiredDiffer, sameAsTwin, differFromTwin, sameAsTwinWithData,
        differFromTwinWithData, onlyBottomOpen, allEmpty, allEmptyOnlyBottom;
    static long exposedNanos, tileEntityNanos, surroundingsNanos, unshadeNanos, idNanos;
    /**
     * Pictures of tile entities by block and surroundings (and with their data), to tell whether pictures could be
     * shared between places; for the log only.
     */
    private static final Map<Long, int[]> TWINS = new HashMap<>(), TWINS_WITH_DATA = new HashMap<>();

    private static void resetStats() {
        lastFound = lastToDraw = lastDrawn = lastMissing = 0;
        whyComplex = whyOwnRenderer = whyGlass = whySides = placeHit = placeExpired = placeChanged = 0;
        surroundingsHit = surroundingsShared = batches = slotsUsed = tileEntities = 0;
        findNanos = drawNanos = readNanos = storeNanos = setupNanos = 0;
        sessionReused = 0;
        progressDone = progressTotal = 0;
        blocksLooked = expiredSame = expiredDiffer = sameAsTwin = differFromTwin = sameAsTwinWithData = 0;
        differFromTwinWithData = onlyBottomOpen = allEmpty = allEmptyOnlyBottom = 0;
        exposedNanos = tileEntityNanos = surroundingsNanos = unshadeNanos = idNanos = 0;
    }

    /** Resource packs changed: sprites are taken again. */
    static void clear() {
        BY_SURROUNDINGS.clear();
        BY_PLACE.clear();
        SESSIONS.clear();
        TWINS.clear();
        TWINS_WITH_DATA.clear();
    }

    /**
     * Finds the chunk's blocks that need sprites, takes them and stores their ids in {@code blocks}. Taking pictures
     * stops once the deadline is past (after at least one batch): the blocks left have none this time.
     *
     * @param deadline {@link System#nanoTime()} to stop at
     * @return false if some pictures weren't taken in time
     */
    static boolean addFaces(World world, Chunk chunk, ChunkBlocks blocks, FacePalette palette, long deadline) {
        resetStats();
        lastPaletteFull = palette.full();
        long findStart = System.nanoTime();
        if (!available()) {
            // The ones of the copy before are kept.
            blocks.picturesMissing = true;
            return true;
        }
        if (cacheGeneration != palette.generation) {
            BY_SURROUNDINGS.clear();
            BY_PLACE.clear();
            SESSIONS.clear();
            TWINS.clear();
            TWINS_WITH_DATA.clear();
            cacheGeneration = palette.generation;
        }
        // Which chunks around are there: without them, blocks at the edge are drawn as if the world ended there.
        boolean[] around = new boolean[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                around[(dz + 1) * 3 + dx + 1] = ChunkScanner
                    .isChunkReady(world, chunk.xPosition + dx, chunk.zPosition + dz);
            }
        }
        long chunkKey = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        long signature = blocks.signature();
        Session session = SESSIONS.get(chunkKey);
        if (session != null && session.signature == signature
            && session.generation == palette.generation
            && Arrays.equals(session.around, around)) {
            // Same blocks as last tick: go on where it stopped.
            sessionReused = 1;
        } else {
            session = find(world, chunk, blocks, around, signature, palette.generation);
            SESSIONS.put(chunkKey, session);
        }
        List<Pending> found = session.found, toDraw = session.toDraw;
        Map<Long, List<Pending>> waiting = session.waiting;
        lastFound = found.size();
        lastToDraw = toDraw.size() - session.from;
        findNanos = System.nanoTime() - findStart;
        if (found.isEmpty()) {
            SESSIONS.remove(chunkKey);
            blocks.setFaces(palette.generation, new int[0], new int[0]);
            return true;
        }
        boolean complete = true;
        int from = session.from;
        while (from < toDraw.size() && !broken) {
            if (from > session.from && System.nanoTime() > deadline) {
                // The rest another time; the pictures taken so far are kept in the caches.
                complete = false;
                break;
            }
            // As many blocks as their pictures fit in the buffer.
            int to = from, slots = 0;
            while (to < toDraw.size() && slots + toDraw.get(to)
                .views() <= SLOTS) {
                slots += toDraw.get(to++)
                    .views();
            }
            List<Pending> batch = toDraw.subList(from, to);
            from = to;
            draw(world, batch, palette);
            if (IsoLog.on()) {
                tryVariants(world, batch, palette);
            }
            lastDrawn += batch.size();
            batches++;
            slotsUsed += slots;
            if (IsoLog.on()) {
                checkSkippable(batch);
            }
            for (Pending pending : batch) {
                if (missing(pending)) {
                    lastMissing++;
                    // Not taken (no room for new pictures now): taken again next time, not remembered as none.
                    continue;
                }
                if (pending.tileEntity != null) {
                    // Kept until its surroundings change (or the player asks for new pictures): taking them again
                    // after a while gave the same pictures five times out of six, and kept big bases from ever
                    // being finished.
                    BY_PLACE.put(
                        place(pending.x, pending.y, pending.z),
                        new Cached(pending.surroundings, pending.ids.clone(), System.currentTimeMillis()));
                } else {
                    if (BY_SURROUNDINGS.size() > 200_000) {
                        // A long game: start over rather than grow without end.
                        BY_SURROUNDINGS.clear();
                    }
                    BY_SURROUNDINGS.put(pending.surroundings, pending.ids.clone());
                    List<Pending> same = waiting.get(pending.surroundings);
                    if (same != null) {
                        for (Pending other : same) {
                            System.arraycopy(pending.ids, 0, other.ids, 0, pending.ids.length);
                        }
                    }
                }
            }
        }
        session.from = from;
        progressDone = from;
        progressTotal = toDraw.size();
        if (complete || broken) {
            SESSIONS.remove(chunkKey);
        }
        if (broken) {
            blocks.picturesMissing = true;
            return true;
        }
        int[] faceCells = new int[found.size()];
        int[] ids = new int[found.size() * ChunkBlocks.PER_CELL];
        for (int n = 0; n < found.size(); n++) {
            Pending pending = found.get(n);
            faceCells[n] = pending.cellIndex;
            System.arraycopy(pending.ids, 0, ids, n * ChunkBlocks.PER_CELL, ChunkBlocks.PER_CELL);
        }
        blocks.setFaces(palette.generation, faceCells, ids);
        int unsure = 0;
        for (Pending pending : found) {
            if (pending.unsure) {
                unsure++;
            }
        }
        if (unsure > 0) {
            int[] unsureCells = new int[unsure];
            unsure = 0;
            for (Pending pending : found) {
                if (pending.unsure) {
                    unsureCells[unsure++] = pending.cellIndex;
                }
            }
            blocks.unsureCells = unsureCells;
        }
        return complete;
    }

    /** Finds the chunk's blocks that need pictures, and which of them have none in the caches yet. */
    private static Session find(World world, Chunk chunk, ChunkBlocks blocks, boolean[] around, long signature,
        int generation) {
        int baseX = chunk.xPosition * 16, baseZ = chunk.zPosition * 16;
        List<Pending> found = new ArrayList<>();
        List<Pending> toDraw = new ArrayList<>();
        Map<Long, List<Pending>> waiting = new HashMap<>();
        int[] cells = blocks.cells;
        // For the log: per kind that may need pictures, blocks hidden, only open at the bottom, drawn from icons,
        // given pictures.
        Map<Integer, int[]> decisions = IsoLog.on() ? new HashMap<>() : null;
        // The last block found to need no pictures anywhere: most of a chunk is runs of the same few blocks.
        int plainKey = -1;
        // The part of an edge chunk kept below its surface gets no pictures: it starts above it.
        for (int i = Math.max(0, blocks.picturesFrom - blocks.yMin) << 8; i < cells.length; i++) {
            int cell = cells[i];
            if (ChunkBlocks.blockId(cell) == 0) {
                continue;
            }
            int key = ChunkBlocks.lookKey(cell);
            if (key == plainKey) {
                continue;
            }
            BlockLooks.Look look = BlockLooks.get(key);
            BlockDiag.kind(key, look);
            if (look.shape == BlockLooks.SHAPE_NONE && !look.complex || look.shape == BlockLooks.SHAPE_LIQUID
                || look.noPictures) {
                plainKey = key;
                continue;
            }
            Block block = Block.getBlockById(ChunkBlocks.blockId(cell));
            int meta = ChunkBlocks.meta(cell);
            // Glass and other see-through cubes: connected textures may come from outside the block (a mod hooking
            // the game's block renderer, resource packs), so they are drawn by the game wherever one touches another.
            // Not leaves (no faces between see-through blocks of the same kind is what makes it glass).
            boolean glassLike = look.renderType == 0 && look.fullCube && !look.opaque && look.skipSame;
            boolean maybe;
            try {
                maybe = look.complex || glassLike
                    || block.hasTileEntity(meta)
                    || (look.renderType == 0 && overridesWorldIcon(block.getClass()));
            } catch (RuntimeException e) {
                maybe = false;
            }
            if (!maybe) {
                // Most blocks: drawn from their icons.
                plainKey = key;
                continue;
            }
            int lx = i & 15, lz = (i >> 4) & 15, y = blocks.yMin + (i >> 8);
            int x = baseX + lx, z = baseZ + lz;
            blocksLooked++;
            long t0 = System.nanoTime();
            int exposed = exposedSides(world, blocks, lx, y, lz, x, z);
            long t1 = System.nanoTime();
            exposedNanos += t1 - t0;
            if (exposed == 0) {
                decide(decisions, key, 0);
                continue;
            }
            if (exposed == 1) {
                // Only the bottom is open: none of the views sees it (they all look from above), no pictures needed.
                onlyBottomOpen++;
                decide(decisions, key, 1);
                continue;
            }
            TileEntity tileEntity = null;
            boolean ownRenderer = false;
            try {
                if (block.hasTileEntity(meta)) {
                    tileEntity = world.getTileEntity(x, y, z);
                    ownRenderer = tileEntity != null
                        && TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity);
                }
            } catch (RuntimeException e) {
                tileEntity = null;
            }
            tileEntityNanos += System.nanoTime() - t1;
            boolean needed = look.complex || ownRenderer
                || (glassLike && touchesSame(world, blocks, cell, lx, y, lz, x, z))
                || (look.renderType == 0 && sidesDependOnWorld(world, block, meta, x, y, z, exposed));
            if (!needed) {
                decide(decisions, key, 2);
                continue;
            }
            decide(decisions, key, 3);
            String why;
            if (look.complex) {
                whyComplex++;
                why = "complex(renderType " + look.renderType + ")";
            } else if (ownRenderer) {
                whyOwnRenderer++;
                why = "tileEntityRenderer";
            } else if (glassLike) {
                whyGlass++;
                why = "glassTouchingGlass";
            } else {
                whySides++;
                why = "sidesDependOnWorld";
            }
            if (ownRenderer && look.complex) {
                why += "+tileEntityRenderer";
            }
            // Blocks with a tile entity (pipes, machines, chests) look by what is in it: each is drawn.
            // Blocks that fill their cell (glass too) connect their textures with all 26 blocks around them.
            long t2 = System.nanoTime();
            long surroundings = surroundings(world, block, x, y, z, look.opaque || look.fullCube);
            surroundingsNanos += System.nanoTime() - t2;
            Pending pending = new Pending(i, x, y, z, block, tileEntity, surroundings, look.opaque, ownRenderer);
            pending.exposed = exposed;
            pending.lookKey = key;
            pending.why = why;
            pending.unsure = !aroundLoaded(around, lx, lz);
            found.add(pending);
            if (tileEntity != null) {
                tileEntities++;
                Cached cached = BY_PLACE.get(place(x, y, z));
                if (cached != null && cached.surroundings == surroundings) {
                    System.arraycopy(cached.ids, 0, pending.ids, 0, pending.ids.length);
                    placeHit++;
                    continue;
                }
                if (cached != null) {
                    if (cached.surroundings != surroundings) {
                        placeChanged++;
                    } else {
                        placeExpired++;
                        pending.oldIds = cached.ids;
                    }
                }
            } else {
                int[] known = BY_SURROUNDINGS.get(surroundings);
                if (known != null) {
                    System.arraycopy(known, 0, pending.ids, 0, pending.ids.length);
                    surroundingsHit++;
                    continue;
                }
                List<Pending> same = waiting.get(surroundings);
                if (same != null) {
                    // Drawn once for all the blocks with the same surroundings.
                    same.add(pending);
                    surroundingsShared++;
                    continue;
                }
                waiting.put(surroundings, new ArrayList<>());
            }
            toDraw.add(pending);
        }
        if (decisions != null && !decisions.isEmpty()) {
            StringBuilder b = new StringBuilder("FIND ").append(chunk.xPosition)
                .append(',')
                .append(chunk.zPosition)
                .append(" blocks that may need pictures, per kind [hidden/onlyBottomOpen/fromIcons/pictures]:");
            for (Map.Entry<Integer, int[]> kind : decisions.entrySet()) {
                int[] d = kind.getValue();
                b.append(' ')
                    .append(BlockDiag.name(kind.getKey()))
                    .append('=')
                    .append(d[0])
                    .append('/')
                    .append(d[1])
                    .append('/')
                    .append(d[2])
                    .append('/')
                    .append(d[3]);
            }
            IsoLog.log(b.toString());
        }
        return new Session(signature, generation, around, found, toDraw, waiting);
    }

    private static void decide(Map<Integer, int[]> decisions, int key, int what) {
        if (decisions != null) {
            decisions.computeIfAbsent(key, k -> new int[4])[what]++;
        }
    }

    /** Whether the chunks the block at (lx, lz) of the chunk touches (itself included) are all loaded. */
    private static boolean aroundLoaded(boolean[] around, int lx, int lz) {
        int fromX = lx == 0 ? 0 : 1, toX = lx == 15 ? 2 : 1;
        int fromZ = lz == 0 ? 0 : 1, toZ = lz == 15 ? 2 : 1;
        for (int z = fromZ; z <= toZ; z++) {
            for (int x = fromX; x <= toX; x++) {
                if (!around[z * 3 + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long place(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Bits of the sides (1 << side) that are not covered by a solid block next to them. */
    private static int exposedSides(World world, ChunkBlocks blocks, int lx, int y, int lz, int x, int z) {
        int exposed = 0;
        for (int side = 0; side < 6; side++) {
            int nx = lx + OFFSETS[side][0], ny = y + OFFSETS[side][1], nz = lz + OFFSETS[side][2];
            boolean covered;
            if (ny > blocks.yMax || ny > 255) {
                covered = false;
            } else if (ny < 0) {
                covered = true;
            } else if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < blocks.yMin) {
                covered = world.getBlock(x + OFFSETS[side][0], ny, z + OFFSETS[side][2])
                    .isOpaqueCube();
            } else {
                int neighbour = blocks.cell(nx, ny, nz);
                covered = ChunkBlocks.blockId(neighbour) != 0 && BlockLooks.get(ChunkBlocks.lookKey(neighbour)).opaque;
            }
            if (!covered) {
                exposed |= 1 << side;
            }
        }
        return exposed;
    }

    /** Whether one of the six blocks next to it is the same block (id and metadata). */
    private static boolean touchesSame(World world, ChunkBlocks blocks, int cell, int lx, int y, int lz, int x, int z) {
        int key = ChunkBlocks.lookKey(cell);
        for (int side = 0; side < 6; side++) {
            int nx = lx + OFFSETS[side][0], ny = y + OFFSETS[side][1], nz = lz + OFFSETS[side][2];
            if (ny < 0 || ny > 255) {
                continue;
            }
            int other;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < blocks.yMin || ny > blocks.yMax) {
                other = blockAt(world, x + OFFSETS[side][0], ny, z + OFFSETS[side][2]);
            } else {
                other = ChunkBlocks.lookKey(blocks.cell(nx, ny, nz));
            }
            if (other == key) {
                return true;
            }
        }
        return false;
    }

    /** Whether any open side of the block shows another icon in the world than its plain icon for the metadata. */
    private static boolean sidesDependOnWorld(IBlockAccess world, Block block, int meta, int x, int y, int z,
        int exposed) {
        try {
            for (int side = 0; side < 6; side++) {
                if ((exposed & 1 << side) != 0 && block.getIcon(world, x, y, z, side) != block.getIcon(side, meta)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** For the log: whether the block's class has its own world-aware getIcon. */
    static boolean overridesWorldIconOf(Block block) {
        return block != null && overridesWorldIcon(block.getClass());
    }

    /** Whether the class (or a parent below Block) has its own world-aware getIcon; found by signature. */
    private static boolean overridesWorldIcon(Class<?> type) {
        Boolean known = WORLD_ICONS.get(type);
        if (known != null) {
            return known;
        }
        boolean overrides = false;
        for (Class<?> c = type; c != null && c != Block.class && !overrides; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == IIcon.class && params.length == 5
                    && params[0] == IBlockAccess.class
                    && params[1] == int.class
                    && params[2] == int.class
                    && params[3] == int.class
                    && params[4] == int.class) {
                    overrides = true;
                    break;
                }
            }
        }
        WORLD_ICONS.put(type, overrides);
        return overrides;
    }

    /**
     * Hash of what the block's pictures depend on: the block and the blocks around it, its icons in place and its
     * color there (grass and leaves take the biome's). A cube's sides connect (connected textures) with all 26
     * blocks around it; other blocks (fences, panes, plants, pipes) with the 6 next to them. Blocks with the same
     * hash share their pictures, so a meadow of the same flowers is drawn a few times, not once per flower.
     */
    private static long surroundings(World world, Block block, int x, int y, int z, boolean cube) {
        long h = 0xCBF29CE484222325L;
        if (cube) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        h = (h ^ blockAt(world, x + dx, y + dy, z + dz)) * 0x100000001B3L;
                    }
                }
            }
        } else {
            h = (h ^ blockAt(world, x, y, z)) * 0x100000001B3L;
            for (int[] offset : OFFSETS) {
                h = (h ^ blockAt(world, x + offset[0], y + offset[1], z + offset[2])) * 0x100000001B3L;
            }
        }
        try {
            for (int side = 0; side < 6; side++) {
                IIcon icon = block.getIcon(world, x, y, z, side);
                h = (h ^ System.identityHashCode(icon)) * 0x100000001B3L;
            }
            h = (h ^ block.colorMultiplier(world, x, y, z)) * 0x100000001B3L;
        } catch (RuntimeException ignored) {}
        return h;
    }

    /** Id and metadata of the block at a place, 0 outside the world. */
    private static int blockAt(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return 0;
        }
        return Block.getIdFromBlock(world.getBlock(x, y, z)) | world.getBlockMetadata(x, y, z) << 16;
    }

    /** Draws each block from the four view sides into the buffer, reads it back, stores the sprites. */
    private static void draw(World world, List<Pending> batch, FacePalette palette) {
        long setupStart = System.nanoTime();
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.instance;
        int ambientOcclusion = mc.gameSettings.ambientOcclusion;
        int previousFramebuffer = GL11.glGetInteger(0x8CA6); // GL_FRAMEBUFFER_BINDING
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        boolean bound = false;
        try {
            if (framebuffer == null) {
                framebuffer = new Framebuffer(SIZE, SIZE, true);
                readBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
                matrixBuffer = BufferUtils.createFloatBuffer(16);
                planeBuffer = BufferUtils.createDoubleBuffer(4);
            }
            framebuffer.bindFramebuffer(true);
            bound = true;
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            // No light map: every block fully lit, the tracer adds the light of the place.
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // Smooth lighting would darken corners by the light around; the sprites are taken unlit.
            mc.gameSettings.ambientOcclusion = 0;
            RenderBlocks renderBlocks = new RenderBlocks(world);
            long drawStart = System.nanoTime();
            setupNanos += drawStart - setupStart;

            boolean diagnose = IsoLog.on();
            int slot = 0;
            for (Pending pending : batch) {
                if (variant == 0) {
                    pending.shot = diagnose ? new BlockDiag.Shot() : null;
                    if (pending.shot != null && BlockDiag.wantsImages(pending.lookKey)) {
                        pending.shot.images = new int[pending.views()][];
                    }
                }
                long blockStart = System.nanoTime();
                pending.covered = pending.cube ? 0 : covered(world, pending);
                for (int view = 0; view < pending.views(); view++, slot++) {
                    int pixels = pending.cube ? FacePalette.FACE_SIZE : FacePalette.SPRITE_SIZE;
                    GL11.glViewport((slot % PER_ROW) * SLOT, (slot / PER_ROW) * SLOT, pixels, pixels);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    if (pending.cube) {
                        // Straight at one side: the picture covers exactly that side, like its texture.
                        GL11.glOrtho(-0.5, 0.5, -0.5, 0.5, -0.01, 1.01);
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        loadSideView(view);
                    } else {
                        // Two blocks of the projection plane around the block's center, like the tracer reads it.
                        GL11.glOrtho(-1, 1, -1, 1, -2, 2);
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        loadView(IsoProjection.of(view));
                    }
                    GL11.glTranslated(-(pending.x + 0.5), -(pending.y + 0.5), -(pending.z + 0.5));
                    // Only what is inside the block's column: not the other half of a double chest, not neighbours.
                    // A little outside the block, or its own sides, which lie on these planes, get cut off.
                    if (variant != 1) {
                        clipColumn(pending, CLIP_MARGIN, true);
                    } else {
                        for (int plane = 0; plane < 6; plane++) {
                            GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
                        }
                    }
                    // Again for every sprite: a tile entity renderer may have changed any of it.
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDisable(GL11.GL_FOG);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    GL11.glDepthMask(true);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glColor4f(1f, 1f, 1f, 1f);
                    drawBlock(mc, renderBlocks, tessellator, pending, mirrored(pending.cube, view));
                }
                if (variant != 0) {
                    continue;
                }
                long blockNanos = System.nanoTime() - blockStart;
                IsoLog.blockDrawn(pending.block, pending.tileEntity, pending.views(), blockNanos);
                if (blockNanos > 10_000_000L) {
                    IsoLog.log(
                        "SLOW_BLOCK " + pending.block.getUnlocalizedName()
                            + (pending.tileEntity == null ? ""
                                : " [" + pending.tileEntity.getClass()
                                    .getName() + "]")
                            + " at "
                            + pending.x
                            + ","
                            + pending.y
                            + ","
                            + pending.z
                            + " ms="
                            + blockNanos / 1_000_000);
                }
            }
            long readStart = System.nanoTime();
            drawNanos += readStart - drawStart;
            for (int plane = 0; plane < 6; plane++) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            }
            // Only the rows of slots used: reading the buffer back waits for the graphics card, the less the better.
            int usedRows = Math.min(SIZE, (slotsUsed(batch) + PER_ROW - 1) / PER_ROW * SLOT);
            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, usedRows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            if (readPixels == null) {
                readPixels = new int[SIZE * SIZE];
            }
            int[] all = readPixels;
            readBuffer.get(all, 0, usedRows * SIZE);
            long storeStart = System.nanoTime();
            readNanos += storeStart - readStart;
            slot = 0;
            int[] faceImage = new int[FacePalette.FACE_SIZE * FacePalette.FACE_SIZE];
            int[] spriteImage = new int[FacePalette.SPRITE_SIZE * FacePalette.SPRITE_SIZE];
            for (Pending pending : batch) {
                int pixels = pending.cube ? FacePalette.FACE_SIZE : FacePalette.SPRITE_SIZE;
                int[] image = pending.cube ? faceImage : spriteImage;
                for (int view = 0; view < pending.views(); view++, slot++) {
                    int sx = (slot % PER_ROW) * SLOT, sy = (slot / PER_ROW) * SLOT;
                    // A side seen straight on is shaded by the game for that side; the tracer shades it itself.
                    float shade = pending.cube && !pending.ownRenderer ? sideShade(all, sx, sy, pixels, pending, view)
                        : 1f;
                    if (pending.shot != null) {
                        pending.shot.shade[view] = shade;
                    }
                    long u0 = System.nanoTime();
                    for (int row = 0; row < pixels; row++) {
                        // Read back bottom-up; pictures are top-down.
                        int from = (sy + pixels - 1 - row) * SIZE + sx;
                        for (int column = 0; column < pixels; column++) {
                            image[row * pixels + column] = unshade(all[from + column], shade);
                        }
                    }
                    long u1 = System.nanoTime();
                    if (pending.shot != null) {
                        BlockDiag.measure(image, pending.shot, view);
                    }
                    if (variant != 0) {
                        // Only to compare, in the log: not a picture of the palette.
                        continue;
                    }
                    pending.ids[view] = palette.idOf(image);
                    unshadeNanos += u1 - u0;
                    idNanos += System.nanoTime() - u1;
                }
            }
            storeNanos += System.nanoTime() - storeStart;
            if (diagnose && variant == 0) {
                for (Pending pending : batch) {
                    BlockDiag.picture(
                        pending.block,
                        pending.lookKey,
                        pending.tileEntity,
                        pending.x,
                        pending.y,
                        pending.z,
                        pending.cube,
                        pending.exposed,
                        pending.why,
                        pending.ids,
                        pending.views(),
                        pending.shot);
                    pending.shot = null;
                }
            }
            failures = 0;
        } catch (Throwable t) {
            // These blocks are drawn from their icons this time.
            for (Pending pending : batch) {
                Arrays.fill(pending.ids, 0);
            }
            IsoLog.log("PICTURES_FAILED batch of " + batch.size() + ": " + t);
            if (++failures >= 5) {
                // Something in this driver or the mods' renderers does not like this: no more pictures.
                broken = true;
                WayFarMap.LOG.warn("The 3D map can't take pictures of blocks; it uses their icons instead", t);
            } else {
                WayFarMap.LOG.debug("Could not take pictures of blocks for the 3D map", t);
            }
        } finally {
            mc.gameSettings.ambientOcclusion = ambientOcclusion;
            if (bound) {
                // Back to the buffer bound before (the game's own while a frame is drawn).
                IconReader.rebind(mc, previousFramebuffer, framebuffer);
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /**
     * For the log: whether the pictures just taken could have been skipped. Taken again only because they were too
     * old, yet the same? The same as another tile entity's with the same block and surroundings (with and without
     * its data)? All empty (and the block only open at the bottom)?
     */
    private static void checkSkippable(List<Pending> batch) {
        for (Pending pending : batch) {
            if (missing(pending)) {
                continue;
            }
            boolean empty = true;
            for (int view = 0; view < pending.views(); view++) {
                empty &= pending.ids[view] == FacePalette.EMPTY;
            }
            if (empty) {
                allEmpty++;
                if (pending.exposed == 1) {
                    allEmptyOnlyBottom++;
                }
            }
            if (pending.oldIds != null) {
                if (Arrays.equals(pending.oldIds, pending.ids)) {
                    expiredSame++;
                } else {
                    expiredDiffer++;
                }
            }
            if (pending.tileEntity == null) {
                continue;
            }
            if (TWINS.size() > 200_000) {
                TWINS.clear();
                TWINS_WITH_DATA.clear();
            }
            long twin = pending.surroundings * 31 + pending.tileEntity.getClass()
                .hashCode();
            int[] other = TWINS.putIfAbsent(twin, pending.ids.clone());
            if (other != null) {
                if (Arrays.equals(other, pending.ids)) {
                    sameAsTwin++;
                } else {
                    differFromTwin++;
                }
            }
            long withData = twin * 31 + dataHash(pending.tileEntity);
            int[] otherWithData = TWINS_WITH_DATA.putIfAbsent(withData, pending.ids.clone());
            if (otherWithData != null) {
                if (Arrays.equals(otherWithData, pending.ids)) {
                    sameAsTwinWithData++;
                } else {
                    differFromTwinWithData++;
                }
            }
        }
    }

    /** Hash of what a tile entity keeps, without where it is. */
    private static int dataHash(TileEntity tileEntity) {
        try {
            net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            tileEntity.writeToNBT(tag);
            tag.removeTag("x");
            tag.removeTag("y");
            tag.removeTag("z");
            return tag.hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Whether a picture of the block wasn't taken (0: the palette had no room, or drawing failed). */
    private static boolean missing(Pending pending) {
        for (int view = 0; view < pending.views(); view++) {
            if (pending.ids[view] == 0) {
                return true;
            }
        }
        return false;
    }

    private static int slotsUsed(List<Pending> batch) {
        int slots = 0;
        for (Pending pending : batch) {
            slots += pending.views();
        }
        return slots;
    }

    /**
     * Keeps only what is within the block's column, and the margin around it (with the camera of the view set). Its
     * sides hidden by a solid block next to it are cut off: the game draws them (the top of a Nuclear Control panel
     * under another), the world hides them, but in the sprite they showed, and where the sprite's pixels of such a
     * side and a seen one meet on their edge, the hidden one drew lines along the seams of a wall.
     */
    private static void clipColumn(Pending pending, double margin, boolean hideCovered) {
        int covered = hideCovered ? pending.covered : 0;
        clip(0, 1, 0, 0, -(pending.x - side(covered, 4, margin)));
        clip(1, -1, 0, 0, pending.x + 1 + side(covered, 5, margin));
        clip(2, 0, 0, 1, -(pending.z - side(covered, 2, margin)));
        clip(3, 0, 0, -1, pending.z + 1 + side(covered, 3, margin));
        if ((covered & 1) != 0) {
            clip(4, 0, 1, 0, -(pending.y + COVERED_INSET));
        } else {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + 4);
        }
        if ((covered & 2) != 0) {
            clip(5, 0, -1, 0, pending.y + 1 - COVERED_INSET);
        } else {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + 5);
        }
    }

    /** How far outside the block its column is kept on a side: the margin, or inside it if the side is hidden. */
    private static double side(int covered, int side, double margin) {
        return (covered & 1 << side) != 0 ? -COVERED_INSET : margin;
    }

    /**
     * The sides of a block hidden by the blocks next to them: a solid cube, or a block filling its cell whose side
     * toward it is drawn without holes (the next block of a Nuclear Control panel; not frames or glass).
     */
    private static int covered(World world, Pending pending) {
        int covered = 0;
        for (int side = 0; side < 6; side++) {
            int nx = pending.x + OFFSETS[side][0], ny = pending.y + OFFSETS[side][1], nz = pending.z + OFFSETS[side][2];
            int key = blockAt(world, nx, ny, nz);
            if (ChunkBlocks.blockId(key) == 0) {
                continue;
            }
            BlockLooks.Look look = BlockLooks.get(key);
            boolean hides = look.opaque;
            if (!hides && look.fullCube && !look.translucent) {
                // Its side toward this block: down <-> up, north <-> south, west <-> east.
                BlockLooks.Texture texture = look.textures[side ^ 1];
                hides = texture != null && texture.solid();
            }
            if (hides) {
                covered |= 1 << side;
            }
        }
        return covered;
    }

    /** Keeps what is on the positive side of a plane: a * x + b * y + c * z + d >= 0 (world coordinates). */
    private static void clip(int plane, double a, double b, double c, double d) {
        planeBuffer.clear();
        planeBuffer.put(a)
            .put(b)
            .put(c)
            .put(d);
        planeBuffer.flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + plane, planeBuffer);
        GL11.glEnable(GL11.GL_CLIP_PLANE0 + plane);
    }

    /** The block as the world draws it, then its tile entity and those next to it (a double chest's other half). */
    private static void drawBlock(Minecraft mc, RenderBlocks renderBlocks, Tessellator tessellator, Pending pending,
        boolean mirrored) {
        try {
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
        } catch (RuntimeException e) {
            return;
        }
        // In each render pass the block is drawn in, telling its renderer which pass it is, as the game does: many
        // renderers (connected glass, modded blocks, see-through parts) draw nothing when asked in another pass,
        // which left their sprites empty and the blocks invisible on the map.
        int worldPass = RenderPass.world(), entityPass = RenderPass.entity();
        // See-through blocks (glass) with back faces culled, as the game draws the world: without it their far
        // faces showed through the near ones, darker, as shadows inside the glass on the map.
        BlockLooks.Look look = BlockLooks.get(pending.lookKey);
        boolean cull = look.translucent || look.fullCube && !look.opaque || variant == 2;
        if (cull) {
            // The map's camera may be a mirror image: then the faces toward it wind the other way.
            GL11.glFrontFace(mirrored ? GL11.GL_CW : GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        try {
            for (int pass = 0; pass < 2; pass++) {
                boolean inPass;
                try {
                    inPass = pending.block.canRenderInPass(pass);
                } catch (RuntimeException e) {
                    inPass = pass == 0;
                }
                if (!inPass) {
                    continue;
                }
                RenderPass.setWorld(pass);
                // Whether this drawing is open is tracked here: the tessellator keeps it to itself.
                boolean drawing = false;
                try {
                    tessellator.startDrawingQuads();
                    drawing = true;
                    renderBlocks.renderBlockByRenderType(pending.block, pending.x, pending.y, pending.z);
                    drawing = false;
                    int bytes = tessellator.draw();
                    if (pending.shot != null) {
                        if (pass == 0) {
                            pending.shot.passBytes0 = Math.max(0, pending.shot.passBytes0) + bytes;
                        } else {
                            pending.shot.passBytes1 = Math.max(0, pending.shot.passBytes1) + bytes;
                        }
                    }
                } catch (RuntimeException e) {
                    if (pending.shot != null && pending.shot.error == null) {
                        pending.shot.error = "block renderer, pass " + pass + ": " + BlockDiag.error(e);
                    }
                    if (drawing) {
                        try {
                            tessellator.draw();
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
            RenderPass.setWorld(worldPass);
            if (cull && variant != 2) {
                // Tile entity renderers as before (their models may wind either way).
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (pending.tileEntity != null) {
                if (variant == 3) {
                    RenderHelper.enableStandardItemLighting();
                }
                for (int pass = 0; pass < 2; pass++) {
                    RenderPass.setEntity(pass);
                    drawTileEntities(pending, pass);
                }
                if (variant == 3) {
                    RenderHelper.disableStandardItemLighting();
                }
            }
        } finally {
            RenderPass.setWorld(worldPass);
            RenderPass.setEntity(entityPass);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glFrontFace(GL11.GL_CCW);
        }
    }

    /**
     * Tile entities whose renderer draws far past their block, by the box they tell the game to draw them in (a
     * stargate's whole ring, drawn by its base: the ring's blocks draw nothing). Found again at most once a second.
     */
    private static List<TileEntity> bigTileEntities = new ArrayList<>();
    private static World bigWorld;
    private static long bigFoundAt;
    /** Largest size of such a box that is believed (some say "everywhere"). */
    private static final double BIG_MAX_SIZE = 32;

    private static List<TileEntity> bigTileEntities(World world) {
        long now = System.currentTimeMillis();
        if (world == bigWorld && now - bigFoundAt < 1000) {
            return bigTileEntities;
        }
        bigWorld = world;
        bigFoundAt = now;
        List<TileEntity> found = new ArrayList<>();
        for (Object o : world.loadedTileEntityList) {
            if (!(o instanceof TileEntity) || o instanceof TileEntityChest || o instanceof TileEntityEnderChest) {
                // Chests say a box a block larger all around for their lids; their other half is drawn anyway.
                continue;
            }
            TileEntity tileEntity = (TileEntity) o;
            try {
                if (tileEntity.isInvalid() || !TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity)) {
                    continue;
                }
                AxisAlignedBB box = tileEntity.getRenderBoundingBox();
                if (box == null || box == TileEntity.INFINITE_EXTENT_AABB
                    || box.maxX - box.minX > BIG_MAX_SIZE
                    || box.maxY - box.minY > BIG_MAX_SIZE
                    || box.maxZ - box.minZ > BIG_MAX_SIZE) {
                    continue;
                }
                int x = tileEntity.xCoord, y = tileEntity.yCoord, z = tileEntity.zCoord;
                double m = OWN_MODEL_MARGIN;
                if (box.minX < x - m || box.maxX > x + 1 + m
                    || box.minY < y - m
                    || box.maxY > y + 1 + m
                    || box.minZ < z - m
                    || box.maxZ > z + 1 + m) {
                    found.add(tileEntity);
                }
            } catch (RuntimeException ignored) {}
        }
        bigTileEntities = found;
        return found;
    }

    /**
     * The block's tile entity, those next to it (a double chest's other half) and those drawing over its place from
     * farther (a stargate's base under a block of its ring) that draw in the render pass.
     */
    private static void drawTileEntities(Pending pending, int pass) {
        World world = pending.tileEntity.getWorldObj();
        List<TileEntity> big = world == null ? new ArrayList<>() : bigTileEntities(world);
        for (int n = -1; n < 4 + big.size(); n++) {
            TileEntity tileEntity;
            if (n < 0) {
                tileEntity = pending.tileEntity;
            } else if (n < 4) {
                tileEntity = world == null ? null
                    : world.getTileEntity(
                        pending.x + (n == 0 ? -1 : n == 1 ? 1 : 0),
                        pending.y,
                        pending.z + (n == 2 ? -1 : n == 3 ? 1 : 0));
            } else {
                tileEntity = big.get(n - 4);
                if (!drawsOver(tileEntity, pending)) {
                    continue;
                }
            }
            if (tileEntity == null || !TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity)) {
                continue;
            }
            try {
                if (!tileEntity.shouldRenderInPass(pass)) {
                    continue;
                }
                if (variant != 1) {
                    // Its own model with room around; one next to it (a double chest's other half) only in this
                    // column. Not cut at its hidden sides: what they draw (a panel's text) reaches past them.
                    clipColumn(pending, n < 0 ? OWN_MODEL_MARGIN : CLIP_MARGIN, false);
                    if (n >= 4) {
                        // Of a model drawn from farther, only what is in this block's place: the blocks above and
                        // below take their own part.
                        clip(4, 0, 1, 0, -(pending.y - CLIP_MARGIN));
                        clip(5, 0, -1, 0, pending.y + 1 + CLIP_MARGIN);
                    }
                }
                TileEntityRendererDispatcher.instance
                    .renderTileEntityAt(tileEntity, tileEntity.xCoord, tileEntity.yCoord, tileEntity.zCoord, 0f);
                if (pending.shot != null) {
                    pending.shot.tileEntitiesDrawn++;
                }
            } catch (RuntimeException e) {
                // A renderer that needs more than this; the block's own drawing stays.
                if (pending.shot != null && pending.shot.error == null) {
                    pending.shot.error = "tile entity renderer " + tileEntity.getClass()
                        .getSimpleName() + ", pass " + pass + ": " + BlockDiag.error(e);
                }
            }
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL11.GL_LIGHTING);
        }
    }

    /**
     * Whether a tile entity drawing past its block draws over this block's place, and isn't drawn for it already
     * (its own, or one next to it).
     */
    private static boolean drawsOver(TileEntity tileEntity, Pending pending) {
        int dx = tileEntity.xCoord - pending.x, dy = tileEntity.yCoord - pending.y, dz = tileEntity.zCoord - pending.z;
        if (dy == 0 && Math.abs(dx) + Math.abs(dz) <= 1) {
            return false;
        }
        try {
            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            return box != null && box.maxX > pending.x
                && box.minX < pending.x + 1
                && box.maxY > pending.y
                && box.minY < pending.y + 1
                && box.maxZ > pending.z
                && box.minZ < pending.z + 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The game's shading of a side taken out: how much darker the side came out than its icon. Renderers shade in
     * their own ways (GregTech's machine casings: the top as dark as the bottom), so it isn't taken from the game's
     * usual numbers but measured; its usual number when it can't be (a tinted or unreadable icon, nothing drawn).
     */
    private static float sideShade(int[] all, int sx, int sy, int pixels, Pending pending, int side) {
        float usual = SIDE_SHADE[side];
        BlockLooks.Look look = BlockLooks.get(pending.lookKey);
        BlockLooks.Texture icon = look.textures[side];
        if (icon == null || icon.mips[0] == null || look.tint != BlockLooks.TINT_NONE && look.tintSide[side]) {
            return usual;
        }
        double iconLight = meanLight(icon.mips[0], 0, 0, (int) Math.round(Math.sqrt(icon.mips[0].length)), 0);
        double pictureLight = meanLight(all, sx, sy, pixels, SIZE);
        if (iconLight <= 8 || pictureLight <= 0) {
            return usual;
        }
        // Never brighter than the icon, never more than 2.5 times darker (a machine front darker than its icon).
        return (float) Math.max(0.4, Math.min(1.0, pictureLight / iconLight));
    }

    /** Mean brightness of the drawn pixels of a square (of {@code stride} pixels per row, 0 for its own width). */
    private static double meanLight(int[] pixels, int x0, int y0, int size, int stride) {
        int width = stride == 0 ? size : stride;
        long sum = 0;
        int count = 0;
        for (int y = 0; y < size; y++) {
            int row = (y0 + y) * width + x0;
            for (int x = 0; x < size; x++) {
                int pixel = pixels[row + x];
                if ((pixel >>> 24) >= 128) {
                    sum += ((pixel >> 16) & 0xFF) * 299 + ((pixel >> 8) & 0xFF) * 587 + (pixel & 0xFF) * 114;
                    count++;
                }
            }
        }
        return count == 0 ? 0 : sum / 1000.0 / count;
    }

    /** Whether the camera for a view (or a cube's side) is a mirror image, which turns faces' winding around. */
    private static boolean mirrored(boolean cube, int view) {
        double[] r;
        if (cube) {
            float[] m = SIDE_VIEWS[view];
            r = new double[] { m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10] };
        } else {
            IsoProjection p = IsoProjection.of(view);
            double sin = IsoProjection.SIN, cos = IsoProjection.COS;
            r = new double[] { p.rightX, 0, p.rightZ, -sin * p.towardX, cos, -sin * p.towardZ, cos * p.towardX, sin,
                cos * p.towardZ };
        }
        double det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6])
            + r[2] * (r[3] * r[7] - r[4] * r[6]);
        if (!cullLogged) {
            cullLogged = true;
            StringBuilder b = new StringBuilder("CAMERAS mirrored (faces wind the other way):");
            for (int v = 0; v < ChunkBlocks.VIEWS; v++) {
                b.append(" view")
                    .append(v)
                    .append('=')
                    .append(mirrored(false, v));
            }
            for (int side = 0; side < 6; side++) {
                b.append(" side")
                    .append(side)
                    .append('=')
                    .append(mirrored(true, side));
            }
            IsoLog.log(b.toString());
        }
        return det < 0;
    }

    /**
     * For the log: the first block of each kind drawn by a tile entity renderer is taken again in other ways (not cut
     * to its column, back faces culled, the game's item lighting), saved as PNG to compare, to find why a model lacks
     * parts on the map (a Blood Magic altar without sides).
     */
    private static void tryVariants(World world, List<Pending> batch, FacePalette palette) {
        for (Pending pending : batch) {
            if (pending.tileEntity == null || !pending.ownRenderer || !BlockDiag.wantsVariants(pending.lookKey)) {
                continue;
            }
            for (int v = 1; v < VARIANTS.length; v++) {
                Pending copy = new Pending(
                    pending.cellIndex,
                    pending.x,
                    pending.y,
                    pending.z,
                    pending.block,
                    pending.tileEntity,
                    pending.surroundings,
                    pending.cube,
                    pending.ownRenderer);
                copy.lookKey = pending.lookKey;
                copy.exposed = pending.exposed;
                copy.shot = new BlockDiag.Shot();
                copy.shot.images = new int[copy.views()][];
                variant = v;
                try {
                    draw(world, java.util.Collections.singletonList(copy), palette);
                } finally {
                    variant = 0;
                }
                BlockDiag.variant(
                    copy.block,
                    copy.lookKey,
                    copy.x,
                    copy.y,
                    copy.z,
                    VARIANTS[v],
                    copy.cube,
                    copy.views(),
                    copy.shot);
            }
        }
    }

    private static void loadSideView(int side) {
        float[] m = SIDE_VIEWS[side];
        matrixBuffer.clear();
        // Column-major.
        for (int column = 0; column < 4; column++) {
            matrixBuffer.put(m[column])
                .put(m[4 + column])
                .put(m[8 + column])
                .put(column == 3 ? 1f : 0f);
        }
        matrixBuffer.flip();
        GL11.glLoadMatrix(matrixBuffer);
    }

    /** Takes the game's side shading out of a pixel, so the tracer can shade it by the light of its place. */
    private static int unshade(int argb, float shade) {
        if (shade >= 1f || (argb >>> 24) == 0) {
            return argb;
        }
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) / shade + 0.5f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) / shade + 0.5f));
        int b = Math.min(255, (int) ((argb & 0xFF) / shade + 0.5f));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * The 3D map's camera for a view side: eye x is the projection plane's u (right), eye y is -v (up on screen),
     * eye z points back toward the viewer (along the rays, reversed).
     */
    private static void loadView(IsoProjection p) {
        double sin = IsoProjection.SIN, cos = IsoProjection.COS;
        // Rows of the rotation: right, up, back.
        double[][] rows = { { p.rightX, 0, p.rightZ }, { -sin * p.towardX, cos, -sin * p.towardZ },
            { cos * p.towardX, sin, cos * p.towardZ } };
        matrixBuffer.clear();
        // Column-major.
        for (int column = 0; column < 3; column++) {
            matrixBuffer.put((float) rows[0][column])
                .put((float) rows[1][column])
                .put((float) rows[2][column])
                .put(0f);
        }
        matrixBuffer.put(0f)
            .put(0f)
            .put(0f)
            .put(1f);
        matrixBuffer.flip();
        GL11.glLoadMatrix(matrixBuffer);
    }
}
