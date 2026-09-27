package WayFarMap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import WayFarMap.share.ShareNetwork;

/**
 * The player's online teammates, as the server reports them twice a second (servers with this mod only). Their
 * positions are smoothed between reports; a teammate the client can see uses the exact position.
 */
public final class TeamMates {

    public static final TeamMates INSTANCE = new TeamMates();

    /** Time between the server's reports, over which positions are smoothed. */
    private static final long REPORT_MS = 500;

    /** A teammate: the last two reported positions, blended by time. */
    public static final class Mate {

        public final UUID id;
        public String name;
        public int dimension;
        double x, y, z, prevX, prevY, prevZ;
        public float yaw;
        long updated;

        Mate(UUID id) {
            this.id = id;
        }
    }

    private Map<UUID, Mate> mates = new LinkedHashMap<>();

    private TeamMates() {}

    /** Game thread: a new report from the server. */
    public void update(List<ShareNetwork.Teammates.Mate> reported) {
        Map<UUID, Mate> next = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (ShareNetwork.Teammates.Mate entry : reported) {
            Mate mate = mates.get(entry.id);
            boolean jumped = mate == null || mate.dimension != entry.dimension;
            if (mate == null) {
                mate = new Mate(entry.id);
            }
            // Blend from where it is shown now; a teammate who just appeared or changed dimension starts in place.
            double[] shown = jumped ? null : blended(mate, now);
            mate.prevX = shown != null ? shown[0] : entry.x;
            mate.prevY = shown != null ? shown[1] : entry.y;
            mate.prevZ = shown != null ? shown[2] : entry.z;
            mate.x = entry.x;
            mate.y = entry.y;
            mate.z = entry.z;
            mate.name = entry.name;
            mate.dimension = entry.dimension;
            mate.yaw = entry.yaw;
            mate.updated = now;
            next.put(entry.id, mate);
        }
        mates = next;
    }

    public void clear() {
        mates = new LinkedHashMap<>();
    }

    /** Online teammates, in the order the server lists them. */
    public List<Mate> all() {
        return mates.isEmpty() ? Collections.emptyList() : new ArrayList<>(mates.values());
    }

    public boolean isTeammate(UUID id) {
        return mates.containsKey(id);
    }

    /**
     * Where to draw the teammate: the entity's own position if the client has it (same dimension, nearby),
     * otherwise the reported one, smoothed.
     */
    public double[] position(Mate mate, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.theWorld.provider.dimensionId == mate.dimension) {
            EntityPlayer entity = mc.theWorld.func_152378_a(mate.id); // getPlayerEntityByUUID
            if (entity != null) {
                return new double[] { entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks,
                    entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks,
                    entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks };
            }
        }
        return blended(mate, System.currentTimeMillis());
    }

    private static double[] blended(Mate mate, long now) {
        double t = Math.max(0, Math.min(1, (now - mate.updated) / (double) REPORT_MS));
        return new double[] { mate.prevX + (mate.x - mate.prevX) * t, mate.prevY + (mate.y - mate.prevY) * t,
            mate.prevZ + (mate.z - mate.prevZ) * t };
    }
}
