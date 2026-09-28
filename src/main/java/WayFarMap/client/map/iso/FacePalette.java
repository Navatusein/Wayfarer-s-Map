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
 * Pictures (16x16 ARGB) of block sides as the game draws them in place, for the 3D map: connected textures, tile
 * entities, modded renderers. The same picture is kept once (a wall of Chisel blocks has only a few different sides),
 * in {@code <world>/iso-faces.dat}. Ids start at 1. Pictures depend on the resource packs: another set of packs starts
 * a new palette ({@link #generation}), and chunks with ids of the old one are drawn from block icons until seen again.
 */
final class FacePalette {

    private static final int MAGIC = 0x57465031; // "WFP1"
    static final int PIXELS = 256;

    final int generation;
    private final File file;
    private final List<int[]> images = new ArrayList<>();
    private final Map<Long, Integer> byHash = new HashMap<>();
    private volatile BlockLooks.Texture[] textures = new BlockLooks.Texture[64];
    /** Pictures already in the file. */
    private int saved;

    private FacePalette(File file, int generation) {
        this.file = file;
        this.generation = generation;
    }

    /** Reads the palette of the world, or starts a new one if it was made with other resource packs. */
    static FacePalette load(File worldDirectory, String cacheId) {
        int generation = cacheId.hashCode() & 0x7FFFFFFF;
        FacePalette palette = new FacePalette(new File(worldDirectory, "iso-faces.dat"), generation);
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
            WayFarMap.LOG.warn("Could not read the 3D map's block pictures " + palette.file, e);
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

    /** Id of the picture, added if new. */
    synchronized int idOf(int[] image) {
        Integer id = byHash.get(hash(image));
        if (id != null && Arrays.equals(images.get(id - 1), image)) {
            return id;
        }
        return add(image.clone());
    }

    /** The picture with its reduced copies, or null for an unknown id. Any thread. */
    BlockLooks.Texture texture(int id) {
        BlockLooks.Texture[] cache = textures;
        if (id < cache.length && cache[id] != null) {
            return cache[id];
        }
        synchronized (this) {
            if (id <= 0 || id > images.size()) {
                return null;
            }
            cache = textures;
            if (id >= cache.length) {
                cache = Arrays.copyOf(cache, Math.max(id + 1, cache.length * 2));
            }
            if (cache[id] == null) {
                cache[id] = BlockLooks.Texture.of(images.get(id - 1));
            }
            textures = cache;
            return cache[id];
        }
    }

    /** Appends the new pictures to the file (background thread). */
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
            WayFarMap.LOG.warn("Could not save the 3D map's block pictures " + file, e);
        }
    }
}
