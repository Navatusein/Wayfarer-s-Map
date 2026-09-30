package WayFarMap.client.waypoint;

import java.util.List;

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
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
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
        // As the game draws it in the inventory, taken once off-screen: the same everywhere, whatever the state here.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        boolean drawn = ItemSprites.draw(stack, centerX, centerY, size);
        GL11.glPopAttrib();
        if (drawn) {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            return;
        }
        drawItemDirect(stack, centerX, centerY, size);
    }

    /**
     * Draws the item with the game's item renderer right here, as the inventory does: for many items at once (the
     * icon picker's grid), where taking a picture of each would cost frames.
     */
    public static void drawItemDirect(ItemStack stack, double centerX, double centerY, float size) {
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
        // Laid out on whole GUI pixels, then moved by the remainder: a GUI pixel is 2-4 screen pixels, and snapping
        // to it made markers jump instead of gliding while the map moves or turns.
        long cx = Math.round(sx), cy = Math.round(sy);
        GL11.glPushMatrix();
        GL11.glTranslated(sx - cx, sy - cy, 0);
        int half = Math.round(size / 2f);
        int x0 = (int) cx - half;
        int y0 = (int) cy - half;
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
            drawItem(icon, cx, cy, size);
        } else if (waypoint.outlineColor == null) {
            int inset = Math.max(1, half / 3);
            Gui.drawRect(x0 + inset - 1, y0 + inset - 1, x1 - inset + 1, y1 - inset + 1, 0xFF000000);
            Gui.drawRect(x0 + inset, y0 + inset, x1 - inset, y1 - inset, 0xFF000000 | DEFAULT_COLOR);
        }

        if (label) {
            int[] rect = getLabelRect(waypoint, cx, cy, size, false);
            if (rect != null) {
                drawMapLabel(waypoint, rect, false);
            }
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Screen rectangle {x0, y0, x1, y1} of the name label under a marker of the given size, or null if the waypoint
     * has neither a name nor a distance (another dimension).
     */
    public static int[] getLabelRect(Waypoint waypoint, double sx, double sy, float size, boolean fullName) {
        return getLabelRect(waypoint, sx, sy, size, 1f);
    }

    /**
     * Screen rectangle {x0, y0, x1, y1} of the name label under a marker, with the text drawn at {@code textScale}
     * (1 = normal size), or null if there is nothing to show.
     */
    public static int[] getLabelRect(Waypoint waypoint, double sx, double sy, float size, float textScale) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String name = mapLabelName(waypoint);
        String distance = labelDistance(waypoint);
        if (name.isEmpty() && distance.isEmpty()) {
            return null;
        }
        int textWidth = font.getStringWidth(name) + font.getStringWidth(distance);
        int width = (int) Math.ceil((textWidth + 4) * textScale);
        int height = (int) Math.ceil(10 * textScale);
        int x0 = (int) Math.round(sx) - width / 2;
        int y0 = (int) Math.round(sy) + Math.round(size / 2f) + 2;
        return new int[] { x0, y0, x0 + width, y0 + height };
    }

    public static void drawMapLabel(Waypoint waypoint, int[] rect, boolean fullName) {
        drawMapLabel(waypoint, rect, 1f);
    }

    /** Draws the label into the rectangle from {@link #getLabelRect}, with the text at {@code textScale}. */
    public static void drawMapLabel(Waypoint waypoint, int[] rect, float textScale) {
        Gui.drawRect(rect[0], rect[1], rect[2], rect[3], Theme.LABEL_BG);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String name = mapLabelName(waypoint);
        GL11.glPushMatrix();
        GL11.glTranslatef(rect[0] + 2 * textScale, rect[1] + textScale, 0f);
        GL11.glScalef(textScale, textScale, 1f);
        font.drawString(name, 0, 0, Theme.TEXT);
        font.drawString(labelDistance(waypoint), font.getStringWidth(name), 0, Theme.TEXT_MUTED);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Name for the world map label: name and distance together always fit in {@link Config#waypointLabelMaxWidth},
     * so the name is cut shorter to leave room for the distance. The full name of the hovered waypoint is shown in
     * the map's bottom bar instead.
     */
    private static String mapLabelName(Waypoint waypoint) {
        if (waypoint.name.isEmpty()) {
            return "";
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int room = Config.waypointLabelMaxWidth - font.getStringWidth(distanceSuffix(waypoint));
        return Theme.ellipsize(font, waypoint.name, Math.max(font.getStringWidth("..."), room));
    }

    /** The distance part of the map label: without a name it stands alone, so no gap before it. */
    private static String labelDistance(Waypoint waypoint) {
        String distance = distanceSuffix(waypoint);
        return waypoint.name.isEmpty() ? distance.trim() : distance;
    }

    /** " 123m": distance from the player, shown after the name on the world map. */
    public static String distanceSuffix(Waypoint waypoint) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.dimension != waypoint.dimension) {
            return "";
        }
        double dx = waypoint.x + 0.5 - player.posX, dy = waypoint.y - player.posY, dz = waypoint.z + 0.5 - player.posZ;
        return "  " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
    }

    /** The name, cut to {@link Config#waypointLabelMaxWidth} unless the full name is wanted (e.g. on hover). */
    public static String labelText(Waypoint waypoint, boolean fullName) {
        if (fullName) {
            return waypoint.name;
        }
        return Theme.ellipsize(Minecraft.getMinecraft().fontRenderer, waypoint.name, Config.waypointLabelMaxWidth);
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
        // The whole item as in the inventory (a block's icon alone is one face; own renderers have none).
        if (ItemSprites.draw(stack, centerX, centerY, size)) {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            return true;
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
                GL11.glColor4f(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, 1f);
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
        if (mc.theWorld == null || mc.thePlayer == null || mc.gameSettings.hideGUI) {
            return;
        }
        int dimension = mc.theWorld.provider.dimensionId;
        if (Config.waypointsInWorld && MapManager.INSTANCE.getDimension() != null) {
            List<Waypoint> waypoints = WaypointManager.INSTANCE.getVisibleWaypoints(dimension);
            // Beams first: the markers are drawn over everything.
            for (Waypoint waypoint : waypoints) {
                if (waypoint.beam) {
                    renderBeam(mc, waypoint, event.partialTicks);
                }
            }
            for (Waypoint waypoint : waypoints) {
                renderInWorld(mc, waypoint);
            }
        }
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.renderTrackedInWorld(mc, dimension);
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            ThaumcraftNodes.renderTrackedInWorld(mc, dimension);
        }
    }

    private static final ResourceLocation BEAM_TEXTURE = new ResourceLocation("textures/entity/beacon_beam.png");

    /**
     * A beacon beam through the waypoint's column, from the bottom of the world to the top, in its outline color
     * (white without one): a turning inner beam with a scrolling texture and a faint outer glow, like the vanilla
     * beacon. Hidden behind terrain like a real one.
     */
    private static void renderBeam(Minecraft mc, Waypoint waypoint, float partialTicks) {
        double x = waypoint.x - RenderManager.renderPosX;
        // The whole height of the world, not only above the waypoint: seen from anywhere, above or below it.
        double y = -RenderManager.renderPosY;
        double z = waypoint.z - RenderManager.renderPosZ;
        double distance = Math.sqrt((x + 0.5) * (x + 0.5) + (z + 0.5) * (z + 0.5));
        if (Config.waypointMaxDistance > 0 && distance > Config.waypointMaxDistance) {
            return;
        }
        double height = Math.max(1, mc.theWorld.getHeight());
        int color = waypoint.outlineColor != null ? waypoint.outlineColor : 0xFFFFFF;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        float time = mc.theWorld.getTotalWorldTime() % 100_000L + partialTicks;
        double scroll = -time * 0.2 - Math.floor(-time * 0.1);

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        mc.getTextureManager()
            .bindTexture(BEAM_TEXTURE);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        // Past the view distance the beam would be cut by the far plane: it is drawn closer and smaller instead, which
        // looks the same on screen (the camera is at the origin), so far waypoints keep their beams.
        double limit = Math.max(16, mc.gameSettings.renderDistanceChunks * 16 - 8);
        boolean far = distance > limit;
        if (far) {
            GL11.glPushMatrix();
            double factor = limit / distance;
            GL11.glScaled(factor, factor, factor);
        }
        Tessellator tessellator = Tessellator.instance;

        // Inner beam: a turning square, blended additively so it glows.
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        double angle = time * 0.025 * -1.5;
        double radius = 0.2;
        double[] cx = new double[4], cz = new double[4];
        for (int i = 0; i < 4; i++) {
            double a = angle + Math.PI / 4 + i * Math.PI / 2;
            cx[i] = x + 0.5 + Math.cos(a) * radius;
            cz[i] = z + 0.5 + Math.sin(a) * radius;
        }
        double vTop = height * (0.5 / radius) + scroll - 1, vBottom = scroll - 1;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(r, g, b, 32);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            tessellator.addVertexWithUV(cx[i], y + height, cz[i], 1, vTop);
            tessellator.addVertexWithUV(cx[i], y, cz[i], 1, vBottom);
            tessellator.addVertexWithUV(cx[j], y, cz[j], 0, vBottom);
            tessellator.addVertexWithUV(cx[j], y + height, cz[j], 0, vTop);
        }
        tessellator.draw();

        // Outer glow: a still, wider square with normal blending.
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        double lo = 0.2, hi = 0.8;
        double[][] corners = { { lo, lo }, { hi, lo }, { hi, hi }, { lo, hi } };
        double vTop2 = height + scroll - 1;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(r, g, b, 32);
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i], c = corners[(i + 1) % 4];
            tessellator.addVertexWithUV(x + a[0], y + height, z + a[1], 1, vTop2);
            tessellator.addVertexWithUV(x + a[0], y, z + a[1], 1, scroll - 1);
            tessellator.addVertexWithUV(x + c[0], y, z + c[1], 0, scroll - 1);
            tessellator.addVertexWithUV(x + c[0], y + height, z + c[1], 0, vTop2);
        }
        tessellator.draw();

        if (far) {
            GL11.glPopMatrix();
        }
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void renderInWorld(Minecraft mc, Waypoint waypoint) {
        final ItemStack icon = waypoint.getIcon();
        renderBillboard(
            mc,
            waypoint.x + 0.5,
            waypoint.y,
            waypoint.z + 0.5,
            labelText(waypoint, false),
            waypoint.outlineColor,
            icon == null ? null : (cx, cy, size) -> drawFlatItem(icon, cx, cy, size));
    }

    /** Draws a 16x16 icon centered on (cx, cy) in the billboard's plane; returns false to use the colored square. */
    public interface BillboardIcon {

        boolean draw(float cx, float cy, float size);
    }

    /**
     * Draws a marker in the world at the given block position (x, z are block centers, y is the feet height): the
     * icon above a box with the name and the distance. Seen through walls and kept readable from far away.
     *
     * @param outlineColor RGB of the box outline, or null for none
     * @param icon         draws the icon, or null for a colored square
     */
    public static void renderBillboard(Minecraft mc, double x, double y, double z, String name, Integer outlineColor,
        BillboardIcon icon) {
        EntityPlayer player = mc.thePlayer;
        double dx = x - RenderManager.renderPosX;
        double dy = y + 1.5 - RenderManager.renderPosY;
        double dz = z - RenderManager.renderPosZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.5 || (Config.waypointMaxDistance > 0 && distance > Config.waypointMaxDistance)) {
            return;
        }
        // Far markers are drawn closer (inside the view distance) and scaled to look as if they were at their real
        // distance. Up close they keep a constant size on screen; farther away they shrink like real objects until
        // they reach the minimum on-screen size, from where they stop shrinking so they stay readable.
        double viewDistance = Math.min(distance, 48.0);
        double factor = viewDistance / distance;
        double apparentSize = Math.max(Config.waypointMinScale, Math.min(1.0, NEAR_DISTANCE / distance));
        float scale = (float) (0.0045 * Config.waypointScale * Math.max(viewDistance, 5.0) * apparentSize);

        int blocks = (int) Math.round(
            Math.sqrt(Math.pow(x - player.posX, 2) + Math.pow(y - player.posY, 2) + Math.pow(z - player.posZ, 2)));
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

        int nameWidth = font.getStringWidth(name);
        int distanceWidth = font.getStringWidth(distanceText);
        int boxHalf = Math.max(nameWidth, distanceWidth) / 2 + 3;
        int top = 0;
        int bottom = name.isEmpty() ? 11 : 21;

        if (outlineColor != null) {
            fillRect(-boxHalf - 1, top - 1, boxHalf + 1, bottom + 1, 0xFF000000 | outlineColor);
        }
        fillRect(-boxHalf, top, boxHalf, bottom, 0xA0000000);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int textY = top + 2;
        if (!name.isEmpty()) {
            font.drawString(name, -nameWidth / 2, textY, 0xFFFFFFFF);
            textY += 10;
        }
        font.drawString(distanceText, -distanceWidth / 2, textY, 0xFFC0C0C0);

        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        if (icon == null || !icon.draw(0f, top - 11f, 16f)) {
            int color = 0xFF000000 | (outlineColor != null ? outlineColor : DEFAULT_COLOR);
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
