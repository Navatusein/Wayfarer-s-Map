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
    private final Map<Long, MapRegion> regions = new HashMap<>();
    /** Regions known to have no file on disk, so we don't probe the file system every frame. */
    private final Set<Long> missing = new HashSet<>();

    public MapDimension(int dimensionId, File directory) {
        this.dimensionId = dimensionId;
        this.directory = directory;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Region that is already in memory, or null. */
    public MapRegion getLoadedRegion(int rx, int rz) {
        return regions.get(key(rx, rz));
    }

    /**
     * Returns the region, loading it from disk if needed.
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
        if (!missing.contains(key)) {
            File file = MapRegion.getFile(directory, rx, rz);
            if (file.isFile()) {
                try {
                    region = MapRegion.read(file, rx, rz);
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Could not load map region " + file, e);
                }
            }
            if (region == null) {
                missing.add(key);
            }
        }
        if (region == null && create) {
            region = new MapRegion(rx, rz);
            missing.remove(key);
        }
        if (region != null) {
            regions.put(key, region);
        }
        return region;
    }

    /** Whether a region may exist but has not been loaded yet (i.e. loading it would touch the disk). */
    public boolean needsDiskLoad(int rx, int rz) {
        long key = key(rx, rz);
        return !regions.containsKey(key) && !missing.contains(key);
    }

    /** Queues all modified regions for writing on the given executor. */
    public List<Future<?>> save(ExecutorService executor) {
        List<Future<?>> futures = new ArrayList<>();
        for (MapRegion region : regions.values()) {
            if (!region.isSaveDirty()) {
                continue;
            }
            final int[] data = region.snapshotForSave();
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
