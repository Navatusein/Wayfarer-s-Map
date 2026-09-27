package WayFarMap.client.map;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.event.world.WorldEvent;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.GuiWorldMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Keeps track of the map of the world the client is currently in: scans loaded chunks into map regions and saves
 * them to {@code .minecraft/wayfarmap/<world>/dim<id>/}.
 */
public class MapManager implements IResourceManagerReloadListener {

    public static final MapManager INSTANCE = new MapManager();

    /** Regions farther than this from the player are released when no fullscreen map is open. */
    private static final int KEEP_REGION_RADIUS = 2;

    private final ExecutorService saveExecutor = createExecutor("WayFarMap saver");
    private final ExecutorService loadExecutor = createExecutor("WayFarMap loader");

    private WorldClient currentWorld;
    private MapDimension dimension;

    private final Map<Long, Integer> lastScanTick = new HashMap<>();
    private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    private int tick;
    private int nextQueueBuild;
    private long lastAutosave;

    private MapManager() {}

    private static ExecutorService createExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Map of the dimension the player is in, or null outside of a world. */
    public MapDimension getDimension() {
        return dimension;
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        BlockColors.clearCache();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world != currentWorld) {
            close();
            if (world != null) {
                open(mc, world);
            }
        }
        if (world == null || mc.thePlayer == null || dimension == null) {
            return;
        }

        tick++;
        scanChunks(mc, world, mc.thePlayer);

        long now = System.currentTimeMillis();
        if (now - lastAutosave >= Config.autosaveIntervalSeconds * 1000L) {
            lastAutosave = now;
            dimension.save(saveExecutor);
            if (!(mc.currentScreen instanceof GuiWorldMap)) {
                trimAroundPlayer(mc.thePlayer);
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == currentWorld && currentWorld != null) {
            close();
        }
    }

    /** Frees memory used by regions far from the player (e.g. after browsing the fullscreen map). */
    public void trimAroundPlayer(EntityPlayer player) {
        if (dimension == null || player == null) {
            return;
        }
        int rx = MathHelper.floor_double(player.posX) >> MapRegion.SHIFT;
        int rz = MathHelper.floor_double(player.posZ) >> MapRegion.SHIFT;
        dimension.trim(rx, rz, KEEP_REGION_RADIUS);
    }

    private void open(Minecraft mc, WorldClient world) {
        currentWorld = world;
        int dimensionId = world.provider.dimensionId;
        File directory = new File(new File(new File(mc.mcDataDir, "wayfarmap"), getWorldFolder(mc)), "dim" + dimensionId);
        dimension = new MapDimension(dimensionId, directory, loadExecutor);
        lastAutosave = System.currentTimeMillis();
        WayFarMap.LOG.info("Map data for dimension {} is stored in {}", dimensionId, directory);
    }

    private void close() {
        if (dimension != null) {
            List<Future<?>> pending = dimension.save(saveExecutor);
            // Wait so the data is on disk even if the game exits right after leaving the world.
            for (Future<?> future : pending) {
                try {
                    future.get();
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Error while saving the map", e);
                }
            }
            dimension.deleteTextures();
        }
        dimension = null;
        currentWorld = null;
        lastScanTick.clear();
        scanQueue.clear();
        nextQueueBuild = 0;
        tick = 0;
    }

    private static String getWorldFolder(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        if (mc.isSingleplayer() && server != null) {
            return "singleplayer" + File.separator + sanitize(server.getFolderName());
        }
        ServerData serverData = mc.func_147104_D(); // getCurrentServerData
        String address = serverData != null && serverData.serverIP != null ? serverData.serverIP : "unknown";
        return "multiplayer" + File.separator + sanitize(address);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void scanChunks(Minecraft mc, WorldClient world, EntityPlayer player) {
        if (scanQueue.isEmpty() && tick >= nextQueueBuild) {
            buildScanQueue(mc, world, player);
            nextQueueBuild = tick + 10;
        }
        int budget = Config.chunksScannedPerTick;
        while (budget > 0 && !scanQueue.isEmpty()) {
            long key = scanQueue.poll();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (!ChunkScanner.isChunkReady(world, cx, cz)) {
                continue;
            }
            try {
                ChunkScanner.scan(world, world.getChunkFromChunkCoords(cx, cz), dimension);
            } catch (Exception e) {
                WayFarMap.LOG.warn("Failed to map chunk " + cx + ", " + cz, e);
            }
            lastScanTick.put(key, tick);
            budget--;
        }
    }

    private void buildScanQueue(Minecraft mc, WorldClient world, EntityPlayer player) {
        int pcx = MathHelper.floor_double(player.posX) >> 4;
        int pcz = MathHelper.floor_double(player.posZ) >> 4;
        int radius = mc.gameSettings.renderDistanceChunks + 1;

        // Forget chunks that are out of range; they get rescanned when the player comes back.
        Iterator<Long> it = lastScanTick.keySet()
            .iterator();
        while (it.hasNext()) {
            long key = it.next();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            if (Math.abs(cx - pcx) > radius + 1 || Math.abs(cz - pcz) > radius + 1) {
                it.remove();
            }
        }

        List<long[]> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = chunkKey(cx, cz);
                Integer last = lastScanTick.get(key);
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (last != null) {
                    // Keep the area around the player up to date; far chunks rarely change.
                    int interval = distance <= 1 ? 20 : distance <= 4 ? 100 : 600;
                    if (tick - last < interval) {
                        continue;
                    }
                }
                if (!ChunkScanner.isChunkReady(world, cx, cz)) {
                    continue;
                }
                long priority = (last == null ? 0 : 1000) + distance;
                candidates.add(new long[] { priority, key });
            }
        }
        Collections.sort(candidates, (a, b) -> Long.compare(a[0], b[0]));
        for (long[] candidate : candidates) {
            scanQueue.add(candidate[1]);
        }
    }
}
