package WayFarMap.share;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Server side of the team map: every ServerUtilities team gets a shared map. Clients with the mod upload the chunks
 * they map; the server keeps the newest version of each chunk per team (stamped with the time it arrived), passes
 * new chunks on to online teammates in the same dimension, and when a player joins or changes dimension sends
 * everything the team mapped there since that player last got it, nearest first.
 * <p>
 * Stored in {@code <world>/wayfarmap/teams/<team>/dim<id>/<surface|caveN>/r.X.Z.bin}, one file per 32x32 chunks.
 */
public final class TeamMapServer {

    public static final TeamMapServer INSTANCE = new TeamMapServer();

    private static final int REGION_SHIFT = 5;
    private static final int FILE_MAGIC = 0x57464D54;
    private static final int FILE_VERSION = 1;
    /** Upload sanity limits: chunks must be near the uploader, and not too many per second. */
    private static final int MAX_UPLOAD_DISTANCE = 96;
    private static final int MAX_UPLOADS_PER_SECOND = 400;
    /** Messages of teammates' chunks sent per player per tick while catching up. */
    private static final int SYNC_MESSAGES_PER_TICK = 2;
    private static final long SAVE_INTERVAL_MS = 30_000, IDLE_UNLOAD_MS = 120_000;

    private static Boolean active;

    /** Team maps are shared only where ServerUtilities (teams) is installed. */
    public static boolean isActive() {
        if (active == null) {
            active = Loader.isModLoaded("serverutilities");
        }
        return active;
    }

    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    /** Players whose client has the mod (said hello). */
    private final Set<UUID> capable = new HashSet<>();
    private final Map<String, TeamStore> teams = new HashMap<>();
    private final Map<UUID, Sync> syncs = new HashMap<>();
    private final Map<UUID, int[]> uploadBudget = new HashMap<>();
    private File root;
    private long lastSave;
    private int tick;

    private TeamMapServer() {}

    /** From the network thread: queued for the server thread. */
    static void receive(EntityPlayerMP player, IMessage message) {
        if (isActive() && player != null) {
            INSTANCE.inbox.add(new Object[] { player, message });
        }
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        if (tick % 20 == 0) {
            uploadBudget.clear();
        }
        Object[] entry;
        while ((entry = inbox.poll()) != null) {
            EntityPlayerMP player = (EntityPlayerMP) entry[0];
            try {
                if (entry[1] instanceof ShareNetwork.Hello) {
                    onHello(player);
                } else if (entry[1] instanceof ShareNetwork.Chunks) {
                    onUpload(player, (ShareNetwork.Chunks) entry[1]);
                }
            } catch (Exception e) {
                WayFarMap.LOG.warn("Team map: could not handle a message from " + player.getCommandSenderName(), e);
            }
        }
        for (Sync sync : new ArrayList<>(syncs.values())) {
            try {
                sync.step();
            } catch (Exception e) {
                WayFarMap.LOG.warn("Team map: sync failed for " + sync.player.getCommandSenderName(), e);
                syncs.remove(sync.player.getUniqueID());
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastSave >= SAVE_INTERVAL_MS) {
            lastSave = now;
            for (TeamStore store : teams.values()) {
                store.save(false);
                store.unloadIdle(now);
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        UUID id = event.player.getUniqueID();
        finishSync(id);
        capable.remove(id);
        uploadBudget.remove(id);
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP && capable.contains(event.player.getUniqueID())) {
            finishSync(event.player.getUniqueID());
            startSync((EntityPlayerMP) event.player);
        }
    }

    /** Saves everything and forgets the world (server stopping). */
    public void stop() {
        for (Sync sync : new ArrayList<>(syncs.values())) {
            finishSync(sync.player.getUniqueID());
        }
        for (TeamStore store : teams.values()) {
            store.save(true);
        }
        teams.clear();
        syncs.clear();
        capable.clear();
        inbox.clear();
        root = null;
    }

    // ---------------------------------------------------------------- messages

    private void onHello(EntityPlayerMP player) {
        capable.add(player.getUniqueID());
        ShareNetwork.sendTo(new ShareNetwork.Hello(), player);
        startSync(player);
    }

    private void onUpload(EntityPlayerMP player, ShareNetwork.Chunks message) {
        UUID id = player.getUniqueID();
        String teamId = SuTeams.teamId(player);
        if (teamId == null || !capable.contains(id) || message.dimension != player.dimension) {
            return;
        }
        int[] budget = uploadBudget.computeIfAbsent(id, k -> new int[1]);
        int playerChunkX = (int) Math.floor(player.posX) >> 4, playerChunkZ = (int) Math.floor(player.posZ) >> 4;
        long now = System.currentTimeMillis();
        TeamStore store = store(teamId);
        List<ChunkRecord> accepted = new ArrayList<>();
        for (ChunkRecord record : message.records) {
            if (budget[0]++ >= MAX_UPLOADS_PER_SECOND) {
                break;
            }
            if (Math.abs(record.chunkX - playerChunkX) > MAX_UPLOAD_DISTANCE
                || Math.abs(record.chunkZ - playerChunkZ) > MAX_UPLOAD_DISTANCE) {
                continue;
            }
            record.time = now;
            store.put(message.dimension, record, id);
            accepted.add(record);
        }
        if (accepted.isEmpty()) {
            return;
        }
        // Teammates in the same dimension get it right away.
        for (EntityPlayerMP mate : SuTeams.onlineTeammates(player)) {
            if (mate != player && mate.dimension == message.dimension && capable.contains(mate.getUniqueID())) {
                sendInMessages(mate, message.dimension, accepted);
            }
        }
    }

    private static void sendInMessages(EntityPlayerMP player, int dimension, List<ChunkRecord> records) {
        for (int i = 0; i < records.size(); i += ShareNetwork.MAX_RECORDS) {
            List<ChunkRecord> part = records.subList(i, Math.min(records.size(), i + ShareNetwork.MAX_RECORDS));
            ShareNetwork.sendTo(new ShareNetwork.Chunks(dimension, part), player);
        }
    }

    // ---------------------------------------------------------------- catching up

    private void startSync(EntityPlayerMP player) {
        String teamId = SuTeams.teamId(player);
        if (teamId == null) {
            return;
        }
        syncs.put(player.getUniqueID(), new Sync(player, store(teamId), player.dimension));
    }

    /** Remembers how far the player got, so next time only newer chunks are sent. */
    private void finishSync(UUID id) {
        Sync sync = syncs.remove(id);
        if (sync != null && sync.done) {
            sync.store.setLastSync(id, sync.dimension, System.currentTimeMillis());
        }
    }

    /** Sends a player everything the team mapped in a dimension since the player's last sync there. */
    private final class Sync {

        final EntityPlayerMP player;
        final TeamStore store;
        final int dimension;
        final long since, started = System.currentTimeMillis();
        final ArrayDeque<RegionKey> regions = new ArrayDeque<>();
        final ArrayDeque<ChunkRecord> pending = new ArrayDeque<>();
        boolean done;

        Sync(EntityPlayerMP player, TeamStore store, int dimension) {
            this.player = player;
            this.store = store;
            this.dimension = dimension;
            this.since = store.lastSync(player.getUniqueID(), dimension);
            List<RegionKey> keys = store.regionsNewerThan(dimension, since);
            final int rx = (int) Math.floor(player.posX) >> (4 + REGION_SHIFT);
            final int rz = (int) Math.floor(player.posZ) >> (4 + REGION_SHIFT);
            // Nearest first, the surface before the caves.
            Collections.sort(
                keys,
                (a, b) -> Integer.compare(
                    Math.max(Math.abs(a.rx - rx), Math.abs(a.rz - rz)) * 2 + (a.layer < 0 ? 0 : 1),
                    Math.max(Math.abs(b.rx - rx), Math.abs(b.rz - rz)) * 2 + (b.layer < 0 ? 0 : 1)));
            regions.addAll(keys);
        }

        void step() {
            if (done) {
                return;
            }
            if (player.dimension != dimension || player.playerNetServerHandler == null) {
                syncs.remove(player.getUniqueID());
                return;
            }
            // Read the next region file when the queue runs low (one per tick, to spread the disk reads).
            if (pending.size() < ShareNetwork.MAX_RECORDS * SYNC_MESSAGES_PER_TICK && !regions.isEmpty()) {
                StoredRegion region = store.region(regions.poll(), false);
                if (region != null) {
                    UUID id = player.getUniqueID();
                    for (StoredChunk chunk : region.chunks.values()) {
                        if (chunk.record.time > since && !id.equals(chunk.author)) {
                            pending.add(chunk.record);
                        }
                    }
                }
            }
            for (int i = 0; i < SYNC_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
                List<ChunkRecord> part = new ArrayList<>();
                while (part.size() < ShareNetwork.MAX_RECORDS && !pending.isEmpty()) {
                    part.add(pending.poll());
                }
                ShareNetwork.sendTo(new ShareNetwork.Chunks(dimension, part), player);
            }
            if (regions.isEmpty() && pending.isEmpty()) {
                done = true;
                store.setLastSync(player.getUniqueID(), dimension, started);
            }
        }
    }

    // ---------------------------------------------------------------- storage

    private TeamStore store(String teamId) {
        TeamStore store = teams.get(teamId);
        if (store == null) {
            if (root == null) {
                root = new File(DimensionManager.getCurrentSaveRootDirectory(), "wayfarmap/teams");
            }
            store = new TeamStore(new File(root, teamId));
            teams.put(teamId, store);
        }
        return store;
    }

    private static final class RegionKey {

        final int dimension, layer, rx, rz;

        RegionKey(int dimension, int layer, int rx, int rz) {
            this.dimension = dimension;
            this.layer = layer;
            this.rx = rx;
            this.rz = rz;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegionKey)) {
                return false;
            }
            RegionKey k = (RegionKey) o;
            return k.dimension == dimension && k.layer == layer && k.rx == rx && k.rz == rz;
        }

        @Override
        public int hashCode() {
            return ((dimension * 31 + layer) * 31 + rx) * 31 + rz;
        }

        String layerFolder() {
            return layer < 0 ? "surface" : "cave" + layer;
        }
    }

    private static final class StoredChunk {

        final ChunkRecord record;
        final UUID author;

        StoredChunk(ChunkRecord record, UUID author) {
            this.record = record;
            this.author = author;
        }
    }

    private static final class StoredRegion {

        final Map<Integer, StoredChunk> chunks = new HashMap<>();
        long newest;
        boolean dirty;
        long lastUse;
    }

    /** One team's map on disk, with its regions loaded on demand. */
    private static final class TeamStore {

        final File dir;
        final Map<RegionKey, StoredRegion> loaded = new HashMap<>();
        /** Newest chunk time of every region file, per dimension, read when first needed. */
        final Map<Integer, Map<RegionKey, Long>> index = new HashMap<>();
        NBTTagCompound lastSyncs;
        boolean lastSyncsDirty;

        TeamStore(File dir) {
            this.dir = dir;
        }

        File file(RegionKey key) {
            return new File(
                new File(new File(dir, "dim" + key.dimension), key.layerFolder()),
                "r." + key.rx + "." + key.rz + ".bin");
        }

        void put(int dimension, ChunkRecord record, UUID author) {
            RegionKey key = new RegionKey(
                dimension,
                record.layer,
                record.chunkX >> REGION_SHIFT,
                record.chunkZ >> REGION_SHIFT);
            StoredRegion region = region(key, true);
            int index = (record.chunkX & 31) | (record.chunkZ & 31) << REGION_SHIFT;
            region.chunks.put(index, new StoredChunk(record, author));
            region.newest = Math.max(region.newest, record.time);
            region.dirty = true;
            index(dimension).put(key, region.newest);
        }

        StoredRegion region(RegionKey key, boolean create) {
            StoredRegion region = loaded.get(key);
            if (region == null) {
                region = read(file(key));
                if (region == null) {
                    if (!create) {
                        return null;
                    }
                    region = new StoredRegion();
                }
                loaded.put(key, region);
            }
            region.lastUse = System.currentTimeMillis();
            return region;
        }

        Map<RegionKey, Long> index(int dimension) {
            Map<RegionKey, Long> regions = index.get(dimension);
            if (regions == null) {
                regions = new HashMap<>();
                File[] layers = new File(dir, "dim" + dimension).listFiles();
                if (layers != null) {
                    for (File layerDir : layers) {
                        String name = layerDir.getName();
                        int layer;
                        if (name.equals("surface")) {
                            layer = -1;
                        } else if (name.startsWith("cave")) {
                            try {
                                layer = Integer.parseInt(name.substring(4));
                            } catch (NumberFormatException e) {
                                continue;
                            }
                        } else {
                            continue;
                        }
                        File[] files = layerDir.listFiles();
                        if (files == null) {
                            continue;
                        }
                        for (File file : files) {
                            String[] parts = file.getName()
                                .split("\\.");
                            if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("bin")) {
                                continue;
                            }
                            try {
                                RegionKey key = new RegionKey(
                                    dimension,
                                    layer,
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]));
                                regions.put(key, readNewest(file));
                            } catch (Exception e) {
                                // Not a region file or unreadable: skipped.
                            }
                        }
                    }
                }
                index.put(dimension, regions);
            }
            return regions;
        }

        List<RegionKey> regionsNewerThan(int dimension, long since) {
            List<RegionKey> keys = new ArrayList<>();
            for (Map.Entry<RegionKey, Long> entry : index(dimension).entrySet()) {
                if (entry.getValue() > since) {
                    keys.add(entry.getKey());
                }
            }
            return keys;
        }

        long lastSync(UUID player, int dimension) {
            NBTTagCompound players = lastSyncs();
            return players.getCompoundTag(player.toString())
                .getLong("dim" + dimension);
        }

        void setLastSync(UUID player, int dimension, long time) {
            NBTTagCompound players = lastSyncs();
            NBTTagCompound tag = players.getCompoundTag(player.toString());
            tag.setLong("dim" + dimension, time);
            players.setTag(player.toString(), tag);
            lastSyncsDirty = true;
        }

        NBTTagCompound lastSyncs() {
            if (lastSyncs == null) {
                File file = new File(dir, "players.dat");
                try {
                    lastSyncs = file.isFile() ? CompressedStreamTools.read(file) : null;
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not read " + file, e);
                }
                if (lastSyncs == null) {
                    lastSyncs = new NBTTagCompound();
                }
            }
            return lastSyncs;
        }

        void save(boolean all) {
            for (Map.Entry<RegionKey, StoredRegion> entry : loaded.entrySet()) {
                if (entry.getValue().dirty) {
                    write(file(entry.getKey()), entry.getValue());
                }
            }
            if (lastSyncsDirty && lastSyncs != null) {
                try {
                    dir.mkdirs();
                    CompressedStreamTools.safeWrite(lastSyncs, new File(dir, "players.dat"));
                    lastSyncsDirty = false;
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not save " + dir, e);
                }
            }
        }

        void unloadIdle(long now) {
            Iterator<StoredRegion> it = loaded.values()
                .iterator();
            while (it.hasNext()) {
                StoredRegion region = it.next();
                if (!region.dirty && now - region.lastUse > IDLE_UNLOAD_MS) {
                    it.remove();
                }
            }
        }

        private static void write(File file, StoredRegion region) {
            File tmp = new File(file.getPath() + ".tmp");
            try {
                file.getParentFile()
                    .mkdirs();
                try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
                    out.writeInt(FILE_MAGIC);
                    out.writeInt(FILE_VERSION);
                    out.writeLong(region.newest);
                    out.writeInt(region.chunks.size());
                    for (StoredChunk chunk : region.chunks.values()) {
                        out.writeLong(chunk.author.getMostSignificantBits());
                        out.writeLong(chunk.author.getLeastSignificantBits());
                        chunk.record.write(out);
                    }
                }
                if (file.exists() && !file.delete()) {
                    throw new IOException("Could not replace " + file);
                }
                if (!tmp.renameTo(file)) {
                    throw new IOException("Could not rename " + tmp);
                }
                region.dirty = false;
            } catch (IOException e) {
                WayFarMap.LOG.warn("Team map: could not save " + file, e);
            }
        }

        private static StoredRegion read(File file) {
            if (!file.isFile()) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
                if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                    return null;
                }
                StoredRegion region = new StoredRegion();
                region.newest = in.readLong();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    UUID author = new UUID(in.readLong(), in.readLong());
                    ChunkRecord record = ChunkRecord.read(in);
                    region.chunks.put((record.chunkX & 31) | (record.chunkZ & 31) << REGION_SHIFT,
                        new StoredChunk(record, author));
                }
                return region;
            } catch (IOException e) {
                WayFarMap.LOG.warn("Team map: could not read " + file, e);
                return null;
            }
        }

        private static long readNewest(File file) throws IOException {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
                if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                    throw new IOException("Not a team map region");
                }
                return in.readLong();
            }
        }
    }
}
