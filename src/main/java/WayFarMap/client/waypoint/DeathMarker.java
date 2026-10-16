package WayFarMap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.MathHelper;

import WayFarMap.Config;
import WayFarMap.client.Lang;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Places a waypoint where the player dies. */
public class DeathMarker {

    public static final DeathMarker INSTANCE = new DeathMarker();

    private static final int COLOR = 0xE03030;

    /** A marker was placed for the current death; cleared once the player is alive again. */
    private boolean marked;

    /** How far (in blocks, each way) from where the player died OpenBlocks may put the grave. */
    private static final int GRAVE_RANGE = 16;

    private DeathMarker() {}

    /**
     * A grave of OpenBlocks was removed from the client's world at (x, y, z): if it was the player's own, their death
     * waypoint nearest to it (where they died, which the grave is put next to) is removed with it. Render thread.
     */
    public static void graveRemoved(int dimension, int x, int y, int z, String owner) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Config.deathWaypointsGrave || mc.thePlayer == null
            || owner == null
            || !owner.equals(mc.thePlayer.getCommandSenderName())
            || !WaypointManager.INSTANCE.isLoaded()) {
            return;
        }
        Waypoint nearest = null;
        long best = Long.MAX_VALUE;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            if (!waypoint.death || waypoint.isForeign() || waypoint.dimension != dimension) {
                continue;
            }
            long dx = waypoint.x - x, dz = waypoint.z - z;
            if (Math.abs(dx) > GRAVE_RANGE || Math.abs(dz) > GRAVE_RANGE) {
                continue;
            }
            // Height counts less: the grave is kept out of the void and bedrock, far above a death down there.
            long dy = waypoint.y - y;
            long distance = dx * dx + dz * dz + Math.min(dy * dy, GRAVE_RANGE * GRAVE_RANGE);
            if (distance < best) {
                best = distance;
                nearest = waypoint;
            }
        }
        if (nearest != null) {
            WaypointManager.INSTANCE.removeWaypoint(nearest);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        if (player.getHealth() > 0 && !player.isDead) {
            marked = false;
            return;
        }
        if (marked || !Config.deathWaypoints || !WaypointManager.INSTANCE.isLoaded()) {
            return;
        }
        marked = true;
        Waypoint waypoint = new Waypoint(
            Lang.format("wayfarmap.death.name"),
            MathHelper.floor_double(player.posX),
            Math.max(0, MathHelper.floor_double(player.posY)),
            MathHelper.floor_double(player.posZ),
            mc.theWorld.provider.dimensionId);
        waypoint.outlineColor = COLOR;
        waypoint.iconItem = "minecraft:skull";
        waypoint.diedAt = System.currentTimeMillis();
        WaypointManager.INSTANCE
            .addDeathWaypoint(
                waypoint,
                Lang.format("wayfarmap.death.group"),
                Lang.allTranslations("wayfarmap.death.group"),
                Config.deathWaypointsKeep);
    }
}
