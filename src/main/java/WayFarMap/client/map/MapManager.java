package WayFarMap.client.map;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.share.ChunkRecord;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Keeps track of the map of the world the client is currently in: scans loaded chunks into map regions and saves
 * them to {@code .minecraft/wayfarmap/<world>/dim<id>/}. Besides the surface, caves are mapped in horizontal layers of
 * 16 blocks ({@code dim<id>/caves/<layer>/}), the one the player is in while cave mode is active.
 */
public class MapManager implements IResourceManagerReloadListener {

    public static final MapManager INSTANCE = new MapManager();

    /** Regions farther than this from the player are released when no fullscreen map is open. */
    private static final int KEEP_REGION_RADIUS = 2;
    /** Solid blocks above the head needed to count as underground (a one block roof doesn't). */
    private static final int UNDERGROUND_ROOF = 3;

    /** One thread, so writes of the same file never overlap. */
    private final ExecutorService saveExecutor = createExecutor("WayFarMap saver", 1);
    /** Decoding region images takes a while; two threads fill a zoomed out map faster. */
    private final ExecutorService loadExecutor = createExecutor("WayFarMap loader", 2);

    private WorldClient currentWorld;
    private File worldDirectory;
    private File dimensionDirectory;
    private MapDimension surface;
    private MapDimension biomes;
    private final Map<Integer, MapDimension> caveLayers = new HashMap<>();
    /** Cave layer shown and scanned, or -1 while the surface is shown. */
    private int activeCaveLayer = -1;
    /** Cave layer chosen on the world map, or -1 to follow the player. */
    private int caveLayerOverride = -1;
    private boolean underground;

    /**
     * Maps of dimensions the player isn't in, opened to look at them on the world map or to write teammates' chunks
     * into; one per dimension, so viewing and writing share the same regions.
     */
    private final Map<Integer, OtherDimension> others = new HashMap<>();
    /** The one of {@link #others} shown on the world map, or null to show the player's dimension. */
    private OtherDimension viewed;

    private final ScanTracker surfaceTracker = new ScanTracker();
    private final ScanTracker caveTracker = new ScanTracker();
    private int tick;
    private long lastAutosave;

    private MapManager() {}

    private static ExecutorService createExecutor(String name, int threads) {
        return Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Map to show right now: the biome map in biome view (biomes are per column, so also underground and in the
     * Nether), otherwise the active cave layer in cave mode or the surface. Null outside of a world.
     */
    public MapDimension getDimension() {
        if (Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            return biomes;
        }
        if (activeCaveLayer >= 0) {
            return getCaveLayer(activeCaveLayer);
        }
        return surface;
    }

    /** Biome map of the current dimension, or null outside of a world. */
    public MapDimension getBiomeMap() {
        return biomes;
    }

    /** Pins the cave view to a layer (0-15), or -1 to follow the player's height. */
    public void setCaveLayerOverride(int layer) {
        caveLayerOverride = layer < 0 ? -1 : Math.min(15, layer);
    }

    public int getCaveLayerOverride() {
        return caveLayerOverride;
    }

    /** Height to stand on at the column, from the explored surface map; 0 if unknown. */
    public int getSurfaceHeight(int x, int z) {
        MapRegion region = surface == null ? null
            : surface.getRegion(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT, false);
        return region == null ? 0 : region.getExtra(x & (MapRegion.SIZE - 1), z & (MapRegion.SIZE - 1));
    }

    /** Biome of the column from the explored biome map, or null if unknown. */
    public BiomeGenBase getBiome(int x, int z) {
        int id = biomes == null ? 0 : biomes.peekExtra(x, z);
        return id == 0 ? null : BiomeGenBase.getBiome(id - 1);
    }

    /** Cave layer being shown (blocks {@code layer * 16} to {@code layer * 16 + 15}), or -1 for the surface. */
    public int getActiveCaveLayer() {
        return activeCaveLayer;
    }

    // ---------------------------------------------------------------- saved dimensions on the world map

    /** A dimension with map data saved for this world or server. */
    public static final class SavedDimension {

        public final int id;
        public final String name;

        SavedDimension(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Maps of a dimension the player isn't in: never scanned, only looked at and written by the team map. */
    private final class OtherDimension {

        final int id;
        final File directory;
        final String name;
        final boolean noSky;
        final MapDimension surface;
        final MapDimension biomes;
        final Map<Integer, MapDimension> caves = new HashMap<>();

        OtherDimension(int id, File directory) {
            this.id = id;
            this.directory = directory;
            DimensionInfo info = readInfo(directory, id);
            this.name = info.name;
            this.noSky = info.noSky;
            surface = new MapDimension(id, directory, loadExecutor);
            biomes = new MapDimension(id, new File(directory, "biomes"), loadExecutor);
        }

        MapDimension cave(int layer) {
            MapDimension cave = caves.get(layer);
            if (cave == null) {
                cave = new MapDimension(
                    id,
                    new File(new File(directory, "caves"), String.valueOf(layer)),
                    loadExecutor);
                caves.put(layer, cave);
            }
            return cave;
        }

        List<MapDimension> all() {
            List<MapDimension> maps = new ArrayList<>();
            maps.add(surface);
            maps.add(biomes);
            maps.addAll(caves.values());
            return maps;
        }

    }

    /** Name and sky of a dimension, saved next to its map so it is known without being in it. */
    private static final class DimensionInfo {

        final String name;
        final boolean noSky;

        DimensionInfo(String name, boolean noSky) {
            this.name = name;
            this.noSky = noSky;
        }
    }

    private static final String INFO_FILE = "dimension.txt";

    private static void writeInfo(File directory, WorldProvider provider) {
        try {
            directory.mkdirs();
            String text = provider.getDimensionName() + "\n" + provider.hasNoSky + "\n";
            Files.write(new File(directory, INFO_FILE).toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not save the dimension name to {}", directory, e);
        }
    }

    private static DimensionInfo readInfo(File directory, int id) {
        try {
            List<String> lines = Files.readAllLines(new File(directory, INFO_FILE).toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0)
                .trim()
                .isEmpty()) {
                return new DimensionInfo(
                    lines.get(0)
                        .trim(),
                    lines.size() > 1 && Boolean.parseBoolean(
                        lines.get(1)
                            .trim()));
            }
        } catch (Exception e) {
            // Saved by an older version: ask Forge below.
        }
        String name = "DIM" + id;
        boolean noSky = id == -1;
        try {
            if (DimensionManager.isDimensionRegistered(id)) {
                WorldProvider provider = DimensionManager.createProviderFor(id);
                name = provider.getDimensionName();
                noSky |= provider instanceof WorldProviderHell;
            }
        } catch (Throwable t) {
            // Unknown provider on this client.
        }
        return new DimensionInfo(name, noSky);
    }

    /** Dimensions of this world or server that have a saved map, sorted by id; always has the current one. */
    public List<SavedDimension> listSavedDimensions() {
        List<SavedDimension> result = new ArrayList<>();
        if (worldDirectory == null || surface == null) {
            return result;
        }
        int current = surface.dimensionId;
        File[] files = worldDirectory.listFiles();
        List<Integer> ids = new ArrayList<>();
        ids.add(current);
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!file.isDirectory() || !name.matches("dim-?\\d+") || !hasMapData(file)) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(name.substring(3));
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (NumberFormatException e) {
                    // Not a dimension folder.
                }
            }
        }
        for (int id : others.keySet()) {
            // Written by the team map but not saved yet.
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        for (int id : ids) {
            String name = id == current && currentWorld != null ? currentWorld.provider.getDimensionName()
                : readInfo(new File(worldDirectory, "dim" + id), id).name;
            result.add(new SavedDimension(id, name));
        }
        return result;
    }

    /** True if the folder or its biome / cave subfolders hold a saved region. */
    private static boolean hasMapData(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (hasMapData(file)) {
                    return true;
                }
            } else if (file.getName()
                .endsWith(".png")) {
                    return true;
                }
        }
        return false;
    }

    /** Shows the saved map of another dimension on the world map; the player's own dimension stops viewing. */
    public void viewDimension(int id) {
        if (surface == null) {
            return;
        }
        if (id == surface.dimensionId) {
            stopViewing();
            return;
        }
        if (viewed != null && viewed.id == id) {
            return;
        }
        stopViewing();
        viewed = other(id);
    }

    /** The maps of another dimension, opened on first use. */
    private OtherDimension other(int id) {
        OtherDimension other = others.get(id);
        if (other == null) {
            other = new OtherDimension(id, new File(worldDirectory, "dim" + id));
            others.put(id, other);
        }
        return other;
    }

    /** Back to the dimension the player is in. */
    public void stopViewing() {
        if (viewed != null) {
            // Its regions are freed (changed ones once saved) with the others' by the next trim.
            for (MapDimension map : viewed.all()) {
                map.retain((x, z) -> false);
            }
            viewed = null;
            // Search overlays belong to the regions of the viewed maps.
            BiomeHighlight.clear();
        }
    }

    /** True while the world map shows a dimension other than the player's. */
    public boolean isViewingOtherDimension() {
        return viewed != null;
    }

    /** Starts saving every changed region now; the futures finish when they are on disk. */
    public List<Future<?>> saveAll() {
        List<Future<?>> pending = new ArrayList<>();
        for (MapDimension map : allMaps()) {
            pending.addAll(map.save(saveExecutor));
        }
        Future<?> blocks = IsoMap.INSTANCE.save();
        if (blocks != null) {
            pending.add(blocks);
        }
        return pending;
    }

    /** This world's (or server's) map folder of the current account, or null outside of a world. */
    public File getWorldDirectory() {
        return worldDirectory;
    }

    /** Id of the dimension shown on the world map. */
    public int getViewedDimensionId() {
        return viewed != null ? viewed.id : surface != null ? surface.dimensionId : 0;
    }

    /** Name of any dimension: the player's own, one looked at, or from its saved map (or Forge). */
    public String getDimensionName(int id) {
        if (surface != null && id == surface.dimensionId && currentWorld != null) {
            return currentWorld.provider.getDimensionName();
        }
        OtherDimension other = others.get(id);
        if (other != null) {
            return other.name;
        }
        return worldDirectory == null ? "DIM" + id : readInfo(new File(worldDirectory, "dim" + id), id).name;
    }

    /** Name of the dimension shown on the world map. */
    public String getViewedDimensionName() {
        if (viewed != null) {
            return viewed.name;
        }
        return currentWorld != null ? currentWorld.provider.getDimensionName() : "";
    }

    /**
     * Cave layer shown on the world map, or -1 for the surface. For another dimension there is no player in it, so
     * "Auto" shows caves only where there is no sky (like the Nether), at the player's height or the slider's layer.
     */
    public int getViewCaveLayer() {
        if (viewed == null) {
            return activeCaveLayer;
        }
        boolean caves = Config.caveMode == Config.CAVES_ON || (Config.caveMode == Config.CAVES_AUTO && viewed.noSky);
        if (!caves) {
            return -1;
        }
        if (caveLayerOverride >= 0) {
            return caveLayerOverride;
        }
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        return player == null ? 4 : Math.max(0, Math.min(15, MathHelper.floor_double(player.boundingBox.minY) >> 4));
    }

    /** Map shown on the world map: like {@link #getDimension()}, but of the viewed dimension. */
    public MapDimension getViewMap() {
        if (viewed == null) {
            return getDimension();
        }
        if (Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            return viewed.biomes;
        }
        int layer = getViewCaveLayer();
        return layer >= 0 ? viewed.cave(layer) : viewed.surface;
    }

    /** Biome map of the dimension shown on the world map. */
    public MapDimension getViewBiomeMap() {
        return viewed != null ? viewed.biomes : biomes;
    }

    /** Biome at the column in the dimension shown on the world map, or null if unknown. */
    public BiomeGenBase getViewBiome(int x, int z) {
        MapDimension map = getViewBiomeMap();
        int id = map == null ? 0 : map.peekExtra(x, z);
        return id == 0 ? null : BiomeGenBase.getBiome(id - 1);
    }

    /** Height to stand on in the dimension shown on the world map, from its explored surface; 0 if unknown. */
    public int getViewSurfaceHeight(int x, int z) {
        MapDimension map = viewed != null ? viewed.surface : surface;
        MapRegion region = map == null ? null : map.getRegion(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT, false);
        return region == null ? 0 : region.getExtra(x & (MapRegion.SIZE - 1), z & (MapRegion.SIZE - 1));
    }

    /**
     * Writes a chunk a teammate mapped into this dimension's map. Returns false (nothing written) while its region is
     * still being read from disk; the caller tries again later.
     */
    public boolean applySharedChunk(int dimension, ChunkRecord record) {
        if (surface == null || worldDirectory == null) {
            return true;
        }
        MapDimension map, biomeMap;
        if (dimension == surface.dimensionId) {
            map = record.layer < 0 ? surface : getCaveLayer(record.layer);
            biomeMap = biomes;
        } else {
            // Another dimension: written to its map on disk, so it can be looked at without going there.
            OtherDimension other = other(dimension);
            map = record.layer < 0 ? other.surface : other.cave(record.layer);
            biomeMap = other.biomes;
        }
        if (record.layer >= 0 || record.biomes == null) {
            biomeMap = null;
        }
        int rx = record.chunkX >> (MapRegion.SHIFT - 4), rz = record.chunkZ >> (MapRegion.SHIFT - 4);
        if (map == null || !map.prepareRegion(rx, rz) | (biomeMap != null && !biomeMap.prepareRegion(rx, rz))) {
            return false;
        }
        MapRegion region = map.getRegion(rx, rz, true);
        int localX = record.chunkX & (MapRegion.CHUNKS - 1), localZ = record.chunkZ & (MapRegion.CHUNKS - 1);
        if (region.getChunkTime(localX, localZ) >= record.time) {
            // Ours is as new or newer: keep it.
            return true;
        }
        region.setChunkTime(localX, localZ, record.time, true);
        MapRegion biomeRegion = biomeMap != null ? biomeMap.getRegion(rx, rz, true) : null;
        int baseX = (record.chunkX * 16) & (MapRegion.SIZE - 1);
        int baseZ = (record.chunkZ * 16) & (MapRegion.SIZE - 1);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                int color = record.colors[i];
                if ((color >>> 24) == 0) {
                    // The teammate hasn't seen this column: keep ours.
                    continue;
                }
                if (record.layer < 0) {
                    region.setPixel(baseX + lx, baseZ + lz, color, record.extra[i] & 0xFF);
                } else {
                    region.setPixel(baseX + lx, baseZ + lz, color);
                }
                if (biomeRegion != null) {
                    int biomeId = record.biomes[i] & 0xFF;
                    BiomeGenBase biome = biomeId == 0 ? null : BiomeGenBase.getBiome(biomeId - 1);
                    if (biome != null) {
                        // Same light relief as ChunkScanner, from the heights within the chunk.
                        int height = record.extra[i] & 0xFF;
                        int north = lz > 0 ? record.extra[i - 16] & 0xFF : 0;
                        float relief = height == 0 || north == 0 ? 1f
                            : 1f + Math.max(-4, Math.min(4, height - north)) * 0.03f;
                        biomeRegion.setPixel(
                            baseX + lx,
                            baseZ + lz,
                            0xFF000000 | BlockColors.shade(ChunkScanner.biomeColor(biome), relief),
                            biomeId);
                    }
                }
            }
        }
        if (record.layer < 0) {
            IsoMap.INSTANCE.onFlatChunkChanged(dimension, record.chunkX, record.chunkZ);
        }
        return true;
    }

    private MapDimension getCaveLayer(int layer) {
        MapDimension cave = caveLayers.get(layer);
        if (cave == null && dimensionDirectory != null) {
            File directory = new File(new File(dimensionDirectory, "caves"), String.valueOf(layer));
            cave = new MapDimension(surface.dimensionId, directory, loadExecutor);
            caveLayers.put(layer, cave);
        }
        return cave;
    }

    private List<MapDimension> allMaps() {
        List<MapDimension> maps = new ArrayList<>();
        if (surface != null) {
            maps.add(surface);
        }
        if (biomes != null) {
            maps.add(biomes);
        }
        maps.addAll(caveLayers.values());
        for (OtherDimension other : others.values()) {
            maps.addAll(other.all());
        }
        return maps;
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        BlockColors.clearCache();
        IsoMap.INSTANCE.onResourcesReloaded();
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
        if (world == null || mc.thePlayer == null || surface == null) {
            return;
        }

        tick++;
        IsoMap.INSTANCE.tick();
        updateCaveMode(world, mc.thePlayer);

        int budget = Config.chunksScannedPerTick;
        if (activeCaveLayer >= 0) {
            caveTracker.scan(mc, world, mc.thePlayer, getCaveLayer(activeCaveLayer), activeCaveLayer, null, budget);
            // The surface rarely changes while the player is underground.
            surfaceTracker.scan(mc, world, mc.thePlayer, surface, -1, biomes, Math.max(1, budget / 4));
        } else {
            surfaceTracker.scan(mc, world, mc.thePlayer, surface, -1, biomes, budget);
        }

        if (tick % 100 == 0 && !others.isEmpty()) {
            // Other dimensions filled by the team map: saved often and freed once saved (the one being viewed
            // is trimmed by the world map instead), so catching up on many dimensions doesn't fill the memory.
            for (OtherDimension other : others.values()) {
                for (MapDimension map : other.all()) {
                    map.save(saveExecutor);
                    if (other != viewed) {
                        map.retain((x, z) -> false);
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastAutosave >= Config.autosaveIntervalSeconds * 1000L) {
            lastAutosave = now;
            for (MapDimension map : allMaps()) {
                map.save(saveExecutor);
            }
            IsoMap.INSTANCE.save();
            if (!(mc.currentScreen instanceof GuiWorldMap)) {
                trimAroundPlayer(mc.thePlayer);
            }
        }
    }

    private void updateCaveMode(WorldClient world, EntityPlayer player) {
        if (tick % 10 == 0) {
            underground = isUnderground(world, player);
        }
        boolean caves = Config.caveMode == Config.CAVES_ON || (Config.caveMode == Config.CAVES_AUTO && underground);
        int playerLayer = Math.max(0, Math.min(15, MathHelper.floor_double(player.boundingBox.minY) >> 4));
        int layer = !caves ? -1 : caveLayerOverride >= 0 ? caveLayerOverride : playerLayer;
        if (layer != activeCaveLayer) {
            activeCaveLayer = layer;
            caveTracker.reset();
        }
    }

    /** In the Nether, or with a few solid blocks above the head (a cave, not a house or a forest). */
    private static boolean isUnderground(WorldClient world, EntityPlayer player) {
        if (world.provider.hasNoSky) {
            return true;
        }
        int x = MathHelper.floor_double(player.posX);
        int z = MathHelper.floor_double(player.posZ);
        int y = MathHelper.floor_double(player.boundingBox.minY) + 2;
        int top = Math.min(255, world.getHeightValue(x, z));
        int solid = 0;
        for (int yy = y; yy <= top && solid < UNDERGROUND_ROOF; yy++) {
            Block block = world.getBlock(x, yy, z);
            if (block.isOpaqueCube()) {
                solid++;
            }
        }
        return solid >= UNDERGROUND_ROOF;
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == currentWorld && currentWorld != null) {
            close();
        }
    }

    /** Frees memory used by regions far from the player (e.g. after browsing the fullscreen map). */
    public void trimAroundPlayer(EntityPlayer player) {
        if (surface == null || player == null) {
            return;
        }
        int rx = MathHelper.floor_double(player.posX) >> MapRegion.SHIFT;
        int rz = MathHelper.floor_double(player.posZ) >> MapRegion.SHIFT;
        for (MapDimension map : allMaps()) {
            map.trim(rx, rz, KEEP_REGION_RADIUS);
        }
    }

    /**
     * While the world map is open: frees everything outside the regions it shows (in region coordinates, inclusive)
     * except around the player. Without it, scrolling over a big explored area zoomed out would keep every region
     * passed on the way in memory.
     */
    public void trimForView(EntityPlayer player, int minRx, int minRz, int maxRx, int maxRz) {
        if (surface == null || player == null) {
            return;
        }
        int prx = MathHelper.floor_double(player.posX) >> MapRegion.SHIFT;
        int prz = MathHelper.floor_double(player.posZ) >> MapRegion.SHIFT;
        MapDimension.RegionFilter keep = (rx, rz) -> (rx >= minRx && rx <= maxRx && rz >= minRz && rz <= maxRz)
            || (Math.abs(rx - prx) <= KEEP_REGION_RADIUS && Math.abs(rz - prz) <= KEEP_REGION_RADIUS);
        for (MapDimension map : allMaps()) {
            map.retain(keep);
        }
    }

    private void open(Minecraft mc, WorldClient world) {
        currentWorld = world;
        int dimensionId = world.provider.dimensionId;
        worldDirectory = accountDirectory(mc, new File(new File(mc.mcDataDir, "wayfarmap"), getWorldFolder(mc)));
        dimensionDirectory = new File(worldDirectory, "dim" + dimensionId);
        // Name and sky saved so the world map can show this dimension from elsewhere.
        writeInfo(dimensionDirectory, world.provider);
        WaypointManager.INSTANCE.load(worldDirectory);
        surface = new MapDimension(dimensionId, dimensionDirectory, loadExecutor);
        biomes = new MapDimension(dimensionId, new File(dimensionDirectory, "biomes"), loadExecutor);
        lastAutosave = System.currentTimeMillis();
        IsoMap.INSTANCE.open(worldDirectory);
        WayFarMap.LOG.info("Map data for dimension {} is stored in {}", dimensionId, dimensionDirectory);
    }

    private void close() {
        List<Future<?>> pending = new ArrayList<>();
        for (MapDimension map : allMaps()) {
            pending.addAll(map.save(saveExecutor));
        }
        // Wait so the data is on disk even if the game exits right after leaving the world.
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (Exception e) {
                WayFarMap.LOG.warn("Error while saving the map", e);
            }
        }
        for (MapDimension map : allMaps()) {
            map.deleteTextures();
        }
        // Saves the blocks of the 3D map and waits for it too.
        IsoMap.INSTANCE.close();
        BiomeHighlight.clear();
        viewed = null;
        others.clear();
        surface = null;
        biomes = null;
        caveLayers.clear();
        caveLayerOverride = -1;
        dimensionDirectory = null;
        worldDirectory = null;
        activeCaveLayer = -1;
        underground = false;
        currentWorld = null;
        surfaceTracker.reset();
        caveTracker.reset();
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

    private static final String ACCOUNT_PREFIX = "player-";

    /**
     * Each account has its own map and waypoints: several accounts playing from the same game folder (two
     * players on one computer, or one launcher instance) must not see what the other explored. Maps saved before
     * this, directly in the world folder, go to the first account that plays there.
     */
    private static File accountDirectory(Minecraft mc, File worldFolder) {
        String id = mc.getSession()
            .getPlayerID();
        if (id == null || id.isEmpty()) {
            id = mc.getSession()
                .getUsername();
        }
        File account = new File(worldFolder, ACCOUNT_PREFIX + sanitize(id));
        if (!account.exists()) {
            File[] legacy = worldFolder.listFiles(
                file -> !file.getName()
                    .startsWith(ACCOUNT_PREFIX));
            if (legacy != null && legacy.length > 0 && account.mkdirs()) {
                for (File file : legacy) {
                    if (!file.renameTo(new File(account, file.getName()))) {
                        WayFarMap.LOG.warn("Could not move {} into {}", file, account);
                    }
                }
                WayFarMap.LOG.info("Moved the map saved before per-account maps to {}", account);
            }
        }
        return account;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Decides which loaded chunks to (re)scan into one map, nearest and never scanned first. */
    private final class ScanTracker {

        private final Map<Long, Integer> lastScanTick = new HashMap<>();
        private final ArrayDeque<Long> queue = new ArrayDeque<>();
        private int nextQueueBuild;

        void reset() {
            lastScanTick.clear();
            queue.clear();
            nextQueueBuild = 0;
        }

        /** @param caveLayer layer to scan, or -1 for the surface */
        void scan(Minecraft mc, WorldClient world, EntityPlayer player, MapDimension map, int caveLayer,
            MapDimension biomeMap, int budget) {
            if (queue.isEmpty() && tick >= nextQueueBuild) {
                buildQueue(mc, world, player);
                nextQueueBuild = tick + 10;
            }
            while (budget > 0 && !queue.isEmpty()) {
                long key = queue.poll();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (!ChunkScanner.isChunkReady(world, cx, cz)) {
                    continue;
                }
                // Reading a region from disk takes tens of milliseconds: it is done in the background, and the
                // chunk is scanned on a later tick instead of freezing the game (both reads start right away).
                int rx = cx >> (MapRegion.SHIFT - 4), rz = cz >> (MapRegion.SHIFT - 4);
                if (!map.prepareRegion(rx, rz) | (biomeMap != null && !biomeMap.prepareRegion(rx, rz))) {
                    continue;
                }
                try {
                    Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                    ChunkScanner.scan(world, chunk, map, caveLayer, biomeMap);
                    if (caveLayer < 0) {
                        // The 3D map keeps the surface's blocks.
                        IsoMap.INSTANCE.onChunkScanned(world, chunk);
                    }
                    MapRegion scanned = map.getLoadedRegion(rx, rz);
                    if (scanned != null) {
                        scanned.setChunkTime(
                            cx & (MapRegion.CHUNKS - 1),
                            cz & (MapRegion.CHUNKS - 1),
                            System.currentTimeMillis());
                    }
                    TeamMapClient.INSTANCE.onChunkScanned(map, biomeMap, caveLayer, cx, cz);
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Failed to map chunk " + cx + ", " + cz, e);
                }
                lastScanTick.put(key, tick);
                budget--;
            }
        }

        private void buildQueue(Minecraft mc, WorldClient world, EntityPlayer player) {
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
                queue.add(candidate[1]);
            }
        }
    }
}
