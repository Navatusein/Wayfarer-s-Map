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

    public MapDimension(int dimensionId, File directory, ExecutorService loadExecutor) {
        this.dimensionId = dimensionId;
        this.directory = directory;
        this.loadExecutor = loadExecutor;
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
        if (pending != null) {
            region = await(pending);
        } else if (!missing.contains(key)) {
            region = readFile(directory, rx, rz);
        }
        if (region == null) {
            missing.add(key);
            if (!create) {
                return null;
            }
            region = new MapRegion(rx, rz);
            missing.remove(key);
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
            final File dir = directory;
            loading.put(key, loadExecutor.submit(() -> readFile(dir, rx, rz)));
            return null;
        }
        if (!pending.isDone()) {
            return null;
        }
        loading.remove(key);
        region = await(pending);
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
            final File dir = directory;
            loading.put(key, loadExecutor.submit(() -> readFile(dir, rx, rz)));
            return false;
        }
        // A finished read is picked up by getRegion without waiting.
        return pending.isDone();
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
                tile = new LodTile();
                tile.update(region);
                lods.put(key, tile);
            } else if (tile.sourceChanges != region.getChanges()
                && System.currentTimeMillis() - tile.builtAt >= LOD_REBUILD_MS) {
                tile.update(region);
            }
            return tile;
        }
        if (tile != null || missing.contains(key)) {
            return tile;
        }
        Future<LodTile> pending = lodLoading.get(key);
        if (pending == null) {
            final File dir = directory;
            lodLoading.put(key, loadExecutor.submit(() -> {
                MapRegion read = readFile(dir, rx, rz);
                return read == null ? null : LodTile.of(read.pixelArray(), read.extraArray());
            }));
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

    private static MapRegion readFile(File directory, int rx, int rz) {
        File file = MapRegion.getFile(directory, rx, rz);
        if (!file.isFile()) {
            return null;
        }
        try {
            return MapRegion.read(file, rx, rz);
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region " + file, e);
            return null;
        }
    }

    /** Queues all modified regions for writing on the given executor. */
    public List<Future<?>> save(ExecutorService executor) {
        List<Future<?>> futures = new ArrayList<>();
        for (MapRegion region : regions.values()) {
            if (!region.isSaveDirty()) {
                continue;
            }
            final MapRegion.Snapshot data = region.snapshotForSave();
            final File file = MapRegion.getFile(directory, region.rx, region.rz);
            futures.add(executor.submit(() -> {
                try {
                    MapRegion.write(file, data);
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Could not save map region " + file, e);
                } finally {
                    region.onSaved();
                }
            }));
        }
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
        Iterator<MapRegion> it = regions.values()
            .iterator();
        while (it.hasNext()) {
            MapRegion region = it.next();
            if (filter.keep(region.rx, region.rz)) {
                continue;
            }
            region.deleteTexture();
            if (!region.isSaveDirty() && !region.isSaving()) {
                it.remove();
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
            }
        }
        dropPending(loading, filter);
        dropPending(lodLoading, filter);
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

    public void deleteTextures() {
        for (MapRegion region : regions.values()) {
            region.deleteTexture();
        }
        for (LodTile tile : lods.values()) {
            tile.deleteTexture();
        }
    }
}
