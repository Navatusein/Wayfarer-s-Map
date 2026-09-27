package WayFarMap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;
import WayFarMap.client.map.MapManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Draws waypoints on the maps and in the world. */
public class WaypointRenderer {

    private static final RenderItem RENDER_ITEM = new RenderItem();
    /** Marker color of waypoints that have neither an icon nor an outline. */
    public static final int DEFAULT_COLOR = 0xFFFFFF;
    /** Up to this distance, in-world waypoints keep their full size on screen. */
    private static final double NEAR_DISTANCE = 12.0;

    /** Draws an item icon of {@code size} GUI pixels centered on the given point. */
    public static void drawItem(ItemStack stack, double centerX, double centerY, float size) {
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glTranslated(centerX - size / 2.0, centerY - size / 2.0, 0);
        GL11.glScalef(size / 16f, size / 16f, 1f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            RENDER_ITEM.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        } catch (Throwable ignored) {
            // A broken modded item renderer must not crash the map.
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Draws a waypoint marker (icon or dot, with the optional outline) centered on the given screen point.
     *
     * @param label draw the name below the marker
     */
    public static void drawMapMarker(Waypoint waypoint, double sx, double sy, float size, boolean label) {
        int half = Math.round(size / 2f);
        int x0 = (int) Math.round(sx) - half;
        int y0 = (int) Math.round(sy) - half;
        int x1 = x0 + half * 2;
        int y1 = y0 + half * 2;
        ItemStack icon = waypoint.getIcon();

        if (waypoint.outlineColor != null) {
            int color = 0xFF000000 | waypoint.outlineColor;
            Gui.drawRect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF000000);
            Gui.drawRect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, color);
            Gui.drawRect(x0, y0, x1, y1, icon != null ? 0xC0202020 : color);
        }
        if (icon != null) {
            drawItem(icon, sx, sy, size);
        } else if (waypoint.outlineColor == null) {
            int inset = Math.max(1, half / 3);
            Gui.drawRect(x0 + inset - 1, y0 + inset - 1, x1 - inset + 1, y1 - inset + 1, 0xFF000000);
            Gui.drawRect(x0 + inset, y0 + inset, x1 - inset, y1 - inset, 0xFF000000 | DEFAULT_COLOR);
        }

        if (label) {
            int[] rect = getLabelRect(waypoint, sx, sy, size);
            if (rect != null) {
                drawMapLabel(waypoint, rect);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Screen rectangle {x0, y0, x1, y1} of the name label under a marker of the given size, or null if the waypoint
     * has no name.
     */
    public static int[] getLabelRect(Waypoint waypoint, double sx, double sy, float size) {
        if (waypoint.name.isEmpty()) {
            return null;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int width = font.getStringWidth(waypoint.name);
        int tx = (int) Math.round(sx) - width / 2;
        int ty = (int) Math.round(sy) + Math.round(size / 2f) + 3;
        return new int[] { tx - 2, ty - 1, tx + width + 2, ty + 9 };
    }

    public static void drawMapLabel(Waypoint waypoint, int[] rect) {
        Gui.drawRect(rect[0], rect[1], rect[2], rect[3], 0x90000000);
        Minecraft.getMinecraft().fontRenderer.drawStringWithShadow(waypoint.name, rect[0] + 2, rect[1] + 1, 0xFFFFFF);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Draws the item's icon as a flat textured square centered on (0-based) {@code centerX}/{@code centerY} in the
     * current coordinate space. Unlike the GUI item renderer this works in any 3D transform.
     *
     * @return false if the item has no usable icon (e.g. it only has a custom 3D renderer)
     */
    private static boolean drawFlatItem(ItemStack stack, float centerX, float centerY, float size) {
        Item item = stack.getItem();
        if (item == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TextureManager textureManager = mc.getTextureManager();
        boolean drew = false;
        try {
            textureManager.bindTexture(textureManager.getResourceLocation(stack.getItemSpriteNumber()));
            boolean multiPass = item.requiresMultipleRenderPasses();
            int passes = multiPass ? item.getRenderPasses(stack.getItemDamage()) : 1;
            float x0 = centerX - size / 2f, y0 = centerY - size / 2f, x1 = x0 + size, y1 = y0 + size;
            Tessellator tessellator = Tessellator.instance;
            for (int pass = 0; pass < passes; pass++) {
                IIcon icon = multiPass ? item.getIcon(stack, pass) : stack.getIconIndex();
                if (icon == null) {
                    continue;
                }
                int color = item.getColorFromItemStack(stack, pass);
                GL11.glColor4f(
                    ((color >> 16) & 0xFF) / 255f,
                    ((color >> 8) & 0xFF) / 255f,
                    (color & 0xFF) / 255f,
                    1f);
                tessellator.startDrawingQuads();
                tessellator.addVertexWithUV(x0, y1, 0, icon.getMinU(), icon.getMaxV());
                tessellator.addVertexWithUV(x1, y1, 0, icon.getMaxU(), icon.getMaxV());
                tessellator.addVertexWithUV(x1, y0, 0, icon.getMaxU(), icon.getMinV());
                tessellator.addVertexWithUV(x0, y0, 0, icon.getMinU(), icon.getMinV());
                tessellator.draw();
                drew = true;
            }
        } catch (Throwable ignored) {
            // Some modded items can't give an icon outside of their own renderer.
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return drew;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Config.waypointsInWorld || mc.theWorld == null
            || mc.thePlayer == null
            || MapManager.INSTANCE.getDimension() == null
            || mc.gameSettings.hideGUI) {
            return;
        }
        int dimension = mc.theWorld.provider.dimensionId;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(dimension)) {
            renderInWorld(mc, waypoint);
        }
    }

    private static void renderInWorld(Minecraft mc, Waypoint waypoint) {
        EntityPlayer player = mc.thePlayer;
        double dx = waypoint.x + 0.5 - RenderManager.renderPosX;
        double dy = waypoint.y + 1.5 - RenderManager.renderPosY;
        double dz = waypoint.z + 0.5 - RenderManager.renderPosZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.5 || (Config.waypointMaxDistance > 0 && distance > Config.waypointMaxDistance)) {
            return;
        }
        // Far waypoints are drawn closer (inside the view distance) and scaled to look as if they were at their real
        // distance. Up close they keep a constant size on screen; farther away they shrink like real objects until
        // they reach the minimum on-screen size, from where they stop shrinking so they stay readable.
        double viewDistance = Math.min(distance, 48.0);
        double factor = viewDistance / distance;
        double apparentSize = Math.max(Config.waypointMinScale, Math.min(1.0, NEAR_DISTANCE / distance));
        float scale = (float) (0.0045 * Config.waypointScale * Math.max(viewDistance, 5.0) * apparentSize);

        int blocks = (int) Math.round(Math.sqrt(
            Math.pow(waypoint.x + 0.5 - player.posX, 2) + Math.pow(waypoint.y - player.posY, 2)
                + Math.pow(waypoint.z + 0.5 - player.posZ, 2)));
        String distanceText = blocks + "m";
        FontRenderer font = mc.fontRenderer;
        RenderManager renderManager = RenderManager.instance;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(dx * factor, dy * factor, dz * factor);
        GL11.glRotatef(-renderManager.playerViewY, 0f, 1f, 0f);
        GL11.glRotatef(renderManager.playerViewX, 1f, 0f, 0f);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int nameWidth = font.getStringWidth(waypoint.name);
        int distanceWidth = font.getStringWidth(distanceText);
        int boxHalf = Math.max(nameWidth, distanceWidth) / 2 + 3;
        int top = 0;
        int bottom = waypoint.name.isEmpty() ? 11 : 21;

        if (waypoint.outlineColor != null) {
            fillRect(-boxHalf - 1, top - 1, boxHalf + 1, bottom + 1, 0xFF000000 | waypoint.outlineColor);
        }
        fillRect(-boxHalf, top, boxHalf, bottom, 0xA0000000);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int textY = top + 2;
        if (!waypoint.name.isEmpty()) {
            font.drawString(waypoint.name, -nameWidth / 2, textY, 0xFFFFFFFF);
            textY += 10;
        }
        font.drawString(distanceText, -distanceWidth / 2, textY, 0xFFC0C0C0);

        ItemStack icon = waypoint.getIcon();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        if (icon == null || !drawFlatItem(icon, 0f, top - 11f, 16f)) {
            int color = 0xFF000000 | (waypoint.outlineColor != null ? waypoint.outlineColor : DEFAULT_COLOR);
            fillRect(-4, top - 12, 4, top - 4, 0xFF000000);
            fillRect(-3, top - 11, 3, top - 5, color);
        }

        GL11.glPopAttrib();
        GL11.glDepthMask(true);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void fillRect(int x0, int y0, int x1, int y1, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
