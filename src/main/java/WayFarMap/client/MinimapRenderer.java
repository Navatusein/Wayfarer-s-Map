package WayFarMap.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Draws the minimap in a corner of the HUD. */
public class MinimapRenderer {

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !Config.minimapEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        MapDimension dimension = MapManager.INSTANCE.getDimension();
        if (player == null || mc.theWorld == null
            || dimension == null
            || mc.gameSettings.showDebugInfo
            || mc.currentScreen instanceof GuiWorldMap) {
            return;
        }

        float partialTicks = event.partialTicks;
        double px = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double pz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        List<String> lines = new ArrayList<>();
        int blockX = MathHelper.floor_double(player.posX);
        int blockZ = MathHelper.floor_double(player.posZ);
        if (Config.minimapShowCoordinates) {
            lines.add(blockX + ", " + MathHelper.floor_double(player.boundingBox.minY) + ", " + blockZ);
        }
        int caveLayer = MapManager.INSTANCE.getActiveCaveLayer();
        if (caveLayer >= 0 && Config.mapDisplayMode != Config.DISPLAY_BIOMES) {
            lines.add(I18n.format("wayfarmap.gui.cave_layer", caveLayer * 16, caveLayer * 16 + 15));
        }
        if (Config.minimapShowBiome) {
            lines.add(mc.theWorld.getBiomeGenForCoords(blockX, blockZ).biomeName);
        }

        int size = Config.minimapSize;
        int screenWidth = event.resolution.getScaledWidth();
        int screenHeight = event.resolution.getScaledHeight();
        int x = Config.minimapCorner % 2 == 0 ? MARGIN : screenWidth - MARGIN - size;
        int y = Config.minimapCorner < 2 ? MARGIN : screenHeight - MARGIN - size - lines.size() * LINE_HEIGHT;
        double scale = Config.MINIMAP_ZOOMS[Math.max(0, Math.min(Config.MINIMAP_ZOOMS.length - 1, Config.minimapZoom))];

        boolean round = Config.minimapShape == Config.SHAPE_ROUND;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        // Turning with the player: the view direction (yaw + 90 degrees on the map) ends up pointing up.
        float rotation = Config.minimapRotate ? MathHelper.wrapAngleTo180_float(-180f - yaw) : 0f;
        double half = size / 2.0;
        double centerX = x + half, centerY = y + half;

        GL11.glPushMatrix();
        if (round) {
            fillCircle(centerX, centerY, half + 2, Theme.BORDER);
            fillCircle(centerX, centerY, half + 1, Theme.PANEL);
            fillCircle(centerX, centerY, half, 0xFF0C0E11);
        } else {
            Gui.drawRect(x - 2, y - 2, x + size + 2, y + size + 2, Theme.PANEL);
            Theme.outline(x - 2, y - 2, x + size + 2, y + size + 2, Theme.BORDER);
            Gui.drawRect(x, y, x + size, y + size, 0xFF0C0E11);
        }

        // A turned map needs a bigger square under it to fill the corners; the shape cuts everything to size.
        int inner = Config.minimapRotate ? (int) Math.ceil(size * Math.sqrt(2)) + 2 : size;
        boolean masked = round || Config.minimapRotate;
        if (masked) {
            beginMask(centerX, centerY, half, inner / 2.0 + 1, round);
        }
        GL11.glPushMatrix();
        GL11.glTranslated(centerX, centerY, 0);
        GL11.glRotatef(rotation, 0f, 0f, 1f);
        GL11.glTranslated(-inner / 2.0, -inner / 2.0, 0);
        MapDrawer.iconRotation = rotation;
        try {
            MapDrawer.drawMap(dimension, px, pz, scale, 0, 0, inner, inner);
            if (Config.chunkGrid) {
                MapDrawer.drawChunkGrid(px, pz, scale, 0, 0, inner, inner);
            }
            if (Mods.isVisualProspectingLoaded()) {
                int dimensionId = mc.theWorld.provider.dimensionId;
                if (Config.showUndergroundFluids) {
                    ProspectingLayer.drawFluids(dimensionId, px, pz, scale, 0, 0, inner, inner, true);
                }
                if (Config.showOreVeins) {
                    ProspectingLayer.drawOreVeins(dimensionId, px, pz, scale, 0, 0, inner, inner, true, 0, 0);
                }
            }
            MapDrawer.drawEntities(mc, px, pz, scale, 0, 0, inner, inner, partialTicks, 6f, false);
        } finally {
            MapDrawer.iconRotation = 0f;
            GL11.glPopMatrix();
            if (masked) {
                endMask(centerX, centerY, inner / 2.0 + 1);
            }
        }

        if (Config.waypointsOnMinimap) {
            drawWaypoints(mc, px, pz, scale, x, y, size, round, rotation);
        }
        MapDrawer.drawPlayerArrow(centerX, centerY, yaw + rotation, 3.5f, 0xFFFFFFFF);

        // "N" where north is: at the top, or on the edge when the map turns.
        FontRenderer font = mc.fontRenderer;
        double[] north = rotate(0, -1, rotation);
        double edge = half - 6;
        double reach = round ? edge : edge / Math.max(Math.abs(north[0]), Math.abs(north[1]));
        int nx = (int) Math.round(centerX + north[0] * reach);
        int ny = (int) Math.round(centerY + north[1] * reach);
        font.drawStringWithShadow("N", nx - font.getStringWidth("N") / 2, ny - 3, 0xFFFFFF);
        int textY = y + size + 3;
        for (String line : lines) {
            font.drawStringWithShadow(line, x + size / 2 - font.getStringWidth(line) / 2, textY, 0xFFFFFF);
            textY += LINE_HEIGHT;
        }

        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }

    /** Turns the offset (dx, dz) by the map's rotation in degrees, like glRotatef does on screen. */
    private static double[] rotate(double dx, double dz, float degrees) {
        if (degrees == 0f) {
            return new double[] { dx, dz };
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r), sin = Math.sin(r);
        return new double[] { dx * cos - dz * sin, dx * sin + dz * cos };
    }

    // Depth values in the HUD's orthographic projection (z from -1000 to 1000, larger is nearer).
    private static final double MASK_BLOCK_Z = 200, MASK_OPEN_Z = -200, MASK_CLEAR_Z = -999;

    /**
     * Cuts what is drawn next to the minimap's shape using the depth buffer (always there, unlike stencil in
     * 1.7.10): the square around it is marked "near" so drawing at z = 0 fails, and the shape itself "far".
     */
    private static void beginMask(double cx, double cy, double half, double outer, boolean round) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        depthQuad(cx - outer, cy - outer, cx + outer, cy + outer, MASK_BLOCK_Z);
        if (round) {
            depthCircle(cx, cy, half, MASK_OPEN_Z);
        } else {
            depthQuad(cx - half, cy - half, cx + half, cy + half, MASK_OPEN_Z);
        }
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Puts the depth back to "empty" (as the HUD starts with) and restores the GL state. */
    private static void endMask(double cx, double cy, double outer) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        depthQuad(cx - outer, cy - outer, cx + outer, cy + outer, MASK_CLEAR_Z);
        GL11.glPopAttrib();
    }

    private static void depthQuad(double x0, double y0, double x1, double y1, double z) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertex(x0, y1, z);
        tessellator.addVertex(x1, y1, z);
        tessellator.addVertex(x1, y0, z);
        tessellator.addVertex(x0, y0, z);
        tessellator.draw();
    }

    private static final int CIRCLE_SEGMENTS = 64;

    private static void depthCircle(double cx, double cy, double radius, double z) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
        tessellator.addVertex(cx, cy, z);
        for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            tessellator.addVertex(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, z);
        }
        tessellator.draw();
    }

    private static void fillCircle(double cx, double cy, double radius, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(cx, cy, 0);
        for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            tessellator.addVertex(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, 0);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Waypoints outside the minimap stick to its border, so their direction stays visible. */
    private static void drawWaypoints(Minecraft mc, double px, double pz, double scale, int x, int y, int size,
        boolean round, float rotation) {
        double half = size / 2.0;
        double limit = half - 5;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(mc.theWorld.provider.dimensionId)) {
            double[] offset = rotate((waypoint.x + 0.5 - px) * scale, (waypoint.z + 0.5 - pz) * scale, rotation);
            double dx = offset[0], dz = offset[1];
            double outside = round ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
            if (outside > limit) {
                dx *= limit / outside;
                dz *= limit / outside;
            }
            WaypointRenderer.drawMapMarker(waypoint, x + half + dx, y + half + dz, 8f, false);
        }
    }
}
