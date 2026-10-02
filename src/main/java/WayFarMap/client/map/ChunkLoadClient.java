package WayFarMap.client.map;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.map.iso.IsoLog;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * The client's part of {@code /wf chunkload}: the server sends a batch of chunks as the game sends chunks, then says
 * which they are. Once they are all here, the inner ones are mapped (flat map, and the 3D map for {@code 3d}), a few
 * milliseconds per tick; then the chunks the game doesn't need are let go and the server is asked for the next batch.
 * The world map shows how far it got.
 */
public final class ChunkLoadClient {

    public static final ChunkLoadClient INSTANCE = new ChunkLoadClient();

    /** How long to wait for a batch's chunks before mapping those that came. */
    private static final long WAIT_MS = 30_000;

    private final Queue<IMessage> inbox = new ConcurrentLinkedQueue<>();
    private ShareNetwork.LoadBatch batch;
    private long batchSince;
    /** Next inner chunk of the batch to map (row by row), and whether its flat map is done. */
    private int at;
    private boolean scanned;
    private boolean lettingGo;
    // Progress, for the world map.
    private long done, total;
    private boolean with3d;
    private long startedAt;
    /** When the last chunk of the area was mapped, 0 while it isn't done. */
    private long finishedAt;
    private long lastBatchAt;
    /** For the log: the batch's chunks mapped and those that never came, the time mapping them, when it began. */
    private int mappedCount, skippedCount;
    private long workNanos, firstWorkAt;
    private StringBuilder skippedList = new StringBuilder();

    private ChunkLoadClient() {}

    /** From the network thread. */
    public void receive(IMessage message) {
        inbox.add(message);
    }

    /** While chunks of a batch are let go (the map's usual handling of chunks let go is skipped). */
    public boolean isLettingGo() {
        return lettingGo;
    }

    /**
     * What the world map shows while an area is being loaded, null when none is (or it ended over a minute ago).
     */
    public String statusText() {
        if (total <= 0 || System.currentTimeMillis() - lastBatchAt > 60_000 && batch == null) {
            return null;
        }
        long percent = done * 100 / total;
        // Seconds since the area was started (or taken up again), stopped once it is done.
        long end = finishedAt != 0 ? finishedAt : System.currentTimeMillis();
        long seconds = Math.max(0, end - startedAt) / 1000;
        String elapsed = I18n.format("wayfarmap.chunkload.elapsed", String.format(Locale.US, "%,d", seconds));
        return I18n.format("wayfarmap.chunkload.progress", with3d ? "3D" : "2D", done, total, percent, elapsed);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world == null || mc.thePlayer == null) {
            inbox.clear();
            batch = null;
            total = 0;
            return;
        }
        IMessage message;
        while ((message = inbox.poll()) != null) {
            if (message instanceof ShareNetwork.LoadBatch) {
                start((ShareNetwork.LoadBatch) message);
            }
        }
        ShareNetwork.LoadBatch b = batch;
        if (b == null) {
            return;
        }
        if (world.provider.dimensionId != b.dimension) {
            // The server pauses it; this batch is sent again when the player is back.
            batch = null;
            return;
        }
        if (!arrived(world, b) && System.currentTimeMillis() - batchSince < WAIT_MS) {
            return;
        }
        if (firstWorkAt == 0) {
            firstWorkAt = System.currentTimeMillis();
        }
        long start = System.nanoTime();
        try {
            mapBatch(world, b, mc, start + Math.max(1, Config.chunkloadClientMs) * 1_000_000L);
        } finally {
            workNanos += System.nanoTime() - start;
        }
    }

    private void mapBatch(WorldClient world, ShareNetwork.LoadBatch b, Minecraft mc, long end) {
        int width = b.innerX1 - b.innerX0 + 1, count = width * (b.innerZ1 - b.innerZ0 + 1);
        while (at < count) {
            int cx = b.innerX0 + at % width, cz = b.innerZ0 + at / width;
            // The client's chunk provider says every chunk exists: one not received is empty.
            Chunk chunk = ChunkScanner.isChunkReady(world, cx, cz) ? world.getChunkFromChunkCoords(cx, cz) : null;
            if (chunk == null) {
                // Never came (waited for above): a hole in the map.
                skippedCount++;
                if (skippedList.length() < 600) {
                    skippedList.append(' ')
                        .append(cx)
                        .append(',')
                        .append(cz);
                }
            }
            if (chunk != null && !scanned) {
                if (System.nanoTime() >= end) {
                    return;
                }
                if (!MapManager.INSTANCE.scanForLoad(chunk, b.with3d)) {
                    // Its regions are being read: next tick.
                    return;
                }
                mappedCount++;
                scanned = true;
                ChunkLoadView.mapped(b.dimension, cx, cz);
            }
            if (chunk != null && b.with3d && Config.record3d) {
                // The 3D map's own time per tick; a chunk with many pictures takes several ticks.
                long deadline = System.nanoTime() + Math.max(2, Config.isoCaptureMs) * 1_000_000L;
                if (!IsoMap.INSTANCE.captureForLoad(world, chunk, deadline)) {
                    return;
                }
            }
            at++;
            scanned = false;
            done = b.doneBefore + at;
            if (b.with3d && System.nanoTime() >= end) {
                return;
            }
        }
        finish(world, b, mc);
    }

    private void start(ShareNetwork.LoadBatch b) {
        if (total <= 0 || b.doneBefore < done - 64 || b.total != total || b.with3d != with3d) {
            // A new area (or one taken up again): the time left is worked out from here.
            startedAt = System.currentTimeMillis();
            finishedAt = 0;
        }
        batch = b;
        at = 0;
        firstWorkAt = 0;
        scanned = false;
        mappedCount = 0;
        skippedCount = 0;
        workNanos = 0;
        skippedList = new StringBuilder();
        done = b.doneBefore;
        total = b.total;
        with3d = b.with3d;
        String text = "CHUNKLOAD batch " + b.index
            + " inner "
            + b.innerX0
            + ","
            + b.innerZ0
            + ".."
            + b.innerX1
            + ","
            + b.innerZ1
            + " done "
            + b.doneBefore
            + "/"
            + b.total
            + (b.with3d ? " 3D" : " 2D")
            + " server[sent="
            + b.sent
            + " loadedAgain="
            + b.reloaded
            + " couldNotLoad="
            + b.missing
            + " batchMs="
            + b.serverMs
            + " workMs="
            + b.workMs
            + "] sinceLastBatchMs="
            + (lastBatchAt == 0 ? -1 : System.currentTimeMillis() - lastBatchAt);
        batchSince = System.currentTimeMillis();
        lastBatchAt = batchSince;
        IsoLog.log(text);
        FlatLog.log(text);
    }

    /**
     * Whether every chunk sent in the batch is here (the client's chunk provider says every chunk exists; one not
     * received yet is empty). Chunks the server could not give are not waited for past {@link #WAIT_MS}.
     */
    private static boolean arrived(WorldClient world, ShareNetwork.LoadBatch b) {
        int ready = 0;
        for (int z = b.outerZ0; z <= b.outerZ1; z++) {
            for (int x = b.outerX0; x <= b.outerX1; x++) {
                if (ChunkScanner.isChunkReady(world, x, z)) {
                    ready++;
                }
            }
        }
        return ready >= b.sent;
    }

    /** Lets go of the batch's chunks the game doesn't need, and asks for the next batch. */
    private void finish(WorldClient world, ShareNetwork.LoadBatch b, Minecraft mc) {
        int pcx = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int pcz = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        int keep = b.viewDistance + 1;
        lettingGo = true;
        try {
            for (int z = b.outerZ0; z <= b.outerZ1; z++) {
                for (int x = b.outerX0; x <= b.outerX1; x++) {
                    long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                    IsoMap.INSTANCE.forgetLoaded(key);
                    if (Math.abs(x - pcx) <= keep && Math.abs(z - pcz) <= keep) {
                        // The game's own chunk around the player.
                        continue;
                    }
                    if (world.getChunkProvider()
                        .chunkExists(x, z)) {
                        world.doPreChunk(x, z, false);
                    }
                }
            }
        } catch (RuntimeException e) {
            WayFarMap.LOG.warn("Could not let go of chunks loaded for /wf chunkload", e);
        } finally {
            lettingGo = false;
        }
        done = b.doneBefore + (long) (b.innerX1 - b.innerX0 + 1) * (b.innerZ1 - b.innerZ0 + 1);
        if (done >= total && finishedAt == 0) {
            finishedAt = System.currentTimeMillis();
        }
        String text = "CHUNKLOAD_DONE batch " + b.index
            + " mapped="
            + mappedCount
            + " skipped(never came)="
            + skippedCount
            + (skippedCount > 0 ? " [" + skippedList.toString()
                .trim() + "]" : "")
            + " waitedForChunksMs="
            + Math.max(0, firstWorkAt - batchSince)
            + " clientMs="
            + String.format(java.util.Locale.ROOT, "%.1f", workNanos / 1e6)
            + " batchOnClientMs="
            + (System.currentTimeMillis() - batchSince);
        IsoLog.log(text);
        FlatLog.log(text);
        batch = null;
        ShareNetwork.sendToServer(new ShareNetwork.LoadDone(b.job, b.index));
    }
}
