package WayFarMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import WayFarMap.client.InspectCommand;
import WayFarMap.client.IsoEntityDrawer;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.MobIconDump;
import WayFarMap.client.PerfTicks;
import WayFarMap.client.PlayerTrail;
import WayFarMap.client.Teleport;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.map.ChunkLoadClient;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.TeamMapClient;
import WayFarMap.client.waypoint.DeathMarker;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        KeyHandler keyHandler = new KeyHandler();
        keyHandler.register();
        FMLCommonHandler.instance()
            .bus()
            .register(keyHandler);

        MapManager manager = MapManager.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(manager);
        MinecraftForge.EVENT_BUS.register(manager);
        IResourceManager resourceManager = Minecraft.getMinecraft()
            .getResourceManager();
        if (resourceManager instanceof IReloadableResourceManager) {
            // Texture packs change block colors.
            ((IReloadableResourceManager) resourceManager).registerReloadListener(manager);
        }

        FMLCommonHandler.instance()
            .bus()
            .register(Teleport.INSTANCE);

        if (Mods.isClaimsAvailable()) {
            ClaimsLayer.register();
        }

        FMLCommonHandler.instance()
            .bus()
            .register(TeamMapClient.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(ChunkLoadClient.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(DeathMarker.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(PlayerTrail.INSTANCE);
        // Times frames and ticks for the 3D log, to tell what costs frames per second and ticks per second.
        FMLCommonHandler.instance()
            .bus()
            .register(PerfTicks.INSTANCE);

        // Waypoints shared in the chat: shown with an [Add] button that runs a client-side command.
        MinecraftForge.EVENT_BUS.register(WaypointShare.INSTANCE);
        ClientCommandHandler.instance.registerCommand(new WaypointShare.AddCommand());
        // Report of how a block gets onto the 3D map, to send when it looks wrong there.
        ClientCommandHandler.instance.registerCommand(new InspectCommand());
        // Material to make the map's mob icons from: every mob drawn by the game, its skin and model.
        ClientCommandHandler.instance.registerCommand(new MobIconDump());

        MinecraftForge.EVENT_BUS.register(new IsoEntityDrawer.NameTags());
        MinecraftForge.EVENT_BUS.register(new MinimapRenderer());
        MinecraftForge.EVENT_BUS.register(new WaypointRenderer());
    }

    @Override
    public void receiveTeamMap(IMessage message) {
        TeamMapClient.INSTANCE.receive(message);
    }

    @Override
    public void receiveChunkLoad(IMessage message) {
        ChunkLoadClient.INSTANCE.receive(message);
    }
}
