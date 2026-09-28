package WayFarMap.client.map.iso;

/**
 * The geometry of the 3D world map, the isometric view of Dynmap's HD maps: the world is looked at from one of four
 * sides (azimuth 45 + 90 * rotation degrees) and 30 degrees above the horizon, with a parallel projection. A world
 * point (x, y, z) lands on the projection plane at
 * <ul>
 * <li>{@code u}: its distance to the right, in blocks;</li>
 * <li>{@code v}: how far it is from the viewer (farther is higher up) minus its height, in blocks (down is
 * positive, like the screen).</li>
 * </ul>
 * Every point on the same line of sight has the same (u, v), which is what the ray tracer follows. The tiles of the
 * 3D map are squares of this plane.
 */
public final class IsoProjection {

    /** The camera's angle above the horizon, as in Dynmap's default "iso_SE_30" perspective. */
    public static final double ELEVATION = Math.toRadians(30);
    public static final double SIN = Math.sin(ELEVATION), COS = Math.cos(ELEVATION);
    /** Height of the plane the map is panned and zoomed on (about sea level). */
    public static final double REFERENCE_Y = 64;
    /** Rays start above everything that can be built. */
    public static final double TOP = 256;

    /** Pixels of a tile side. */
    public static final int TILE_PIXELS = 128;
    /** Pixels per block of the most detailed level: the pictures of block sides the game draws are this sharp. */
    public static final int FINEST_PIXELS_PER_BLOCK = 64;
    /** Detail levels: level k has {@code 64 / 2^k} pixels per block, down to 1 pixel per 8 blocks. */
    public static final int LEVELS = 10;

    private static final IsoProjection[] ROTATIONS = { new IsoProjection(0), new IsoProjection(1),
        new IsoProjection(2), new IsoProjection(3) };

    /** 0 = from the south-east, 1 = north-east, 2 = north-west, 3 = south-west. */
    public final int rotation;
    /** Unit vectors on the ground: the screen's right, and the direction toward the viewer (down on the screen). */
    public final double rightX, rightZ, towardX, towardZ;
    /** Direction of the rays, from the viewer into the world. */
    public final double rayX, rayY, rayZ;

    private IsoProjection(int rotation) {
        this.rotation = rotation;
        double a = Math.toRadians(45 + 90 * rotation);
        towardX = Math.sin(a);
        towardZ = Math.cos(a);
        rightX = Math.cos(a);
        rightZ = -Math.sin(a);
        rayX = -towardX * COS;
        rayY = -SIN;
        rayZ = -towardZ * COS;
    }

    public static IsoProjection of(int rotation) {
        return ROTATIONS[rotation & 3];
    }

    /** Pixels per block of a detail level. */
    public static double pixelsPerBlock(int level) {
        return (double) FINEST_PIXELS_PER_BLOCK / (1 << level);
    }

    /** Blocks of the projection plane covered by one tile side at the level. */
    public static int tileBlocks(int level) {
        return TILE_PIXELS / FINEST_PIXELS_PER_BLOCK << level;
    }

    /**
     * The coarsest level that still has at least as many pixels per block as the screen shows, so tiles are only
     * ever shrunk (at most by half), never blown up, except at the most detailed level.
     */
    public static int levelFor(double screenPixelsPerBlock) {
        return levelFor(screenPixelsPerBlock, FINEST_PIXELS_PER_BLOCK);
    }

    /** Like {@link #levelFor(double)}, with no more than the given pixels per block (the chosen quality). */
    public static int levelFor(double screenPixelsPerBlock, int maxPixelsPerBlock) {
        int ratio = Math.max(1, FINEST_PIXELS_PER_BLOCK / Math.max(1, maxPixelsPerBlock));
        int finest = Integer.numberOfTrailingZeros(ratio);
        double wanted = FINEST_PIXELS_PER_BLOCK / Math.max(1e-6, screenPixelsPerBlock);
        int level = (int) Math.floor(Math.log(wanted) / Math.log(2));
        return Math.max(finest, Math.min(LEVELS - 1, level));
    }

    public double u(double x, double z) {
        return x * rightX + z * rightZ;
    }

    public double v(double x, double y, double z) {
        return (x * towardX + z * towardZ) * SIN - y * COS;
    }

    /** How far toward the viewer a ground point is. */
    public double toward(double x, double z) {
        return x * towardX + z * towardZ;
    }

    /** World {x, z} of the point at (u, v) at height y. */
    public double[] unproject(double u, double v, double y) {
        double toward = (v + y * COS) / SIN;
        return new double[] { u * rightX + toward * towardX, u * rightZ + toward * towardZ };
    }

    /** World {x, z} of a point on the ground given by its u and its distance toward the viewer. */
    public double[] ground(double u, double toward) {
        return new double[] { u * rightX + toward * towardX, u * rightZ + toward * towardZ };
    }

    /**
     * Range {minU, minV, maxU, maxV} of the projection plane where rays can pass through the block column box (x0..x1,
     * y0..y1, z0..z1), e.g. a chunk from the bottom of the world to its highest block.
     */
    public double[] projectBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
        for (double x : new double[] { x0, x1 }) {
            for (double z : new double[] { z0, z1 }) {
                double u = u(x, z), t = toward(x, z);
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minT = Math.min(minT, t);
                maxT = Math.max(maxT, t);
            }
        }
        return new double[] { minU, minT * SIN - y1 * COS, maxU, maxT * SIN - y0 * COS };
    }
}
