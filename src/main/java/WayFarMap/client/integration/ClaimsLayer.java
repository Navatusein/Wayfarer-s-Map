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
    /** Outline around chunk loaded areas. */
    private static final int LOADED_OUTLINE = 0x000000;

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

    /** A claimed chunk that is chunk loaded (and not just unclaimed). */
    private static boolean loaded(int chunkX, int chunkZ, int dimension) {
        if (!unclaimed.isEmpty() && unclaimed.containsKey(pack(chunkX, chunkZ))) {
            return false;
        }
        ClientClaimedChunks.ChunkData data = get(chunkX, chunkZ, dimension);
        return data != null && data.isLoaded();
    }

    /** The neighbour is chunk loaded by the same team: the loaded area goes on there, no outline between. */
    private static boolean loaded(ClientClaimedChunks.ChunkData of, int chunkX, int chunkZ, int dimension) {
        return loaded(chunkX, chunkZ, dimension) && sameTeam(of, get(chunkX, chunkZ, dimension));
    }


    private static int teamColor(ClientClaimedChunks.ChunkData data) {
        try {
            return data.team.color.getColor()
                .rgb() & 0xFFFFFF;
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    /**
     * Draws the claims in their team's color (own ones too), with a border around each claimed area, and one
     * black outline around each area of chunk loaded chunks (none between loaded chunks side by side). And the chunks of
     * the current drag selection.
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
        double border = Math.max(pixel, Math.min(2, cell / 12));

        begin();
        for (Map.Entry<ChunkDimPos, ClientClaimedChunks.ChunkData> entry : NavigatorIntegration.CLAIMS.entrySet()) {
            ChunkDimPos pos = entry.getKey();
            if (!unclaimed.isEmpty() && unclaimed.containsKey(pack(pos.posX, pos.posZ))) {
                continue;
            }
            if (pos.dim != dimension || pos.posX < minChunkX
                || pos.posX > maxChunkX
                || pos.posZ < minChunkZ
                || pos.posZ > maxChunkZ) {
                continue;
            }
            ClientClaimedChunks.ChunkData data = entry.getValue();
            boolean own = data.team != null && data.team.isMember;
            int color = teamColor(data);
            double sx = x + (pos.posX * 16 - left) * scale;
            double sy = y + (pos.posZ * 16 - top) * scale;
            rect(sx, sy, cell, cell, color, own ? 0x70 : 0x50, x, y, width, height);
            // Border only where the neighbour isn't the same team, so a claimed area has one outline.
            if (!sameTeam(data, get(pos.posX, pos.posZ - 1, dimension))) {
                rect(sx, sy, cell, border, color, 0xE0, x, y, width, height);
            }
            if (!sameTeam(data, get(pos.posX, pos.posZ + 1, dimension))) {
                rect(sx, sy + cell - border, cell, border, color, 0xE0, x, y, width, height);
            }
            if (!sameTeam(data, get(pos.posX - 1, pos.posZ, dimension))) {
                rect(sx, sy, border, cell, color, 0xE0, x, y, width, height);
            }
            if (!sameTeam(data, get(pos.posX + 1, pos.posZ, dimension))) {
                rect(sx + cell - border, sy, border, cell, color, 0xE0, x, y, width, height);
            }
        }
        // Chunk loaded areas over the claims: an outline only where the neighbour isn't loaded.
        for (Map.Entry<ChunkDimPos, ClientClaimedChunks.ChunkData> entry : NavigatorIntegration.CLAIMS.entrySet()) {
            ChunkDimPos pos = entry.getKey();
            if (pos.dim != dimension || !entry.getValue()
                .isLoaded()
                || !loaded(pos.posX, pos.posZ, dimension)
                || pos.posX < minChunkX
                || pos.posX > maxChunkX
                || pos.posZ < minChunkZ
                || pos.posZ > maxChunkZ) {
                continue;
            }
            ClientClaimedChunks.ChunkData data = entry.getValue();
            int outline = LOADED_OUTLINE;
            double sx = x + (pos.posX * 16 - left) * scale;
            double sy = y + (pos.posZ * 16 - top) * scale;
            if (!loaded(data, pos.posX, pos.posZ - 1, dimension)) {
                rect(sx, sy, cell, border, outline, 0xFF, x, y, width, height);
            }
            if (!loaded(data, pos.posX, pos.posZ + 1, dimension)) {
                rect(sx, sy + cell - border, cell, border, outline, 0xFF, x, y, width, height);
            }
            if (!loaded(data, pos.posX - 1, pos.posZ, dimension)) {
                rect(sx, sy, border, cell, outline, 0xFF, x, y, width, height);
            }
            if (!loaded(data, pos.posX + 1, pos.posZ, dimension)) {
                rect(sx + cell - border, sy, border, cell, outline, 0xFF, x, y, width, height);
            }
        }
        if (selection != null && !selection.isEmpty()) {
            int color = selectionMode == UNCLAIM || selectionMode == UNLOAD || selectionMode == UNLOAD_AND_UNCLAIM
                ? 0xFF5050
                : selectionMode == LOAD ? LOADED_COLOR : selectionMode == CLAIM_AND_LOAD ? 0xB070FF : CLAIMED_COLOR;
            for (long packed : selection) {
                double sx = x + (unpackX(packed) * 16 - left) * scale;
                double sy = y + (unpackZ(packed) * 16 - top) * scale;
                rect(sx, sy, cell, cell, color, 0x60, x, y, width, height);
                hollowRect(sx, sy, cell, cell, pixel, color, 0xFF, x, y, width, height);
            }
        }
        end();
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
