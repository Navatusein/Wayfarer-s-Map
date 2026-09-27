package WayFarMap.client;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.network.play.client.C14PacketTabComplete;
import net.minecraft.network.play.server.S3APacketTabComplete;
import net.minecraft.world.World;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.MapManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Teleporting with the vanilla {@code /tp x y z} command, offered only when the player may use it.
 * <p>
 * The client isn't told its permissions, so it asks the server to tab-complete {@code /tp}: servers (and permission
 * plugins) only complete commands the player is allowed to run. The answer is read from the network pipeline before
 * the game would hand it to the chat screen.
 */
public final class Teleport {

    public static final Teleport INSTANCE = new Teleport();

    private static final long PROBE_INTERVAL_MS = 30_000;
    private static final long PROBE_TIMEOUT_MS = 5_000;

    private volatile boolean allowed;
    private volatile long probeSentAt;
    private volatile boolean awaitingProbe;
    private long lastProbe;
    private static Field completionsField;

    private Teleport() {}

    /** Whether the player may use /tp (as last reported by the server). */
    public static boolean isAllowed() {
        return INSTANCE.allowed;
    }

    /** Teleports the player within the current dimension. */
    public static void teleport(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && isAllowed()) {
            mc.thePlayer.sendChatMessage("/tp " + x + " " + y + " " + z);
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

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        allowed = false;
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
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastProbe >= PROBE_INTERVAL_MS && !(mc.currentScreen instanceof GuiChat)) {
            lastProbe = now;
            probeSentAt = now;
            awaitingProbe = true;
            mc.thePlayer.sendQueue.addToSendQueue(new C14PacketTabComplete("/tp"));
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
                boolean found = false;
                for (String completion : completions((S3APacketTabComplete) msg)) {
                    if ("/tp".equals(completion) || "tp".equals(completion)) {
                        found = true;
                    }
                }
                allowed = found;
                if (!(Minecraft.getMinecraft().currentScreen instanceof GuiChat)) {
                    // Our own question: don't let it reach the game.
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
