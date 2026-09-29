package WayFarMap.share;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * {@code /wf chunkload 2d|3d <radius>}: maps a large area around the player, generating the chunks not made yet. The
 * server loads (or generates) the chunks batch by batch, nearest first, and sends them to the player as the game
 * sends chunks; the player's map maps them (the flat map, and with {@code 3d} the 3D map too) and lets them go, then
 * asks for the next batch. Only for players allowed to cheat (operators). Goes on after the player or the server
 * comes back; {@code /wf chunkload stop} ends it.
 */
public final class ChunkLoadServer {

    public static final ChunkLoadServer INSTANCE = new ChunkLoadServer();

    /** Chunks per side of a batch (mapped); a ring of one more is sent around it for their neighbours. */
    private static final int BATCH = 8;
    /** Time per server tick spent loading and generating chunks. */
    private static final long TICK_NANOS = 20_000_000L;
    /** Chunks per packet, as the game sends them. */
    private static final int PER_PACKET = 5;
    private static final String FILE = "wayfarmap_chunkload.dat";

    /** One player's area being mapped. */
    private static final class Job {

        final UUID player;
        final int dimension, centerX, centerZ, radius;
        final boolean with3d;
        final int id;
        /** Batches in the order they are done: grid positions {i, j} around the center, nearest first. */
        final int[] order;
        int index;
        long done;
        final long total;
        final long started;
        // Not saved:
        /** Chunks of the batch being loaded, and how far. */
        int[] loading;
        int loadingAt;
        boolean waiting;
        EntityPlayerMP sentTo;
        int[] previousOuter;
        boolean pausedTold;

        Job(UUID player, int dimension, int centerX, int centerZ, int radius, boolean with3d, int id, long started) {
            this.player = player;
            this.dimension = dimension;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.with3d = with3d;
            this.id = id;
            this.started = started;
            this.order = spiral((radius + BATCH - 1) / BATCH, radius);
            long side = 2L * radius + 1;
            this.total = side * side;
        }

        /** Inner chunks of batch n: {x0, z0, x1, z1}, inclusive, within the area. */
        int[] inner(int n) {
            int i = order[n * 2], j = order[n * 2 + 1];
            int x0 = Math.max(centerX - radius, centerX + i * BATCH - BATCH / 2);
            int z0 = Math.max(centerZ - radius, centerZ + j * BATCH - BATCH / 2);
            int x1 = Math.min(centerX + radius, centerX + i * BATCH + BATCH / 2 - 1);
            int z1 = Math.min(centerZ + radius, centerZ + j * BATCH + BATCH / 2 - 1);
            return new int[] { x0, z0, x1, z1 };
        }

        int batches() {
            return order.length / 2;
        }
    }

    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();

    private ChunkLoadServer() {}

    /**
     * Batch grid positions ring by ring from the middle ({i, j} pairs), leaving out those outside the area of the
     * radius (the outer ring may reach past it).
     */
    static int[] spiral(int rings, int radius) {
        int side = 2 * rings + 1;
        int[] order = new int[side * side * 2];
        int n = 0;
        for (int k = 0; k <= rings; k++) {
            List<int[]> ring = new ArrayList<>();
            if (k == 0) {
                ring.add(new int[] { 0, 0 });
            }
            // Round the ring: top row, right column, bottom row, left column.
            for (int i = -k; i < k; i++) {
                ring.add(new int[] { i, -k });
            }
            for (int j = -k; j < k; j++) {
                ring.add(new int[] { k, j });
            }
            for (int i = k; i > -k; i--) {
                ring.add(new int[] { i, k });
            }
            for (int j = k; j > -k; j--) {
                ring.add(new int[] { -k, j });
            }
            for (int[] p : ring) {
                if (within(p[0], radius) && within(p[1], radius)) {
                    order[n++] = p[0];
                    order[n++] = p[1];
                }
            }
        }
        return java.util.Arrays.copyOf(order, n);
    }

    /** Whether the batches in column (or row) i hold any chunk within the radius. */
    private static boolean within(int i, int radius) {
        return i * BATCH - BATCH / 2 <= radius && i * BATCH + BATCH / 2 - 1 >= -radius;
    }

    // ---------------------------------------------------------------- the command

    public static final class Command extends CommandBase {

        @Override
        public String getCommandName() {
            return "wf";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/wf chunkload <2d|3d> <radius in chunks> | /wf chunkload stop | /wf chunkload status";
        }

        /** Operators only (single player: with cheats allowed). */
        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length < 2 || !args[0].equalsIgnoreCase("chunkload")) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            String what = args[1].toLowerCase();
            if (what.equals("stop")) {
                INSTANCE.stop(player);
            } else if (what.equals("status")) {
                INSTANCE.status(player);
            } else if ((what.equals("2d") || what.equals("3d")) && args.length >= 3) {
                int radius = parseIntWithMin(sender, args[2], 1);
                INSTANCE.begin(player, what.equals("3d"), radius);
            } else {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }

        @Override
        public List addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "chunkload");
            }
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, "2d", "3d", "stop", "status");
            }
            return null;
        }
    }

    private void begin(EntityPlayerMP player, boolean with3d, int radius) {
        Job old = jobs.remove(player.getUniqueID());
        if (old != null) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.replaced"));
        }
        int cx = (int) Math.floor(player.posX) >> 4, cz = (int) Math.floor(player.posZ) >> 4;
        Job job = new Job(
            player.getUniqueID(),
            player.dimension,
            cx,
            cz,
            radius,
            with3d,
            (int) (System.nanoTime() & 0x7FFFFFFF),
            System.currentTimeMillis());
        jobs.put(job.player, job);
        save();
        player.addChatMessage(
            new ChatComponentTranslation(
                "wayfarmap.chunkload.started",
                with3d ? "3D" : "2D",
                2 * radius + 1,
                2 * radius + 1,
                job.total,
                (long) radius * 16));
        WayFarMap.LOG.info(
            "{} started mapping {} chunks around {}, {} in dimension {} ({})",
            player.getCommandSenderName(),
            job.total,
            cx,
            cz,
            job.dimension,
            with3d ? "3D" : "2D");
    }

    private void stop(EntityPlayerMP player) {
        Job job = jobs.remove(player.getUniqueID());
        if (job == null) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.none"));
            return;
        }
        release(job, job.previousOuter, null);
        release(job, job.loading, null);
        save();
        player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.stopped", job.done, job.total));
    }

    private void status(EntityPlayerMP player) {
        Job job = jobs.get(player.getUniqueID());
        if (job == null) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.none"));
            return;
        }
        player.addChatMessage(
            new ChatComponentTranslation(
                "wayfarmap.chunkload.status",
                job.with3d ? "3D" : "2D",
                job.done,
                job.total,
                job.done * 100 / Math.max(1, job.total)));
    }

    // ---------------------------------------------------------------- running

    /** From the network thread: the player's client mapped a batch. */
    void receive(EntityPlayerMP player, ShareNetwork.LoadDone message) {
        inbox.add(new Object[] { player.getUniqueID(), message });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || jobs.isEmpty()) {
            inbox.clear();
            return;
        }
        Object[] next;
        while ((next = inbox.poll()) != null) {
            Job job = jobs.get(next[0]);
            ShareNetwork.LoadDone done = (ShareNetwork.LoadDone) next[1];
            if (job != null && job.waiting && done.job == job.id && done.index == job.index) {
                batchDone(job);
            }
        }
        long end = System.nanoTime() + TICK_NANOS;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (System.nanoTime() >= end) {
                break;
            }
            work(job, end);
        }
    }

    private static EntityPlayerMP online(UUID id) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.getUniqueID()
                .equals(id)) {
                return player;
            }
        }
        return null;
    }

    private void work(Job job, long end) {
        EntityPlayerMP player = online(job.player);
        if (player == null) {
            // Goes on when the player is back (the batch is sent again: the client lost it).
            job.waiting = false;
            job.sentTo = null;
            return;
        }
        if (player.dimension != job.dimension) {
            job.waiting = false;
            if (!job.pausedTold) {
                job.pausedTold = true;
                player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.paused", job.dimension));
            }
            return;
        }
        job.pausedTold = false;
        if (job.waiting) {
            if (job.sentTo != player) {
                // The player came back (a new connection): the batch again.
                job.waiting = false;
            } else {
                return;
            }
        }
        WorldServer world = DimensionManager.getWorld(job.dimension);
        if (world == null) {
            return;
        }
        ChunkProviderServer provider = world.theChunkProviderServer;
        if (job.loading == null) {
            int[] inner = job.inner(job.index);
            job.loading = new int[] { inner[0] - 1, inner[1] - 1, inner[2] + 1, inner[3] + 1 };
            job.loadingAt = 0;
        }
        int[] outer = job.loading;
        int width = outer[2] - outer[0] + 1, count = width * (outer[3] - outer[1] + 1);
        while (job.loadingAt < count && System.nanoTime() < end) {
            int x = outer[0] + job.loadingAt % width, z = outer[1] + job.loadingAt / width;
            try {
                // Generated (and decorated once its neighbours are there) if it wasn't yet.
                provider.loadChunk(x, z);
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Could not load chunk " + x + ", " + z + " for /wf chunkload", e);
            }
            job.loadingAt++;
        }
        if (job.loadingAt < count) {
            return;
        }
        send(job, player, world, outer);
    }

    /** Sends the batch's chunks as the game sends them, their tile entities, then the batch itself. */
    private void send(Job job, EntityPlayerMP player, WorldServer world, int[] outer) {
        List<Chunk> chunks = new ArrayList<>();
        for (int z = outer[1]; z <= outer[3]; z++) {
            for (int x = outer[0]; x <= outer[2]; x++) {
                if (world.theChunkProviderServer.chunkExists(x, z)) {
                    chunks.add(world.getChunkFromChunkCoords(x, z));
                }
            }
        }
        for (int from = 0; from < chunks.size(); from += PER_PACKET) {
            player.playerNetServerHandler.sendPacket(
                new S26PacketMapChunkBulk(chunks.subList(from, Math.min(chunks.size(), from + PER_PACKET))));
        }
        for (Chunk chunk : chunks) {
            for (Object o : chunk.chunkTileEntityMap.values()) {
                TileEntity tileEntity = (TileEntity) o;
                try {
                    Packet packet = tileEntity.getDescriptionPacket();
                    if (packet != null) {
                        player.playerNetServerHandler.sendPacket(packet);
                    }
                } catch (RuntimeException e) {
                    // A tile entity that can't describe itself: drawn as its block.
                }
            }
        }
        int[] inner = job.inner(job.index);
        ShareNetwork.LoadBatch batch = new ShareNetwork.LoadBatch();
        batch.job = job.id;
        batch.index = job.index;
        batch.dimension = job.dimension;
        batch.with3d = job.with3d;
        batch.innerX0 = inner[0];
        batch.innerZ0 = inner[1];
        batch.innerX1 = inner[2];
        batch.innerZ1 = inner[3];
        batch.outerX0 = outer[0];
        batch.outerZ0 = outer[1];
        batch.outerX1 = outer[2];
        batch.outerZ1 = outer[3];
        batch.doneBefore = job.done;
        batch.total = job.total;
        batch.viewDistance = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance();
        ShareNetwork.sendTo(batch, player);
        job.waiting = true;
        job.sentTo = player;
    }

    private void batchDone(Job job) {
        job.waiting = false;
        int[] inner = job.inner(job.index);
        job.done += (long) (inner[2] - inner[0] + 1) * (inner[3] - inner[1] + 1);
        // The batch before is let go (this one stays: the next one needs its ring).
        release(job, job.previousOuter, job.loading);
        job.previousOuter = job.loading;
        job.loading = null;
        job.index++;
        if (job.index % 16 == 0) {
            save();
        }
        if (job.index >= job.batches()) {
            jobs.remove(job.player);
            release(job, job.previousOuter, null);
            save();
            EntityPlayerMP player = online(job.player);
            long seconds = (System.currentTimeMillis() - job.started) / 1000;
            if (player != null) {
                player.addChatMessage(
                    new ChatComponentTranslation(
                        "wayfarmap.chunkload.finished",
                        job.total,
                        seconds / 3600,
                        seconds / 60 % 60,
                        seconds % 60));
            }
        }
    }

    /** Lets the server unload the chunks of an area (not those in {@code keep}, nor near any player). */
    private void release(Job job, int[] area, int[] keep) {
        if (area == null) {
            return;
        }
        WorldServer world = DimensionManager.getWorld(job.dimension);
        if (world == null) {
            return;
        }
        int near = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance() + 2;
        List<int[]> players = new ArrayList<>();
        for (Object o : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            players.add(new int[] { (int) Math.floor(player.posX) >> 4, (int) Math.floor(player.posZ) >> 4 });
        }
        for (int z = area[1]; z <= area[3]; z++) {
            for (int x = area[0]; x <= area[2]; x++) {
                if (keep != null && x >= keep[0] && x <= keep[2] && z >= keep[1] && z <= keep[3]) {
                    continue;
                }
                boolean seen = false;
                for (int[] p : players) {
                    if (Math.abs(p[0] - x) <= near && Math.abs(p[1] - z) <= near) {
                        seen = true;
                        break;
                    }
                }
                if (!seen && world.theChunkProviderServer.chunkExists(x, z)) {
                    world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x, z);
                }
            }
        }
    }

    // ---------------------------------------------------------------- saved between server starts

    public void start() {
        jobs.clear();
        inbox.clear();
        File file = file();
        if (file == null || !file.isFile()) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            NBTTagList list = CompressedStreamTools.readCompressed(in)
                .getTagList("jobs", 10);
            for (int n = 0; n < list.tagCount(); n++) {
                NBTTagCompound tag = list.getCompoundTagAt(n);
                Job job = new Job(
                    new UUID(tag.getLong("most"), tag.getLong("least")),
                    tag.getInteger("dimension"),
                    tag.getInteger("x"),
                    tag.getInteger("z"),
                    tag.getInteger("radius"),
                    tag.getBoolean("3d"),
                    tag.getInteger("id"),
                    tag.getLong("started"));
                job.index = tag.getInteger("index");
                job.done = tag.getLong("done");
                if (job.index < job.batches()) {
                    jobs.put(job.player, job);
                }
            }
        } catch (IOException | RuntimeException e) {
            WayFarMap.LOG.warn("Could not read " + file, e);
        }
    }

    public void stop() {
        save();
        jobs.clear();
        inbox.clear();
    }

    private void save() {
        File file = file();
        if (file == null) {
            return;
        }
        NBTTagList list = new NBTTagList();
        for (Job job : jobs.values()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("most", job.player.getMostSignificantBits());
            tag.setLong("least", job.player.getLeastSignificantBits());
            tag.setInteger("dimension", job.dimension);
            tag.setInteger("x", job.centerX);
            tag.setInteger("z", job.centerZ);
            tag.setInteger("radius", job.radius);
            tag.setBoolean("3d", job.with3d);
            tag.setInteger("id", job.id);
            tag.setLong("started", job.started);
            tag.setInteger("index", job.index);
            tag.setLong("done", job.done);
            list.appendTag(tag);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("jobs", list);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, out);
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save " + file, e);
        }
    }

    private static File file() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : new File(new File(root, "data"), FILE);
    }
}
