package WayFarMap.client.integration;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.sinthoras.visualprospecting.Config;
import com.sinthoras.visualprospecting.Utils;
import com.sinthoras.visualprospecting.VP;
import com.sinthoras.visualprospecting.database.ClientCache;
import com.sinthoras.visualprospecting.database.OreVeinPosition;
import com.sinthoras.visualprospecting.database.UndergroundFluidPosition;
import com.sinthoras.visualprospecting.database.veintypes.VeinType;
import com.sinthoras.visualprospecting.database.veintypes.VeinTypeCaching;
import com.sinthoras.visualprospecting.integration.model.locations.OreVeinLocation;
import com.sinthoras.visualprospecting.integration.model.locations.UndergroundFluidLocation;
import com.sinthoras.visualprospecting.integration.model.render.DimensionStoneBackground;

import WayFarMap.WayFarMap;
import WayFarMap.client.waypoint.WaypointRenderer;
import gregtech.api.interfaces.IIconContainer;

/**
 * VisualProspecting (GTNH fork) ore veins and underground fluids on the maps, drawn the way VisualProspecting's own
 * {@code OreVeinRenderStep} and {@code UndergroundFluidRenderStep} draw them for JourneyMap.
 * <p>
 * Only touch this class after {@link Mods#isVisualProspectingLoaded()}.
 */
public final class ProspectingLayer {

    private static final ResourceLocation DEPLETED_TEXTURE = new ResourceLocation(
        "visualprospecting",
        "textures/depleted.png");
    private static final long REFRESH_MS = 1000;
    private static final int LABEL_BACKGROUND = 0xB4000000;

    private static int cachedDimension = Integer.MIN_VALUE;
    private static long lastRefresh;
    private static List<OreVeinLocation> veins = Collections.emptyList();
    /** Database entry of each vein location, for its height range. */
    private static Map<OreVeinLocation, OreVeinPosition> positions = new IdentityHashMap<>();
    private static List<UndergroundFluidLocation> fluids = Collections.emptyList();

    /** Vein under the mouse on the last drawn fullscreen map, or null. */
    private static OreVeinLocation hovered;

    /** The vein being tracked: shown in the world with its distance, framed in gold on the maps. */
    private static final class Tracked {

        final int dimension, chunkX, chunkZ;
        final double x, z;
        final int y;
        final OreVeinLocation location;

        Tracked(OreVeinLocation location, OreVeinPosition position) {
            this.location = location;
            this.dimension = position.dimensionId;
            this.chunkX = position.chunkX;
            this.chunkZ = position.chunkZ;
            this.x = location.getBlockX();
            this.z = location.getBlockZ();
            // Middle of the vein's height range, where the ores are.
            this.y = (position.veinType.minBlockY + position.veinType.maxBlockY) / 2;
        }

        boolean is(OreVeinPosition position) {
            return position.dimensionId == dimension && position.chunkX == chunkX && position.chunkZ == chunkZ;
        }
    }

    private static Tracked tracked;
    private static final int TRACKED_COLOR = 0xFFD700;

    private ProspectingLayer() {}

    /** Like VisualProspecting's onOpenMap: dims the veins that don't match the NEI search. */
    public static void onOpenMap() {
        try {
            VeinTypeCaching.recalculateSearch(Utils.getNEISearchPattern(), Utils.getNEISearchItemFilter());
        } catch (Throwable t) {
            // NEI search highlighting is optional.
        }
        lastRefresh = 0;
    }

    private static void refresh(int dimension) {
        long now = System.currentTimeMillis();
        if (dimension == cachedDimension && now - lastRefresh < REFRESH_MS) {
            return;
        }
        cachedDimension = dimension;
        lastRefresh = now;

        // One bad entry (e.g. a vein type whose ore has no texture) is skipped instead of hiding everything.
        List<OreVeinLocation> newVeins = new ArrayList<>();
        Map<OreVeinLocation, OreVeinPosition> newPositions = new IdentityHashMap<>();
        try {
            for (OreVeinPosition vein : ClientCache.instance.getAllOreVeins()) {
                try {
                    if (vein.veinType != null && vein.veinType != VeinType.NO_VEIN && vein.dimensionId == dimension) {
                        OreVeinLocation location = new OreVeinLocation(vein);
                        newVeins.add(location);
                        newPositions.put(location, vein);
                    }
                } catch (Throwable t) {
                    warnOnce("vein " + (vein.veinType != null ? vein.veinType.name : "?"), t);
                }
            }
        } catch (Throwable t) {
            warnOnce("ore veins", t);
        }
        List<UndergroundFluidLocation> newFluids = new ArrayList<>();
        try {
            for (UndergroundFluidPosition fluid : ClientCache.instance.getAllUndergroundFluids()) {
                try {
                    if (fluid.isProspected() && fluid.dimensionId == dimension) {
                        UndergroundFluidLocation location = new UndergroundFluidLocation(fluid);
                        location.setActive(true);
                        newFluids.add(location);
                    }
                } catch (Throwable t) {
                    warnOnce("fluid " + (fluid.fluid != null ? fluid.fluid.getName() : "?"), t);
                }
            }
        } catch (Throwable t) {
            warnOnce("underground fluids", t);
        }
        veins = newVeins;
        positions = newPositions;
        fluids = newFluids;
    }

    private static final Set<String> WARNED = new HashSet<>();

    /** Logs a problem with VisualProspecting data once per kind, so a broken entry doesn't flood the log. */
    private static void warnOnce(String what, Throwable t) {
        if (WARNED.add(what)) {
            WayFarMap.LOG.warn("Skipping VisualProspecting " + what + " on the map", t);
        }
    }

    /** JourneyMap-style zoom level: 2^zoom screen pixels per block. */
    private static double zoomLevel(double guiScale) {
        Minecraft mc = Minecraft.getMinecraft();
        int factor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        return Math.log(guiScale * factor) / Math.log(2);
    }

    // ---------------------------------------------------------------- underground fluids

    public static void drawFluids(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap) {
        refresh(dimension);
        if (fluids.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double zoom = zoomLevel(scale);
        int fieldBlocks = VP.undergroundFluidSizeChunkX * VP.chunkWidth;
        double fieldSize = fieldBlocks * scale;
        Minecraft mc = Minecraft.getMinecraft();
        double pixel = 1.0 / new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();

        begin();
        for (UndergroundFluidLocation location : fluids) {
            double fx = x + (Math.floor(location.getBlockX()) - left) * scale;
            double fy = y + (Math.floor(location.getBlockZ()) - top) * scale;
            if (fx + fieldSize < x || fy + fieldSize < y || fx > x + width || fy > y + height) {
                continue;
            }
            int max = location.getMaxProduction();
            if (zoom >= Config.minZoomLevelForUndergroundFluidDetails - 1 && max > 0) {
                drawFluidChunks(location, fx, fy, scale, zoom, x, y, width, height, minimap);
            }
            if (max > 0) {
                hollowRect(fx, fy, fieldSize, fieldSize, 2 * pixel, location.getColor(), 255, x, y, width, height);
            } else {
                hollowRect(fx, fy, fieldSize, fieldSize, 2 * pixel, 0xFFFFFF, 74, x, y, width, height);
            }
        }
        end();

        if (minimap || zoom < Config.minZoomLevelForUndergroundFluidDetails - 3) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        for (UndergroundFluidLocation location : fluids) {
            double fx = x + (Math.floor(location.getBlockX()) - left) * scale;
            double fy = y + (Math.floor(location.getBlockZ()) - top) * scale;
            if (fieldSize < 40 || fx + fieldSize < x || fy + fieldSize < y || fx > x + width || fy > y + height) {
                continue;
            }
            int max = location.getMaxProduction();
            String title = I18n.format("visualprospecting.empty");
            String values = null;
            if (max > 0) {
                title = location.getFluid()
                    .getLocalizedName();
                values = MessageFormat.format(
                    "{0}-{1} L/Op",
                    formatAmount(location.getMinProduction() >> 1),
                    formatAmount(max >> 1));
            }
            int maxWidth = (int) fieldSize - 6;
            double labelX = fx + fieldSize / 2;
            label(font, ellipsize(font, title, maxWidth), labelX, fy + 3, 0xFFFFFF);
            if (values != null) {
                label(font, ellipsize(font, values, maxWidth), labelX, fy + 14, 0xFFFFFF);
            }
        }
    }

    private static void drawFluidChunks(UndergroundFluidLocation location, double fx, double fy, double scale,
        double zoom, int x, int y, int width, int height, boolean minimap) {
        int min = location.getMinProduction();
        int max = location.getMaxProduction();
        int color = location.getColor();
        int[][] chunks = location.getChunks();
        float range = max - min + 1;
        boolean highlightPeak = max >= 10;
        boolean labels = !minimap && zoom >= Config.minZoomLevelForUndergroundFluidDetails;
        double cell = VP.chunkWidth * scale;
        List<Object[]> cellLabels = labels ? new ArrayList<>() : null;
        for (int cx = 0; cx < VP.undergroundFluidSizeChunkX; cx++) {
            for (int cz = 0; cz < VP.undergroundFluidSizeChunkZ; cz++) {
                int amount = chunks[cx][cz];
                if (amount <= 0) {
                    continue;
                }
                double cellX = fx + cx * cell;
                double cellY = fy + cz * cell;
                int alpha = range > 1 ? (int) ((amount - min) / range * 255) : 10;
                rect(cellX, cellY, cell, cell, color, alpha, x, y, width, height);
                if (highlightPeak && amount >= max) {
                    hollowRect(cellX, cellY, cell, cell, Math.max(1, cell / 16), 0xFFD700, 204, x, y, width, height);
                }
                if (labels) {
                    cellLabels.add(
                        new Object[] { MessageFormat.format("{0} L/Op", formatAmount(amount >> 1)), cellX + cell / 2,
                            cellY + cell / 2 - 4 });
                }
            }
        }
        if (labels && !cellLabels.isEmpty()) {
            end();
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            for (Object[] cellLabel : cellLabels) {
                double lx = (Double) cellLabel[1], ly = (Double) cellLabel[2];
                if (lx > x && ly > y && lx < x + width && ly < y + height) {
                    label(font, (String) cellLabel[0], lx, ly, 0xFFFFFF);
                }
            }
            begin();
        }
    }

    /** Same formatting as VisualProspecting: 950, 1.2k, 3k. */
    private static String formatAmount(int amount) {
        if (amount < 1000) {
            return String.valueOf(amount);
        }
        int roundedTenths = (amount + 50) / 100;
        if (roundedTenths % 10 == 0) {
            return (roundedTenths / 10) + "k";
        }
        return (roundedTenths / 10) + "." + (roundedTenths % 10) + "k";
    }

    // ---------------------------------------------------------------- ore veins

    /**
     * Draws the ore vein icons (and their names when zoomed in on the fullscreen map).
     *
     * @return nothing; the vein under the mouse is remembered for {@link #getHoveredTooltip()} and the right click menu
     */
    public static void drawOreVeins(int dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, boolean minimap, int mouseX, int mouseY) {
        refresh(dimension);
        if (!minimap) {
            hovered = null;
        }
        if (veins.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double zoom = zoomLevel(scale);
        double veinSpacing = VP.oreVeinSizeChunkX * VP.chunkWidth * scale;
        double size = minimap ? Math.max(5, Math.min(10, veinSpacing * 0.6))
            : Math.max(6, Math.min(16, veinSpacing * 0.6));
        double half = size / 2;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;

        List<OreVeinLocation> visible = new ArrayList<>();
        for (OreVeinLocation vein : veins) {
            double sx = x + (vein.getBlockX() - left) * scale;
            double sy = y + (vein.getBlockZ() - top) * scale;
            if (sx - half < x || sy - half < y || sx + half > x + width || sy + half > y + height) {
                continue;
            }
            visible.add(vein);
            if (!minimap && mouseX >= sx - half && mouseX <= sx + half && mouseY >= sy - half && mouseY <= sy + half) {
                hovered = vein;
            }
        }

        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (OreVeinLocation vein : visible) {
            double sx = x + (vein.getBlockX() - left) * scale - half;
            double sy = y + (vein.getBlockZ() - top) * scale - half;
            drawVeinIcon(vein, sx, sy, size);
            if (!vein.drawSearchHighlight() || vein.isDepleted()) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                begin();
                rect(sx, sy, size, size, 0x000000, 150, x, y, width, height);
                end();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                if (vein.isDepleted()) {
                    mc.getTextureManager()
                        .bindTexture(DEPLETED_TEXTURE);
                    textureQuad(sx, sy, size, 0, 0, 1, 1, 0xFFFFFF);
                    mc.getTextureManager()
                        .bindTexture(TextureMap.locationBlocksTexture);
                }
            }
            if (isTrackedLocation(vein)) {
                // Gold frame, like VisualProspecting's "active as waypoint".
                begin();
                hollowRect(
                    sx - 1,
                    sy - 1,
                    size + 2,
                    size + 2,
                    Math.max(1, size / 8),
                    TRACKED_COLOR,
                    204,
                    x,
                    y,
                    width,
                    height);
                end();
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationBlocksTexture);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);

        if (minimap || zoom < Config.minZoomLevelForOreLabel) {
            return;
        }
        // Names above the icons, skipping those that would overlap one already placed.
        List<double[]> placed = new ArrayList<>();
        for (OreVeinLocation vein : visible) {
            if (vein.isDepleted()) {
                continue;
            }
            String name = ellipsize(font, vein.getName(), 120);
            int w = font.getStringWidth(name);
            double lx = x + (vein.getBlockX() - left) * scale;
            double ly = y + (vein.getBlockZ() - top) * scale - half - 11;
            double[] box = { lx - w / 2.0 - 2, ly - 1, lx + w / 2.0 + 2, ly + 9 };
            if (vein != hovered && overlaps(box, placed)) {
                continue;
            }
            placed.add(box);
            label(font, name, lx, ly, vein.drawSearchHighlight() ? 0xFFFFFF : 0x7F7F7F);
        }
    }

    /** Tooltip lines of the vein under the mouse, or null. */
    public static List<String> getHoveredTooltip() {
        if (hovered == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (hovered.isDepleted()) {
            lines.add(hovered.getDepletedHint());
        }
        if (isTrackedLocation(hovered)) {
            lines.add("\u00a76" + I18n.format("wayfarmap.gui.vein_tracked"));
        }
        lines.add(hovered.getName());
        if (!hovered.isDepleted()) {
            lines.addAll(hovered.getMaterialNames());
        }
        lines.add("§8" + I18n.format("wayfarmap.gui.vein_toggle_hint"));
        return lines;
    }

    /**
     * The vein under the mouse, as an opaque handle for the other methods; null if none. Callers keep it (e.g. while a
     * menu is open) instead of asking again, since the vein under the mouse changes as the mouse moves.
     */
    public static Object getHoveredVein() {
        OreVeinPosition position = hovered == null ? null : positions.get(hovered);
        return position == null ? null : new Handle(hovered, position);
    }

    /** A vein kept by a caller; stays valid when the vein list is rebuilt. */
    private static final class Handle {

        final OreVeinLocation location;
        final OreVeinPosition position;

        Handle(OreVeinLocation location, OreVeinPosition position) {
            this.location = location;
            this.position = position;
        }
    }

    public static boolean isDepleted(Object vein) {
        return ((Handle) vein).location.isDepleted();
    }

    /** Marks the vein depleted, or not depleted anymore. */
    public static void toggleDepleted(Object vein) {
        ((Handle) vein).location.toggleOreVein();
        lastRefresh = 0;
    }

    public static boolean isTracked(Object vein) {
        return tracked != null && tracked.is(((Handle) vein).position);
    }

    private static boolean isTrackedLocation(OreVeinLocation location) {
        OreVeinPosition position = positions.get(location);
        return tracked != null && position != null && tracked.is(position);
    }

    /** Starts tracking the vein, or stops if it is the tracked one. */
    public static void toggleTracked(Object vein) {
        Handle handle = (Handle) vein;
        tracked = isTracked(vein) ? null : new Tracked(handle.location, handle.position);
    }

    /** Draws the tracked vein in the world: its ore icon, name and distance, like a waypoint. */
    public static void renderTrackedInWorld(Minecraft mc, int dimension) {
        final Tracked target = tracked;
        if (target == null || target.dimension != dimension) {
            return;
        }
        String name = I18n.format("wayfarmap.gui.vein_tracked_name", strip(target.location.getName()));
        WaypointRenderer.renderBillboard(mc, target.x, target.y, target.z, name, TRACKED_COLOR, (cx, cy, size) -> {
            drawVeinIcon(target.location, cx - size / 2, cy - size / 2, size);
            return true;
        });
    }

    private static String strip(String text) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return plain != null ? plain : text;
    }

    /** Stone background and ore icon of the vein, with the top-left corner at (sx, sy). */
    private static void drawVeinIcon(OreVeinLocation vein, double sx, double sy, double size) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        try {
            iconQuad(DimensionStoneBackground.getBackgroundIcon(vein.getDimensionId()), sx, sy, size, 0xFFFFFF);
            IIconContainer ore = vein.getIconFromPrimaryOre();
            iconQuad(ore.getIcon(), sx, sy, size, vein.getColor());
            IIcon overlay = ore.getOverlayIcon();
            if (overlay != null) {
                iconQuad(overlay, sx, sy, size, 0xFFFFFF);
            }
        } catch (Throwable t) {
            // Missing textures only cost the icon.
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    // ---------------------------------------------------------------- drawing helpers

    private static void begin() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void end() {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Filled rectangle clipped to the map rectangle. */
    private static void rect(double rx, double ry, double w, double h, int rgb, int alpha, int x, int y, int width,
        int height) {
        double x0 = Math.max(rx, x), y0 = Math.max(ry, y);
        double x1 = Math.min(rx + w, x + width), y1 = Math.min(ry + h, y + height);
        if (x1 <= x0 || y1 <= y0 || alpha <= 0) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(rgb & 0xFFFFFF, Math.min(255, alpha));
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
    }

    private static void hollowRect(double rx, double ry, double w, double h, double thickness, int rgb, int alpha,
        int x, int y, int width, int height) {
        rect(rx, ry, w, thickness, rgb, alpha, x, y, width, height);
        rect(rx, ry + h - thickness, w, thickness, rgb, alpha, x, y, width, height);
        rect(rx, ry + thickness, thickness, h - 2 * thickness, rgb, alpha, x, y, width, height);
        rect(rx + w - thickness, ry + thickness, thickness, h - 2 * thickness, rgb, alpha, x, y, width, height);
    }

    private static void iconQuad(IIcon icon, double sx, double sy, double size, int rgb) {
        if (icon != null) {
            textureQuad(sx, sy, size, icon.getMinU(), icon.getMinV(), icon.getMaxU(), icon.getMaxV(), rgb);
        }
    }

    private static void textureQuad(double sx, double sy, double size, double u0, double v0, double u1, double v1,
        int rgb) {
        GL11.glColor4f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(sx, sy + size, 0, u0, v1);
        tessellator.addVertexWithUV(sx + size, sy + size, 0, u1, v1);
        tessellator.addVertexWithUV(sx + size, sy, 0, u1, v0);
        tessellator.addVertexWithUV(sx, sy, 0, u0, v0);
        tessellator.draw();
    }

    /** Text centered on {@code cx} with a dark background, like VisualProspecting's labels. */
    private static void label(FontRenderer font, String text, double cx, double ty, int color) {
        int w = font.getStringWidth(text);
        int lx = (int) Math.round(cx - w / 2.0);
        int ly = (int) Math.round(ty);
        begin();
        rect(lx - 2, ly - 1, w + 4, 10, LABEL_BACKGROUND & 0xFFFFFF, LABEL_BACKGROUND >>> 24, lx - 2, ly - 1, w + 4, 10);
        end();
        font.drawString(text, lx, ly, color);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth("..."))) + "...";
    }

    private static boolean overlaps(double[] box, List<double[]> others) {
        for (double[] other : others) {
            if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) {
                return true;
            }
        }
        return false;
    }
}
