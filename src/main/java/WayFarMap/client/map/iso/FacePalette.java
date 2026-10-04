package WayFarMap.client.map.iso;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import WayFarMap.WayFarMap;

/**
 * Sprites of blocks as the game draws them in place, seen the way the 3D map looks at the world (see
 * {@link FaceRenderer}): connected textures, tile entities, pipes and cables, modded renderers. A sprite is 128x128
 * ARGB and covers two blocks of the projection plane around the block's center (64 pixels per block, the most
 * detailed level); a picture of a cube's side is 32x32 (its texture is 16x16). The same sprite is kept once (a wall
 * of Chisel blocks has only a few different ones), compressed in {@code <world>/iso-sprites.dat}; in memory are only
 * the ones drawn lately, up to {@link #MEMORY_BUDGET}, the others are read again when needed. Ids start at 1. Sprites
 * depend on the resource packs: another set of packs starts a new palette ({@link #generation}), and chunks with ids
 * of the old one are drawn from block icons until seen again.
 */
final class FacePalette {

    private static final int MAGIC = 0x5746503B; // "WFP;"
    /** Bytes before the first sprite: magic, resource packs, generation. */
    private static final int HEADER = 12;
    /** Pixels per side of a picture of a cube's side. */
    static final int FACE_SIZE = 32;
    /** Pixels per side of a sprite of a block that isn't a plain cube (two blocks wide). */
    static final int SPRITE_SIZE = 128;
    private static final int MAX_SIZE = 256;
    /** Bytes of sprites (with their reduced copies) kept in memory. */
    private static final long MEMORY_BUDGET = 96L << 20;

    /** A sprite with its reduced copies: full size, half ... 1x1. */
    static final class Sprite {

        final int size;
        final int[][] mips;

        private Sprite(int size) {
            this.size = size;
            this.mips = new int[Integer.numberOfTrailingZeros(size) + 1][];
        }

        /** Pixel at (u, v) in 0..1 of the copy with {@code size >> mip} pixels per side (the smallest at most). */
        int texel(double u, double v, int mip) {
            mip = Math.min(mip, mips.length - 1);
            int side = size >> mip;
            int x = (int) (u * side), y = (int) (v * side);
            if (x < 0 || y < 0 || x >= side || y >= side) {
                return 0;
            }
            return mips[mip][y * side + x];
        }

        /** Memory it takes, in bytes (about 4/3 of the full size). */
        long bytes() {
            return (long) size * size * 16 / 3;
        }

        static Sprite of(int[] pixels) {
            Sprite sprite = new Sprite(sideOf(pixels.length));
            sprite.mips[0] = pixels;
            for (int mip = 1; mip < sprite.mips.length; mip++) {
                int size = sprite.size >> mip;
                int[] source = sprite.mips[mip - 1];
                int[] target = new int[size * size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int row = y * 2 * size * 2 + x * 2;
                        target[y * size + x] = average(
                            source[row],
                            source[row + 1],
                            source[row + size * 2],
                            source[row + size * 2 + 1]);
                    }
                }
                sprite.mips[mip] = target;
            }
            return sprite;
        }

        /** Average colored by alpha, so see-through pixels don't darken it. */
        private static int average(int... colors) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int c : colors) {
                int alpha = c >>> 24;
                a += alpha;
                r += ((c >> 16) & 0xFF) * alpha;
                g += ((c >> 8) & 0xFF) * alpha;
                b += (c & 0xFF) * alpha;
            }
            if (a == 0) {
                return 0;
            }
            return (int) (a / colors.length) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
        }
    }

    /** Where a sprite is: in the file (offset of its compressed pixels), or only in memory until saved. */
    private static final class Entry {

        final long hash;
        final int side;
        long offset = -1;
        int length;
        /** The pixels until they are in the file. */
        int[] pixels;

        Entry(long hash, int side) {
            this.hash = hash;
            this.side = side;
        }
    }

    /**
     * Identifies this palette: chunks keep the generation their picture ids belong to, and ids of another one are not
     * used. Picked at random when a palette is started (not worked out from the resource packs): two worlds, or a
     * palette begun again, never share it, so ids can't be taken for pictures of another palette.
     */
    final int generation;
    /** The resource packs the pictures were taken with. */
    final int packs;
    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Integer> byHash = new HashMap<>();
    /** Sprites in memory by id; read without a lock (a missing one is just read again). */
    private volatile Sprite[] sprites = new Sprite[64];
    /** Ids in memory, oldest first, and the bytes they take. */
    private final ArrayDeque<Integer> loaded = new ArrayDeque<>();
    private long loadedBytes;
    /**
     * New sprites not in the file yet may take at most this much memory; beyond it none are added (those blocks are
     * drawn from their icons) until the file caught up.
     */
    private static final long UNSAVED_LIMIT = 256L << 20;
    /** Bytes of the pixels of sprites not in the file yet. */
    private volatile long unsavedBytes;

    /** Sprites already in the file, and where the last of them ends. */
    private int saved;
    private long fileEnd;
    private RandomAccessFile reader;

    private FacePalette(File file, int packs, int generation) {
        this.file = file;
        this.packs = packs;
        this.generation = generation;
    }

    /** The resource packs a palette is for. */
    static int packsOf(String cacheId) {
        return cacheId.hashCode() & 0x7FFFFFFF;
    }

    /** A generation no palette had before (0 is "none"). */
    private static int newGeneration() {
        return 1 + new Random().nextInt(Integer.MAX_VALUE - 1);
    }

    /** Reads the palette of the world, or starts a new one if it was made with other resource packs. */
    static FacePalette load(File worldDirectory, String cacheId) {
        int packs = packsOf(cacheId);
        File file = new File(worldDirectory, "iso-sprites.dat");
        if (!file.isFile()) {
            return new FacePalette(file, packs, newGeneration());
        }
        FacePalette palette;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long end = in.length();
            if (end < HEADER || in.readInt() != MAGIC || in.readInt() != packs) {
                // Other resource packs or an older kind of file: begin again.
                return new FacePalette(file, packs, newGeneration());
            }
            int generation = in.readInt();
            if (generation <= 0) {
                return new FacePalette(file, packs, newGeneration());
            }
            palette = new FacePalette(file, packs, generation);
            // Only the headers: the pixels are read when a sprite is drawn.
            while (in.getFilePointer() + 16 <= end) {
                long hash = in.readLong();
                int side = in.readInt();
                int length = in.readInt();
                long offset = in.getFilePointer();
                if (side <= 0 || side > MAX_SIZE
                    || Integer.bitCount(side) != 1
                    || length < 0
                    || offset + length > end) {
                    // Cut off while it was written: the rest is taken again.
                    break;
                }
                Entry entry = new Entry(hash, side);
                entry.offset = offset;
                entry.length = length;
                palette.add(entry);
                in.seek(offset + length);
            }
            palette.saved = palette.entries.size();
            palette.fileEnd = palette.saved == 0 ? HEADER
                : palette.entries.get(palette.saved - 1).offset + palette.entries.get(palette.saved - 1).length;
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read the 3D map's block sprites " + file, e);
            // A new palette: ids chunks have of the one in the file are not used.
            return new FacePalette(file, packs, newGeneration());
        }
        return palette;
    }

    /** Pixels per side of a square picture. */
    static int sideOf(int pixels) {
        int side = (int) Math.round(Math.sqrt(pixels));
        if (side * side != pixels || Integer.bitCount(side) != 1) {
            throw new IllegalArgumentException("Not a square picture: " + pixels);
        }
        return side;
    }

    private static long hash(int[] image) {
        long h = 0xCBF29CE484222325L ^ image.length;
        for (int pixel : image) {
            h = (h ^ pixel) * 0x100000001B3L;
        }
        return h;
    }

    private int add(Entry entry) {
        entries.add(entry);
        int id = entries.size();
        byHash.put(entry.hash, id);
        return id;
    }

    /** Id of a sprite with nothing on it: the block can't be seen from that side (0 means "no sprite"). */
    static final int EMPTY = -1;

    /** Id of the sprite, added if new; {@link #EMPTY} if nothing was drawn. */
    synchronized int idOf(int[] image) {
        // One pass: whether anything is on it, and a quick fingerprint (four hashes side by side, several times
        // faster than one over the whole picture) for the pictures already met in this game.
        int alpha = 0;
        long h0 = QUICK_SEED, h1 = QUICK_SEED + 1, h2 = QUICK_SEED + 2, h3 = QUICK_SEED + 3;
        int n = image.length, i = 0;
        for (; i + 3 < n; i += 4) {
            int p0 = image[i], p1 = image[i + 1], p2 = image[i + 2], p3 = image[i + 3];
            alpha |= p0 | p1 | p2 | p3;
            h0 = (h0 ^ p0) * 0x100000001B3L;
            h1 = (h1 ^ p1) * 0x100000001B3L;
            h2 = (h2 ^ p2) * 0x100000001B3L;
            h3 = (h3 ^ p3) * 0x100000001B3L;
        }
        for (; i < n; i++) {
            alpha |= image[i];
            h0 = (h0 ^ image[i]) * 0x100000001B3L;
        }
        if ((alpha >>> 24) == 0) {
            spritesEmpty++;
            return EMPTY;
        }
        long quick = mix(mix(mix(mix(n, h0), h1), h2), h3);
        Integer met = quickIds.get(quick);
        if (met != null) {
            spritesKnown++;
            return met;
        }
        int id = idOfSlow(image);
        if (id > 0) {
            quickIds.put(quick, id);
        }
        return id;
    }

    /** Start of the quick fingerprints' hashes (FNV's). */
    private static final long QUICK_SEED = 0xCBF29CE484222325L;
    /**
     * Ids of the pictures met in this game by their quick fingerprint: 64 bits over every pixel, as safe as the
     * slower hash the file keeps (which the pictures found here are looked up by the first time).
     */
    private final Map<Long, Integer> quickIds = new HashMap<>();

    /** Mixes a value into a 64-bit hash, every bit of it reaching every bit of the result. */
    private static long mix(long hash, long value) {
        long h = (hash ^ value) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        return h ^ h >>> 32;
    }

    /** {@link #idOf} for a picture with something on it, not met before in this game: by the file's hash. */
    private int idOfSlow(int[] image) {
        long hash = hash(image);
        Integer id = byHash.get(hash);
        if (id != null) {
            Entry known = entries.get(id - 1);
            // In the file only its 64-bit hash is known, which is as good as the pixels.
            if (known.pixels == null ? known.side * known.side == image.length : Arrays.equals(known.pixels, image)) {
                spritesKnown++;
                return id;
            }
        }
        if (id != null) {
            // Same hash, other pixels: rare, stored as a new sprite.
            IsoLog.log("SPRITE_HASH_CLASH hash=" + hash);
        }
        if (unsavedBytes > UNSAVED_LIMIT) {
            // The file hasn't caught up: no new sprites until it has, rather than run out of memory.
            spritesRefused++;
            return 0;
        }
        spritesNew++;
        Entry entry = new Entry(hash, sideOf(image.length));
        entry.pixels = image.clone();
        unsavedBytes += image.length * 4L;
        return add(entry);
    }

    /** For the log: sprites taken that were new, already known, empty, refused (palette full). */
    int spritesNew, spritesKnown, spritesEmpty, spritesRefused;

    /** Sprites in the palette (for the log). */
    synchronized int size() {
        return entries.size();
    }

    /** Whether no new sprites are taken until the file caught up (for the log). */
    boolean full() {
        return unsavedBytes > UNSAVED_LIMIT;
    }

    /** Memory taken by sprites not in the file yet, in bytes. */
    long unsavedBytes() {
        return unsavedBytes;
    }

    /** Whether the id is one of this palette's sprites. */
    synchronized boolean has(int id) {
        return id > 0 && id <= entries.size();
    }

    /** The sprite with its reduced copies, or null for an unknown id. Any thread. */
    Sprite sprite(int id) {
        Sprite[] cache = sprites;
        if (id > 0 && id < cache.length) {
            Sprite sprite = cache[id];
            if (sprite != null) {
                return sprite;
            }
        }
        Entry entry;
        int[] pixels;
        synchronized (this) {
            if (id <= 0 || id > entries.size()) {
                return null;
            }
            cache = sprites;
            if (id < cache.length && cache[id] != null) {
                return cache[id];
            }
            entry = entries.get(id - 1);
            pixels = entry.pixels;
        }
        if (pixels == null) {
            pixels = read(entry);
            if (pixels == null) {
                return null;
            }
        }
        Sprite sprite = Sprite.of(pixels);
        synchronized (this) {
            cache = sprites;
            if (id >= cache.length) {
                cache = Arrays.copyOf(cache, Math.max(id + 1, cache.length * 2));
            }
            if (cache[id] != null) {
                return cache[id];
            }
            cache[id] = sprite;
            loaded.add(id);
            loadedBytes += sprite.bytes();
            // The ones loaded longest ago are let go (read again from the file if needed); those not in the file
            // yet, and this one, stay.
            for (int checks = loaded.size(); loadedBytes > MEMORY_BUDGET && checks > 0; checks--) {
                int old = loaded.poll();
                Sprite dropped = cache[old];
                if (dropped == null) {
                    continue;
                }
                if (old != id && entries.get(old - 1).pixels == null) {
                    cache[old] = null;
                    loadedBytes -= dropped.bytes();
                } else {
                    loaded.add(old);
                }
            }
            sprites = cache;
        }
        return sprite;
    }

    /** The sprite's pixels from the file, or null if they can't be read. */
    private int[] read(Entry entry) {
        byte[] compressed = new byte[entry.length];
        synchronized (file) {
            try {
                if (reader == null) {
                    reader = new RandomAccessFile(file, "r");
                }
                reader.seek(entry.offset);
                reader.readFully(compressed);
            } catch (IOException e) {
                WayFarMap.LOG.debug("Could not read a 3D map sprite", e);
                return null;
            }
        }
        byte[] raw = new byte[entry.side * entry.side * 4];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int n = 0;
            while (n < raw.length && !inflater.finished()) {
                int got = inflater.inflate(raw, n, raw.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                n += got;
            }
            if (n != raw.length) {
                return null;
            }
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
        int[] pixels = new int[entry.side * entry.side];
        ByteBuffer.wrap(raw)
            .asIntBuffer()
            .get(pixels);
        return pixels;
    }

    private static byte[] compress(int[] pixels) {
        ByteBuffer raw = ByteBuffer.allocate(pixels.length * 4);
        raw.asIntBuffer()
            .put(pixels);
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(raw.array());
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length);
            byte[] buffer = new byte[1 << 14];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Appends the new sprites to the file (background thread). */
    void save() {
        List<Entry> added;
        int from;
        long end;
        synchronized (this) {
            from = saved;
            end = fileEnd;
            if (from == entries.size()) {
                return;
            }
            added = new ArrayList<>(entries.subList(from, entries.size()));
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            long[] offsets = new long[added.size()];
            int[] lengths = new int[added.size()];
            synchronized (file) {
                try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                    if (end < HEADER) {
                        out.setLength(0);
                        out.writeInt(MAGIC);
                        out.writeInt(packs);
                        out.writeInt(generation);
                    } else {
                        // After the last whole sprite (anything after it was cut off while written).
                        out.seek(end);
                    }
                    for (int i = 0; i < added.size(); i++) {
                        Entry entry = added.get(i);
                        byte[] compressed = compress(entry.pixels);
                        out.writeLong(entry.hash);
                        out.writeInt(entry.side);
                        out.writeInt(compressed.length);
                        offsets[i] = out.getFilePointer();
                        lengths[i] = compressed.length;
                        out.write(compressed);
                    }
                    end = out.getFilePointer();
                    out.setLength(end);
                }
                if (reader != null) {
                    // It sees the file as it was opened; opened again when next needed.
                    reader.close();
                    reader = null;
                }
            }
            synchronized (this) {
                for (int i = 0; i < added.size(); i++) {
                    Entry entry = added.get(i);
                    entry.offset = offsets[i];
                    entry.length = lengths[i];
                    // In the file now: memory can let it go.
                    unsavedBytes -= entry.pixels.length * 4L;
                    entry.pixels = null;
                }
                saved = from + added.size();
                fileEnd = end;
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save the 3D map's block sprites " + file, e);
        }
    }
}
