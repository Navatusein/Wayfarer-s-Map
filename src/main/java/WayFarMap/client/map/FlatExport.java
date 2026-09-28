package WayFarMap.client.map;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import WayFarMap.client.map.export.TilePyramid;

/** The whole flat map (surface, a cave layer or biomes) as tiles for {@link TilePyramid}: one per region, 1:1. */
public final class FlatExport implements TilePyramid.Source {

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.png");

    private final File directory;

    /** The map's saved regions; save it first so the files are up to date. */
    public FlatExport(MapDimension map) {
        this.directory = map.getDirectory();
    }

    @Override
    public int tileSize() {
        return MapRegion.SIZE;
    }

    @Override
    public int threads() {
        return 2;
    }

    @Override
    public Set<Long> tiles() {
        Set<Long> tiles = new HashSet<>();
        String[] names = directory.list();
        if (names != null) {
            for (String name : names) {
                Matcher m = REGION.matcher(name);
                if (m.matches()) {
                    tiles.add(TilePyramid.key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            }
        }
        return tiles;
    }

    @Override
    public int[] render(int rx, int rz) throws IOException {
        MapRegion region = MapRegion.read(MapRegion.getFile(directory, rx, rz), rx, rz);
        int[] pixels = region.pixelArray();
        for (int pixel : pixels) {
            if (pixel >>> 24 != 0) {
                return pixels;
            }
        }
        return null;
    }
}
