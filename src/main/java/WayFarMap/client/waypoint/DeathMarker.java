package WayFarMap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import WayFarMap.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Places a waypoint where the player dies. */
public class DeathMarker {

    public static final DeathMarker INSTANCE = new DeathMarker();

    private static final int COLOR = 0xE03030;

    /** A marker was placed for the current death; cleared once the player is alive again. */
    private boolean marked;

    private DeathMarker() {}

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
            I18n.format("wayfarmap.death.name"),
            MathHelper.floor_double(player.posX),
            Math.max(0, MathHelper.floor_double(player.posY)),
            MathHelper.floor_double(player.posZ),
            mc.theWorld.provider.dimensionId);
        waypoint.outlineColor = COLOR;
        waypoint.iconItem = "minecraft:skull";
        WaypointManager.INSTANCE
            .addDeathWaypoint(waypoint, I18n.format("wayfarmap.death.group"), Config.deathWaypointsKeep);
    }
}
