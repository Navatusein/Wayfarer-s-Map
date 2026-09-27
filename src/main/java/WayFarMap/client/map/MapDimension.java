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

    public MapDimension(int dimensionId, File directory, ExecutorService loadExecutor) {
        this.dimensionId = dimensionId;
        this.directory = directory;
        this.loadExecutor = loadExecutor;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
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

    /**
     * Releases regions farther than {@code radius} regions from the given one: their textures are freed, and saved
     * regions are dropped from memory (they get reloaded from disk when needed).
     */
    public void trim(int centerRx, int centerRz, int radius) {
        Iterator<MapRegion> it = regions.values()
            .iterator();
        while (it.hasNext()) {
            MapRegion region = it.next();
            if (Math.abs(region.rx - centerRx) <= radius && Math.abs(region.rz - centerRz) <= radius) {
                continue;
            }
            region.deleteTexture();
            if (!region.isSaveDirty() && !region.isSaving()) {
                it.remove();
            }
        }
    }

    public void deleteTextures() {
        for (MapRegion region : regions.values()) {
            region.deleteTexture();
        }
    }
}
