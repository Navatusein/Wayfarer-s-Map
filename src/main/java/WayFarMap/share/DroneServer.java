package WayFarMap.share;

import java.util.ArrayList;
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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server side of the Drone Centre layer: every couple of seconds the drone centres in loaded chunks with their range,
 * sent to the players whose client knows the layer when the list changed. The same list for everyone: a centre has
 * no owner. Needed most by GregTech versions that don't tell the client the drone level.
 */
public final class DroneServer {

    public static final DroneServer INSTANCE = new DroneServer();

    private static final int INTERVAL_TICKS = 40;

    public static boolean isActive() {
        return DroneCentres.isAvailable();
    }

    private final Queue<EntityPlayerMP> inbox = new ConcurrentLinkedQueue<>();
    private final Set<UUID> capable = new HashSet<>();
    private final Map<UUID, List<MachinePoint>> sent = new HashMap<>();
    private boolean broken;
    private int tick;

    private DroneServer() {}

    public void stop() {
        inbox.clear();
        capable.clear();
        sent.clear();
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.Hello hello) {
        if (player != null && hello.protocol >= ShareNetwork.DRONES_PROTOCOL && isActive()) {
            inbox.add(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken) {
            return;
        }
        EntityPlayerMP joined;
        while ((joined = inbox.poll()) != null) {
            if (TeamMapServer.isOnline(joined)) {
                capable.add(joined.getUniqueID());
                sent.remove(joined.getUniqueID());
                tick = INTERVAL_TICKS - 1;
            }
        }
        if (capable.isEmpty() || ++tick < INTERVAL_TICKS) {
            return;
        }
        tick = 0;
        Set<UUID> online = new HashSet<>();
        try {
            List<MachinePoint> points = findAll();
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) o;
                online.add(player.getUniqueID());
                if (capable.contains(player.getUniqueID()) && !points.equals(sent.get(player.getUniqueID()))) {
                    sent.put(player.getUniqueID(), points);
                    ShareNetwork.sendTo(new ShareNetwork.DroneList(points), player);
                }
            }
        } catch (Throwable t) {
            broken = true;
            WayFarMap.LOG.error("The Drone Centre layer failed and is turned off until restart", t);
        }
        capable.retainAll(online);
        sent.keySet()
            .retainAll(online);
    }

    private static List<MachinePoint> findAll() {
        List<MachinePoint> points = new ArrayList<>();
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (Object o : world.loadedTileEntityList) {
                TileEntity tile = (TileEntity) o;
                Object centre = tile.isInvalid() ? null : DroneCentres.centre(tile);
                if (centre != null) {
                    points.add(
                        new MachinePoint(
                            DroneCentres.KIND,
                            world.provider.dimensionId,
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            DroneCentres.level(centre),
                            0,
                            DroneCentres.range(centre),
                            DroneCentres.isActive(tile)));
                }
            }
        }
        return points;
    }
}
