package WayFarMap.share;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server side of the AE2 wireless coverage layer: every couple of seconds, for each player whose client knows it, the
 * access points of the networks their wireless terminals are linked to ({@link Ae2Wireless}), sent when they changed.
 * <p>
 * The server is asked because the client can't know the range: AE2 sends the client only whether an access point has
 * power and a channel, not its boosters. Only access points in loaded chunks are part of a network, the same ones a
 * terminal can use. Works without ServerUtilities, unlike the team map.
 */
public final class WirelessServer {

    public static final WirelessServer INSTANCE = new WirelessServer();

    private static final int INTERVAL_TICKS = 40;

    private static Boolean active;

    /** Only where AE2 is installed; AE2 classes are touched only after this. */
    public static boolean isActive() {
        if (active == null) {
            active = Loader.isModLoaded("appliedenergistics2");
        }
        return active;
    }

    /** Hellos from the network thread. */
    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    private final Set<UUID> capable = new HashSet<>();
    /** What each player was last sent: {infinite, points}. */
    private final Map<UUID, Object[]> sent = new HashMap<>();
    private boolean broken;
    private int tick;

    private WirelessServer() {}

    public void stop() {
        inbox.clear();
        capable.clear();
        sent.clear();
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.Hello hello) {
        if (isActive() && player != null && hello.protocol >= ShareNetwork.WIRELESS_PROTOCOL) {
            inbox.add(new Object[] { player, hello });
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken) {
            return;
        }
        Object[] next;
        while ((next = inbox.poll()) != null) {
            EntityPlayerMP player = (EntityPlayerMP) next[0];
            if (TeamMapServer.isOnline(player)) {
                capable.add(player.getUniqueID());
                // A new session of the client: it gets the points even if they didn't change.
                sent.remove(player.getUniqueID());
                tick = INTERVAL_TICKS - 1;
            }
        }
        if (capable.isEmpty() || ++tick < INTERVAL_TICKS) {
            return;
        }
        tick = 0;
        Set<UUID> online = new HashSet<>();
        try {
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) o;
                UUID id = player.getUniqueID();
                online.add(id);
                if (capable.contains(id)) {
                    update(player);
                }
            }
        } catch (Throwable t) {
            // An AE2 version this doesn't fit: off for the session rather than failing every two seconds.
            broken = true;
            WayFarMap.LOG.error("AE2 wireless coverage failed and is turned off until restart", t);
        }
        capable.retainAll(online);
        sent.keySet()
            .retainAll(online);
    }

    private void update(EntityPlayerMP player) {
        boolean[] infinite = new boolean[1];
        List<WirelessPoint> points = Ae2Wireless.collect(player, infinite);
        Object[] last = sent.get(player.getUniqueID());
        if (last != null && (Boolean) last[0] == infinite[0] && last[1].equals(points)) {
            return;
        }
        sent.put(player.getUniqueID(), new Object[] { infinite[0], points });
        ShareNetwork.sendTo(new ShareNetwork.Wireless(infinite[0], points), player);
    }
}
