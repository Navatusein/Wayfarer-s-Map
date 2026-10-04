package WayFarMap.client.waypoint;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.MapManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameData;

/** Draws waypoints on the maps and in the world. */
public class WaypointRenderer {

    private static final RenderItem RENDER_ITEM = new RenderItem();
    /** Marker color of waypoints that have neither an icon nor an outline. */
    public static final int DEFAULT_COLOR = 0xFFFFFF;
    /** Background of a death marker's label on the map: the label's dark, tinted red. */
    private static final int DEATH_LABEL_BG = 0xC0401216;
    /** The age line under a death marker's name: a light red. */
    private static final int DEATH_AGE_COLOR = 0xFFE59A96;
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
        boolean drawn = ItemSprites.draw(stack, centerX, centerY, size, true);
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
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glTranslated(centerX - size / 2.0, centerY - size / 2.0, 0);
        GL11.glScalef(size / 16f, size / 16f, 1f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        renderItemSafely(RENDER_ITEM, mc, stack);
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Items never drawn: their renderer fails and leaves the game's drawing broken (GregTech's volumetric flasks). Left
     * out of the icon picker too.
     */
    private static final Set<String> UNDRAWABLE_ITEMS = new HashSet<>(
        Arrays.asList(
            "gregtech:gt.Volumetric_Flask",
            "miscutils:gt.Volumetric_Flask_8k",
            "miscutils:gt.Volumetric_Flask_32k",
            "miscutils:gt.Volumetric_Flask_Infinite"));

    /** Whether the item's icon is never drawn (see {@link #UNDRAWABLE_ITEMS}). */
    public static boolean isUndrawable(ItemStack stack) {
        Object name = GameData.getItemRegistry()
            .getNameForObject(stack.getItem());
        return name != null && UNDRAWABLE_ITEMS.contains(name.toString());
    }

    /** Items whose renderer failed once ({@link #itemKey}): not drawn again, so they can't break the drawing. */
    private static final Set<String> BROKEN_ITEMS = new HashSet<>();

    private static String itemKey(ItemStack stack) {
        return Item.getIdFromItem(stack.getItem()) + ":" + stack.getItemDamage();
    }

    /**
     * Draws the item as in an inventory slot at 0,0, surviving modded renderers that fail or leave things behind: a
     * renderer that throws halfway left the game's tessellator in the middle of a drawing, and every item after it
     * then failed too (the icon picker went empty for good). Whatever happens, the tessellator is finished and the
     * matrices the renderer left pushed are taken off. False if the item can't be drawn.
     *
     * After a failure, the settings such renderers change on the way (GregTech's flask: the depth test, left so that
     * the world and the screens drew nothing anymore) are set back to the game's usual ones with plain GL calls,
     * which mods caching the GL state (Angelica in GTNH) see too.
     */
    public static boolean renderItemSafely(RenderItem renderItem, Minecraft mc, ItemStack stack) {
        String key = itemKey(stack);
        if (BROKEN_ITEMS.contains(key) || isUndrawable(stack)) {
            return false;
        }
        int modelview = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        float zLevel = renderItem.zLevel;
        boolean ok = true;
        try {
            renderItem.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        } catch (Throwable t) {
            ok = false;
            BROKEN_ITEMS.add(key);
            WayFarMap.LOG.warn("Could not draw the icon of item " + key + "; it is left out", t);
            finishTessellator();
            // Matrices it pushed and never took off (the game's item code pushes one around it); items are drawn in
            // the model-view mode.
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            for (int extra = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) - modelview; extra > 0; extra--) {
                GL11.glPopMatrix();
            }
            resetGuiState();
            // The game raises it before drawing and lowers it after, which a failure skips.
            renderItem.zLevel = zLevel;
        }
        return ok;
    }

    /** The game's usual settings for drawing screens, after a renderer that failed halfway changed some. */
    private static void resetGuiState() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Ends a drawing a failed renderer left open, without showing what it had: drawn shrunk to nothing (only the
     * matrix changes, put back right after).
     */
    private static void finishTessellator() {
        GL11.glPushMatrix();
        GL11.glScalef(0f, 0f, 0f);
        try {
            Tessellator.instance.draw();
        } catch (Throwable ignored) {
            // Wasn't drawing: nothing to finish.
        } finally {
            GL11.glPopMatrix();
        }
    }

    /**
     * Draws a waypoint marker (icon or dot, with the optional outline) centered on the given screen point.
     *
     * @param label draw the name below the marker
     */
    /** Laid over the marker of a disabled waypoint on the world map. */
    private static final int DISABLED_VEIL = 0xB0181A1E;

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

        if (!waypoint.enabled) {
            // Disabled: faded under a dark veil, over the item icon too.
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            Gui.drawRect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, DISABLED_VEIL);
            GL11.glPopAttrib();
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
        // A death marker's age on a second line, under the name.
        String age = ageLine(waypoint);
        textWidth = Math.max(textWidth, font.getStringWidth(age));
        int width = (int) Math.ceil((textWidth + 4) * textScale);
        int height = (int) Math.ceil((age.isEmpty() ? 10 : 19) * textScale);
        int x0 = (int) Math.round(sx) - width / 2;
        int y0 = (int) Math.round(sy) + Math.round(size / 2f) + 2;
        return new int[] { x0, y0, x0 + width, y0 + height };
    }

    public static void drawMapLabel(Waypoint waypoint, int[] rect, boolean fullName) {
        drawMapLabel(waypoint, rect, 1f);
    }

    /** Draws the label into the rectangle from {@link #getLabelRect}, with the text at {@code textScale}. */
    public static void drawMapLabel(Waypoint waypoint, int[] rect, float textScale) {
        // A death marker's label is tinted red, so it is never taken for an ordinary waypoint.
        Gui.drawRect(rect[0], rect[1], rect[2], rect[3], waypoint.death ? DEATH_LABEL_BG : Theme.LABEL_BG);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String name = mapLabelName(waypoint);
        GL11.glPushMatrix();
        GL11.glTranslatef(rect[0] + 2 * textScale, rect[1] + textScale, 0f);
        GL11.glScalef(textScale, textScale, 1f);
        font.drawString(name, 0, 0, waypoint.enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        font.drawString(
            labelDistance(waypoint),
            font.getStringWidth(name),
            0,
            waypoint.enabled ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED);
        String age = ageLine(waypoint);
        if (!age.isEmpty()) {
            font.drawString(age, 0, 9, waypoint.enabled ? DEATH_AGE_COLOR : Theme.TEXT_DISABLED);
        }
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
        int room = Config.waypointLabelMaxWidth - font.getStringWidth(labelSuffix(waypoint));
        return Theme.ellipsize(font, waypoint.name, Math.max(font.getStringWidth("..."), room));
    }

    /** The distance part of the map label: without a name it stands alone, so no gap before it. */
    private static String labelDistance(Waypoint waypoint) {
        String distance = labelSuffix(waypoint);
        return waypoint.name.isEmpty() ? distance.trim() : distance;
    }

    /** What follows the name on the map: the distance (a death marker's age goes on a line of its own). */
    private static String labelSuffix(Waypoint waypoint) {
        return distanceSuffix(waypoint);
    }

    /** A death marker's age for the line under its map label ("12 min ago"), or "" for none. */
    private static String ageLine(Waypoint waypoint) {
        return ageSuffix(waypoint).trim();
    }

    /** "  5 min ago" for a death marker that knows when the player died; "" otherwise. */
    public static String ageSuffix(Waypoint waypoint) {
        if (!waypoint.death || waypoint.diedAt <= 0) {
            return "";
        }
        long minutes = Math.max(0, (System.currentTimeMillis() - waypoint.diedAt) / 60_000);
        String age;
        if (minutes < 1) {
            age = I18n.format("wayfarmap.death.just_now");
        } else if (minutes < 60) {
            age = I18n.format("wayfarmap.death.minutes_ago", minutes);
        } else if (minutes < 60 * 24) {
            age = I18n.format("wayfarmap.death.hours_ago", minutes / 60);
        } else {
            age = I18n.format("wayfarmap.death.days_ago", minutes / (60 * 24));
        }
        return "  " + age;
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
    private static boolean drawFlatItem(ItemStack stack, float centerX, float centerY, float size, float alpha) {
        Item item = stack.getItem();
        if (item == null) {
            return false;
        }
        // The whole item as in the inventory (a block's icon alone is one face; own renderers have none). Its
        // picture isn't taken here, while the world is drawn, but next time the HUD is.
        if (ItemSprites.draw(stack, centerX, centerY, size, false, alpha)) {
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
                GL11.glColor4f(
                    ((color >> 16) & 0xFF) / 255f,
                    ((color >> 8) & 0xFF) / 255f,
                    (color & 0xFF) / 255f,
                    alpha);
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

    /** Pictures of icons asked for while the world was drawn are taken here, in the inventory's own state. */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            ItemSprites.takeQueued();
        }
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
                float alpha = nearFade(waypoint);
                if (waypoint.beam && alpha > 0f) {
                    renderBeam(mc, waypoint, event.partialTicks, alpha);
                }
            }
            for (Waypoint waypoint : waypoints) {
                renderInWorld(mc, waypoint);
            }
        }
        if (Mods.isVisualProspectingLoaded()) {
            Mods.draw(Mods.Addon.VISUAL_PROSPECTING, () -> ProspectingLayer.renderTrackedInWorld(mc, dimension));
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            Mods.draw(Mods.Addon.THAUMCRAFT_NODES, () -> ThaumcraftNodes.renderTrackedInWorld(mc, dimension));
        }
    }

    private static final ResourceLocation BEAM_TEXTURE = new ResourceLocation("textures/entity/beacon_beam.png");

    /**
     * A beacon beam through the waypoint's column, from the bottom of the world to the top, in its outline color
     * (white without one): a turning inner beam with a scrolling texture and a faint outer glow, like the vanilla
     * beacon. Hidden behind terrain like a real one.
     */
    private static void renderBeam(Minecraft mc, Waypoint waypoint, float partialTicks, float alpha) {
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
        boolean lightmap = disableLightmap();
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
        int beamAlpha = Math.round(32 * alpha);
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA(r, g, b, beamAlpha);
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
        tessellator.setColorRGBA(r, g, b, beamAlpha);
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
        restoreLightmap(lightmap);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Turns off the light map, the second texture the world is drawn with: left on, it tinted the markers' text and
     * the beams with the light of whatever was drawn last, which from some angles was black.
     *
     * @return whether it was on, for {@link #restoreLightmap}
     */
    private static boolean disableLightmap() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        boolean on = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        return on;
    }

    private static void restoreLightmap(boolean on) {
        if (on) {
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }

    private static void renderInWorld(Minecraft mc, Waypoint waypoint) {
        final ItemStack icon = waypoint.getIcon();
        final float alpha = nearFade(waypoint);
        if (alpha <= 0f) {
            return;
        }
        renderBillboard(
            mc,
            waypoint.x + 0.5,
            waypoint.y,
            waypoint.z + 0.5,
            labelText(waypoint, false) + ageSuffix(waypoint),
            waypoint.outlineColor,
            icon == null ? null : (cx, cy, size) -> drawFlatItem(icon, cx, cy, size, alpha),
            alpha,
            Config.waypointWorldLabels == Config.LABELS_HOVER
                ? LABEL_SHOWN.computeIfAbsent(waypoint, w -> new Smooth(0))
                : null);
    }

    /** How much of each waypoint's name shows in the world, eased in and out, while it shows only when looked at. */
    private static final Map<Waypoint, Smooth> LABEL_SHOWN = new WeakHashMap<>();
    /** Angle (radians) around a marker's icon within which the crosshair is on it, at the least. */
    private static final double LOOK_ANGLE_MIN = 0.012;

    /**
     * How much of the waypoint shows in the world: all of it from {@link Config#waypointFadeStart} blocks away,
     * fading out smoothly closer, gone at {@link Config#waypointFadeEnd}; always all of it with the fading off.
     */
    private static float nearFade(Waypoint waypoint) {
        if (!Config.waypointFadeNear) {
            return 1f;
        }
        double dx = waypoint.x + 0.5 - RenderManager.renderPosX;
        double dy = waypoint.y + 1.5 - RenderManager.renderPosY;
        double dz = waypoint.z + 0.5 - RenderManager.renderPosZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double end = Math.max(0, Config.waypointFadeEnd);
        double start = Math.max(end + 1, Config.waypointFadeStart);
        if (distance >= start) {
            return 1f;
        }
        if (distance <= end) {
            return 0f;
        }
        double t = (distance - end) / (start - end);
        // Eased at both ends: it starts fading softly and is gone softly.
        float alpha = (float) (t * t * (3 - 2 * t));
        // The font draws text with almost no alpha as opaque: below that the marker is simply gone.
        return alpha < 0.03f ? 0f : alpha;
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
        renderBillboard(mc, x, y, z, name, outlineColor, icon, 1f);
    }

    /** Same, at the given opacity (the icon draws itself, at the opacity it was given). */
    public static void renderBillboard(Minecraft mc, double x, double y, double z, String name, Integer outlineColor,
        BillboardIcon icon, float alpha) {
        renderBillboard(mc, x, y, z, name, outlineColor, icon, alpha, null);
    }

    /**
     * Same; with {@code label} the name and the distance show only while the crosshair is on the icon, coming and
     * going with it (eased by {@code label}), the icon alone otherwise.
     */
    private static void renderBillboard(Minecraft mc, double x, double y, double z, String name,
        Integer outlineColor, BillboardIcon icon, float alpha, Smooth label) {
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
        float labelAlpha = alpha;
        if (label != null) {
            labelAlpha *= (float) label.update(lookedAt(dx * factor, dy * factor, dz * factor, scale) ? 1 : 0, 14);
        }

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
        boolean lightmap = disableLightmap();
        // Fog darkened the text with the distance it is drawn at, not the waypoint's.
        GL11.glDisable(GL11.GL_FOG);
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

        // The font draws text with almost no alpha as opaque: below that the box is left out.
        if (labelAlpha >= 0.03f) {
            // The box plain: the waypoint's color frames the icon, as on the maps.
            fillRect(-boxHalf, top, boxHalf, bottom, faded(0xA0000000, labelAlpha));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            int textY = top + 2;
            if (!name.isEmpty()) {
                font.drawString(name, -nameWidth / 2, textY, faded(0xFFFFFFFF, labelAlpha));
                textY += 10;
            }
            font.drawString(distanceText, -distanceWidth / 2, textY, faded(0xFFC0C0C0, labelAlpha));
        }

        GL11.glEnable(GL11.GL_ALPHA_TEST);
        // The icon's cut-out edges as usual, its see-through parts scaled with the fading, so it fades with the box.
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f * alpha);
        if (outlineColor != null) {
            // As on the maps: a dark line, the waypoint's color around the icon, and a dark tile under it.
            fillRect(-10, top - 21, 10, top - 1, faded(0xFF000000, alpha));
            fillRect(-9, top - 20, 9, top - 2, faded(0xFF000000 | outlineColor, alpha));
            fillRect(-8, top - 19, 8, top - 3, faded(0xE0202020, alpha));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        boolean drawn = icon != null && icon.draw(0f, top - 11f, 16f);
        if (!drawn && outlineColor != null) {
            // No icon: the frame filled with the color, as on the maps.
            fillRect(-8, top - 19, 8, top - 3, faded(0xFF000000 | outlineColor, alpha));
        } else if (!drawn) {
            fillRect(-4, top - 12, 4, top - 4, faded(0xFF000000, alpha));
            fillRect(-3, top - 11, 3, top - 5, faded(0xFF000000 | DEFAULT_COLOR, alpha));
        }

        GL11.glPopAttrib();
        restoreLightmap(lightmap);
        GL11.glDepthMask(true);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Whether the crosshair is on a marker's icon: the angle between where the camera looks and the icon (drawn at
     * (x, y, z) from the camera, scaled by {@code scale}) is within the icon's half size seen from there.
     */
    private static boolean lookedAt(double x, double y, double z, float scale) {
        RenderManager view = RenderManager.instance;
        double yaw = Math.toRadians(view.playerViewY), pitch = Math.toRadians(view.playerViewX);
        double lookX = -Math.sin(yaw) * Math.cos(pitch), lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * Math.cos(pitch);
        // The icon sits 11 units over the anchor (the box's top), half of it 8 units wide; a little more is allowed.
        double iconY = y + 11 * scale;
        double length = Math.sqrt(x * x + iconY * iconY + z * z);
        if (length < 1e-6) {
            return true;
        }
        double cos = (x * lookX + iconY * lookY + z * lookZ) / length;
        double angle = Math.acos(Math.max(-1, Math.min(1, cos)));
        // The icon itself, and around it as far as set: it needn't be aimed at exactly.
        double zone = Math.toRadians(Math.max(0, Config.waypointLookZone));
        return angle <= Math.max(LOOK_ANGLE_MIN, Math.atan(10 * scale / length)) + zone;
    }

    /** The color with its alpha times {@code alpha}. */
    private static int faded(int color, float alpha) {
        return Math.round((color >>> 24) * alpha) << 24 | color & 0xFFFFFF;
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
