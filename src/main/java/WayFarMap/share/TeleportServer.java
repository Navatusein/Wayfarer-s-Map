package WayFarMap.share;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Teleporting from the world map into another dimension, on servers that have this mod: the client asks, and the
 * server moves the player there if they are an operator (the same check as loading chunks from the map, the level
 * vanilla's {@code /tp} needs). Without a known height the player is put on the highest free spot of the column.
 */
public final class TeleportServer {

    public static final TeleportServer INSTANCE = new TeleportServer();

    /** Requests from the network thread, handled on the server's tick. */
    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();

    private TeleportServer() {}

    /** Forgets the requests not handled yet (server stopping): they hold their players and so their worlds. */
    public void stop() {
        inbox.clear();
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.TeleportRequest message) {
        inbox.add(new Object[] { player, message });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Object[] next;
        while ((next = inbox.poll()) != null) {
            if (!TeamMapServer.isOnline((EntityPlayerMP) next[0])) {
                // Left since, or asked before a restart: the old player (and its world) isn't kept.
                continue;
            }
            try {
                teleport((EntityPlayerMP) next[0], (ShareNetwork.TeleportRequest) next[1]);
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Teleport from the map failed", e);
            }
        }
    }

    private void teleport(EntityPlayerMP player, ShareNetwork.TeleportRequest request) {
        if (player.playerNetServerHandler == null || player.isDead
            || !player.canCommandSenderUseCommand(2, "wf")
            || !DimensionManager.isDimensionRegistered(request.dimension)) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer world = server.worldServerForDimension(request.dimension);
        if (world == null) {
            return;
        }
        int x = request.x, z = request.z;
        int y = request.y > 0 && request.y < 256 ? request.y : safeY(world, x, z);
        if (player.ridingEntity != null) {
            player.mountEntity(null);
        }
        if (player.dimension != request.dimension) {
            server.getConfigurationManager()
                .transferPlayerToDimension(player, request.dimension, new NoPortal(world));
        }
        player.playerNetServerHandler.setPlayerLocation(x + 0.5, y, z + 0.5, player.rotationYaw, player.rotationPitch);
        player.fallDistance = 0;
    }

    /** The highest spot of the column with solid ground under it and room for the player. */
    private static int safeY(World world, int x, int z) {
        // Loads (or generates) the chunk, so its blocks are known.
        world.getChunkFromBlockCoords(x, z);
        // Under a roof of bedrock (the Nether), from below the roof: going down, the first free spot on ground.
        int top = world.provider.hasNoSky ? Math.min(world.getActualHeight(), 128) - 3 : world.getHeight() - 2;
        for (int y = top; y > 0; y--) {
            boolean free = !world.getBlock(x, y, z)
                .getMaterial()
                .blocksMovement()
                && !world.getBlock(x, y + 1, z)
                    .getMaterial()
                    .blocksMovement();
            boolean ground = world.getBlock(x, y - 1, z)
                .getMaterial()
                .blocksMovement();
            if (free && ground) {
                return y;
            }
        }
        return Math.max(1, world.getTopSolidOrLiquidBlock(x, z));
    }

    /** Moves the player into the other dimension without looking for or building a portal. */
    private static final class NoPortal extends Teleporter {

        NoPortal(WorldServer world) {
            super(world);
        }

        @Override
        public void placeInPortal(Entity entity, double x, double y, double z, float yaw) {}

        @Override
        public boolean placeInExistingPortal(Entity entity, double x, double y, double z, float yaw) {
            return true;
        }

        @Override
        public boolean makePortal(Entity entity) {
            return true;
        }

        @Override
        public void removeStalePortalLocations(long time) {}
    }
}
