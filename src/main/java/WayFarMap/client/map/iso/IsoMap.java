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
import java.util.concurrent.ConcurrentHashMap;
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
 * ({@link IsoTracer}, {@link IsoTiles}). Only chunks whose blocks were copied are shown.
 */
public final class IsoMap implements BlockStore.Listener {

    public static final IsoMap INSTANCE = new IsoMap();

    /** Changes when tiles would look different; old saved tiles are then not used. */
    private static final int RENDER_VERSION = 15;
    /** Changes when sprites would look different; the old ones are then taken again. */
    private static final int SPRITE_VERSION = 7;
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
    private static final long LOOK_BUDGET_NANOS = 10_000_000L;
    /** The same while playing: looks of the chunks copied are worked out ahead, a little each tick. */
    private static final long TICK_LOOK_BUDGET_NANOS = 2_000_000L;

    /** The maps of one dimension. */
    static final class Dimension {

        final int id;
        final File directory;
        final BlockStore store;

        Dimension(int id, File directory, BlockStore.Listener listener) {
            this.id = id;
            this.directory = directory;
            this.store = new BlockStore(id, directory, listener);
        }
    }

    private File worldDirectory;
    private final Map<Integer, Dimension> dimensions = new HashMap<>();
    /** Stores the copied chunks, one after the other. */
    private ExecutorService writer;
    /**
     * Writes the block files and pictures to disk. Apart from the writer: saving whole regions takes seconds, and new
     * chunks would wait for it before they show on the map.
     */
    private ExecutorService saver;
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
    /**
     * Chunks whose pictures weren't all taken yet, and how often they were copied so far: a chunk is stored only
     * once they all are (the ones taken are kept in the caches meanwhile), so the map never shows it half drawn,
     * with icons where machines, glass and connected textures belong.
     */
    private final Map<Long, Integer> unfinished = new HashMap<>();
    /**
     * Per chunk, {@link ChunkBlocks#signature} of the copy last stored with all its pictures: a chunk copied again
     * whose blocks are the same is left as it is, without taking its pictures again. Chunks there change pictures
     * on their own (blinking ME controllers, GregTech machines turning on and off) but not blocks; copying them again
     * and again took half the game's time in a big base. {@code /wf chunkload 3d} takes them anew.
     */
    private final Map<Long, Long> signatures = new HashMap<>();
    /**
     * Signatures of stored copies with every picture, worked out by the writer for chunks not yet copied this
     * session ({@link #NO_SIGNATURE} if there is none such): a chunk whose blocks are the same as stored needs no
     * pictures after the game is started again, so a big base shows at once instead of being taken anew each time.
     */
    private final Map<Long, Long> storedSignatures = new ConcurrentHashMap<>();
    private static final long NO_SIGNATURE = 0;
    /** Chunks whose stored copy the writer is looking at; they come back to their queue when it is done. */
    private final Set<Long> checking = new HashSet<>();
    private final Queue<Long> checked = new ConcurrentLinkedQueue<>();
    /** Chunks being copied for {@code /wf chunkload}: they don't go into the queues (they are let go soon). */
    private final Set<Long> loading = new HashSet<>();
    /** Chunks copied anew for {@code /wf chunkload 3d}: copied and stored even if unchanged. */
    private final Set<Long> refreshing = new HashSet<>();
    /**
     * Copies in a row without a single new picture after which a chunk is stored with pictures missing (the game
     * gives none for some block). Copies that take pictures don't count: a chunk with thousands of machines takes
     * hundreds of ticks, and giving up after 40 left big bases with holes.
     */
    private static final int MAX_UNFINISHED_COPIES = 40;
    /**
     * The chunk whose pictures are being taken, finished before the next one is started: taking a little of each of
     * a hundred such chunks in turn finished none of them for minutes.
     */
    private Long inProgress;
    /** For the log: when the chunk being finished was started, and its tries since. */
    private long inProgressSince;
    private int inProgressTries;

    private IsoMap() {}

    /** A world was joined: its maps live in {@code worldDirectory/dim<id>/}. */
    public void open(File worldDirectory) {
        close();
        // Block ids belong to the world (Forge numbers blocks per world and server), and pictures to its palette:
        // nothing worked out for another world is used here.
        BlockLooks.clear();
        FaceRenderer.clear();
        this.worldDirectory = worldDirectory;
        IsoLog.open(Minecraft.getMinecraft().mcDataDir, worldDirectory);
        palette = FacePalette.load(worldDirectory, spriteCacheId());
        writer = backgroundThread("WayFarMap 3D writer");
        saver = backgroundThread("WayFarMap 3D saver");
    }

    private static ExecutorService backgroundThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            // Below the game's threads, but not the lowest: busy, the system gave those next to no time and new
            // chunks waited seconds to be stored.
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
    }

    /** Lets the thread finish what it was given, for up to 30 seconds. */
    private static void finish(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                WayFarMap.LOG.warn("Saving the 3D map took too long");
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    /** The world was left: saves the blocks, waits for it, and frees everything (render thread). */
    public void close() {
        if (tiles != null) {
            tiles.shutdown();
            tiles = null;
        }
        if (writer != null) {
            // The chunks still to be stored first, then everything saved.
            finish(writer);
            saver.submit(saveTask());
            finish(saver);
            writer = null;
            saver = null;
        }
        dimensions.clear();
        changes.clear();
        lastCapture.clear();
        captureQueue.clear();
        freshQueue.clear();
        partial.clear();
        copiedWhileLoaded.clear();
        unfinished.clear();
        signatures.clear();
        refreshing.clear();
        storedSignatures.clear();
        checking.clear();
        checked.clear();
        loading.clear();
        inProgress = null;
        lastCaptureDimension = Integer.MIN_VALUE;
        worldDirectory = null;
        palette = null;
        IsoLog.close();
    }

    /** Saves the pictures, then the blocks that refer to them. */
    private Runnable saveTask() {
        List<Dimension> all = new ArrayList<>(dimensions.values());
        FacePalette pictures = palette;
        return () -> {
            IsoLog.saverStart("save all");
            try {
                if (pictures != null) {
                    long start = System.nanoTime();
                    pictures.save();
                    IsoLog.log("PICTURES_SAVED ms=" + (System.nanoTime() - start) / 1_000_000);
                }
                all.forEach(d -> d.store.save());
            } finally {
                IsoLog.saverEnd("save all");
            }
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
        IsoLog.log("PICTURES_MANY unsavedMB=" + (pictures.unsavedBytes() >> 20) + ": saving them now");
        saver.submit(() -> {
            IsoLog.saverStart("pictures");
            try {
                pictures.save();
            } finally {
                savingPictures = false;
                IsoLog.saverEnd("pictures");
            }
        });
    }

    /** Called once per client tick (render thread): copies the chunks that are due, for a few milliseconds. */
    public void tick(World world) {
        long start = System.nanoTime();
        boolean outOfTime = false;
        try {
            outOfTime = tickCapture(world);
        } finally {
            if (IsoLog.on()) {
                FacePalette pictures = palette;
                IsoLog.tick(
                    System.nanoTime() - start,
                    outOfTime,
                    freshQueue.size(),
                    captureQueue.size(),
                    unfinished.size(),
                    tilesQueued(),
                    pictures == null ? 0 : pictures.unsavedBytes(),
                    pictures);
            }
        }
    }

    /** @return whether the time ran out with chunks still waiting */
    private boolean tickCapture(World world) {
        BlockLooks.pump(TICK_LOOK_BUDGET_NANOS);
        drainChanges();
        Long done;
        while ((done = checked.poll()) != null) {
            // Its stored copy was looked at: its turn again (first, as a chunk new this session), now with the
            // signature at hand.
            if (checking.remove(done) && !loading.contains(done)) {
                freshQueue.add(done);
            }
        }
        if (world == null || writer == null || captureQueue.isEmpty() && freshQueue.isEmpty() && inProgress == null) {
            return false;
        }
        if (world.provider.dimensionId != lastCaptureDimension) {
            IsoLog
                .log("DIMENSION_CHANGED queues cleared: fresh=" + freshQueue.size() + " again=" + captureQueue.size());
            captureQueue.clear();
            freshQueue.clear();
            partial.clear();
            copiedWhileLoaded.clear();
            unfinished.clear();
            refreshing.clear();
            inProgress = null;
            return false;
        }
        savePicturesIfMany();
        // A chunk takes a fraction of a millisecond, more with pictures.
        long start = System.nanoTime(), budget = Config.isoCaptureMs * 1_000_000L;
        // The chunk being finished first, with most of the time; the rest for the others (most take a fraction of
        // a millisecond), so new land keeps coming while a big base is taken.
        continueInProgress(world, start + budget * 3 / 4);
        long end = start + budget;
        captureFrom(world, freshQueue, end);
        captureFrom(world, captureQueue, end);
        return !freshQueue.isEmpty() || !captureQueue.isEmpty() || inProgress != null;
    }

    /** Takes more pictures of the chunk being finished, until the deadline (at least one batch). */
    private void continueInProgress(World world, long deadline) {
        Long key = inProgress;
        if (key == null) {
            return;
        }
        int cx = (int) (key >> 32), cz = (int) (long) key;
        if (!ChunkScanner.isChunkReady(world, cx, cz)) {
            inProgress = null;
            unfinished.remove(key);
            IsoLog.dropped(cx, cz, "no longer loaded while its pictures were taken");
            return;
        }
        inProgressTries++;
        if (capture(world, world.getChunkFromChunkCoords(cx, cz), false, false, deadline)) {
            inProgress = null;
            IsoLog.log(
                "IN_PROGRESS_DONE " + cx
                    + ","
                    + cz
                    + " finished in "
                    + (System.currentTimeMillis() - inProgressSince)
                    + " ms over "
                    + inProgressTries
                    + " more ticks; the next chunk may start");
        }
    }

    /**
     * Copies chunks from the queue until the time is up. The first one is taken anew each time: copying a chunk may
     * put chunks in the queues (one whose pictures weren't all taken in time goes back).
     */
    private void captureFrom(World world, LinkedHashSet<Long> queue, long end) {
        List<Long> later = null;
        try {
            captureFrom(world, queue, end, later = new ArrayList<>());
        } finally {
            // Back at the end, in their order.
            queue.addAll(later);
        }
    }

    private void captureFrom(World world, LinkedHashSet<Long> queue, long end, List<Long> later) {
        while (!queue.isEmpty() && System.nanoTime() < end) {
            Iterator<Long> it = queue.iterator();
            long key = it.next();
            it.remove();
            int cx = (int) (key >> 32), cz = (int) key;
            if (inProgress != null && unfinished.containsKey(key)) {
                // A chunk with many pictures still to take, while another one is being finished: not tried now (a
                // try found its blocks again and took a few pictures, and took the time of the chunks that are
                // quick), its turn comes when that one is done.
                later.add(key);
                continue;
            }
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) {
                IsoLog.dropped(cx, cz, "no longer loaded when its turn came");
                continue;
            }
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk != null && !chunk.isEmpty()) {
                if (!capture(world, chunk, false, false, end)) {
                    if (inProgress == null) {
                        // Its other pictures are taken in the next ticks, before any other chunk starts on its own.
                        inProgress = key;
                        inProgressSince = System.currentTimeMillis();
                        inProgressTries = 0;
                        IsoLog.log(
                            "IN_PROGRESS " + cx
                                + ","
                                + cz
                                + " pictures "
                                + FaceRenderer.progressDone
                                + "/"
                                + FaceRenderer.progressTotal
                                + " taken; finished first in the next ticks");
                        return;
                    }
                    // Another one is being finished: this one waits its turn (the pictures taken are kept).
                    queue.add(key);
                    IsoLog.log(
                        "REQUEUED " + cx
                            + ","
                            + cz
                            + " pictures missing, another chunk is being finished; back at the end of "
                            + (queue == freshQueue ? "fresh" : "again")
                            + " queue (position "
                            + queue.size()
                            + ")");
                }
            } else {
                IsoLog.dropped(cx, cz, "chunk empty when its turn came");
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
        boolean waitingFresh = freshQueue.contains(key);
        boolean waiting = freshQueue.remove(key) | captureQueue.remove(key);
        if (inProgress != null && inProgress == key) {
            inProgress = null;
            waiting = true;
        }
        // Copied again as a new chunk when it comes back.
        partial.remove(key);
        copiedWhileLoaded.remove(key);
        if (!mayCopy) {
            // No time left this tick: the chunk is copied the next time it is loaded.
            if (waiting) {
                IsoLog.dropped(
                    chunk.xPosition,
                    chunk.zPosition,
                    "unloaded while waiting in " + (waitingFresh ? "fresh" : "again") + " queue, no unload time left");
            }
            IsoLog.unload(
                chunk.xPosition,
                chunk.zPosition,
                "skipped (no time left this tick) waiting=" + waiting + " everCopied=" + lastCapture.containsKey(key));
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
            IsoLog.unload(chunk.xPosition, chunk.zPosition, "copy whole (edge of explored map) waiting=" + waiting);
            unloadReason = "unload-edge";
            capture(world, chunk, true, true, deadline);
        } else if (waiting || !lastCapture.containsKey(key)) {
            // Flying fast, it came and goes before its turn: copied now, while its blocks are still there.
            IsoLog.unload(
                chunk.xPosition,
                chunk.zPosition,
                "copy now (came and goes before its turn) waiting=" + waiting
                    + " waitingFresh="
                    + waitingFresh
                    + " everCopied="
                    + lastCapture.containsKey(key));
            unloadReason = "unload-late";
            capture(world, chunk, false, true, deadline);
        } else {
            IsoLog.unload(chunk.xPosition, chunk.zPosition, "nothing to do (copied already)");
        }
        unloadReason = null;
    }

    /** Why the chunk being copied as it is let go is copied, for the log. */
    private String unloadReason;

    /** The surface map just scanned this chunk: its blocks are copied soon, unless they were lately (render thread). */
    public void onChunkScanned(World world, Chunk chunk, boolean changed) {
        if (!Config.record3d || writer == null) {
            return;
        }
        int dimensionId = world.provider.dimensionId;
        if (dimensionId != lastCaptureDimension) {
            IsoLog.log("DIMENSION " + lastCaptureDimension + " -> " + dimensionId + ": queues cleared");
            lastCapture.clear();
            captureQueue.clear();
            freshQueue.clear();
            partial.clear();
            copiedWhileLoaded.clear();
            unfinished.clear();
            signatures.clear();
            refreshing.clear();
            storedSignatures.clear();
            checking.clear();
            checked.clear();
            inProgress = null;
            lastCaptureDimension = dimensionId;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        Long last = lastCapture.get(key);
        int cx = chunk.xPosition, cz = chunk.zPosition;
        if (last == null) {
            freshQueue.add(key);
            IsoLog.queued(cx, cz, "fresh", "fresh", freshQueue.size(), captureQueue.size());
            return;
        }
        if (!copiedWhileLoaded.contains(key)) {
            // Back after it was let go: copied again soon, so the chunks around it copied without it are too.
            captureQueue.add(key);
            IsoLog.queued(cx, cz, "back", "again", freshQueue.size(), captureQueue.size());
            return;
        }
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        boolean near = player != null
            && Math.abs(chunk.xPosition - MathHelper.floor_double(player.posX / 16)) <= NEAR_CHUNKS
            && Math.abs(chunk.zPosition - MathHelper.floor_double(player.posZ / 16)) <= NEAR_CHUNKS;
        // A chunk whose blocks changed (trees and snow added after it arrived, or built on) is copied again soon.
        long interval = changed ? CHANGED_RECAPTURE_MS : near ? RECAPTURE_MS : FAR_RECAPTURE_MS;
        long since = System.currentTimeMillis() - last;
        if (since < interval || freshQueue.contains(key)) {
            IsoLog.scanDecision(
                cx,
                cz,
                freshQueue.contains(key) ? "skip (still in fresh queue)"
                    : "skip (copied lately, interval " + interval + "ms, changed=" + changed + ")",
                since);
            return;
        }
        captureQueue.add(key);
        IsoLog.queued(
            cx,
            cz,
            changed ? "changed" : near ? "near" : "far",
            "again",
            freshQueue.size(),
            captureQueue.size());
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
        return capture(world, chunk, whole, unloading, deadline, false);
    }

    /**
     * Copies a chunk sent for {@code /wf chunkload} (render thread), for up to the deadline. False until it is done:
     * its stored copy is looked at first (unchanged: nothing to do), then its pictures may take several ticks. The
     * chunk is let go after, so it isn't put in the queues.
     */
    public boolean captureForLoad(World world, Chunk chunk, long deadline) {
        if (!Config.record3d || writer == null || chunk == null || chunk.isEmpty()) {
            return true;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        if (loading.add(key)) {
            // Copied and stored again whatever is stored (an old copy): the loading is asked for to make the 3D map
            // of the area anew.
            refreshing.add(key);
            IsoLog.log("CHUNKLOAD_CAPTURE " + chunk.xPosition + "," + chunk.zPosition + " forced (stored again)");
        }
        boolean done = capture(world, chunk, false, false, deadline, true);
        if (done) {
            loading.remove(key);
        }
        return done;
    }

    /** The chunks of a batch of {@code /wf chunkload} were let go: what was left of them is forgotten. */
    public void forgetLoaded(long key) {
        if (loading.remove(key)) {
            IsoLog.log("CHUNKLOAD_UNFINISHED " + (int) (key >> 32) + "," + (int) key + " let go before stored");
            refreshing.remove(key);
        }
        unfinished.remove(key);
        freshQueue.remove(key);
        captureQueue.remove(key);
        if (inProgress != null && inProgress == key) {
            inProgress = null;
        }
    }

    /**
     * @param forLoad for {@code /wf chunkload}: false (not done) while its stored copy is being looked at, instead
     *                of coming back through the queues
     */
    private boolean capture(World world, Chunk chunk, boolean whole, boolean unloading, long deadline,
        boolean forLoad) {
        Dimension dimension = dimension(world.provider.dimensionId);
        if (dimension == null) {
            return true;
        }
        long key = ((long) chunk.xPosition << 32) | (chunk.zPosition & 0xFFFFFFFFL);
        lastCapture.put(key, System.currentTimeMillis());
        if (!unloading && !forLoad) {
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
        int cx = chunk.xPosition, cz = chunk.zPosition;
        long t0 = System.nanoTime(), t1 = t0;
        long signature = 0;
        boolean refresh = refreshing.contains(key);
        try {
            blocks = BlockCapture.capture(world, chunk, whole);
            t1 = System.nanoTime();
            if (blocks != null) {
                signature = blocks.signature();
                Long stored = signatures.get(key);
                if (stored != null && stored == signature && !refresh) {
                    // Nothing changed but maybe pictures of animated blocks: left as it is.
                    unfinished.remove(key);
                    IsoLog.unchanged(cx, cz, unloading ? unloadReason : null, t1 - t0);
                    return true;
                }
                if (stored == null && !refresh && !unloading && palette != null && !unfinished.containsKey(key)) {
                    // Not copied yet this session: the copy stored before (maybe in an earlier game) may be it.
                    Long onDisk = storedSignatures.remove(key);
                    if (onDisk == null) {
                        if (checking.add(key)) {
                            checkStored(dimension, key, palette.generation);
                        }
                        return !forLoad;
                    }
                    if (onDisk == signature) {
                        signatures.put(key, signature);
                        unfinished.remove(key);
                        IsoLog.unchanged(cx, cz, "stored copy has the same blocks and every picture", t1 - t0);
                        return true;
                    }
                    IsoLog.log(
                        "DISK_CHECK " + cx
                            + ","
                            + cz
                            + (onDisk == NO_SIGNATURE ? " no usable stored copy" : " blocks changed since stored")
                            + ": pictures taken");
                }
            }
            if (blocks != null && palette != null) {
                complete = FaceRenderer.addFaces(world, chunk, blocks, palette, deadline);
            }
        } catch (RuntimeException e) {
            WayFarMap.LOG.debug("Could not copy chunk blocks for the 3D map", e);
            IsoLog.captureFailed(cx, cz, "exception " + e);
            return true;
        }
        long t2 = System.nanoTime();
        if (blocks == null) {
            unfinished.remove(key);
            IsoLog.captureFailed(cx, cz, "no blocks (empty chunk)");
            return true;
        }
        // Their looks are worked out in the next ticks, so the 3D map has them when it opens.
        BlockLooks.warm(blocks.lookKeys());
        String reason = unloading ? unloadReason : null;
        boolean unfinishedGaveUp = false;
        if (!complete && !unloading) {
            // Only copies that took no picture at all count toward giving up.
            int copies = FaceRenderer.lastDrawn > 0 ? 0 : unfinished.getOrDefault(key, 0) + 1;
            unfinished.put(key, copies);
            if (copies < MAX_UNFINISHED_COPIES) {
                IsoLog
                    .captured(cx, cz, reason, whole, unloading, t1 - t0, t2 - t1, false, false, copies, deadline - t2);
                // Stored once all its pictures are taken; until then the map shows the copy before (or nothing
                // for a new chunk), not one half drawn.
                if (FaceRenderer.progressTotal > 0) {
                    IsoLog.log(
                        "PROGRESS " + cx
                            + ","
                            + cz
                            + " pictures "
                            + FaceRenderer.progressDone
                            + "/"
                            + FaceRenderer.progressTotal
                            + " ("
                            + FaceRenderer.progressDone * 100 / FaceRenderer.progressTotal
                            + "%), +"
                            + FaceRenderer.lastDrawn
                            + " this try in "
                            + (t2 - t1) / 1_000_000
                            + " ms"
                            + (copies > 0 ? ", tries without a picture: " + copies : ""));
                }
                if (unfinished.size() > 10_000) {
                    IsoLog.log("UNFINISHED_CLEARED over 10000 chunks with missing pictures");
                    unfinished.clear();
                }
                return false;
            }
            IsoLog.captured(cx, cz, reason, whole, unloading, t1 - t0, t2 - t1, false, true, copies, deadline - t2);
            complete = true;
            unfinishedGaveUp = true;
        } else {
            IsoLog.captured(cx, cz, reason, whole, unloading, t1 - t0, t2 - t1, complete, false, 0, deadline - t2);
        }
        unfinished.remove(key);
        FacePalette pictures = palette;
        if (FaceRenderer.progressTotal > 0 && FaceRenderer.sessionReused != 0) {
            IsoLog.log(
                "PROGRESS " + cx
                    + ","
                    + cz
                    + " all "
                    + FaceRenderer.progressTotal
                    + " blocks' pictures taken, chunk stored now");
        }
        // Remembered only for a copy with every picture: one stored with some missing is copied again as before.
        boolean allPictures = complete && pictures != null
            && !blocks.picturesMissing
            && blocks.unsureCells.length == 0
            && !unfinishedGaveUp;
        if (allPictures) {
            if (signatures.size() > 100_000) {
                signatures.clear();
            }
            signatures.put(key, signature);
        } else {
            signatures.remove(key);
        }
        boolean force = refreshing.remove(key);
        IsoLog.Trace trace = IsoLog.submitted(cx, cz);
        writer.submit(() -> {
            IsoLog.writerStart(trace);
            long keepNanos = 0;
            long[] timing = new long[7];
            try {
                ChunkBlocks before = dimension.store.chunk(cx, cz);
                if (pictures != null) {
                    long start = System.nanoTime();
                    // Pictures this copy lacks, or took without the chunk next door, are kept from the one before.
                    blocks.keepPicturesFrom(before, pictures.generation);
                    keepNanos = System.nanoTime() - start;
                }
                if (!force && blocks.onlyRetakenPictures(before)) {
                    // Only pictures of animated blocks differ: the stored copy stays, the tiles aren't redrawn.
                    IsoLog.log("STORE_SKIPPED " + cx + "," + cz + " same blocks, only pictures taken again");
                    return;
                }
                dimension.store.put(cx, cz, blocks, timing);
                if (timing[5] != 0 && before != null) {
                    IsoLog.chunkDiff(cx, cz, trace, before, blocks);
                }
                if (timing[5] != 0) {
                    BlockDiag.chunkReport(cx, cz, blocks);
                }
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Could not store chunk blocks for the 3D map", e);
                IsoLog.log("STORE_FAILED " + cx + "," + cz + " " + e);
            } finally {
                IsoLog.stored(trace, cx, cz, keepNanos, timing);
            }
        });
        return complete;
    }

    /**
     * Has the writer work out the signature of the chunk's stored copy (reading it may take the disk), if it has
     * every picture; the chunk then comes back to the queue.
     */
    private void checkStored(Dimension dimension, long key, int generation) {
        int cx = (int) (key >> 32), cz = (int) key;
        writer.submit(() -> {
            long signature = NO_SIGNATURE;
            try {
                ChunkBlocks stored = dimension.store.chunk(cx, cz);
                if (stored != null && stored.allPicturesTaken(generation)) {
                    signature = stored.signature();
                }
            } catch (RuntimeException e) {
                WayFarMap.LOG.debug("Could not read the stored 3D map chunk", e);
            } finally {
                if (storedSignatures.size() > 100_000) {
                    storedSignatures.clear();
                }
                storedSignatures.put(key, signature);
                checked.add(key);
            }
        });
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
                IsoLog.queued(cx, cz, "neighbour", "again", freshQueue.size(), captureQueue.size());
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

    @Override
    public void chunkChanged(BlockStore store, int chunkX, int chunkZ, int top) {
        changes.add(new long[] { store.dimension, chunkX, chunkZ, top, System.currentTimeMillis() });
    }

    private void drainChanges() {
        long[] change;
        while ((change = changes.poll()) != null) {
            if (tiles != null) {
                int marked = tiles
                    .chunkChanged((int) change[0], (int) change[1], (int) change[2], (int) change[3], change[4]);
                IsoLog.marked((int) change[0], (int) change[1], (int) change[2], change[4], marked, tiles.size());
            }
        }
    }

    /** Starts writing the changed block files in the background. */
    public Future<?> save() {
        if (writer == null) {
            return null;
        }
        return saver.submit(saveTask());
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
            saver.submit(() -> {
                IsoLog.saverStart("old pictures");
                try {
                    if (old != null) {
                        old.save();
                    }
                } finally {
                    IsoLog.saverEnd("old pictures");
                }
            });
            palette = FacePalette.load(worldDirectory, spriteCacheId());
        }
        // Copied again as they come by, with pictures in the new textures.
        lastCapture.clear();
        signatures.clear();
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

    /** Chunks waiting to be copied for the 3D map (render thread). */
    public int chunksQueued() {
        return freshQueue.size() + captureQueue.size() + checking.size() + (inProgress != null ? 1 : 0);
    }

    /** Tiles of the 3D map waiting to be drawn, 0 if none. */
    public int tilesQueued() {
        return tiles == null ? 0 : tiles.queued();
    }

    /**
     * How far toward the viewer the map stops being see-through at each point of a grid on the projection plane (u0
     * + i * step, v0 + j * step), row by row into {@code out}: NaN where nothing is drawn yet, negative infinity
     * where nothing solid is seen. False if the 3D map has no tiles.
     */
    public boolean solidToward(int dimensionId, int rotation, double scale, int factor, double u0, double v0,
        double step, int columns, int rows, float[] out) {
        if (tiles == null) {
            return false;
        }
        tiles.solidToward(dimensionId, rotation, scale, factor, u0, v0, step, columns, rows, out);
        return true;
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
