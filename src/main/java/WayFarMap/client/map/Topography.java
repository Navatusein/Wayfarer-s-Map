package WayFarMap.client.map;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import WayFarMap.Config;

/**
 * Topographic view: the surface colored by its height like a physical map (blues below sea level, then greens,
 * yellows, browns, grays and white on the peaks), lightly shaded by its slopes, with contour lines every few blocks.
 * Made from the heights the surface map keeps, as one texture per region drawn over it; rebuilt when the region or
 * the settings change.
 */
public final class Topography {

    /** Height of the top block of the sea: water up to it is blue, land from one above. */
    private static final int SEA_LEVEL = 62;
    /** {height of the top block, RGB}, under the sea and on land. */
    private static final int[][] WATER = { { 0, 0x0B2545 }, { 40, 0x1D4E89 }, { SEA_LEVEL, 0x4F8FCF } };
    private static final int[][] LAND = { { SEA_LEVEL + 1, 0x4E8F3A }, { 72, 0x7DAF4C }, { 85, 0xC9C65E },
        { 100, 0xC99A4E }, { 120, 0x9C6A3E }, { 150, 0x8E837B }, { 185, 0xE6E6E6 }, { 255, 0xFFFFFF } };
    /** How much darker contour lines are than the ground; every fourth line is a stronger one. */
    private static final float CONTOUR = 0.62f, MAJOR_CONTOUR = 0.4f;
    /** Rebuilding a region's texture while it is being explored is throttled to this interval. */
    private static final long REBUILD_MS = 1000;
    /** Textures (re)built per frame, so turning the view on over many regions spreads over a few frames. */
    private static final int BUILDS_PER_FRAME = 4, LOD_BUILDS_PER_FRAME = 32;

    private static final class Overlay {

        int textureId = -1;
        int settings = -1;
        int regionChanges = -1;
        long builtAt;
    }

    private static final Map<PixelSource, Overlay> OVERLAYS = new WeakHashMap<>();
    /** Color of each height, for the current palette. */
    private static final int[] COLORS = new int[256];
    private static int buildsLeft;
    private static IntBuffer buffer;

    static {
        for (int y = 0; y < 256; y++) {
            COLORS[y] = paletteColor(y <= SEA_LEVEL ? WATER : LAND, y);
        }
    }

    private Topography() {}

    public static boolean isShown() {
        return Config.mapDisplayMode == Config.DISPLAY_TOPO;
    }

    /** Draws the topography of the surface map in the given rectangle (same geometry as MapDrawer#drawMap). */
    public static void draw(MapDimension surface, double centerX, double centerZ, double scale, int x, int y, int width,
        int height) {
        if (surface == null) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;
        int rx0 = (int) Math.floor(left) >> MapRegion.SHIFT;
        int rz0 = (int) Math.floor(top) >> MapRegion.SHIFT;
        int rx1 = (int) Math.floor(right) >> MapRegion.SHIFT;
        int rz1 = (int) Math.floor(bottom) >> MapRegion.SHIFT;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Tessellator tessellator = Tessellator.instance;
        // Same resolution as the map under it.
        boolean lod = WayFarMap.client.MapDrawer.useLod(scale);
        buildsLeft = lod ? LOD_BUILDS_PER_FRAME : BUILDS_PER_FRAME;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                PixelSource region = lod ? surface.requestLod(rx, rz) : surface.getLoadedRegion(rx, rz);
                if (region == null) {
                    continue;
                }
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }
                int texture = overlayTexture(region);
                if (texture == -1) {
                    continue;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
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

    /** Frees all textures (e.g. when leaving the world). */
    public static void clear() {
        Iterator<Overlay> it = OVERLAYS.values()
            .iterator();
        while (it.hasNext()) {
            Overlay overlay = it.next();
            if (overlay.textureId != -1) {
                GL11.glDeleteTextures(overlay.textureId);
            }
            it.remove();
        }
    }

    /** Frees the texture of a region or reduced copy whose own texture is freed. */
    static void forget(PixelSource source) {
        Overlay overlay = OVERLAYS.remove(source);
        if (overlay != null && overlay.textureId != -1) {
            GL11.glDeleteTextures(overlay.textureId);
        }
    }

    /** The settings the textures are built with, as one number: a change rebuilds them. */
    private static int settings() {
        return (Config.topoContours ? 1 : 0) | Config.topoContourInterval << 1;
    }

    /** Texture of the region, or -1 while it waits for its turn to be built. */
    private static int overlayTexture(PixelSource region) {
        Overlay overlay = OVERLAYS.get(region);
        if (overlay == null) {
            overlay = new Overlay();
            OVERLAYS.put(region, overlay);
        }
        long now = System.currentTimeMillis();
        boolean stale = overlay.settings != settings()
            || (overlay.regionChanges != region.getChanges() && now - overlay.builtAt >= REBUILD_MS);
        if (stale && buildsLeft <= 0) {
            // Out of budget this frame: an outdated texture is still better than a flash of the plain map.
            return overlay.settings == -1 || overlay.textureId == -1 ? -1 : overlay.textureId;
        }
        if (stale) {
            buildsLeft--;
        }
        if (overlay.textureId == -1) {
            overlay.textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, overlay.textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            stale = true;
        }
        if (stale) {
            build(region);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, overlay.textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                region.size(),
                region.size(),
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                buffer);
            overlay.settings = settings();
            overlay.regionChanges = region.getChanges();
            overlay.builtAt = now;
        }
        return overlay.textureId;
    }

    /** Fills {@link #buffer} with the topography of the region; transparent where the height is unknown. */
    private static void build(PixelSource region) {
        int size = region.size();
        // A pixel of a reduced copy covers several blocks: contour lines keep their spacing in blocks.
        int blocks = MapRegion.SIZE / size;
        int interval = Math.max(1, Config.topoContourInterval);
        if (buffer == null) {
            buffer = BufferUtils.createIntBuffer(MapRegion.SIZE * MapRegion.SIZE);
        }
        buffer.clear();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int h = height(region, x, z);
                if (h < 0) {
                    buffer.put(0);
                    continue;
                }
                // Slopes facing north-west are lighter, those facing south-east darker, as lit from the north-west.
                int north = height(region, x, z - 1), west = height(region, x - 1, z);
                int rise = (north < 0 ? 0 : h - north) + (west < 0 ? 0 : h - west);
                float shade = 1f + Math.max(-8, Math.min(8, rise / blocks)) * 0.03f;
                if (Config.topoContours) {
                    // A line where a neighbour is in a lower band: drawn once, on the upper side of the step.
                    int band = Math.floorDiv(h, interval);
                    int lower = Integer.MAX_VALUE;
                    for (int[] d : NEIGHBOURS) {
                        int other = height(region, x + d[0], z + d[1]);
                        if (other >= 0 && Math.floorDiv(other, interval) < band) {
                            lower = Math.min(lower, Math.floorDiv(other, interval));
                        }
                    }
                    if (lower != Integer.MAX_VALUE) {
                        // A stronger line where the step crosses a multiple of four intervals.
                        boolean major = Math.floorDiv(band, 4) != Math.floorDiv(lower, 4);
                        shade *= major ? MAJOR_CONTOUR : CONTOUR;
                    }
                }
                buffer.put(0xFF000000 | BlockColors.shade(COLORS[h], shade));
            }
        }
        buffer.flip();
    }

    private static final int[][] NEIGHBOURS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

    /** Height of the top block at the pixel, -1 if unknown or outside the region. */
    private static int height(PixelSource region, int x, int z) {
        int size = region.size();
        if (x < 0 || z < 0 || x >= size || z >= size || (region.getPixel(x, z) >>> 24) == 0) {
            return -1;
        }
        // The surface keeps the height to stand on: one above the top block, 0 when unknown.
        int extra = region.getExtra(x, z);
        return extra == 0 ? -1 : extra - 1;
    }

    /** The palette's color at the height, between its two nearest stops. */
    private static int paletteColor(int[][] stops, int y) {
        if (y <= stops[0][0]) {
            return stops[0][1];
        }
        for (int i = 1; i < stops.length; i++) {
            if (y <= stops[i][0]) {
                float t = (y - stops[i - 1][0]) / (float) (stops[i][0] - stops[i - 1][0]);
                return mix(stops[i - 1][1], stops[i][1], t);
            }
        }
        return stops[stops.length - 1][1];
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }
}
