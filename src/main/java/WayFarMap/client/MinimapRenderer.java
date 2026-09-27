package WayFarMap.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;
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

        // The map is drawn into an offscreen buffer the size of the minimap, which cuts it exactly to the square
        // (a turned map sticks out otherwise), and the buffer is then put on screen as a square or a circle.
        int factor = event.resolution.getScaleFactor();
        if (OpenGlHelper.isFramebufferEnabled()) {
            int pixels = size * factor;
            if (buffer == null) {
                buffer = new Framebuffer(pixels, pixels, false);
                buffer.setFramebufferColor(0f, 0f, 0f, 0f);
            } else if (buffer.framebufferWidth != pixels || buffer.framebufferHeight != pixels) {
                buffer.createBindFramebuffer(pixels, pixels);
            }
            buffer.framebufferClear();
            buffer.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, size, size, 0, 1000, 3000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0f, 0f, -2000f);
            try {
                drawLayers(mc, dimension, px, pz, scale, size, rotation, partialTicks);
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                mc.getFramebuffer()
                    .bindFramebuffer(true);
            }
            drawBuffer(x, y, size, round);
        } else {
            // Offscreen buffers are off in the video settings: cut to the square only.
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0f);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x * factor, mc.displayHeight - (y + size) * factor, size * factor, size * factor);
            try {
                drawLayers(mc, dimension, px, pz, scale, size, rotation, partialTicks);
            } finally {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glPopMatrix();
            }
        }

        if (Config.waypointsOnMinimap) {
            drawWaypoints(mc, px, pz, scale, x, y, size, round, rotation);
        }
        MapDrawer.drawPlayerArrow(centerX, centerY, yaw + rotation, 3.5f, 0xFFFFFFFF);
        drawCompass(mc.fontRenderer, centerX, centerY, half, round, rotation);

        int textY = y + size + 3;
        for (String line : lines) {
            mc.fontRenderer.drawStringWithShadow(line, x + size / 2 - mc.fontRenderer.getStringWidth(line) / 2, textY, 0xFFFFFF);
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

    private static Framebuffer buffer;

    /** The map and everything on it, into the square (0, 0, size, size), turned by {@code rotation} degrees. */
    private static void drawLayers(Minecraft mc, MapDimension dimension, double px, double pz, double scale, int size,
        float rotation, float partialTicks) {
        Gui.drawRect(0, 0, size, size, 0xFF0C0E11);
        // A turned map needs a bigger square under it to fill the corners.
        int inner = Config.minimapRotate ? (int) Math.ceil(size * Math.sqrt(2)) + 2 : size;
        GL11.glPushMatrix();
        GL11.glTranslated(size / 2.0, size / 2.0, 0);
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
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }

    /** Puts the offscreen buffer on screen as a square or a circle (its image is upside down, v = 0 at the bottom). */
    private static void drawBuffer(int x, int y, int size, boolean round) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // The buffer's alpha is meaningless after blending into it; the map inside is opaque anyway.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        buffer.bindFramebufferTexture();
        Tessellator tessellator = Tessellator.instance;
        if (round) {
            double half = size / 2.0;
            tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
            tessellator.addVertexWithUV(x + half, y + half, 0, 0.5, 0.5);
            for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
                double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
                double cos = Math.cos(a), sin = Math.sin(a);
                tessellator.addVertexWithUV(x + half + cos * half, y + half + sin * half, 0, 0.5 + cos * 0.5, 0.5 - sin * 0.5);
            }
            tessellator.draw();
        } else {
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(x, y + size, 0, 0, 0);
            tessellator.addVertexWithUV(x + size, y + size, 0, 1, 0);
            tessellator.addVertexWithUV(x + size, y, 0, 1, 1);
            tessellator.addVertexWithUV(x, y, 0, 0, 1);
            tessellator.draw();
        }
        buffer.unbindFramebufferTexture();
        GL11.glEnable(GL11.GL_BLEND);
    }

    private static final String[] COMPASS_LETTERS = { "N", "E", "S", "W" };
    private static final double[][] COMPASS_DIRECTIONS = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };

    /** N, E, S and W on the edge of the minimap, turning with it; north stands out in red. */
    private static void drawCompass(FontRenderer font, double cx, double cy, double half, boolean round,
        float rotation) {
        double edge = half - 5;
        for (int i = 0; i < COMPASS_LETTERS.length; i++) {
            double[] direction = rotate(COMPASS_DIRECTIONS[i][0], COMPASS_DIRECTIONS[i][1], rotation);
            // On a square the letter slides along the border, on a circle along the rim.
            double reach = round ? edge : edge / Math.max(Math.abs(direction[0]), Math.abs(direction[1]));
            String letter = COMPASS_LETTERS[i];
            // Placed at sub-pixel positions: rounding to GUI pixels made the letters jump while turning.
            GL11.glPushMatrix();
            GL11.glTranslated(
                cx + direction[0] * reach - font.getStringWidth(letter) / 2.0 + 1,
                cy + direction[1] * reach - 3,
                0);
            font.drawStringWithShadow(letter, 0, 0, i == 0 ? 0xFF5555 : 0xFFFFFF);
            GL11.glPopMatrix();
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static final int CIRCLE_SEGMENTS = 64;

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
