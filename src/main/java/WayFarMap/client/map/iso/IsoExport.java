package WayFarMap.client.map.iso;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import WayFarMap.client.map.export.TilePyramid;

/**
 * The whole 3D map of a dimension as tiles for {@link TilePyramid}: everything the block files and the flat map know,
 * seen from one side, drawn at one level of detail (16 pixels per block at most, like the closest zoom in game).
 */
public final class IsoExport implements TilePyramid.Source {

    /** Pixels per side of an exported tile. */
    private static final int SIZE = 256;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(wfb|png)");

    private final IsoMap.Dimension dimension;
    private final IsoProjection projection;
    private final int level;
    private final boolean night;
    private final double pixelsPerBlock;
    /** Blocks of the projection plane per tile side. */
    private final double tileBlocks;
    private final ThreadLocal<IsoTracer> tracers;

    private IsoExport(IsoMap.Dimension dimension, FacePalette palette, int rotation, int level, boolean night) {
        this.dimension = dimension;
        this.projection = IsoProjection.of(rotation);
        this.level = level;
        this.night = night;
        this.pixelsPerBlock = IsoProjection.pixelsPerBlock(level);
        this.tileBlocks = SIZE / pixelsPerBlock;
        this.tracers = ThreadLocal.withInitial(() -> new IsoTracer(dimension.store, dimension.fallback, palette));
    }

    /**
     * The 3D map of the dimension, or null outside of a world (render thread).
     *
     * @param level 0 (16 pixels per block) to 3 (2 pixels per block)
     * @param night the map at night (moonlight and lit torches) instead of by day
     */
    public static IsoExport of(int dimensionId, int rotation, int level, boolean night) {
        IsoMap.Dimension dimension = IsoMap.INSTANCE.dimension(dimensionId);
        if (dimension == null) {
            return null;
        }
        return new IsoExport(dimension, IsoMap.INSTANCE.palette(), rotation, Math.max(0, Math.min(3, level)), night);
    }

    /** Pixels per block of the finest tiles. */
    public double pixelsPerBlock() {
        return pixelsPerBlock;
    }

    @Override
    public int tileSize() {
        return SIZE;
    }

    @Override
    public int threads() {
        return Math.max(1, Math.min(6, Runtime.getRuntime()
            .availableProcessors() - 2));
    }

    /** Tiles over every chunk with blocks or on the flat map, from the bottom of the world to its highest block. */
    @Override
    public Set<Long> tiles() {
        return tiles(true);
    }

    /** Roughly how many tiles there are, quickly (render thread): doesn't read the flat map's pictures. */
    public int estimateTiles() {
        return tiles(false).size();
    }

    private Set<Long> tiles(boolean exact) {
        Set<Long> regions = new HashSet<>();
        addRegions(new File(dimension.directory, "blocks"), regions);
        addRegions(dimension.directory, regions);
        Set<Long> tiles = new HashSet<>();
        for (long region : regions) {
            int rx = (int) (region >> 32), rz = (int) region;
            for (int cz = rz * 32; cz < rz * 32 + 32; cz++) {
                for (int cx = rx * 32; cx < rx * 32 + 32; cx++) {
                    int top = dimension.store.top(cx, cz);
                    if (top < 0 && dimension.fallback.time(cx, cz) != 0) {
                        top = exact ? dimension.fallback.top(cx, cz) : 128;
                    }
                    if (top < 0) {
                        continue;
                    }
                    double x0 = cx * 16.0, z0 = cz * 16.0;
                    double[] box = projection.projectBox(x0, 0, z0, x0 + 16, top + 1, z0 + 16);
                    int tu0 = (int) Math.floor(box[0] / tileBlocks), tv0 = (int) Math.floor(box[1] / tileBlocks);
                    int tu1 = (int) Math.floor(box[2] / tileBlocks), tv1 = (int) Math.floor(box[3] / tileBlocks);
                    for (int tv = tv0; tv <= tv1; tv++) {
                        for (int tu = tu0; tu <= tu1; tu++) {
                            tiles.add(TilePyramid.key(tu, tv));
                        }
                    }
                }
            }
        }
        return tiles;
    }

    private static void addRegions(File directory, Set<Long> regions) {
        String[] names = directory.list();
        if (names == null) {
            return;
        }
        for (String name : names) {
            Matcher m = REGION.matcher(name);
            if (m.matches()) {
                regions.add(TilePyramid.key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            }
        }
    }

    @Override
    public int[] render(int tu, int tv) {
        IsoTracer tracer = tracers.get();
        tracer.reset(projection, level);
        double u0 = tu * tileBlocks, v0 = tv * tileBlocks;
        int[] pixels = new int[SIZE * SIZE];
        boolean any = false;
        // Column by column: the rays of one column pass through nearly the same chunks.
        for (int px = 0; px < SIZE; px++) {
            for (int py = 0; py < SIZE; py++) {
                int day = tracer.trace(u0 + (px + 0.5) / pixelsPerBlock, v0 + (py + 0.5) / pixelsPerBlock);
                int color = night ? tracer.nightColor : day;
                pixels[py * SIZE + px] = color;
                any |= color != 0;
            }
        }
        return any ? pixels : null;
    }
}
