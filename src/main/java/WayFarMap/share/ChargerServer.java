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
 * Server side of the wireless chargers layer: every couple of seconds, the chargers in loaded chunks that would charge
 * each player whose client knows the layer, with the range of their real mode, sent when they changed. Only the
 * server knows a GregTech charger's mode and owner.
 */
public final class ChargerServer {

    public static final ChargerServer INSTANCE = new ChargerServer();

    private static final int INTERVAL_TICKS = 40;

    public static boolean isActive() {
        return Chargers.isGtAvailable() || Chargers.isEnderIoAvailable();
    }

    private final Queue<EntityPlayerMP> inbox = new ConcurrentLinkedQueue<>();
    private final Set<UUID> capable = new HashSet<>();
    private final Map<UUID, List<ChargerPoint>> sent = new HashMap<>();
    private boolean broken;
    private int tick;

    private ChargerServer() {}

    public void stop() {
        inbox.clear();
        capable.clear();
        sent.clear();
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.Hello hello) {
        if (player != null && hello.protocol >= ShareNetwork.CHARGERS_PROTOCOL && isActive()) {
            inbox.add(player);
        }
    }

    /** A charger found this round, before it is matched with the players. */
    private static final class Found {

        final TileEntity tile;
        final Object gtCharger;
        final ChargerPoint point;

        Found(TileEntity tile, Object gtCharger, ChargerPoint point) {
            this.tile = tile;
            this.gtCharger = gtCharger;
            this.point = point;
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
            List<Found> found = findAll();
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) o;
                online.add(player.getUniqueID());
                if (capable.contains(player.getUniqueID())) {
                    update(player, found);
                }
            }
        } catch (Throwable t) {
            broken = true;
            WayFarMap.LOG.error("The wireless chargers layer failed and is turned off until restart", t);
        }
        capable.retainAll(online);
        sent.keySet()
            .retainAll(online);
    }

    private static List<Found> findAll() {
        List<Found> found = new ArrayList<>();
        double enderIoRange = Chargers.isEnderIoAvailable() ? Chargers.enderIoRange() : 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            int dimension = world.provider.dimensionId;
            for (Object o : world.loadedTileEntityList) {
                TileEntity tile = (TileEntity) o;
                if (tile.isInvalid()) {
                    continue;
                }
                Object gt = Chargers.gtCharger(tile);
                if (gt != null) {
                    int tier = Chargers.gtTier(gt);
                    int mode = Chargers.gtMode(gt);
                    double range = mode == Chargers.MODE_UNKNOWN ? Chargers.gtMinRange(tier)
                        : Chargers.gtRange(tier, mode);
                    boolean active = Chargers.gtAllowedToWork(tile) && Chargers.gtHasEnergy(tile);
                    found.add(
                        new Found(
                            tile,
                            gt,
                            new ChargerPoint(
                                Chargers.KIND_GT,
                                dimension,
                                tile.xCoord,
                                tile.yCoord,
                                tile.zCoord,
                                tier,
                                mode,
                                (float) range,
                                active)));
                } else if (Chargers.isEnderIoCharger(tile)) {
                    found.add(
                        new Found(
                            tile,
                            null,
                            new ChargerPoint(
                                Chargers.KIND_ENDERIO,
                                dimension,
                                tile.xCoord,
                                tile.yCoord,
                                tile.zCoord,
                                0,
                                Chargers.MODE_UNKNOWN,
                                (float) enderIoRange,
                                Chargers.enderIoActive(tile))));
                }
            }
        }
        return found;
    }

    private void update(EntityPlayerMP player, List<Found> found) {
        List<ChargerPoint> points = new ArrayList<>();
        for (Found f : found) {
            if (f.gtCharger == null || Chargers.gtChargesPlayer(f.tile, f.gtCharger, player)) {
                points.add(f.point);
            }
        }
        List<ChargerPoint> last = sent.get(player.getUniqueID());
        if (points.equals(last)) {
            return;
        }
        sent.put(player.getUniqueID(), points);
        ShareNetwork.sendTo(new ShareNetwork.ChargerList(points), player);
    }
}
