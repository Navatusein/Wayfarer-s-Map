package WayFarMap.client.map.iso;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import WayFarMap.WayFarMap;

/**
 * Sprites of blocks as the game draws them in place, seen the way the 3D map looks at the world (see
 * {@link FaceRenderer}): connected textures, tile entities, pipes and cables, modded renderers. A sprite is 32x32
 * ARGB and covers two blocks of the projection plane around the block's center (16 pixels per block, the most
 * detailed level). The same sprite is kept once (a wall of Chisel blocks has only a few different ones), in
 * {@code <world>/iso-sprites.dat}. Ids start at 1. Sprites depend on the resource packs: another set of packs starts a
 * new palette ({@link #generation}), and chunks with ids of the old one are drawn from block icons until seen again.
 */
final class FacePalette {

    private static final int MAGIC = 0x57465032; // "WFP2"
    static final int SIZE = 32;
    static final int PIXELS = SIZE * SIZE;

    /** A sprite with its reduced copies: 32x32, 16x16 ... 1x1. */
    static final class Sprite {

        final int[][] mips = new int[6][];

        /** Pixel at (u, v) in 0..1 of the copy with {@code 32 >> mip} pixels per side. */
        int texel(double u, double v, int mip) {
            int size = SIZE >> mip;
            int x = (int) (u * size), y = (int) (v * size);
            if (x < 0 || y < 0 || x >= size || y >= size) {
                return 0;
            }
            return mips[mip][y * size + x];
        }

        static Sprite of(int[] pixels) {
            Sprite sprite = new Sprite();
            sprite.mips[0] = pixels;
            for (int mip = 1; mip < sprite.mips.length; mip++) {
                int size = SIZE >> mip;
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

    final int generation;
    private final File file;
    private final List<int[]> images = new ArrayList<>();
    private final Map<Long, Integer> byHash = new HashMap<>();
    private volatile Sprite[] sprites = new Sprite[64];
    /** Sprites already in the file. */
    private int saved;

    private FacePalette(File file, int generation) {
        this.file = file;
        this.generation = generation;
    }

    /** Reads the palette of the world, or starts a new one if it was made with other resource packs. */
    static FacePalette load(File worldDirectory, String cacheId) {
        int generation = cacheId.hashCode() & 0x7FFFFFFF;
        FacePalette palette = new FacePalette(new File(worldDirectory, "iso-sprites.dat"), generation);
        if (!palette.file.isFile()) {
            return palette;
        }
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new FileInputStream(palette.file), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != generation) {
                // Other resource packs: begin again.
                return palette;
            }
            while (true) {
                int[] image = new int[PIXELS];
                try {
                    for (int i = 0; i < PIXELS; i++) {
                        image[i] = in.readInt();
                    }
                } catch (EOFException e) {
                    break;
                }
                palette.add(image);
            }
            palette.saved = palette.images.size();
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read the 3D map's block sprites " + palette.file, e);
        }
        return palette;
    }

    private static long hash(int[] image) {
        long h = 0xCBF29CE484222325L;
        for (int pixel : image) {
            h = (h ^ pixel) * 0x100000001B3L;
        }
        return h;
    }

    private int add(int[] image) {
        images.add(image);
        int id = images.size();
        byHash.put(hash(image), id);
        return id;
    }

    /** Id of a sprite with nothing on it: the block can't be seen from that side (0 means "no sprite"). */
    static final int EMPTY = -1;

    /** Id of the sprite, added if new; {@link #EMPTY} if nothing was drawn. */
    synchronized int idOf(int[] image) {
        boolean empty = true;
        for (int pixel : image) {
            if ((pixel >>> 24) != 0) {
                empty = false;
                break;
            }
        }
        if (empty) {
            return EMPTY;
        }
        Integer id = byHash.get(hash(image));
        if (id != null && Arrays.equals(images.get(id - 1), image)) {
            return id;
        }
        return add(image.clone());
    }

    /** The sprite with its reduced copies, or null for an unknown id. Any thread. */
    Sprite sprite(int id) {
        Sprite[] cache = sprites;
        if (id > 0 && id < cache.length && cache[id] != null) {
            return cache[id];
        }
        synchronized (this) {
            if (id <= 0 || id > images.size()) {
                return null;
            }
            cache = sprites;
            if (id >= cache.length) {
                cache = Arrays.copyOf(cache, Math.max(id + 1, cache.length * 2));
            }
            if (cache[id] == null) {
                cache[id] = Sprite.of(images.get(id - 1));
            }
            sprites = cache;
            return cache[id];
        }
    }

    /** Appends the new sprites to the file (background thread). */
    void save() {
        List<int[]> added;
        int from;
        synchronized (this) {
            from = saved;
            if (from == images.size()) {
                return;
            }
            added = new ArrayList<>(images.subList(from, images.size()));
        }
        boolean fresh = from == 0;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file, !fresh), 1 << 16))) {
                if (fresh) {
                    out.writeInt(MAGIC);
                    out.writeInt(generation);
                }
                for (int[] image : added) {
                    for (int pixel : image) {
                        out.writeInt(pixel);
                    }
                }
            }
            synchronized (this) {
                saved = from + added.size();
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save the 3D map's block sprites " + file, e);
        }
    }
}
