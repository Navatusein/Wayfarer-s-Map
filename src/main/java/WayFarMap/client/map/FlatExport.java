package WayFarMap.client.map;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import WayFarMap.client.map.export.TilePyramid;

/**
 * The whole flat map (surface, a cave layer or biomes) as tiles for {@link TilePyramid}: each block as a square of
 * {@code scale} pixels, so the saved picture is as big as wanted.
 */
public final class FlatExport implements TilePyramid.Source {

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.png");
    private static final int SIZE = MapRegion.SIZE;
    /** Regions read lately, shared by the threads: a region gives scale x scale tiles. */
    private static final int CACHED_REGIONS = 8;

    private final File directory;
    private final int scale;
    private final Map<Long, int[]> regions = new LinkedHashMap<Long, int[]>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > CACHED_REGIONS;
        }
    };

    /**
     * The map's saved regions; save it first so the files are up to date.
     *
     * @param scale pixels per block: 1, 2, 4, 8 or 16
     */
    public FlatExport(MapDimension map, int scale) {
        this.directory = map.getDirectory();
        this.scale = Math.max(1, Math.min(16, Integer.highestOneBit(Math.max(1, scale))));
    }

    public int scale() {
        return scale;
    }

    @Override
    public int tileSize() {
        return SIZE;
    }

    @Override
    public int threads() {
        return scale == 1 ? 2
            : Math.max(
                1,
                Math.min(
                    4,
                    Runtime.getRuntime()
                        .availableProcessors() - 1));
    }

    @Override
    public Set<Long> tiles() {
        Set<Long> tiles = new HashSet<>();
        String[] names = directory.list();
        if (names == null) {
            return tiles;
        }
        for (String name : names) {
            Matcher m = REGION.matcher(name);
            if (!m.matches()) {
                continue;
            }
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            for (int j = 0; j < scale; j++) {
                for (int i = 0; i < scale; i++) {
                    tiles.add(TilePyramid.key(rx * scale + i, rz * scale + j));
                }
            }
        }
        return tiles;
    }

    @Override
    public int[] render(int tx, int ty) throws IOException {
        int rx = Math.floorDiv(tx, scale), rz = Math.floorDiv(ty, scale);
        int[] region = region(rx, rz);
        // The part of the region this tile shows: SIZE / scale blocks per side.
        int blocks = SIZE / scale;
        int x0 = Math.floorMod(tx, scale) * blocks, z0 = Math.floorMod(ty, scale) * blocks;
        int[] pixels = new int[SIZE * SIZE];
        boolean any = false;
        for (int z = 0; z < blocks; z++) {
            for (int x = 0; x < blocks; x++) {
                int color = region[(z0 + z) * SIZE + x0 + x];
                if (color >>> 24 == 0) {
                    continue;
                }
                any = true;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (z * scale + dy) * SIZE + x * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[row + dx] = color;
                    }
                }
            }
        }
        return any ? pixels : null;
    }

    private int[] region(int rx, int rz) throws IOException {
        long key = TilePyramid.key(rx, rz);
        synchronized (regions) {
            int[] cached = regions.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int[] pixels = MapRegion.read(MapRegion.getFile(directory, rx, rz), rx, rz)
            .pixelArray();
        synchronized (regions) {
            regions.put(key, pixels);
        }
        return pixels;
    }
}
