package WayFarMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.common.MinecraftForge;

import WayFarMap.client.KeyHandler;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.Teleport;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.TeamMapClient;
import WayFarMap.client.waypoint.WaypointRenderer;
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

        MinecraftForge.EVENT_BUS.register(new MinimapRenderer());
        MinecraftForge.EVENT_BUS.register(new WaypointRenderer());
    }

    @Override
    public void receiveTeamMap(IMessage message) {
        TeamMapClient.INSTANCE.receive(message);
    }
}
