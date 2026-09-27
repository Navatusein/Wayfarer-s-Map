package WayFarMap.client.map;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import WayFarMap.client.MapDrawer;
import WayFarMap.client.gui.ui.ScaledScreen;

/**
 * Draws the world map in 3D, as an isometric view like Dynmap's: every explored column is a block of its map color
 * standing as high as the ground there, with its sides shaded, so hills, cliffs, trees and buildings stand out.
 * <p>
 * The map is cut into tiles of 64x64 cells; a cell is one column up close and 2, 4, 8... columns (averaged, like the
 * zoomed out flat map) farther out, so there are never many more cells than screen pixels. Each tile is turned into a
 * display list once, in the background of drawing (a few milliseconds per frame, nearest first), and built again
 * when its part of the map changes. Tiles that aren't drawn for a while are freed.
 */
public final class IsoMapRenderer {

    /** Cells per tile side. */
    private static final int CELLS = 64;
    /** A cell is at least this many screen pixels wide: more detail than that can't be seen. */
    private static final double MIN_CELL_PIXELS = 3;
    private static final int MAX_LOD = 64;
    /** Time spent building tiles per frame. */
    private static final long BUILD_BUDGET_NANOS = 5_000_000L;
    /** A tile whose part of the map keeps changing (around the player) is rebuilt at most this often. */
    private static final long REBUILD_MS = 1000;
    private static final long UNUSED_MS = 10_000;
    private static final int MAX_TILES = 3000;
    /** Blocks of wall below the edge of the explored area, so it doesn't look like paper. */
    private static final int EDGE_DEPTH = 2;
    /** Shade of the sides facing east/west and north/south (the top is as on the flat map). */
    private static final float SIDE_X = 0.80f, SIDE_Z = 0.64f;
    private static final long NOT_READY = Long.MIN_VALUE;

    private IsoMapRenderer() {}

    private static final class Key {

        final MapDimension colors, heights;
        final int lod, tx, tz;

        Key(MapDimension colors, MapDimension heights, int lod, int tx, int tz) {
            this.colors = colors;
            this.heights = heights;
            this.lod = lod;
            this.tx = tx;
            this.tz = tz;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return k.colors == colors && k.heights == heights && k.lod == lod && k.tx == tx && k.tz == tz;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(colors) * 31 + System.identityHashCode(heights);
            return ((hash * 31 + lod) * 31 + tx) * 31 + tz;
        }
    }

    private static final class Tile {

        /** Display list, or -1 while there is nothing to draw (nothing explored in it). */
        int list = -1;
        long signature;
        long builtAt;
        long lastUsed;
    }

    private static final Map<Key, Tile> tiles = new HashMap<>();
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static int frames;

    /** Columns per cell at this zoom (screen pixels per block). */
    public static int lodFor(double scale) {
        double pixels = scale * ScaledScreen.currentFactor();
        int lod = 1;
        while (lod < MAX_LOD && pixels * lod < MIN_CELL_PIXELS) {
            lod *= 2;
        }
        return lod;
    }

    /**
     * Draws the map into the screen rectangle.
     *
     * @param colors  the map whose colors are shown (the surface, or the biome map)
     * @param heights the surface map, whose extra byte is the height of each column
     */
    public static void draw(IsoView view, MapDimension colors, MapDimension heights, int x, int y, int width,
        int height) {
        long start = System.nanoTime();
        long now = System.currentTimeMillis();
        int lod = lodFor(view.scale);
        int span = CELLS * lod;

        // The tiles that show up on the screen, nearest to the center first (they are built first).
        double[] box = view.visibleBounds(x, y, width, height);
        int tx0 = Math.floorDiv((int) Math.floor(box[0]), span), tz0 = Math.floorDiv((int) Math.floor(box[1]), span);
        int tx1 = Math.floorDiv((int) Math.floor(box[2]), span), tz1 = Math.floorDiv((int) Math.floor(box[3]), span);
        List<int[]> visible = new ArrayList<>();
        int ctx = Math.floorDiv((int) Math.floor(view.centerX), span);
        int ctz = Math.floorDiv((int) Math.floor(view.centerZ), span);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                if (onScreen(view, tx * span, tz * span, span, x, y, width, height)) {
                    visible.add(new int[] { tx, tz, Math.max(Math.abs(tx - ctx), Math.abs(tz - ctz)) });
                }
            }
        }
        visible.sort((a, b) -> Integer.compare(a[2], b[2]));

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDepthMask(true);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glPushMatrix();
        GL11.glTranslated(view.screenX, view.screenY, 0);
        view.writeMatrix(MATRIX, view.depthScale(height));
        GL11.glMultMatrix(MATRIX);

        int built = 0;
        Set<Key> fallbacksDrawn = new HashSet<>();
        for (int[] at : visible) {
            Key key = new Key(colors, heights, lod, at[0], at[1]);
            Tile tile = tiles.get(key);
            boolean mayBuild = built == 0 || System.nanoTime() - start < BUILD_BUDGET_NANOS;
            // Missing tiles ask for their map data even when there is no time left to build them this frame, so
            // the reads run meanwhile.
            if (tile == null || mayBuild && now - tile.builtAt >= REBUILD_MS) {
                Sources sources = sources(key);
                long signature = sources == null ? NOT_READY : signature(sources);
                if (mayBuild && signature != NOT_READY && (tile == null || tile.signature != signature)) {
                    tile = build(key, sources, tile, signature, now);
                    built++;
                }
            }
            if (tile != null) {
                tile.lastUsed = now;
                drawTile(view, tile, at[0] * span, at[1] * span);
                continue;
            }
            // Not built yet: a coarser tile of the same place, if there is one, until it is.
            for (int coarser = lod * 2; coarser <= MAX_LOD; coarser *= 2) {
                int factor = coarser / lod;
                Key other = new Key(
                    colors,
                    heights,
                    coarser,
                    Math.floorDiv(at[0], factor),
                    Math.floorDiv(at[1], factor));
                Tile fallback = tiles.get(other);
                if (fallback != null) {
                    if (fallbacksDrawn.add(other)) {
                        fallback.lastUsed = now;
                        drawTile(view, fallback, other.tx * CELLS * coarser, other.tz * CELLS * coarser);
                    }
                    break;
                }
            }
        }

        GL11.glPopMatrix();
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glPopAttrib();
        GL11.glDepthMask(true);

        // Night: the map darkened like the flat one, by multiplying what was drawn.
        float[] tint = MapDrawer.lightTint(Minecraft.getMinecraft());
        if (tint[0] < 1f || tint[1] < 1f || tint[2] < 1f) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ZERO);
            GL11.glColor4f(tint[0], tint[1], tint[2], 1f);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.addVertex(x, y + height, 0);
            tessellator.addVertex(x + width, y + height, 0);
            tessellator.addVertex(x + width, y, 0);
            tessellator.addVertex(x, y, 0);
            tessellator.draw();
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);

        if (++frames % 60 == 0) {
            freeUnused(now);
        }
    }

    private static void drawTile(IsoView view, Tile tile, int originX, int originZ) {
        if (tile.list < 0) {
            return;
        }
        GL11.glPushMatrix();
        GL11.glTranslated(originX - view.centerX, -IsoView.REFERENCE_Y, originZ - view.centerZ);
        GL11.glCallList(tile.list);
        GL11.glPopMatrix();
    }

    /** Whether any part of the tile (at any height) lands in the screen rectangle. */
    private static boolean onScreen(IsoView view, int originX, int originZ, int span, int x, int y, int width,
        int height) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double[] p = view.project(
                originX + ((corner & 1) != 0 ? span : 0),
                (corner & 4) != 0 ? 256 : 0,
                originZ + ((corner & 2) != 0 ? span : 0));
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return maxX >= x && minX <= x + width && maxY >= y && minY <= y + height;
    }

    // ---------------------------------------------------------------- building

    /** The map data a tile is made from (its cells and the ring around it, for the sides). */
    private static final class Sources {

        final int rx0, rz0, columns, rows;
        final PixelSource[] colors, heights;

        Sources(int rx0, int rz0, int columns, int rows) {
            this.rx0 = rx0;
            this.rz0 = rz0;
            this.columns = columns;
            this.rows = rows;
            colors = new PixelSource[columns * rows];
            heights = new PixelSource[columns * rows];
        }
    }

    /**
     * Gets the regions (or their reduced copies when zoomed out) the tile needs, starting their reads if needed;
     * null while some are still being read.
     */
    private static Sources sources(Key key) {
        int span = CELLS * key.lod;
        int minX = key.tx * span - key.lod, minZ = key.tz * span - key.lod;
        int maxX = key.tx * span + span + key.lod - 1, maxZ = key.tz * span + span + key.lod - 1;
        int rx0 = minX >> MapRegion.SHIFT, rz0 = minZ >> MapRegion.SHIFT;
        int rx1 = maxX >> MapRegion.SHIFT, rz1 = maxZ >> MapRegion.SHIFT;
        Sources sources = new Sources(rx0, rz0, rx1 - rx0 + 1, rz1 - rz0 + 1);
        boolean ready = true;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                int index = (rz - rz0) * sources.columns + (rx - rx0);
                PixelSource color = source(key.colors, key.lod, rx, rz);
                PixelSource height = key.heights == key.colors ? color : source(key.heights, key.lod, rx, rz);
                ready &= (color != null || key.colors.isKnownMissing(rx, rz))
                    && (height != null || key.heights.isKnownMissing(rx, rz));
                sources.colors[index] = color;
                sources.heights[index] = height;
            }
        }
        return ready ? sources : null;
    }

    private static PixelSource source(MapDimension map, int lod, int rx, int rz) {
        return lod >= LodTile.FACTOR ? map.requestLod(rx, rz) : map.requestRegion(rx, rz);
    }

    /** Identifies the state of the map data of the tile, to notice changes. */
    private static long signature(Sources sources) {
        long signature = 17;
        for (int i = 0; i < sources.colors.length; i++) {
            signature = signature * 31 + stamp(sources.colors[i]);
            signature = signature * 31 + stamp(sources.heights[i]);
        }
        return signature == NOT_READY ? NOT_READY + 1 : signature;
    }

    private static long stamp(PixelSource source) {
        return source == null ? 7 : ((long) System.identityHashCode(source) << 20) ^ source.getChanges();
    }

    private static Tile build(Key key, Sources sources, Tile tile, long signature, long now) {
        if (tile == null) {
            tile = new Tile();
            tiles.put(key, tile);
        }
        tile.signature = signature;
        tile.builtAt = now;
        int lod = key.lod;
        int originX = key.tx * CELLS * lod, originZ = key.tz * CELLS * lod;
        // Cells of the tile and the ring around it: color, and height of the top (-1 = unknown).
        int size = CELLS + 2;
        int[] colors = new int[size * size];
        int[] heights = new int[size * size];
        boolean any = false;
        for (int j = -1; j <= CELLS; j++) {
            for (int i = -1; i <= CELLS; i++) {
                int bx = originX + i * lod, bz = originZ + j * lod;
                int index = (j + 1) * size + (i + 1);
                int color = sample(sources, sources.colors, lod, bx, bz, false);
                int top = sample(sources, sources.heights, lod, bx, bz, true);
                if ((color >>> 24) == 0 || top <= 0) {
                    heights[index] = -1;
                    continue;
                }
                colors[index] = color & 0xFFFFFF;
                heights[index] = top;
                any |= i >= 0 && j >= 0 && i < CELLS && j < CELLS;
            }
        }
        if (!any) {
            if (tile.list >= 0) {
                GL11.glDeleteLists(tile.list, 1);
                tile.list = -1;
            }
            return tile;
        }
        if (tile.list < 0) {
            tile.list = GL11.glGenLists(1);
        }
        GL11.glNewList(tile.list, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int j = 0; j < CELLS; j++) {
            for (int i = 0; i < CELLS; i++) {
                int index = (j + 1) * size + (i + 1);
                int h = heights[index];
                if (h < 0) {
                    continue;
                }
                int color = colors[index];
                int x0 = i * lod, x1 = x0 + lod, z0 = j * lod, z1 = z0 + lod;
                t.setColorOpaque_I(color);
                t.addVertex(x0, h, z0);
                t.addVertex(x0, h, z1);
                t.addVertex(x1, h, z1);
                t.addVertex(x1, h, z0);
                // Walls down to each lower neighbour.
                int west = bottom(heights[index - 1], h), east = bottom(heights[index + 1], h);
                int north = bottom(heights[index - size], h), south = bottom(heights[index + size], h);
                if (west < h || east < h) {
                    t.setColorOpaque_I(shade(color, SIDE_X));
                    if (west < h) {
                        wall(t, x0, z0, x0, z1, west, h);
                    }
                    if (east < h) {
                        wall(t, x1, z0, x1, z1, east, h);
                    }
                }
                if (north < h || south < h) {
                    t.setColorOpaque_I(shade(color, SIDE_Z));
                    if (north < h) {
                        wall(t, x0, z0, x1, z0, north, h);
                    }
                    if (south < h) {
                        wall(t, x0, z1, x1, z1, south, h);
                    }
                }
            }
        }
        t.draw();
        GL11.glEndList();
        return tile;
    }

    /** Where the wall next to a neighbour ends: at its top, or a little below ours at the edge of the map. */
    private static int bottom(int neighbour, int h) {
        return neighbour < 0 ? Math.max(0, h - EDGE_DEPTH) : neighbour;
    }

    private static void wall(Tessellator t, int xa, int za, int xb, int zb, int bottom, int top) {
        t.addVertex(xa, bottom, za);
        t.addVertex(xa, top, za);
        t.addVertex(xb, top, zb);
        t.addVertex(xb, bottom, zb);
    }

    private static int shade(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return r << 16 | g << 8 | b;
    }

    /** Pixel (or with {@code extra} its extra byte, the height) of the block from the tile's sources; 0 if unknown. */
    private static int sample(Sources sources, PixelSource[] maps, int lod, int bx, int bz, boolean extra) {
        int rx = (bx >> MapRegion.SHIFT) - sources.rx0, rz = (bz >> MapRegion.SHIFT) - sources.rz0;
        if (rx < 0 || rz < 0 || rx >= sources.columns || rz >= sources.rows) {
            return 0;
        }
        PixelSource source = maps[rz * sources.columns + rx];
        if (source == null) {
            return 0;
        }
        int local = MapRegion.SIZE - 1;
        int reduce = lod >= LodTile.FACTOR ? LodTile.FACTOR : 1;
        int lx = (bx & local) / reduce, lz = (bz & local) / reduce;
        return extra ? source.getExtra(lx, lz) : source.getPixel(lx, lz);
    }

    // ---------------------------------------------------------------- memory

    private static void freeUnused(long now) {
        Iterator<Tile> it = tiles.values()
            .iterator();
        while (it.hasNext()) {
            Tile tile = it.next();
            if (now - tile.lastUsed > UNUSED_MS) {
                delete(tile);
                it.remove();
            }
        }
        if (tiles.size() > MAX_TILES) {
            // Still too many: the ones unused the longest go.
            List<Map.Entry<Key, Tile>> entries = new ArrayList<>(tiles.entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed));
            for (int i = 0; i < entries.size() - MAX_TILES; i++) {
                delete(entries.get(i).getValue());
                tiles.remove(entries.get(i).getKey());
            }
        }
    }

    private static void delete(Tile tile) {
        if (tile.list >= 0) {
            GL11.glDeleteLists(tile.list, 1);
            tile.list = -1;
        }
    }

    /** Frees everything (the world map was closed). Render thread only. */
    public static void clear() {
        for (Tile tile : tiles.values()) {
            delete(tile);
        }
        tiles.clear();
    }

    /** The heights of the surface map for picking what is under the mouse ({@link IsoView#pick}). */
    public static IsoView.Heights heightsOf(MapDimension surface) {
        return (x, z) -> surface.peekExtra(x, z);
    }
}
