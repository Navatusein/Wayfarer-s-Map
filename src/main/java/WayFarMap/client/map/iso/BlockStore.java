package WayFarMap.client.map.iso;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import WayFarMap.WayFarMap;

/**
 * The blocks of the explored chunks of one dimension, for the 3D map: {@code dim<id>/blocks/r.X.Z.wfb}, one file per
 * 32x32 chunks (the regions of the flat map). Chunks are kept compressed; the ones being drawn are unpacked into a
 * small cache. Written by the writer and saver threads of {@link IsoMap}, read by the tile renderers.
 */
public final class BlockStore {

    private static final int MAGIC = 0x57464231; // "WFB1"
    private static final int CHUNKS = 32;
    /**
     * Compressed chunks kept in memory before regions that weren't used lately are dropped: a 32nd of the game's
     * memory, 96 to 384 MB. Too little and flying back and forth reads the same region files again and again.
     */
    private static final long BLOB_BUDGET = Math.max(
        96L << 20,
        Math.min(
            384L << 20,
            Runtime.getRuntime()
                .maxMemory() / 32));
    /**
     * Unpacked chunks kept in memory, in ints: a 16th of the game's memory, 12M to 48M ints. Zoomed out, a tile passes
     * over thousands of chunks; too few kept and each tile unpacks them all again.
     */
    private static final long DECODED_BUDGET = Math.max(
        12L << 20,
        Math.min(
            48L << 20,
            Runtime.getRuntime()
                .maxMemory() / 64));

    public interface Listener {

        /** A chunk's blocks changed; {@code top} is the highest block it had before or has now. */
        void chunkChanged(BlockStore store, int chunkX, int chunkZ, int top);
    }

    public final int dimension;
    private final File directory;
    private final Listener listener;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();
    private long blobBytes;
    private final LinkedHashMap<Long, ChunkBlocks> decoded = new LinkedHashMap<>(256, 0.75f, true);
    private long decodedWeight;

    BlockStore(int dimension, File dimensionDirectory, Listener listener) {
        this.dimension = dimension;
        this.directory = new File(dimensionDirectory, "blocks");
        this.listener = listener;
    }

    private static final class Region {

        final int rx, rz;
        final long[] times = new long[CHUNKS * CHUNKS];
        final short[] yMin = new short[CHUNKS * CHUNKS];
        /** Highest block per chunk, -1 for chunks without blocks. */
        final short[] yMax = new short[CHUNKS * CHUNKS];
        final int[] lengths = new int[CHUNKS * CHUNKS];
        /** Compressed chunks; null until read from the file. */
        byte[][] blobs;
        boolean fileExists;
        boolean dirty;
        /**
         * Being written to its file (by the saver, while the writer keeps storing chunks): its chunks stay in memory,
         * the file may not have them yet.
         */
        boolean saving;
        long lastUsed;

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
            Arrays.fill(yMax, (short) -1);
        }

        int bytes() {
            int sum = 0;
            for (int length : lengths) {
                sum += length;
            }
            return sum;
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private File file(int rx, int rz) {
        return new File(directory, "r." + rx + "." + rz + ".wfb");
    }

    /** The region's chunk list, read from its file on first use. */
    private Region region(int rx, int rz) {
        long key = key(rx, rz);
        Region region = regions.get(key);
        if (region != null) {
            return region;
        }
        Region read = readHeader(rx, rz);
        region = regions.putIfAbsent(key, read);
        return region != null ? region : read;
    }

    private Region readHeader(int rx, int rz) {
        Region region = new Region(rx, rz);
        File file = file(rx, rz);
        long start = System.nanoTime();
        if (!file.isFile()) {
            IsoLog.regionRead(dimension, rx, rz, "header (no file)", System.nanoTime() - start, 0);
            return region;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            readHeader(in, region);
            region.fileExists = true;
            IsoLog.regionRead(dimension, rx, rz, "header", System.nanoTime() - start, file.length());
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read 3D map data " + file, e);
            Arrays.fill(region.yMax, (short) -1);
            Arrays.fill(region.times, 0L);
        }
        return region;
    }

    private static void readHeader(DataInputStream in, Region region) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a 3D map region");
        }
        for (int i = 0; i < CHUNKS * CHUNKS; i++) {
            region.times[i] = in.readLong();
            region.yMin[i] = in.readShort();
            region.yMax[i] = in.readShort();
            region.lengths[i] = in.readInt();
        }
    }

    /**
     * Reads the compressed chunks of the region if they aren't in memory. Call holding the region's lock, and
     * {@link #trimBlobs} after letting it go if this returns true.
     */
    private boolean loadBlobs(Region region) {
        region.lastUsed = System.nanoTime();
        if (region.blobs != null) {
            return false;
        }
        byte[][] blobs = new byte[CHUNKS * CHUNKS][];
        long start = System.nanoTime();
        if (region.fileExists) {
            File file = file(region.rx, region.rz);
            try (
                DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
                Region check = new Region(region.rx, region.rz);
                readHeader(in, check);
                for (int i = 0; i < blobs.length; i++) {
                    if (check.lengths[i] > 0) {
                        blobs[i] = new byte[check.lengths[i]];
                        in.readFully(blobs[i]);
                    }
                }
            } catch (IOException e) {
                WayFarMap.LOG.warn("Could not read 3D map data " + file, e);
            }
        }
        region.blobs = blobs;
        synchronized (this) {
            blobBytes += region.bytes();
        }
        if (region.fileExists) {
            IsoLog.regionRead(dimension, region.rx, region.rz, "blobs", System.nanoTime() - start, region.bytes());
        }
        return true;
    }

    /** Drops the compressed chunks of regions not used lately (and saved) while over the memory budget. */
    private void trimBlobs(Region keep) {
        List<Region> loaded = new ArrayList<>();
        long before;
        synchronized (this) {
            if (blobBytes <= BLOB_BUDGET) {
                return;
            }
            before = blobBytes;
        }
        long start = System.nanoTime();
        int dropped = 0;
        try {
            dropped = trimBlobs(keep, loaded);
        } finally {
            long after;
            synchronized (this) {
                after = blobBytes;
            }
            IsoLog.regionTrimmed(dimension, dropped, before, after, System.nanoTime() - start);
        }
    }

    private int trimBlobs(Region keep, List<Region> loaded) {
        int dropped = 0;
        for (Region region : regions.values()) {
            if (region != keep && region.blobs != null && !region.dirty && !region.saving) {
                loaded.add(region);
            }
        }
        loaded.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
        for (Region region : loaded) {
            synchronized (this) {
                if (blobBytes <= BLOB_BUDGET * 3 / 4) {
                    return dropped;
                }
            }
            synchronized (region) {
                if (region.blobs != null && !region.dirty && !region.saving) {
                    region.blobs = null;
                    dropped++;
                    synchronized (this) {
                        blobBytes -= region.bytes();
                    }
                }
            }
        }
        return dropped;
    }

    // ---------------------------------------------------------------- reading (tile renderers)

    /** Highest block of the chunk, or -1 if there are no blocks for it. */
    public int top(int chunkX, int chunkZ) {
        Region region = region(chunkX >> 5, chunkZ >> 5);
        return region.yMax[(chunkZ & 31) * CHUNKS + (chunkX & 31)];
    }

    /** Lowest height kept of the chunk (0 for a chunk kept whole), meaningless if there are no blocks for it. */
    public int bottom(int chunkX, int chunkZ) {
        Region region = region(chunkX >> 5, chunkZ >> 5);
        return region.yMin[(chunkZ & 31) * CHUNKS + (chunkX & 31)];
    }

    /** When the chunk's blocks last changed, 0 if there are none. */
    public long time(int chunkX, int chunkZ) {
        Region region = region(chunkX >> 5, chunkZ >> 5);
        return region.times[(chunkZ & 31) * CHUNKS + (chunkX & 31)];
    }

    /** The chunk's blocks, or null if there are none (or they can't be read). May read from disk. */
    public ChunkBlocks chunk(int chunkX, int chunkZ) {
        long key = key(chunkX, chunkZ);
        synchronized (decoded) {
            ChunkBlocks cached = decoded.get(key);
            if (cached != null) {
                IsoLog.decodedHits.incrementAndGet();
                return cached;
            }
        }
        Region region = region(chunkX >> 5, chunkZ >> 5);
        int index = (chunkZ & 31) * CHUNKS + (chunkX & 31);
        byte[] blob;
        boolean loaded;
        synchronized (region) {
            if (region.yMax[index] < 0) {
                return null;
            }
            loaded = loadBlobs(region);
            blob = region.blobs[index];
        }
        if (loaded) {
            trimBlobs(region);
        }
        if (blob == null) {
            return null;
        }
        ChunkBlocks blocks;
        long decodeStart = System.nanoTime();
        try {
            blocks = ChunkBlocks.decode(blob);
        } catch (IOException e) {
            IsoLog.log("DECODE_FAILED " + chunkX + "," + chunkZ + " " + e);
            return null;
        }
        IsoLog.decodedMisses.incrementAndGet();
        IsoLog.decodeNanos.addAndGet(System.nanoTime() - decodeStart);
        IsoLog.tileDecoded(System.nanoTime() - decodeStart);
        synchronized (decoded) {
            // A newer version may have been put meanwhile; it replaced the key, so only fill an empty slot.
            if (!decoded.containsKey(key) && blobIsCurrent(region, index, blob)) {
                decoded.put(key, blocks);
                decodedWeight += blocks.weight();
                Iterator<ChunkBlocks> it = decoded.values()
                    .iterator();
                while (decodedWeight > DECODED_BUDGET && it.hasNext()) {
                    decodedWeight -= it.next()
                        .weight();
                    it.remove();
                }
            }
        }
        return blocks;
    }

    private static boolean blobIsCurrent(Region region, int index, byte[] blob) {
        synchronized (region) {
            return region.blobs != null && region.blobs[index] == blob;
        }
    }

    // ---------------------------------------------------------------- writing (writer and saver threads of IsoMap)

    /**
     * Stores the chunk's blocks; nothing happens (not even the time changes) if they are the same as before.
     *
     * @param timing for the log, filled in: nanos reading the region header, reading its chunks, encoding, trimming;
     *               bytes; 1 if changed; nanos waiting for the region's lock
     */
    void put(int chunkX, int chunkZ, ChunkBlocks blocks, long[] timing) {
        long t0 = System.nanoTime();
        byte[] blob = blocks.encode();
        long t1 = System.nanoTime();
        Region region = region(chunkX >> 5, chunkZ >> 5);
        long t2 = System.nanoTime();
        timing[2] = t1 - t0;
        timing[0] = t2 - t1;
        timing[4] = blob.length;
        int index = (chunkZ & 31) * CHUNKS + (chunkX & 31);
        int oldTop = -1;
        boolean loaded, changed = false;
        synchronized (region) {
            long t3 = System.nanoTime();
            timing[6] = t3 - t2;
            loaded = loadBlobs(region);
            timing[1] = System.nanoTime() - t3;
            byte[] old = region.blobs[index];
            if (old == null || !Arrays.equals(old, blob)) {
                changed = true;
                oldTop = region.yMax[index];
                region.blobs[index] = blob;
                region.yMin[index] = (short) blocks.yMin;
                region.yMax[index] = (short) blocks.yMax;
                int oldLength = region.lengths[index];
                region.lengths[index] = blob.length;
                region.times[index] = System.currentTimeMillis();
                region.dirty = true;
                synchronized (this) {
                    blobBytes += blob.length - oldLength;
                }
            }
        }
        if (loaded) {
            long t4 = System.nanoTime();
            trimBlobs(region);
            timing[3] = System.nanoTime() - t4;
        }
        timing[5] = changed ? 1 : 0;
        if (!changed) {
            return;
        }
        synchronized (decoded) {
            ChunkBlocks old = decoded.remove(key(chunkX, chunkZ));
            if (old != null) {
                decodedWeight -= old.weight();
            }
        }
        listener.chunkChanged(this, chunkX, chunkZ, Math.max(oldTop, blocks.yMax));
    }

    /**
     * Forgets the chunk's blocks (writer thread of IsoMap): the 3D map shows it empty, as a chunk never recorded.
     *
     * @return whether there were blocks of it
     */
    boolean remove(int chunkX, int chunkZ) {
        Region region = region(chunkX >> 5, chunkZ >> 5);
        int index = (chunkZ & 31) * CHUNKS + (chunkX & 31);
        int oldTop;
        boolean loaded;
        synchronized (region) {
            oldTop = region.yMax[index];
            if (oldTop < 0) {
                return false;
            }
            loaded = loadBlobs(region);
            int oldLength = region.lengths[index];
            region.blobs[index] = null;
            region.yMin[index] = 0;
            region.yMax[index] = -1;
            region.lengths[index] = 0;
            region.times[index] = 0;
            region.dirty = true;
            synchronized (this) {
                blobBytes -= oldLength;
            }
        }
        if (loaded) {
            trimBlobs(region);
        }
        synchronized (decoded) {
            ChunkBlocks old = decoded.remove(key(chunkX, chunkZ));
            if (old != null) {
                decodedWeight -= old.weight();
            }
        }
        listener.chunkChanged(this, chunkX, chunkZ, oldTop);
        return true;
    }

    /** Writes every changed region (saver thread of IsoMap, while its writer keeps storing chunks). */
    void save() {
        for (Region region : regions.values()) {
            long[] times;
            short[] yMin, yMax;
            int[] lengths;
            byte[][] blobs;
            long copyStart = System.nanoTime();
            synchronized (region) {
                if (!region.dirty) {
                    continue;
                }
                region.dirty = false;
                region.saving = true;
                times = region.times.clone();
                yMin = region.yMin.clone();
                yMax = region.yMax.clone();
                lengths = region.lengths.clone();
                blobs = region.blobs.clone();
            }
            File file = file(region.rx, region.rz);
            long writeStart = System.nanoTime();
            long bytes = 0;
            for (byte[] blob : blobs) {
                bytes += blob == null ? 0 : blob.length;
            }
            try {
                write(file, times, yMin, yMax, lengths, blobs);
                synchronized (region) {
                    region.fileExists = true;
                    region.saving = false;
                }
                IsoLog.regionSaved(
                    dimension,
                    region.rx,
                    region.rz,
                    bytes,
                    writeStart - copyStart,
                    System.nanoTime() - writeStart,
                    true);
            } catch (IOException e) {
                IsoLog.regionSaved(
                    dimension,
                    region.rx,
                    region.rz,
                    bytes,
                    writeStart - copyStart,
                    System.nanoTime() - writeStart,
                    false);
                WayFarMap.LOG.warn("Could not save 3D map data " + file, e);
                synchronized (region) {
                    region.dirty = true;
                    region.saving = false;
                }
            }
        }
        trimBlobs(null);
    }

    private static void write(File file, long[] times, short[] yMin, short[] yMax, int[] lengths, byte[][] blobs)
        throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC);
            for (int i = 0; i < times.length; i++) {
                out.writeLong(times[i]);
                out.writeShort(yMin[i]);
                out.writeShort(yMax[i]);
                out.writeInt(blobs[i] == null ? 0 : lengths[i]);
            }
            for (byte[] blob : blobs) {
                if (blob != null) {
                    out.write(blob);
                }
            }
        }
        // In one step where the file system can: a crash never leaves the region without its file.
        try {
            Files
                .move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
