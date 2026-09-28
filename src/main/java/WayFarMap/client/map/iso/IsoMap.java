package WayFarMap.client.map.iso;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;
import WayFarMap.WayFarMap;

/**
 * The 3D (isometric) world map, drawn like Dynmap's HD maps from the blocks themselves: while playing, the blocks
 * of every explored chunk are kept ({@link BlockStore}); on the world map, tiles are drawn from them by ray tracing
 * ({@link IsoTracer}, {@link IsoTiles}). Chunks without blocks (explored before, or by a teammate) are shown from the
 * flat map's colors and heights.
 */
public final class IsoMap implements BlockStore.Listener {

    public static final IsoMap INSTANCE = new IsoMap();

    /** Changes when tiles would look different; old saved tiles are then not used. */
    private static final int RENDER_VERSION = 10;
    /** Changes when sprites would look different; the old ones are then taken again. */
    private static final int SPRITE_VERSION = 7;
    /** Time per game tick spent copying chunks (a chunk takes a fraction of a millisecond, more with pictures). */
    private static final long CAPTURE_BUDGET_NANOS = 4_000_000L;
    /** A chunk is copied again at most this often while the player stays near it. */
    private static final long RECAPTURE_MS = 10_000;
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

    private IsoMap() {}

    /** A world was joined: its maps live in {@code worldDirectory/dim<id>/}. */
    public void open(File worldDirectory) {
        close();
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
            return;
        }
        long end = System.nanoTime() + CAPTURE_BUDGET_NANOS;
        captureFrom(world, freshQueue, end);
        captureFrom(world, captureQueue, end);
    }

    /** Copies chunks from the queue until the time is up. */
    private void captureFrom(World world, LinkedHashSet<Long> queue, long end) {
        Iterator<Long> it = queue.iterator();
        while (it.hasNext() && System.nanoTime() < end) {
            long key = it.next();
            it.remove();
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) {
                continue;
            }
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk != null && !chunk.isEmpty()) {
                capture(world, chunk);
            }
        }
    }

    /** The surface map just scanned this chunk: its blocks are copied soon, unless they were lately (render thread). */
    public void onChunkScanned(World world, Chunk chunk) {
        if (!Config.record3d || writer == null) {
            return;
        }
        int dimensionId = world.provider.dimensionId;
        if (dimensionId != lastCaptureDimension) {
            lastCapture.clear();
            captureQueue.clear();
            freshQueue.clear();
            lastCaptureDimension = dimensionId;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        Long last = lastCapture.get(key);
        if (last == null) {
            freshQueue.add(key);
            return;
        }
        if (System.currentTimeMillis() - last < RECAPTURE_MS || freshQueue.contains(key)) {
            return;
        }
        captureQueue.add(key);
    }

    private void capture(World world, Chunk chunk) {
        Dimension dimension = dimension(world.provider.dimensionId);
        if (dimension == null) {
            return;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        lastCapture.put(key, System.currentTimeMillis());
        if (lastCapture.size() > 50_000) {
            lastCapture.clear();
        }
        ChunkBlocks blocks;
        try {
            blocks = BlockCapture.capture(world, chunk);
            if (blocks != null && palette != null) {
                FaceRenderer.addFaces(world, chunk, blocks, palette);
            }
        } catch (RuntimeException e) {
            WayFarMap.LOG.debug("Could not copy chunk blocks for the 3D map", e);
            return;
        }
        if (blocks == null) {
            return;
        }
        int cx = chunk.xPosition, cz = chunk.zPosition;
        writer.submit(() -> {
            try {
                dimension.store.put(cx, cz, blocks);
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Could not store chunk blocks for the 3D map", e);
            }
        });
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
            && (old == null || old.generation != (spriteCacheId().hashCode() & 0x7FFFFFFF))) {
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
