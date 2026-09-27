package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;

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
import WayFarMap.client.gui.ui.Theme;
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
        ID_BIOMES = 5;
    private static final int SLIDER_WIDTH = 10;
    private static final int MENU_WIDTH = 130, MENU_ROW = 14;
    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };

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

    /** Right click menu; null when closed. */
    private List<MenuEntry> menu;
    private int menuX, menuY;
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

    @Override
    public void initGui() {
        super.initGui();
        if (!initialized && mc.thePlayer != null) {
            // Only on first open, not when the window is resized.
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            initialized = true;
        }
        lastFrameNanos = System.nanoTime();
        buttonList.clear();
        int x = 4;
        x = addHeaderButton(new FlatButton(ID_WAYPOINTS, x, 4, 0, 16, I18n.format("wayfarmap.gui.waypoints")), x);
        addHeaderButton(new FlatButton(ID_SETTINGS, x, 4, 0, 16, I18n.format("wayfarmap.gui.settings")), x);

        // Right side, laid out right to left before the zoom text.
        int right = width - 34;
        nightButton = new FlatButton(ID_NIGHT, 0, 4, 0, 16, I18n.format("wayfarmap.gui.night"));
        dayButton = new FlatButton(ID_DAY, 0, 4, 0, 16, I18n.format("wayfarmap.gui.day"));
        caveButton = new FlatButton(ID_CAVES, 0, 4, 0, 16, caveButtonText());
        biomeButton = new FlatButton(ID_BIOMES, 0, 4, 0, 16, I18n.format("wayfarmap.gui.biomes"));
        for (FlatButton button : new FlatButton[] { nightButton, dayButton, caveButton, biomeButton }) {
            int w = fontRendererObj.getStringWidth(button.displayString) + 12;
            if (button == caveButton) {
                // Room for longer mode names, so the button doesn't jump when cycling.
                w += 12;
            }
            button.setWidth(w);
            right -= w;
            button.xPosition = right;
            right -= 4;
            buttonList.add(button);
        }
        updateLightButtons();
        menu = null;
    }

    private int addHeaderButton(FlatButton button, int x) {
        button.setWidth(fontRendererObj.getStringWidth(button.displayString) + 12);
        buttonList.add(button);
        return x + button.getWidth() + 4;
    }

    private static String caveButtonText() {
        return I18n.format("wayfarmap.gui.caves") + ": "
            + I18n.format("wayfarmap.option.map.caveMode." + CAVE_MODE_KEYS[Config.caveMode]);
    }

    /** The forced mode is highlighted; with both off the map follows the time of day. */
    private void updateLightButtons() {
        dayButton.active = Config.mapLightMode == Config.LIGHT_DAY;
        nightButton.active = Config.mapLightMode == Config.LIGHT_NIGHT;
        caveButton.active = Config.caveMode == Config.CAVES_ON;
        caveButton.displayString = caveButtonText();
        biomeButton.active = Config.mapDisplayMode == Config.DISPLAY_BIOMES;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_WAYPOINTS) {
            mc.displayGuiScreen(new GuiWaypointList(this));
        } else if (button.id == ID_BIOMES) {
            Config.toggleBiomeView();
            updateLightButtons();
        } else if (button.id == ID_CAVES) {
            Config.cycleCaveMode();
            updateLightButtons();
        } else if (button.id == ID_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
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

        MapDimension dimension = MapManager.INSTANCE.getDimension();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        updateView();
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
        MapDrawer.drawEntities(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, 8f, true);

        drawWaypoints(mouseX, mouseY);

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double playerScreenX = width / 2.0 + (px - centerX) * scale;
        double playerScreenY = height / 2.0 + (pz - centerZ) * scale;
        if (playerScreenX >= 0 && playerScreenY >= 0 && playerScreenX <= width && playerScreenY <= height) {
            float yaw = mc.thePlayer.prevRotationYaw
                + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;
            MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, yaw, 5f, 0xFFFFFFFF);
        }

        // Header and footer.
        Theme.fill(0, 0, width, HEADER_HEIGHT, Theme.PANEL);
        Theme.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, Theme.BORDER);
        String title = I18n.format("wayfarmap.gui.title");
        int titleWidth = fontRendererObj.getStringWidth(title);
        if (width / 2 - titleWidth / 2 > headerLeftEnd() + 8 && width / 2 + titleWidth / 2 < biomeButton.xPosition - 8) {
            Theme.centered(fontRendererObj, title, width / 2, 8, Theme.TEXT_MUTED);
        }
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
        int caveLayer = MapManager.INSTANCE.getActiveCaveLayer();
        if (caveLayer >= 0) {
            cursorText += "  |  " + I18n.format("wayfarmap.gui.cave_layer", caveLayer * 16, caveLayer * 16 + 15);
        }
        if (caveLayer < 0 && Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            BiomeGenBase biome = MapManager.INSTANCE.getBiome(hoverX, hoverZ);
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
        String help = I18n.format("wayfarmap.gui.help");
        Theme.text(fontRendererObj, help, width - 6 - fontRendererObj.getStringWidth(help), height - 10, Theme.TEXT_MUTED);

        GL11.glColor4f(1f, 1f, 1f, 1f);
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (caveLayer >= 0) {
            drawCaveSlider(mouseX, mouseY);
        }
        if (menu != null) {
            drawMenu(mouseX, mouseY);
        }
    }

    private int headerLeftEnd() {
        int end = 0;
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id == ID_WAYPOINTS || button.id == ID_SETTINGS) {
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
        return MapManager.INSTANCE.getActiveCaveLayer() >= 0
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
        int layer = MapManager.INSTANCE.getActiveCaveLayer();

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

    // ---------------------------------------------------------------- right click menu

    private void openMenu(int mouseX, int mouseY) {
        int dimension = mc.theWorld.provider.dimensionId;
        List<MenuEntry> entries = new ArrayList<>();
        final int bx = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale);
        final int bz = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale);
        final int safeY = Teleport.findSafeY(mc.theWorld, bx, bz);
        entries.add(new MenuEntry(I18n.format("wayfarmap.gui.teleport_here"), Teleport.isAllowed(), () -> {
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
                    GuiEditWaypoint.create(this, bx, safeY > 0 ? safeY : surfaceY(bx, bz), bz, dimension))));
        menu = entries;
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
        if (!Teleport.isAllowed()) {
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
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(mc.theWorld.provider.dimensionId)) {
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

        // Labels: skip any that would overlap a label already placed, starting with the hovered one.
        List<Waypoint> labelled = new ArrayList<>();
        List<int[]> rects = new ArrayList<>();
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            Waypoint waypoint = onScreen.get(i);
            int[] rect = WaypointRenderer
                .getLabelRect(waypoint, screenX(waypoint), screenY(waypoint), size, waypoint == hovered);
            if (rect == null || (waypoint != hovered && overlapsAny(rect, rects))) {
                continue;
            }
            labelled.add(waypoint);
            rects.add(rect);
        }
        for (int i = labelled.size() - 1; i >= 0; i--) {
            WaypointRenderer.drawMapLabel(labelled.get(i), rects.get(i), labelled.get(i) == hovered);
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
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(mc.theWorld.provider.dimensionId)) {
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
        MapRegion region = dimension.getLoadedRegion(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        return region != null
            && (region.getPixel(x & (MapRegion.SIZE - 1), z & (MapRegion.SIZE - 1)) >>> 24) != 0;
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
        if (clickMenu(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (mouseY < HEADER_HEIGHT || mouseY >= height - FOOTER_HEIGHT || mc.currentScreen != this) {
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
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (menu != null && keyCode == Keyboard.KEY_ESCAPE) {
            menu = null;
            return;
        }
        if (keyCode == KeyHandler.OPEN_MAP.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_SPACE && mc.thePlayer != null) {
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
        MapManager.INSTANCE.trimAroundPlayer(mc.thePlayer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
