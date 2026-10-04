package WayFarMap.client;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.ScaledScreen;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The way the player came ({@link Config#playerTrail}): their position every couple of blocks, kept for the game
 * session only, drawn on the 2D map and the minimap as a line, a dashed line or dots, in one color, a rainbow, colors
 * by speed or by height, or like fire, fading out toward its old end.
 */
public final class PlayerTrail {

    public static final PlayerTrail INSTANCE = new PlayerTrail();

    /** A point is added once the player is this far from the last one (blocks). */
    private static final double STEP = 1.5;
    /** Farther than this between two points is a teleport: the line is not drawn across it. */
    private static final double JUMP = 32;
    /** How opaque the newest part of the trail is. */
    private static final float ALPHA = 0.9f;
    /** Dashes: this many GUI pixels drawn, then a gap; dots: this far apart. */
    private static final double DASH = 6, DASH_GAP = 4, DOT_STEP = 6;
    /** How fast the dashes and dots run toward the player, in GUI pixels a second. */
    private static final double FLOW_SPEED = 14;
    /** Speeds (blocks a second) shown from blue (standing) to red (this fast or faster). */
    private static final double FAST = 12;

    /** {x, z, y, time in ms} of the points, the oldest first, all in {@link #dimension}. */
    private final ArrayDeque<double[]> points = new ArrayDeque<>();
    private int dimension = Integer.MIN_VALUE;

    private PlayerTrail() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (!Config.playerTrail || player == null || mc.theWorld == null) {
            // Off, or out of the world: a trail from before would lead nowhere.
            points.clear();
            return;
        }
        int dim = mc.theWorld.provider.dimensionId;
        if (dim != dimension) {
            points.clear();
            dimension = dim;
        }
        double[] last = points.peekLast();
        if (last == null || distance(last, player.posX, player.posZ) >= STEP) {
            double y = player.boundingBox.minY;
            points.addLast(new double[] { player.posX, player.posZ, y, System.currentTimeMillis() });
        }
        int keep = (int) Math.ceil(Config.playerTrailLength / STEP);
        while (points.size() > keep) {
            points.removeFirst();
        }
    }

    private static double distance(double[] point, double x, double z) {
        double dx = point[0] - x, dz = point[1] - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Draws the trail into the map rectangle ({@code x}, {@code y}, {@code width}, {@code height}) showing the world
     * around ({@code centerX}, {@code centerZ}) at {@code scale} screen pixels per block, ending at the player.
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, double playerX, double playerZ) {
        PlayerTrail trail = INSTANCE;
        if (!Config.playerTrail || trail.points.isEmpty() || dimension != trail.dimension) {
            return;
        }
        double originX = x + width / 2.0 - centerX * scale, originY = y + height / 2.0 - centerZ * scale;
        render(trail.points, originX, originY, scale, playerX, playerZ);
    }

    /** How long the preview's walk takes, and how long it stays at its end before it starts over (ms). */
    private static final long PREVIEW_WALK_MS = 6000, PREVIEW_HOLD_MS = 1500;
    /** The preview's walk, made for a rectangle of this size: {width, height}. */
    private static List<double[]> previewPath;
    private static int previewWidth, previewHeight;

    /**
     * The settings' preview: a walk across the rectangle with the trail drawn as it is set and the player's marker
     * at its head; it starts over every few seconds. It goes down into a valley and up a mountain, walking, then
     * riding fast, then slowly, so the height and speed colors show.
     */
    public static void drawPreview(int x, int y, int width, int height) {
        if (previewPath == null || previewWidth != width || previewHeight != height) {
            previewPath = makePreviewPath(width, height);
            previewWidth = width;
            previewHeight = height;
        }
        long cycle = System.currentTimeMillis() % (PREVIEW_WALK_MS + PREVIEW_HOLD_MS);
        int shown = (int) Math.max(2, previewPath.size() * Math.min(1, cycle / (double) PREVIEW_WALK_MS));
        List<double[]> walked = previewPath.subList(0, shown);
        double[] head = walked.get(shown - 1), before = walked.get(shown - 2);
        render(walked, x, y, 1, head[0], head[1]);
        // Minecraft's yaw: 0 is south (+z), -90 east (+x).
        float yaw = (float) Math.toDegrees(Math.atan2(-(head[0] - before[0]), head[1] - before[1]));
        MapDrawer.drawPlayerArrow(x + head[0], y + head[1], yaw, 4f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static List<double[]> makePreviewPath(int width, int height) {
        List<double[]> path = new ArrayList<>();
        double time = 0;
        double[] last = null;
        int steps = 2000;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double px = 12 + t * (width - 30);
            double pz = height / 2.0 + height * 0.28 * Math.sin(t * 7) + height * 0.08 * Math.sin(t * 23);
            if (last != null && distance(last, px, pz) < STEP) {
                continue;
            }
            // Height: down into a valley, up a mountain, back to sea level.
            double valley = 40 * Math.sin(Math.min(1, t / 0.3) * Math.PI);
            double mountain = t > 0.3 ? 90 * Math.sin((t - 0.3) / 0.7 * Math.PI) : 0;
            double py = 64 - valley + mountain;
            // Speed: walking, then riding fast, then slowly.
            double speed = t < 0.35 ? 4.3 : t < 0.7 ? 11 : 2;
            if (last != null) {
                time += distance(last, px, pz) / speed * 1000;
            }
            last = new double[] { px, pz, py, time };
            path.add(last);
        }
        return path;
    }

    /** Draws the trail through the points ({x, z, y, time}) at {@code scale}, ending at the player. */
    private static void render(Collection<double[]> points, double originX, double originY, double scale,
        double playerX, double playerZ) {
        // The points on screen with their colors, the player's own place last.
        int count = points.size() + 1;
        double[] sx = new double[count], sy = new double[count];
        int[] rgb = new int[count];
        float[] alpha = new float[count];
        boolean[] joined = new boolean[count];
        double[] previous = null;
        int i = 0;
        for (double[] point : points) {
            sx[i] = originX + point[0] * scale;
            sy[i] = originY + point[1] * scale;
            rgb[i] = color(point, previous, i, count);
            alpha[i] = Config.playerTrailFade ? ALPHA * i / (count - 1) : ALPHA;
            joined[i] = previous != null && distance(previous, point[0], point[1]) < JUMP;
            previous = point;
            i++;
        }
        sx[i] = originX + playerX * scale;
        sy[i] = originY + playerZ * scale;
        rgb[i] = rgb[i - 1];
        alpha[i] = ALPHA;
        joined[i] = distance(previous, playerX, playerZ) < JUMP;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        if (Config.playerTrailStyle == Config.TRAIL_DOTS) {
            drawDots(sx, sy, rgb, alpha, joined);
        } else {
            drawLine(sx, sy, rgb, alpha, joined, Config.playerTrailStyle == Config.TRAIL_DASHED);
        }
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** How far the dashes and dots have run (GUI pixels), so they move toward the player. */
    private static double flow() {
        return Config.playerTrailAnimated ? System.currentTimeMillis() / 1000.0 * FLOW_SPEED : 0;
    }

    /** The trail as a line, or as dashes: each piece colored from its start to its end. */
    private static void drawLine(double[] sx, double[] sy, int[] rgb, float[] alpha, boolean[] joined,
        boolean dashed) {
        // Line width is in window pixels: from about one to three GUI pixels.
        GL11.glLineWidth(Math.max(1f, ScaledScreen.currentFactor() * (0.5f + 0.6f * Config.playerTrailWidth)));
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);
        double period = DASH + DASH_GAP;
        // Where along the dash pattern the trail's newest end is: the pattern runs toward the player.
        double along = 0;
        double total = 0;
        for (int i = 1; i < sx.length; i++) {
            total += Math.hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1]);
        }
        double offset = total + flow();
        for (int i = 1; i < sx.length; i++) {
            double length = Math.hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1]);
            if (joined[i] && length > 0) {
                if (!dashed) {
                    addLine(tessellator, sx, sy, rgb, alpha, i, 0, 1);
                } else {
                    // The parts of this segment that fall on dashes.
                    double from = 0;
                    while (from < length) {
                        double phase = mod(offset - (along + from), period);
                        // Distance to the end of the current dash or gap, walking toward the player.
                        if (phase < DASH) {
                            double to = Math.min(length, from + phase + 1e-6);
                            addLine(tessellator, sx, sy, rgb, alpha, i, from / length, to / length);
                            from = to + 1e-6;
                        } else {
                            from += phase - DASH + 1e-6;
                        }
                    }
                }
            }
            along += length;
        }
        tessellator.draw();
    }

    /** The trail as dots every few pixels along it, each in the color of its place. */
    private static void drawDots(double[] sx, double[] sy, int[] rgb, float[] alpha, boolean[] joined) {
        double size = 0.5 + 0.5 * Config.playerTrailWidth;
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        double total = 0;
        for (int i = 1; i < sx.length; i++) {
            total += Math.hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1]);
        }
        double offset = total + flow();
        double along = 0;
        for (int i = 1; i < sx.length; i++) {
            double length = Math.hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1]);
            if (joined[i] && length > 0) {
                // The first dot of this segment, on the pattern that runs toward the player.
                double first = mod(offset - along, DOT_STEP);
                for (double at = first; at < length; at += DOT_STEP) {
                    double t = at / length;
                    double px = sx[i - 1] + (sx[i] - sx[i - 1]) * t, py = sy[i - 1] + (sy[i] - sy[i - 1]) * t;
                    float a = alpha[i - 1] + (alpha[i] - alpha[i - 1]) * (float) t;
                    setColor(tessellator, mix(rgb[i - 1], rgb[i], t), a);
                    tessellator.addVertex(px - size, py + size, 0);
                    tessellator.addVertex(px + size, py + size, 0);
                    tessellator.addVertex(px + size, py - size, 0);
                    tessellator.addVertex(px - size, py - size, 0);
                }
            }
            along += length;
        }
        tessellator.draw();
    }

    /** The part of segment {@code i} (from point i - 1 to point i) between {@code t0} and {@code t1} (0 to 1). */
    private static void addLine(Tessellator tessellator, double[] sx, double[] sy, int[] rgb, float[] alpha, int i,
        double t0, double t1) {
        for (double t : new double[] { t0, t1 }) {
            setColor(tessellator, mix(rgb[i - 1], rgb[i], t), alpha[i - 1] + (alpha[i] - alpha[i - 1]) * (float) t);
            tessellator.addVertex(sx[i - 1] + (sx[i] - sx[i - 1]) * t, sy[i - 1] + (sy[i] - sy[i - 1]) * t, 0);
        }
    }

    private static void setColor(Tessellator tessellator, int rgb, float alpha) {
        tessellator.setColorRGBA_F((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    private static double mod(double value, double period) {
        double m = value % period;
        return m < 0 ? m + period : m;
    }

    /** The color between two (RGB) at {@code t}. */
    private static int mix(int from, int to, double t) {
        int r = (int) Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    /** The color (RGB) of a point of the trail: the {@code index}-th of {@code count}, after {@code previous}. */
    private static int color(double[] point, double[] previous, int index, int count) {
        switch (Config.playerTrailColorMode) {
            case Config.TRAIL_RAINBOW: {
                // Around the color wheel along the trail, shimmering toward the player when animated.
                double shift = Config.playerTrailAnimated ? System.currentTimeMillis() / 20.0 : 0;
                float hue = (float) (mod(index * 4.0 - shift, 360) / 360);
                return Color.HSBtoRGB(hue, 0.75f, 1f) & 0xFFFFFF;
            }
            case Config.TRAIL_SPEED: {
                // Blue standing still, green walking, red riding or flying fast.
                double speed = 0;
                if (previous != null && point[3] > previous[3]) {
                    speed = distance(previous, point[0], point[1]) / ((point[3] - previous[3]) / 1000.0);
                }
                float t = (float) Math.min(1, speed / FAST);
                return Color.HSBtoRGB((1 - t) * 0.66f, 0.8f, 1f) & 0xFFFFFF;
            }
            case Config.TRAIL_HEIGHT: {
                // Deep purple at the bottom of the world, through blue and green at sea level, to orange high up.
                float t = (float) Math.max(0, Math.min(1, point[2] / 160.0));
                return Color.HSBtoRGB(0.8f - t * 0.75f, 0.75f, 1f) & 0xFFFFFF;
            }
            case Config.TRAIL_FIRE: {
                // Dark red at the old end, through orange, to bright yellow by the player.
                float t = count > 1 ? (float) index / (count - 1) : 1;
                return Color.HSBtoRGB(t * 0.15f, 1f - t * 0.35f, 0.65f + t * 0.35f) & 0xFFFFFF;
            }
            default:
                return Config.playerTrailColor & 0xFFFFFF;
        }
    }
}
