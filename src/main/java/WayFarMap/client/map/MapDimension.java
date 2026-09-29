package WayFarMap.client.map;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import WayFarMap.WayFarMap;

/** All map regions of one dimension of one world or server. */
public class MapDimension {

    public final int dimensionId;
    private final File directory;
    private final ExecutorService loadExecutor;
    private final Map<Long, MapRegion> regions = new HashMap<>();
    /** Regions being read from disk in the background. */
    private final Map<Long, Future<MapRegion>> loading = new HashMap<>();
    /** Regions known to have no file on disk, so we don't probe the file system every frame. */
    private final Set<Long> missing = new HashSet<>();
    /** Reduced copies for drawing zoomed out, and those being built from files in the background. */
    private final Map<Long, LodTile> lods = new HashMap<>();
    private final Map<Long, Future<LodTile>> lodLoading = new HashMap<>();

    /** Which regions {@link #retain} keeps, by region coordinates. */
    public interface RegionFilter {

        boolean keep(int rx, int rz);
    }

    /** Short name of the map for the flat map log (surface, biomes, cave layer folder). */
    private final String label;

    public MapDimension(int dimensionId, File directory, ExecutorService loadExecutor) {
        this.dimensionId = dimensionId;
        this.directory = directory;
        this.loadExecutor = loadExecutor;
        // dimN, dimN/biomes, dimN/caves/L
        File parent = directory.getParentFile();
        String name = directory.getName();
        if (parent != null && parent.getName()
            .equals("caves")) {
            File dim = parent.getParentFile();
            this.label = (dim == null ? "" : dim.getName() + "/") + "cave" + name;
        } else if (parent != null && name.equals("biomes")) {
            this.label = parent.getName() + "/biomes";
        } else {
            this.label = name;
        }
    }

    /** Short name of the map for the log. */
    String label() {
        return label;
    }

    /** Who asked for a region from the render thread, for the log (not the scanner's own frames). */
    private static String caller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < stack.length && i < 8; i++) {
            String cls = stack[i].getClassName();
            if (!cls.endsWith("MapDimension")) {
                return cls.substring(cls.lastIndexOf('.') + 1) + "." + stack[i].getMethodName();
            }
        }
        return "?";
    }

    /** Folder of the region files. */
    public File getDirectory() {
        return directory;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Pixel of a block from what is in memory, the full region or else its reduced copy (zoomed out); 0 if neither
     * is loaded. Never reads from disk.
     */
    public int peekPixel(int x, int z) {
        long key = key(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        int lx = x & (MapRegion.SIZE - 1), lz = z & (MapRegion.SIZE - 1);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region.getPixel(lx, lz);
        }
        LodTile tile = lods.get(key);
        return tile == null ? 0 : tile.getPixel(lx / LodTile.FACTOR, lz / LodTile.FACTOR);
    }

    /** Like {@link #peekPixel}, for the extra byte (height or biome); 0 if unknown. */
    public int peekExtra(int x, int z) {
        long key = key(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        int lx = x & (MapRegion.SIZE - 1), lz = z & (MapRegion.SIZE - 1);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region.getExtra(lx, lz);
        }
        LodTile tile = lods.get(key);
        return tile == null ? 0 : tile.getExtra(lx / LodTile.FACTOR, lz / LodTile.FACTOR);
    }

    /** Whether the region is known to have no file (and none was made): nothing to draw there. */
    public boolean isKnownMissing(int rx, int rz) {
        return missing.contains(key(rx, rz));
    }

    /** Region that is already in memory, or null. */
    public MapRegion getLoadedRegion(int rx, int rz) {
        return regions.get(key(rx, rz));
    }

    /**
     * Returns the region, loading it from disk if needed. Blocks while the region is being read.
     *
     * @param create create an empty region if none exists on disk
     * @return the region, or null if it doesn't exist and {@code create} is false
     */
    public MapRegion getRegion(int rx, int rz, boolean create) {
        long key = key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region;
        }
        Future<MapRegion> pending = loading.remove(key);
        boolean log = FlatLog.on();
        if (pending != null) {
            boolean done = pending.isDone();
            long start = System.nanoTime();
            region = await(pending);
            if (log) {
                if (!done) {
                    FlatLog.blockingRead(label, rx, rz, System.nanoTime() - start, true, caller());
                }
                FlatLog.picked(label, rx, rz, region != null, caller() + (done ? "" : "(waited)"));
            }
        } else if (!missing.contains(key)) {
            long start = System.nanoTime();
            region = readFile(directory, rx, rz, label);
            if (log) {
                FlatLog.blockingRead(label, rx, rz, System.nanoTime() - start, false, caller());
            }
        }
        if (region == null) {
            missing.add(key);
            if (!create) {
                return null;
            }
            region = new MapRegion(rx, rz);
            missing.remove(key);
            FlatLog.made(label, rx, rz);
        }
        regions.put(key, region);
        return region;
    }

    /**
     * Non-blocking lookup for rendering: returns the region if it is in memory, otherwise starts reading it in the
     * background and returns null until it is ready.
     */
    public MapRegion requestRegion(int rx, int rz) {
        long key = key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null || missing.contains(key)) {
            return region;
        }
        Future<MapRegion> pending = loading.get(key);
        if (pending == null) {
            startRead(key, rx, rz, "draw");
            return null;
        }
        if (!pending.isDone()) {
            return null;
        }
        loading.remove(key);
        region = await(pending);
        FlatLog.picked(label, rx, rz, region != null, "draw");
        if (region == null) {
            missing.add(key);
        } else {
            regions.put(key, region);
        }
        return region;
    }

    /**
     * Makes the region ready to be written to without blocking the game: true if it is in memory (or doesn't exist
     * yet and will be created), false while it is still being read from disk in the background.
     */
    public boolean prepareRegion(int rx, int rz) {
        long key = key(rx, rz);
        if (regions.containsKey(key) || missing.contains(key)) {
            return true;
        }
        Future<MapRegion> pending = loading.get(key);
        if (pending == null) {
            startRead(key, rx, rz, "scan");
            return false;
        }
        // A finished read is picked up by getRegion without waiting.
        return pending.isDone();
    }

    private void startRead(long key, int rx, int rz, String why) {
        final File dir = directory;
        final String name = label;
        loading.put(key, loadExecutor.submit(() -> readFile(dir, rx, rz, name)));
        FlatLog.readAsked(label, rx, rz, why, loading.size() + lodLoading.size());
    }

    /** How often a reduced copy of a region that keeps changing (around the player) is rebuilt. */
    private static final long LOD_REBUILD_MS = 1000;

    /**
     * Non-blocking lookup of the reduced copy for drawing zoomed out: built from the region if it is in memory,
     * otherwise read from its file in the background (only the reduced copy is kept). Null until ready or if the
     * region doesn't exist.
     */
    public LodTile requestLod(int rx, int rz) {
        long key = key(rx, rz);
        LodTile tile = lods.get(key);
        MapRegion region = regions.get(key);
        if (region != null) {
            if (tile == null) {
                long start = System.nanoTime();
                tile = new LodTile();
                tile.update(region);
                lods.put(key, tile);
                FlatLog.lod(label, rx, rz, "region in memory", System.nanoTime() - start);
            } else if (tile.sourceChanges != region.getChanges()
                && System.currentTimeMillis() - tile.builtAt >= LOD_REBUILD_MS) {
                    long start = System.nanoTime();
                    tile.update(region);
                    FlatLog.lod(label, rx, rz, "region changed", System.nanoTime() - start);
                }
            return tile;
        }
        if (tile != null || missing.contains(key)) {
            return tile;
        }
        Future<LodTile> pending = lodLoading.get(key);
        if (pending == null) {
            final File dir = directory;
            final String name = label;
            lodLoading.put(key, loadExecutor.submit(() -> {
                MapRegion read = readFile(dir, rx, rz, name);
                if (read == null) {
                    return null;
                }
                long start = System.nanoTime();
                LodTile built = LodTile.of(read.pixelArray(), read.extraArray());
                FlatLog.lod(name, rx, rz, "file", System.nanoTime() - start);
                return built;
            }));
            FlatLog.readAsked(label, rx, rz, "reduced", loading.size() + lodLoading.size());
            return null;
        }
        if (!pending.isDone()) {
            return null;
        }
        lodLoading.remove(key);
        try {
            tile = pending.get();
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region", e);
            tile = null;
        }
        FlatLog.picked(label, rx, rz, tile != null, "reduced");
        if (tile == null) {
            missing.add(key);
        } else {
            lods.put(key, tile);
        }
        return tile;
    }

    private static MapRegion await(Future<MapRegion> future) {
        try {
            return future.get();
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region", e);
            return null;
        }
    }

    private static MapRegion readFile(File directory, int rx, int rz, String label) {
        long start = System.nanoTime();
        File file = MapRegion.getFile(directory, rx, rz);
        if (!file.isFile()) {
            if (FlatLog.on()) {
                FlatLog.read(label, rx, rz, System.nanoTime() - start, 0, "-", "NO_FILE");
            }
            return null;
        }
        try {
            MapRegion region = MapRegion.read(file, rx, rz);
            if (FlatLog.on()) {
                FlatLog.read(label, rx, rz, System.nanoTime() - start, MapRegion.bytesOnDisk(file),
                    MapRegion.partsOnDisk(file), "OK");
            }
            return region;
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region " + file, e);
            if (FlatLog.on()) {
                FlatLog.read(label, rx, rz, System.nanoTime() - start, MapRegion.bytesOnDisk(file),
                    MapRegion.partsOnDisk(file), "FAILED " + e);
            }
            return null;
        }
    }

    /** Queues all modified regions for writing on the given executor. */
    public List<Future<?>> save(ExecutorService executor) {
        List<Future<?>> futures = new ArrayList<>();
        long copyTotal = 0;
        final String name = label;
        for (MapRegion region : regions.values()) {
            if (!region.isSaveDirty()) {
                continue;
            }
            final int changed = region.getChanges() - region.changesAtSave;
            region.changesAtSave = region.getChanges();
            long copyStart = System.nanoTime();
            final MapRegion.Snapshot data = region.snapshotForSave();
            long copy = System.nanoTime() - copyStart;
            copyTotal += copy;
            FlatLog.saveCopy(label, region.rx, region.rz, copy);
            final File file = MapRegion.getFile(directory, region.rx, region.rz);
            final long queued = System.nanoTime();
            futures.add(executor.submit(() -> {
                long start = System.nanoTime();
                String result = "OK";
                try {
                    MapRegion.write(file, data);
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Could not save map region " + file, e);
                    result = "FAILED " + e;
                } finally {
                    region.onSaved();
                }
                if (FlatLog.on()) {
                    FlatLog.saved(name, region.rx, region.rz, System.nanoTime() - start, MapRegion.bytesOnDisk(file),
                        MapRegion.partsOnDisk(file), result + " queuedMs="
                        + FlatLog.ms(start - queued)
                        + " changesSinceLastSave="
                        + changed);
                }
            }));
        }
        FlatLog.saveRound(label, futures.size(), copyTotal);
        return futures;
    }

    /** Releases everything farther than {@code radius} regions from the given one (see {@link #retain}). */
    public void trim(int centerRx, int centerRz, int radius) {
        retain((rx, rz) -> Math.abs(rx - centerRx) <= radius && Math.abs(rz - centerRz) <= radius);
    }

    /**
     * Frees everything the filter doesn't keep: textures and reduced copies, regions that are saved (they are read
     * again when needed) and background reads that nobody waits for anymore. Changed regions stay until saved.
     */
    public void retain(RegionFilter filter) {
        int freed = 0, keptUnsaved = 0, textures = 0, lodsFreed = 0;
        int cancelled = loading.size() + lodLoading.size();
        Iterator<MapRegion> it = regions.values()
            .iterator();
        while (it.hasNext()) {
            MapRegion region = it.next();
            if (filter.keep(region.rx, region.rz)) {
                continue;
            }
            if (region.hasTexture()) {
                textures++;
            }
            region.deleteTexture();
            if (!region.isSaveDirty() && !region.isSaving()) {
                it.remove();
                freed++;
            } else {
                keptUnsaved++;
            }
        }
        Iterator<Map.Entry<Long, LodTile>> lodIt = lods.entrySet()
            .iterator();
        while (lodIt.hasNext()) {
            Map.Entry<Long, LodTile> entry = lodIt.next();
            if (!filter.keep(rx(entry.getKey()), rz(entry.getKey()))) {
                entry.getValue()
                    .deleteTexture();
                lodIt.remove();
                lodsFreed++;
            }
        }
        dropPending(loading, filter);
        dropPending(lodLoading, filter);
        cancelled -= loading.size() + lodLoading.size();
        FlatLog.retained(label, freed, keptUnsaved, textures, lodsFreed, cancelled, regions.size());
    }

    /** Cancels background reads outside the filter; a read already running just finishes and is dropped. */
    private static <T> void dropPending(Map<Long, Future<T>> pending, RegionFilter filter) {
        Iterator<Map.Entry<Long, Future<T>>> it = pending.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Future<T>> entry = it.next();
            if (!filter.keep(rx(entry.getKey()), rz(entry.getKey()))) {
                entry.getValue()
                    .cancel(false);
                it.remove();
            }
        }
    }

    private static int rx(long key) {
        return (int) (key >> 32);
    }

    private static int rz(long key) {
        return (int) key;
    }

    /** For the log's statistics: regions and reduced copies in memory, textures, background reads. */
    int regionCount() {
        return regions.size();
    }

    int lodCount() {
        return lods.size();
    }

    int textureCount() {
        int count = 0;
        for (MapRegion region : regions.values()) {
            if (region.hasTexture()) {
                count++;
            }
        }
        for (LodTile tile : lods.values()) {
            if (tile.hasTexture()) {
                count++;
            }
        }
        return count;
    }

    int pendingCount() {
        return loading.size() + lodLoading.size();
    }

    public void deleteTextures() {
        for (MapRegion region : regions.values()) {
            region.deleteTexture();
        }
        for (LodTile tile : lods.values()) {
            tile.deleteTexture();
        }
    }
}
