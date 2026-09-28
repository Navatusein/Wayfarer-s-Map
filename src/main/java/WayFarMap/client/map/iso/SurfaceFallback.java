package WayFarMap.client.map.iso;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import WayFarMap.client.map.MapRegion;

/**
 * The flat surface map as a height field, for chunks the 3D map has no blocks of: explored before it existed, or
 * mapped by a teammate. Each column is a pillar of its map color up to the height to stand on. Read from the saved
 * files (tile renderers must not touch the live map).
 */
final class SurfaceFallback {

    /** Full regions kept in memory (1.3 MB each). */
    private static final int MAX_REGIONS = 24;

    private final File directory;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    SurfaceFallback(File dimensionDirectory) {
        this.directory = dimensionDirectory;
    }

    private static final class Entry {

        final int rx, rz;
        /** When the region file was written; stands for the time of every chunk explored in it. */
        final long fileTime;
        /** Which chunks are explored. */
        final boolean[] explored = new boolean[1024];
        /** The region and its highest pillar per chunk (-1 if none); null until read or once dropped. */
        volatile Loaded loaded;
        long lastUsed;
        /** When the file's date was last compared, to notice the flat map being saved again. */
        volatile long checkedAt = System.currentTimeMillis();

        Entry(int rx, int rz, long fileTime) {
            this.rx = rx;
            this.rz = rz;
            this.fileTime = fileTime;
        }
    }

    private static final class Loaded {

        final MapRegion data;
        final short[] tops;

        Loaded(MapRegion data, short[] tops) {
            this.data = data;
            this.tops = tops;
        }
    }

    /** How often a region file's date is looked at again. */
    private static final long RECHECK_MS = 5000;

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private Entry entry(int rx, int rz) {
        long key = key(rx, rz);
        Entry entry = entries.get(key);
        if (entry != null && System.currentTimeMillis() - entry.checkedAt < RECHECK_MS) {
            return entry;
        }
        File file = MapRegion.getFile(directory, rx, rz);
        if (entry != null) {
            long now = System.currentTimeMillis();
            entry.checkedAt = now;
            if ((file.isFile() ? file.lastModified() : 0) == entry.fileTime) {
                return entry;
            }
            // Saved again (new chunks from a teammate): read it again.
            entries.remove(key, entry);
        }
        entry = new Entry(rx, rz, file.isFile() ? file.lastModified() : 0);
        if (entry.fileTime != 0) {
            File times = new File(directory, "r." + rx + "." + rz + ".time");
            boolean read = false;
            if (times.isFile()) {
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(times)))) {
                    for (int i = 0; i < 1024; i++) {
                        entry.explored[i] = in.readLong() != 0;
                    }
                    read = true;
                } catch (IOException e) {
                    // Treated as all explored below.
                }
            }
            if (!read) {
                Arrays.fill(entry.explored, true);
            }
        }
        Entry previous = entries.putIfAbsent(key, entry);
        return previous != null ? previous : entry;
    }

    /** Time the chunk's pillars count as made, 0 if it isn't on the flat map. */
    long time(int chunkX, int chunkZ) {
        Entry entry = entry(chunkX >> 5, chunkZ >> 5);
        return entry.explored[(chunkZ & 31) * 32 + (chunkX & 31)] ? entry.fileTime : 0;
    }

    /** The region with the chunk if the chunk is on the flat map, else null. May read the file. */
    MapRegion region(int chunkX, int chunkZ) {
        Entry entry = entry(chunkX >> 5, chunkZ >> 5);
        if (!entry.explored[(chunkZ & 31) * 32 + (chunkX & 31)]) {
            return null;
        }
        Loaded loaded = load(entry);
        return loaded == null ? null : loaded.data;
    }

    /** Highest pillar in the chunk, -1 if none. */
    int top(int chunkX, int chunkZ) {
        Entry entry = entry(chunkX >> 5, chunkZ >> 5);
        Loaded loaded = entry.explored[(chunkZ & 31) * 32 + (chunkX & 31)] ? load(entry) : null;
        return loaded == null ? -1 : loaded.tops[(chunkZ & 31) * 32 + (chunkX & 31)];
    }

    private Loaded load(Entry entry) {
        Loaded loaded;
        synchronized (entry) {
            entry.lastUsed = System.nanoTime();
            if (entry.loaded != null) {
                return entry.loaded;
            }
            if (entry.fileTime == 0) {
                return null;
            }
            MapRegion region;
            try {
                region = MapRegion.read(MapRegion.getFile(directory, entry.rx, entry.rz), entry.rx, entry.rz);
            } catch (IOException | RuntimeException e) {
                Arrays.fill(entry.explored, false);
                return null;
            }
            short[] tops = new short[1024];
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    int top = -1;
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        for (int x = cx * 16; x < cx * 16 + 16; x++) {
                            if ((region.getPixel(x, z) >>> 24) != 0) {
                                top = Math.max(top, region.getExtra(x, z) - 1);
                            }
                        }
                    }
                    tops[cz * 32 + cx] = (short) top;
                }
            }
            loaded = new Loaded(region, tops);
            entry.loaded = loaded;
        }
        trim(entry);
        return loaded;
    }

    private void trim(Entry keep) {
        List<Entry> loaded = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.loaded != null && entry != keep) {
                loaded.add(entry);
            }
        }
        if (loaded.size() < MAX_REGIONS) {
            return;
        }
        loaded.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
        for (int i = 0; i <= loaded.size() - MAX_REGIONS; i++) {
            Entry entry = loaded.get(i);
            entry.loaded = null;
        }
    }
}
