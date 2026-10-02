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
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.map.export.MapExport;
import WayFarMap.client.map.iso.IsoLog;
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

    private final ScanTracker surfaceTracker = new ScanTracker(true);
    private final ScanTracker caveTracker = new ScanTracker(false);
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
        if (Config.mapDisplayMode == Config.DISPLAY_TOPO) {
            // The topography is drawn from the surface's heights.
            return surface;
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
            surface = new MapDimension(id, directory, loadExecutor).withPlantless();
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
            maps.add(surface.plantless());
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
            // Search overlays and the topography belong to the regions of the viewed maps.
            BiomeHighlight.clear();
            Topography.clear();
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

    /** Whether the dimension has no sky (like the Nether), for the player's own or the viewed one; else false. */
    public boolean hasNoSky(int dimensionId) {
        if (currentWorld != null && currentWorld.provider.dimensionId == dimensionId) {
            return currentWorld.provider.hasNoSky;
        }
        return viewed != null && viewed.id == dimensionId && viewed.noSky;
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
        if (surfaceView) {
            return -1;
        }
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

    /**
     * Map shown on the world map: like {@link #getDimension()}, but of the viewed dimension; the surface without grass
     * and flowers in that mode.
     */
    public MapDimension getViewMap() {
        MapDimension map = viewedMap();
        if (!Config.showPlants && map != null && map.plantless() != null) {
            return map.plantless();
        }
        return map;
    }

    private MapDimension viewedMap() {
        if (surfaceView) {
            return viewed == null ? surface : viewed.surface;
        }
        if (viewed == null) {
            return getDimension();
        }
        if (Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            return viewed.biomes;
        }
        if (Config.mapDisplayMode == Config.DISPLAY_TOPO) {
            return viewed.surface;
        }
        int layer = getViewCaveLayer();
        return layer >= 0 ? viewed.cave(layer) : viewed.surface;
    }

    /**
     * The world map shows the surface whatever the cave mode (its 3D view, its chunk and region loading views); not the
     * minimap.
     */
    private boolean surfaceView;

    public void setSurfaceView(boolean surfaceOnly) {
        surfaceView = surfaceOnly;
    }

    public boolean isSurfaceView() {
        return surfaceView;
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
            FlatLog.shared(record.chunkX, record.chunkZ, "waiting");
            return false;
        }
        MapRegion region = map.getRegion(rx, rz, true);
        // Teammates send the surface as they see it, with grass and flowers: the map without them gets it only where
        // it has nothing.
        MapRegion plantlessRegion = map.plantless() != null ? map.plantless()
            .getRegion(rx, rz, true) : null;
        int localX = record.chunkX & (MapRegion.CHUNKS - 1), localZ = record.chunkZ & (MapRegion.CHUNKS - 1);
        if (region.getChunkTime(localX, localZ) >= record.time) {
            // Ours is as new or newer: keep it.
            FlatLog.shared(record.chunkX, record.chunkZ, "ours newer");
            return true;
        }
        region.setChunkTime(localX, localZ, record.time, true);
        if (plantlessRegion != null) {
            plantlessRegion.setChunkTime(localX, localZ, record.time, true);
        }
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
                    if (plantlessRegion != null && (plantlessRegion.getPixel(baseX + lx, baseZ + lz) >>> 24) == 0) {
                        // Only where we have nothing: the team's colors have the plants in them, and the
                        // server's copy of our own chunks (newer by its time) would cover the plants up again.
                        plantlessRegion.setPixel(baseX + lx, baseZ + lz, color, record.extra[i] & 0xFF);
                    }
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
        FlatLog.shared(record.chunkX, record.chunkZ, "written");
        return true;
    }

    /**
     * What the chunk looks like from above, as one number (heights and top blocks), for the log: a scan or a
     * "changed" mark with the same signature can't change the map.
     */
    static long surfaceSignature(Chunk chunk) {
        long hash = 1125899906842597L;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = chunk.getHeightValue(x, z);
                hash = hash * 31 + y;
                if (y > 0) {
                    hash = hash * 31 + Block.getIdFromBlock(chunk.getBlock(x, y - 1, z));
                    hash = hash * 31 + chunk.getBlockMetadata(x, y - 1, z);
                }
            }
        }
        return hash == 0 ? 1 : hash;
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
            maps.add(surface.plantless());
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

    /** For the log: chunks within the view distance the client doesn't have (the server hasn't sent them yet). */
    private static int notLoadedInView(WorldClient world, EntityPlayer player, int radius) {
        int pcx = MathHelper.floor_double(player.posX) >> 4, pcz = MathHelper.floor_double(player.posZ) >> 4;
        int missing = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!ChunkScanner.isChunkReady(world, pcx + dx, pcz + dz)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /** A gap between two ticks past this is a hitch (a tick is 50 ms). */
    private static final long HITCH_NANOS = 150_000_000L;
    /** For the log: when the last tick's map work ended, and how long that took. */
    private long lastTickEnd, lastMapTick;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            clientTick();
        } finally {
            lastTickEnd = System.nanoTime();
        }
    }

    private void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world != currentWorld) {
            MapExport.cancel();
            close();
            if (world != null) {
                open(mc, world);
            }
        }
        if (world == null || mc.thePlayer == null || surface == null) {
            return;
        }

        tick++;
        // Time mapping chunks let go by the game since the last tick (they are let go while its packets are handled).
        long unloadsBefore = unloadNanos;
        unloadNanos = 0;
        long tickStart = System.nanoTime();
        if (FlatLog.on() && lastTickEnd != 0 && tickStart - lastTickEnd > HITCH_NANOS) {
            FlatLog.hitch(
                tickStart - lastTickEnd,
                lastMapTick,
                (mc.currentScreen instanceof GuiWorldMap ? "worldMapOpen" : "inGame")
                    + (Config.isometric ? " 3D" : " 2D")
                    + (ChunkLoadClient.INSTANCE.statusText() != null ? " chunkload" : ""));
        }
        long isoStart = System.nanoTime();
        IsoMap.INSTANCE.tick(world);
        long isoNanos = System.nanoTime() - isoStart;
        MapExport.tick();
        updateCaveMode(world, mc.thePlayer);

        int budget = Config.chunksScannedPerTick;
        if (activeCaveLayer >= 0) {
            caveTracker.scan(mc, world, mc.thePlayer, getCaveLayer(activeCaveLayer), activeCaveLayer, null, budget);
            // The surface rarely changes while the player is underground.
            surfaceTracker.scan(mc, world, mc.thePlayer, surface, -1, biomes, Math.max(1, budget / 4));
        } else {
            surfaceTracker.scan(mc, world, mc.thePlayer, surface, -1, biomes, budget);
        }
        lastMapTick = System.nanoTime() - tickStart + unloadsBefore;
        FlatLog.mapTick(lastMapTick, isoNanos, unloadsBefore);
        if (FlatLog.on()) {
            int regionCount = 0, lodCount = 0, textureCount = 0, pendingCount = 0;
            for (MapDimension map : allMaps()) {
                regionCount += map.regionCount();
                lodCount += map.lodCount();
                textureCount += map.textureCount();
                pendingCount += map.pendingCount();
            }
            FlatLog.stats(
                regionCount,
                lodCount,
                textureCount,
                pendingCount,
                surfaceTracker.queueSize() + caveTracker.queueSize(),
                surfaceTracker.settlingSize() + caveTracker.settlingSize(),
                mc.thePlayer.posX,
                mc.thePlayer.posZ,
                mc.gameSettings.renderDistanceChunks,
                notLoadedInView(world, mc.thePlayer, mc.gameSettings.renderDistanceChunks));
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
            long start = System.nanoTime();
            int queued = 0, maps = 0;
            for (MapDimension map : allMaps()) {
                queued += map.save(saveExecutor)
                    .size();
                maps++;
            }
            if (FlatLog.on()) {
                logSaveAll("AUTOSAVE", maps, queued, System.nanoTime() - start);
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

    /**
     * Maps a chunk sent for {@code /wf chunkload} (surface and biomes, shared with the team as any other). False if
     * it can't be yet (its regions are being read, or no world): tried again next tick.
     */
    public boolean scanForLoad(Chunk chunk, boolean with3d) {
        if (currentWorld == null || surface == null || chunk == null || chunk.isEmpty()) {
            return currentWorld == null || chunk == null || chunk.isEmpty();
        }
        return surfaceTracker.scanForLoad(currentWorld, chunk, surface, biomes, with3d);
    }

    /**
     * Maps a cave layer of a chunk sent for {@code /wf chunkload} (picked with Shift on the world map: every cave
     * layer is mapped). False if it can't be yet (the layer's region is being read): tried again next tick.
     */
    public boolean scanCaveForLoad(Chunk chunk, int layer) {
        if (currentWorld == null || surface == null || chunk == null || chunk.isEmpty()) {
            return currentWorld == null || chunk == null || chunk.isEmpty();
        }
        MapDimension cave = getCaveLayer(layer);
        return cave == null || caveTracker.scanCaveForLoad(currentWorld, chunk, cave, layer);
    }

    /** Cave layers worth mapping for a chunk: up to the one just above its highest blocks (higher show nothing). */
    public static int caveLayersOf(Chunk chunk) {
        return Math.min(16, Math.max(0, chunk.getTopFilledSegment() >> 4) + 2);
    }

    /** For the log: when the game loaded each chunk. */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        Chunk chunk = event.getChunk();
        if (event.world != null && event.world.isRemote && event.world == currentWorld && chunk != null) {
            // A chunk the scanner already mapped is loaded again: let go and loaded again as the player came back,
            // or sent again by the server while the client still had it (a new chunk object). The scanner took it for
            // one already mapped and never looked at the new data: it is mapped as a new one.
            int lastScan = surfaceTracker.forget(chunk.xPosition, chunk.zPosition);
            caveTracker.forget(chunk.xPosition, chunk.zPosition);
            if (lastScan >= 0) {
                FlatLog.log(
                    "RELOADED " + chunk.xPosition
                        + ","
                        + chunk.zPosition
                        + " loaded again (came back, or sent again) "
                        + (tick - lastScan)
                        + " ticks after its last scan: mapped again as a new chunk");
            }
            FlatLog.loaded(chunk.xPosition, chunk.zPosition);
        }
    }

    /** A chunk the game lets go of: the 3D map keeps the ones left at the edge of the explored map whole. */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.world;
        Chunk chunk = event.getChunk();
        if (world != null && world.isRemote && world == currentWorld && chunk != null) {
            FlatLog.unloaded(chunk.xPosition, chunk.zPosition);
        }
        if (world != null && world.isRemote
            && world == currentWorld
            && chunk != null
            && surface != null
            && !ChunkLoadClient.INSTANCE.isLettingGo()) {
            // Flying fast, chunks can come and go before their turn: one never mapped is mapped now, while its
            // blocks are still there.
            long start = System.nanoTime();
            surfaceTracker.scanIfStale(currentWorld, chunk, surface, biomes);
            // Copying blocks for the 3D map costs more: only for a few milliseconds per tick (flying fast, dozens of
            // chunks go at once); the others are copied the next time they are loaded.
            IsoMap.INSTANCE.onChunkUnload(world, chunk, unloadNanos < UNLOAD_BUDGET_NANOS);
            unloadNanos += System.nanoTime() - start;
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

    /**
     * Deletes a region (512x512 blocks) of a dimension's flat map: surface, biomes and every cave layer, in memory and
     * on disk. Chunks still loaded around the player are mapped again only once they change or load again. Render
     * thread.
     */
    public void deleteFlatRegion(int dimensionId, int rx, int rz) {
        if (worldDirectory == null || surface == null) {
            return;
        }
        List<MapDimension> maps = new ArrayList<>();
        if (dimensionId == surface.dimensionId) {
            maps.add(surface);
            maps.add(surface.plantless());
            maps.add(biomes);
            maps.addAll(caveLayers.values());
        } else {
            maps.addAll(other(dimensionId).all());
        }
        for (MapDimension map : maps) {
            map.deleteRegion(rx, rz);
        }
        // Cave layers not in memory have files too.
        File caves = new File(new File(worldDirectory, "dim" + dimensionId), "caves");
        File[] layers = caves.listFiles(File::isDirectory);
        if (layers != null) {
            for (File layer : layers) {
                MapRegion.deleteFiles(layer, rx, rz);
            }
        }
    }

    /**
     * Deletes saved map data safely while in a world: the maps are saved and closed (with the logs), {@code delete}
     * runs, and they are opened again on the next tick, from what is left on disk. Without closing, the regions in
     * memory would be saved back over what was deleted. Render thread.
     */
    public void resetMaps(Runnable delete) {
        resetMaps("unnamed", delete);
    }

    /**
     * @param what what is deleted, for the 2D map log: the cleaning happens while its log is closed, so it is written
     *             at the start of the next one
     */
    public void resetMaps(String what, Runnable delete) {
        File world = worldDirectory;
        FlatLog.note("CLEAN_START " + what + " world=" + world + ": maps saved and closed, then the files deleted");
        long start = System.nanoTime();
        MapExport.cancel();
        close();
        long closed = System.nanoTime();
        // Opened again by the next tick, as when a world is joined.
        delete.run();
        FlatLog.note(
            "CLEAN_DONE " + what
                + " closeMs="
                + FlatLog.ms(closed - start)
                + " deleteMs="
                + FlatLog.ms(System.nanoTime() - closed));
    }

    private void open(Minecraft mc, WorldClient world) {
        currentWorld = world;
        int dimensionId = world.provider.dimensionId;
        worldDirectory = accountDirectory(mc, new File(new File(mc.mcDataDir, "wayfarmap"), getWorldFolder(mc)));
        dimensionDirectory = new File(worldDirectory, "dim" + dimensionId);
        // Name and sky saved so the world map can show this dimension from elsewhere.
        writeInfo(dimensionDirectory, world.provider);
        WaypointManager.INSTANCE.load(worldDirectory);
        surface = new MapDimension(dimensionId, dimensionDirectory, loadExecutor).withPlantless();
        biomes = new MapDimension(dimensionId, new File(dimensionDirectory, "biomes"), loadExecutor);
        lastAutosave = System.currentTimeMillis();
        IsoMap.INSTANCE.open(worldDirectory);
        FlatLog.open(mc.mcDataDir, dimensionDirectory, dimensionId);
        WayFarMap.LOG.info("Map data for dimension {} is stored in {}", dimensionId, dimensionDirectory);
    }

    private void close() {
        long start = System.nanoTime();
        List<Future<?>> pending = new ArrayList<>();
        int maps = 0;
        for (MapDimension map : allMaps()) {
            pending.addAll(map.save(saveExecutor));
            maps++;
        }
        // Wait so the data is on disk even if the game exits right after leaving the world.
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (Exception e) {
                WayFarMap.LOG.warn("Error while saving the map", e);
            }
        }
        if (FlatLog.on()) {
            logSaveAll("CLOSE_SAVE", maps, pending.size(), System.nanoTime() - start);
        }
        for (MapDimension map : allMaps()) {
            map.deleteTextures();
        }
        // Saves the blocks of the 3D map and waits for it too.
        IsoMap.INSTANCE.close();
        FlatLog.close();
        BiomeHighlight.clear();
        Topography.clear();
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

    /** For the log: a round of saves of every map, with what is in memory and still changed after it. */
    private void logSaveAll(String why, int maps, int queued, long nanos) {
        int inMemory = 0, dirty = 0;
        for (MapDimension map : allMaps()) {
            inMemory += map.regionCount();
            dirty += map.dirtyCount();
        }
        FlatLog.saveAll(why, maps, queued, dirty, nanos, inMemory);
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

    /** A chunk whose blocks changed is scanned again at most this often (ticks). */
    private static final int CHANGED_RESCAN_TICKS = 40;
    /** The same right around the player, where building shows at once. */
    private static final int NEAR_CHANGED_RESCAN_TICKS = 10;
    /** Ticks before the scan queue is built again once it ran empty. */
    private static final int QUEUE_REBUILD_TICKS = 2;
    /** Time per tick for scanning chunks into one map (besides the number of chunks in the settings). */
    private static final long SCAN_BUDGET_NANOS = 3_000_000L;
    /** Time per tick for mapping chunks as the game lets them go. */
    private static final long UNLOAD_BUDGET_NANOS = 4_000_000L;
    /** Time spent on chunks let go since the last tick. */
    private long unloadNanos;

    /** Ticks a new chunk's blocks must stay unchanged before it is first mapped (its decoration has arrived). */
    private static final int SETTLE_QUIET_TICKS = 10;
    /** A new chunk is mapped after this long anyway (the edge of the view, flowing water that never settles). */
    private static final int SETTLE_MAX_TICKS = 100;

    /** Decides which loaded chunks to (re)scan into one map, nearest and never scanned first. */
    private final class ScanTracker {

        private final Map<Long, Integer> lastScanTick = new HashMap<>();
        /**
         * New chunks not mapped yet: {tick first seen, tick of the last change seen}. A chunk is sent before the
         * server decorated it (trees, snow, ores come as the chunks around it are made): it is mapped only once it
         * is whole, so the map shows it finished at once instead of bare first.
         */
        private final Map<Long, int[]> settling = new HashMap<>();
        private final ArrayDeque<Long> queue = new ArrayDeque<>();
        private int nextQueueBuild;
        /** Chunk the player was in when the queue was built. */
        private long queuedAround = Long.MIN_VALUE;
        /** Whether this tracker maps the surface: it owns the chunks' changed marks (the cave one leaves them). */
        private final boolean surface;

        ScanTracker(boolean surface) {
            this.surface = surface;
        }

        /** Forgets that a chunk was scanned (it is scanned again as a new one); the tick of its last scan, or -1. */
        int forget(int chunkX, int chunkZ) {
            Integer last = lastScanTick.remove(chunkKey(chunkX, chunkZ));
            return last == null ? -1 : last;
        }

        void reset() {
            lastScanTick.clear();
            settling.clear();
            queue.clear();
            nextQueueBuild = 0;
            queuedAround = Long.MIN_VALUE;
        }

        /**
         * Whether a chunk never mapped is finished: all eight chunks around it are there (so the server decorated
         * it) and its blocks stayed unchanged for a moment (the decoration arrived); or it waited long enough.
         */
        private boolean settled(WorldClient world, Chunk chunk, long key) {
            int[] state = settling.get(key);
            if (state == null) {
                state = new int[] { tick, tick };
                settling.put(key, state);
                if (surface) {
                    chunk.isModified = false;
                    IsoLog.seen(chunk.xPosition, chunk.zPosition, allAroundReady(world, chunk));
                }
                if (FlatLog.on()) {
                    FlatLog.seen(
                        chunk.xPosition,
                        chunk.zPosition,
                        missingNeighbours(world, chunk),
                        where(chunk),
                        surfaceSignature(chunk));
                    if (surface) {
                        FlatLog.arrived(chunk.xPosition, chunk.zPosition, ArrivalCheck.take(world, chunk));
                    }
                }
                return false;
            }
            if (tick - state[0] >= SETTLE_MAX_TICKS) {
                if (surface && IsoLog.enabled()) {
                    IsoLog.settled(
                        chunk.xPosition,
                        chunk.zPosition,
                        "timeout(neighboursReady=" + allAroundReady(world, chunk)
                            + ", quietTicks="
                            + (tick - state[1])
                            + ")",
                        tick - state[0]);
                }
                if (FlatLog.on()) {
                    FlatLog.settled(
                        chunk.xPosition,
                        chunk.zPosition,
                        true,
                        tick - state[1],
                        missingNeighbours(world, chunk),
                        where(chunk),
                        atEdge(chunk),
                        tick - state[0]);
                }
                return true;
            }
            if (surface && chunk.isModified) {
                chunk.isModified = false;
                state[1] = tick;
                if (FlatLog.on()) {
                    FlatLog.marked(chunk.xPosition, chunk.zPosition, surfaceSignature(chunk));
                }
                return false;
            }
            if (tick - state[1] < SETTLE_QUIET_TICKS) {
                return false;
            }
            if (!allAroundReady(world, chunk)) {
                return false;
            }
            if (surface) {
                IsoLog.settled(chunk.xPosition, chunk.zPosition, "quiet+neighbours", tick - state[0]);
            }
            if (FlatLog.on()) {
                FlatLog.settled(
                    chunk.xPosition,
                    chunk.zPosition,
                    false,
                    tick - state[1],
                    "",
                    where(chunk),
                    atEdge(chunk),
                    tick - state[0]);
            }
            return true;
        }

        /** The neighbours not loaded (for the log): "+x-z,-x" style, empty if all are. */
        private String missingNeighbours(WorldClient world, Chunk chunk) {
            StringBuilder missing = new StringBuilder();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0)
                        && !ChunkScanner.isChunkReady(world, chunk.xPosition + dx, chunk.zPosition + dz)) {
                        if (missing.length() > 0) {
                            missing.append(',');
                        }
                        missing.append(dx < 0 ? "-x" : dx > 0 ? "+x" : "")
                            .append(dz < 0 ? "-z" : dz > 0 ? "+z" : "");
                    }
                }
            }
            return missing.toString();
        }

        /** Chunks from the player (the larger of x and z), for the log. */
        private int distance(Chunk chunk) {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player == null) {
                return -1;
            }
            return Math.max(
                Math.abs(chunk.xPosition - (MathHelper.floor_double(player.posX) >> 4)),
                Math.abs(chunk.zPosition - (MathHelper.floor_double(player.posZ) >> 4)));
        }

        /** At the edge of the area the server sends: the chunks past it come only when the player gets closer. */
        private boolean atEdge(Chunk chunk) {
            return distance(chunk) >= Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
        }

        private String where(Chunk chunk) {
            return "distance=" + distance(chunk)
                + "/"
                + Minecraft.getMinecraft().gameSettings.renderDistanceChunks
                + (atEdge(chunk) ? " (edge)" : "");
        }

        private boolean allAroundReady(WorldClient world, Chunk chunk) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0)
                        && !ChunkScanner.isChunkReady(world, chunk.xPosition + dx, chunk.zPosition + dz)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Maps a chunk now if it never was, or changed since (it is about to be let go by the game). */
        void scanIfStale(WorldClient world, Chunk chunk, MapDimension map, MapDimension biomeMap) {
            long key = chunkKey(chunk.xPosition, chunk.zPosition);
            boolean scanned = lastScanTick.containsKey(key);
            if (scanned && !chunk.isModified || chunk.isEmpty()) {
                return;
            }
            int rx = chunk.xPosition >> (MapRegion.SHIFT - 4), rz = chunk.zPosition >> (MapRegion.SHIFT - 4);
            // Only if its regions are in memory: waiting for the disk here would freeze the game.
            if (!map.prepareRegion(rx, rz) | (biomeMap != null && !biomeMap.prepareRegion(rx, rz))) {
                FlatLog.unloadScan(
                    chunk.xPosition,
                    chunk.zPosition,
                    "SKIPPED region not in memory (being read), never scanned=" + !scanned);
                IsoLog.log(
                    "UNLOAD_SCAN_SKIPPED " + chunk.xPosition
                        + ","
                        + chunk.zPosition
                        + " flat region not in memory, never scanned="
                        + !scanned);
                return;
            }
            FlatLog.unloadScan(chunk.xPosition, chunk.zPosition, scanned ? "scanned (changed)" : "scanned (never was)");
            unloading = true;
            try {
                scanChunk(world, chunk, map, -1, biomeMap, rx, rz, scanned);
            } finally {
                unloading = false;
            }
            lastScanTick.put(key, tick);
            settling.remove(key);
        }

        /** While a chunk let go by the game is scanned (for the log). */
        private boolean unloading;

        /**
         * Maps a chunk of {@code /wf chunkload} now (its surface and biomes; not queued for the 3D map, the loading
         * does that itself). False if its regions are still being read: tried again next tick.
         */
        boolean scanForLoad(WorldClient world, Chunk chunk, MapDimension map, MapDimension biomeMap, boolean with3d) {
            int rx = chunk.xPosition >> (MapRegion.SHIFT - 4), rz = chunk.zPosition >> (MapRegion.SHIFT - 4);
            boolean surfaceReady = map.prepareRegion(rx, rz);
            boolean biomesReady = biomeMap == null || biomeMap.prepareRegion(rx, rz);
            if (!surfaceReady || !biomesReady) {
                FlatLog.deferred(
                    chunk.xPosition,
                    chunk.zPosition,
                    map.label() + " (chunkload)",
                    surfaceReady,
                    biomesReady);
                return false;
            }
            scanChunk(world, chunk, map, -1, biomeMap, rx, rz, false, false);
            FlatLog.log("CHUNKLOAD_SCAN " + chunk.xPosition + "," + chunk.zPosition + (with3d ? " 3D" : " 2D"));
            return true;
        }

        boolean scanCaveForLoad(WorldClient world, Chunk chunk, MapDimension map, int caveLayer) {
            int rx = chunk.xPosition >> (MapRegion.SHIFT - 4), rz = chunk.zPosition >> (MapRegion.SHIFT - 4);
            if (!map.prepareRegion(rx, rz)) {
                FlatLog.deferred(chunk.xPosition, chunk.zPosition, map.label() + " (chunkload)", false, true);
                return false;
            }
            scanChunk(world, chunk, map, caveLayer, null, rx, rz, false, false);
            return true;
        }

        private void scanChunk(WorldClient world, Chunk chunk, MapDimension map, int caveLayer, MapDimension biomeMap,
            int rx, int rz, boolean changed) {
            scanChunk(world, chunk, map, caveLayer, biomeMap, rx, rz, changed, true);
        }

        /**
         * @param changed scanned before, and its blocks changed since
         * @param for3d   the 3D map is told (it copies the chunk's blocks soon)
         */
        private void scanChunk(WorldClient world, Chunk chunk, MapDimension map, int caveLayer, MapDimension biomeMap,
            int rx, int rz, boolean changed, boolean for3d) {
            int cx = chunk.xPosition, cz = chunk.zPosition;
            try {
                // The game marks a chunk changed when its blocks or light change (the client never saves chunks,
                // so the mark is free to use): changes from now on bring the chunk up again.
                boolean modified = chunk.isModified;
                if (caveLayer < 0) {
                    chunk.isModified = false;
                }
                MapRegion before = map.getLoadedRegion(rx, rz);
                int changesBefore = before == null ? -1 : before.getChanges();
                long onMapSince = before == null ? 0
                    : before.getChunkTime(cx & (MapRegion.CHUNKS - 1), cz & (MapRegion.CHUNKS - 1));
                ArrivalCheck.Snapshot arrived = caveLayer < 0 ? FlatLog.takeArrival(cx, cz) : null;
                int[] arrival = null;
                if (arrived != null) {
                    arrival = new int[8];
                    ArrivalCheck.compare(chunk, arrived, world.provider.hasNoSky, arrival);
                }
                long scanStart = System.nanoTime();
                ChunkScanner.scan(world, chunk, map, caveLayer, biomeMap);
                if (caveLayer < 0 && for3d) {
                    IsoLog.scanned(cx, cz, changed, changed && modified, modified, System.nanoTime() - scanStart);
                    // The 3D map keeps the surface's blocks.
                    // Copied again soon only if its blocks really changed: a chunk scanned again on schedule is
                    // copied again rarely, instead of taking the time of new chunks.
                    IsoMap.INSTANCE.onChunkScanned(world, chunk, changed && modified);
                }
                MapRegion scanned = map.getLoadedRegion(rx, rz);
                if (FlatLog.on()) {
                    String why = !for3d ? "chunkload"
                        : unloading ? (changed ? "unloading(changed)" : "unloading(never scanned)")
                            : !changed ? "new" : modified ? "changed" : "rescan";
                    FlatLog.scanned(
                        cx,
                        cz,
                        caveLayer,
                        why,
                        System.nanoTime() - scanStart,
                        scanned == null ? 0 : scanned.getChanges() - Math.max(0, changesBefore),
                        before == null,
                        map.label(),
                        caveLayer < 0 ? surfaceSignature(chunk) : 0,
                        onMapSince);
                    if (arrival != null) {
                        FlatLog.arrival(cx, cz, arrival, System.nanoTime() - arrived.at, why);
                    }
                }
                // The time says when the chunk last looked like this: kept if nothing changed, so a region scanned
                // again and again isn't saved again each time (and teammates' newer versions still win).
                boolean same = scanned != null && scanned == before
                    && scanned.getChanges() == changesBefore
                    && scanned.getChunkTime(cx & (MapRegion.CHUNKS - 1), cz & (MapRegion.CHUNKS - 1)) != 0;
                if (scanned != null && !same) {
                    scanned.setChunkTime(
                        cx & (MapRegion.CHUNKS - 1),
                        cz & (MapRegion.CHUNKS - 1),
                        System.currentTimeMillis());
                }
                TeamMapClient.INSTANCE.onChunkScanned(map, biomeMap, caveLayer, cx, cz);
            } catch (Exception e) {
                WayFarMap.LOG.warn("Failed to map chunk " + cx + ", " + cz, e);
            }
        }

        /** @param caveLayer layer to scan, or -1 for the surface */
        void scan(Minecraft mc, WorldClient world, EntityPlayer player, MapDimension map, int caveLayer,
            MapDimension biomeMap, int budget) {
            long around = chunkKey(
                MathHelper.floor_double(player.posX) >> 4,
                MathHelper.floor_double(player.posZ) >> 4);
            // Built again when the player moved to another chunk too: flying fast, the new chunks ahead come first
            // instead of waiting for the old queue to be worked through.
            if (queue.isEmpty() && tick >= nextQueueBuild || around != queuedAround) {
                queue.clear();
                buildQueue(mc, world, player);
                nextQueueBuild = tick + QUEUE_REBUILD_TICKS;
                queuedAround = around;
            }
            long tickStart = System.nanoTime();
            long end = tickStart + SCAN_BUDGET_NANOS;
            int budgetGiven = budget, deferredCount = 0;
            boolean first = true;
            while (budget > 0 && !queue.isEmpty() && (first || System.nanoTime() < end)) {
                first = false;
                long key = queue.poll();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (!ChunkScanner.isChunkReady(world, cx, cz)) {
                    continue;
                }
                // Reading a region from disk takes tens of milliseconds: it is done in the background, and the
                // chunk is scanned on a later tick instead of freezing the game (both reads start right away).
                int rx = cx >> (MapRegion.SHIFT - 4), rz = cz >> (MapRegion.SHIFT - 4);
                boolean surfaceReady = map.prepareRegion(rx, rz);
                boolean biomesReady = biomeMap == null || biomeMap.prepareRegion(rx, rz);
                if (!surfaceReady || !biomesReady) {
                    if (surface) {
                        IsoLog.scanDeferred(cx, cz);
                    }
                    FlatLog.deferred(cx, cz, map.label(), surfaceReady, biomesReady);
                    deferredCount++;
                    continue;
                }
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                scanChunk(world, chunk, map, caveLayer, biomeMap, rx, rz, lastScanTick.containsKey(key));
                lastScanTick.put(key, tick);
                settling.remove(key);
                budget--;
            }
            if (FlatLog.on()) {
                FlatLog.tick(
                    caveLayer,
                    budgetGiven - budget,
                    deferredCount,
                    queue.size(),
                    System.nanoTime() - tickStart,
                    budgetGiven);
            }
        }

        int queueSize() {
            return queue.size();
        }

        int settlingSize() {
            return settling.size();
        }

        private void buildQueue(Minecraft mc, WorldClient world, EntityPlayer player) {
            long buildStart = System.nanoTime();
            int loaded = 0, fresh = 0, changedCount = 0, rescans = 0;
            int pcx = MathHelper.floor_double(player.posX) >> 4;
            int pcz = MathHelper.floor_double(player.posZ) >> 4;
            int radius = mc.gameSettings.renderDistanceChunks + 1;

            // Forget chunks well out of range that the game has let go; they get rescanned when the player comes
            // back. Flying fast, the game keeps chunks well past this range for a while: forgotten while still
            // loaded, each was mapped again as "never scanned" when let go (over a hundred in one tick).
            Iterator<Long> it = lastScanTick.keySet()
                .iterator();
            while (it.hasNext()) {
                long key = it.next();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if ((Math.abs(cx - pcx) > radius + 4 || Math.abs(cz - pcz) > radius + 4)
                    && !ChunkScanner.isChunkReady(world, cx, cz)) {
                    it.remove();
                }
            }
            Iterator<Long> waiting = settling.keySet()
                .iterator();
            while (waiting.hasNext()) {
                long key = waiting.next();
                if (Math.abs((int) (key >> 32) - pcx) > radius + 4 || Math.abs((int) key - pcz) > radius + 4) {
                    waiting.remove();
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
                    if (!ChunkScanner.isChunkReady(world, cx, cz)) {
                        continue;
                    }
                    loaded++;
                    boolean changed = false;
                    if (last == null && !settled(world, world.getChunkFromChunkCoords(cx, cz), key)) {
                        continue;
                    }
                    if (last != null) {
                        // Keep the area around the player up to date; far chunks rarely change, except right after
                        // they arrive: trees, snow and ores are added as the chunks around them are made, after the
                        // chunk itself was sent (flying fast, the map would keep chunks without them).
                        // Changes are caught by the chunk's changed mark: scanning again without one is only a
                        // fallback, now and then.
                        int interval = distance <= 1 ? 200 : distance <= 4 ? 600 : 1200;
                        int changedInterval = distance <= 1 ? NEAR_CHANGED_RESCAN_TICKS : CHANGED_RESCAN_TICKS;
                        changed = tick - last >= changedInterval && world.getChunkFromChunkCoords(cx, cz).isModified;
                        if (tick - last < interval && !changed) {
                            continue;
                        }
                    }
                    if (last == null) {
                        fresh++;
                    } else if (changed) {
                        changedCount++;
                    } else {
                        rescans++;
                    }
                    long priority = (last == null ? 0 : changed ? 500 : 1000) + distance;
                    candidates.add(new long[] { priority, key });
                }
            }
            Collections.sort(candidates, (a, b) -> Long.compare(a[0], b[0]));
            for (long[] candidate : candidates) {
                queue.add(candidate[1]);
            }
            if (FlatLog.on()) {
                FlatLog.queueBuilt(
                    surface ? -1 : activeCaveLayer,
                    radius,
                    loaded,
                    fresh,
                    changedCount,
                    rescans,
                    settling.size(),
                    System.nanoTime() - buildStart);
            }
        }
    }
}
