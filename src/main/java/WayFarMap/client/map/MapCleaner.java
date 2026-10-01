package WayFarMap.client.map;

import java.io.File;

/**
 * Deletes saved map data: the logs, the flat (2D) or 3D map of all dimensions of a world or of one, or a dimension's
 * whole map. Waypoints, the dimension's name and where the map was left are kept. Run while the maps are closed
 * ({@link MapManager#resetMaps}), so nothing in memory is saved back over it.
 */
public final class MapCleaner {

    /** Parts of a dimension's folder that are the 3D map: its blocks and its drawn tiles. */
    private static final String[] ISO_PARTS = { "blocks", "iso" };
    /** Pictures of blocks for the 3D map, shared by the dimensions of a world. */
    private static final String SPRITES = "iso-sprites.dat";
    /** Kept in a dimension's folder whatever is deleted: its name and sky. */
    private static final String INFO = "dimension.txt";

    private MapCleaner() {}

    /** Everything in the logs folder; returns the bytes deleted. */
    public static long clearLogs(File logs) {
        long freed = 0;
        File[] files = logs.listFiles();
        if (files != null) {
            for (File file : files) {
                freed += delete(file);
            }
        }
        return freed;
    }

    /** The flat map of a dimension (surface, biomes, cave layers); returns the bytes deleted. */
    public static long delete2d(File dimensionDirectory) {
        long freed = 0;
        File[] files = dimensionDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!isIso(file) && !file.getName()
                    .equals(INFO)) {
                    freed += delete(file);
                }
            }
        }
        return freed;
    }

    /** The 3D map of a dimension (its blocks and tiles); returns the bytes deleted. */
    public static long delete3d(File dimensionDirectory) {
        long freed = 0;
        for (String part : ISO_PARTS) {
            freed += delete(new File(dimensionDirectory, part));
        }
        return freed;
    }

    /** The flat or 3D map of every dimension of a world (3D: with the pictures of blocks too). */
    public static long deleteAll(File worldDirectory, boolean iso) {
        long freed = 0;
        for (File dimension : dimensions(worldDirectory)) {
            freed += iso ? delete3d(dimension) : delete2d(dimension);
        }
        if (iso) {
            freed += delete(new File(worldDirectory, SPRITES));
        }
        return freed;
    }

    /** The {@code dim<id>} folders of a world, also of players' own maps of it. */
    public static File[] dimensions(File worldDirectory) {
        File[] found = worldDirectory.listFiles(f -> f.isDirectory() && f.getName()
            .matches("dim-?\\d+"));
        return found == null ? new File[0] : found;
    }

    private static boolean isIso(File file) {
        for (String part : ISO_PARTS) {
            if (file.getName()
                .equals(part)) {
                return true;
            }
        }
        return false;
    }

    /** Size of a file or of a folder with everything in it. */
    public static long size(File file) {
        if (!file.isDirectory()) {
            return file.length();
        }
        long sum = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                sum += size(child);
            }
        }
        return sum;
    }

    /** Size of a dimension's flat map, as {@link #delete2d} would delete it. */
    public static long size2d(File dimensionDirectory) {
        long sum = 0;
        File[] files = dimensionDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!isIso(file) && !file.getName()
                    .equals(INFO)) {
                    sum += size(file);
                }
            }
        }
        return sum;
    }

    /** Size of a dimension's 3D map, as {@link #delete3d} would delete it. */
    public static long size3d(File dimensionDirectory) {
        long sum = 0;
        for (String part : ISO_PARTS) {
            sum += size(new File(dimensionDirectory, part));
        }
        return sum;
    }

    /** Deletes a file or a folder with everything in it; returns the bytes deleted. */
    private static long delete(File file) {
        if (!file.exists()) {
            return 0;
        }
        long freed = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    freed += delete(child);
                }
            }
            file.delete();
            return freed;
        }
        long length = file.length();
        return file.delete() ? length : 0;
    }
}
