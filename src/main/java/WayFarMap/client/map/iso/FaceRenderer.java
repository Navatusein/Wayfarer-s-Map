package WayFarMap.client.map.iso;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

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
     * Whether clip planes may be used, null until checked. OpenGL ES (Android launchers) has them only with
     * GL_EXT_clip_cull_distance: without it, Angelica's shader for any drawing with a clip plane on fails to compile
     * and the game crashes (at the next drawing, often another mod's). Pictures are then taken without them.
     */
    private static Boolean clipPlanesWork;
    /**
     * Pictures of blocks with a tile entity, by place: they depend on what is in it, so they aren't shared between
     * places, but the chunks near the player are copied every few seconds and are not taken again each time.
     */
    private static final Map<Long, Cached> BY_PLACE = lru(400_000);
    /**
     * Blocks of chunks whose pictures are being taken, by chunk: a chunk with thousands of machines takes many ticks,
     * and finding its blocks again each tick (their surroundings above all) took half of each tick's time.
     */
    private static final Map<Long, Session> SESSIONS = lru(64);

    /**
     * A batch of pictures drawn and being read back into a pixel buffer object, without waiting for the graphics card:
     * reading them at once ({@code glReadPixels} into memory) waited for it to finish all it was given, the last frame
     * too, and took a third of the time of taking pictures. They are read out the next tick ({@link #finishFlights}),
     * when it is long done.
     */
    private static final class Flight {

        final List<Pending> batch;
        final int pbo, usedRows, cell, perRow;
        final boolean diagnose;
        Map<Long, List<Pending>> waiting;

        Flight(List<Pending> batch, int pbo, int usedRows, int cell, int perRow, boolean diagnose) {
            this.batch = batch;
            this.pbo = pbo;
            this.usedRows = usedRows;
            this.cell = cell;
            this.perRow = perRow;
            this.diagnose = diagnose;
        }
    }

    /** Batches being read back, oldest first. */
    private static final List<Flight> FLIGHTS = new ArrayList<>();
    /** Pixel buffer objects not in use, and how many were made (at most {@link #MAX_PBOS}). */
    private static final ArrayDeque<Integer> FREE_PBOS = new ArrayDeque<>();
    private static final int MAX_PBOS = 8;
    private static int pboCount;
    /**
     * Whether pictures may be read back later (OpenGL 2.1 pixel buffer objects); null until checked, false after any
     * failure (then they are read at once, as before). {@code -Dwayfarmap.syncPictures=true} turns it off.
     */
    private static Boolean asyncWorks;
    /** The last {@link #addFaces} is not complete only because its pictures are still being read back. */
    static boolean lastInFlight;
    /** For the log: time reading out and storing pictures drawn the tick before, since the log last took it. */
    static long finishNanos;

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

    /**
     * Pictures learned by kind: a block (with its metadata) open on the same sides with the same six blocks next to
     * it. The surroundings above also hold the place's color and textures, which make nearly every place a key of its
     * own (the biome's color blends from block to block), yet most blocks look the same wherever they are: grass-like
     * plants, ores the game draws nothing of here. Once a kind gave the very same pictures {@link #LEARN_CONFIRMATIONS}
     * times, the next ones get them without being drawn; every {@link #VERIFY_EVERY}th is drawn anyway to check, and a
     * kind that gave other pictures once is drawn every time from then on. Not for solid cubes (connected textures go
     * by all 26 blocks around) nor tile entities (kept by place).
     */
    private static final Map<Long, Learned> BY_KIND = new HashMap<>();
    private static final int LEARN_CONFIRMATIONS = 3, VERIFY_EVERY = 16;
    /**
     * Pictures learned by block alone (with its metadata and open sides, whatever is next to it), for the blocks
     * whose six neighbours make nearly every place a kind of its own: grass with a different block under it, ores
     * between other stones. Learned from {@link #BLOCK_CONFIRMATIONS} pictures alike, taken in at least
     * {@link #BLOCK_PLACES} kinds of places. Only for blocks that can't reach their neighbours (smaller than their cell
     * on every side: plants, an unconnected fence post), or whose pictures are all empty (ores the game draws nothing
     * of here): a block that joins its neighbours looks by them.
     */
    private static final Map<Long, Learned> BY_BLOCK = new HashMap<>();
    private static final int BLOCK_CONFIRMATIONS = 6, BLOCK_PLACES = 3;
    /**
     * How far apart pictures of a block that can't reach its neighbours may be and still count as the same: plants
     * the game moves a little from place to place (tall grass, Biomes O' Plenty's foliage), so no two places give the
     * very same picture. Share of the picture covered (relative) and average color (per channel, 0-255): loose, as
     * the part of a moved plant reaching past its cell is cut off.
     */
    private static final float ALIKE_COVERAGE = 0.5f, ALIKE_COLOR = 32f;
    /**
     * Coverage and average color of sprites by id, for {@link #alike}: worked out when they are drawn (reading a
     * sprite back from the palette waited for its saving and made ticks of 200 ms). Cleared with the other caches.
     */
    private static final Map<Integer, float[]> LOOKS_OF_SPRITES = new HashMap<>();
    /** Look keys a kind was found unreliable for, logged only the first few times. */
    private static final Map<Integer, Integer> UNRELIABLE_LOGGED = new HashMap<>();

    private static final class Learned {

        final int[] ids;
        int confirmed, reused;
        boolean unreliable;
        /** By block: the kinds of places (their {@link #kindKey}) it was confirmed in, the first few. */
        final long[] places = new long[BLOCK_PLACES];
        int placeCount;

        Learned(int[] ids) {
            this.ids = ids;
        }

        boolean allEmpty() {
            for (int id : ids) {
                if (id != FacePalette.EMPTY && id != 0) {
                    return false;
                }
            }
            return true;
        }

        void place(long kindKey) {
            for (int n = 0; n < placeCount; n++) {
                if (places[n] == kindKey) {
                    return;
                }
            }
            if (placeCount < places.length) {
                places[placeCount++] = kindKey;
            }
        }

        /** Its pictures, to give a block without drawing it, or null: not learned yet, or this one is to check. */
        int[] reuse(int confirmations, int placesNeeded) {
            if (unreliable || confirmed < confirmations || placeCount < placesNeeded) {
                return null;
            }
            return ++reused % VERIFY_EVERY != 0 ? ids : null;
        }
    }

    /** The key of a block for {@link #BY_BLOCK}: its look, open sides, width and whether it can reach out. */
    private static long blockKey(int lookKey, int exposed, boolean wide, boolean detached, int tint) {
        long h = 0x51AFD7ED558CCD1DL
            ^ ((long) tint << 40 | (long) lookKey << 8 | (long) exposed << 2 | (wide ? 2 : 0) | (detached ? 1 : 0));
        h = (h ^ h >>> 33) * 0xFF51AFD7ED558CCDL;
        return h ^ h >>> 33;
    }

    /**
     * The color the game tints the block with here (grass and leaves by the biome), 16 steps per channel: the pictures
     * are taken with it, so a plant of one biome doesn't look like the same plant of another. One biome gives one
     * key; where biomes blend, a few.
     */
    private static int tint(World world, Block block, int x, int y, int z) {
        try {
            int c = block.colorMultiplier(world, x, y, z);
            return (c >> 20 & 0xF) << 8 | (c >> 12 & 0xF) << 4 | (c >> 4 & 0xF);
        } catch (RuntimeException e) {
            return 0xFFF;
        }
    }

    /**
     * Whether the block, as it is here, stays inside its cell on all four sides (a plant, a torch, a fence post with
     * nothing to join): then what is next to it doesn't change how it looks.
     */
    private static boolean detached(World world, Block block, int x, int y, int z) {
        try {
            block.setBlockBoundsBasedOnState(world, x, y, z);
            return block.getBlockBoundsMinX() > 0.01 && block.getBlockBoundsMaxX() < 0.99
                && block.getBlockBoundsMinZ() > 0.01
                && block.getBlockBoundsMaxZ() < 0.99;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether two blocks' pictures look the same: the very same, or (for a block that can't reach its neighbours)
     * each view covering about as much with about the same color, as a plant moved a little does.
     */
    private static boolean alike(int[] a, int[] b, boolean detached) {
        if (Arrays.equals(a, b)) {
            return true;
        }
        if (!detached) {
            return false;
        }
        for (int view = 0; view < a.length; view++) {
            if (a[view] == b[view]) {
                continue;
            }
            if (a[view] <= 0 || b[view] <= 0) {
                return false;
            }
            float[] la = LOOKS_OF_SPRITES.get(a[view]), lb = LOOKS_OF_SPRITES.get(b[view]);
            if (la == null || lb == null) {
                return false;
            }
            if (Math.abs(la[0] - lb[0]) > ALIKE_COVERAGE * Math.max(la[0], lb[0]) + 0.01f) {
                return false;
            }
            for (int channel = 1; channel <= 3; channel++) {
                if (Math.abs(la[channel] - lb[channel]) > ALIKE_COLOR) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A picture's coverage (0-1) and average red, green and blue (every other pixel); any thread. */
    private static float[] look(int[] pixels) {
        int side = FacePalette.sideOf(pixels.length);
        long alpha = 0, r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = 0; y < side; y += 2) {
            for (int x = 0; x < side; x += 2) {
                int c = pixels[y * side + x], a = c >>> 24;
                alpha += a;
                r += (c >> 16 & 0xFF) * a;
                g += (c >> 8 & 0xFF) * a;
                b += (c & 0xFF) * a;
                count++;
            }
        }
        return alpha == 0 ? new float[] { 0, 0, 0, 0 }
            : new float[] { alpha / (255f * count), r / (float) alpha, g / (float) alpha, b / (float) alpha };
    }

    /** The kind of a block for {@link #BY_KIND}: its look, open sides, width and the six blocks next to it. */
    private static long kindKey(World world, int lookKey, int exposed, boolean wide, int tint, int x, int y, int z) {
        long h = 0x84222325CBF29CE4L ^ ((long) tint << 40 | (long) lookKey << 8 | (long) exposed << 1 | (wide ? 1 : 0));
        for (int[] offset : OFFSETS) {
            h = (h ^ blockAt(world, x + offset[0], y + offset[1], z + offset[2])) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
        }
        return h;
    }

    /** Whether a block's pictures may be learned by kind. */
    private static boolean learnable(Pending pending) {
        return !pending.cube && !pending.byPlace() && !pending.ownRenderer;
    }

    /** What the learned pictures did, by look key, since the log started: see {@link #KIND_FIELDS}. */
    private static final Map<Integer, long[]> KIND_STATS = new HashMap<>();
    static final String[] KIND_FIELDS = { "reused", "drawn", "drawnEmpty", "confirmed", "conflicts", "alike",
        "reusedByBlock", "blockConflicts" };

    private static void kindStat(int lookKey, int field) {
        if (IsoLog.on()) {
            KIND_STATS.computeIfAbsent(lookKey, k -> new long[KIND_FIELDS.length])[field]++;
        }
    }

    /** The learned pictures' counts by look key (for the log), and how many kinds are learned or unreliable. */
    static Map<Integer, long[]> kindStats() {
        return KIND_STATS;
    }

    /** {learned, unreliable, seen} kinds of places, then the same by block alone. */
    static int[] learnedKinds() {
        int learned = 0, unreliable = 0, blockLearned = 0, blockUnreliable = 0;
        for (Learned l : BY_KIND.values()) {
            if (l.unreliable) {
                unreliable++;
            } else if (l.confirmed >= LEARN_CONFIRMATIONS) {
                learned++;
            }
        }
        for (Learned l : BY_BLOCK.values()) {
            if (l.unreliable) {
                blockUnreliable++;
            } else if (l.confirmed >= BLOCK_CONFIRMATIONS && l.placeCount >= BLOCK_PLACES) {
                blockLearned++;
            }
        }
        return new int[] { learned, unreliable, BY_KIND.size(), blockLearned, blockUnreliable, BY_BLOCK.size() };
    }

    /**
     * Remembers the pictures just taken of a block for its kind and for the block alone: alike pictures confirm them,
     * other ones make it be drawn every time. Not from a block at the edge of what is loaded (its pictures may be
     * wrong).
     */
    private static void learn(Pending pending) {
        boolean empty = true;
        for (int view = 0; view < pending.views(); view++) {
            empty &= pending.ids[view] == FacePalette.EMPTY;
        }
        kindStat(pending.lookKey, 1);
        if (empty) {
            kindStat(pending.lookKey, 2);
        }
        if (pending.unsure || !learnable(pending)) {
            return;
        }
        Learned learned = BY_KIND.get(pending.kindKey);
        if (learned == null) {
            BY_KIND.put(pending.kindKey, new Learned(pending.ids.clone()));
        } else if (!learned.unreliable) {
            if (Arrays.equals(learned.ids, pending.ids)) {
                learned.confirmed++;
                kindStat(pending.lookKey, 3);
            } else if (alike(learned.ids, pending.ids, pending.detached)) {
                learned.confirmed++;
                kindStat(pending.lookKey, 5);
            } else {
                learned.unreliable = true;
                kindConflicts++;
                kindStat(pending.lookKey, 4);
                logUnreliable("KIND_UNRELIABLE", pending, learned);
            }
        }
        Learned byBlock = BY_BLOCK.get(pending.blockKey);
        if (byBlock == null) {
            byBlock = new Learned(pending.ids.clone());
            byBlock.place(pending.kindKey);
            BY_BLOCK.put(pending.blockKey, byBlock);
        } else if (!byBlock.unreliable) {
            if (alike(byBlock.ids, pending.ids, pending.detached)) {
                byBlock.confirmed++;
                byBlock.place(pending.kindKey);
            } else {
                byBlock.unreliable = true;
                kindStat(pending.lookKey, 7);
                logUnreliable("BLOCK_UNRELIABLE", pending, byBlock);
            }
        }
    }

    /** Logs that a kind gave other pictures, the first few times for each block. */
    private static void logUnreliable(String what, Pending pending, Learned learned) {
        if (!IsoLog.on() || UNRELIABLE_LOGGED.merge(pending.lookKey, 1, Integer::sum) > 3) {
            return;
        }
        IsoLog.log(
            what + " "
                + BlockDiag.name(pending.lookKey)
                + " openSides="
                + pending.exposed
                + " detached="
                + pending.detached
                + " at "
                + pending.x
                + ","
                + pending.y
                + ","
                + pending.z
                + " after "
                + learned.confirmed
                + " same: pictures "
                + Arrays.toString(learned.ids)
                + " now "
                + Arrays.toString(pending.ids)
                + (pending.detached ? " looks[coverage,r,g,b] " + looks(learned.ids) + " now " + looks(pending.ids)
                    : "")
                + " (drawn every time from now on; logged 3 times per block at most)");
    }

    /** For the log: each picture's coverage and average color, as {@link #alike} compares them. */
    private static String looks(int[] ids) {
        StringBuilder b = new StringBuilder("[");
        for (int view = 0; view < ChunkBlocks.VIEWS; view++) {
            float[] look = LOOKS_OF_SPRITES.get(ids[view]);
            b.append(view == 0 ? "" : " ")
                .append(
                    look == null ? "-"
                        : String.format(Locale.ROOT, "%.2f,%.0f,%.0f,%.0f", look[0], look[1], look[2], look[3]));
        }
        return b.append(']')
            .toString();
    }

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
        /**
         * Its model reaches far past its block (a banner two blocks tall, an obelisk): sprites four blocks wide around
         * its center ({@link #WIDE_SPRITE_SIZE} pixels), not two.
         */
        boolean wide;
        /** A tile entity farther away draws over its place (a stargate's base under its ring): kept by place. */
        boolean overBig;
        /** Its kind for {@link #BY_KIND} and its key for {@link #BY_BLOCK} (if learnable). */
        long kindKey, blockKey;
        /** It stays inside its cell on all four sides here (see {@link #detached}). */
        boolean detached;

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

        /** Pixels per side of its pictures. */
        int pixels() {
            return cube ? FacePalette.FACE_SIZE : wide ? WIDE_SPRITE_SIZE : FacePalette.SPRITE_SIZE;
        }

        /** Kept by its place (what is drawn depends on more than the blocks around it). */
        boolean byPlace() {
            return tileEntity != null || overBig;
        }
    }

    /** Pixels per side of a sprite four blocks wide (see {@link Pending#wide}): as sharp as the usual ones. */
    static final int WIDE_SPRITE_SIZE = 2 * FacePalette.SPRITE_SIZE;

    /** What {@link #inspect} found out about one block's pictures. */
    static final class Inspection {

        boolean cube, ownRenderer, glassLike, wide, overBig;
        int exposed, covered;
        long surroundings;
        /** Picture ids the fresh pictures got (as the map would store them). */
        int[] ids;
        /** Per way of taking them ({@link #VARIANTS}): how it went, with the pictures. */
        BlockDiag.Shot[] shots = new BlockDiag.Shot[VARIANTS.length];
        /** Tile entities drawing over its place from farther (a stargate's base), as text. */
        List<String> drawnOver = new ArrayList<>();
        /** Its pictures as kept by place (tile entities), or null: surroundings and ids. */
        String cached;
    }

    /**
     * Takes the pictures of one block now, the usual way and the other ways to compare (render thread), for the
     * report of {@code /wfmap3d}: they are also what the map would store for it.
     */
    static Inspection inspect(World world, int x, int y, int z, FacePalette palette) {
        Inspection result = new Inspection();
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        int key = Block.getIdFromBlock(block) | meta << 16;
        BlockLooks.Look look = BlockLooks.get(key);
        TileEntity tileEntity = null;
        boolean ownRenderer = false;
        try {
            if (block.hasTileEntity(meta)) {
                tileEntity = world.getTileEntity(x, y, z);
                ownRenderer = tileEntity != null
                    && TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity);
            }
        } catch (RuntimeException ignored) {}
        result.ownRenderer = ownRenderer;
        result.cube = look.opaque;
        result.glassLike = look.renderType == 0 && look.fullCube && !look.opaque && look.skipSame;
        result.surroundings = surroundings(world, block, x, y, z, look.opaque || look.fullCube);
        Cached cached = BY_PLACE.get(place(x, y, z));
        if (cached != null) {
            result.cached = "surroundings=" + Long.toHexString(cached.surroundings)
                + (cached.surroundings == result.surroundings ? " (same as now)" : " (CHANGED since)")
                + " ids="
                + Arrays.toString(cached.ids)
                + " taken "
                + (System.currentTimeMillis() - cached.time) / 1000
                + " s ago";
        }
        if (!available()) {
            return result;
        }
        for (int v = 0; v < VARIANTS.length; v++) {
            Pending pending = new Pending(
                -1,
                x,
                y,
                z,
                block,
                tileEntity,
                result.surroundings,
                look.opaque,
                ownRenderer);
            pending.lookKey = key;
            pending.why = "inspect";
            if (!look.opaque) {
                pending.wide = reachesFar(world, block, tileEntity, x, y, z);
                pending.overBig = drawnOverByBig(world, pending);
            }
            prepareCovered(world, java.util.Collections.singletonList(pending), palette);
            // Made by the drawing itself for the usual way; the others are only compared.
            pending.shot = new BlockDiag.Shot();
            pending.shot.images = new int[pending.views()][];
            variant = v;
            inspecting = true;
            try {
                draw(world, java.util.Collections.singletonList(pending), palette);
            } finally {
                variant = 0;
                inspecting = false;
            }
            result.shots[v] = pending.shot;
            if (v == 0) {
                result.ids = pending.ids.clone();
                result.covered = pending.covered;
                result.wide = pending.wide;
                result.overBig = pending.overBig;
            }
        }
        for (TileEntity big : bigTileEntities(world)) {
            Pending probe = new Pending(-1, x, y, z, block, tileEntity, 0, false, false);
            if (big != tileEntity && drawsOver(big, probe)) {
                result.drawnOver.add(
                    big.getClass()
                        .getName() + " at "
                        + big.xCoord
                        + ","
                        + big.yCoord
                        + ","
                        + big.zCoord
                        + " renderBox="
                        + big.getRenderBoundingBox());
            }
        }
        return result;
    }

    /** Sides of a block hidden by the blocks next to it, as its sprites leave them out (bits by {@link #OFFSETS}). */
    static int coveredSides(World world, int x, int y, int z) {
        Pending probe = new Pending(-1, x, y, z, null, null, 0, false, false);
        return covered(world, probe);
    }

    /** Whether sprites can be taken (off-screen buffers are available and nothing went wrong). */
    /** Whether it was said that pictures can't be taken (said again once they could be in between). */
    private static boolean offLogged;

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
        surroundingsShared, batches, slotsUsed, tileEntities, kindHit, kindConflicts;
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
    static final String[] VARIANTS = { "usual", "noClip", "cullBackFaces", "noItemLighting" };
    /** While {@link #inspect} takes pictures: they are kept whole for its report, not logged. */
    private static boolean inspecting;
    private static boolean cullLogged;
    static int sessionReused;
    /** For the log: blocks of the chunk's list of pictures to take done so far, and in all. */
    static int progressDone, progressTotal;
    static int blocksLooked, expiredSame, expiredDiffer, sameAsTwin, differFromTwin, sameAsTwinWithData,
        differFromTwinWithData, onlyBottomOpen, allEmpty, allEmptyOnlyBottom;
    static long exposedNanos, tileEntityNanos, surroundingsNanos, unshadeNanos, idNanos;
    /** Time remembering the pictures taken for their kinds ({@link #learn}). */
    static long learnNanos;
    /**
     * Pictures of tile entities by block and surroundings (and with their data), to tell whether pictures could be
     * shared between places; for the log only.
     */
    private static final Map<Long, int[]> TWINS = new HashMap<>(), TWINS_WITH_DATA = new HashMap<>();

    private static void resetStats() {
        lastFound = lastToDraw = lastDrawn = lastMissing = 0;
        whyComplex = whyOwnRenderer = whyGlass = whySides = placeHit = placeExpired = placeChanged = 0;
        surroundingsHit = surroundingsShared = batches = slotsUsed = tileEntities = kindHit = kindConflicts = 0;
        findNanos = drawNanos = readNanos = storeNanos = setupNanos = 0;
        sessionReused = 0;
        progressDone = progressTotal = 0;
        blocksLooked = expiredSame = expiredDiffer = sameAsTwin = differFromTwin = sameAsTwinWithData = 0;
        differFromTwinWithData = onlyBottomOpen = allEmpty = allEmptyOnlyBottom = 0;
        exposedNanos = tileEntityNanos = surroundingsNanos = unshadeNanos = idNanos = learnNanos = 0;
    }

    /** Resource packs changed: sprites are taken again. */
    static void clear() {
        BY_SURROUNDINGS.clear();
        BY_KIND.clear();
        BY_BLOCK.clear();
        LOOKS_OF_SPRITES.clear();
        UNRELIABLE_LOGGED.clear();
        BY_PLACE.clear();
        SESSIONS.clear();
        TWINS.clear();
        TWINS_WITH_DATA.clear();
        dropFlights();
    }

    /** Forgets the batches being read back (their blocks get pictures another time); render thread. */
    static void dropFlights() {
        for (Flight flight : FLIGHTS) {
            FREE_PBOS.add(flight.pbo);
        }
        FLIGHTS.clear();
    }

    /** Batches being read back (for the log). */
    static int flights() {
        return FLIGHTS.size();
    }

    private static boolean asyncAvailable() {
        if (asyncWorks == null) {
            boolean works;
            try {
                works = !Boolean.getBoolean("wayfarmap.syncPictures") && GLContext.getCapabilities().OpenGL21;
            } catch (Throwable t) {
                works = false;
            }
            asyncWorks = works;
            IsoLog.log("PICTURES_READBACK " + (works ? "later (pixel buffer objects)" : "at once"));
        }
        return asyncWorks;
    }

    /** A free pixel buffer object for a batch, made if need be; -1 if none (then the batch is read at once). */
    private static int takePbo() {
        if (!asyncAvailable()) {
            return -1;
        }
        Integer free = FREE_PBOS.poll();
        if (free != null) {
            return free;
        }
        if (pboCount >= MAX_PBOS) {
            return -1;
        }
        int bound = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) SIZE * SIZE * 4, GL15.GL_STREAM_READ);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, bound);
        pboCount++;
        return pbo;
    }

    /** Pictures can't be read back later after all: from now on at once. */
    private static void asyncFailed(String why) {
        if (asyncWorks == null || asyncWorks) {
            WayFarMap.LOG.warn("The 3D map reads block pictures back at once from now on: {}", why);
            IsoLog.log("PICTURES_READBACK at once from now on: " + why);
        }
        asyncWorks = false;
    }

    /**
     * Reads out the batches of pictures drawn the tick before and gives them their ids, so the chunks waiting for
     * them can be finished (render thread, before new pictures are drawn). A batch that can't be read gets none: its
     * blocks are taken again.
     */
    static void finishFlights(FacePalette palette) {
        if (FLIGHTS.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        List<Flight> flights = new ArrayList<>(FLIGHTS);
        FLIGHTS.clear();
        for (Flight flight : flights) {
            boolean read = false;
            int bound = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flight.pbo);
                ByteBuffer mapped = GL15
                    .glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, (long) SIZE * SIZE * 4, null);
                if (mapped == null) {
                    asyncFailed("glMapBuffer gave nothing (error " + GL11.glGetError() + ")");
                } else {
                    if (readPixels == null) {
                        readPixels = new int[SIZE * SIZE];
                    }
                    mapped.order(ByteOrder.nativeOrder())
                        .asIntBuffer()
                        .get(readPixels, 0, flight.usedRows * SIZE);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    read = true;
                }
            } catch (Throwable t) {
                asyncFailed(String.valueOf(t));
            } finally {
                // As it was: another mod may read pixels into a buffer object of its own.
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, bound);
                FREE_PBOS.add(flight.pbo);
            }
            try {
                if (!read || palette == null || palette.generation != cacheGeneration) {
                    throw new IllegalStateException(read ? "the palette changed" : "not read back");
                }
                store(flight.batch, readPixels, flight.cell, flight.perRow, palette, flight.diagnose);
                if (IsoLog.on()) {
                    checkSkippable(flight.batch);
                }
                afterBatch(flight.batch, flight.waiting);
            } catch (Throwable t) {
                for (Pending pending : flight.batch) {
                    Arrays.fill(pending.ids, 0);
                }
                IsoLog.log("PICTURES_FAILED batch of " + flight.batch.size() + " read back later: " + t);
            }
        }
        finishNanos += System.nanoTime() - start;
    }

    /**
     * Finds the chunk's blocks that need sprites, takes them and stores their ids in {@code blocks}. Taking pictures
     * stops once the deadline is past (after at least one batch): the blocks left have none this time.
     *
     * @param deadline {@link System#nanoTime()} to stop at
     * @param async    pictures may be read back later: then false is returned with {@link #lastInFlight} set, and
     *                 the chunk is finished by calling this again with the same blocks after {@link #finishFlights}
     * @return false if some pictures weren't taken in time (or are still being read back)
     */
    static boolean addFaces(World world, Chunk chunk, ChunkBlocks blocks, FacePalette palette, long deadline,
        boolean async) {
        resetStats();
        lastInFlight = false;
        lastPaletteFull = palette.full();
        long findStart = System.nanoTime();
        if (!available()) {
            // The ones of the copy before are kept.
            blocks.picturesMissing = true;
            if (!offLogged) {
                offLogged = true;
                String why = broken ? "the game's renderers failed again and again (see PICTURES_FAILED)"
                    : "the game's framebuffers are off (video settings: FBO, options.txt fboEnable:true), or "
                        + "another mod turned them off";
                WayFarMap.LOG.warn("The 3D map takes no pictures of blocks, they are drawn from icons: {}", why);
                IsoLog.log("PICTURES_OFF " + why + ": blocks drawn from their icons, old pictures kept");
            }
            return true;
        }
        offLogged = false;
        if (cacheGeneration != palette.generation) {
            BY_SURROUNDINGS.clear();
            BY_KIND.clear();
            BY_BLOCK.clear();
            LOOKS_OF_SPRITES.clear();
            UNRELIABLE_LOGGED.clear();
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
            // Wide sprites take four slots each: they go in batches of their own.
            boolean wide = toDraw.get(from).wide;
            int capacity = wide ? SLOTS / 4 : SLOTS;
            int to = from, slots = 0;
            while (to < toDraw.size() && toDraw.get(to).wide == wide
                && slots + toDraw.get(to)
                    .views() <= capacity) {
                slots += toDraw.get(to++)
                    .views();
            }
            List<Pending> batch = toDraw.subList(from, to);
            from = to;
            prepareCovered(world, batch, palette);
            Flight flight = drawBatch(world, batch, palette, async);
            if (IsoLog.on()) {
                tryVariants(world, batch, palette);
            }
            lastDrawn += batch.size();
            batches++;
            slotsUsed += slots;
            if (flight != null) {
                // Read back and stored next tick.
                flight.waiting = waiting;
                FLIGHTS.add(flight);
                lastInFlight = true;
                continue;
            }
            if (IsoLog.on()) {
                checkSkippable(batch);
            }
            afterBatch(batch, waiting);
        }
        session.from = from;
        progressDone = from;
        progressTotal = toDraw.size();
        if (lastInFlight && !broken) {
            // The session stays: called again once they are read, it goes on from here.
            return false;
        }
        lastInFlight = false;
        if (complete || broken) {
            SESSIONS.remove(chunkKey);
        }
        return finish(found, blocks, palette, complete);
    }

    /** The pictures of a batch, taken: kept in the caches, and given to the blocks waiting for the same. */
    private static void afterBatch(List<Pending> batch, Map<Long, List<Pending>> waiting) {
        {
            for (Pending pending : batch) {
                if (missing(pending)) {
                    lastMissing++;
                    // Not taken (no room for new pictures now): taken again next time, not remembered as none.
                    continue;
                }
                if (pending.byPlace()) {
                    // Kept until its surroundings change (or the player asks for new pictures): taking them again
                    // after a while gave the same pictures five times out of six, and kept big bases from ever
                    // being finished.
                    BY_PLACE.put(
                        place(pending.x, pending.y, pending.z),
                        new Cached(pending.surroundings, pending.ids.clone(), System.currentTimeMillis()));
                } else {
                    long learnStart = System.nanoTime();
                    learn(pending);
                    learnNanos += System.nanoTime() - learnStart;
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
    }

    /** Puts the pictures found into the chunk's copy. */
    private static boolean finish(List<Pending> found, ChunkBlocks blocks, FacePalette palette, boolean complete) {
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
            if (!look.opaque) {
                pending.wide = reachesFar(world, block, tileEntity, x, y, z);
                pending.overBig = drawnOverByBig(world, pending);
            }
            found.add(pending);
            if (pending.byPlace()) {
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
                if (learnable(pending)) {
                    int tint = tint(world, block, x, y, z);
                    pending.kindKey = kindKey(world, key, exposed, pending.wide, tint, x, y, z);
                    pending.detached = detached(world, block, x, y, z);
                    pending.blockKey = blockKey(key, exposed, pending.wide, pending.detached, tint);
                    Learned learned = BY_KIND.get(pending.kindKey);
                    int[] ids = learned == null ? null : learned.reuse(LEARN_CONFIRMATIONS, 0);
                    if (learned == null || learned.unreliable || learned.confirmed < LEARN_CONFIRMATIONS) {
                        // Not learned for this kind of place (else this one is drawn to check): by block alone.
                        Learned byBlock = BY_BLOCK.get(pending.blockKey);
                        if (byBlock != null && (pending.detached || byBlock.allEmpty())) {
                            ids = byBlock.reuse(BLOCK_CONFIRMATIONS, BLOCK_PLACES);
                            if (ids != null) {
                                kindStat(key, 6);
                            }
                        }
                    }
                    if (ids != null) {
                        // Its kind looks the same wherever it is: its pictures without drawing it.
                        System.arraycopy(ids, 0, pending.ids, 0, pending.ids.length);
                        kindHit++;
                        kindStat(key, 0);
                        continue;
                    }
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
        drawBatch(world, batch, palette, false);
    }

    /**
     * Draws the batch's pictures and reads them back: at once, or, if {@code async}, into a pixel buffer object read
     * out the next tick ({@link #finishFlights}). Returns the batch then, null if it was read (or failed) now.
     */
    private static Flight drawBatch(World world, List<Pending> batch, FacePalette palette, boolean async) {
        Flight flight = null;
        int pbo = -1;
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
            // No light map: every block fully lit, the tracer adds the light of the place. Renderers turning it on
            // themselves (SGCraft's) get a white one: the game's own is tinted by the time of day (blue at night).
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTexture());
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // Smooth lighting would darken corners by the light around; the sprites are taken unlit.
            mc.gameSettings.ambientOcclusion = 0;
            RenderBlocks renderBlocks = new RenderBlocks(world);
            long drawStart = System.nanoTime();
            setupNanos += drawStart - setupStart;

            boolean diagnose = IsoLog.on() || inspecting;
            // A batch is all wide sprites or none (see addFaces).
            int cell = !batch.isEmpty() && batch.get(0).wide ? 2 * SLOT : SLOT, perRow = SIZE / cell;
            int slot = 0;
            for (Pending pending : batch) {
                if (variant == 0) {
                    pending.shot = diagnose ? new BlockDiag.Shot() : null;
                    if (pending.shot != null && (inspecting || BlockDiag.wantsImages(pending.lookKey))) {
                        pending.shot.images = new int[pending.views()][];
                    }
                }
                long blockStart = System.nanoTime();
                for (int view = 0; view < pending.views(); view++, slot++) {
                    int pixels = pending.pixels();
                    GL11.glViewport((slot % perRow) * cell, (slot / perRow) * cell, pixels, pixels);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    if (pending.cube) {
                        // Straight at one side: the picture covers exactly that side, like its texture.
                        GL11.glOrtho(-0.5, 0.5, -0.5, 0.5, -0.01, 1.01);
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        loadSideView(view);
                    } else {
                        // Two blocks of the projection plane around the block's center (four if wide), like the
                        // tracer reads it.
                        double extent = pending.wide ? 2 : 1;
                        GL11.glOrtho(-extent, extent, -extent, extent, -2 * extent, 2 * extent);
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
                    drawnPixelsWhole(!BlockLooks.get(pending.lookKey).translucent);
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
            int usedRows = Math.min(SIZE, (slotsUsed(batch) + perRow - 1) / perRow * cell);
            if (async && variant == 0 && !inspecting) {
                pbo = takePbo();
            }
            if (pbo >= 0) {
                boolean first = pboChecked;
                if (!first) {
                    // Errors before ours aren't ours.
                    for (int n = 0; n < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; n++) {}
                }
                int packBound = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
                try {
                    // Into the buffer object: returns at once, the graphics card copies them when it gets there.
                    GL11.glReadPixels(0, 0, SIZE, usedRows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
                } finally {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBound);
                }
                if (!first) {
                    pboChecked = true;
                    int error = GL11.glGetError();
                    if (error != GL11.GL_NO_ERROR) {
                        asyncFailed("reading into a pixel buffer object gave error " + error);
                        FREE_PBOS.add(pbo);
                        pbo = -1;
                    }
                }
            }
            if (pbo >= 0) {
                readNanos += System.nanoTime() - readStart;
                flight = new Flight(batch, pbo, usedRows, cell, perRow, diagnose);
            } else {
                readBuffer.clear();
                GL11.glReadPixels(0, 0, SIZE, usedRows, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
                if (readPixels == null) {
                    readPixels = new int[SIZE * SIZE];
                }
                readBuffer.get(readPixels, 0, usedRows * SIZE);
                readNanos += System.nanoTime() - readStart;
                store(batch, readPixels, cell, perRow, palette, diagnose);
            }
            failures = 0;
        } catch (Throwable t) {
            if (pbo >= 0 && flight == null) {
                FREE_PBOS.add(pbo);
            }
            flight = null;
            // These blocks are drawn from their icons this time.
            for (Pending pending : batch) {
                Arrays.fill(pending.ids, 0);
            }
            IsoLog.log("PICTURES_FAILED batch of " + batch.size() + ": " + t);
            if (isClipFailure(t)) {
                // Taken again without clip planes next time.
            } else if (++failures >= 5) {
                // Something in this driver or the mods' renderers does not like this: no more pictures.
                broken = true;
                WayFarMap.LOG.warn("The 3D map can't take pictures of blocks; it uses their icons instead", t);
            } else {
                WayFarMap.LOG.debug("Could not take pictures of blocks for the 3D map", t);
            }
        } finally {
            // Off whatever happened: a clip plane left on clips (or, on OpenGL ES, crashes) the game's own drawing.
            disableClipPlanes();
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
        return flight;
    }

    /** Whether reading into a pixel buffer object was checked for errors once. */
    private static boolean pboChecked;

    /**
     * Takes the batch's pictures out of the pixels read back ({@code all}, rows bottom-up) and gives them their ids.
     * The work on each picture (copying it out, taking the side's shade out, its fingerprints) is shared by the
     * picture hash threads; the shade is worked out first here (it reads the blocks' looks, render thread only).
     */
    private static void store(List<Pending> batch, int[] all, int cell, int perRow, FacePalette palette,
        boolean diagnose) throws Exception {
        long storeStart = System.nanoTime();
        int slots = slotsUsed(batch);
        Pending[] owners = new Pending[slots];
        int[] views = new int[slots];
        int[][] images = new int[slots][];
        int[][] tables = new int[slots][];
        int slot = 0;
        for (Pending pending : batch) {
            int pixels = pending.pixels();
            for (int view = 0; view < pending.views(); view++, slot++) {
                owners[slot] = pending;
                views[slot] = view;
                images[slot] = slotImage(slot, pixels * pixels);
                int sx = (slot % perRow) * cell, sy = (slot / perRow) * cell;
                // A side seen straight on is shaded by the game for that side; the tracer shades it itself.
                float shade = pending.cube && !pending.ownRenderer ? sideShade(all, sx, sy, pixels, pending, view) : 1f;
                if (pending.shot != null) {
                    pending.shot.shade[view] = shade;
                }
                // Without shading (all pictures but cubes' sides) the rows are only copied; with it, through a table
                // for the shade instead of dividing each color of each pixel.
                tables[slot] = shade >= 1f ? null : shadeTable(shade);
            }
        }
        // (Another variant's pictures are only compared in the log: they get no ids.)
        boolean identify = variant == 0;
        long[][] hashes = new long[slots][];
        float[][] looks = new float[slots][];
        long u0 = System.nanoTime();
        forEachSlot(slots, n -> {
            Pending pending = owners[n];
            int pixels = pending.pixels(), view = views[n];
            int[] image = images[n], table = tables[n];
            int sx = (n % perRow) * cell, sy = (n / perRow) * cell;
            for (int row = 0; row < pixels; row++) {
                // Read back bottom-up; pictures are top-down.
                int from = (sy + pixels - 1 - row) * SIZE + sx;
                if (table == null) {
                    System.arraycopy(all, from, image, row * pixels, pixels);
                    continue;
                }
                for (int column = 0; column < pixels; column++) {
                    image[row * pixels + column] = unshade(all[from + column], table);
                }
            }
            if (pending.shot != null) {
                BlockDiag.measure(image, pending.shot, view);
            }
            if (identify) {
                hashes[n] = FacePalette.hashes(image);
                looks[n] = pending.detached ? look(image) : null;
            }
        });
        unshadeNanos += System.nanoTime() - u0;
        if (identify) {
            long idStart = System.nanoTime();
            for (int n = 0; n < slots; n++) {
                int id = palette.idOf(images[n], hashes[n]);
                owners[n].ids[views[n]] = id;
                if (looks[n] != null && id > 0 && !LOOKS_OF_SPRITES.containsKey(id)) {
                    if (LOOKS_OF_SPRITES.size() > 200_000) {
                        LOOKS_OF_SPRITES.clear();
                    }
                    LOOKS_OF_SPRITES.put(id, looks[n]);
                }
            }
            idNanos += System.nanoTime() - idStart;
        }
        storeNanos += System.nanoTime() - storeStart;
        if (diagnose && variant == 0 && !inspecting) {
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
    }

    /** Runs the work for each slot, shared by the picture hash threads when there are enough. */
    private static void forEachSlot(int slots, java.util.function.IntConsumer work) throws Exception {
        if (slots < PARALLEL_FROM) {
            for (int n = 0; n < slots; n++) {
                work.accept(n);
            }
            return;
        }
        int parts = Math.min(
            slots,
            4 * Math.max(
                1,
                Math.min(
                    6,
                    Runtime.getRuntime()
                        .availableProcessors() / 4)));
        List<Callable<Void>> tasks = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            int from = slots * part / parts, to = slots * (part + 1) / parts;
            tasks.add(() -> {
                for (int n = from; n < to; n++) {
                    work.accept(n);
                }
                return null;
            });
        }
        for (Future<Void> done : HASHERS.invokeAll(tasks)) {
            // Throws what a task threw.
            done.get();
        }
    }

    /** Pictures of each slot of the buffer, kept from batch to batch (one array per slot and size). */
    private static int[][] slotImages = new int[SLOTS][];

    private static int[] slotImage(int slot, int length) {
        int[] image = slotImages[slot];
        if (image == null || image.length != length) {
            image = slotImages[slot] = new int[length];
        }
        return image;
    }

    /**
     * Threads working out the pictures' fingerprints, all of a batch at once: one after the other they took more
     * than any other part of taking pictures (64 pictures of 128x128 pixels a batch, most of them new).
     */
    private static final ExecutorService HASHERS = Executors.newFixedThreadPool(
        Math.max(
            1,
            Math.min(
                6,
                Runtime.getRuntime()
                    .availableProcessors() / 4)),
        task -> {
            Thread thread = new Thread(task, "WayFarMap 3D picture hashes");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    /** Fewer pictures than this are worked out on the render thread: handing them over would cost more. */
    private static final int PARALLEL_FROM = 8;

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

    private static int whiteTexture = -1;

    /** A texture of one white pixel (render thread). */
    private static int whiteTexture() {
        if (whiteTexture < 0) {
            whiteTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTexture);
            java.nio.ByteBuffer white = BufferUtils.createByteBuffer(4);
            white.put((byte) -1)
                .put((byte) -1)
                .put((byte) -1)
                .put((byte) -1)
                .flip();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white);
        }
        return whiteTexture;
    }

    /**
     * For blocks that aren't see-through: every pixel drawn is stored fully drawn, its color as drawn. The game draws
     * them without blending, so a texel of half alpha (over the alpha test's 0.1) shows whole on screen; stored with
     * its alpha it was a hole on the map (a gap in the rim of SGCraft's DHD). See-through ones keep their alpha.
     * Renderers that blend set their own blending, which then keeps theirs.
     */
    /** OpenGL 1.4's blend factor of the constant alpha (LWJGL 2 keeps it in ARB_imaging only). */
    private static final int GL_CONSTANT_ALPHA = 0x8003;

    private static void drawnPixelsWhole(boolean whole) {
        if (whole && GLContext.getCapabilities().OpenGL14) {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(0f, 0f, 0f, 1f);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL_CONSTANT_ALPHA, GL11.GL_ZERO);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
    }

    /**
     * Keeps only what is within the block's column, and the margin around it (with the camera of the view set). Its
     * sides hidden by a solid block next to it are cut off: the game draws them (the top of a Nuclear Control panel
     * under another), the world hides them, but in the sprite they showed, and where the sprite's pixels of such a
     * side and a seen one meet on their edge, the hidden one drew lines along the seams of a wall.
     */
    private static void clipColumn(Pending pending, double margin, boolean hideCovered) {
        clipColumn(pending, margin, hideCovered, 0);
    }

    /**
     * As {@link #clipColumn(Pending, double, boolean)}, with only the small {@link #CLIP_MARGIN} on the sides in
     * {@code tight} (bits by side, like {@code covered}).
     */
    private static void clipColumn(Pending pending, double margin, boolean hideCovered, int tight) {
        if (!clipPlanesWork()) {
            return;
        }
        int covered = hideCovered ? pending.covered : 0;
        clip(0, 1, 0, 0, -(pending.x - side(covered, 4, margin(tight, 4, margin))));
        clip(1, -1, 0, 0, pending.x + 1 + side(covered, 5, margin(tight, 5, margin)));
        clip(2, 0, 0, 1, -(pending.z - side(covered, 2, margin(tight, 2, margin))));
        clip(3, 0, 0, -1, pending.z + 1 + side(covered, 3, margin(tight, 3, margin)));
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

    private static double margin(int tight, int side, double margin) {
        return (tight & 1 << side) != 0 ? Math.min(margin, CLIP_MARGIN) : margin;
    }

    /**
     * Sides (bits as in {@code covered}) with a tile entity drawing a model next to the block: a double chest's other
     * half, a fridge beside another. That place takes its own picture of what is drawn there (the other half of the
     * chest comes from this one's model), so this block's own model is cut right at its outline on those sides. With
     * the wider margin of its own model, a quarter of the chest's other half was in this block's picture too and was
     * drawn over the other half: dark seams, latches twice and dark bands along rows of chests.
     */
    private static int modelNeighbours(Pending pending) {
        World world = pending.tileEntity != null ? pending.tileEntity.getWorldObj() : null;
        if (world == null) {
            return 0;
        }
        int sides = 0;
        for (int side = 2; side < 6; side++) {
            try {
                TileEntity next = world.getTileEntity(
                    pending.x + OFFSETS[side][0],
                    pending.y + OFFSETS[side][1],
                    pending.z + OFFSETS[side][2]);
                if (next != null && TileEntityRendererDispatcher.instance.hasSpecialRenderer(next)) {
                    sides |= 1 << side;
                }
            } catch (RuntimeException ignored) {}
        }
        return sides;
    }

    /** How far outside the block its column is kept on a side: the margin, or inside it if the side is hidden. */
    private static double side(int covered, int side, double margin) {
        return (covered & 1 << side) != 0 ? -COVERED_INSET : margin;
    }

    /**
     * Per kind of block (with its tile entity's class): whether its sprite fills its block's whole outline, so it
     * hides the side of a block next to it. Learned from a picture of it (see {@link #prepareCovered}): the size the
     * game says a block has is no help (a hopper, a GregTech pipe, a Thaumcraft cap all say a whole block).
     */
    private static final Map<Long, Boolean> WHOLE_CUBE = new HashMap<>();
    /** The way pictures of neighbours are taken to learn {@link #WHOLE_CUBE}: as usual, not stored. */
    private static final int PROBE = VARIANTS.length;

    private static long wholeKey(int key, TileEntity tileEntity) {
        return (long) key << 32 | (tileEntity == null ? 0
            : tileEntity.getClass()
                .getName()
                .hashCode() & 0xFFFFFFFFL);
    }

    /**
     * Whether a block next to another may hide that one's side: a block filling its cell, drawn without holes on the
     * side toward it (not glass or frames); whether it does is learned from its picture.
     */
    private static boolean mayHide(BlockLooks.Look look, int side) {
        if (!look.fullCube || look.translucent) {
            return false;
        }
        // Its side toward this block: down <-> up, north <-> south, west <-> east.
        BlockLooks.Texture texture = look.textures[side ^ 1];
        return texture != null && texture.solid();
    }

    /**
     * The sides of a block hidden by the blocks next to them: a solid cube, or a block whose sprite fills its whole
     * outline (the next block of a Nuclear Control panel, a machine next to a machine). Neighbours not known yet hide
     * nothing.
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
            if (!hides && mayHide(look, side)) {
                Boolean whole = WHOLE_CUBE.get(wholeKey(key, world.getTileEntity(nx, ny, nz)));
                hides = whole != null && whole;
            }
            if (hides) {
                covered |= 1 << side;
            }
        }
        return covered;
    }

    /**
     * Sets the sides of the blocks of a batch hidden by their neighbours, first taking a picture of each kind of
     * neighbour that may hide one and isn't known yet (render thread, before the batch is drawn).
     */
    private static void prepareCovered(World world, List<Pending> batch, FacePalette palette) {
        Map<Long, Pending> probes = new LinkedHashMap<>();
        for (Pending pending : batch) {
            if (pending.cube) {
                continue;
            }
            for (int side = 0; side < 6; side++) {
                int nx = pending.x + OFFSETS[side][0], ny = pending.y + OFFSETS[side][1],
                    nz = pending.z + OFFSETS[side][2];
                int key = blockAt(world, nx, ny, nz);
                if (ChunkBlocks.blockId(key) == 0) {
                    continue;
                }
                BlockLooks.Look look = BlockLooks.get(key);
                if (look.opaque || !mayHide(look, side)) {
                    continue;
                }
                TileEntity tileEntity = world.getTileEntity(nx, ny, nz);
                long whole = wholeKey(key, tileEntity);
                if (WHOLE_CUBE.containsKey(whole) || probes.containsKey(whole)) {
                    continue;
                }
                boolean ownRenderer;
                try {
                    ownRenderer = tileEntity != null
                        && TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity);
                } catch (RuntimeException e) {
                    ownRenderer = false;
                }
                Pending probe = new Pending(
                    -1,
                    nx,
                    ny,
                    nz,
                    world.getBlock(nx, ny, nz),
                    tileEntity,
                    0,
                    false,
                    ownRenderer);
                probe.lookKey = key;
                probe.why = "probe";
                probes.put(whole, probe);
            }
        }
        if (!probes.isEmpty() && available()) {
            List<Long> keys = new ArrayList<>(probes.keySet());
            List<Pending> list = new ArrayList<>(probes.values());
            int per = SLOTS / ChunkBlocks.VIEWS;
            for (int from = 0; from < list.size(); from += per) {
                List<Pending> part = list.subList(from, Math.min(list.size(), from + per));
                for (Pending probe : part) {
                    probe.shot = new BlockDiag.Shot();
                    probe.shot.images = new int[probe.views()][];
                }
                variant = PROBE;
                try {
                    draw(world, part, palette);
                } finally {
                    variant = 0;
                }
                for (int i = 0; i < part.size(); i++) {
                    int[][] images = part.get(i).shot.images;
                    boolean whole = images[0] != null && fillsOutline(images[0], FacePalette.SPRITE_SIZE);
                    WHOLE_CUBE.put(keys.get(from + i), whole);
                }
            }
        }
        for (Pending pending : batch) {
            pending.covered = pending.cube ? 0 : covered(world, pending);
        }
    }

    /** Whether a sprite from the first view side is drawn solid all over its block's outline (but its very edge). */
    private static boolean fillsOutline(int[] image, int size) {
        double[] outline = IsoTracer.outline(IsoProjection.of(0));
        // Three pixels in from the edge: the edge's pixels are drawn or not by where it crosses them.
        double margin = -3.0 * 2 / size;
        for (int py = 0; py < size; py++) {
            double v = (py + 0.5) / size * 2 - 1;
            for (int px = 0; px < size; px++) {
                double u = (px + 0.5) / size * 2 - 1;
                if (IsoTracer.within(outline, u, v, margin) && (image[py * size + px] >>> 24) < 128) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a block's model reaches above or below its block by more than a little (its bounds, or the box its tile
     * entity says it draws in): a banner two blocks tall, an obelisk. Such blocks get wide sprites.
     */
    private static boolean reachesFar(World world, Block block, TileEntity tileEntity, int x, int y, int z) {
        double m = OWN_MODEL_MARGIN;
        try {
            block.setBlockBoundsBasedOnState(world, x, y, z);
            if (block.getBlockBoundsMinY() < -m || block.getBlockBoundsMaxY() > 1 + m) {
                return true;
            }
        } catch (RuntimeException ignored) {}
        if (tileEntity == null || tileEntity instanceof TileEntityChest || tileEntity instanceof TileEntityEnderChest) {
            return false;
        }
        try {
            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            return box != null && box != TileEntity.INFINITE_EXTENT_AABB
                && box.maxY - box.minY <= BIG_MAX_SIZE
                && (box.minY < y - m || box.maxY > y + 1 + m);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a tile entity farther away draws over a block's place (see {@link #bigTileEntities}). */
    private static boolean drawnOverByBig(World world, Pending pending) {
        for (TileEntity big : bigTileEntities(world)) {
            if (big != pending.tileEntity && drawsOver(big, pending)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps what is on the positive side of a plane: a * x + b * y + c * z + d >= 0 (world coordinates). */
    private static void clip(int plane, double a, double b, double c, double d) {
        if (!clipPlanesWork()) {
            return;
        }
        planeBuffer.clear();
        planeBuffer.put(a)
            .put(b)
            .put(c)
            .put(d);
        planeBuffer.flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + plane, planeBuffer);
        GL11.glEnable(GL11.GL_CLIP_PLANE0 + plane);
    }

    private static void disableClipPlanes() {
        for (int plane = 0; plane < 6; plane++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
        }
    }

    /** Desktop OpenGL always has clip planes; OpenGL ES (Android) only with GL_EXT_clip_cull_distance. */
    private static boolean clipPlanesWork() {
        if (clipPlanesWork == null) {
            boolean works = true;
            try {
                String version = GL11.glGetString(GL11.GL_VERSION);
                boolean android = System.getProperty("os.version", "")
                    .contains("Android")
                    || System.getProperty("java.vendor", "")
                        .contains("Android");
                if (android || version != null && version.contains("OpenGL ES")) {
                    works = hasExtension("GL_EXT_clip_cull_distance") || hasExtension("GL_APPLE_clip_distance");
                }
            } catch (Throwable t) {
                works = false;
            }
            clipPlanesWork = works;
            if (!works) {
                WayFarMap.LOG.info("No clip planes on this OpenGL: the 3D map takes pictures of blocks without them");
            }
        }
        return clipPlanesWork;
    }

    private static boolean hasExtension(String name) {
        String all = GL11.glGetString(GL11.GL_EXTENSIONS);
        if (all != null) {
            return all.contains(name);
        }
        int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
        for (int i = 0; i < count; i++) {
            if (name.equals(GL30.glGetStringi(GL11.GL_EXTENSIONS, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A drawing failed because clip planes aren't supported (Angelica's shader without gl_ClipDistance): they are
     * no longer used. True if so, and the error is passed on so the batch isn't stored with empty pictures.
     */
    private static boolean isClipFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && (message.contains("gl_ClipDistance") || message.contains("clip_cull_distance"))) {
                if (!Boolean.FALSE.equals(clipPlanesWork)) {
                    clipPlanesWork = false;
                    WayFarMap.LOG.warn(
                        "Clip planes don't work on this OpenGL: the 3D map takes pictures of blocks without them",
                        error);
                }
                disableClipPlanes();
                return true;
            }
        }
        return false;
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
                    if (isClipFailure(e)) {
                        throw e;
                    }
                }
            }
            RenderPass.setWorld(worldPass);
            if (cull && variant != 2) {
                // Tile entity renderers as before (their models may wind either way).
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (pending.byPlace()) {
                for (int pass = 0; pass < 2; pass++) {
                    RenderPass.setEntity(pass);
                    drawTileEntities(pending, pass);
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
        World world = pending.tileEntity != null ? pending.tileEntity.getWorldObj() : Minecraft.getMinecraft().theWorld;
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
                    if (n < 0) {
                        clipColumn(pending, OWN_MODEL_MARGIN, false, modelNeighbours(pending));
                    } else {
                        clipColumn(pending, CLIP_MARGIN, false);
                    }
                    if (n >= 4) {
                        // Of a model drawn from farther, only what is in this block's place: the blocks above and
                        // below take their own part.
                        clip(4, 0, 1, 0, -(pending.y - CLIP_MARGIN));
                        clip(5, 0, -1, 0, pending.y + 1 + CLIP_MARGIN);
                    }
                }
                // Lit as the game lights tile entities in the world (it turns on the item lights before them):
                // renderers lighting their models themselves (SGCraft's) came out nearly black without. The light
                // map at full: the tracer adds the light of the place.
                if (variant != 3) {
                    RenderHelper.enableStandardItemLighting();
                }
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
                TileEntityRendererDispatcher.instance
                    .renderTileEntityAt(tileEntity, tileEntity.xCoord, tileEntity.yCoord, tileEntity.zCoord, 0f);
                if (pending.shot != null) {
                    pending.shot.tileEntitiesDrawn++;
                }
            } catch (RuntimeException e) {
                if (isClipFailure(e)) {
                    throw e;
                }
                // A renderer that needs more than this; the block's own drawing stays.
                if (pending.shot != null && pending.shot.error == null) {
                    pending.shot.error = "tile entity renderer " + tileEntity.getClass()
                        .getSimpleName() + ", pass " + pass + ": " + BlockDiag.error(e);
                }
            }
            RenderHelper.disableStandardItemLighting();
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
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

    /**
     * Takes the game's side shading out of a pixel, so the tracer can shade it by the light of its place: each color
     * through the table for its shade ({@link #shadeTable}).
     */
    private static int unshade(int argb, int[] table) {
        if ((argb >>> 24) == 0) {
            return argb;
        }
        return argb & 0xFF000000 | table[(argb >> 16) & 0xFF] << 16
            | table[(argb >> 8) & 0xFF] << 8
            | table[argb & 0xFF];
    }

    /** Tables for {@link #unshade}, by the shade's bits: a side's few shades come back again and again. */
    private static final Map<Integer, int[]> SHADE_TABLES = new HashMap<>();

    /** Each color (0-255) with the shade taken out, as dividing it would give. */
    private static int[] shadeTable(float shade) {
        int key = Float.floatToIntBits(shade);
        int[] table = SHADE_TABLES.get(key);
        if (table == null) {
            if (SHADE_TABLES.size() > 256) {
                SHADE_TABLES.clear();
            }
            table = new int[256];
            for (int color = 0; color < 256; color++) {
                table[color] = Math.min(255, (int) (color / shade + 0.5f));
            }
            SHADE_TABLES.put(key, table);
        }
        return table;
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
