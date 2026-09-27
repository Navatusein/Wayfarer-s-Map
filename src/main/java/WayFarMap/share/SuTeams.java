package WayFarMap.share;

import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** ServerUtilities teams. Only touched when ServerUtilities is installed. */
final class SuTeams {

    private SuTeams() {}

    /** Id of the player's team (stable across restarts), or null without a team. */
    static String teamId(EntityPlayerMP player) {
        ForgeTeam team = team(player);
        return team == null ? null : team.getUIDCode();
    }

    /** Online members of the player's team, the player included; empty without a team. */
    static List<EntityPlayerMP> onlineTeammates(EntityPlayerMP player) {
        ForgeTeam team = team(player);
        return team == null ? Collections.emptyList() : team.getOnlineMembers();
    }

    private static ForgeTeam team(EntityPlayerMP player) {
        Universe universe = Universe.getNullable();
        if (universe == null) {
            return null;
        }
        ForgePlayer forgePlayer = universe.getPlayer(player);
        return forgePlayer != null && forgePlayer.hasTeam() && forgePlayer.team.isValid() ? forgePlayer.team : null;
    }
}
