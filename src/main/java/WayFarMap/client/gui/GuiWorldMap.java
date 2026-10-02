package WayFarMap.client.gui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.IsoEntityDrawer;
import WayFarMap.client.TeamMates;
import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.PowerfailLayer;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.BiomeHighlight;
import WayFarMap.client.map.ChunkLoadClient;
import WayFarMap.client.map.ChunkLoadView;
import WayFarMap.client.map.FlatExport;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;
import WayFarMap.client.map.Topography;
import WayFarMap.client.map.export.MapExport;
import WayFarMap.client.map.export.TilePyramid;
import WayFarMap.client.map.iso.IsoExport;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.client.map.iso.IsoProjection;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;

/** Fullscreen world map: drag to pan, mouse wheel to zoom. */
public class GuiWorldMap extends ScaledScreen {

    private static final int DEFAULT_ZOOM = 3;

    /** Zoom level is kept between openings of the map. */
    private static int zoomIndex = DEFAULT_ZOOM;

    private static final int HEADER_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 14;
    private static final float MARKER_SIZE = 12f;
    private static final float MIN_MARKER_SIZE = 6f;
    private static final int ID_WAYPOINTS = 0, ID_LIGHT = 1, ID_SETTINGS = 3, ID_CAVES = 4, ID_GRID = 6, ID_HELP = 10,
        ID_MOBS = 11, ID_ADDONS = 13, ID_TEAM = 14, ID_EXPORT = 17, ID_FOLLOW = 18, ID_STATS = 19, ID_MODES = 20,
        ID_CLOSE = 21, ID_CLEAN = 22;
    /** What the open menu is: the right click map menu, the mob filter, the add-on layers, teammates or export. */
    private static final int MENU_MAP = 0, MENU_MOBS = 1, MENU_ADDONS = 2, MENU_TEAM = 3, MENU_EXPORT = 4,
        MENU_MODES = 5, MENU_CONFIRM = 6, MENU_WAYPOINT = 7;
    private static final int EXPORT_MENU_WIDTH = 250;
    /** Rough time to draw one exported 3D tile on one thread, in seconds, for the menu's estimate. */
    private static final double EXPORT_SECONDS_PER_TILE = 0.2;
    /** Export the 3D map as it looks at night. */
    private static boolean exportNight;
    private static final int SLIDER_WIDTH = 10;
    private static final int MENU_WIDTH = 130, TEAM_MENU_WIDTH = 190, MENU_ROW = 14;
    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };
    /** Lang key suffixes of {@link Config#mapLightMode} values. */
    private static final String[] LIGHT_MODE_KEYS = { "auto", "day", "night" };

    /** How fast the zoom animation approaches the target zoom (higher is faster). */
    private static final double ZOOM_SPEED = 18.0;

    private double centerX;
    private double centerZ;
    /** Currently displayed scale; animates towards {@code Config.MAP_ZOOMS[zoomIndex]}. */
    private double scale = Config.MAP_ZOOMS[zoomIndex];
    private boolean initialized;

    /** World point that stays under {@link #anchorScreenX}/{@link #anchorScreenY} while the zoom animates. */
    private double anchorWorldX, anchorWorldZ, anchorScreenX, anchorScreenY;
    private boolean zooming;

    /** Map lighting: auto, day or night, switched in turn. */
    private IconButton lightButton;
    private IconButton caveButton;
    /** The map's mode: 2D, 3D, topography or biomes, picked from a list. */
    private IconButton modesButton;
    private IconButton gridButton;
    /** Open the map at the player every time, or where it was closed. */
    private IconButton followButton;
    private IconButton mobsButton;
    /** 3D (isometric) view on or off (turned with Q / E). */
    /** Saves the whole map (flat or 3D) as a zoomable picture. */
    private IconButton exportButton;
    /** Add-on layers (ores, fluids, claims, power failures); null when none of those mods is installed. */
    private IconButton addonsButton;
    /** Online teammates, to jump to them; shown only while there are some. */
    private IconButton teamButton;
    /** Search of biomes, ore veins, fluids or power failures; kept between openings of the map. */
    private static String searchText = "";
    private FlatTextField searchField;
    private IconButton helpButton;

    /** Chunks passed while dragging with Ctrl/Shift in the claims layer, applied on release. */
    private final Set<Long> claimSelection = new LinkedHashSet<>();
    private int claimButton = -1;
    private int claimAction;
    /** Chunk where the drag started: the selection is the rectangle from it to the chunk under the mouse. */
    private int claimStartX, claimStartZ, claimEndX = Integer.MIN_VALUE, claimEndZ;

    /** Dimension picker under the title; null when closed. */
    private List<MapManager.SavedDimension> dimensionList;
    private int dimensionListX, dimensionListWidth;
    /** Title bounds in the header, clickable to open the dimension picker. */
    private int titleX0, titleX1;

    /** Right click menu; null when closed. */
    private List<MenuEntry> menu;
    private int menuX, menuY;
    private int menuKind;
    private int menuWidth = MENU_WIDTH;
    private boolean draggingCaveSlider;

    /** One line of the right click menu. */
    private static final class MenuEntry {

        final String label;
        final boolean enabled;
        final Runnable action;
        /** Checkbox state for a toggle (the menu then stays open), null for a plain entry. */
        final Boolean checked;

        MenuEntry(String label, boolean enabled, Runnable action) {
            this(label, enabled, action, null);
        }

        MenuEntry(String label, boolean enabled, Runnable action, Boolean checked) {
            this.label = label;
            this.enabled = enabled;
            this.action = action;
            this.checked = checked;
        }
    }

    private boolean dragging;
    private int lastRawMouseX;
    private int lastRawMouseY;
    private long lastFrameNanos;
    private int ticks;
    /** Waypoint to show in the middle of the map when it opens, or null. */
    private Waypoint focus;

    /** The world map opened on the waypoint, in its dimension. */
    public static GuiWorldMap showing(Waypoint waypoint) {
        GuiWorldMap map = new GuiWorldMap();
        map.focus = waypoint;
        return map;
    }

    @Override
    public void initGui() {
        super.initGui();
        checkWelcome();
        // The chunk loading view always shows the surface, whatever the cave mode.
        MapManager.INSTANCE.setSurfaceView(chunkloadView || regionloadView);
        if (!initialized && mc.thePlayer != null) {
            // Only on first open, not when the window is resized: back where the map was closed (unless it follows
            // the player), or at the player.
            initialized = true;
            MapManager.INSTANCE.stopViewing();
            if (Config.mapFollowPlayer || !restoreView()) {
                centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
            }
            if (focus != null) {
                centerOnWaypoint(focus);
                focus = null;
            }
            if (Mods.isVisualProspectingLoaded()) {
                ProspectingLayer.onOpenMap();
            }
        }
        lastFrameNanos = System.nanoTime();
        buttonList.clear();
        // Header: icons with tooltips. Left: settings, waypoints, add-on layers; right (before the zoom text):
        // mobs, grid, biomes, caves, day, night.
        int x = 4;
        x = addIconButton(new IconButton(ID_SETTINGS, x, 4, Icons.SETTINGS, I18n.format("wayfarmap.gui.settings")), x);
        // The others can be hidden in the settings; the settings button and the dimension title always stay.
        if (Config.isMapButtonShown("waypoints")) {
            x = addIconButton(
                new IconButton(ID_WAYPOINTS, x, 4, Icons.WAYPOINTS, I18n.format("wayfarmap.gui.waypoints")),
                x);
        }
        if (Config.isMapButtonShown("stats")) {
            x = addIconButton(new IconButton(ID_STATS, x, 4, Icons.STATS, I18n.format("wayfarmap.gui.stats")), x);
        }
        if (Config.isMapButtonShown("clean")) {
            x = addIconButton(new IconButton(ID_CLEAN, x, 4, Icons.CLEAN, I18n.format("wayfarmap.gui.clean")), x);
        }
        exportButton = new IconButton(ID_EXPORT, x, 4, Icons.CAMERA, I18n.format("wayfarmap.gui.export"));
        if (Config.isMapButtonShown("export")) {
            x = addIconButton(exportButton, x);
        }
        addonsButton = null;
        if (Config.isMapButtonShown("addons") && (Mods.isVisualProspectingLoaded() || Mods.isClaimsAvailable()
            || Mods.isPowerfailsAvailable()
            || Mods.isThaumcraftNodesAvailable())) {
            addonsButton = new IconButton(ID_ADDONS, x, 4, Icons.ADDONS, I18n.format("wayfarmap.gui.addons"));
            addIconButton(addonsButton, x);
        }

        lightButton = new IconButton(ID_LIGHT, 0, 4, Icons.DAY_NIGHT, "");
        caveButton = new IconButton(ID_CAVES, 0, 4, Icons.CAVES, "");
        modesButton = new IconButton(ID_MODES, 0, 4, Icons.FLAT, "");
        gridButton = new IconButton(ID_GRID, 0, 4, Icons.GRID, I18n.format("wayfarmap.gui.grid"));
        followButton = new IconButton(ID_FOLLOW, 0, 4, Icons.FOLLOW, I18n.format("wayfarmap.gui.follow"));
        mobsButton = new IconButton(ID_MOBS, 0, 4, Icons.MOBS, "");
        teamButton = new IconButton(ID_TEAM, 0, 4, Icons.TEAM, I18n.format("wayfarmap.gui.team"));
        teamButton.visible = teamShown();
        followButton.visible = Config.isMapButtonShown("follow");
        lightButton.visible = Config.isMapButtonShown("light");
        caveButton.visible = Config.isMapButtonShown("caves");
        modesButton.visible = Config.isMapButtonShown("modes");
        gridButton.visible = Config.isMapButtonShown("grid");
        mobsButton.visible = Config.isMapButtonShown("mobs");
        for (IconButton button : rightButtons()) {
            buttonList.add(button);
        }
        updateLightButtons();
        menu = null;
        dimensionList = null;

        // Top right corner: closes the map, like Esc.
        buttonList.add(new IconButton(ID_CLOSE, width - 24, 4, Icons.CLOSE, I18n.format("wayfarmap.gui.close")));

        // Bottom right: the help screen with every feature explained.
        helpButton = new IconButton(
            ID_HELP,
            width - 22,
            height - FOOTER_HEIGHT + 1,
            Icons.HELP,
            I18n.format("wayfarmap.gui.help_button"));
        helpButton.setHeight(13);
        helpButton.visible = Config.isMapButtonShown("help");
        buttonList.add(helpButton);

        Keyboard.enableRepeatEvents(true);
        searchField = new FlatTextField(fontRendererObj, width / 2 - 90, HEADER_HEIGHT + 4, 180, 14)
            .setHint(I18n.format("wayfarmap.gui.search_hint"));
        searchField.setMaxStringLength(40);
        searchField.setText(searchText);
        applySearch();
    }

    /** The teammates button: while some are online, unless hidden in the settings. */
    private boolean teamShown() {
        return Config.isMapButtonShown("team") && !TeamMates.INSTANCE.all()
            .isEmpty();
    }

    /** Buttons on the right of the header, from the right edge to the left. */
    private IconButton[] rightButtons() {
        return new IconButton[] { followButton, lightButton, caveButton, modesButton, gridButton, mobsButton,
            teamButton };
    }

    /** Places the right header buttons next to each other, leaving out hidden ones. */
    private void layoutRightButtons() {
        // Left of the zoom text, which is left of the close button.
        int right = width - 58;
        for (IconButton button : rightButtons()) {
            if (!button.visible) {
                continue;
            }
            right -= button.getWidth();
            button.xPosition = right;
            right -= 3;
        }
    }

    // ---------------------------------------------------------------- 3D view

    /** True when the map is drawn in 3D: the surface (biome view and cave layers stay flat). */
    private boolean isoShown() {
        return Config.isometric && !columnViewShown() && MapManager.INSTANCE.getViewCaveLayer() < 0;
    }

    private static IsoProjection isoProjection() {
        return IsoProjection.of(Config.isoRotation);
    }

    /** World move {dx, dz} that shows as the screen move (ox, oy); in 3D on the plane at sea level. */
    private double[] screenToWorldOffset(double ox, double oy) {
        if (isoShown()) {
            IsoProjection p = isoProjection();
            return p.ground(ox / scale, oy / scale / IsoProjection.SIN);
        }
        return new double[] { ox / scale, oy / scale };
    }

    /** Screen position {x, y} of a world point. */
    private double[] toScreen(double x, double y, double z) {
        if (isoShown()) {
            IsoProjection p = isoProjection();
            return new double[] { width / 2.0 + (p.u(x, z) - p.u(centerX, centerZ)) * scale,
                height / 2.0 + (p.v(x, y, z) - p.v(centerX, IsoProjection.REFERENCE_Y, centerZ)) * scale };
        }
        return new double[] { width / 2.0 + (x - centerX) * scale, height / 2.0 + (z - centerZ) * scale };
    }

    /**
     * Block under the screen point: {x, y, z}, where y is only known in 3D (the block seen there; -1 otherwise).
     * In 3D where nothing is drawn, the point at sea level.
     */
    private int[] blockAt(int mouseX, int mouseY) {
        if (isoShown()) {
            int[] hit = IsoMap.INSTANCE.pick(
                viewDimension(),
                Config.isoRotation,
                centerX,
                centerZ,
                scale,
                ScaledScreen.currentFactor(),
                mouseX - width / 2.0,
                mouseY - height / 2.0);
            if (hit != null) {
                return hit;
            }
            IsoProjection p = isoProjection();
            double[] ground = p.unproject(
                p.u(centerX, centerZ) + (mouseX - width / 2.0) / scale,
                p.v(centerX, IsoProjection.REFERENCE_Y, centerZ) + (mouseY - height / 2.0) / scale,
                IsoProjection.REFERENCE_Y);
            return new int[] { MathHelper.floor_double(ground[0]), -1, MathHelper.floor_double(ground[1]) };
        }
        return new int[] { MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale), -1,
            MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) };
    }

    /** Puts a world point in the middle of the screen (in 3D the point itself, not the ground below it). */
    private void centerOn(double x, double y, double z) {
        if (isoShown()) {
            IsoProjection p = isoProjection();
            double toward = p.toward(x, z) + (IsoProjection.REFERENCE_Y - y) * IsoProjection.COS / IsoProjection.SIN;
            double[] center = p.ground(p.u(x, z), toward);
            centerX = center[0];
            centerZ = center[1];
        } else {
            centerX = x;
            centerZ = z;
        }
    }

    /** The search field shows up in biome view and with the ore vein or fluid layer (not in 3D). */
    private boolean searchAvailable() {
        return !isoShown() && (biomeViewShown() || prospectingLayerShown() || powerfailsShown() || nodesShown());
    }

    private static boolean nodesShown() {
        return Config.showThaumcraftNodes && Mods.isThaumcraftNodesAvailable();
    }

    private static boolean powerfailsShown() {
        return Config.showPowerfails && Mods.isPowerfailsAvailable();
    }

    private boolean biomeViewShown() {
        return Config.mapDisplayMode == Config.DISPLAY_BIOMES;
    }

    /** Biomes or topography: drawn for whole columns from the surface, so without cave layers and not in 3D. */
    private boolean columnViewShown() {
        return biomeViewShown() || Topography.isShown();
    }

    private static boolean prospectingLayerShown() {
        return Mods.isVisualProspectingLoaded() && (Config.showOreVeins || Config.showUndergroundFluids);
    }

    /** Sends the search text to the layers that are shown; the others search for nothing. */
    private void applySearch() {
        String query = searchAvailable() ? searchText : "";
        BiomeHighlight.setQuery(biomeViewShown() ? query : "");
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.setSearch(prospectingLayerShown() ? query : "");
        }
        if (Mods.isPowerfailsAvailable()) {
            PowerfailLayer.setSearch(powerfailsShown() ? query : "");
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            ThaumcraftNodes.setSearch(nodesShown() ? query : "");
        }
    }

    private int addIconButton(IconButton button, int x) {
        buttonList.add(button);
        return x + button.getWidth() + 3;
    }

    /** Menu under the add-ons button: a checkbox per layer; it stays open to switch several. */
    private void openAddonsMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        if (Mods.isVisualProspectingLoaded()) {
            entries.add(addonToggle("wayfarmap.gui.ores", Config.showOreVeins, Config::toggleOreVeins));
            entries.add(
                addonToggle("wayfarmap.gui.fluids", Config.showUndergroundFluids, Config::toggleUndergroundFluids));
        }
        if (Mods.isClaimsAvailable()) {
            entries.add(addonToggle("wayfarmap.gui.claims", Config.showClaims, () -> {
                Config.toggleClaims();
                if (Config.showClaims) {
                    ClaimsLayer.onShow();
                }
            }));
        }
        if (Mods.isPowerfailsAvailable()) {
            entries.add(addonToggle("wayfarmap.gui.powerfails", Config.showPowerfails, Config::togglePowerfails));
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            entries.add(addonToggle("wayfarmap.gui.nodes", Config.showThaumcraftNodes, Config::toggleThaumcraftNodes));
        }
        menu = entries;
        menuKind = MENU_ADDONS;
        menuWidth = MENU_WIDTH;
        menuX = Math.max(2, Math.min(addonsButton.xPosition, width - MENU_WIDTH - 2));
        menuY = addonsButton.yPosition + 18;
    }

    private MenuEntry addonToggle(String key, boolean on, Runnable toggle) {
        return new MenuEntry(I18n.format(key), true, () -> {
            toggle.run();
            updateLightButtons();
        }, on);
    }

    /** "Mobs: neutral, friendly, pets, hostile, players", or that none are shown. */
    private static String mobsButtonText() {
        List<String> shown = new ArrayList<>();
        if (Config.showPassiveMobs) {
            shown.add(I18n.format("wayfarmap.gui.mobs.neutral"));
        }
        if (Config.showOtherEntities) {
            shown.add(I18n.format("wayfarmap.gui.mobs.friendly"));
        }
        if (Config.showPets) {
            shown.add(I18n.format("wayfarmap.gui.mobs.pets"));
        }
        if (Config.showHostileMobs) {
            shown.add(I18n.format("wayfarmap.gui.mobs.hostile"));
        }
        if (Config.showOtherPlayers) {
            shown.add(I18n.format("wayfarmap.gui.mobs.players"));
        }
        return I18n.format("wayfarmap.gui.mobs") + ": "
            + (shown.isEmpty() ? I18n.format("wayfarmap.gui.mobs.none") : String.join(", ", shown));
    }

    /** Menu under the "Mobs" button: a checkbox for each kind of mob and for players; all off shows none. */
    private void openMobsMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(addonToggle("wayfarmap.gui.mobs.menu.neutral", Config.showPassiveMobs, Config::toggleNeutralMobs));
        entries
            .add(addonToggle("wayfarmap.gui.mobs.menu.friendly", Config.showOtherEntities, Config::toggleFriendlyMobs));
        entries.add(addonToggle("wayfarmap.gui.mobs.menu.pets", Config.showPets, Config::togglePets));
        entries.add(addonToggle("wayfarmap.gui.mobs.menu.hostile", Config.showHostileMobs, Config::toggleHostileMobs));
        entries
            .add(addonToggle("wayfarmap.gui.mobs.menu.players", Config.showOtherPlayers, Config::toggleOtherPlayers));
        menu = entries;
        menuKind = MENU_MOBS;
        menuWidth = MENU_WIDTH;
        menuX = Math.max(2, Math.min(mobsButton.xPosition, width - MENU_WIDTH - 2));
        menuY = mobsButton.yPosition + 18;
    }

    /**
     * Menu under the export button: saves the whole map as it is shown (flat, or 3D at a chosen detail) into a folder
     * a browser opens zoomable down to single blocks; while an export runs, stops it.
     */
    private void openExportMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        if (MapExport.running()) {
            String status = MapExport.statusText();
            entries.add(new MenuEntry(status == null ? "" : status, false, () -> {}));
            entries.add(new MenuEntry(I18n.format("wayfarmap.export.cancel"), true, MapExport::cancel));
        } else if (isoShown()) {
            int dimensionId = viewDimension();
            for (int level = 0; level <= IsoExport.MAX_LEVEL; level++) {
                IsoExport export = IsoExport.of(dimensionId, Config.isoRotation, level, exportNight);
                if (export == null) {
                    continue;
                }
                Set<Long> tiles = export.estimatedTiles();
                // Drawing time grows with the pixels: a tile of 256x256 takes about the same at any detail.
                double minutes = tiles.size() * EXPORT_SECONDS_PER_TILE / export.threads() / 60;
                String time = minutes < 1 ? I18n.format("wayfarmap.export.under_minute")
                    : I18n.format("wayfarmap.export.minutes", (int) Math.ceil(minutes));
                long[] picture = TilePyramid.pictureSize(tiles, export.tileSize());
                String label = I18n.format(
                    "wayfarmap.export.iso_level",
                    (int) export.pixelsPerBlock(),
                    picture[0] + "\u00D7" + picture[1],
                    time);
                final int chosen = level;
                entries.add(new MenuEntry(label, !tiles.isEmpty(), () -> startIsoExport(dimensionId, chosen)));
            }
            entries.add(
                new MenuEntry(
                    I18n.format("wayfarmap.export.night"),
                    true,
                    () -> { exportNight = !exportNight; },
                    exportNight));
        } else {
            MapDimension map = MapManager.INSTANCE.getViewMap();
            Set<Long> regions = map == null ? Collections.<Long>emptySet() : new FlatExport(map, 1).tiles();
            long[] picture = TilePyramid.pictureSize(regions, MapRegion.SIZE);
            for (int blockPixels = 1; blockPixels <= 16; blockPixels *= 2) {
                final int chosen = blockPixels;
                String label = I18n.format(
                    "wayfarmap.export.flat_scale",
                    blockPixels,
                    picture[0] * blockPixels + "\u00D7" + picture[1] * blockPixels);
                entries.add(new MenuEntry(label, !regions.isEmpty(), () -> startFlatExport(chosen)));
            }
        }
        menu = entries;
        menuKind = MENU_EXPORT;
        menuWidth = EXPORT_MENU_WIDTH;
        menuX = Math.max(2, Math.min(exportButton.xPosition, width - EXPORT_MENU_WIDTH - 2));
        menuY = exportButton.yPosition + 18;
    }

    /** Name of the export: world and dimension. */
    private String exportName() {
        File world = MapManager.INSTANCE.getWorldDirectory();
        return (world == null ? "map" : world.getName()) + "_" + MapManager.INSTANCE.getViewedDimensionName();
    }

    /** @param blockPixels pixels per block of the saved map */
    private void startFlatExport(int blockPixels) {
        MapDimension map = MapManager.INSTANCE.getViewMap();
        if (map == null) {
            return;
        }
        String what;
        int caveLayer = MapManager.INSTANCE.getViewCaveLayer();
        if (biomeViewShown()) {
            what = "biomes";
        } else if (caveLayer >= 0) {
            what = "caves_" + caveLayer * 16 + "-" + (caveLayer * 16 + 15);
        } else {
            what = "2d";
        }
        what += "_" + blockPixels + "px";
        TilePyramid.Info info = new TilePyramid.Info();
        info.title = MapManager.INSTANCE.getViewedDimensionName() + " (" + what + ")";
        info.mode = "2d";
        info.pixelsPerBlock = blockPixels;
        // Up to 64 screen pixels per block, like the closest zoom of the map.
        info.maxZoom = Math.max(4, 64.0 / blockPixels);
        MapExport.start(new FlatExport(map, blockPixels), info, exportName() + "_" + what);
        chatExportStarted();
    }

    private void startIsoExport(int dimensionId, int level) {
        IsoExport export = IsoExport.of(dimensionId, Config.isoRotation, level, exportNight);
        if (export == null) {
            return;
        }
        String what = "3d_" + (int) export.pixelsPerBlock() + "px" + (exportNight ? "_night" : "");
        TilePyramid.Info info = new TilePyramid.Info();
        info.title = MapManager.INSTANCE.getViewedDimensionName() + " (3D)";
        info.mode = "3d";
        info.pixelsPerBlock = export.pixelsPerBlock();
        // Up to 128 screen pixels per block, and always a few times closer than the picture itself.
        info.maxZoom = Math.max(4, 128 / export.pixelsPerBlock());
        MapExport.start(export, info, exportName() + "_" + what);
        chatExportStarted();
    }

    private void chatExportStarted() {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(I18n.format("wayfarmap.export.started")));
        }
    }

    private static String caveButtonText() {
        return I18n.format("wayfarmap.gui.caves") + ": "
            + I18n.format("wayfarmap.option.map.caveMode." + CAVE_MODE_KEYS[Config.caveMode]);
    }

    /** The forced mode is highlighted; with both off the map follows the time of day. */
    private void updateLightButtons() {
        if (searchField != null) {
            // The layers shown may have changed; send them the search.
            applySearch();
        }
        // Lighting: the sun or the moon when fixed, a half dark circle with a dot when automatic.
        int light = Config.mapLightMode >= 0 && Config.mapLightMode < LIGHT_MODE_KEYS.length ? Config.mapLightMode
            : Config.LIGHT_AUTO;
        lightButton.icon = light == Config.LIGHT_DAY ? Icons.DAY
            : light == Config.LIGHT_NIGHT ? Icons.NIGHT : Icons.DAY_NIGHT;
        lightButton.active = light != Config.LIGHT_AUTO;
        lightButton.badge = light == Config.LIGHT_AUTO ? Theme.ACCENT : 0;
        lightButton.tooltip = I18n.format("wayfarmap.option.map.lightMode") + ": "
            + I18n.format("wayfarmap.option.map.lightMode." + LIGHT_MODE_KEYS[light]);
        // Caves: highlighted when on, a dot when automatic, dimmed when off.
        caveButton.active = Config.caveMode == Config.CAVES_ON;
        caveButton.dim = Config.caveMode == Config.CAVES_OFF;
        caveButton.badge = Config.caveMode == Config.CAVES_AUTO ? Theme.ACCENT : 0;
        caveButton.tooltip = caveButtonText();
        int mode = currentMode();
        modesButton.icon = MODE_ICONS[mode];
        modesButton.active = mode != MODE_FLAT;
        modesButton.tooltip = I18n.format("wayfarmap.gui.modes") + ": "
            + I18n.format("wayfarmap.gui.modes." + MODE_KEYS[mode]);
        gridButton.active = Config.chunkGrid;
        followButton.active = Config.mapFollowPlayer;
        layoutRightButtons();
        // Mobs: highlighted while some are hidden, dim when none are shown; a dot when only hostile or only
        // peaceful mobs are left.
        boolean peaceful = Config.showPassiveMobs || Config.showOtherEntities || Config.showPets;
        boolean hostile = Config.showHostileMobs;
        mobsButton.active = !Config.allMobsShown();
        mobsButton.dim = Config.noMobsShown();
        mobsButton.badge = peaceful && !hostile ? Theme.SUCCESS : hostile && !peaceful ? Theme.DANGER : 0;
        mobsButton.tooltip = mobsButtonText();
        if (addonsButton != null) {
            addonsButton.active = prospectingLayerShown() || Config.showClaims && Mods.isClaimsAvailable()
                || powerfailsShown()
                || nodesShown();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_WAYPOINTS) {
            mc.displayGuiScreen(new GuiWaypointList(this));
        } else if (button.id == ID_STATS) {
            mc.displayGuiScreen(new GuiMapStats(this));
        } else if (button.id == ID_CLEAN) {
            mc.displayGuiScreen(new GuiMapClean(this));
        } else if (button.id == ID_TEAM) {
            openTeamMenu();
        } else if (button.id == ID_ADDONS) {
            openAddonsMenu();
        } else if (button.id == ID_FOLLOW) {
            Config.toggleFollowPlayer();
            updateLightButtons();
            if (Config.mapFollowPlayer) {
                // Turned on: show the player now, as the map will be every time it opens.
                showDimension(mc.theWorld.provider.dimensionId);
                centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
                zooming = false;
            }
        } else if (button.id == ID_GRID) {
            Config.toggleChunkGrid();
            updateLightButtons();
        } else if (button.id == ID_MODES) {
            openModesMenu();
        } else if (button.id == ID_EXPORT) {
            openExportMenu();
        } else if (button.id == ID_CAVES) {
            Config.cycleCaveMode();
            updateLightButtons();
        } else if (button.id == ID_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
        } else if (button.id == ID_MOBS) {
            openMobsMenu();
        } else if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        } else if (button.id == ID_HELP) {
            mc.displayGuiScreen(new GuiHelp(this));
        } else if (button.id == ID_LIGHT) {
            // Auto -> day -> night -> auto.
            Config.setMapLightMode(
                Config.mapLightMode == Config.LIGHT_AUTO ? Config.LIGHT_DAY
                    : Config.mapLightMode == Config.LIGHT_DAY ? Config.LIGHT_NIGHT : Config.LIGHT_AUTO);
            updateLightButtons();
        }
    }

    /** Moves and zooms the view once per frame, so panning is as smooth as the frame rate allows. */
    private void updateView() {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;

        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                dragging = false;
            } else {
                // Raw window coordinates give sub-GUI-pixel precision at any GUI scale.
                int rawX = Mouse.getX();
                int rawY = Mouse.getY();
                double dx = (rawX - lastRawMouseX) * (double) width / mc.displayWidth;
                double dy = -(rawY - lastRawMouseY) * (double) height / mc.displayHeight;
                lastRawMouseX = rawX;
                lastRawMouseY = rawY;
                double[] move = screenToWorldOffset(dx, dy);
                centerX -= move[0];
                centerZ -= move[1];
                if (zooming) {
                    anchorWorldX -= move[0];
                    anchorWorldZ -= move[1];
                }
            }
        }

        if (zooming) {
            double target = Config.MAP_ZOOMS[zoomIndex];
            // Interpolate in log space so every zoom step feels equally fast.
            double t = 1.0 - Math.exp(-ZOOM_SPEED * seconds);
            double logScale = Math.log(scale) + (Math.log(target) - Math.log(scale)) * t;
            scale = Math.exp(logScale);
            if (Math.abs(scale - target) < target * 0.002) {
                scale = target;
                zooming = false;
            }
            double[] offset = screenToWorldOffset(anchorScreenX - width / 2.0, anchorScreenY - height / 2.0);
            centerX = anchorWorldX - offset[0];
            centerZ = anchorWorldZ - offset[1];
        }
    }

    /** Switches between the flat map and the 3D view, keeping the same place in the middle. */
    /**
     * Modes of the map, in the order of the list: flat in block colors, 3D, flat without grass and flowers,
     * topography, biomes.
     */
    private static final int MODE_FLAT = 0, MODE_ISO = 1, MODE_BARE = 2, MODE_TOPO = 3, MODE_BIOMES = 4,
        MODE_CHUNKLOAD = 5, MODE_REGIONLOAD = 6;
    private static final String[] MODE_KEYS = { "flat", "iso", "bare", "topo", "biomes", "chunkload", "regionload" };
    private static final String[][] MODE_ICONS = { Icons.FLAT, Icons.ISO, Icons.PLANTS, Icons.TOPO, Icons.BIOMES,
        Icons.CHUNKLOAD, Icons.REGIONLOAD };
    /** The chunk loading view: the flat map with the chunks on it and those picked to be loaded. */
    private static boolean chunkloadView;
    /** Its region loading variant: also the chunks saved in the world, loaded from it as they are. */
    private static boolean regionloadView;

    private static int currentMode() {
        if (Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            return MODE_BIOMES;
        }
        if (Config.mapDisplayMode == Config.DISPLAY_TOPO) {
            return MODE_TOPO;
        }
        if (Config.isometric) {
            return MODE_ISO;
        }
        if (regionloadView) {
            return MODE_REGIONLOAD;
        }
        return chunkloadView ? MODE_CHUNKLOAD : Config.showPlants ? MODE_FLAT : MODE_BARE;
    }

    /**
     * Menu under the modes button: 2D map, 3D map, 2D map without plants, topography, biomes; the current one is
     * marked.
     */
    private void openModesMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        int current = currentMode();
        for (int mode = 0; mode < MODE_KEYS.length; mode++) {
            if ((mode == MODE_CHUNKLOAD || mode == MODE_REGIONLOAD) && !ChunkLoadView.isAllowed()) {
                // Loading from the map is for operators: the modes aren't offered to others.
                continue;
            }
            final int value = mode;
            String label = (mode == current ? "\u25CF " : "   ")
                + I18n.format("wayfarmap.gui.modes." + MODE_KEYS[mode]);
            entries.add(new MenuEntry(label, true, () -> setMode(value)));
        }
        menu = entries;
        menuKind = MENU_MODES;
        menuWidth = MENU_WIDTH;
        menuX = Math.max(2, Math.min(modesButton.xPosition, width - MENU_WIDTH - 2));
        menuY = modesButton.yPosition + 18;
    }

    /** Switches the map's mode, keeping the point in the middle of the screen there (2D and 3D place it apart). */
    private void setMode(int mode) {
        double[] middle = middlePoint();
        int display = mode == MODE_TOPO ? Config.DISPLAY_TOPO
            : mode == MODE_BIOMES ? Config.DISPLAY_BIOMES : Config.DISPLAY_BLOCKS;
        Config.setMapMode(mode == MODE_ISO, display);
        // The map without grass and flowers is kept along with the surface: switching only picks the one drawn.
        Config.setShowPlants(mode != MODE_BARE);
        chunkloadView = mode == MODE_CHUNKLOAD;
        regionloadView = mode == MODE_REGIONLOAD;
        MapManager.INSTANCE.setSurfaceView(chunkloadView || regionloadView);
        centerOn(middle[0], middle[1], middle[2]);
        zooming = false;
        updateLightButtons();
        applySearch();
    }

    /** Turns the 3D view by quarters around the point in the middle of the screen. */
    private void rotateIso(int quarters) {
        double[] middle = middlePoint();
        Config.rotateIso(quarters);
        centerOn(middle[0], middle[1], middle[2]);
        zooming = false;
    }

    /**
     * The world point in the middle of the screen: in 3D the block seen there, on the flat map the ground from its
     * heights (sea level where unknown).
     */
    private double[] middlePoint() {
        if (isoShown()) {
            int[] block = blockAt(width / 2, height / 2);
            if (block[1] >= 0) {
                return new double[] { block[0] + 0.5, block[1] + 1, block[2] + 0.5 };
            }
            return new double[] { centerX, IsoProjection.REFERENCE_Y, centerZ };
        }
        int ground = MapManager.INSTANCE
            .getViewSurfaceHeight(MathHelper.floor_double(centerX), MathHelper.floor_double(centerZ));
        return new double[] { centerX, ground > 0 ? ground : IsoProjection.REFERENCE_Y, centerZ };
    }

    // ---------------------------------------------------------------- welcome window

    /** Written once the welcome window is closed: it is never shown again. */
    private static final String WELCOME_FILE = "welcome-shown";
    private static final int WELCOME_WIDTH = 240, WELCOME_HEIGHT = 124;
    /** The welcome window is open: the map takes no input until it is closed. */
    private boolean welcome;

    private File welcomeFile() {
        return new File(new File(mc.mcDataDir, "wayfarmap"), WELCOME_FILE);
    }

    /** Opens the welcome window the first time the map is opened after installing the mod. */
    private void checkWelcome() {
        welcome = !welcomeFile().exists();
    }

    private void closeWelcome() {
        welcome = false;
        File file = welcomeFile();
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            file.createNewFile();
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not write " + file, e);
        }
    }

    private int welcomeLeft() {
        return (width - WELCOME_WIDTH) / 2;
    }

    private int welcomeTop() {
        return (height - WELCOME_HEIGHT) / 2;
    }

    /** The window's button: {x0, y0, x1, y1}. */
    private int[] welcomeButton() {
        int x0 = welcomeLeft() + WELCOME_WIDTH / 2 - 40, y0 = welcomeTop() + WELCOME_HEIGHT - 26;
        return new int[] { x0, y0, x0 + 80, y0 + 18 };
    }

    /** The mod's name, what it is, and where its help is; the help button is outlined meanwhile. */
    private void drawWelcome(int mouseX, int mouseY) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        if (helpButton != null && helpButton.visible) {
            // Pulsing outline around the help button the text points to.
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 250.0);
            int alpha = 0x60 + (int) (0x9F * pulse);
            int x0 = helpButton.xPosition - 2, y0 = helpButton.yPosition - 2;
            int color = alpha << 24 | (Theme.ACCENT & 0xFFFFFF);
            Theme.outline(x0, y0, x0 + helpButton.getWidth() + 4, y0 + 13 + 4, color);
        }
        int left = welcomeLeft(), top = welcomeTop();
        Theme.panel(left, top, left + WELCOME_WIDTH, top + WELCOME_HEIGHT);
        Theme.centered(fontRendererObj, "Wayfarer's Map", left + WELCOME_WIDTH / 2, top + 10, Theme.ACCENT);
        Theme.fill(left + 10, top + 22, left + WELCOME_WIDTH - 10, top + 23, Theme.ACCENT_DIM);
        String text = I18n.format("wayfarmap.welcome.text");
        List<?> lines = fontRendererObj.listFormattedStringToWidth(text, WELCOME_WIDTH - 24);
        for (int i = 0; i < lines.size(); i++) {
            Theme.text(fontRendererObj, String.valueOf(lines.get(i)), left + 12, top + 30 + i * 10, Theme.TEXT);
        }
        int[] b = welcomeButton();
        boolean hovered = Theme.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
        Theme.fill(b[0], b[1], b[2], b[3], hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
        Theme.outline(b[0], b[1], b[2], b[3], hovered ? Theme.ACCENT : Theme.BORDER);
        Theme.centered(fontRendererObj, I18n.format("wayfarmap.welcome.ok"), (b[0] + b[2]) / 2, b[1] + 5, Theme.TEXT);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        drawMap(mouseX, mouseY, partialTicks);
        if (welcome) {
            drawWelcome(mouseX, mouseY);
        }
    }

    private void drawMap(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF0C0E11);

        MapDimension dimension = MapManager.INSTANCE.getViewMap();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScaled(mouseX, mouseY, partialTicks);
            return;
        }
        // Teammates come and go while the map is open.
        boolean teammates = teamShown();
        if (teammates != teamButton.visible) {
            teamButton.visible = teammates;
            layoutRightButtons();
        }
        if (!teamButton.visible && menuKind == MENU_TEAM) {
            menu = null;
        }

        updateView();
        // The dimension shown: the player's, or another one picked from the title.
        int dimensionId = viewDimension();
        boolean otherDimension = MapManager.INSTANCE.isViewingOtherDimension();
        boolean iso = isoShown();
        if (iso) {
            // 3D: the terrain; the mobs and the player come below. The add-on layers and the grid are flat only.
            IsoMap.INSTANCE.draw(
                dimensionId,
                Config.isoRotation,
                centerX,
                centerZ,
                scale,
                ScaledScreen.currentFactor(),
                0,
                0,
                width,
                height);
        } else {
            drawFlatLayers(dimension, dimensionId, otherDimension, mouseX, mouseY, partialTicks);
        }
        // Teammates always, also in another dimension being looked at.
        if (!chunkloadShown()) {
            MapDrawer
                .drawTeammates(mc, dimensionId, this::toScreen, scale, 0, 0, width, height, partialTicks, 8f, true);
            drawWaypoints(mouseX, mouseY);
        }

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double py = mc.thePlayer.prevPosY + (mc.thePlayer.posY - mc.thePlayer.prevPosY) * partialTicks
            - mc.thePlayer.yOffset;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double[] playerScreen = toScreen(px, py, pz);
        double playerScreenX = playerScreen[0], playerScreenY = playerScreen[1];
        boolean playerOnScreen = !otherDimension && playerScreenX >= 0
            && playerScreenY >= 0
            && playerScreenX <= width
            && playerScreenY <= height;
        // 3D: the mobs in sight and the player as their 3D models (mobs of the player's own dimension only).
        boolean model = iso && !otherDimension
            && IsoEntityDrawer.draw(
                mc,
                this::toScreen,
                scale,
                isoProjection(),
                width,
                height,
                partialTicks,
                true,
                playerOnScreen && Config.isoPlayerModel);
        if (playerOnScreen) {
            float yaw = mc.thePlayer.prevRotationYaw
                + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;
            // Drawn as the player itself, or else as the arrow.
            if (!model && iso) {
                // Where one block ahead of the player lands on the screen gives the arrow's direction.
                double r = Math.toRadians(yaw);
                double[] ahead = toScreen(px - Math.sin(r), py, pz + Math.cos(r));
                yaw = (float) Math.toDegrees(Math.atan2(-(ahead[0] - playerScreenX), ahead[1] - playerScreenY));
            }
            if (!model) {
                MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, yaw, 5f);
            }
        }
        drawOverlay(mouseX, mouseY, partialTicks, dimension, dimensionId, otherDimension, iso);
    }

    /** The flat map and everything drawn on it (not in 3D). */
    private void drawFlatLayers(MapDimension dimension, int dimensionId, boolean otherDimension, int mouseX, int mouseY,
        float partialTicks) {
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
        if (chunkloadShown()) {
            // Only the map and its chunks: no layers, grid or mobs.
            updatePick(mouseX, mouseY);
            ChunkLoadView.draw(
                MapManager.INSTANCE.getViewMap(),
                dimensionId,
                centerX,
                centerZ,
                scale,
                0,
                0,
                width,
                height,
                pickSelection,
                pickRemove,
                regionloadView);
            return;
        }
        if (Topography.isShown()) {
            Topography.draw(dimension, centerX, centerZ, scale, 0, 0, width, height);
        }
        boolean prospecting = Mods.isVisualProspectingLoaded();
        // Search: gray over everything that doesn't match; matching biomes keep their color and get an outline.
        if (biomeViewShown() && BiomeHighlight.isActive()) {
            BiomeHighlight.draw(MapManager.INSTANCE.getViewBiomeMap(), centerX, centerZ, scale, 0, 0, width, height);
        } else if (prospecting && prospectingLayerShown() && ProspectingLayer.isSearchActive()) {
            Theme.fill(0, 0, width, height, 0xB0202428);
        }
        if (Config.chunkGrid) {
            MapDrawer.drawChunkGrid(centerX, centerZ, scale, 0, 0, width, height);
        }
        if (claimsShown()) {
            updateClaimPaint(mouseX, mouseY);
            ClaimsLayer.draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, claimSelection, claimAction);
        }
        if (prospecting && Config.showUndergroundFluids) {
            ProspectingLayer.drawFluids(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false);
        }
        if (prospecting && Config.showOreVeins) {
            ProspectingLayer
                .drawOreVeins(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY);
        }
        if (nodesShown()) {
            ThaumcraftNodes.draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY);
        }
        if (powerfailsShown()) {
            PowerfailLayer.draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY);
        }
        if (!otherDimension) {
            MapDrawer.drawEntities(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, 8f, true);
        }
    }

    /** Header, footer, menus and tooltips over the map. */
    private void drawOverlay(int mouseX, int mouseY, float partialTicks, MapDimension dimension, int dimensionId,
        boolean otherDimension, boolean iso) {
        boolean prospecting = Mods.isVisualProspectingLoaded();
        // Header and footer.
        Theme.fill(0, 0, width, HEADER_HEIGHT, Theme.PANEL);
        Theme.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, Theme.BORDER);
        drawTitle(mouseX, mouseY, dimensionId, otherDimension);
        double targetScale = Config.MAP_ZOOMS[zoomIndex];
        String zoomText = targetScale >= 1 ? (int) targetScale + ":1" : "1:" + (int) Math.round(1 / targetScale);
        Theme.text(
            fontRendererObj,
            zoomText,
            width - 30 - fontRendererObj.getStringWidth(zoomText),
            8,
            Theme.TEXT_MUTED);

        Theme.fill(0, height - FOOTER_HEIGHT, width, height, Theme.PANEL);
        Theme.fill(0, height - FOOTER_HEIGHT, width, height - FOOTER_HEIGHT + 1, Theme.BORDER);
        int[] hovered = blockAt(mouseX, mouseY);
        int hoverX = hovered[0], hoverZ = hovered[2];
        String cursorText = hovered[1] >= 0 ? "X: " + hoverX + "  Y: " + hovered[1] + "  Z: " + hoverZ
            : "X: " + hoverX + "  Z: " + hoverZ;
        if (iso ? hovered[1] < 0 : !isExplored(dimension, hoverX, hoverZ)) {
            cursorText += "  (?)";
        }
        // Biome view shows biomes of whole columns, so there is no cave layer to pick.
        int caveLayer = columnViewShown() ? -1 : MapManager.INSTANCE.getViewCaveLayer();
        if (caveLayer >= 0) {
            cursorText += "  |  " + I18n.format("wayfarmap.gui.cave_layer", caveLayer * 16, caveLayer * 16 + 15);
        }
        if (biomeViewShown()) {
            BiomeGenBase biome = MapManager.INSTANCE.getViewBiome(hoverX, hoverZ);
            if (biome != null) {
                cursorText += "  |  " + biome.biomeName;
            }
        }
        Waypoint hoveredWaypoint = waypointAt(mouseX, mouseY);
        if (hoveredWaypoint != null) {
            cursorText = hoveredWaypoint.name + "  ("
                + hoveredWaypoint.x
                + ", "
                + hoveredWaypoint.y
                + ", "
                + hoveredWaypoint.z
                + ")  |  "
                + I18n.format("wayfarmap.gui.waypoint_hint");
        }
        if (chunkloadShown()) {
            int queued = ChunkLoadView.pendingCount(dimensionId);
            cursorText += "  |  "
                + I18n.format(regionloadView ? "wayfarmap.gui.regionload_hint" : "wayfarmap.gui.chunkload_hint")
                + (queued > 0 ? "  |  " + I18n.format("wayfarmap.gui.chunkload_queued", queued) : "");
        }
        String exportStatus = MapExport.statusText();
        exportButton.active = exportStatus != null;
        // An area being loaded with /wf chunkload: how far it got, and the time left.
        String loadStatus = ChunkLoadClient.INSTANCE.statusText();
        // On the right, by the help button: what is going on, or a note on the view.
        String right = null;
        int rightColor = Theme.TEXT_MUTED;
        if (exportStatus != null) {
            right = exportStatus;
            rightColor = Theme.ACCENT;
        } else if (loadStatus != null) {
            right = loadStatus;
            rightColor = Theme.ACCENT;
        } else if (iso) {
            right = I18n.format("wayfarmap.gui.iso_hint");
        } else if (claimsShown() && !chunkloadShown()) {
            right = ClaimsLayer.countsText();
        } else if (Config.showClaims && Mods.isClaimsAvailable() && otherDimension) {
            // ServerUtilities takes the dimension of every claim change from the player, so claims can only be
            // shown and changed in the dimension the player is in.
            right = I18n.format("wayfarmap.claims.other_dimension");
            rightColor = Theme.DANGER;
        }
        int rightEdge = helpButton.xPosition - 8;
        int rightX = rightEdge;
        if (right != null && !right.isEmpty()) {
            // Never more than half the footer, so the left text keeps room too.
            right = Theme.ellipsize(fontRendererObj, right, width / 2);
            rightX = rightEdge - fontRendererObj.getStringWidth(right);
            Theme.text(fontRendererObj, right, rightX, height - 10, rightColor);
        }
        // The left text takes the room left, cut short with an ellipsis rather than running into the right one.
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, cursorText, Math.max(20, rightX - 12 - 6)),
            6,
            height - 10,
            Theme.TEXT);

        GL11.glColor4f(1f, 1f, 1f, 1f);
        super.drawScaled(mouseX, mouseY, partialTicks);

        if (caveLayer >= 0) {
            drawCaveSlider(mouseX, mouseY);
        }
        if (iso) {
            drawQualitySlider(mouseX, mouseY);
            if (!Config.record3d) {
                // Nothing new comes onto the 3D map: it is drawn from the flat map where it has no blocks.
                String warning = I18n.format("wayfarmap.gui.iso_not_recording");
                int w = fontRendererObj.getStringWidth(warning);
                int x = (width - w) / 2, y = height - FOOTER_HEIGHT - 16;
                Theme.fill(x - 4, y - 3, x + w + 4, y + 11, Theme.LABEL_BG);
                Theme.text(fontRendererObj, warning, x, y, Theme.DANGER);
            }
        }
        if (searchAvailable()) {
            searchField.drawTextBox();
        }
        IconButton hoveredIcon = null;
        for (Object o : buttonList) {
            if (o instanceof IconButton && ((IconButton) o).isMouseOver(mouseX, mouseY)) {
                hoveredIcon = (IconButton) o;
            }
        }
        if (dimensionList != null) {
            drawDimensionList(mouseX, mouseY);
        } else if (menu != null) {
            drawMenu(mouseX, mouseY);
        } else if (hoveredIcon != null && !hoveredIcon.tooltip.isEmpty()) {
            drawHoveringText(Collections.singletonList(hoveredIcon.tooltip), mouseX, mouseY, fontRendererObj);
        } else if (!iso && !chunkloadShown()
            && mouseY > HEADER_HEIGHT
            && mouseY < height - FOOTER_HEIGHT
            && claimButton < 0) {
            // Power failures are drawn on top, so their tooltip comes first.
            List<String> tooltip = powerfailsShown() ? PowerfailLayer.getHoveredTooltip() : null;
            if (tooltip == null && nodesShown()) {
                tooltip = ThaumcraftNodes.getHoveredTooltip();
            }
            if (tooltip == null && prospecting && Config.showOreVeins) {
                tooltip = ProspectingLayer.getHoveredTooltip();
            }
            if (tooltip == null && claimsShown()) {
                tooltip = ClaimsLayer.tooltip(hoverX >> 4, hoverZ >> 4, dimensionId);
            }
            if (tooltip != null) {
                drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
            }
        }
    }

    private int headerLeftEnd() {
        int end = 0;
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id == ID_WAYPOINTS || button.id == ID_SETTINGS
                || button.id == ID_EXPORT
                || button.id == ID_ADDONS) {
                end = Math.max(end, button.xPosition + ((FlatButton) button).getWidth());
            }
        }
        return end;
    }

    // ---------------------------------------------------------------- 3D quality slider

    private static final int QUALITY_WIDTH = 20, QUALITY_ROW = 14;

    private int qualityX() {
        return width - QUALITY_WIDTH - 6;
    }

    private int qualityTop() {
        return HEADER_HEIGHT + 6;
    }

    /** The quality (0-3) of the slider's cell under the mouse, -1 if not on it; the best is at the top. */
    private int qualityAt(int mouseX, int mouseY) {
        int x = qualityX(), top = qualityTop();
        int rows = Config.ISO_QUALITY_MAX + 1;
        if (!Theme.inside(mouseX, mouseY, x, top, x + QUALITY_WIDTH, top + rows * QUALITY_ROW)) {
            return -1;
        }
        return Config.ISO_QUALITY_MAX - (mouseY - top) / QUALITY_ROW;
    }

    /** Pixels per block the 3D map is drawn with at most: 64 at the top down to 8. */
    private void drawQualitySlider(int mouseX, int mouseY) {
        int x = qualityX(), top = qualityTop();
        int rows = Config.ISO_QUALITY_MAX + 1;
        int hovered = qualityAt(mouseX, mouseY);
        Theme.fill(x - 1, top - 1, x + QUALITY_WIDTH + 1, top + rows * QUALITY_ROW + 1, Theme.BORDER);
        for (int quality = Config.ISO_QUALITY_MAX; quality >= 0; quality--) {
            int y = top + (Config.ISO_QUALITY_MAX - quality) * QUALITY_ROW;
            int color;
            if (quality == Config.isoQuality) {
                color = Theme.ACCENT;
            } else if (quality < Config.isoQuality) {
                color = Theme.ACCENT_DIM;
            } else {
                color = quality == hovered ? Theme.CONTROL_HOVER : Theme.PANEL;
            }
            Theme.fill(x, y, x + QUALITY_WIDTH, y + QUALITY_ROW - 1, color);
            Theme.centered(
                fontRendererObj,
                String.valueOf(8 << quality),
                x + QUALITY_WIDTH / 2,
                y + 3,
                quality <= Config.isoQuality ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        if (hovered >= 0 && menu == null) {
            String text = I18n.format("wayfarmap.iso.quality") + ": "
                + I18n.format("wayfarmap.iso.quality_value", 8 << hovered);
            drawHoveringText(Collections.singletonList(text), mouseX, mouseY, fontRendererObj);
        }
    }

    // ---------------------------------------------------------------- cave layer slider

    private int sliderX() {
        return width - SLIDER_WIDTH - 6;
    }

    private int sliderAutoTop() {
        return HEADER_HEIGHT + 6;
    }

    private int sliderTop() {
        return sliderAutoTop() + 18;
    }

    private int sliderBottom() {
        return height - FOOTER_HEIGHT - 8;
    }

    /** Layer (0-15) at the height of the mouse on the slider track; the top is the highest layer. */
    private int sliderLayerAt(int mouseY) {
        double t = (mouseY - sliderTop()) / (double) Math.max(1, sliderBottom() - sliderTop());
        return 15 - Math.max(0, Math.min(15, (int) Math.floor(t * 16)));
    }

    private boolean onCaveSlider(int mouseX, int mouseY) {
        return MapManager.INSTANCE.getViewCaveLayer() >= 0 && !columnViewShown()
            && Theme
                .inside(mouseX, mouseY, sliderX() - 4, sliderAutoTop(), sliderX() + SLIDER_WIDTH + 4, sliderBottom());
    }

    private void drawCaveSlider(int mouseX, int mouseY) {
        if (draggingCaveSlider) {
            if (Mouse.isButtonDown(0)) {
                MapManager.INSTANCE.setCaveLayerOverride(sliderLayerAt(mouseY));
            } else {
                draggingCaveSlider = false;
            }
        }
        int x = sliderX();
        int top = sliderTop(), bottom = sliderBottom();
        int override = MapManager.INSTANCE.getCaveLayerOverride();
        int layer = MapManager.INSTANCE.getViewCaveLayer();

        // "Auto" follows the player's height.
        int autoTop = sliderAutoTop();
        boolean autoHovered = Theme.inside(mouseX, mouseY, x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14);
        Theme.fill(x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14, override < 0 ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14, autoHovered ? Theme.ACCENT : Theme.BORDER);
        Theme.centered(fontRendererObj, "A", x + SLIDER_WIDTH / 2, autoTop + 3, Theme.TEXT);

        Theme.fill(x - 1, top - 1, x + SLIDER_WIDTH + 1, bottom + 1, Theme.BORDER);
        Theme.fill(x, top, x + SLIDER_WIDTH, bottom, Theme.PANEL);
        double step = (bottom - top) / 16.0;
        for (int i = 1; i < 16; i++) {
            int y = top + (int) Math.round(i * step);
            Theme.fill(x + 2, y, x + SLIDER_WIDTH - 2, y + 1, Theme.CONTROL_HOVER);
        }
        int knobTop = top + (int) Math.round((15 - layer) * step);
        int knobBottom = top + (int) Math.round((16 - layer) * step);
        Theme.fill(x, knobTop, x + SLIDER_WIDTH, knobBottom, override >= 0 ? Theme.ACCENT : Theme.TEXT_MUTED);

        boolean hovered = onCaveSlider(mouseX, mouseY) || draggingCaveSlider;
        String label = "Y " + layer * 16 + "-" + (layer * 16 + 15);
        int labelWidth = fontRendererObj.getStringWidth(label);
        int labelY = (knobTop + knobBottom) / 2 - 4;
        Theme.fill(x - labelWidth - 10, labelY - 2, x - 4, labelY + 10, Theme.LABEL_BG);
        Theme.text(fontRendererObj, label, x - labelWidth - 7, labelY, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    // ---------------------------------------------------------------- dimension picker

    /** "[id] Name ▼" in the middle of the header; it opens the list of saved dimensions. */
    private void drawTitle(int mouseX, int mouseY, int dimensionId, boolean otherDimension) {
        String title = "[" + dimensionId + "] " + MapManager.INSTANCE.getViewedDimensionName() + " \u25BE";
        int titleWidth = fontRendererObj.getStringWidth(title);
        if (width / 2 - titleWidth / 2 <= headerLeftEnd() + 8
            || width / 2 + titleWidth / 2 >= rightButtonsStart() - 8) {
            // No room for the name: the id alone still opens the list.
            title = "[" + dimensionId + "] \u25BE";
            titleWidth = fontRendererObj.getStringWidth(title);
        }
        titleX0 = width / 2 - titleWidth / 2;
        titleX1 = titleX0 + titleWidth;
        boolean hovered = dimensionList != null || Theme.inside(mouseX, mouseY, titleX0 - 4, 4, titleX1 + 4, 20);
        if (hovered) {
            Theme.fill(titleX0 - 4, 4, titleX1 + 4, 20, Theme.CONTROL_HOVER);
        }
        // Accent while looking at another dimension than the player's.
        int color = otherDimension ? Theme.ACCENT : hovered ? Theme.TEXT : Theme.TEXT_MUTED;
        Theme.text(fontRendererObj, title, titleX0, 8, color);
    }

    private void openDimensionList() {
        if (dimensionList != null) {
            dimensionList = null;
            return;
        }
        menu = null;
        dimensionList = MapManager.INSTANCE.listSavedDimensions();
        String here = I18n.format("wayfarmap.gui.dimension_here");
        int widest = 0;
        for (MapManager.SavedDimension dimension : dimensionList) {
            widest = Math.max(
                widest,
                fontRendererObj.getStringWidth(dimensionLabel(dimension))
                    + fontRendererObj.getStringWidth("  " + here));
        }
        dimensionListWidth = Math.max(titleX1 - titleX0 + 8, widest + 12);
        dimensionListX = Math.max(2, Math.min(width / 2 - dimensionListWidth / 2, width - dimensionListWidth - 2));
    }

    private static String dimensionLabel(MapManager.SavedDimension dimension) {
        return "[" + dimension.id + "] " + dimension.name;
    }

    private void drawDimensionList(int mouseX, int mouseY) {
        int top = HEADER_HEIGHT - 2;
        int h = dimensionList.size() * MENU_ROW + 4;
        Theme.panel(dimensionListX, top, dimensionListX + dimensionListWidth, top + h);
        int shown = viewDimension();
        int playerDimension = mc.theWorld.provider.dimensionId;
        String here = I18n.format("wayfarmap.gui.dimension_here");
        for (int i = 0; i < dimensionList.size(); i++) {
            MapManager.SavedDimension dimension = dimensionList.get(i);
            int y = top + 2 + i * MENU_ROW;
            if (dimension.id == shown) {
                Theme.fill(
                    dimensionListX + 1,
                    y,
                    dimensionListX + dimensionListWidth - 1,
                    y + MENU_ROW,
                    Theme.ACCENT_DIM);
            } else if (Theme
                .inside(mouseX, mouseY, dimensionListX, y, dimensionListX + dimensionListWidth, y + MENU_ROW)) {
                    Theme.fill(
                        dimensionListX + 1,
                        y,
                        dimensionListX + dimensionListWidth - 1,
                        y + MENU_ROW,
                        Theme.CONTROL_HOVER);
                }
            String label = dimensionLabel(dimension);
            Theme.text(fontRendererObj, label, dimensionListX + 6, y + 3, Theme.TEXT);
            if (dimension.id == playerDimension) {
                Theme.text(
                    fontRendererObj,
                    here,
                    dimensionListX + dimensionListWidth - 6 - fontRendererObj.getStringWidth(here),
                    y + 3,
                    Theme.TEXT_MUTED);
            }
        }
    }

    /** @return true if the click was taken by the dimension list (which then closes) */
    private boolean clickDimensionList(int mouseX, int mouseY) {
        if (dimensionList == null) {
            return false;
        }
        List<MapManager.SavedDimension> entries = dimensionList;
        dimensionList = null;
        int top = HEADER_HEIGHT - 2;
        for (int i = 0; i < entries.size(); i++) {
            int y = top + 2 + i * MENU_ROW;
            if (Theme.inside(mouseX, mouseY, dimensionListX, y, dimensionListX + dimensionListWidth, y + MENU_ROW)) {
                showDimension(entries.get(i).id);
            }
        }
        return true;
    }

    /** Left edge of the buttons on the right of the header. */
    private int rightButtonsStart() {
        return teamButton.visible ? teamButton.xPosition : mobsButton.xPosition;
    }

    /** Menu under the team button: every online teammate and the dimension they are in; a click goes there. */
    private void openTeamMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        for (TeamMates.Mate mate : TeamMates.INSTANCE.all()) {
            String where = "[" + mate.dimension + "] " + MapManager.INSTANCE.getDimensionName(mate.dimension);
            String label = mate.name + " \u00a77" + where;
            entries.add(
                new MenuEntry(
                    Theme.ellipsize(fontRendererObj, label, TEAM_MENU_WIDTH - 12),
                    true,
                    () -> goToTeammate(mate)));
        }
        menu = entries;
        menuKind = MENU_TEAM;
        menuWidth = TEAM_MENU_WIDTH;
        menuX = Math.max(2, Math.min(teamButton.xPosition, width - TEAM_MENU_WIDTH - 2));
        menuY = teamButton.yPosition + 18;
    }

    /** Centers the map on the teammate, switching to their dimension if needed. */
    private void goToTeammate(TeamMates.Mate mate) {
        if (mate.dimension != viewDimension()) {
            showDimension(mate.dimension);
        }
        double[] position = TeamMates.INSTANCE.position(mate, 1f);
        centerOn(position[0], position[1], position[2]);
        zooming = false;
    }

    /** Shows the waypoint in the middle of the map, switching to its dimension if it has a saved map. */
    private void centerOnWaypoint(Waypoint waypoint) {
        if (waypoint.dimension != viewDimension()) {
            if (waypoint.dimension == mc.theWorld.provider.dimensionId) {
                showDimension(waypoint.dimension);
            } else {
                for (MapManager.SavedDimension other : MapManager.INSTANCE.listSavedDimensions()) {
                    if (other.id == waypoint.dimension) {
                        showDimension(waypoint.dimension);
                        break;
                    }
                }
            }
        }
        centerOn(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5);
        zooming = false;
    }

    /** Switches the map to a saved dimension, keeping the view roughly in place (Nether coordinates are 1:8). */
    private void showDimension(int id) {
        int from = viewDimension();
        if (id == from) {
            return;
        }
        MapManager.INSTANCE.viewDimension(id);
        if (id == mc.theWorld.provider.dimensionId) {
            centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
        } else if (id == -1 && from != -1) {
            centerX /= 8;
            centerZ /= 8;
        } else if (from == -1 && id != -1) {
            centerX *= 8;
            centerZ *= 8;
        }
        zooming = false;
        scale = Config.MAP_ZOOMS[zoomIndex];
        claimButton = -1;
        claimSelection.clear();
        applySearch();
    }

    // ---------------------------------------------------------------- right click menu

    private void openMenu(int mouseX, int mouseY) {
        int dimension = viewDimension();
        // Teleporting works only within the player's dimension.
        boolean here = !MapManager.INSTANCE.isViewingOtherDimension();
        List<MenuEntry> entries = new ArrayList<>();
        // The vein under the mouse right now: the menu keeps it, since the mouse leaves the vein to click an entry.
        // The 3D view has no layers to point at.
        boolean flat = !isoShown() && !chunkloadShown();
        final Object vein = flat && Mods.isVisualProspectingLoaded() && Config.showOreVeins
            ? ProspectingLayer.getHoveredVein()
            : null;
        // Same for the power failure under the mouse.
        final Object powerfail = flat && powerfailsShown() ? PowerfailLayer.getHovered() : null;
        // And for the Thaumcraft node under the mouse.
        final Object node = flat && powerfail == null && nodesShown() ? ThaumcraftNodes.getHovered() : null;
        if (node != null) {
            entries.add(
                new MenuEntry(
                    I18n.format(ThaumcraftNodes.isTracked(node) ? "wayfarmap.node.untrack" : "wayfarmap.node.track"),
                    true,
                    () -> ThaumcraftNodes.toggleTracked(node)));
            entries.add(
                new MenuEntry(I18n.format("wayfarmap.node.deplete"), true, () -> ThaumcraftNodes.markDepleted(node)));
        }
        if (powerfail != null) {
            entries.add(
                new MenuEntry(I18n.format("wayfarmap.powerfail.clear"), true, () -> PowerfailLayer.clear(powerfail)));
            final int[] at = PowerfailLayer.position(powerfail);
            entries.add(
                new MenuEntry(
                    I18n.format("wayfarmap.powerfail.waypoint"),
                    true,
                    () -> mc.displayGuiScreen(GuiEditWaypoint.create(this, at[0], at[1] + 1, at[2], at[3]))));
        }
        if (vein != null) {
            entries.add(
                new MenuEntry(
                    I18n.format(
                        ProspectingLayer.isTracked(vein) ? "wayfarmap.gui.vein_untrack" : "wayfarmap.gui.vein_track"),
                    true,
                    () -> ProspectingLayer.toggleTracked(vein)));
            entries.add(
                new MenuEntry(
                    I18n.format(
                        ProspectingLayer.isDepleted(vein) ? "wayfarmap.gui.vein_restore"
                            : "wayfarmap.gui.vein_deplete"),
                    true,
                    () -> ProspectingLayer.toggleDepleted(vein)));
        }
        int[] block = blockAt(mouseX, mouseY);
        final int bx = block[0];
        final int bz = block[2];
        // In 3D the block seen there gives the height, also in another dimension.
        final int seenY = block[1] >= 0 ? Math.min(255, block[1] + 1) : 0;
        final int safeY = here ? Teleport.findSafeY(mc.theWorld, bx, bz) : 0;
        entries.add(new MenuEntry(I18n.format("wayfarmap.gui.teleport_here"), here && Teleport.isAllowed(), () -> {
            if (safeY > 0) {
                Teleport.teleport(bx, safeY, bz);
            } else {
                // Unexplored or unknown height: ask which Y to go to.
                mc.displayGuiScreen(new GuiTeleportY(this, bx, bz));
            }
        }));
        entries.add(
            new MenuEntry(
                I18n.format("wayfarmap.gui.new_waypoint"),
                true,
                () -> mc.displayGuiScreen(
                    GuiEditWaypoint
                        .create(this, bx, safeY > 0 ? safeY : seenY > 0 ? seenY : waypointY(bx, bz), bz, dimension))));
        // A waypoint at once, without its editor: named by its coordinates, no icon.
        final int markY = safeY > 0 ? safeY : seenY > 0 ? seenY : waypointY(bx, bz);
        entries.add(
            new MenuEntry(
                I18n.format("wayfarmap.gui.quick_waypoint"),
                true,
                () -> WaypointManager.INSTANCE
                    .addWaypoint(new Waypoint(bx + ", " + markY + ", " + bz, bx, markY, bz, dimension))));
        if (flat) {
            final int rx = bx >> MapRegion.SHIFT, rz = bz >> MapRegion.SHIFT;
            entries.add(
                new MenuEntry(
                    I18n.format("wayfarmap.gui.delete_region"),
                    true,
                    () -> confirmDeleteRegion(dimension, rx, rz)));
        }
        showMenu(entries, MENU_MAP, mouseX, mouseY);
    }

    /** Menu of a waypoint on the map: share it in chat, teleport to it, edit, remove or disable it. */
    private void openWaypointMenu(Waypoint waypoint, int mouseX, int mouseY) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(I18n.format("wayfarmap.gui.share"), true, () -> WaypointShare.share(waypoint)));
        // Teleporting needs /tp permission and the same dimension.
        boolean canTeleport = Teleport.isAllowed() && mc.theWorld != null
            && waypoint.dimension == mc.theWorld.provider.dimensionId;
        entries.add(new MenuEntry(I18n.format("wayfarmap.gui.teleport"), canTeleport, () -> {
            mc.displayGuiScreen(null);
            Teleport.teleport(waypoint.x, waypoint.y, waypoint.z);
        }));
        entries.add(
            new MenuEntry(
                I18n.format("wayfarmap.gui.edit"),
                true,
                () -> mc.displayGuiScreen(GuiEditWaypoint.edit(this, waypoint))));
        entries.add(
            new MenuEntry(
                I18n.format("wayfarmap.gui.remove"),
                true,
                () -> WaypointManager.INSTANCE.removeWaypoint(waypoint)));
        // Disabled: gone from the world and the minimap, faded on this map.
        String toggle = I18n.format(waypoint.enabled ? "wayfarmap.gui.disable" : "wayfarmap.gui.enable");
        entries.add(new MenuEntry(toggle, true, () -> {
            waypoint.enabled = !waypoint.enabled;
            WaypointManager.INSTANCE.waypointChanged();
        }));
        showMenu(entries, MENU_WAYPOINT, mouseX, mouseY);
    }

    /** Opens a menu at the point, as wide as its longest entry. */
    private void showMenu(List<MenuEntry> entries, int kind, int x, int y) {
        int widest = MENU_WIDTH;
        for (MenuEntry entry : entries) {
            widest = Math.max(widest, fontRendererObj.getStringWidth(entry.label) + 14);
        }
        menu = entries;
        menuKind = kind;
        menuWidth = widest;
        menuX = Math.max(2, Math.min(x, width - widest - 2));
        menuY = Math.min(y, height - entries.size() * MENU_ROW - 6);
    }

    /** Asks before deleting a region of the flat map, in the same place as the menu was. */
    private void confirmDeleteRegion(int dimension, int rx, int rz) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(
            new MenuEntry(
                I18n.format("wayfarmap.gui.delete_region_yes"),
                true,
                () -> MapManager.INSTANCE.deleteFlatRegion(dimension, rx, rz)));
        entries.add(new MenuEntry(I18n.format("gui.cancel"), true, () -> {}));
        showMenu(entries, MENU_CONFIRM, menuX, menuY);
    }

    private void drawMenu(int mouseX, int mouseY) {
        int h = menu.size() * MENU_ROW + 4;
        Theme.panel(menuX, menuY, menuX + menuWidth, menuY + h);
        for (int i = 0; i < menu.size(); i++) {
            MenuEntry entry = menu.get(i);
            int y = menuY + 2 + i * MENU_ROW;
            if (entry.enabled && Theme.inside(mouseX, mouseY, menuX, y, menuX + menuWidth, y + MENU_ROW)) {
                Theme.fill(menuX + 1, y, menuX + menuWidth - 1, y + MENU_ROW, Theme.CONTROL_HOVER);
            }
            int textX = menuX + 6;
            if (entry.checked != null) {
                // Checkbox.
                Theme.outline(menuX + 5, y + 3, menuX + 13, y + 11, entry.checked ? Theme.ACCENT : Theme.BORDER);
                if (entry.checked) {
                    Theme.fill(menuX + 7, y + 5, menuX + 11, y + 9, Theme.ACCENT);
                }
                textX = menuX + 18;
            }
            Theme.text(fontRendererObj, entry.label, textX, y + 3, entry.enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        }
        if (menuKind == MENU_MAP && !Teleport.isAllowed()) {
            String note = I18n.format("wayfarmap.gui.no_teleport_permission");
            Theme.text(fontRendererObj, note, menuX + 2, menuY + h + 3, Theme.TEXT_DISABLED);
        }
    }

    /** @return true if the click was taken by the menu (which then closes) */
    private boolean clickMenu(int mouseX, int mouseY) {
        if (menu == null) {
            return false;
        }
        List<MenuEntry> entries = menu;
        menu = null;
        for (int i = 0; i < entries.size(); i++) {
            int y = menuY + 2 + i * MENU_ROW;
            MenuEntry entry = entries.get(i);
            if (Theme.inside(mouseX, mouseY, menuX, y, menuX + menuWidth, y + MENU_ROW) && entry.enabled) {
                entry.action.run();
                if (entry.checked != null && menuKind == MENU_ADDONS) {
                    // Toggles keep the menu open, showing the new state.
                    openAddonsMenu();
                } else if (entry.checked != null && menuKind == MENU_MOBS) {
                    openMobsMenu();
                } else if (entry.checked != null && menuKind == MENU_EXPORT) {
                    openExportMenu();
                }
            }
        }
        return true;
    }

    /** Marker size in GUI pixels: markers shrink when zooming out so nearby waypoints don't pile up. */
    private float markerSize() {
        return (float) Math.max(MIN_MARKER_SIZE, Math.min(MARKER_SIZE, MARKER_SIZE * Math.pow(scale, 0.4)));
    }

    /** Waypoint under the mouse last frame, and how far its hover effect has grown (0 to 1). */
    private Waypoint hoverEffectWaypoint;
    private float hoverEffect;
    private long hoverEffectNanos;
    /** How much bigger a hovered waypoint is drawn. */
    private static final float HOVER_GROWTH = 0.3f;
    /** Time for the hover effect to grow in, in seconds. */
    private static final float HOVER_SECONDS = 0.12f;
    /** Time of one pulse of the frame around a hovered waypoint, in milliseconds. */
    private static final long HOVER_PULSE_MS = 1200;

    /** Grows the hover effect while the same waypoint stays under the mouse; starts over on another one. */
    private float updateHoverEffect(Waypoint hovered) {
        long now = System.nanoTime();
        float seconds = hoverEffectNanos == 0 ? 0f : Math.min(0.1f, (now - hoverEffectNanos) / 1.0e9f);
        hoverEffectNanos = now;
        if (hovered != hoverEffectWaypoint) {
            hoverEffectWaypoint = hovered;
            hoverEffect = 0f;
        } else if (hovered != null) {
            hoverEffect = Math.min(1f, hoverEffect + seconds / HOVER_SECONDS);
        }
        // Eased, so it pops out and settles.
        float t = hoverEffect;
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * Frame around the hovered waypoint, in its color, pulsing gently: shows it can be clicked (right click: its
     * menu). Drawn under the marker.
     */
    private static void drawHoverFrame(Waypoint waypoint, double sx, double sy, float size, float effect) {
        long cx = Math.round(sx), cy = Math.round(sy);
        GL11.glPushMatrix();
        GL11.glTranslated(sx - cx, sy - cy, 0);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() % HOVER_PULSE_MS * 2 * Math.PI / HOVER_PULSE_MS);
        int rgb = waypoint.outlineColor != null ? waypoint.outlineColor & 0xFFFFFF : Theme.ACCENT & 0xFFFFFF;
        int half = Math.round(size / 2f) + 3 + Math.round(effect * (float) pulse);
        int x0 = (int) cx - half, y0 = (int) cy - half, x1 = (int) cx + half, y1 = (int) cy + half;
        // A soft glow inside, a bright frame, and a dark line around it so it shows on any ground.
        int glow = (int) (effect * (40 + 40 * pulse));
        Theme.fill(x0, y0, x1, y1, glow << 24 | rgb);
        Theme.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, (int) (effect * 160) << 24);
        Theme.outline(x0, y0, x1, y1, (int) (effect * (170 + 85 * pulse)) << 24 | rgb);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void drawWaypoints(int mouseX, int mouseY) {
        float size = markerSize();
        Waypoint hovered = waypointAt(mouseX, mouseY);
        float effect = updateHoverEffect(hovered);
        List<Waypoint> onScreen = new ArrayList<>();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getMapWaypoints(viewDimension())) {
            double[] at = waypointScreen(waypoint);
            double wx = at[0], wy = at[1];
            if (wx > -size && wy > -size && wx < width + size && wy < height + size && waypoint != hovered) {
                onScreen.add(waypoint);
            }
        }
        if (hovered != null) {
            // Drawn last so it is on top, and its label always wins.
            onScreen.add(hovered);
        }

        for (Waypoint waypoint : onScreen) {
            if (waypoint == hovered) {
                // Hovered: a pulsing frame, and the marker a little bigger.
                float hoveredSize = size * (1f + HOVER_GROWTH * effect);
                drawHoverFrame(waypoint, screenX(waypoint), screenY(waypoint), hoveredSize, effect);
                WaypointRenderer.drawMapMarker(waypoint, screenX(waypoint), screenY(waypoint), hoveredSize, false);
            } else {
                WaypointRenderer.drawMapMarker(waypoint, screenX(waypoint), screenY(waypoint), size, false);
            }
        }

        // Labels shrink with the markers when zooming out and stop growing at normal size when zooming in.
        float textScale = size / MARKER_SIZE;
        // Labels: skip any that would overlap a label already placed, starting with the hovered one.
        List<Waypoint> labelled = new ArrayList<>();
        List<int[]> rects = new ArrayList<>();
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            Waypoint waypoint = onScreen.get(i);
            // The hovered one's label moves down out of the way of its bigger marker and frame.
            float labelSize = waypoint == hovered ? size * (1f + HOVER_GROWTH * effect) + 8 * effect : size;
            int[] rect = WaypointRenderer
                .getLabelRect(waypoint, screenX(waypoint), screenY(waypoint), labelSize, textScale);
            if (rect == null || (waypoint != hovered && overlapsAny(rect, rects))) {
                continue;
            }
            labelled.add(waypoint);
            rects.add(rect);
        }
        for (int i = labelled.size() - 1; i >= 0; i--) {
            // Moved by the same sub-pixel remainder as its marker, so the two glide together.
            Waypoint waypoint = labelled.get(i);
            double sx = screenX(waypoint), sy = screenY(waypoint);
            GL11.glPushMatrix();
            GL11.glTranslated(sx - Math.round(sx), sy - Math.round(sy), 0);
            WaypointRenderer.drawMapLabel(waypoint, rects.get(i), textScale);
            GL11.glPopMatrix();
        }
    }

    private static boolean overlapsAny(int[] rect, List<int[]> others) {
        for (int[] other : others) {
            if (rect[0] < other[2] && other[0] < rect[2] && rect[1] < other[3] && other[1] < rect[3]) {
                return true;
            }
        }
        return false;
    }

    /** Screen position of the waypoint's marker; in 3D at its height. */
    private double[] waypointScreen(Waypoint waypoint) {
        return toScreen(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5);
    }

    private double screenX(Waypoint waypoint) {
        return waypointScreen(waypoint)[0];
    }

    private double screenY(Waypoint waypoint) {
        return waypointScreen(waypoint)[1];
    }

    private Waypoint waypointAt(int mouseX, int mouseY) {
        if (chunkloadShown()) {
            // The chunk loading view shows no waypoints.
            return null;
        }
        Waypoint best = null;
        double bestDistance = markerSize() / 2 + 2;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getMapWaypoints(viewDimension())) {
            double[] at = waypointScreen(waypoint);
            double wx = at[0], wy = at[1];
            double distance = Math.max(Math.abs(wx - mouseX), Math.abs(wy - mouseY));
            if (distance <= bestDistance) {
                best = waypoint;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Id of the dimension the map shows: the player's, or another one picked from the title. */
    private static int viewDimension() {
        return MapManager.INSTANCE.getViewedDimensionId();
    }

    /** Height for a new waypoint: the ground if known, in another dimension from its saved map. */
    private int waypointY(int x, int z) {
        if (!MapManager.INSTANCE.isViewingOtherDimension()) {
            return surfaceY(x, z);
        }
        int y = MapManager.INSTANCE.getViewSurfaceHeight(x, z);
        return y > 0 ? y : 64;
    }

    /** Height of the ground at the column if its chunk is loaded, otherwise the player's height. */
    private int surfaceY(int x, int z) {
        int playerY = MathHelper.floor_double(mc.thePlayer.boundingBox.minY);
        if (mc.theWorld.provider.hasNoSky || mc.theWorld.getChunkFromBlockCoords(x, z)
            .isEmpty()) {
            return playerY;
        }
        int y = mc.theWorld.getHeightValue(x, z);
        return y > 0 ? y : playerY;
    }

    private static boolean isExplored(MapDimension dimension, int x, int z) {
        return (dimension.peekPixel(x, z) >>> 24) != 0;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || welcome) {
            return;
        }
        int newIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoomIndex + (wheel > 0 ? 1 : -1)));
        if (newIndex == zoomIndex) {
            return;
        }
        // Keep the block under the cursor in place while zooming.
        anchorScreenX = Mouse.getEventX() * (double) width / mc.displayWidth;
        anchorScreenY = height - Mouse.getEventY() * (double) height / mc.displayHeight;
        double[] offset = screenToWorldOffset(anchorScreenX - width / 2.0, anchorScreenY - height / 2.0);
        anchorWorldX = centerX + offset[0];
        anchorWorldZ = centerZ + offset[1];
        zoomIndex = newIndex;
        zooming = true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (welcome) {
            // Only its button closes it; the map waits.
            int[] b = welcomeButton();
            if (button == 0 && Theme.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
                closeWelcome();
            }
            return;
        }
        if (clickDimensionList(mouseX, mouseY)) {
            return;
        }
        if (clickMenu(mouseX, mouseY)) {
            return;
        }
        if (button == 0 && titleX1 > titleX0 && Theme.inside(mouseX, mouseY, titleX0 - 4, 4, titleX1 + 4, 20)) {
            openDimensionList();
            return;
        }
        if (searchAvailable()) {
            searchField.mouseClicked(mouseX, mouseY, button);
            if (searchField.isMouseOver(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (mouseY < HEADER_HEIGHT || mouseY >= height - FOOTER_HEIGHT || mc.currentScreen != this) {
            return;
        }
        if (chunkloadShown() && (button == 0 || button == 1) && startPick(mouseX, mouseY, button)) {
            return;
        }
        if (!isoShown() && !chunkloadShown()
            && claimsShown()
            && (button == 0 || button == 1)
            && startClaimPaint(mouseX, mouseY, button)) {
            return;
        }
        if (button == 0 && isoShown() && qualityAt(mouseX, mouseY) >= 0) {
            Config.setIsoQuality(qualityAt(mouseX, mouseY));
            return;
        }
        if (button == 0 && onCaveSlider(mouseX, mouseY)) {
            if (mouseY < sliderTop()) {
                MapManager.INSTANCE.setCaveLayerOverride(-1);
            } else {
                draggingCaveSlider = true;
                MapManager.INSTANCE.setCaveLayerOverride(sliderLayerAt(mouseY));
            }
            return;
        }
        if (button == 1) {
            // Right click on a waypoint: its menu (share, teleport, edit, remove, disable); elsewhere the map's menu.
            Waypoint hovered = waypointAt(mouseX, mouseY);
            if (hovered != null) {
                openWaypointMenu(hovered, mouseX, mouseY);
            } else {
                openMenu(mouseX, mouseY);
            }
            return;
        }
        if (button == 0) {
            dragging = true;
            lastRawMouseX = Mouse.getX();
            lastRawMouseY = Mouse.getY();
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button == 0) {
            dragging = false;
        }
        if (button == claimButton) {
            finishClaimPaint();
        }
        if (button == pickButton) {
            finishPick();
        }
    }

    // ---------------------------------------------------------------- chunk loading view

    /** Chunks picked by the drag going on (Ctrl held) in the chunk loading view. */
    private final Set<Long> pickSelection = new LinkedHashSet<>();
    private int pickButton = -1;
    /** Right button: the picked chunks are taken off the queue. */
    private boolean pickRemove;
    private int pickStartX, pickStartZ, pickEndX = Integer.MIN_VALUE, pickEndZ;

    /** The chunk loading view: the flat map of the player's own dimension. */
    private boolean chunkloadShown() {
        if ((chunkloadView || regionloadView) && !ChunkLoadView.isAllowed()) {
            // No longer allowed (or another server): back to the flat map.
            chunkloadView = false;
            regionloadView = false;
            MapManager.INSTANCE.setSurfaceView(false);
        }
        // The surface even in caves (MapManager's surface view): only another dimension or 3D leave it out.
        return (chunkloadView || regionloadView) && !isoShown() && !MapManager.INSTANCE.isViewingOtherDimension();
    }

    /** Ctrl and a drag: left picks the rectangle of chunks to be loaded, right takes them off the queue. */
    private boolean startPick(int mouseX, int mouseY, int button) {
        if (!Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) && !Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            return false;
        }
        pickButton = button;
        pickRemove = button == 1;
        pickStartX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        pickStartZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        pickEndX = Integer.MIN_VALUE;
        updatePickRectangle(pickStartX, pickStartZ);
        return true;
    }

    private void updatePick(int mouseX, int mouseY) {
        if (pickButton < 0) {
            return;
        }
        if (!Mouse.isButtonDown(pickButton)) {
            finishPick();
            return;
        }
        updatePickRectangle(
            MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4,
            MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4);
    }

    private void updatePickRectangle(int endX, int endZ) {
        if (endX == pickEndX && endZ == pickEndZ) {
            return;
        }
        pickEndX = endX;
        pickEndZ = endZ;
        pickSelection.clear();
        pickSelection.addAll(ChunkLoadView.rectangle(pickStartX, pickStartZ, endX, endZ));
    }

    private void finishPick() {
        if (pickButton < 0) {
            return;
        }
        pickButton = -1;
        ChunkLoadView.pick(mc.theWorld.provider.dimensionId, pickSelection, pickRemove, regionloadView);
        pickSelection.clear();
    }

    // ---------------------------------------------------------------- claims painting

    /** Claims come from the server for the player's dimension only, so other dimensions don't show them. */
    private static boolean claimsShown() {
        return Config.showClaims && Mods.isClaimsAvailable() && !MapManager.INSTANCE.isViewingOtherDimension();
    }

    /**
     * With Ctrl and/or Shift held, a drag selects the rectangle of chunks between where it started and the mouse
     * instead of moving the map. Left button: Ctrl claims, Shift chunk loads own claims, both claim and load. Right
     * button: Ctrl unclaims, Shift unloads, both unload and unclaim.
     */
    private boolean startClaimPaint(int mouseX, int mouseY, int button) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (!ctrl && !shift) {
            return false;
        }
        if (button == 0) {
            claimAction = ctrl && shift ? ClaimsLayer.CLAIM_AND_LOAD : ctrl ? ClaimsLayer.CLAIM : ClaimsLayer.LOAD;
        } else {
            claimAction = ctrl && shift ? ClaimsLayer.UNLOAD_AND_UNCLAIM
                : ctrl ? ClaimsLayer.UNCLAIM : ClaimsLayer.UNLOAD;
        }
        claimButton = button;
        claimStartX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        claimStartZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        claimEndX = Integer.MIN_VALUE;
        updateClaimRectangle(claimStartX, claimStartZ);
        return true;
    }

    /** Called every frame: adds the chunks under the mouse, and finishes when the button was released. */
    private void updateClaimPaint(int mouseX, int mouseY) {
        if (claimButton < 0) {
            return;
        }
        if (!Mouse.isButtonDown(claimButton)) {
            finishClaimPaint();
            return;
        }
        int chunkX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        int chunkZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        updateClaimRectangle(chunkX, chunkZ);
    }

    /** Selects every fitting chunk in the rectangle from the start chunk to the given one. */
    private void updateClaimRectangle(int endX, int endZ) {
        if (endX == claimEndX && endZ == claimEndZ) {
            return;
        }
        claimEndX = endX;
        claimEndZ = endZ;
        claimSelection.clear();
        int dimension = mc.theWorld.provider.dimensionId;
        int minX = Math.min(claimStartX, endX), maxX = Math.max(claimStartX, endX);
        int minZ = Math.min(claimStartZ, endZ), maxZ = Math.max(claimStartZ, endZ);
        // Capped so a huge accidental drag can't send thousands of chunks.
        maxX = Math.min(maxX, minX + 63);
        maxZ = Math.min(maxZ, minZ + 63);
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                if (ClaimsLayer.accepts(claimAction, chunkX, chunkZ, dimension)) {
                    claimSelection.add(ClaimsLayer.pack(chunkX, chunkZ));
                }
            }
        }
    }

    private void finishClaimPaint() {
        if (claimButton < 0) {
            return;
        }
        claimButton = -1;
        ClaimsLayer.apply(claimAction, new ArrayList<>(claimSelection));
        claimSelection.clear();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (welcome) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN) {
                closeWelcome();
            }
            return;
        }
        if (dimensionList != null && keyCode == Keyboard.KEY_ESCAPE) {
            dimensionList = null;
            return;
        }
        if (menu != null && keyCode == Keyboard.KEY_ESCAPE) {
            menu = null;
            return;
        }
        if (searchAvailable() && searchField.isFocused()) {
            // Typing goes to the search; Esc clears it (or leaves the field when empty), Enter leaves the field.
            if (keyCode == Keyboard.KEY_ESCAPE && !searchField.getText()
                .isEmpty()) {
                searchField.setText("");
            } else if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER) {
                    searchField.setFocused(false);
                } else {
                    searchField.textboxKeyTyped(typedChar, keyCode);
                }
            if (!searchField.getText()
                .equals(searchText)) {
                searchText = searchField.getText();
                applySearch();
            }
            return;
        }
        if (keyCode == KeyHandler.OPEN_MAP.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (isoShown() && (keyCode == Keyboard.KEY_Q || keyCode == Keyboard.KEY_E)) {
            // Turns the 3D view: E clockwise, Q the other way.
            rotateIso(keyCode == Keyboard.KEY_E ? 1 : -1);
            return;
        }
        if (keyCode == Keyboard.KEY_SPACE && mc.thePlayer != null) {
            MapManager.INSTANCE.stopViewing();
            applySearch();
            centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
            zooming = false;
            scale = Config.MAP_ZOOMS[zoomIndex];
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        MapManager.INSTANCE.setSurfaceView(false);
        Keyboard.enableRepeatEvents(false);
        // Veins on the minimap go back to NEI's search; the map's search comes back when it is opened again.
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.setSearch("");
        }
        if (Mods.isPowerfailsAvailable()) {
            PowerfailLayer.setSearch("");
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            ThaumcraftNodes.setSearch("");
        }
        saveView();
        MapManager.INSTANCE.trimAroundPlayer(mc.thePlayer);
    }

    // ---------------------------------------------------------------- where the map was left

    /**
     * Where the map was last closed, per dimension the player was in: the dimension looked at, the center and the
     * zoom, as "dimension;x;z;zoom" in the account's world folder, so it survives restarts.
     */
    private static final String VIEW_FILE = "map-view.properties";

    private static Properties readViews(File file) {
        Properties views = new Properties();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                views.load(in);
            } catch (IOException e) {
                // Lost view positions only mean the map opens at the player.
            }
        }
        return views;
    }

    /** Opens the map where it was closed; false if there is nothing saved (or its dimension has no map). */
    private boolean restoreView() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null) {
            return false;
        }
        int playerDimension = mc.theWorld.provider.dimensionId;
        String saved = readViews(new File(worldDirectory, VIEW_FILE)).getProperty("dim" + playerDimension);
        if (saved == null) {
            return false;
        }
        try {
            String[] parts = saved.split(";");
            int dimension = Integer.parseInt(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            int zoom = Integer.parseInt(parts[3]);
            if (dimension != playerDimension) {
                boolean known = false;
                for (MapManager.SavedDimension other : MapManager.INSTANCE.listSavedDimensions()) {
                    known |= other.id == dimension;
                }
                if (!known) {
                    return false;
                }
                MapManager.INSTANCE.viewDimension(dimension);
            }
            centerX = x;
            centerZ = z;
            zoomIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoom));
            scale = Config.MAP_ZOOMS[zoomIndex];
            zooming = false;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void saveView() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null || mc.theWorld == null || !initialized) {
            return;
        }
        File file = new File(worldDirectory, VIEW_FILE);
        Properties views = readViews(file);
        views.setProperty(
            "dim" + mc.theWorld.provider.dimensionId,
            viewDimension() + ";" + centerX + ";" + centerZ + ";" + zoomIndex);
        try (OutputStream out = new FileOutputStream(file)) {
            views.store(out, "WayFarMap: where the world map was last closed, per dimension the player was in");
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save the map position to " + file, e);
        }
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
        // Twice a second: free the regions scrolled away from, so a long look around doesn't fill the memory.
        if (++ticks % 10 == 0 && mc.thePlayer != null) {
            double[] box = visibleWorldBox();
            MapManager.INSTANCE.trimForView(
                mc.thePlayer,
                (MathHelper.floor_double(box[0]) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(box[1]) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(box[2]) >> MapRegion.SHIFT) + 1,
                (MathHelper.floor_double(box[3]) >> MapRegion.SHIFT) + 1);
        }
    }

    /** World box {minX, minZ, maxX, maxZ} on the screen; in 3D all the ground that can show up, at any height. */
    private double[] visibleWorldBox() {
        if (!isoShown()) {
            double halfWidth = width / 2.0 / scale, halfHeight = height / 2.0 / scale;
            return new double[] { centerX - halfWidth, centerZ - halfHeight, centerX + halfWidth,
                centerZ + halfHeight };
        }
        IsoProjection p = isoProjection();
        double u0 = p.u(centerX, centerZ), v0 = p.v(centerX, IsoProjection.REFERENCE_Y, centerZ);
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (double sx : new double[] { -width / 2.0, width / 2.0 }) {
            for (double sy : new double[] { -height / 2.0, height / 2.0 }) {
                for (double y : new double[] { 0, IsoProjection.TOP }) {
                    double[] point = p.unproject(u0 + sx / scale, v0 + sy / scale, y);
                    box[0] = Math.min(box[0], point[0]);
                    box[1] = Math.min(box[1], point[1]);
                    box[2] = Math.max(box[2], point[0]);
                    box[3] = Math.max(box[3], point[1]);
                }
            }
        }
        return box;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
