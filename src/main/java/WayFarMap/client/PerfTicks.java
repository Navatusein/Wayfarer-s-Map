package WayFarMap.client;

import WayFarMap.Perf;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Times the game's frames, client ticks and (in single player) server ticks for {@link Perf}: from the first handler
 * of each to the last, so the whole of it is in, the mod's share and everything else.
 */
public final class PerfTicks {

    public static final PerfTicks INSTANCE = new PerfTicks();

    private PerfTicks() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderStart(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Perf.frame();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Perf.tickStart();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Perf.tickEnd();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Perf.serverTickStart();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Perf.serverTickEnd();
        }
    }
}
