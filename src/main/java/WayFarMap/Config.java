package WayFarMap;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    private static final String CATEGORY_MINIMAP = "minimap";
    private static final String CATEGORY_MAP = "map";
    private static final String CATEGORY_WAYPOINTS = "waypoints";

    /** Minimap zoom levels, in GUI pixels per block. */
    public static final double[] MINIMAP_ZOOMS = { 0.5, 1.0, 2.0, 4.0 };
    /** Fullscreen map zoom levels, in GUI pixels per block. */
    public static final double[] MAP_ZOOMS = { 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0 };

    public static boolean minimapEnabled = true;
    public static int minimapSize = 100;
    public static int minimapCorner = 1;
    public static int minimapZoom = 1;
    public static boolean minimapShowCoordinates = true;
    public static boolean minimapShowBiome = true;
    public static boolean showOtherPlayers = true;

    public static boolean waypointsInWorld = true;
    public static boolean waypointsOnMinimap = true;
    public static int waypointMaxDistance = 0;
    public static double waypointScale = 1.0;
    public static double waypointMinScale = 0.35;

    /** Map lighting: 0 = follows the game's day/night cycle, 1 = always day, 2 = always night. */
    public static int mapLightMode = 0;
    public static final int LIGHT_AUTO = 0, LIGHT_DAY = 1, LIGHT_NIGHT = 2;

    public static boolean showHostileMobs = true;
    public static boolean showPassiveMobs = true;
    public static boolean showOtherEntities = true;
    public static int entityVerticalRange = 32;

    public static boolean useTextureColors = true;
    public static int chunksScannedPerTick = 16;
    public static int autosaveIntervalSeconds = 60;

    private static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);

        minimapEnabled = configuration
            .getBoolean("enabled", CATEGORY_MINIMAP, minimapEnabled, "Show the minimap on the HUD.");
        minimapSize = configuration.getInt("size", CATEGORY_MINIMAP, minimapSize, 48, 256, "Minimap size in GUI pixels.");
        minimapCorner = configuration.getInt(
            "corner",
            CATEGORY_MINIMAP,
            minimapCorner,
            0,
            3,
            "Screen corner: 0 = top left, 1 = top right, 2 = bottom left, 3 = bottom right.");
        minimapZoom = configuration.getInt(
            "zoom",
            CATEGORY_MINIMAP,
            minimapZoom,
            0,
            MINIMAP_ZOOMS.length - 1,
            "Minimap zoom level index (0 = farthest).");
        minimapShowCoordinates = configuration
            .getBoolean("showCoordinates", CATEGORY_MINIMAP, minimapShowCoordinates, "Show coordinates under the minimap.");
        minimapShowBiome = configuration
            .getBoolean("showBiome", CATEGORY_MINIMAP, minimapShowBiome, "Show the current biome under the minimap.");

        waypointsInWorld = configuration
            .getBoolean("showInWorld", CATEGORY_WAYPOINTS, waypointsInWorld, "Show waypoint markers in the world.");
        waypointsOnMinimap = configuration
            .getBoolean("showOnMinimap", CATEGORY_WAYPOINTS, waypointsOnMinimap, "Show waypoints on the minimap.");
        waypointMaxDistance = configuration.getInt(
            "maxDistance",
            CATEGORY_WAYPOINTS,
            waypointMaxDistance,
            0,
            1000000,
            "Waypoints farther than this many blocks are not shown in the world. 0 = no limit.");

        waypointScale = configuration.get(
            CATEGORY_WAYPOINTS,
            "scale",
            waypointScale,
            "Size of in-world waypoints (1.0 = default).",
            0.25,
            4.0)
            .getDouble(waypointScale);
        waypointMinScale = configuration.get(
            CATEGORY_WAYPOINTS,
            "minScale",
            waypointMinScale,
            "In-world waypoints shrink with distance down to this fraction of their close-up size, then stop shrinking.",
            0.05,
            1.0)
            .getDouble(waypointMinScale);

        mapLightMode = configuration.getInt(
            "lightMode",
            CATEGORY_MAP,
            mapLightMode,
            0,
            2,
            "Map lighting: 0 = follow the day/night cycle, 1 = always day, 2 = always night.");
        showHostileMobs = configuration
            .getBoolean("showHostileMobs", CATEGORY_MAP, showHostileMobs, "Show hostile mobs (red dots) on the maps.");
        showPassiveMobs = configuration
            .getBoolean("showPassiveMobs", CATEGORY_MAP, showPassiveMobs, "Show animals (green dots) on the maps.");
        showOtherEntities = configuration.getBoolean(
            "showOtherEntities",
            CATEGORY_MAP,
            showOtherEntities,
            "Show other living entities, e.g. villagers and golems (yellow dots), on the maps.");
        entityVerticalRange = configuration.getInt(
            "entityVerticalRange",
            CATEGORY_MAP,
            entityVerticalRange,
            1,
            256,
            "Only show mobs at most this many blocks above or below you.");

        showOtherPlayers = configuration
            .getBoolean("showOtherPlayers", CATEGORY_MAP, showOtherPlayers, "Show other nearby players on the maps.");
        useTextureColors = configuration.getBoolean(
            "useTextureColors",
            CATEGORY_MAP,
            useTextureColors,
            "Color the map with the average color of block textures. If false, vanilla map colors are used.");
        chunksScannedPerTick = configuration.getInt(
            "chunksScannedPerTick",
            CATEGORY_MAP,
            chunksScannedPerTick,
            1,
            256,
            "How many loaded chunks may be scanned into the map per client tick.");
        autosaveIntervalSeconds = configuration.getInt(
            "autosaveIntervalSeconds",
            CATEGORY_MAP,
            autosaveIntervalSeconds,
            10,
            3600,
            "How often explored map data is written to disk.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    public static void setMinimapZoom(int zoom) {
        minimapZoom = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, zoom));
        save(CATEGORY_MINIMAP, "zoom", minimapZoom);
    }

    public static void setMinimapEnabled(boolean enabled) {
        minimapEnabled = enabled;
        if (configuration != null) {
            configuration.get(CATEGORY_MINIMAP, "enabled", true)
                .set(enabled);
            configuration.save();
        }
    }

    public static void setMapLightMode(int mode) {
        mapLightMode = mode;
        save(CATEGORY_MAP, "lightMode", mode);
    }

    private static void save(String category, String key, int value) {
        if (configuration != null) {
            configuration.get(category, key, value)
                .set(value);
            configuration.save();
        }
    }
}
