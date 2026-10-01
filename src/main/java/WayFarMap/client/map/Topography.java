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
 * Topographic view in JourneyMap's look: water in flat dark blue (lighter along the shore), land in flat bands of
 * height from dark green at the shore through gray-green and slate to lavender and nearly white peaks, with dark
 * brown contour lines between the bands. Made from the heights the surface map keeps, as one texture per region
 * drawn over it; rebuilt when the region or the settings change.
 */
public final class Topography {

    /** Height of the top block of the sea: up to it is water, land from one above. */
    private static final int SEA_LEVEL = 62;
    /** Water, and water along the shore (JourneyMap's colors). */
    private static final int DEEP_WATER = 0x10108A, SHALLOW_WATER = 0x0707B8;
    /** How far from land water counts as along the shore, in pixels. */
    private static final int SHORE = 2;
    /** Land from the shore up to {@link #TOP} and above, one color per band (JourneyMap's colors). */
    private static final int[] LAND = { 0x25432A, 0x2B4D30, 0x33533B, 0x3B5944, 0x435F4F, 0x4B6459, 0x536A63,
        0x5B706D, 0x637678, 0x6A7C82, 0x72818C, 0x7A8896, 0x828DA0, 0x8A92AA, 0x9198B4, 0x9DA5C4, 0xAAB2D3, 0xAAB9D3,
        0xAAC1D3, 0xAACBD3, 0xBCD0D3, 0xD0D3D3 };
    /** Height the last land color is reached at. */
    private static final int TOP = 180;
    private static final int CONTOUR_COLOR = 0x392410;
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
    private static int buildsLeft;
    private static IntBuffer buffer;

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
        int interval = Math.max(1, Config.topoContourInterval);
        if (buffer == null) {
            buffer = BufferUtils.createIntBuffer(MapRegion.SIZE * MapRegion.SIZE);
        }
        buffer.clear();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int h = height(region, x, z);
                int argb;
                if (h < 0) {
                    argb = 0;
                } else if (h <= SEA_LEVEL) {
                    argb = 0xFF000000 | (nearLand(region, x, z) ? SHALLOW_WATER : DEEP_WATER);
                } else {
                    int band = band(h, interval);
                    argb = 0xFF000000 | landColor(band, interval);
                    if (Config.topoContours) {
                        // A line where a neighbour is in a lower band: drawn once, on the upper side of the step.
                        for (int[] d : NEIGHBOURS) {
                            int other = height(region, x + d[0], z + d[1]);
                            if (other > SEA_LEVEL && band(other, interval) < band) {
                                argb = 0xFF000000 | CONTOUR_COLOR;
                                break;
                            }
                        }
                    }
                }
                buffer.put(argb);
            }
        }
        buffer.flip();
    }

    private static final int[][] NEIGHBOURS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

    /** Band of a land height: 0 from just above the sea, one more every {@code interval} blocks. */
    private static int band(int h, int interval) {
        return (h - SEA_LEVEL - 1) / interval;
    }

    /** Flat color of a band: the palette at the band's middle height, so the step only changes how fine it is. */
    private static int landColor(int band, int interval) {
        double middle = band * interval + interval / 2.0;
        int index = (int) Math.round(middle / (TOP - SEA_LEVEL - 1) * (LAND.length - 1));
        return LAND[Math.max(0, Math.min(LAND.length - 1, index))];
    }

    /** Land within {@link #SHORE} pixels: the water there is along the shore. */
    private static boolean nearLand(PixelSource region, int x, int z) {
        for (int dz = -SHORE; dz <= SHORE; dz++) {
            for (int dx = -SHORE; dx <= SHORE; dx++) {
                if (height(region, x + dx, z + dz) > SEA_LEVEL) {
                    return true;
                }
            }
        }
        return false;
    }

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
}
