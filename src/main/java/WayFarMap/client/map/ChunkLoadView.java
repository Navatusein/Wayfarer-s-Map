package WayFarMap.client.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.share.ShareNetwork;

/**
 * The world map's chunk loading view: the chunks on the map in green, the chunks picked to be loaded in red until the
 * map has them (then they turn green and join the rest), each area with one border around it like claims. Chunks
 * are picked by dragging with Ctrl; the server loads them (generating those not made yet) through
 * {@code /wf chunkload}'s batches.
 */
public final class ChunkLoadView {

    private static final int NONE = 0, MAPPED = 1, PENDING = 2;
    private static final int MAPPED_FILL = 0x3FB950, MAPPED_BORDER = 0x2EA043;
    private static final int PENDING_FILL = 0xE5534B, PENDING_BORDER = 0xFF5050;
    private static final int FILL_ALPHA = 80, PENDING_ALPHA = 120, BORDER_ALPHA = 230;
    /** Chunks a drag can pick at most (a square of this side). */
    public static final int MAX_SIDE = 128;
    /** Below this many GUI pixels a chunk, only the picked chunks are drawn (the map has too many). */
    private static final double MIN_CELL = 3;

    /** Picked chunks of each dimension, with when they were sent: red until mapped after that. */
    private static final Map<Integer, Map<Long, Long>> PENDING_CHUNKS = new HashMap<>();

    private ChunkLoadView() {}

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    /** Sends the picked chunks to the server to be loaded (or taken off the queue) and marks them. */
    public static void pick(int dimension, Set<Long> chunks, boolean remove) {
        if (chunks.isEmpty()) {
            return;
        }
        Map<Long, Long> pending = PENDING_CHUNKS.computeIfAbsent(dimension, d -> new HashMap<>());
        long now = System.currentTimeMillis();
        // The far view shows the change at once.
        REGION_STATES.clear();
        for (long chunk : chunks) {
            if (remove) {
                pending.remove(chunk);
            } else {
                pending.put(chunk, now);
            }
        }
        long[] all = new long[chunks.size()];
        int n = 0;
        for (long chunk : chunks) {
            all[n++] = chunk;
        }
        for (int from = 0; from < all.length; from += ShareNetwork.LoadChunks.MAX) {
            long[] part = Arrays.copyOfRange(all, from, Math.min(all.length, from + ShareNetwork.LoadChunks.MAX));
            ShareNetwork.sendToServer(new ShareNetwork.LoadChunks(remove, part));
        }
    }

    /**
     * Draws the view over the flat map.
     *
     * @param selection chunks of the drag going on, or null
     * @param removing  the drag takes chunks off the queue
     */
    public static void draw(MapDimension surface, int dimension, double centerX, double centerZ, double scale, int x,
        int y, int width, int height, Set<Long> selection, boolean removing) {
        double left = centerX - width / 2.0 / scale, top = centerZ - height / 2.0 / scale;
        int minX = (int) Math.floor(left) >> 4, maxX = (int) Math.floor(left + width / scale) >> 4;
        int minZ = (int) Math.floor(top) >> 4, maxZ = (int) Math.floor(top + height / scale) >> 4;
        double cell = 16 * scale;
        double pixel = 1.0 / ScaledScreen.currentFactor();
        double border = Math.max(pixel, Math.min(3, cell / 12));
        Map<Long, Long> pending = PENDING_CHUNKS.computeIfAbsent(dimension, d -> new HashMap<>());

        begin();
        if (cell >= MIN_CELL && surface != null) {
            // States of the chunks on screen and one around (for the borders): NONE, MAPPED or PENDING.
            int w = maxX - minX + 3, h = maxZ - minZ + 3;
            int[] state = new int[w * h];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    state[j * w + i] = state(surface, pending, minX - 1 + i, minZ - 1 + j);
                }
            }
            // Fill: runs of the same state along each row, as one rectangle.
            for (int j = 1; j < h - 1; j++) {
                int i = 1;
                while (i < w - 1) {
                    int s = state[j * w + i];
                    int start = i;
                    while (i < w - 1 && state[j * w + i] == s) {
                        i++;
                    }
                    if (s != NONE) {
                        double sx = x + ((minX - 1 + start) * 16 - left) * scale;
                        double sy = y + ((minZ - 1 + j) * 16 - top) * scale;
                        rect(sx, sy, cell * (i - start), cell, fill(s), s == PENDING ? PENDING_ALPHA : FILL_ALPHA,
                            x, y, width, height);
                    }
                }
            }
            // Borders where the neighbour differs, and the corners where an area turns inward.
            for (int j = 1; j < h - 1; j++) {
                for (int i = 1; i < w - 1; i++) {
                    int s = state[j * w + i];
                    if (s == NONE) {
                        continue;
                    }
                    int color = s == PENDING ? PENDING_BORDER : MAPPED_BORDER;
                    double sx = x + ((minX - 1 + i) * 16 - left) * scale;
                    double sy = y + ((minZ - 1 + j) * 16 - top) * scale;
                    if (state[(j - 1) * w + i] != s) {
                        rect(sx, sy, cell, border, color, BORDER_ALPHA, x, y, width, height);
                    }
                    if (state[(j + 1) * w + i] != s) {
                        rect(sx, sy + cell - border, cell, border, color, BORDER_ALPHA, x, y, width, height);
                    }
                    if (state[j * w + i - 1] != s) {
                        rect(sx, sy, border, cell, color, BORDER_ALPHA, x, y, width, height);
                    }
                    if (state[j * w + i + 1] != s) {
                        rect(sx + cell - border, sy, border, cell, color, BORDER_ALPHA, x, y, width, height);
                    }
                    for (int dx = -1; dx <= 1; dx += 2) {
                        for (int dz = -1; dz <= 1; dz += 2) {
                            if (state[j * w + i + dx] == s && state[(j + dz) * w + i] == s
                                && state[(j + dz) * w + i + dx] != s) {
                                rect(
                                    dx < 0 ? sx : sx + cell - border,
                                    dz < 0 ? sy : sy + cell - border,
                                    border,
                                    border,
                                    color,
                                    BORDER_ALPHA,
                                    x,
                                    y,
                                    width,
                                    height);
                            }
                        }
                    }
                }
            }
        } else if (surface != null) {
            // Zoomed far out: too many chunks to look at every frame. The states of each region's chunks are kept
            // and refreshed now and then, and drawn as strips along the rows (no borders: they would be too thin).
            int minRx = minX >> 5, maxRx = maxX >> 5, minRz = minZ >> 5, maxRz = maxZ >> 5;
            long now = System.currentTimeMillis();
            for (int rz = minRz; rz <= maxRz; rz++) {
                for (int rx = minRx; rx <= maxRx; rx++) {
                    byte[] states = regionStates(surface, pending, dimension, rx, rz, now);
                    for (int lz = 0; lz < 32; lz++) {
                        int lx = 0;
                        while (lx < 32) {
                            int st = states[lz * 32 + lx];
                            int start = lx;
                            while (lx < 32 && states[lz * 32 + lx] == st) {
                                lx++;
                            }
                            if (st == NONE) {
                                continue;
                            }
                            double sx = x + ((rx * 32 + start) * 16 - left) * scale;
                            double sy = y + ((rz * 32 + lz) * 16 - top) * scale;
                            // At least a pixel, so far out the areas still show.
                            rect(sx, sy, Math.max(pixel, cell * (lx - start)), Math.max(pixel, cell),
                                st == PENDING ? PENDING_BORDER : MAPPED_FILL,
                                st == PENDING ? BORDER_ALPHA : FILL_ALPHA + 40, x, y, width, height);
                        }
                    }
                }
            }
            if (REGION_STATES.size() > MAX_CACHED_REGIONS) {
                REGION_STATES.clear();
            }
        }
        if (selection != null && !selection.isEmpty()) {
            int color = removing ? 0xA0A0A0 : PENDING_BORDER;
            for (long chunk : selection) {
                double sx = x + (unpackX(chunk) * 16 - left) * scale, sy = y + (unpackZ(chunk) * 16 - top) * scale;
                rect(sx, sy, cell, cell, 0xFFFFFF, 33, x, y, width, height);
                hollowRect(sx, sy, cell, cell, pixel, color, 0xFF, x, y, width, height);
            }
        }
        end();
    }

    /** States of a region's chunks for the far view, and when they were worked out. */
    private static final Map<Long, Object[]> REGION_STATES = new HashMap<>();
    /** How often the far view looks at a region's chunks again. */
    private static final long REGION_REFRESH_MS = 500;
    private static final int MAX_CACHED_REGIONS = 4096;

    /** The 32 x 32 chunk states of a region (row by row), kept for {@link #REGION_REFRESH_MS}. */
    private static byte[] regionStates(MapDimension surface, Map<Long, Long> pending, int dimension, int rx, int rz,
        long now) {
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL) ^ ((long) dimension << 52);
        Object[] kept = REGION_STATES.get(key);
        if (kept != null && now - (Long) kept[0] < REGION_REFRESH_MS && kept[2] == surface) {
            return (byte[]) kept[1];
        }
        if (!surface.isInMemory(rx, rz) && !hasPending(pending, rx, rz)) {
            // Nothing of it on the map in memory, nothing picked: no need to look at its 1024 chunks.
            REGION_STATES.put(key, new Object[] { now, EMPTY, surface });
            return EMPTY;
        }
        byte[] states = new byte[32 * 32];
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                states[lz * 32 + lx] = (byte) state(surface, pending, rx * 32 + lx, rz * 32 + lz);
            }
        }
        REGION_STATES.put(key, new Object[] { now, states, surface });
        return states;
    }

    private static final byte[] EMPTY = new byte[32 * 32];

    private static boolean hasPending(Map<Long, Long> pending, int rx, int rz) {
        for (long chunk : pending.keySet()) {
            if (unpackX(chunk) >> 5 == rx && unpackZ(chunk) >> 5 == rz) {
                return true;
            }
        }
        return false;
    }

        /** NONE, MAPPED or PENDING; a picked chunk mapped since it was picked stops being picked. */
    private static int state(MapDimension surface, Map<Long, Long> pending, int chunkX, int chunkZ) {
        long time = surface.chunkTimeInMemory(chunkX, chunkZ);
        if (!pending.isEmpty()) {
            Long picked = pending.get(pack(chunkX, chunkZ));
            if (picked != null) {
                if (time > picked) {
                    pending.remove(pack(chunkX, chunkZ));
                } else {
                    return PENDING;
                }
            }
        }
        return time > 0 ? MAPPED : NONE;
    }

    private static int fill(int state) {
        return state == PENDING ? PENDING_FILL : MAPPED_FILL;
    }

    /** A chunk was mapped by the loading: it is done even if it looks the same as before (its time stays then). */
    public static void mapped(int dimension, int chunkX, int chunkZ) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        if (pending != null) {
            pending.remove(pack(chunkX, chunkZ));
        }
    }

    /** Picked chunks still waiting, for the footer. */
    public static int pendingCount(int dimension) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        return pending == null ? 0 : pending.size();
    }

    /** The chunks of the rectangle between two chunks (capped to {@link #MAX_SIDE} a side). */
    public static List<Long> rectangle(int x0, int z0, int x1, int z1) {
        int minX = Math.min(x0, x1), maxX = Math.min(Math.max(x0, x1), Math.min(x0, x1) + MAX_SIDE - 1);
        int minZ = Math.min(z0, z1), maxZ = Math.min(Math.max(z0, z1), Math.min(z0, z1) + MAX_SIDE - 1);
        List<Long> chunks = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                chunks.add(pack(cx, cz));
            }
        }
        return chunks;
    }

    // ---------------------------------------------------------------- drawing helpers (batched quads)

    private static void begin() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator.instance.startDrawingQuads();
    }

    private static void end() {
        Tessellator.instance.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void rect(double rx, double ry, double w, double h, int rgb, int alpha, int x, int y, int width,
        int height) {
        double x0 = Math.max(rx, x), y0 = Math.max(ry, y);
        double x1 = Math.min(rx + w, x + width), y1 = Math.min(ry + h, y + height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorRGBA_I(rgb & 0xFFFFFF, alpha);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    private static void hollowRect(double rx, double ry, double w, double h, double t, int rgb, int alpha, int x,
        int y, int width, int height) {
        rect(rx, ry, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + h - t, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
        rect(rx + w - t, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
    }
}
