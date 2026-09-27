package WayFarMap.client.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.share.ChunkRecord;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Client side of the team map. On joining a server the client says hello; if the server shares team maps it
 * answers, and from then on every chunk the client maps is uploaded (unless it looks the same as when it was last
 * sent) and the chunks teammates map are written into this map as they arrive.
 */
public final class TeamMapClient {

    public static final TeamMapClient INSTANCE = new TeamMapClient();

    /** Received chunks written into the map per tick (each is 256 pixels). */
    private static final int APPLY_PER_TICK = 48;
    /** Chunks waiting to be uploaded at most; the oldest are dropped (they get rescanned anyway). */
    private static final int MAX_OUTGOING = 2048;
    private static final int SENT_MEMORY = 8192;

    private final Queue<IMessage> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<ChunkRecord> outgoing = new ArrayDeque<>();
    private final ArrayDeque<Object[]> incoming = new ArrayDeque<>();
    /** What each recently uploaded chunk looked like, so unchanged rescans aren't sent again. */
    private final Map<Long, Integer> sent = new LinkedHashMap<Long, Integer>(1024, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > SENT_MEMORY;
        }
    };
    private boolean helloSent;
    private boolean serverShares;
    private int dimension = Integer.MIN_VALUE;

    private TeamMapClient() {}

    /** True while connected to a server that shares team maps (and sharing is on). */
    public boolean isActive() {
        return serverShares && Config.shareMapWithTeam;
    }

    /** From the network thread. */
    public void receive(IMessage message) {
        inbox.add(message);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            // Left the server: start over on the next one.
            if (helloSent || serverShares) {
                helloSent = false;
                serverShares = false;
                inbox.clear();
                outgoing.clear();
                incoming.clear();
                sent.clear();
            }
            return;
        }
        if (!helloSent) {
            helloSent = true;
            ShareNetwork.sendToServer(new ShareNetwork.Hello());
        }
        int currentDimension = mc.theWorld.provider.dimensionId;
        if (currentDimension != dimension) {
            dimension = currentDimension;
            outgoing.clear();
            sent.clear();
        }

        IMessage message;
        while ((message = inbox.poll()) != null) {
            if (message instanceof ShareNetwork.Hello) {
                if (!serverShares) {
                    WayFarMap.LOG.info("This server shares the map between team members");
                }
                serverShares = true;
            } else if (message instanceof ShareNetwork.Chunks && Config.shareMapWithTeam) {
                ShareNetwork.Chunks chunks = (ShareNetwork.Chunks) message;
                for (ChunkRecord record : chunks.records) {
                    incoming.add(new Object[] { chunks.dimension, record });
                }
            }
        }

        // Teammates' chunks: written as their regions are ready, without waiting for disk reads.
        for (int i = 0; i < APPLY_PER_TICK && !incoming.isEmpty(); i++) {
            Object[] next = incoming.peek();
            if (!MapManager.INSTANCE.applySharedChunk((Integer) next[0], (ChunkRecord) next[1])) {
                // Its region is still loading; move it to the back and go on with the others.
                incoming.add(incoming.poll());
                continue;
            }
            incoming.poll();
        }

        if (isActive() && !outgoing.isEmpty()) {
            List<ChunkRecord> part = new ArrayList<>();
            while (part.size() < ShareNetwork.MAX_UPLOAD_RECORDS && !outgoing.isEmpty()) {
                part.add(outgoing.poll());
            }
            ShareNetwork.sendToServer(new ShareNetwork.Chunks(dimension, part));
        }
    }

    /** Called by the map right after it scanned a chunk: queues it for the teammates if it changed. */
    void onChunkScanned(MapDimension map, MapDimension biomeMap, int layer, int chunkX, int chunkZ) {
        if (!isActive() || map.dimensionId != dimension) {
            return;
        }
        int rx = chunkX >> (MapRegion.SHIFT - 4), rz = chunkZ >> (MapRegion.SHIFT - 4);
        MapRegion region = map.getLoadedRegion(rx, rz);
        if (region == null) {
            return;
        }
        MapRegion biomeRegion = layer < 0 && biomeMap != null ? biomeMap.getLoadedRegion(rx, rz) : null;
        ChunkRecord record = new ChunkRecord();
        record.chunkX = chunkX;
        record.chunkZ = chunkZ;
        record.layer = layer;
        if (biomeRegion != null) {
            record.biomes = new byte[ChunkRecord.AREA];
        }
        int baseX = (chunkX * 16) & (MapRegion.SIZE - 1);
        int baseZ = (chunkZ * 16) & (MapRegion.SIZE - 1);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                record.colors[i] = region.getPixel(baseX + lx, baseZ + lz);
                if (layer < 0) {
                    record.extra[i] = (byte) region.getExtra(baseX + lx, baseZ + lz);
                }
                if (biomeRegion != null) {
                    record.biomes[i] = (byte) biomeRegion.getExtra(baseX + lx, baseZ + lz);
                }
            }
        }
        if (!record.hasPixels()) {
            return;
        }
        long key = ((long) chunkX << 36) ^ ((long) (chunkZ & 0xFFFFFFFL) << 4) ^ (layer + 1);
        int hash = record.contentHash();
        Integer previous = sent.get(key);
        if (previous != null && previous == hash) {
            return;
        }
        sent.put(key, hash);
        if (outgoing.size() >= MAX_OUTGOING) {
            outgoing.poll();
        }
        outgoing.add(record);
    }
}
