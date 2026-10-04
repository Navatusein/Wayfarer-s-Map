package WayFarMap.client;

import java.util.ArrayDeque;
import java.util.Iterator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The way the player came ({@link Config#playerTrail}): their position every couple of blocks, kept for the game
 * session only, drawn on the 2D map and the minimap as a line fading out toward its old end.
 */
public final class PlayerTrail {

    public static final PlayerTrail INSTANCE = new PlayerTrail();

    /** A point is added once the player is this far from the last one (blocks). */
    private static final double STEP = 1.5;
    /** Farther than this between two points is a teleport: the line is not drawn across it. */
    private static final double JUMP = 32;
    /** How opaque the newest part of the line is. */
    private static final float ALPHA = 0.85f;

    /** {x, z} of the points, the oldest first, all in {@link #dimension}. */
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
            points.addLast(new double[] { player.posX, player.posZ });
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
        int rgb = Theme.ACCENT & 0xFFFFFF;
        float r = (rgb >> 16) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        // Line width is in window pixels: about one and a half GUI pixels.
        GL11.glLineWidth(Math.max(1f, ScaledScreen.currentFactor() * 1.5f));
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);
        int count = trail.points.size();
        int index = 0;
        double[] previous = null;
        Iterator<double[]> it = trail.points.iterator();
        while (it.hasNext()) {
            double[] point = it.next();
            if (previous != null && distance(previous, point[0], point[1]) < JUMP) {
                // Older parts fade out: transparent at the old end.
                float from = ALPHA * (index - 1) / count, to = ALPHA * index / count;
                tessellator.setColorRGBA_F(r, g, b, from);
                tessellator.addVertex(originX + previous[0] * scale, originY + previous[1] * scale, 0);
                tessellator.setColorRGBA_F(r, g, b, to);
                tessellator.addVertex(originX + point[0] * scale, originY + point[1] * scale, 0);
            }
            previous = point;
            index++;
        }
        if (previous != null && distance(previous, playerX, playerZ) < JUMP) {
            // The last bit up to where the player is right now, so the line never lags behind the arrow.
            tessellator.setColorRGBA_F(r, g, b, ALPHA);
            tessellator.addVertex(originX + previous[0] * scale, originY + previous[1] * scale, 0);
            tessellator.addVertex(originX + playerX * scale, originY + playerZ * scale, 0);
        }
        tessellator.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
