package WayFarMap.client;

import java.lang.reflect.Field;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.network.play.client.C14PacketTabComplete;
import net.minecraft.network.play.server.S3APacketTabComplete;
import net.minecraft.world.World;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.ChunkLoadView;
import WayFarMap.client.map.MapManager;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Teleporting with the vanilla {@code /tp x y z} command, offered only when the player may use it. Into another
 * dimension: through this mod on the server (operators), or else with CoFH Core's {@code /cofh tpx}.
 * <p>
 * The client isn't told its permissions, so it asks the server to tab-complete {@code /tp}: servers (and permission
 * plugins) only complete commands the player is allowed to run. The answer is read from the network pipeline before
 * the game would hand it to the chat screen.
 */
public final class Teleport {

    public static final Teleport INSTANCE = new Teleport();

    private static final long PROBE_INTERVAL_MS = 30_000;
    private static final long PROBE_TIMEOUT_MS = 5_000;

    /** Commands asked about in turn: vanilla's teleport, and CoFH Core's (its tpx goes to other dimensions). */
    private static final String[] PROBES = { "/tp", "/cofh" };
    private static final int PROBE_TP = 0, PROBE_COFH = 1;
    /** How long after one question the next one is asked. */
    private static final long PROBE_GAP_MS = 1_000;

    private volatile boolean allowed;
    /**
     * The server (with this mod) says the player may use /tp: it knows ServerUtilities ranks, tab completion doesn't.
     */
    private volatile boolean serverAllowed;
    /** CoFH Core's command is there for the player. */
    private volatile boolean cofh;
    private volatile long probeSentAt;
    private volatile boolean awaitingProbe;
    /** Which of {@link #PROBES} was asked last. */
    private volatile int probe;
    private long lastProbe;
    private static Field completionsField;

    private Teleport() {}

    /** Whether the player may use /tp (as last reported by the server). */
    public static boolean isAllowed() {
        return INSTANCE.allowed || INSTANCE.serverAllowed;
    }

    /** The server's word on /tp, rank or operator: from this mod on the server. */
    public static void setServerAllowed(boolean value) {
        INSTANCE.serverAllowed = value;
    }

    /** Teleports the player within the current dimension. */
    public static void teleport(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && isAllowed()) {
            mc.thePlayer.sendChatMessage("/tp " + x + " " + y + " " + z);
        }
    }

    /**
     * Whether the player may teleport into another dimension: an operator on a server with this mod, or with CoFH
     * Core's {@code /cofh tpx} (which needs the same rights as {@code /tp}).
     */
    public static boolean isOtherDimensionAllowed() {
        return ChunkLoadView.isAllowed() || INSTANCE.cofh && isAllowed();
    }

    /**
     * Teleports the player into another dimension (or this one). {@code y} 0 when the height there isn't known: the
     * server with this mod finds the ground; CoFH's command needs one, so the top of the explored map or sea level is
     * taken.
     */
    public static void teleport(int dimension, int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        if (dimension == mc.theWorld.provider.dimensionId && y > 0 && isAllowed()) {
            teleport(x, y, z);
        } else if (ChunkLoadView.isAllowed()) {
            ShareNetwork.sendToServer(new ShareNetwork.TeleportRequest(dimension, x, y, z));
        } else if (INSTANCE.cofh && isAllowed()) {
            int height = y > 0 ? y : 64;
            String name = mc.thePlayer.getCommandSenderName();
            mc.thePlayer.sendChatMessage("/cofh tpx " + name + " " + x + " " + height + " " + z + " " + dimension);
        }
    }

    /**
     * Height to teleport to at the column: the highest free spot, from the explored map or the loaded chunk.
     *
     * @return the y to stand on, or -1 if it is unknown
     */
    public static int findSafeY(World world, int x, int z) {
        int height = MapManager.INSTANCE.getSurfaceHeight(x, z);
        if (height > 0) {
            return height;
        }
        if (!world.provider.hasNoSky && !world.getChunkFromBlockCoords(x, z)
            .isEmpty()) {
            return world.getTopSolidOrLiquidBlock(x, z);
        }
        return -1;
    }

    /** How far below a roof (in blocks) a floor is looked for, and how thick a roof may be. */
    private static final int MAX_DROP = 32, MAX_ROOF = 3;

    /**
     * Where to stand for a block picked on the 3D map ({x, y, z, side} as {@code IsoMap#pick} gives it): {x, y, z,
     * roof}. A wall or a window: the floor of that storey in front of it. A roof: the floor under it (roof 1), so the
     * player lands inside the building rather than on top of it. Open ground: on top of it. Where the chunk isn't
     * loaded, right at the block seen.
     */
    public static int[] isoTarget(World world, int[] hit) {
        int x = hit[0], y = hit[1], z = hit[2], side = hit[3];
        if (side >= 2 && side <= 5) {
            // A side of a block: the cell in front of it, down to its floor.
            int fx = x + (side == 4 ? -1 : side == 5 ? 1 : 0);
            int fz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
            if (!isLoaded(world, fx, fz)) {
                return new int[] { fx, y, fz, 0 };
            }
            int floor = floorBelow(world, fx, Math.min(254, y), fz, MAX_DROP);
            return new int[] { fx, floor > 0 ? floor : y, fz, 0 };
        }
        int top = Math.min(255, y + 1);
        if (side != 1 || !isLoaded(world, x, z)) {
            return new int[] { x, top, z, 0 };
        }
        // The top of a block: a roof if open space with a floor is right under a few blocks of it.
        Block roof = world.getBlock(x, y, z);
        Material material = roof.getMaterial();
        // Natural ground and water are stood on (a cave or a lake bed under them is not a floor to go to).
        boolean ground = material.isLiquid() || material == Material.grass
            || material == Material.ground
            || material == Material.sand
            || material == Material.rock
            || material == Material.clay
            || material == Material.snow
            || material == Material.craftedSnow;
        if (!ground) {
            for (int below = y - 1; below >= Math.max(1, y - MAX_ROOF); below--) {
                if (!solid(world, x, below, z)) {
                    int floor = floorBelow(world, x, below - 1, z, MAX_DROP);
                    if (floor > 0) {
                        return new int[] { x, floor, z, 1 };
                    }
                    break;
                }
            }
        }
        return new int[] { x, top, z, 0 };
    }

    /** The feet height of the first spot going down from {@code fromY} with ground under it and room above; -1. */
    private static int floorBelow(World world, int x, int fromY, int z, int maxDrop) {
        for (int y = fromY; y >= Math.max(1, fromY - maxDrop); y--) {
            if (!solid(world, x, y, z) && !solid(world, x, y + 1, z) && solid(world, x, y - 1, z)) {
                return y;
            }
        }
        return -1;
    }

    private static boolean solid(World world, int x, int y, int z) {
        return world.getBlock(x, y, z)
            .getMaterial()
            .blocksMovement();
    }

    private static boolean isLoaded(World world, int x, int z) {
        return world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)
            && !world.getChunkFromBlockCoords(x, z)
                .isEmpty();
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        allowed = false;
        serverAllowed = false;
        cofh = false;
        probe = PROBE_COFH;
        awaitingProbe = false;
        lastProbe = 0;
        try {
            event.manager.channel()
                .pipeline()
                .addBefore("packet_handler", "wayfarmap:permission_probe", new ProbeHandler());
        } catch (Exception e) {
            WayFarMap.LOG.warn("Teleport permission check unavailable", e);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            allowed = false;
            serverAllowed = false;
            cofh = false;
            return;
        }
        long now = System.currentTimeMillis();
        // Every interval /tp, and a moment later /cofh.
        long wait = probe == PROBE_TP ? PROBE_GAP_MS : PROBE_INTERVAL_MS;
        if (now - lastProbe >= wait && !(mc.currentScreen instanceof GuiChat)) {
            lastProbe = now;
            probe = (probe + 1) % PROBES.length;
            probeSentAt = now;
            awaitingProbe = true;
            mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete(PROBES[probe]));
        }
    }

    private static String[] completions(S3APacketTabComplete packet) throws IllegalAccessException {
        if (completionsField == null) {
            for (Field field : S3APacketTabComplete.class.getDeclaredFields()) {
                if (field.getType() == String[].class) {
                    field.setAccessible(true);
                    completionsField = field;
                }
            }
        }
        return completionsField == null ? new String[0] : (String[]) completionsField.get(packet);
    }

    /** Reads the answer to our probe; runs on the network thread. */
    private final class ProbeHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S3APacketTabComplete && awaitingProbe
                && System.currentTimeMillis() - probeSentAt < PROBE_TIMEOUT_MS) {
                awaitingProbe = false;
                String command = PROBES[probe];
                boolean found = false;
                for (String completion : completions((S3APacketTabComplete) msg)) {
                    if (command.equals(completion) || command.substring(1)
                        .equals(completion)) {
                        found = true;
                    }
                }
                if (probe == PROBE_TP) {
                    allowed = found;
                } else {
                    cofh = found;
                }
                if (!(Minecraft.getMinecraft().currentScreen instanceof GuiChat)) {
                    // Our own question: don't let it reach the game.
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
