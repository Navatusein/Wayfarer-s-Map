package WayFarMap.client.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;

import WayFarMap.client.gui.ui.ScaledScreen;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import serverutils.client.gui.ClientClaimedChunks;
import serverutils.events.chunks.UpdateClientDataEvent;
import serverutils.integration.navigator.NavigatorIntegration;
import serverutils.lib.math.ChunkDimPos;
import serverutils.net.MessageClaimedChunksModify;
import serverutils.net.MessageClaimedChunksRequest;
import serverutils.net.MessageClaimedChunksUpdate;
import serverutils.net.MessageNavigatorRequest;
import serverutils.net.MessageNavigatorValidateKnown;

/**
 * ServerUtilities chunk claims on the world map, through the same messages and client cache its Navigator
 * (JourneyMap / Xaero) integration uses. Claiming and chunk loading are checked by the server (permissions, limits).
 * <p>
 * Only touch this class after {@link Mods#isClaimsAvailable()}.
 */
public final class ClaimsLayer {

    /** What a drag over the map does with the chunks it passes. */
    public static final int CLAIM = 0, LOAD = 1, CLAIM_AND_LOAD = 2, UNCLAIM = 3, UNLOAD = 4, UNLOAD_AND_UNCLAIM = 5;

    /** Colors of the drag selection: claiming and loading. */
    private static final int CLAIMED_COLOR = 0x4CB4FF, LOADED_COLOR = 0x50E070;

    private static final long REQUEST_MS = 2000, VALIDATE_MS = 10_000, COUNTS_MS = 5000;
    /** The area asked from the server at once is capped, like the view of a normal map. */
    private static final int MAX_REQUEST_CHUNKS = 128;

    private static long lastRequest, lastValidate, lastCounts;

    /**
     * Chunks the player just unclaimed, with the time. ServerUtilities only marks unclaimed chunks invalid and drops
     * them in a later cleanup, and until then its map updates still list them; they stay hidden here meanwhile.
     */
    private static final Map<Long, Long> unclaimed = new HashMap<>();
    private static final long UNCLAIMED_MAX_MS = 60_000, UNCLAIMED_MIN_MS = 6000;
    private static boolean haveCounts;
    private static int claimed, maxClaimed, loaded, maxLoaded;

    private ClaimsLayer() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Listener());
    }

    /** Claim counts from ServerUtilities' own claim screen data. */
    public static final class Listener {

        @SubscribeEvent
        public void onClientData(UpdateClientDataEvent event) {
            MessageClaimedChunksUpdate message = event.getMessage();
            claimed = message.claimedChunks;
            maxClaimed = message.maxClaimedChunks;
            loaded = message.loadedChunks;
            maxLoaded = message.maxLoadedChunks;
            haveCounts = true;
        }
    }

    /** Called when the layer is shown: fetch everything again right away. */
    public static void onShow() {
        lastRequest = 0;
        lastValidate = 0;
        lastCounts = 0;
    }

    /** Asks the server for the claims in the visible chunk range and drops ones that are gone (throttled). */
    public static void update(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        long now = System.currentTimeMillis();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        if (now - lastRequest >= REQUEST_MS) {
            lastRequest = now;
            int centerX = (minChunkX + maxChunkX) / 2, centerZ = (minChunkZ + maxChunkZ) / 2;
            int halfX = Math.min(MAX_REQUEST_CHUNKS, maxChunkX - minChunkX) / 2 + 1;
            int halfZ = Math.min(MAX_REQUEST_CHUNKS, maxChunkZ - minChunkZ) / 2 + 1;
            new MessageNavigatorRequest(centerX - halfX, centerX + halfX, centerZ - halfZ, centerZ + halfZ)
                .sendToServer();
        }
        if (now - lastCounts >= COUNTS_MS) {
            lastCounts = now;
            new MessageClaimedChunksRequest(mc.thePlayer).sendToServer();
        }
        if (now - lastValidate >= VALIDATE_MS) {
            lastValidate = now;
            int dimension = mc.thePlayer.dimension;
            LongList known = new LongArrayList();
            for (ChunkDimPos pos : NavigatorIntegration.CLAIMS.keySet()) {
                if (pos.dim == dimension && pos.posX >= minChunkX
                    && pos.posX <= maxChunkX
                    && pos.posZ >= minChunkZ
                    && pos.posZ <= maxChunkZ) {
                    known.add(CoordinatePacker.pack(pos.posX, 0, pos.posZ));
                    if (known.size() >= 2000) {
                        new MessageNavigatorValidateKnown(known).sendToServer();
                        known = new LongArrayList();
                    }
                }
            }
            if (!known.isEmpty()) {
                new MessageNavigatorValidateKnown(known).sendToServer();
            }
        }
    }

    private static ClientClaimedChunks.ChunkData get(int chunkX, int chunkZ, int dimension) {
        if (!unclaimed.isEmpty() && unclaimed.containsKey(pack(chunkX, chunkZ))) {
            return null;
        }
        return NavigatorIntegration.CLAIMS.get(new ChunkDimPos(chunkX, chunkZ, dimension));
    }

    /** Stops hiding unclaimed chunks once the server no longer lists them (or after a minute). */
    private static void expireUnclaimed(int dimension) {
        if (unclaimed.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, Long>> it = unclaimed.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            long age = now - entry.getValue();
            long packed = entry.getKey();
            boolean listed = NavigatorIntegration.CLAIMS
                .containsKey(new ChunkDimPos(unpackX(packed), unpackZ(packed), dimension));
            if (age > UNCLAIMED_MAX_MS || (age > UNCLAIMED_MIN_MS && !listed)) {
                it.remove();
            }
        }
    }

    private static boolean sameTeam(ClientClaimedChunks.ChunkData a, ClientClaimedChunks.ChunkData b) {
        return a != null && b != null && a.team != null && b.team != null && a.team.uid == b.team.uid;
    }

    private static int teamColor(ClientClaimedChunks.ChunkData data) {
        try {
            return data.team.color.getColor()
                .rgb() & 0xFFFFFF;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    // The look of ServerUtilities' own claim screen (GuiClaimedChunks), so the two read the same.
    /** Fill of a claimed chunk over its team color. */
    private static final int FILL_ALPHA = 150;
    /** Border of a claimed area, and of a chunk loaded one. */
    private static final int BORDER_COLOR = 0x505050, LOADED_BORDER_COLOR = 0xFF5050, BORDER_ALPHA = 230;
    /** Dashed diagonal lines over chunk loaded chunks. */
    private static final int DASH_COLOR = 0x000000, DASH_ALPHA = 90;
    /** Brightness of the fill of claimed chunks that aren't chunk loaded. */
    private static final float CLAIMED_SHADE = 0.78f;

    private static int darker(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor), g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return r << 16 | g << 8 | b;
    }

    /** Selection being dragged: a light veil (ServerUtilities' own), outlined in the action's color here. */
    private static final int SELECTION_ALPHA = 33;

    /**
     * Whether a claimed chunk has a border on the side of the neighbour: as in ServerUtilities, where the neighbour
     * is another team or unclaimed, or differs in being chunk loaded, but never toward a chunk loaded neighbour
     * (which draws its own, red, border).
     */
    private static boolean hasBorder(ClientClaimedChunks.ChunkData data, ClientClaimedChunks.ChunkData with) {
        if (with == null) {
            return true;
        }
        return (data.getFlags() != with.getFlags() || !sameTeam(data, with)) && !with.isLoaded();
    }

    /**
     * Draws the claims as ServerUtilities' claim screen does: every claimed chunk in its team's color, a grey border
     * around each claimed area (red around chunk loaded ones) and dashed diagonal lines over chunk loaded chunks. All
     * of it scales with the zoom. And the chunks of the current drag selection.
     *
     * @param selection     packed chunk positions being selected, or null
     * @param selectionMode one of the action constants, for the selection color
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, Set<Long> selection, int selectionMode) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        int minChunkX = (int) Math.floor(left) >> 4, maxChunkX = (int) Math.floor(left + width / scale) >> 4;
        int minChunkZ = (int) Math.floor(top) >> 4, maxChunkZ = (int) Math.floor(top + height / scale) >> 4;
        update(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        expireUnclaimed(dimension);

        double pixel = 1.0 / ScaledScreen.currentFactor();
        double cell = 16 * scale;
        // ServerUtilities: a 1 pixel line on a 12 pixel chunk.
        double border = Math.max(pixel, Math.min(3, cell / 12));
        // Dashes only where a chunk is big enough for them to read as lines.
        boolean dashes = cell >= 8;

        begin();
        List<ChunkDimPos> shown = new ArrayList<>();
        for (Map.Entry<ChunkDimPos, ClientClaimedChunks.ChunkData> entry : NavigatorIntegration.CLAIMS.entrySet()) {
            ChunkDimPos pos = entry.getKey();
            if (pos.dim != dimension || pos.posX < minChunkX
                || pos.posX > maxChunkX
                || pos.posZ < minChunkZ
                || pos.posZ > maxChunkZ
                || get(pos.posX, pos.posZ, dimension) == null) {
                continue;
            }
            shown.add(pos);
            double sx = x + (pos.posX * 16 - left) * scale;
            double sy = y + (pos.posZ * 16 - top) * scale;
            int fill = teamColor(entry.getValue());
            if (!entry.getValue()
                .isLoaded()) {
                // Only claimed: a little darker than the chunk loaded ones, so those stand out.
                fill = darker(fill, CLAIMED_SHADE);
            }
            rect(sx, sy, cell, cell, fill, FILL_ALPHA, x, y, width, height);
        }
        for (ChunkDimPos pos : shown) {
            ClientClaimedChunks.ChunkData data = get(pos.posX, pos.posZ, dimension);
            double sx = x + (pos.posX * 16 - left) * scale;
            double sy = y + (pos.posZ * 16 - top) * scale;
            boolean isLoaded = data.isLoaded();
            if (isLoaded && dashes) {
                dashedDiagonals(sx, sy, cell, pixel, x, y, width, height);
            }
            int color = isLoaded ? LOADED_BORDER_COLOR : BORDER_COLOR;
            if (hasBorder(data, get(pos.posX, pos.posZ - 1, dimension))) {
                rect(sx, sy, cell, border, color, BORDER_ALPHA, x, y, width, height);
            }
            if (hasBorder(data, get(pos.posX, pos.posZ + 1, dimension))) {
                rect(sx, sy + cell - border, cell, border, color, BORDER_ALPHA, x, y, width, height);
            }
            if (hasBorder(data, get(pos.posX - 1, pos.posZ, dimension))) {
                rect(sx, sy, border, cell, color, BORDER_ALPHA, x, y, width, height);
            }
            if (hasBorder(data, get(pos.posX + 1, pos.posZ, dimension))) {
                rect(sx + cell - border, sy, border, cell, color, BORDER_ALPHA, x, y, width, height);
            }
            // Inner corners of an area: the borders of the two neighbours meet in this chunk's corner.
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    innerCorner(
                        data,
                        pos.posX,
                        pos.posZ,
                        dx,
                        dz,
                        dimension,
                        dx < 0 ? sx : sx + cell - border,
                        dz < 0 ? sy : sy + cell - border,
                        border,
                        x,
                        y,
                        width,
                        height);
                }
            }
        }
        if (selection != null && !selection.isEmpty()) {
            int color = selectionMode == UNCLAIM || selectionMode == UNLOAD || selectionMode == UNLOAD_AND_UNCLAIM
                ? 0xFF5050
                : selectionMode == LOAD ? LOADED_COLOR : selectionMode == CLAIM_AND_LOAD ? 0xB070FF : CLAIMED_COLOR;
            for (long packed : selection) {
                double sx = x + (unpackX(packed) * 16 - left) * scale;
                double sy = y + (unpackZ(packed) * 16 - top) * scale;
                rect(sx, sy, cell, cell, 0xFFFFFF, SELECTION_ALPHA, x, y, width, height);
                hollowRect(sx, sy, cell, cell, pixel, color, 0xFF, x, y, width, height);
            }
        }
        end();
    }

    /**
     * The corner square of a chunk where an area turns inward: the neighbours on both sides draw their border toward
     * the chunk diagonal to this one, but each stops at its own edge and the corner between them, which is in this
     * chunk, stayed empty (a notch in the line). Filled in their color when this chunk has no border there itself.
     */
    private static void innerCorner(ClientClaimedChunks.ChunkData data, int chunkX, int chunkZ, int dx, int dz,
        int dimension, double cornerX, double cornerY, double border, int x, int y, int width, int height) {
        ClientClaimedChunks.ChunkData side = get(chunkX + dx, chunkZ, dimension);
        ClientClaimedChunks.ChunkData across = get(chunkX, chunkZ + dz, dimension);
        if (side == null || across == null || hasBorder(data, side) || hasBorder(data, across)) {
            return;
        }
        ClientClaimedChunks.ChunkData diagonal = get(chunkX + dx, chunkZ + dz, dimension);
        if (!hasBorder(side, diagonal) || !hasBorder(across, diagonal)) {
            return;
        }
        int color = side.isLoaded() ? LOADED_BORDER_COLOR : BORDER_COLOR;
        rect(cornerX, cornerY, border, border, color, BORDER_ALPHA, x, y, width, height);
    }

    /**
     * ServerUtilities' mark of a chunk loaded chunk: three parallel dashed lines going down to the right (corner to
     * corner, and halfway on both sides of it).
     */
    private static void dashedDiagonals(double sx, double sy, double cell, double pixel, int x, int y, int width,
        int height) {
        double half = cell / 2;
        dashedLine(sx, sy, sx + cell, sy + cell, cell, pixel, x, y, width, height);
        dashedLine(sx, sy + half, sx + half, sy + cell, cell, pixel, x, y, width, height);
        dashedLine(sx + half, sy, sx + cell, sy + half, cell, pixel, x, y, width, height);
    }

    /** A dashed line of thin quads; dashes and gaps grow with the chunk (about 10 of each corner to corner). */
    private static void dashedLine(double x0, double y0, double x1, double y1, double cell, double pixel, int x, int y,
        int width, int height) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        double dash = Math.max(2 * pixel, cell / 14);
        double thickness = Math.max(pixel, cell / 40) / 2;
        double ux = (x1 - x0) / length, uy = (y1 - y0) / length;
        // Across the line, for its thickness.
        double nx = -uy * thickness, ny = ux * thickness;
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorRGBA_I(DASH_COLOR, DASH_ALPHA);
        for (double t = 0; t < length; t += 2 * dash) {
            double t1 = Math.min(length, t + dash);
            double ax = x0 + ux * t, ay = y0 + uy * t, bx = x0 + ux * t1, by = y0 + uy * t1;
            if (Math.max(ax, bx) < x || Math.min(ax, bx) > x + width
                || Math.max(ay, by) < y
                || Math.min(ay, by) > y + height) {
                continue;
            }
            tessellator.addVertex(ax - nx, ay - ny, 0);
            tessellator.addVertex(bx - nx, by - ny, 0);
            tessellator.addVertex(bx + nx, by + ny, 0);
            tessellator.addVertex(ax + nx, ay + ny, 0);
        }
    }

    /** Tooltip lines for a chunk, or null if it isn't claimed. */
    public static List<String> tooltip(int chunkX, int chunkZ, int dimension) {
        ClientClaimedChunks.ChunkData data = get(chunkX, chunkZ, dimension);
        if (data == null || data.team == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(data.team.nameComponent != null ? data.team.nameComponent.getFormattedText() : "?");
        if (data.team.isMember) {
            lines.add("§a" + I18n.format("wayfarmap.claims.own"));
        } else if (data.team.isAlly) {
            lines.add("§b" + I18n.format("wayfarmap.claims.ally"));
        }
        if (data.isLoaded()) {
            lines.add("§e" + I18n.format("wayfarmap.claims.loaded"));
        }
        return lines;
    }

    /** "Claimed 12/200 · Loaded 3/25", once the server has sent the counts. */
    public static String countsText() {
        if (!haveCounts) {
            return "";
        }
        return I18n.format(
            "wayfarmap.claims.counts",
            claimed,
            maxClaimed < 0 ? "-" : maxClaimed == Integer.MAX_VALUE ? "∞" : String.valueOf(maxClaimed),
            loaded,
            maxLoaded < 0 ? "-" : String.valueOf(maxLoaded));
    }

    /**
     * Keeps only the chunks the action makes sense for: new claims only on free chunks, loading, unclaiming and
     * unloading only on the own team's chunks.
     */
    public static boolean accepts(int action, int chunkX, int chunkZ, int dimension) {
        ClientClaimedChunks.ChunkData data = get(chunkX, chunkZ, dimension);
        boolean own = data != null && data.team != null && data.team.isMember;
        switch (action) {
            case CLAIM:
                return data == null;
            case CLAIM_AND_LOAD:
                return (data == null || own) && (data == null || !data.isLoaded());
            case LOAD:
                return own && !data.isLoaded();
            case UNLOAD:
                return own && data.isLoaded();
            case UNCLAIM:
            case UNLOAD_AND_UNCLAIM:
                return own;
            default:
                return false;
        }
    }

    /** Sends the action for the selected chunks to the server and updates the map right away. */
    public static void apply(int action, Collection<Long> packedChunks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (packedChunks.isEmpty() || mc.thePlayer == null) {
            return;
        }
        int dimension = mc.thePlayer.dimension;
        List<ChunkCoordIntPair> chunks = new ArrayList<>();
        for (long packed : packedChunks) {
            chunks.add(new ChunkCoordIntPair(unpackX(packed), unpackZ(packed)));
        }
        ChunkCoordIntPair first = chunks.get(0);
        if (action == UNLOAD_AND_UNCLAIM) {
            // Unload first, then give the claims up; the server handles the messages in order.
            new MessageClaimedChunksModify(first.chunkXPos, first.chunkZPos, MessageClaimedChunksModify.UNLOAD, chunks)
                .sendToServer();
            new MessageClaimedChunksModify(first.chunkXPos, first.chunkZPos, MessageClaimedChunksModify.UNCLAIM, chunks)
                .sendToServer();
        } else {
            int message = action == CLAIM ? MessageClaimedChunksModify.CLAIM
                : action == UNCLAIM ? MessageClaimedChunksModify.UNCLAIM
                    : action == UNLOAD ? MessageClaimedChunksModify.UNLOAD : MessageClaimedChunksModify.LOAD;
            new MessageClaimedChunksModify(first.chunkXPos, first.chunkZPos, message, chunks).sendToServer();
        }

        // Show the result before the server answers, the way ServerUtilities' own map integration does.
        long now = System.currentTimeMillis();
        for (ChunkCoordIntPair chunk : chunks) {
            long packed = pack(chunk.chunkXPos, chunk.chunkZPos);
            if (action == UNCLAIM || action == UNLOAD_AND_UNCLAIM) {
                unclaimed.put(packed, now);
            } else if (action == CLAIM || action == CLAIM_AND_LOAD) {
                unclaimed.remove(packed);
            }
            switch (action) {
                case CLAIM:
                case CLAIM_AND_LOAD:
                    if (get(chunk.chunkXPos, chunk.chunkZPos, dimension) == null) {
                        NavigatorIntegration.addToOwnTeam(chunk.chunkXPos, chunk.chunkZPos);
                    }
                    if (action == CLAIM_AND_LOAD) {
                        setLoaded(chunk, dimension, true);
                    }
                    break;
                case LOAD:
                    setLoaded(chunk, dimension, true);
                    break;
                case UNLOAD:
                    setLoaded(chunk, dimension, false);
                    break;
                case UNCLAIM:
                case UNLOAD_AND_UNCLAIM:
                    NavigatorIntegration.removeChunk(chunk.chunkXPos, chunk.chunkZPos);
                    break;
                default:
                    break;
            }
        }
        // The server answers in order, after it has applied the change.
        lastRequest = 0;
        lastCounts = 0;
    }

    private static void setLoaded(ChunkCoordIntPair chunk, int dimension, boolean value) {
        ClientClaimedChunks.ChunkData data = get(chunk.chunkXPos, chunk.chunkZPos, dimension);
        if (data != null) {
            data.setLoaded(value);
        }
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    // ---------------------------------------------------------------- drawing helpers

    /** All rectangles between begin() and end() go into one batch: a big claimed area is thousands of them. */
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

    private static void hollowRect(double rx, double ry, double w, double h, double t, int rgb, int alpha, int x, int y,
        int width, int height) {
        rect(rx, ry, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + h - t, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
        rect(rx + w - t, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
    }
}
