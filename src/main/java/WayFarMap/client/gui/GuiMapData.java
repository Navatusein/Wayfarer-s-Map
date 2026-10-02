package WayFarMap.client.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.MapCleaner;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;

/**
 * Map data: how much disk the maps take and cleaning them up, on one screen. At the top, all worlds played; below,
 * this world, all its dimensions together or the one picked: its flat (2D) and 3D map, each with its share of the
 * bar and a button that deletes it, the waypoints and what was found with the mods (GregTech ore veins, underground
 * fluids, Thaumcraft nodes); at the bottom, the logs. Each delete button asks again before it deletes; waypoints are
 * never deleted. The sizes are counted again in the background each time the screen is opened and after each
 * cleaning; until then the last count is shown, dimmed.
 */
public class GuiMapData extends ScaledScreen {

    private static final int ID_DONE = 0, ID_DIMENSION = 1, ID_REFRESH = 2, ID_DEL_2D = 3, ID_DEL_3D = 4,
        ID_DEL_ALL = 5, ID_LOGS = 6;
    /** Height of a line of the dimension list, of a row with a button, and of a tile. */
    private static final int LIST_ROW = 14, ROW = 20, TILE = 24, BUTTON_WIDTH = 84;
    /** How long a button waits for the second click that confirms it. */
    private static final long CONFIRM_MS = 4000;
    /** Colors of the 3D map in the bar and the rows (the 2D map takes the accent). */
    private static final int ISO_COLOR = 0xFFA371F7;

    /** One count of the map folders. */
    private static final class Data {

        final File worldDirectory;
        /** This world, per dimension: {2D, 3D}, as the cleaning would delete them. */
        final Map<Integer, long[]> dimensions = new HashMap<>();
        /** Pictures of blocks for the 3D map, shared by the dimensions of this world. */
        long sprites;
        /** All worlds: how many, and their {2D, 3D, other} sizes. */
        int worlds;
        final long[] all = new long[3];
        /** The 2D and 3D maps' logs, {@code wayfarmap/logs}. */
        long logs;
        long countedAt;

        Data(File worldDirectory) {
            this.worldDirectory = worldDirectory;
        }

        /** {2D, 3D} of a dimension, or of all of them (null; the pictures of blocks count with the 3D map). */
        long[] sizes(Integer dimension) {
            if (dimension != null) {
                long[] row = dimensions.get(dimension);
                return row == null ? new long[2] : row;
            }
            long[] sum = { 0, sprites };
            for (long[] row : dimensions.values()) {
                sum[0] += row[0];
                sum[1] += row[1];
            }
            return sum;
        }
    }

    /** The last count, kept while the game runs so it shows at once the next time. */
    private static volatile Data last;
    /** The count running now, if any. */
    private static volatile Thread counting;
    /** Dimension picked, null for all of them; kept between openings. */
    private static Integer picked;

    private final GuiScreen parent;
    private final File logsDirectory;
    /**
     * This world's folder, kept: a cleaning closes the maps, and until the next tick opens them again the map
     * manager has none (the count right after the cleaning still needs it).
     */
    private File worldDirectory;
    private int left, top, right, bottom;
    /** Counted once per opening (not again when the window is resized). */
    private boolean started;
    /** Found with the mods, per dimension; null without the mod. */
    private Map<Integer, int[]> prospected;
    private Map<Integer, Integer> nodes;
    private final Map<Integer, Integer> waypoints = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private FlatButton dimensionButton, refreshButton;
    /** The dimension list is open, and how far it is scrolled (lines). */
    private boolean listOpen;
    private int listScroll;
    /** Button waiting for its second click, and since when. */
    private int armed = -1;
    private long armedAt;
    /** What the last cleaning did. */
    private String status = "";

    public GuiMapData(GuiScreen parent) {
        this.parent = parent;
        this.logsDirectory = new File(
            new File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "wayfarmap"),
            "logs");
    }

    /** This world's folder: the map manager's, or the one kept while the maps are closed. */
    private File worldDirectory() {
        File current = MapManager.INSTANCE.getWorldDirectory();
        if (current != null) {
            worldDirectory = current;
        }
        return worldDirectory;
    }

    // ---------------------------------------------------------------- layout

    private int tilesY() {
        return top + 40;
    }

    private int worldY() {
        return top + 76;
    }

    private int barY() {
        return worldY() + 22;
    }

    /** Text line of a row of this world: 2D, 3D, whole map. */
    private int rowY(int row) {
        return barY() + 12 + row * ROW;
    }

    private int foundY() {
        return rowY(3) - 2;
    }

    private int logsY() {
        return foundY() + TILE + 16;
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 400);
        int panelHeight = Math.min(height - 16, 300);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        buttonList.clear();
        String refresh = I18n.format("wayfarmap.data.refresh");
        int refreshWidth = fontRendererObj.getStringWidth(refresh) + 14;
        refreshButton = new FlatButton(ID_REFRESH, right - 10 - refreshWidth, top + 6, refreshWidth, 14, refresh);
        buttonList.add(refreshButton);
        // The picked dimension, after the section's title; a click opens the list of them under it.
        int pickerX = left + 16 + fontRendererObj.getStringWidth(I18n.format("wayfarmap.stats.this_world"));
        dimensionButton = new FlatButton(ID_DIMENSION, pickerX, worldY() - 4, right - 10 - pickerX, 16, "");
        buttonList.add(dimensionButton);
        int x = right - 10 - BUTTON_WIDTH;
        buttonList.add(dangerButton(ID_DEL_2D, x, rowY(0)));
        buttonList.add(dangerButton(ID_DEL_3D, x, rowY(1)));
        buttonList.add(dangerButton(ID_DEL_ALL, x, rowY(2)));
        buttonList.add(dangerButton(ID_LOGS, x, logsY()));
        buttonList.add(new FlatButton(ID_DONE, right - 88, bottom - 26, 80, 18, I18n.format("gui.done")));
        listOpen = false;
        if (!started) {
            started = true;
            if (counting == null) {
                startCount();
            }
            countFound();
        }
    }

    private static FlatButton dangerButton(int id, int x, int y) {
        FlatButton button = new FlatButton(id, x, y - 4, BUTTON_WIDTH, 16, "");
        button.danger = true;
        return button;
    }

    // ---------------------------------------------------------------- counting

    /**
     * What this world has besides its map files: waypoints, and what was found with VisualProspecting (GregTech ore
     * veins, underground fluids) and TCNodeTracker (nodes). Render thread: those mods keep them there.
     */
    private void countFound() {
        waypoints.clear();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            waypoints.merge(waypoint.dimension, 1, Integer::sum);
        }
        if (Mods.isVisualProspectingLoaded()) {
            try {
                prospected = ProspectingLayer.countFound();
            } catch (Throwable t) {
                prospected = null;
            }
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            try {
                nodes = ThaumcraftNodes.countFound();
            } catch (Throwable t) {
                nodes = null;
            }
        }
    }

    /**
     * Counts the sizes in the background: walking big maps' folders takes a while. A count started later (after a
     * cleaning) wins over one still running.
     */
    private void startCount() {
        File worldDirectory = worldDirectory();
        if (worldDirectory == null) {
            return;
        }
        File root = new File(mc.mcDataDir, "wayfarmap");
        Thread thread = new Thread(() -> {
            Data data = count(root, worldDirectory);
            if (counting == Thread.currentThread()) {
                last = data;
                counting = null;
            }
        }, "Wayfarer's Map data");
        thread.setDaemon(true);
        counting = thread;
        thread.start();
    }

    private static Data count(File root, File worldDirectory) {
        Data data = new Data(worldDirectory);
        for (File dimension : MapCleaner.dimensions(worldDirectory)) {
            try {
                int id = Integer.parseInt(
                    dimension.getName()
                        .substring(3));
                data.dimensions.put(id, new long[] { MapCleaner.size2d(dimension), MapCleaner.size3d(dimension) });
            } catch (NumberFormatException e) {
                // Not a dimension folder.
            }
        }
        data.sprites = new File(worldDirectory, "iso-sprites.dat").length();
        // Every world played: wayfarmap/singleplayer/<world> and wayfarmap/multiplayer/<server>.
        for (String kind : new String[] { "singleplayer", "multiplayer" }) {
            File[] worlds = new File(root, kind).listFiles(File::isDirectory);
            if (worlds == null) {
                continue;
            }
            for (File world : worlds) {
                data.worlds++;
                addWorld(world, data.all);
            }
        }
        data.logs = MapCleaner.size(new File(root, "logs"));
        data.countedAt = System.currentTimeMillis();
        return data;
    }

    /** Dimension of a {@code dim<id>} folder, {@link Integer#MIN_VALUE} for anything else. */
    private static int dimensionOf(File file) {
        String name = file.getName();
        if (!file.isDirectory() || !name.startsWith("dim")) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(3));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Adds a world's (or one account's) map files to {2D, 3D, other}. */
    private static void addWorld(File directory, long[] sizes) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (dimensionOf(file) != Integer.MIN_VALUE) {
                sizes[0] += MapCleaner.size2d(file);
                sizes[1] += MapCleaner.size3d(file);
                sizes[2] += MapCleaner.size(file) - MapCleaner.size2d(file) - MapCleaner.size3d(file);
            } else if (file.isDirectory() && file.getName()
                .startsWith("player-")) {
                    // Each account's map of the world.
                    addWorld(file, sizes);
                } else if (file.getName()
                    .equals("iso-sprites.dat")) {
                        sizes[1] += file.length();
                    } else {
                        sizes[2] += MapCleaner.size(file);
                    }
        }
    }

    /** The last count if it is of this world. */
    private Data visibleData() {
        Data data = last;
        File worldDirectory = worldDirectory();
        return data != null && worldDirectory != null && data.worldDirectory.equals(worldDirectory) ? data : null;
    }

    // ---------------------------------------------------------------- dimensions

    /** Dimensions of this world that have anything: map files, waypoints or finds; the player's always. */
    private List<Integer> dimensions(Data data) {
        TreeSet<Integer> ids = new TreeSet<>(waypoints.keySet());
        if (data != null) {
            for (Map.Entry<Integer, long[]> entry : data.dimensions.entrySet()) {
                if (entry.getValue()[0] + entry.getValue()[1] > 0) {
                    ids.add(entry.getKey());
                }
            }
        }
        if (prospected != null) {
            ids.addAll(prospected.keySet());
        }
        if (nodes != null) {
            ids.addAll(nodes.keySet());
        }
        if (mc.theWorld != null) {
            ids.add(mc.theWorld.provider.dimensionId);
        }
        return new ArrayList<>(ids);
    }

    private String dimensionName(int id) {
        // Once per dimension: it may read the dimension's info file.
        return names.computeIfAbsent(id, k -> "[" + k + "] " + MapManager.INSTANCE.getDimensionName(k));
    }

    private String scopeName() {
        return picked == null ? I18n.format("wayfarmap.stats.all_dims") : dimensionName(picked);
    }

    /** What the list offers: all dimensions (null), then each one. */
    private List<Integer> choices() {
        List<Integer> choices = new ArrayList<>();
        choices.add(null);
        choices.addAll(dimensions(visibleData()));
        return choices;
    }

    private int listTop() {
        return dimensionButton.yPosition + 17;
    }

    /** Lines of the list shown at once: down to the bottom of the panel. */
    private int listVisible() {
        int room = (bottom - 8 - listTop()) / LIST_ROW;
        return Math.max(1, Math.min(choices().size(), room));
    }

    /** Index into {@link #choices()} of the list line under the mouse, -1 if none. */
    private int listIndexAt(int mouseX, int mouseY) {
        int x0 = dimensionButton.xPosition, y0 = listTop();
        if (!listOpen || mouseX < x0 || mouseX >= x0 + dimensionButton.getWidth() || mouseY < y0) {
            return -1;
        }
        int line = (mouseY - y0) / LIST_ROW;
        return line < listVisible() ? line + listScroll : -1;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_DIMENSION) {
            listOpen = !listOpen;
            listScroll = 0;
            armed = -1;
            return;
        }
        if (button.id == ID_REFRESH) {
            armed = -1;
            startCount();
            countFound();
            return;
        }
        long now = System.currentTimeMillis();
        if (armed != button.id || now - armedAt > CONFIRM_MS) {
            // First click: asks again.
            armed = button.id;
            armedAt = now;
            return;
        }
        armed = -1;
        clean(button.id);
    }

    private void clean(int id) {
        File worldDirectory = worldDirectory();
        if (worldDirectory == null) {
            return;
        }
        File dimension = picked == null ? null : new File(worldDirectory, "dim" + picked);
        long[] freed = new long[1];
        MapManager.INSTANCE.resetMaps(() -> {
            if (id == ID_LOGS) {
                freed[0] = MapCleaner.clearLogs(logsDirectory);
                return;
            }
            boolean flat = id == ID_DEL_2D || id == ID_DEL_ALL, iso = id == ID_DEL_3D || id == ID_DEL_ALL;
            if (dimension == null) {
                freed[0] = (flat ? MapCleaner.deleteAll(worldDirectory, false) : 0)
                    + (iso ? MapCleaner.deleteAll(worldDirectory, true) : 0);
            } else {
                freed[0] = (flat ? MapCleaner.delete2d(dimension) : 0) + (iso ? MapCleaner.delete3d(dimension) : 0);
            }
        });
        status = I18n.format("wayfarmap.clean.done", bytes(freed[0]));
        startCount();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (listOpen) {
                listOpen = false;
            } else if (armed != -1) {
                armed = -1;
            } else {
                mc.displayGuiScreen(parent);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (listOpen) {
            // While it is open, a click picks from the list or closes it.
            int index = listIndexAt(mouseX, mouseY);
            List<Integer> choices = choices();
            if (index >= 0 && index < choices.size() && button == 0) {
                picked = choices.get(index);
                armed = -1;
            }
            if (index >= 0 || !dimensionButton.isMouseOver(mouseX, mouseY)) {
                listOpen = false;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && listOpen) {
            int max = Math.max(0, choices().size() - listVisible());
            listScroll = Math.max(0, Math.min(max, listScroll + (wheel > 0 ? -1 : 1)));
        }
    }

    // ---------------------------------------------------------------- formatting

    /** Sum of the values of the picked dimension, or of all of them. */
    private static long sum(Map<Integer, ? extends Number> byDimension, Integer dimension) {
        if (dimension != null) {
            Number value = byDimension.get(dimension);
            return value == null ? 0 : value.longValue();
        }
        long total = 0;
        for (Number value : byDimension.values()) {
            total += value.longValue();
        }
        return total;
    }

    /** 1234 -> 1,234. */
    private static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** 0 B, 512 B, 1.5 KB, 12.3 MB, 1.2 GB. */
    private static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static String percent(long part, long whole) {
        return whole <= 0 ? "" : Math.round(part * 100.0 / whole) + "%";
    }

    // ---------------------------------------------------------------- drawing

    private void section(String title, int y) {
        Theme.fill(left + 10, y - 6, right - 10, y - 5, Theme.BORDER);
        Theme.text(fontRendererObj, title, left + 10, y, Theme.TEXT_MUTED);
    }

    /** A row of tiles across the panel: a muted label over a value each. */
    private void tiles(int y, String[] labels, String[] values, int valueColor) {
        int count = labels.length, gap = 6;
        int tileWidth = (right - left - 20 - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int x0 = left + 10 + i * (tileWidth + gap), x1 = x0 + tileWidth;
            Theme.fill(x0, y, x1, y + TILE, Theme.PANEL_ALT);
            Theme.outline(x0, y, x1, y + TILE, Theme.BORDER);
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, labels[i], tileWidth - 10),
                x0 + 5,
                y + 3,
                Theme.TEXT_MUTED);
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, values[i], tileWidth - 10),
                x0 + 5,
                y + 13,
                valueColor);
        }
    }

    /** A row with a button: a color mark (0 for none), the label, the share of the bar and the size. */
    private void sizeRow(int mark, String label, String share, String size, int y, int valueColor) {
        int x = left + 14;
        if (mark != 0) {
            Theme.fill(x, y, x + 7, y + 7, mark);
            x += 12;
        }
        Theme.text(fontRendererObj, label, x, y, Theme.TEXT);
        int valueRight = right - 18 - BUTTON_WIDTH;
        Theme.text(fontRendererObj, size, valueRight - fontRendererObj.getStringWidth(size), y, valueColor);
        if (!share.isEmpty()) {
            Theme.text(
                fontRendererObj,
                share,
                valueRight - 70 - fontRendererObj.getStringWidth(share),
                y,
                Theme.TEXT_MUTED);
        }
    }

    /** The 2D and the 3D map's shares of the scope's size, side by side. */
    private void bar(long flat, long iso, int y) {
        int x0 = left + 10, x1 = right - 10;
        Theme.fill(x0, y, x1, y + 5, Theme.CONTROL);
        long total = flat + iso;
        if (total <= 0) {
            return;
        }
        int split = x0 + (int) Math.round((x1 - x0) * (double) flat / total);
        if (flat > 0) {
            Theme.fill(x0, y, Math.max(x0 + 1, split), y + 5, Theme.ACCENT);
        }
        if (iso > 0) {
            Theme.fill(Math.min(x1 - 1, split), y, x1, y + 5, ISO_COLOR);
        }
    }

    private String rowLabel(int id) {
        switch (id) {
            case ID_DEL_2D:
                return I18n.format("wayfarmap.stats.flat");
            case ID_DEL_3D:
                return I18n.format("wayfarmap.stats.iso");
            case ID_DEL_ALL:
                return I18n.format("wayfarmap.data.whole");
            default:
                return I18n.format("wayfarmap.data.logs");
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (armed != -1 && System.currentTimeMillis() - armedAt > CONFIRM_MS) {
            armed = -1;
        }
        Data data = visibleData();
        boolean updating = counting != null;
        if (picked != null && !dimensions(data).contains(picked)) {
            picked = null;
        }
        long[] scope = data == null ? null : data.sizes(picked);
        String unknown = "-";
        // Old numbers are dimmed while they are counted again.
        int value = updating ? Theme.TEXT_DISABLED : Theme.ACCENT;

        boolean canDelete = worldDirectory() != null && data != null;
        for (Object o : buttonList) {
            FlatButton b = (FlatButton) o;
            if (b.id == ID_DIMENSION) {
                b.displayString = Theme.ellipsize(fontRendererObj, scopeName(), b.getWidth() - 24)
                    + (listOpen ? " ▴" : " ▾");
            } else if (b.id == ID_REFRESH) {
                b.enabled = !updating;
            } else if (b.id != ID_DONE) {
                long size = !canDelete ? 0
                    : b.id == ID_LOGS ? data.logs
                        : b.id == ID_DEL_2D ? scope[0] : b.id == ID_DEL_3D ? scope[1] : scope[0] + scope[1];
                b.displayString = I18n.format(
                    armed == b.id ? "wayfarmap.clean.confirm"
                        : b.id == ID_LOGS ? "wayfarmap.clean.clear" : "wayfarmap.clean.delete");
                b.active = armed == b.id;
                // Nothing to delete: the button stays off (unless it waits for its second click).
                b.enabled = armed == b.id || size > 0;
            }
        }

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.data.title"), left + 10, top + 10, Theme.ACCENT);
        // Header, right: counting now, or when the numbers were counted.
        String counted;
        int countedColor;
        if (updating) {
            int dots = (int) (System.currentTimeMillis() / 400 % 4);
            counted = I18n.format("wayfarmap.stats.updating") + "...".substring(0, dots);
            countedColor = Theme.ACCENT;
        } else if (data != null) {
            counted = I18n.format(
                "wayfarmap.stats.updated",
                new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(data.countedAt)));
            countedColor = Theme.TEXT_MUTED;
        } else {
            counted = I18n.format("wayfarmap.stats.none");
            countedColor = Theme.TEXT_MUTED;
        }
        Theme.text(
            fontRendererObj,
            counted,
            refreshButton.xPosition - 8 - fontRendererObj.getStringWidth(counted),
            top + 10,
            countedColor);
        Theme.fill(left + 10, top + 23, right - 10, top + 24, Theme.ACCENT_DIM);

        // All worlds.
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.all_worlds"), left + 10, top + 29, Theme.TEXT_MUTED);
        tiles(
            tilesY(),
            new String[] { I18n.format("wayfarmap.stats.worlds"), I18n.format("wayfarmap.stats.flat"),
                I18n.format("wayfarmap.stats.iso"), I18n.format("wayfarmap.stats.total") },
            data == null ? new String[] { unknown, unknown, unknown, unknown }
                : new String[] { number(data.worlds), bytes(data.all[0]), bytes(data.all[1]),
                    bytes(data.all[0] + data.all[1] + data.all[2]) },
            value);

        // This world: all its dimensions, or the one picked.
        section(I18n.format("wayfarmap.stats.this_world"), worldY());
        long flat = scope == null ? 0 : scope[0], iso = scope == null ? 0 : scope[1];
        bar(flat, iso, barY());
        sizeRow(
            Theme.ACCENT,
            rowLabel(ID_DEL_2D),
            percent(flat, flat + iso),
            scope == null ? unknown : bytes(flat),
            rowY(0),
            value);
        sizeRow(
            ISO_COLOR,
            rowLabel(ID_DEL_3D),
            percent(iso, flat + iso),
            scope == null ? unknown : bytes(iso),
            rowY(1),
            value);
        sizeRow(0, rowLabel(ID_DEL_ALL), "", scope == null ? unknown : bytes(flat + iso), rowY(2), value);
        // What the world has besides its map files: kept whatever is deleted.
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        labels.add(I18n.format("wayfarmap.stats.waypoints"));
        values.add(number(sum(waypoints, picked)));
        if (prospected != null) {
            Map<Integer, Integer> veins = new HashMap<>(), fluids = new HashMap<>();
            for (Map.Entry<Integer, int[]> entry : prospected.entrySet()) {
                veins.put(entry.getKey(), entry.getValue()[0]);
                fluids.put(entry.getKey(), entry.getValue()[1]);
            }
            labels.add(I18n.format("wayfarmap.stats.veins"));
            values.add(number(sum(veins, picked)));
            labels.add(I18n.format("wayfarmap.stats.fluids"));
            values.add(number(sum(fluids, picked)));
        }
        if (nodes != null) {
            labels.add(I18n.format("wayfarmap.stats.nodes"));
            values.add(number(sum(nodes, picked)));
        }
        tiles(foundY(), labels.toArray(new String[0]), values.toArray(new String[0]), Theme.TEXT);

        // Logs of all worlds.
        Theme.fill(left + 10, logsY() - 10, right - 10, logsY() - 9, Theme.BORDER);
        sizeRow(0, rowLabel(ID_LOGS), "", data == null ? unknown : bytes(data.logs), logsY(), value);

        // Footer: the confirmation asked, or what the last cleaning did; and what is kept.
        int textWidth = right - left - 110;
        if (armed != -1) {
            String what = armed == ID_LOGS ? rowLabel(armed) : rowLabel(armed) + " · " + scopeName();
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.data.confirm", what), textWidth),
                left + 10,
                bottom - 34,
                Theme.DANGER);
        } else if (!status.isEmpty()) {
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, status, textWidth),
                left + 10,
                bottom - 34,
                Theme.SUCCESS);
        }
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.data.note"), textWidth),
            left + 10,
            bottom - 21,
            Theme.TEXT_MUTED);
        super.drawScaled(mouseX, mouseY, partialTicks);
        if (listOpen) {
            // Over everything else.
            drawList(mouseX, mouseY);
        }
    }

    private void drawList(int mouseX, int mouseY) {
        List<Integer> choices = choices();
        int x0 = dimensionButton.xPosition, x1 = x0 + dimensionButton.getWidth();
        int y0 = listTop(), visible = listVisible();
        listScroll = Math.max(0, Math.min(listScroll, choices.size() - visible));
        Theme.fill(x0, y0, x1, y0 + visible * LIST_ROW, Theme.PANEL_ALT);
        Theme.outline(x0, y0, x1, y0 + visible * LIST_ROW, Theme.BORDER);
        int hovered = listIndexAt(mouseX, mouseY);
        Data data = visibleData();
        for (int line = 0; line < visible; line++) {
            int index = line + listScroll;
            Integer dimension = choices.get(index);
            int y = y0 + line * LIST_ROW;
            boolean current = dimension == null ? picked == null : dimension.equals(picked);
            if (index == hovered) {
                Theme.fill(x0 + 1, y, x1 - 1, y + LIST_ROW, Theme.CONTROL_HOVER);
            }
            // The size of each, on the right.
            String size = "";
            if (data != null) {
                long[] sizes = data.sizes(dimension);
                size = bytes(sizes[0] + sizes[1]);
            }
            int sizeWidth = fontRendererObj.getStringWidth(size);
            String label = dimension == null ? I18n.format("wayfarmap.stats.all_dims") : dimensionName(dimension);
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, label, x1 - x0 - 24 - sizeWidth),
                x0 + 6,
                y + 3,
                current ? Theme.ACCENT : Theme.TEXT);
            Theme.text(fontRendererObj, size, x1 - 8 - sizeWidth, y + 3, Theme.TEXT_MUTED);
        }
        if (choices.size() > visible) {
            // Scroll bar.
            int height = visible * LIST_ROW;
            int bar = Math.max(8, height * visible / choices.size());
            int barY = y0 + (height - bar) * listScroll / (choices.size() - visible);
            Theme.fill(x1 - 4, barY, x1 - 2, barY + bar, Theme.BORDER);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
