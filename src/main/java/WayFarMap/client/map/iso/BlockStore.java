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
 * small cache. Written by the background thread of {@link IsoMap}, read by the tile renderers.
 */
public final class BlockStore {

    private static final int MAGIC = 0x57464231; // "WFB1"
    private static final int CHUNKS = 32;
    /** Compressed chunks kept in memory before regions that weren't used lately are dropped. */
    private static final long BLOB_BUDGET = 96L << 20;
    /** Unpacked chunks kept in memory, in ints. */
    private static final long DECODED_BUDGET = 12L << 20;

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
        if (!file.isFile()) {
            return region;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            readHeader(in, region);
            region.fileExists = true;
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
        return true;
    }

    /** Drops the compressed chunks of regions not used lately (and saved) while over the memory budget. */
    private void trimBlobs(Region keep) {
        List<Region> loaded = new ArrayList<>();
        synchronized (this) {
            if (blobBytes <= BLOB_BUDGET) {
                return;
            }
        }
        for (Region region : regions.values()) {
            if (region != keep && region.blobs != null && !region.dirty) {
                loaded.add(region);
            }
        }
        loaded.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
        for (Region region : loaded) {
            synchronized (this) {
                if (blobBytes <= BLOB_BUDGET * 3 / 4) {
                    return;
                }
            }
            synchronized (region) {
                if (region.blobs != null && !region.dirty) {
                    region.blobs = null;
                    synchronized (this) {
                        blobBytes -= region.bytes();
                    }
                }
            }
        }
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
        try {
            blocks = ChunkBlocks.decode(blob);
        } catch (IOException e) {
            return null;
        }
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

    // ---------------------------------------------------------------- writing (background thread of IsoMap)

    /** Stores the chunk's blocks; nothing happens (not even the time changes) if they are the same as before. */
    void put(int chunkX, int chunkZ, ChunkBlocks blocks) {
        byte[] blob = blocks.encode();
        Region region = region(chunkX >> 5, chunkZ >> 5);
        int index = (chunkZ & 31) * CHUNKS + (chunkX & 31);
        int oldTop = -1;
        boolean loaded, changed = false;
        synchronized (region) {
            loaded = loadBlobs(region);
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
            trimBlobs(region);
        }
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

    /** Writes every changed region (background thread of IsoMap). */
    void save() {
        for (Region region : regions.values()) {
            long[] times;
            short[] yMin, yMax;
            int[] lengths;
            byte[][] blobs;
            synchronized (region) {
                if (!region.dirty) {
                    continue;
                }
                region.dirty = false;
                times = region.times.clone();
                yMin = region.yMin.clone();
                yMax = region.yMax.clone();
                lengths = region.lengths.clone();
                blobs = region.blobs.clone();
            }
            File file = file(region.rx, region.rz);
            try {
                write(file, times, yMin, yMax, lengths, blobs);
                synchronized (region) {
                    region.fileExists = true;
                }
            } catch (IOException e) {
                WayFarMap.LOG.warn("Could not save 3D map data " + file, e);
                synchronized (region) {
                    region.dirty = true;
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
