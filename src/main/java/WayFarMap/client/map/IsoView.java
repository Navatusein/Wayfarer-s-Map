package WayFarMap.client.map;

import java.nio.FloatBuffer;

/**
 * The geometry of the 3D world map: an isometric view like Dynmap's, looking down at the world from one of four
 * sides. A world point (x, y, z) is drawn at
 * <ul>
 * <li>screen x: its distance to the right of the center, times the scale;</li>
 * <li>screen y: how far it is from the viewer (farther is higher up) minus its height, times the scale.</li>
 * </ul>
 * The view is centered and panned on the plane {@link #REFERENCE_Y}: {@code centerX, centerZ} is the point of that
 * plane in the middle of the screen, so moving and zooming work like on the flat map.
 */
public final class IsoView {

    /** Height of the ground plane the view is centered on (about sea level). */
    public static final double REFERENCE_Y = 64;
    /** The camera's angle above the horizon: true isometric, a block's top is twice as wide as it is tall. */
    public static final double ELEVATION = Math.atan(Math.sqrt(0.5));
    static final double SIN = Math.sin(ELEVATION), COS = Math.cos(ELEVATION);
    private static final int WORLD_TOP = 256;

    public final double centerX, centerZ;
    /** Screen pixels per block. */
    public final double scale;
    /** Screen point of the center. */
    public final double screenX, screenY;
    /** On the ground: the screen's right, and the direction toward the viewer (down on the screen). */
    final double rightX, rightZ, towardX, towardZ;

    /**
     * @param angle degrees around the vertical of the side looked from: 0 from the south, 90 from the east, and so
     *              on; {@link #angleOf} gives the four isometric ones
     */
    public IsoView(double centerX, double centerZ, double scale, double angle, double screenX, double screenY) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.scale = scale;
        this.screenX = screenX;
        this.screenY = screenY;
        double a = Math.toRadians(angle);
        towardX = Math.sin(a);
        towardZ = Math.cos(a);
        rightX = Math.cos(a);
        rightZ = -Math.sin(a);
    }

    /** The view angle of a rotation step: 0 from the south-east, 1 north-east, 2 north-west, 3 south-west. */
    public static double angleOf(int rotation) {
        return 45 + 90 * rotation;
    }

    /** Screen position {x, y} of a world point. */
    public double[] project(double x, double y, double z) {
        double dx = x - centerX, dz = z - centerZ;
        double along = dx * rightX + dz * rightZ;
        double toward = dx * towardX + dz * towardZ;
        return new double[] { screenX + along * scale, screenY + (toward * SIN - (y - REFERENCE_Y) * COS) * scale };
    }

    /** Move {dx, dz} on the reference plane that shows as a move of (ox, oy) on the screen. */
    public double[] groundOffset(double ox, double oy) {
        double along = ox / scale, toward = oy / (scale * SIN);
        return new double[] { along * rightX + toward * towardX, along * rightZ + toward * towardZ };
    }

    /** World {x, z} where the screen point meets height y. */
    public double[] unproject(double sx, double sy, double y) {
        double along = (sx - screenX) / scale;
        double toward = ((sy - screenY) / scale + (y - REFERENCE_Y) * COS) / SIN;
        return new double[] { centerX + along * rightX + toward * towardX,
            centerZ + along * rightZ + toward * towardZ };
    }

    /** The center {x, z} that shows the world point (x, y, z) in the middle of the screen, at this view angle. */
    public static double[] centerFor(double x, double y, double z, double angle) {
        double a = Math.toRadians(angle);
        double toward = (y - REFERENCE_Y) * COS / SIN;
        return new double[] { x - Math.sin(a) * toward, z - Math.cos(a) * toward };
    }

    /** Heights of the explored surface: the top of the column, 0 where unknown. */
    public interface Heights {

        int at(int x, int z);
    }

    /**
     * The column seen at the screen point: the ray from the viewer through it is followed down until it meets the
     * ground. Returns {x, z, top of the column}, or null if it passes through unknown ground all the way down.
     */
    public int[] pick(double sx, double sy, Heights heights) {
        for (double y = WORLD_TOP; y >= 0; y -= 0.25) {
            double[] at = unproject(sx, sy, y);
            int x = (int) Math.floor(at[0]), z = (int) Math.floor(at[1]);
            int top = heights.at(x, z);
            if (top > 0 && top >= y) {
                return new int[] { x, z, top };
            }
        }
        return null;
    }

    /** World box {minX, minZ, maxX, maxZ} that can show up in the screen rectangle (heights 0 to the top). */
    public double[] visibleBounds(double x, double y, double width, double height) {
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (double sx : new double[] { x, x + width }) {
            for (double sy : new double[] { y, y + height }) {
                for (double h : new double[] { 0, WORLD_TOP }) {
                    double[] p = unproject(sx, sy, h);
                    box[0] = Math.min(box[0], p[0]);
                    box[1] = Math.min(box[1], p[1]);
                    box[2] = Math.max(box[2], p[0]);
                    box[3] = Math.max(box[3], p[1]);
                }
            }
        }
        return box;
    }

    /**
     * Depth factor that keeps everything visible in the screen rectangle inside the GUI's depth range (about 1000
     * units each way), so the ground hides what is behind it without being cut off.
     */
    double depthScale(double height) {
        double toward = (height / 2 / scale + WORLD_TOP * COS) / SIN;
        double depth = toward * COS + (WORLD_TOP - REFERENCE_Y) * SIN;
        return 900 / Math.max(1, depth);
    }

    /**
     * The view as a matrix for {@code glMultMatrix}: from world coordinates relative to (centerX, REFERENCE_Y,
     * centerZ) to screen pixels relative to the screen center, with the depth (nearer is larger) in z.
     */
    void writeMatrix(FloatBuffer buffer, double depthScale) {
        double s = scale, k = depthScale;
        buffer.clear();
        // Column-major: the columns are where world x, y and z go.
        buffer.put((float) (s * rightX))
            .put((float) (s * SIN * towardX))
            .put((float) (k * COS * towardX))
            .put(0f);
        buffer.put(0f)
            .put((float) (-s * COS))
            .put((float) (k * SIN))
            .put(0f);
        buffer.put((float) (s * rightZ))
            .put((float) (s * SIN * towardZ))
            .put((float) (k * COS * towardZ))
            .put(0f);
        buffer.put(0f)
            .put(0f)
            .put(0f)
            .put(1f);
        buffer.flip();
    }
}
