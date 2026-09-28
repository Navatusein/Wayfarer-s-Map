package WayFarMap.client.map.iso;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.map.ChunkScanner;

/**
 * The 3D (isometric) world map, drawn like Dynmap's HD maps from the blocks themselves: while playing, the blocks
 * of every explored chunk are kept ({@link BlockStore}); on the world map, tiles are drawn from them by ray tracing
 * ({@link IsoTracer}, {@link IsoTiles}). Chunks without blocks (explored before, or by a teammate) are shown from the
 * flat map's colors and heights.
 */
public final class IsoMap implements BlockStore.Listener {

    public static final IsoMap INSTANCE = new IsoMap();

    /** Changes when tiles would look different; old saved tiles are then not used. */
    private static final int RENDER_VERSION = 14;
    /** Changes when sprites would look different; the old ones are then taken again. */
    private static final int SPRITE_VERSION = 7;
    /** Time per game tick spent copying chunks (a chunk takes a fraction of a millisecond, more with pictures). */
    private static final long CAPTURE_BUDGET_NANOS = 3_000_000L;
    /**
     * A chunk is copied again at most this often while the player is near it (building changes it), and farther
     * away only now and then: copying every loaded chunk again and again costs frames while flying around.
     */
    private static final long RECAPTURE_MS = 10_000, FAR_RECAPTURE_MS = 60_000, CHANGED_RECAPTURE_MS = 3_000;
    /** Chunks from the player that count as near. */
    private static final int NEAR_CHUNKS = 2;
    /** Time for pictures of a chunk copied as it is let go. */
    private static final long UNLOAD_PICTURES_NANOS = 1_000_000L;
    /** Time per frame the render thread spends working out block looks for the renderers. */
    private static final long LOOK_BUDGET_NANOS = 6_000_000L;

    /** The maps of one dimension. */
    static final class Dimension {

        final int id;
        final File directory;
        final BlockStore store;
        final SurfaceFallback fallback;

        Dimension(int id, File directory, BlockStore.Listener listener) {
            this.id = id;
            this.directory = directory;
            this.store = new BlockStore(id, directory, listener);
            this.fallback = new SurfaceFallback(directory);
        }
    }

    private File worldDirectory;
    private final Map<Integer, Dimension> dimensions = new HashMap<>();
    /** Copies chunks and saves the block files, one after the other. */
    private ExecutorService writer;
    private IsoTiles tiles;
    private String cacheId;
    /** Chunk changes from the writer, for the tiles on screen: {dimension, chunk x, chunk z, top, time}. */
    private final Queue<long[]> changes = new ConcurrentLinkedQueue<>();
    private final Map<Long, Long> lastCapture = new HashMap<>();
    private int lastCaptureDimension = Integer.MIN_VALUE;
    /**
     * Chunks the surface map scanned that are due to be copied, oldest first. All of them get copied over the next
     * ticks, so the whole loaded area gets its blocks, not just what is near the player.
     */
    private final LinkedHashSet<Long> captureQueue = new LinkedHashSet<>();
    /**
     * Chunks not copied yet this session, copied before the others: flying over new land, its chunks come first,
     * not after the chunks near the player that are copied again every few seconds.
     */
    private final LinkedHashSet<Long> freshQueue = new LinkedHashSet<>();
    /** Pictures of block sides taken from the game (connected textures, tile entities). */
    private FacePalette palette;
    /** The eight chunks around one, in an order where {@code AROUND[7 - i]} is the opposite of {@code AROUND[i]}. */
    private static final int[][] AROUND = { { -1, -1 }, { 0, -1 }, { 1, -1 }, { -1, 0 }, { 1, 0 }, { -1, 1 }, { 0, 1 },
        { 1, 1 } };
    /**
     * Chunks copied while some of the chunks around them weren't loaded (bits by {@link #AROUND}): their edge toward
     * those was drawn as if the world ended there (connected textures, pipes, glass). When one of those arrives, the
     * chunk is copied again.
     */
    private final Map<Long, Integer> partial = new HashMap<>();
    /** Chunks copied since the game loaded them (last time); let go, they arrive anew when they come back. */
    private final Set<Long> copiedWhileLoaded = new HashSet<>();

    private IsoMap() {}

    /** A world was joined: its maps live in {@code worldDirectory/dim<id>/}. */
    public void open(File worldDirectory) {
        close();
        // Block ids belong to the world (Forge numbers blocks per world and server), and pictures to its palette:
        // nothing worked out for another world is used here.
        BlockLooks.clear();
        FaceRenderer.clear();
        this.worldDirectory = worldDirectory;
        palette = FacePalette.load(worldDirectory, spriteCacheId());
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "WayFarMap 3D writer");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });
    }

    /** The world was left: saves the blocks, waits for it, and frees everything (render thread). */
    public void close() {
        if (tiles != null) {
            tiles.shutdown();
            tiles = null;
        }
        if (writer != null) {
            writer.submit(saveTask());
            writer.shutdown();
            try {
                if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                    WayFarMap.LOG.warn("Saving the 3D map took too long");
                }
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            writer = null;
        }
        dimensions.clear();
        changes.clear();
        lastCapture.clear();
        captureQueue.clear();
        freshQueue.clear();
        partial.clear();
        copiedWhileLoaded.clear();
        lastCaptureDimension = Integer.MIN_VALUE;
        worldDirectory = null;
        palette = null;
    }

    /** Saves the pictures, then the blocks that refer to them. */
    private Runnable saveTask() {
        List<Dimension> all = new ArrayList<>(dimensions.values());
        FacePalette pictures = palette;
        return () -> {
            if (pictures != null) {
                pictures.save();
            }
            all.forEach(d -> d.store.save());
        };
    }

    FacePalette palette() {
        return palette;
    }

    Dimension dimension(int id) {
        if (worldDirectory == null) {
            return null;
        }
        Dimension dimension = dimensions.get(id);
        if (dimension == null) {
            dimension = new Dimension(id, new File(worldDirectory, "dim" + id), this);
            dimensions.put(id, dimension);
        }
        return dimension;
    }

    // ---------------------------------------------------------------- recording blocks while playing

    /** Pictures not in the file yet, in bytes, that start saving them. */
    private static final long PICTURES_TO_SAVE = 48L << 20;
    private volatile boolean savingPictures;

    /** Many new pictures (flying over new land): written now, so they don't pile up in memory. */
    private void savePicturesIfMany() {
        FacePalette pictures = palette;
        if (pictures == null || savingPictures || pictures.unsavedBytes() < PICTURES_TO_SAVE) {
            return;
        }
        savingPictures = true;
        writer.submit(() -> {
            try {
                pictures.save();
            } finally {
                savingPictures = false;
            }
        });
    }

    /** Called once per client tick (render thread): copies the chunks that are due, for a few milliseconds. */
    public void tick(World world) {
        BlockLooks.pump(LOOK_BUDGET_NANOS / 3);
        drainChanges();
        if (world == null || writer == null || captureQueue.isEmpty() && freshQueue.isEmpty()) {
            return;
        }
        if (world.provider.dimensionId != lastCaptureDimension) {
            captureQueue.clear();
            freshQueue.clear();
            partial.clear();
            copiedWhileLoaded.clear();
            return;
        }
        savePicturesIfMany();
        long end = System.nanoTime() + CAPTURE_BUDGET_NANOS;
        captureFrom(world, freshQueue, end);
        captureFrom(world, captureQueue, end);
    }

    /**
     * Copies chunks from the queue until the time is up. The first one is taken anew each time: copying a chunk may
     * put chunks in the queues (one whose pictures weren't all taken in time goes back).
     */
    private void captureFrom(World world, LinkedHashSet<Long> queue, long end) {
        while (!queue.isEmpty() && System.nanoTime() < end) {
            Iterator<Long> it = queue.iterator();
            long key = it.next();
            it.remove();
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) {
                continue;
            }
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk != null && !chunk.isEmpty()) {
                if (!capture(world, chunk, false, false, end)) {
                    // Its other pictures are taken in the next ticks (the ones taken are kept).
                    captureQueue.add(key);
                }
            }
        }
    }

    /**
     * A chunk is let go by the game (render thread). If it stays at the edge of the explored map (a chunk next to it
     * has no blocks), it is copied whole, down to the bottom of the world: the edge of the 3D map then shows the real
     * ground. Only the chunks left at the edge are kept this way.
     */
    public void onChunkUnload(World world, Chunk chunk, boolean mayCopy) {
        if (!Config.record3d || writer == null
            || chunk.isEmpty()
            || world.provider.dimensionId != lastCaptureDimension) {
            return;
        }
        Dimension dimension = dimension(world.provider.dimensionId);
        if (dimension == null) {
            return;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        // Waiting to be copied (for the first time, or again since it changed).
        boolean waiting = freshQueue.remove(key) | captureQueue.remove(key);
        // Copied again as a new chunk when it comes back.
        partial.remove(key);
        copiedWhileLoaded.remove(key);
        if (!mayCopy) {
            // No time left this tick: the 3D map draws this chunk from the flat map.
            return;
        }
        int[][] sides = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        boolean edge = false;
        for (int[] side : sides) {
            int cx = chunk.xPosition + side[0], cz = chunk.zPosition + side[1];
            if (dimension.store.time(cx, cz) == 0 && !world.getChunkProvider()
                .chunkExists(cx, cz)) {
                edge = true;
                break;
            }
        }
        boolean keptWhole = dimension.store.time(chunk.xPosition, chunk.zPosition) != 0
            && dimension.store.bottom(chunk.xPosition, chunk.zPosition) == 0;
        // Pictures only for a moment: the chunk goes away.
        long deadline = System.nanoTime() + UNLOAD_PICTURES_NANOS;
        // The pictures not taken in that moment, and those of its edges toward chunks already let go, are kept from
        // its copy before (see ChunkBlocks.keepPicturesFrom).
        if (edge && !keptWhole) {
            capture(world, chunk, true, true, deadline);
        } else if (waiting || !lastCapture.containsKey(key)) {
            // Flying fast, it came and goes before its turn: copied now, while its blocks are still there.
            capture(world, chunk, false, true, deadline);
        }
    }

    /** The surface map just scanned this chunk: its blocks are copied soon, unless they were lately (render thread). */
    public void onChunkScanned(World world, Chunk chunk, boolean changed) {
        if (!Config.record3d || writer == null) {
            return;
        }
        int dimensionId = world.provider.dimensionId;
        if (dimensionId != lastCaptureDimension) {
            lastCapture.clear();
            captureQueue.clear();
            freshQueue.clear();
            partial.clear();
            copiedWhileLoaded.clear();
            lastCaptureDimension = dimensionId;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        Long last = lastCapture.get(key);
        if (last == null) {
            freshQueue.add(key);
            return;
        }
        if (!copiedWhileLoaded.contains(key)) {
            // Back after it was let go: copied again soon, so the chunks around it copied without it are too.
            captureQueue.add(key);
            return;
        }
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        boolean near = player != null
            && Math.abs(chunk.xPosition - MathHelper.floor_double(player.posX / 16)) <= NEAR_CHUNKS
            && Math.abs(chunk.zPosition - MathHelper.floor_double(player.posZ / 16)) <= NEAR_CHUNKS;
        // A chunk whose blocks changed (trees and snow added after it arrived, or built on) is copied again soon.
        long interval = changed ? CHANGED_RECAPTURE_MS : near ? RECAPTURE_MS : FAR_RECAPTURE_MS;
        if (System.currentTimeMillis() - last < interval || freshQueue.contains(key)) {
            return;
        }
        captureQueue.add(key);
    }

    /**
     * Copies the chunk's blocks and stores them in the background.
     *
     * @param whole     down to the bottom of the world (see {@link #onChunkUnload})
     * @param unloading the chunk is being let go by the game
     * @param deadline  {@link System#nanoTime()} after which no more pictures are taken
     * @return false if some pictures are still to be taken (the chunk should be copied again soon)
     */
    private boolean capture(World world, Chunk chunk, boolean whole, boolean unloading, long deadline) {
        Dimension dimension = dimension(world.provider.dimensionId);
        if (dimension == null) {
            return true;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        lastCapture.put(key, System.currentTimeMillis());
        if (!unloading) {
            boolean first = copiedWhileLoaded.add(key);
            if (copiedWhileLoaded.size() > 50_000) {
                copiedWhileLoaded.clear();
            }
            updateNeighbours(world, chunk, key, first);
        }
        if (lastCapture.size() > 50_000) {
            lastCapture.clear();
        }
        ChunkBlocks blocks;
        boolean complete = true;
        try {
            blocks = BlockCapture.capture(world, chunk, whole);
            if (blocks != null && palette != null) {
                complete = FaceRenderer.addFaces(world, chunk, blocks, palette, deadline);
            }
        } catch (RuntimeException e) {
            WayFarMap.LOG.debug("Could not copy chunk blocks for the 3D map", e);
            return true;
        }
        if (blocks == null) {
            return true;
        }
        int cx = chunk.xPosition, cz = chunk.zPosition;
        FacePalette pictures = palette;
        writer.submit(() -> {
            try {
                if (pictures != null) {
                    // Pictures this copy lacks, or took without the chunk next door, are kept from the one before.
                    blocks.keepPicturesFrom(dimension.store.chunk(cx, cz), pictures.generation);
                }
                dimension.store.put(cx, cz, blocks);
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Could not store chunk blocks for the 3D map", e);
            }
        });
        return complete;
    }

    /**
     * Remembers which chunks around this one are missing, and, the first time it is copied (it just arrived), copies
     * again the chunks around it that were copied without it.
     */
    private void updateNeighbours(World world, Chunk chunk, long key, boolean first) {
        int missing = 0;
        for (int i = 0; i < AROUND.length; i++) {
            int cx = chunk.xPosition + AROUND[i][0], cz = chunk.zPosition + AROUND[i][1];
            boolean ready = ChunkScanner.isChunkReady(world, cx, cz);
            if (!ready) {
                missing |= 1 << i;
                continue;
            }
            if (!first) {
                continue;
            }
            long other = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            Integer otherMissing = partial.get(other);
            // This chunk lies at AROUND[7 - i] from the other one.
            if (otherMissing != null && (otherMissing & 1 << (7 - i)) != 0 && !freshQueue.contains(other)) {
                captureQueue.add(other);
            }
        }
        if (missing != 0) {
            if (partial.size() > 50_000) {
                partial.clear();
            }
            partial.put(key, missing);
        } else {
            partial.remove(key);
        }
    }

    /** A teammate's chunk was written into the flat map: tiles showing it from the flat map are drawn again. */
    public void onFlatChunkChanged(int dimension, int chunkX, int chunkZ) {
        changes.add(new long[] { dimension, chunkX, chunkZ, 255, System.currentTimeMillis() });
    }

    @Override
    public void chunkChanged(BlockStore store, int chunkX, int chunkZ, int top) {
        changes.add(new long[] { store.dimension, chunkX, chunkZ, top, System.currentTimeMillis() });
    }

    private void drainChanges() {
        long[] change;
        while ((change = changes.poll()) != null) {
            if (tiles != null) {
                tiles.chunkChanged((int) change[0], (int) change[1], (int) change[2], (int) change[3], change[4]);
            }
        }
    }

    /** Starts writing the changed block files in the background. */
    public Future<?> save() {
        if (writer == null) {
            return null;
        }
        return writer.submit(saveTask());
    }

    /** Resource packs changed: blocks look different, pictures of them are taken again. */
    public void onResourcesReloaded() {
        BlockLooks.clear();
        FaceRenderer.clear();
        cacheId = null;
        FacePalette old = palette;
        if (worldDirectory != null && writer != null
            && (old == null || old.packs != FacePalette.packsOf(spriteCacheId()))) {
            // Other resource packs: a new palette (it replaces the file when first saved).
            writer.submit(() -> {
                if (old != null) {
                    old.save();
                }
            });
            palette = FacePalette.load(worldDirectory, spriteCacheId());
        }
        // Copied again as they come by, with pictures in the new textures.
        lastCapture.clear();
        if (tiles != null) {
            tiles.invalidateAll();
        }
    }

    /** Identifies the sprites: they depend on the textures, not on how tiles are drawn from them. */
    private static String spriteCacheId() {
        Minecraft mc = Minecraft.getMinecraft();
        String packs = mc.gameSettings == null ? "" : String.valueOf(mc.gameSettings.resourcePacks);
        return "v" + SPRITE_VERSION + "-" + Integer.toHexString((packs + "|" + SPRITE_VERSION).hashCode());
    }

    /** Folder name of the saved tiles: they depend on the textures. */
    String cacheId() {
        String id = cacheId;
        if (id == null) {
            Minecraft mc = Minecraft.getMinecraft();
            String packs = mc.gameSettings == null ? "" : String.valueOf(mc.gameSettings.resourcePacks);
            id = "v" + RENDER_VERSION + "-" + Integer.toHexString((packs + "|" + RENDER_VERSION).hashCode());
            cacheId = id;
        }
        return id;
    }

    // ---------------------------------------------------------------- the world map

    private IsoTiles tiles() {
        if (tiles == null) {
            tiles = new IsoTiles(this);
        }
        return tiles;
    }

    /**
     * Draws the 3D map of a dimension into the screen rectangle (render thread).
     *
     * @param centerX,centerZ point of the plane at {@link IsoProjection#REFERENCE_Y} in the middle of the rectangle
     * @param scale           GUI pixels per block
     * @param factor          screen pixels per GUI pixel
     */
    public void draw(int dimensionId, int rotation, double centerX, double centerZ, double scale, int factor, int x,
        int y, int width, int height) {
        Dimension dimension = dimension(dimensionId);
        if (dimension == null) {
            return;
        }
        BlockLooks.pump(LOOK_BUDGET_NANOS);
        drainChanges();
        IsoProjection projection = IsoProjection.of(rotation);
        IsoTiles view = tiles();
        view.draw(
            dimension,
            rotation,
            projection.u(centerX, centerZ),
            projection.v(centerX, IsoProjection.REFERENCE_Y, centerZ),
            scale,
            factor,
            x,
            y,
            width,
            height);
    }

    /**
     * The block seen at a screen point: {x, y, z}, or null if nothing is drawn there yet.
     *
     * @param offsetX,offsetY screen point relative to the middle of the map, in GUI pixels
     */
    public int[] pick(int dimensionId, int rotation, double centerX, double centerZ, double scale, int factor,
        double offsetX, double offsetY) {
        if (tiles == null) {
            return null;
        }
        IsoProjection projection = IsoProjection.of(rotation);
        double u = projection.u(centerX, centerZ) + offsetX / scale;
        double v = projection.v(centerX, IsoProjection.REFERENCE_Y, centerZ) + offsetY / scale;
        return tiles.pick(dimensionId, rotation, u, v, scale, factor);
    }
}
