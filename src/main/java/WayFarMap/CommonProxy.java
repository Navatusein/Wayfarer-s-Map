package WayFarMap;

import WayFarMap.share.ChunkLoadServer;
import WayFarMap.share.ShareNetwork;
import WayFarMap.share.TeamMapServer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

public class CommonProxy {

    // The map itself is client-side. On a server (dedicated or the integrated one) the mod shares maps between the
    // members of a ServerUtilities team.
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        WayFarMap.LOG.info("WayFarMap version " + Tags.VERSION);
        ShareNetwork.register();
    }

    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance()
            .bus()
            .register(ChunkLoadServer.INSTANCE);
        if (TeamMapServer.isActive()) {
            FMLCommonHandler.instance()
                .bus()
                .register(TeamMapServer.INSTANCE);
        }
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new ChunkLoadServer.Command());
        ChunkLoadServer.INSTANCE.start();
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        ChunkLoadServer.INSTANCE.stop();
        if (TeamMapServer.isActive()) {
            TeamMapServer.INSTANCE.stop();
        }
    }

    /** A team map message for the client; only the client proxy handles it. */
    public void receiveTeamMap(IMessage message) {}

    /** A batch of {@code /wf chunkload} for the client; only the client proxy handles it. */
    public void receiveChunkLoad(IMessage message) {}
}
