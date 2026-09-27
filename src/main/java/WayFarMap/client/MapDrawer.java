package WayFarMap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.opengl.GL11;

import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapRegion;

/** Shared drawing code of the minimap and the fullscreen map. */
public final class MapDrawer {

    /** How many regions may be read from disk per frame, to avoid freezing when zooming out. */
    private static final int DISK_LOADS_PER_FRAME = 2;

    private MapDrawer() {}

    /**
     * Draws the map into the screen rectangle ({@code x}, {@code y}, {@code width}, {@code height}).
     *
     * @param centerX world X shown at the center of the rectangle
     * @param centerZ world Z shown at the center of the rectangle
     * @param scale   screen pixels per block
     */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;

        int rx0 = floor(left) >> MapRegion.SHIFT;
        int rz0 = floor(top) >> MapRegion.SHIFT;
        int rx1 = floor(right) >> MapRegion.SHIFT;
        int rz1 = floor(bottom) >> MapRegion.SHIFT;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        int diskLoads = 0;
        Tessellator tessellator = Tessellator.instance;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                MapRegion region = dimension.getLoadedRegion(rx, rz);
                if (region == null) {
                    if (!dimension.needsDiskLoad(rx, rz) || diskLoads >= DISK_LOADS_PER_FRAME) {
                        continue;
                    }
                    diskLoads++;
                    region = dimension.getRegion(rx, rz, false);
                    if (region == null) {
                        continue;
                    }
                }

                // Part of the region that is inside the view, in block coordinates.
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, region.getTextureId());
                double u0 = (bx0 - regionX) / MapRegion.SIZE;
                double v0 = (bz0 - regionZ) / MapRegion.SIZE;
                double u1 = (bx1 - regionX) / MapRegion.SIZE;
                double v1 = (bz1 - regionZ) / MapRegion.SIZE;
                double sx0 = x + (bx0 - left) * scale;
                double sy0 = y + (bz0 - top) * scale;
                double sx1 = x + (bx1 - left) * scale;
                double sy1 = y + (bz1 - top) * scale;

                tessellator.startDrawingQuads();
                tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                tessellator.draw();
            }
        }
    }

    /** Draws an arrow at the given screen position pointing where the player looks. */
    public static void drawPlayerArrow(double sx, double sy, float yaw, float size, int color) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glTranslated(sx, sy, 0);
        // Yaw 180 faces north, which is "up" on the map.
        GL11.glRotatef(yaw + 180f, 0f, 0f, 1f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tessellator tessellator = Tessellator.instance;
        // Dark outline, then the colored arrow on top.
        drawArrowShape(tessellator, size + 1.2f, 0xFF000000);
        drawArrowShape(tessellator, size, color);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static void drawArrowShape(Tessellator tessellator, float size, int color) {
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        // Two triangles forming an arrow head with a notch at the back.
        tessellator.addVertex(0, -size, 0);
        tessellator.addVertex(-size * 0.75f, size, 0);
        tessellator.addVertex(0, size * 0.45f, 0);
        tessellator.addVertex(0, -size, 0);
        tessellator.addVertex(0, size * 0.45f, 0);
        tessellator.addVertex(size * 0.75f, size, 0);
        tessellator.draw();
    }

    /** Draws a filled square marker centered on the given position. */
    public static void drawDot(double sx, double sy, float radius, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        fillRect(tessellator, sx - radius - 1, sy - radius - 1, sx + radius + 1, sy + radius + 1, 0xFF000000);
        fillRect(tessellator, sx - radius, sy - radius, sx + radius, sy + radius, color);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void fillRect(Tessellator tessellator, double x0, double y0, double x1, double y1, int color) {
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
    }

    /**
     * Draws the other players that are within the rectangle.
     *
     * @param showNames draw the player names next to their markers
     */
    public static void drawOtherPlayers(Minecraft mc, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, float partialTicks, boolean showNames) {
        FontRenderer font = mc.fontRenderer;
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer) || o == mc.thePlayer) {
                continue;
            }
            EntityPlayer other = (EntityPlayer) o;
            double px = other.prevPosX + (other.posX - other.prevPosX) * partialTicks;
            double pz = other.prevPosZ + (other.posZ - other.prevPosZ) * partialTicks;
            double sx = x + width / 2.0 + (px - centerX) * scale;
            double sy = y + height / 2.0 + (pz - centerZ) * scale;
            if (sx < x + 2 || sy < y + 2 || sx > x + width - 2 || sy > y + height - 2) {
                continue;
            }
            drawDot(sx, sy, 1.5f, 0xFF4FC3F7);
            if (showNames) {
                String name = other.getCommandSenderName();
                font.drawStringWithShadow(name, (int) sx - font.getStringWidth(name) / 2, (int) sy + 4, 0xFFFFFF);
            }
        }
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
