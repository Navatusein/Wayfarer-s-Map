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
    public static final double[] MAP_ZOOMS = { 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0 };

    public static final int LIGHT_AUTO = 0, LIGHT_DAY = 1, LIGHT_NIGHT = 2;

    public static boolean minimapEnabled = true;
    public static int minimapSize = 100;
    public static int minimapCorner = 1;
    public static int minimapZoom = 1;
    public static boolean minimapShowCoordinates = true;
    public static boolean minimapShowBiome = true;

    /** Map lighting: {@link #LIGHT_AUTO} follows the day/night cycle. */
    public static int mapLightMode = LIGHT_AUTO;
    public static boolean useTextureColors = true;
    public static int chunksScannedPerTick = 16;
    public static int autosaveIntervalSeconds = 60;

    public static boolean showOtherPlayers = true;
    public static boolean showHostileMobs = true;
    public static boolean showPassiveMobs = true;
    public static boolean showOtherEntities = true;
    public static boolean entityIcons = true;
    public static int entityIconLimit = 48;
    public static int entityVerticalRange = 32;

    public static boolean waypointsInWorld = true;
    public static boolean waypointsOnMinimap = true;
    public static int waypointMaxDistance = 0;
    public static double waypointScale = 1.0;
    public static double waypointMinScale = 0.35;
    public static int waypointLabelMaxWidth = 100;

    public static final List<Option> OPTIONS = new ArrayList<>();

    static {
        String c = CATEGORY_MINIMAP;
        bool(c, "enabled", "Show the minimap on the HUD.", true, () -> minimapEnabled, v -> minimapEnabled = v);
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
            "zoom",
            "Minimap zoom level index (0 = farthest).",
            1,
            new String[] { "z0", "z1", "z2", "z3" },
            () -> minimapZoom,
            v -> minimapZoom = v);
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
        choice(
            c,
            "lightMode",
            "Map lighting: 0 = follow the day/night cycle, 1 = always day, 2 = always night.",
            LIGHT_AUTO,
            new String[] { "auto", "day", "night" },
            () -> mapLightMode,
            v -> mapLightMode = v);
        bool(
            c,
            "useTextureColors",
            "Color the map with the average color of block textures. If false, vanilla map colors are used.",
            true,
            () -> useTextureColors,
            v -> useTextureColors = v);
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

        c = CATEGORY_ENTITIES;
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
        bool(c, "icons", "Draw mobs as small models instead of dots.", true, () -> entityIcons, v -> entityIcons = v);
        integer(
            c,
            "iconLimit",
            "Only the nearest this many mobs are drawn as models, the rest as dots.",
            48,
            4,
            256,
            4,
            () -> entityIconLimit,
            v -> entityIconLimit = v);
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

        c = CATEGORY_WAYPOINTS;
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
            "maxDistance",
            "Waypoints farther than this many blocks are not shown in the world. 0 = no limit.",
            0,
            0,
            10000,
            100,
            () -> waypointMaxDistance,
            v -> waypointMaxDistance = v);
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

        ChoiceOption(String category, String key, String comment, int defaultValue, String[] values,
            IntSupplier getter, IntConsumer setter) {
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

    private static void bool(String category, String key, String comment, boolean def, Supplier<Boolean> getter,
        Consumer<Boolean> setter) {
        OPTIONS.add(new BoolOption(category, key, comment, def, getter, setter));
    }

    private static void integer(String category, String key, String comment, int def, int min, int max, int step,
        IntSupplier getter, IntConsumer setter) {
        OPTIONS.add(new IntOption(category, key, comment, def, min, max, step, getter, setter));
    }

    private static void choice(String category, String key, String comment, int def, String[] values,
        IntSupplier getter, IntConsumer setter) {
        OPTIONS.add(new ChoiceOption(category, key, comment, def, values, getter, setter));
    }

    private static void decimal(String category, String key, String comment, double def, double min, double max,
        double step, DoubleSupplier getter, DoubleConsumer setter) {
        OPTIONS.add(new DoubleOption(category, key, comment, def, min, max, step, getter, setter));
    }
}
