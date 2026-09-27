package WayFarMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.common.MinecraftForge;

import WayFarMap.client.KeyHandler;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.map.MapManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

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

        MinecraftForge.EVENT_BUS.register(new MinimapRenderer());
    }
}
