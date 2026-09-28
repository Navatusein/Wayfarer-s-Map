package WayFarMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraftforge.common.config.Configuration;

/**
 * Mod settings. Every setting is declared once as an {@link Option}; the list drives loading, saving and the in-game
 * settings screen.
 */
public class Config {

    public static final String CATEGORY_MINIMAP = "minimap";
    public static final String CATEGORY_MAP = "map";
    public static final String CATEGORY_ENTITIES = "entities";
    public static final String CATEGORY_WAYPOINTS = "waypoints";
    /** Categories in the order the settings screen shows them. */
    public static final List<String> CATEGORIES = Collections
        .unmodifiableList(Arrays.asList(CATEGORY_MINIMAP, CATEGORY_MAP, CATEGORY_ENTITIES, CATEGORY_WAYPOINTS));

    /** Minimap zoom levels, in GUI pixels per block. */
    public static final double[] MINIMAP_ZOOMS = { 0.5, 1.0, 2.0, 4.0 };
    /** Fullscreen map zoom levels, in GUI pixels per block. */
    public static final double[] MAP_ZOOMS = { 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0 };

    public static final int LIGHT_AUTO = 0, LIGHT_DAY = 1, LIGHT_NIGHT = 2;
    public static final int CAVES_AUTO = 0, CAVES_OFF = 1, CAVES_ON = 2;
    public static final int DISPLAY_BLOCKS = 0, DISPLAY_BIOMES = 1;

    public static boolean minimapEnabled = true;
    public static int minimapSize = 100;
    public static int minimapCorner = 1;
    public static int minimapZoom = 1;
    public static boolean minimapShowCoordinates = true;
    public static boolean minimapShowBiome = true;
    public static final int SHAPE_SQUARE = 0, SHAPE_ROUND = 1;
    public static int minimapShape = SHAPE_SQUARE;
    /** Turn the minimap with the player, so the view direction is always up. */
    public static boolean minimapRotate = false;
    /** Scale of the mod's screens (screen pixels per GUI pixel), independent of Minecraft's; 0 = auto. */
    public static int uiScale = 0;

    /** Map lighting: {@link #LIGHT_AUTO} follows the day/night cycle. */
    public static int mapLightMode = LIGHT_AUTO;
    /** Cave view: {@link #CAVES_AUTO} switches to it while underground. */
    public static int caveMode = CAVES_AUTO;
    /** Surface drawn with block colors or biome colors. */
    public static int mapDisplayMode = DISPLAY_BLOCKS;
    public static boolean chunkGrid = false;
    /** World map drawn in 3D, as an isometric view like Dynmap's, instead of from above. */
    public static boolean isometric = false;
    /** Side the 3D view looks from: 0 = south-east, 1 = north-east, 2 = north-west, 3 = south-west. */
    public static final int ISO_QUALITY_MAX = 3;
    public static int isoRotation = 0;
    /** Detail of the 3D world map: at most {@code 8 << isoQuality} pixels per block (8, 16, 32 or 64). */
    public static int isoQuality = ISO_QUALITY_MAX;
    /** Zoomed far out, four rays per pixel instead of one: smoother, but up to four times slower to draw. */
    public static boolean isoSmooth = true;
    /** Milliseconds per game tick spent copying chunks' blocks for the 3D map. */
    public static int isoCaptureMs = 5;
    /** Keep the blocks of explored chunks, which the 3D map is drawn from. */
    public static boolean record3d = true;
    /** VisualProspecting layers (only used when it is installed). */
    public static boolean showOreVeins = true;
    public static boolean showUndergroundFluids = false;
    /** ServerUtilities claims layer on the world map (only used when it is installed). */
    public static boolean showClaims = false;
    public static boolean showPowerfails = true;
    public static boolean showThaumcraftNodes = true;
    /** Share the explored map with the ServerUtilities team (where the server has the mod). */
    public static boolean shareMapWithTeam = true;
    public static boolean useTextureColors = true;
    public static int chunksScannedPerTick = 16;
    public static int autosaveIntervalSeconds = 60;

    public static boolean showOtherPlayers = true;
    public static boolean showHostileMobs = true;
    public static boolean showPassiveMobs = true;
    public static boolean showOtherEntities = true;
    public static boolean entityIcons = true;
    public static int entityIconLimit = 128;
    public static int entityVerticalRange = 32;

    public static boolean waypointsInWorld = true;
    public static boolean waypointsOnMinimap = true;
    public static int waypointMaxDistance = 0;
    public static double waypointScale = 1.0;
    public static double waypointMinScale = 0.35;
    public static int waypointLabelMaxWidth = 100;

    public static final List<Option> OPTIONS = new ArrayList<>();

    /** Section of the settings screen the options declared next are shown under. */
    private static String currentGroup = "";
    /** Switch the options declared next depend on (shown under it), or null. */
    private static BoolOption currentParent;

    static {
        String c = CATEGORY_MINIMAP;
        group("general");
        parent(null);
        bool(c, "enabled", "Show the minimap on the HUD.", true, () -> minimapEnabled, v -> minimapEnabled = v);
        parent("enabled");
        integer(c, "size", "Minimap size in GUI pixels.", 100, 48, 256, 4, () -> minimapSize, v -> minimapSize = v);
        choice(
            c,
            "corner",
            "Screen corner: 0 = top left, 1 = top right, 2 = bottom left, 3 = bottom right.",
            1,
            new String[] { "top_left", "top_right", "bottom_left", "bottom_right" },
            () -> minimapCorner,
            v -> minimapCorner = v);
        choice(
            c,
            "shape",
            "Minimap shape: 0 = square, 1 = round.",
            SHAPE_SQUARE,
            new String[] { "square", "round" },
            () -> minimapShape,
            v -> minimapShape = v);
        choice(
            c,
            "zoom",
            "Minimap zoom level index (0 = farthest).",
            1,
            new String[] { "z0", "z1", "z2", "z3" },
            () -> minimapZoom,
            v -> minimapZoom = v);
        bool(
            c,
            "rotate",
            "Turn the minimap with the player so the view direction is always up.",
            false,
            () -> minimapRotate,
            v -> minimapRotate = v);
        group("info");
        parent("enabled");
        bool(
            c,
            "showCoordinates",
            "Show coordinates under the minimap.",
            true,
            () -> minimapShowCoordinates,
            v -> minimapShowCoordinates = v);
        bool(
            c,
            "showBiome",
            "Show the current biome under the minimap.",
            true,
            () -> minimapShowBiome,
            v -> minimapShowBiome = v);

        c = CATEGORY_MAP;
        group("view");
        parent(null);
        choice(
            c,
            "uiScale",
            "Scale of the map and the mod's screens, independent of Minecraft's GUI scale: "
                + "0 = auto (fits the window), 1-6 = fixed.",
            0,
            new String[] { "auto", "s1", "s2", "s3", "s4", "s5", "s6" },
            () -> uiScale,
            v -> uiScale = v);
        choice(
            c,
            "lightMode",
            "Map lighting: 0 = follow the day/night cycle, 1 = always day, 2 = always night.",
            LIGHT_AUTO,
            new String[] { "auto", "day", "night" },
            () -> mapLightMode,
            v -> mapLightMode = v);
        choice(
            c,
            "displayMode",
            "Surface map colors: 0 = blocks, 1 = biomes.",
            DISPLAY_BLOCKS,
            new String[] { "blocks", "biomes" },
            () -> mapDisplayMode,
            v -> mapDisplayMode = v);
        choice(
            c,
            "caveMode",
            "Cave view: 0 = automatic while underground, 1 = off, 2 = always.",
            CAVES_AUTO,
            new String[] { "auto", "off", "on" },
            () -> caveMode,
            v -> caveMode = v);
        bool(
            c,
            "chunkGrid",
            "Draw chunk borders on the world map and the minimap.",
            false,
            () -> chunkGrid,
            v -> chunkGrid = v);
        bool(
            c,
            "useTextureColors",
            "Color the map with the average color of block textures. If false, vanilla map colors are used.",
            true,
            () -> useTextureColors,
            v -> useTextureColors = v);
        group("iso");
        parent(null);
        bool(
            c,
            "isometric",
            "Show the world map in 3D (isometric, like Dynmap) instead of from above.",
            false,
            () -> isometric,
            v -> isometric = v);
        parent("isometric");
        choice(
            c,
            "isoRotation",
            "Side the 3D world map is looked at from: 0 = south-east, 1 = north-east, 2 = north-west, "
                + "3 = south-west.",
            0,
            new String[] { "se", "ne", "nw", "sw" },
            () -> isoRotation,
            v -> isoRotation = v);
        integer(
            c,
            "isoQuality",
            "Detail of the 3D world map when zoomed in: 0 = 8, 1 = 16, 2 = 32, 3 = 64 pixels per block at most. "
                + "Less is quicker to draw.",
            ISO_QUALITY_MAX,
            0,
            ISO_QUALITY_MAX,
            1,
            () -> isoQuality,
            v -> isoQuality = v);
        bool(
            c,
            "isoSmooth",
            "Smooth the 3D world map zoomed far out (four rays per pixel). Off draws those tiles up to four times "
                + "faster.",
            true,
            () -> isoSmooth,
            v -> isoSmooth = v);
        parent(null);
        bool(
            c,
            "record3d",
            "Keep the blocks of explored chunks for the 3D world map (dim<id>/blocks, a few MB per region).",
            true,
            () -> record3d,
            v -> record3d = v);
        parent("record3d");
        integer(
            c,
            "isoCaptureMs",
            "Milliseconds per game tick spent copying the blocks of chunks for the 3D map. More puts new chunks on "
                + "the 3D map sooner but may cost frames while flying over new land.",
            5,
            1,
            20,
            1,
            () -> isoCaptureMs,
            v -> isoCaptureMs = v);
        group("layers");
        parent(null);
        bool(
            c,
            "oreVeins",
            "Show ore veins prospected with VisualProspecting (if installed).",
            true,
            () -> showOreVeins,
            v -> {
                showOreVeins = v;
                // Ore veins and underground fluids are shown one at a time.
                if (v) showUndergroundFluids = false;
            });
        bool(
            c,
            "undergroundFluids",
            "Show underground fluids prospected with VisualProspecting (if installed).",
            false,
            () -> showUndergroundFluids,
            v -> {
                showUndergroundFluids = v;
                if (v) showOreVeins = false;
            });
        bool(
            c,
            "claims",
            "Show ServerUtilities chunk claims on the world map (if installed).",
            false,
            () -> showClaims,
            v -> showClaims = v);
        bool(
            c,
            "powerfails",
            "Show GregTech power failures on the world map and the minimap (if GregTech has them).",
            true,
            () -> showPowerfails,
            v -> showPowerfails = v);
        bool(
            c,
            "thaumcraftNodes",
            "Show the Thaumcraft aura nodes found with TCNodeTracker on the maps (if installed).",
            true,
            () -> showThaumcraftNodes,
            v -> showThaumcraftNodes = v);
        group("data");
        parent(null);
        integer(
            c,
            "chunksScannedPerTick",
            "How many loaded chunks may be scanned into the map per client tick.",
            16,
            1,
            64,
            1,
            () -> chunksScannedPerTick,
            v -> chunksScannedPerTick = v);
        integer(
            c,
            "autosaveIntervalSeconds",
            "How often explored map data is written to disk.",
            60,
            10,
            600,
            10,
            () -> autosaveIntervalSeconds,
            v -> autosaveIntervalSeconds = v);
        bool(
            c,
            "shareWithTeam",
            "Share the explored map with your ServerUtilities team, on servers that have this mod too.",
            true,
            () -> shareMapWithTeam,
            v -> shareMapWithTeam = v);

        c = CATEGORY_ENTITIES;
        group("shown");
        parent(null);
        bool(
            c,
            "showOtherPlayers",
            "Show other players on the maps.",
            true,
            () -> showOtherPlayers,
            v -> showOtherPlayers = v);
        bool(
            c,
            "showHostileMobs",
            "Show hostile mobs on the maps.",
            true,
            () -> showHostileMobs,
            v -> showHostileMobs = v);
        bool(c, "showPassiveMobs", "Show animals on the maps.", true, () -> showPassiveMobs, v -> showPassiveMobs = v);
        bool(
            c,
            "showOtherEntities",
            "Show other living entities (villagers, golems...) on the maps.",
            true,
            () -> showOtherEntities,
            v -> showOtherEntities = v);
        integer(
            c,
            "verticalRange",
            "Only show mobs at most this many blocks above or below you.",
            32,
            4,
            256,
            4,
            () -> entityVerticalRange,
            v -> entityVerticalRange = v);
        group("icons");
        parent(null);
        bool(
            c,
            "icons",
            "Draw mobs as a small icon of their face instead of dots.",
            true,
            () -> entityIcons,
            v -> entityIcons = v);
        parent("icons");
        integer(
            c,
            "iconLimit",
            "Only the nearest this many mobs get an icon, the rest are dots.",
            128,
            4,
            256,
            4,
            () -> entityIconLimit,
            v -> entityIconLimit = v);

        c = CATEGORY_WAYPOINTS;
        group("where");
        parent(null);
        bool(
            c,
            "showInWorld",
            "Show waypoint markers in the world.",
            true,
            () -> waypointsInWorld,
            v -> waypointsInWorld = v);
        bool(
            c,
            "showOnMinimap",
            "Show waypoints on the minimap.",
            true,
            () -> waypointsOnMinimap,
            v -> waypointsOnMinimap = v);
        integer(
            c,
            "maxDistance",
            "Waypoints farther than this many blocks are not shown in the world. 0 = no limit.",
            0,
            0,
            10000,
            100,
            () -> waypointMaxDistance,
            v -> waypointMaxDistance = v);
        group("look");
        parent(null);
        decimal(
            c,
            "scale",
            "Size of in-world waypoints (1.0 = default).",
            1.0,
            0.25,
            3.0,
            0.05,
            () -> waypointScale,
            v -> waypointScale = v);
        decimal(
            c,
            "minScale",
            "In-world waypoints shrink with distance down to this fraction of their close-up size, then stop.",
            0.35,
            0.05,
            1.0,
            0.05,
            () -> waypointMinScale,
            v -> waypointMinScale = v);
        integer(
            c,
            "labelMaxWidth",
            "Maximum width of waypoint names on the maps and in the world, in pixels; longer names end with '...'.",
            100,
            30,
            300,
            10,
            () -> waypointLabelMaxWidth,
            v -> waypointLabelMaxWidth = v);
    }

    private static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);
        for (Option option : OPTIONS) {
            option.load(configuration);
        }
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** Writes all current values to the config file. */
    public static void save() {
        if (configuration == null) {
            return;
        }
        for (Option option : OPTIONS) {
            option.save(configuration);
        }
        configuration.save();
    }

    public static List<Option> getOptions(String category) {
        List<Option> result = new ArrayList<>();
        for (Option option : OPTIONS) {
            if (option.category.equals(category)) {
                result.add(option);
            }
        }
        return result;
    }

    public static void setMinimapZoom(int zoom) {
        minimapZoom = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, zoom));
        save();
    }

    public static void setMinimapEnabled(boolean enabled) {
        minimapEnabled = enabled;
        save();
    }

    /** Auto -> off -> on -> auto. */
    public static void cycleCaveMode() {
        caveMode = (caveMode + 1) % 3;
        save();
    }

    /** Ore veins and underground fluids are exclusive: turning one on turns the other off. */
    public static void toggleOreVeins() {
        showOreVeins = !showOreVeins;
        if (showOreVeins) {
            showUndergroundFluids = false;
        }
        save();
    }

    public static void toggleUndergroundFluids() {
        showUndergroundFluids = !showUndergroundFluids;
        if (showUndergroundFluids) {
            showOreVeins = false;
        }
        save();
    }

    public static void toggleThaumcraftNodes() {
        showThaumcraftNodes = !showThaumcraftNodes;
        save();
    }

    public static void togglePowerfails() {
        showPowerfails = !showPowerfails;
        save();
    }

    public static void toggleClaims() {
        showClaims = !showClaims;
        save();
    }

    /** Mob filter of the world map's "Mobs" button: which of hostile and friendly mobs are shown. */
    public static final int MOBS_ALL = 0, MOBS_FRIENDLY = 1, MOBS_HOSTILE = 2, MOBS_NONE = 3;

    /** Current mob filter; "friendly" is animals and other living entities (villagers, golems...). */
    public static int getMobFilter() {
        boolean friendly = showPassiveMobs || showOtherEntities;
        if (showHostileMobs) {
            return friendly ? MOBS_ALL : MOBS_HOSTILE;
        }
        return friendly ? MOBS_FRIENDLY : MOBS_NONE;
    }

    public static void setMobFilter(int filter) {
        showHostileMobs = filter == MOBS_ALL || filter == MOBS_HOSTILE;
        boolean friendly = filter == MOBS_ALL || filter == MOBS_FRIENDLY;
        showPassiveMobs = friendly;
        showOtherEntities = friendly;
        save();
    }

    public static void toggleIsometric() {
        isometric = !isometric;
        save();
    }

    /** Turns the 3D view by a quarter: +1 or -1. */
    public static void setIsoQuality(int quality) {
        isoQuality = Math.max(0, Math.min(ISO_QUALITY_MAX, quality));
        save();
    }

    /** Most pixels per block the 3D world map is drawn with. */
    public static int isoPixelsPerBlock() {
        return 8 << isoQuality;
    }

    public static void rotateIso(int quarters) {
        isoRotation = Math.floorMod(isoRotation + quarters, 4);
        save();
    }

    public static void toggleChunkGrid() {
        chunkGrid = !chunkGrid;
        save();
    }

    public static void toggleBiomeView() {
        mapDisplayMode = mapDisplayMode == DISPLAY_BIOMES ? DISPLAY_BLOCKS : DISPLAY_BIOMES;
        save();
    }

    public static void setMapLightMode(int mode) {
        mapLightMode = mode;
        save();
    }

    // ---------------------------------------------------------------- option types

    /** One setting: where it lives in the file and how to read/write its static field. */
    public abstract static class Option {

        public final String category;
        public final String key;
        public final String comment;
        /** Section of the settings screen it is shown under ({@code wayfarmap.settings.group.<group>}). */
        public String group = "";
        /** The switch it depends on: shown under it, dimmed while it is off; null if none. */
        public BoolOption parent;

        Option(String category, String key, String comment) {
            this.category = category;
            this.key = key;
            this.comment = comment;
        }

        /** Translation key of the name; {@code + ".desc"} is the description. */
        public String langKey() {
            return "wayfarmap.option." + category + "." + key;
        }

        abstract void load(Configuration configuration);

        abstract void save(Configuration configuration);

        public abstract void reset();
    }

    public static class BoolOption extends Option {

        public final boolean defaultValue;
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;

        BoolOption(String category, String key, String comment, boolean defaultValue, Supplier<Boolean> getter,
            Consumer<Boolean> setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.getter = getter;
            this.setter = setter;
        }

        public boolean get() {
            return getter.get();
        }

        public void set(boolean value) {
            setter.accept(value);
        }

        @Override
        void load(Configuration configuration) {
            set(configuration.getBoolean(key, category, defaultValue, comment));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }
    }

    public static class IntOption extends Option {

        public final int defaultValue, min, max, step;
        private final IntSupplier getter;
        private final IntConsumer setter;

        IntOption(String category, String key, String comment, int defaultValue, int min, int max, int step,
            IntSupplier getter, IntConsumer setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
        }

        public int get() {
            return getter.getAsInt();
        }

        public void set(int value) {
            setter.accept(Math.max(min, Math.min(max, value)));
        }

        @Override
        void load(Configuration configuration) {
            set(configuration.getInt(key, category, defaultValue, min, max, comment));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment, min, max)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }
    }

    /** An int setting shown as a list of named values. */
    public static class ChoiceOption extends IntOption {

        /** Suffixes of the value translation keys: {@code langKey() + "." + values[i]}. */
        public final String[] values;

        ChoiceOption(String category, String key, String comment, int defaultValue, String[] values, IntSupplier getter,
            IntConsumer setter) {
            super(category, key, comment, defaultValue, 0, values.length - 1, 1, getter, setter);
            this.values = values;
        }
    }

    public static class DoubleOption extends Option {

        public final double defaultValue, min, max, step;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        DoubleOption(String category, String key, String comment, double defaultValue, double min, double max,
            double step, DoubleSupplier getter, DoubleConsumer setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
        }

        public double get() {
            return getter.getAsDouble();
        }

        public void set(double value) {
            double snapped = Math.round(value / step) * step;
            setter.accept(Math.max(min, Math.min(max, snapped)));
        }

        @Override
        void load(Configuration configuration) {
            set(
                configuration.get(category, key, defaultValue, comment, min, max)
                    .getDouble(defaultValue));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment, min, max)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }
    }

    private static void group(String group) {
        currentGroup = group;
        currentParent = null;
    }

    private static void parent(String key) {
        currentParent = null;
        if (key == null) {
            return;
        }
        for (Option option : OPTIONS) {
            if (option.key.equals(key) && option instanceof BoolOption) {
                currentParent = (BoolOption) option;
            }
        }
    }

    private static void add(Option option) {
        option.group = currentGroup;
        option.parent = currentParent;
        OPTIONS.add(option);
    }

    private static void bool(String category, String key, String comment, boolean def, Supplier<Boolean> getter,
        Consumer<Boolean> setter) {
        add(new BoolOption(category, key, comment, def, getter, setter));
    }

    private static void integer(String category, String key, String comment, int def, int min, int max, int step,
        IntSupplier getter, IntConsumer setter) {
        add(new IntOption(category, key, comment, def, min, max, step, getter, setter));
    }

    private static void choice(String category, String key, String comment, int def, String[] values,
        IntSupplier getter, IntConsumer setter) {
        add(new ChoiceOption(category, key, comment, def, values, getter, setter));
    }

    private static void decimal(String category, String key, String comment, double def, double min, double max,
        double step, DoubleSupplier getter, DoubleConsumer setter) {
        add(new DoubleOption(category, key, comment, def, min, max, step, getter, setter));
    }
}
