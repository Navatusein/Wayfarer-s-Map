package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.BiomeHighlight;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;

/** Fullscreen world map: drag to pan, mouse wheel to zoom. */
public class GuiWorldMap extends GuiScreen {

    private static final int DEFAULT_ZOOM = 3;

    /** Zoom level is kept between openings of the map. */
    private static int zoomIndex = DEFAULT_ZOOM;

    private static final int HEADER_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 14;
    private static final float MARKER_SIZE = 12f;
    private static final float MIN_MARKER_SIZE = 6f;
    private static final int ID_WAYPOINTS = 0, ID_DAY = 1, ID_NIGHT = 2, ID_SETTINGS = 3, ID_CAVES = 4,
        ID_BIOMES = 5, ID_GRID = 6, ID_ORES = 7, ID_FLUIDS = 8, ID_CLAIMS = 9, ID_HELP = 10,
        ID_MOBS = 11;
    private static final int SLIDER_WIDTH = 10;
    private static final int MENU_WIDTH = 130, MENU_ROW = 14;
    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };
    /** Lang key suffixes of {@link Config#getMobFilter()} values. */
    private static final String[] MOB_FILTER_KEYS = { "all", "friendly", "hostile", "none" };

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

    private FlatButton dayButton;
    private FlatButton nightButton;
    private FlatButton caveButton;
    private FlatButton biomeButton;
    private FlatButton gridButton;
    private FlatButton mobsButton;
    /** Search of biomes, ore veins or fluids; kept between openings of the map. */
    private static String searchText = "";
    private FlatTextField searchField;
    /** VisualProspecting layers; null when it isn't installed. */
    private FlatButton oreButton, fluidButton;
    /** ServerUtilities claims layer; null when it isn't installed. */
    private FlatButton claimsButton;
    private FlatButton helpButton;

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
    /** The right click map menu shows a note under it when teleporting isn't allowed. */
    private boolean menuNote;
    private boolean draggingCaveSlider;

    /** One line of the right click menu. */
    private static final class MenuEntry {

        final String label;
        final boolean enabled;
        final Runnable action;

        MenuEntry(String label, boolean enabled, Runnable action) {
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private boolean dragging;
    private int lastRawMouseX;
    private int lastRawMouseY;
    private long lastFrameNanos;
    private int ticks;

    @Override
    public void initGui() {
        super.initGui();
        if (!initialized && mc.thePlayer != null) {
            // Only on first open, not when the window is resized.
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            initialized = true;
            // A freshly opened map shows the dimension the player is in.
            MapManager.INSTANCE.stopViewing();
            if (Mods.isVisualProspectingLoaded()) {
                ProspectingLayer.onOpenMap();
            }
        }
        lastFrameNanos = System.nanoTime();
        buttonList.clear();
        int x = 4;
        x = addHeaderButton(new FlatButton(ID_WAYPOINTS, x, 4, 0, 16, I18n.format("wayfarmap.gui.waypoints")), x);
        x = addHeaderButton(new FlatButton(ID_SETTINGS, x, 4, 0, 16, I18n.format("wayfarmap.gui.settings")), x);
        if (Mods.isVisualProspectingLoaded()) {
            oreButton = new FlatButton(ID_ORES, x, 4, 0, 16, I18n.format("wayfarmap.gui.ores"));
            x = addHeaderButton(oreButton, x);
            fluidButton = new FlatButton(ID_FLUIDS, x, 4, 0, 16, I18n.format("wayfarmap.gui.fluids"));
            x = addHeaderButton(fluidButton, x);
        }
        if (Mods.isClaimsAvailable()) {
            claimsButton = new FlatButton(ID_CLAIMS, x, 4, 0, 16, I18n.format("wayfarmap.gui.claims"));
            addHeaderButton(claimsButton, x);
        }

        // Right side, laid out right to left before the zoom text.
        int right = width - 34;
        nightButton = new FlatButton(ID_NIGHT, 0, 4, 0, 16, I18n.format("wayfarmap.gui.night"));
        dayButton = new FlatButton(ID_DAY, 0, 4, 0, 16, I18n.format("wayfarmap.gui.day"));
        caveButton = new FlatButton(ID_CAVES, 0, 4, 0, 16, caveButtonText());
        biomeButton = new FlatButton(ID_BIOMES, 0, 4, 0, 16, I18n.format("wayfarmap.gui.biomes"));
        gridButton = new FlatButton(ID_GRID, 0, 4, 0, 16, I18n.format("wayfarmap.gui.grid"));
        mobsButton = new FlatButton(ID_MOBS, 0, 4, 0, 16, mobsButtonText(Config.getMobFilter()));
        for (FlatButton button : new FlatButton[] { nightButton, dayButton, caveButton, biomeButton, gridButton,
            mobsButton }) {
            int w = fontRendererObj.getStringWidth(button.displayString) + 12;
            if (button == caveButton) {
                // Room for longer mode names, so the button doesn't jump when cycling.
                w += 12;
            } else if (button == mobsButton) {
                // As wide as the longest filter name, so the button doesn't jump either.
                for (int filter = 0; filter < MOB_FILTER_KEYS.length; filter++) {
                    w = Math.max(w, fontRendererObj.getStringWidth(mobsButtonText(filter)) + 12);
                }
            }
            button.setWidth(w);
            right -= w;
            button.xPosition = right;
            right -= 4;
            buttonList.add(button);
        }
        updateLightButtons();
        menu = null;
        dimensionList = null;

        // Bottom right: the help screen with every feature explained.
        String helpText = "? " + I18n.format("wayfarmap.gui.help_button");
        int helpWidth = fontRendererObj.getStringWidth(helpText) + 12;
        helpButton = new FlatButton(ID_HELP, width - helpWidth - 2, height - FOOTER_HEIGHT + 1, helpWidth, 13, helpText);
        buttonList.add(helpButton);

        Keyboard.enableRepeatEvents(true);
        searchField = new FlatTextField(fontRendererObj, width / 2 - 90, HEADER_HEIGHT + 4, 180, 14)
            .setHint(I18n.format("wayfarmap.gui.search_hint"));
        searchField.setMaxStringLength(40);
        searchField.setText(searchText);
        applySearch();
    }

    /** The search field shows up in biome view and with the ore vein or fluid layer. */
    private boolean searchAvailable() {
        return biomeViewShown() || prospectingLayerShown();
    }

    private boolean biomeViewShown() {
        return Config.mapDisplayMode == Config.DISPLAY_BIOMES;
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
    }

    private int addHeaderButton(FlatButton button, int x) {
        button.setWidth(fontRendererObj.getStringWidth(button.displayString) + 12);
        buttonList.add(button);
        return x + button.getWidth() + 4;
    }

    private static String mobsButtonText(int filter) {
        return I18n.format("wayfarmap.gui.mobs") + ": " + I18n.format("wayfarmap.gui.mobs." + MOB_FILTER_KEYS[filter]);
    }

    /** Menu under the "Mobs" button: show all mobs, only friendly, only hostile or none. */
    private void openMobsMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        int current = Config.getMobFilter();
        for (int filter = 0; filter < MOB_FILTER_KEYS.length; filter++) {
            final int value = filter;
            String label = (filter == current ? "\u25CF " : "   ")
                + I18n.format("wayfarmap.gui.mobs.menu." + MOB_FILTER_KEYS[filter]);
            entries.add(new MenuEntry(label, true, () -> {
                Config.setMobFilter(value);
                updateLightButtons();
            }));
        }
        menu = entries;
        menuNote = false;
        menuX = Math.max(2, Math.min(mobsButton.xPosition, width - MENU_WIDTH - 2));
        menuY = mobsButton.yPosition + 18;
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
        dayButton.active = Config.mapLightMode == Config.LIGHT_DAY;
        nightButton.active = Config.mapLightMode == Config.LIGHT_NIGHT;
        caveButton.active = Config.caveMode == Config.CAVES_ON;
        caveButton.displayString = caveButtonText();
        biomeButton.active = Config.mapDisplayMode == Config.DISPLAY_BIOMES;
        gridButton.active = Config.chunkGrid;
        int mobFilter = Config.getMobFilter();
        mobsButton.displayString = mobsButtonText(mobFilter);
        // Highlighted while some mobs are hidden.
        mobsButton.active = mobFilter != Config.MOBS_ALL;
        if (oreButton != null) {
            oreButton.active = Config.showOreVeins;
            fluidButton.active = Config.showUndergroundFluids;
        }
        if (claimsButton != null) {
            claimsButton.active = Config.showClaims;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_WAYPOINTS) {
            mc.displayGuiScreen(new GuiWaypointList(this));
        } else if (button.id == ID_CLAIMS) {
            Config.toggleClaims();
            if (Config.showClaims) {
                ClaimsLayer.onShow();
            }
            updateLightButtons();
        } else if (button.id == ID_ORES) {
            Config.toggleOreVeins();
            updateLightButtons();
        } else if (button.id == ID_FLUIDS) {
            Config.toggleUndergroundFluids();
            updateLightButtons();
        } else if (button.id == ID_GRID) {
            Config.toggleChunkGrid();
            updateLightButtons();
        } else if (button.id == ID_BIOMES) {
            Config.toggleBiomeView();
            updateLightButtons();
        } else if (button.id == ID_CAVES) {
            Config.cycleCaveMode();
            updateLightButtons();
        } else if (button.id == ID_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
        } else if (button.id == ID_MOBS) {
            if (menu != null && !menuNote) {
                menu = null;
            } else {
                openMobsMenu();
            }
        } else if (button.id == ID_HELP) {
            mc.displayGuiScreen(new GuiHelp(this));
        } else if (button.id == ID_DAY) {
            Config.setMapLightMode(Config.mapLightMode == Config.LIGHT_DAY ? Config.LIGHT_AUTO : Config.LIGHT_DAY);
            updateLightButtons();
        } else if (button.id == ID_NIGHT) {
            Config.setMapLightMode(Config.mapLightMode == Config.LIGHT_NIGHT ? Config.LIGHT_AUTO : Config.LIGHT_NIGHT);
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
                centerX -= dx / scale;
                centerZ -= dy / scale;
                if (zooming) {
                    anchorWorldX -= dx / scale;
                    anchorWorldZ -= dy / scale;
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
            centerX = anchorWorldX - (anchorScreenX - width / 2.0) / scale;
            centerZ = anchorWorldZ - (anchorScreenY - height / 2.0) / scale;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF0C0E11);

        MapDimension dimension = MapManager.INSTANCE.getViewMap();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        updateView();
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
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
        // The dimension shown: the player's, or another one picked from the title.
        int dimensionId = viewDimension();
        boolean otherDimension = MapManager.INSTANCE.isViewingOtherDimension();
        if (claimsShown()) {
            updateClaimPaint(mouseX, mouseY);
            ClaimsLayer.draw(
                dimensionId,
                centerX,
                centerZ,
                scale,
                0,
                0,
                width,
                height,
                claimSelection,
                claimAction);
        }
        if (prospecting && Config.showUndergroundFluids) {
            ProspectingLayer.drawFluids(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false);
        }
        if (prospecting && Config.showOreVeins) {
            ProspectingLayer
                .drawOreVeins(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY);
        }
        if (!otherDimension) {
            MapDrawer.drawEntities(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, 8f, true);
        }

        drawWaypoints(mouseX, mouseY);

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double playerScreenX = width / 2.0 + (px - centerX) * scale;
        double playerScreenY = height / 2.0 + (pz - centerZ) * scale;
        if (!otherDimension && playerScreenX >= 0 && playerScreenY >= 0 && playerScreenX <= width && playerScreenY <= height) {
            float yaw = mc.thePlayer.prevRotationYaw
                + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;
            MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, yaw, 5f, 0xFFFFFFFF);
        }

        // Header and footer.
        Theme.fill(0, 0, width, HEADER_HEIGHT, Theme.PANEL);
        Theme.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, Theme.BORDER);
        drawTitle(mouseX, mouseY, dimensionId, otherDimension);
        double targetScale = Config.MAP_ZOOMS[zoomIndex];
        String zoomText = targetScale >= 1 ? (int) targetScale + ":1" : "1:" + (int) Math.round(1 / targetScale);
        Theme.text(fontRendererObj, zoomText, width - 6 - fontRendererObj.getStringWidth(zoomText), 8, Theme.TEXT_MUTED);

        Theme.fill(0, height - FOOTER_HEIGHT, width, height, Theme.PANEL);
        Theme.fill(0, height - FOOTER_HEIGHT, width, height - FOOTER_HEIGHT + 1, Theme.BORDER);
        int hoverX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale);
        int hoverZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale);
        String cursorText = "X: " + hoverX + "  Z: " + hoverZ;
        if (!isExplored(dimension, hoverX, hoverZ)) {
            cursorText += "  (?)";
        }
        // Biome view shows biomes of whole columns, so there is no cave layer to pick.
        int caveLayer = biomeViewShown() ? -1 : MapManager.INSTANCE.getViewCaveLayer();
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
            cursorText = hoveredWaypoint.name + "  (" + hoveredWaypoint.x + ", " + hoveredWaypoint.y + ", "
                + hoveredWaypoint.z + ")";
        }
        Theme.text(fontRendererObj, cursorText, 6, height - 10, Theme.TEXT);
        if (claimsShown()) {
            String counts = ClaimsLayer.countsText();
            Theme.text(
                fontRendererObj,
                counts,
                helpButton.xPosition - 8 - fontRendererObj.getStringWidth(counts),
                height - 10,
                Theme.TEXT_MUTED);
        } else if (Config.showClaims && Mods.isClaimsAvailable() && otherDimension) {
            // ServerUtilities takes the dimension of every claim change from the player, so claims can only be
            // shown and changed in the dimension the player is in.
            String note = I18n.format("wayfarmap.claims.other_dimension");
            Theme.text(
                fontRendererObj,
                note,
                helpButton.xPosition - 8 - fontRendererObj.getStringWidth(note),
                height - 10,
                Theme.DANGER);
        }

        GL11.glColor4f(1f, 1f, 1f, 1f);
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (caveLayer >= 0) {
            drawCaveSlider(mouseX, mouseY);
        }
        if (searchAvailable()) {
            searchField.drawTextBox();
        }
        if (dimensionList != null) {
            drawDimensionList(mouseX, mouseY);
        } else if (menu != null) {
            drawMenu(mouseX, mouseY);
        } else if (mouseY > HEADER_HEIGHT && mouseY < height - FOOTER_HEIGHT && claimButton < 0) {
            List<String> tooltip = prospecting && Config.showOreVeins ? ProspectingLayer.getHoveredTooltip() : null;
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
                || button.id == ID_ORES
                || button.id == ID_FLUIDS
                || button.id == ID_CLAIMS) {
                end = Math.max(end, button.xPosition + ((FlatButton) button).getWidth());
            }
        }
        return end;
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
        return MapManager.INSTANCE.getViewCaveLayer() >= 0 && !biomeViewShown()
            && Theme.inside(mouseX, mouseY, sliderX() - 4, sliderAutoTop(), sliderX() + SLIDER_WIDTH + 4, sliderBottom());
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
        if (width / 2 - titleWidth / 2 <= headerLeftEnd() + 8 || width / 2 + titleWidth / 2 >= mobsButton.xPosition - 8) {
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
                fontRendererObj.getStringWidth(dimensionLabel(dimension)) + fontRendererObj.getStringWidth("  " + here));
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
                Theme.fill(dimensionListX + 1, y, dimensionListX + dimensionListWidth - 1, y + MENU_ROW, Theme.ACCENT_DIM);
            } else if (Theme.inside(mouseX, mouseY, dimensionListX, y, dimensionListX + dimensionListWidth, y + MENU_ROW)) {
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

    /** Switches the map to a saved dimension, keeping the view roughly in place (Nether coordinates are 1:8). */
    private void showDimension(int id) {
        int from = viewDimension();
        if (id == from) {
            return;
        }
        MapManager.INSTANCE.viewDimension(id);
        if (id == mc.theWorld.provider.dimensionId) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
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
        final Object vein = Mods.isVisualProspectingLoaded() && Config.showOreVeins ? ProspectingLayer.getHoveredVein()
            : null;
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
                        ProspectingLayer.isDepleted(vein) ? "wayfarmap.gui.vein_restore" : "wayfarmap.gui.vein_deplete"),
                    true,
                    () -> ProspectingLayer.toggleDepleted(vein)));
        }
        final int bx = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale);
        final int bz = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale);
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
                    GuiEditWaypoint.create(this, bx, safeY > 0 ? safeY : waypointY(bx, bz), bz, dimension))));
        menu = entries;
        menuNote = true;
        menuX = Math.min(mouseX, width - MENU_WIDTH - 2);
        menuY = Math.min(mouseY, height - entries.size() * MENU_ROW - 6);
    }

    private void drawMenu(int mouseX, int mouseY) {
        int h = menu.size() * MENU_ROW + 4;
        Theme.panel(menuX, menuY, menuX + MENU_WIDTH, menuY + h);
        for (int i = 0; i < menu.size(); i++) {
            MenuEntry entry = menu.get(i);
            int y = menuY + 2 + i * MENU_ROW;
            if (entry.enabled && Theme.inside(mouseX, mouseY, menuX, y, menuX + MENU_WIDTH, y + MENU_ROW)) {
                Theme.fill(menuX + 1, y, menuX + MENU_WIDTH - 1, y + MENU_ROW, Theme.CONTROL_HOVER);
            }
            Theme.text(
                fontRendererObj,
                entry.label,
                menuX + 6,
                y + 3,
                entry.enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        }
        if (menuNote && !Teleport.isAllowed()) {
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
            if (Theme.inside(mouseX, mouseY, menuX, y, menuX + MENU_WIDTH, y + MENU_ROW) && entry.enabled) {
                entry.action.run();
            }
        }
        return true;
    }

    /** Marker size in GUI pixels: markers shrink when zooming out so nearby waypoints don't pile up. */
    private float markerSize() {
        return (float) Math.max(MIN_MARKER_SIZE, Math.min(MARKER_SIZE, MARKER_SIZE * Math.pow(scale, 0.4)));
    }

    private void drawWaypoints(int mouseX, int mouseY) {
        float size = markerSize();
        Waypoint hovered = waypointAt(mouseX, mouseY);
        List<Waypoint> onScreen = new ArrayList<>();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(viewDimension())) {
            double wx = width / 2.0 + (waypoint.x + 0.5 - centerX) * scale;
            double wy = height / 2.0 + (waypoint.z + 0.5 - centerZ) * scale;
            if (wx > -size && wy > -size && wx < width + size && wy < height + size && waypoint != hovered) {
                onScreen.add(waypoint);
            }
        }
        if (hovered != null) {
            // Drawn last so it is on top, and its label always wins.
            onScreen.add(hovered);
        }

        for (Waypoint waypoint : onScreen) {
            WaypointRenderer.drawMapMarker(waypoint, screenX(waypoint), screenY(waypoint), size, false);
        }

        // Labels shrink with the markers when zooming out and stop growing at normal size when zooming in.
        float textScale = size / MARKER_SIZE;
        // Labels: skip any that would overlap a label already placed, starting with the hovered one.
        List<Waypoint> labelled = new ArrayList<>();
        List<int[]> rects = new ArrayList<>();
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            Waypoint waypoint = onScreen.get(i);
            int[] rect = WaypointRenderer.getLabelRect(waypoint, screenX(waypoint), screenY(waypoint), size, textScale);
            if (rect == null || (waypoint != hovered && overlapsAny(rect, rects))) {
                continue;
            }
            labelled.add(waypoint);
            rects.add(rect);
        }
        for (int i = labelled.size() - 1; i >= 0; i--) {
            WaypointRenderer.drawMapLabel(labelled.get(i), rects.get(i), textScale);
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

    private double screenX(Waypoint waypoint) {
        return width / 2.0 + (waypoint.x + 0.5 - centerX) * scale;
    }

    private double screenY(Waypoint waypoint) {
        return height / 2.0 + (waypoint.z + 0.5 - centerZ) * scale;
    }

    private Waypoint waypointAt(int mouseX, int mouseY) {
        Waypoint best = null;
        double bestDistance = markerSize() / 2 + 2;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(viewDimension())) {
            double wx = width / 2.0 + (waypoint.x + 0.5 - centerX) * scale;
            double wy = height / 2.0 + (waypoint.z + 0.5 - centerZ) * scale;
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
        if (wheel == 0) {
            return;
        }
        int newIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoomIndex + (wheel > 0 ? 1 : -1)));
        if (newIndex == zoomIndex) {
            return;
        }
        // Keep the block under the cursor in place while zooming.
        anchorScreenX = Mouse.getEventX() * (double) width / mc.displayWidth;
        anchorScreenY = height - Mouse.getEventY() * (double) height / mc.displayHeight;
        anchorWorldX = centerX + (anchorScreenX - width / 2.0) / scale;
        anchorWorldZ = centerZ + (anchorScreenY - height / 2.0) / scale;
        zoomIndex = newIndex;
        zooming = true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
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
        if (claimsShown() && (button == 0 || button == 1) && startClaimPaint(mouseX, mouseY, button)) {
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
            // Right click on a waypoint edits it (teleport is in the editor); elsewhere a small menu to teleport
            // there or create a waypoint.
            Waypoint hovered = waypointAt(mouseX, mouseY);
            if (hovered != null) {
                mc.displayGuiScreen(GuiEditWaypoint.edit(this, hovered));
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
        if (keyCode == Keyboard.KEY_SPACE && mc.thePlayer != null) {
            MapManager.INSTANCE.stopViewing();
            applySearch();
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            zooming = false;
            scale = Config.MAP_ZOOMS[zoomIndex];
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        // Veins on the minimap go back to NEI's search; the map's search comes back when it is opened again.
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.setSearch("");
        }
        MapManager.INSTANCE.trimAroundPlayer(mc.thePlayer);
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
        // Twice a second: free the regions scrolled away from, so a long look around doesn't fill the memory.
        if (++ticks % 10 == 0 && mc.thePlayer != null) {
            double halfWidth = width / 2.0 / scale, halfHeight = height / 2.0 / scale;
            MapManager.INSTANCE.trimForView(
                mc.thePlayer,
                (MathHelper.floor_double(centerX - halfWidth) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(centerZ - halfHeight) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(centerX + halfWidth) >> MapRegion.SHIFT) + 1,
                (MathHelper.floor_double(centerZ + halfHeight) >> MapRegion.SHIFT) + 1);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
