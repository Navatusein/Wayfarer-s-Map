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

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;

/**
 * Stats: how much disk the maps of all worlds take, and for this world, all its dimensions together or the one
 * picked, the flat (2D) and the 3D map apart, the waypoints, and what was found with the mods (GregTech ore veins,
 * underground fluids, Thaumcraft nodes). The sizes are counted again in the background each time the screen is
 * opened; until then the last count is shown, marked as being updated.
 */
public class GuiMapStats extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_PREV = 1, ID_NEXT = 2;
    private static final int ROW_HEIGHT = 12;
    /** Where the dimension picker is, from the top of the panel. */
    private static final int PICKER_Y = 122;

    /** Sizes of one dimension's map, in bytes. */
    private static final class Row {

        long flat, iso;
    }

    /** One count of the map folders. */
    private static final class Stats {

        final File worldDirectory;
        /** This world, per dimension. */
        final Map<Integer, Row> rows = new HashMap<>();
        /** Pictures of blocks for the 3D map, shared by the dimensions of this world. */
        long sprites;
        /** Everything else of this world (waypoints, where the map was left...). */
        long other;
        /** All worlds: how many, and their {2D, 3D, other} sizes. */
        int worlds;
        final long[] all = new long[3];
        long countedAt;

        Stats(File worldDirectory) {
            this.worldDirectory = worldDirectory;
        }

        long flat() {
            long sum = 0;
            for (Row row : rows.values()) {
                sum += row.flat;
            }
            return sum;
        }

        long iso() {
            long sum = sprites;
            for (Row row : rows.values()) {
                sum += row.iso;
            }
            return sum;
        }
    }

    /** The last count, kept while the game runs so it shows at once the next time. */
    private static volatile Stats last;
    /** The count running now, if any. */
    private static volatile Thread counting;
    /** Dimension picked, null for all of them; kept between openings. */
    private static Integer picked;

    private final GuiScreen parent;
    private int left, top, right, bottom;
    /** Counted once per opening (not again when the window is resized). */
    private boolean started;
    /** Found with the mods, per dimension; null without the mod. */
    private Map<Integer, int[]> prospected;
    private Map<Integer, Integer> nodes;
    private final Map<Integer, Integer> waypoints = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();

    public GuiMapStats(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 360);
        int panelHeight = Math.min(height - 16, 330);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        buttonList.clear();
        buttonList.add(new FlatButton(ID_CLOSE, right - 88, bottom - 26, 80, 18, I18n.format("gui.done")));
        buttonList.add(new FlatButton(ID_PREV, left + 10, top + PICKER_Y, 18, 16, "<"));
        buttonList.add(new FlatButton(ID_NEXT, right - 28, top + PICKER_Y, 18, 16, ">"));
        if (!started) {
            started = true;
            if (counting == null) {
                startCount();
            }
            countFound();
        }
    }

    /**
     * What this world has besides its map files: waypoints, and what was found with VisualProspecting (GregTech ore
     * veins, underground fluids) and TCNodeTracker (nodes). Render thread: those mods keep them there.
     */
    private void countFound() {
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

    /** Counts the sizes in the background: walking big maps' folders takes a while. */
    private void startCount() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null) {
            return;
        }
        File root = new File(mc.mcDataDir, "wayfarmap");
        Thread thread = new Thread(() -> {
            try {
                last = count(root, worldDirectory);
            } finally {
                counting = null;
            }
        }, "Wayfarer's Map stats");
        thread.setDaemon(true);
        counting = thread;
        thread.start();
    }

    private static Stats count(File root, File worldDirectory) {
        Stats stats = new Stats(worldDirectory);
        File[] files = worldDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                int dimension = dimensionOf(file);
                if (dimension != Integer.MIN_VALUE) {
                    Row row = new Row();
                    long[] sizes = new long[3];
                    addDimension(file, sizes);
                    row.flat = sizes[0];
                    row.iso = sizes[1];
                    stats.rows.put(dimension, row);
                } else if (file.getName()
                    .equals("iso-sprites.dat")) {
                    stats.sprites += size(file);
                } else {
                    stats.other += size(file);
                }
            }
        }
        // Every world played: wayfarmap/singleplayer/<world> and wayfarmap/multiplayer/<server>.
        for (String kind : new String[] { "singleplayer", "multiplayer" }) {
            File[] worlds = new File(root, kind).listFiles(File::isDirectory);
            if (worlds == null) {
                continue;
            }
            for (File world : worlds) {
                stats.worlds++;
                addWorld(world, stats.all);
            }
        }
        stats.countedAt = System.currentTimeMillis();
        return stats;
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
                addDimension(file, sizes);
            } else if (file.isDirectory() && file.getName()
                .startsWith("player-")) {
                // Each account's map of the world.
                addWorld(file, sizes);
            } else if (file.getName()
                .equals("iso-sprites.dat")) {
                sizes[1] += size(file);
            } else {
                sizes[2] += size(file);
            }
        }
    }

    /**
     * Adds a dimension's folder to {2D, 3D}: the 3D map keeps its blocks and its drawn tiles apart, the rest is the
     * flat map (surface, biomes, cave layers).
     */
    private static void addDimension(File directory, long[] sizes) {
        File[] parts = directory.listFiles();
        if (parts == null) {
            return;
        }
        for (File part : parts) {
            boolean iso = part.isDirectory() && (part.getName()
                .equals("blocks")
                || part.getName()
                    .equals("iso"));
            sizes[iso ? 1 : 0] += size(part);
        }
    }

    private static long size(File file) {
        if (!file.isDirectory()) {
            return file.length();
        }
        long sum = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                sum += size(child);
            }
        }
        return sum;
    }

    /** Dimensions of this world that have anything: map files, waypoints or finds; the player's always. */
    private List<Integer> dimensions(Stats stats) {
        TreeSet<Integer> ids = new TreeSet<>(waypoints.keySet());
        if (stats != null) {
            ids.addAll(stats.rows.keySet());
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

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(parent);
        } else if (button.id == ID_PREV || button.id == ID_NEXT) {
            // All dimensions, then each one, round and round.
            List<Integer> choices = new ArrayList<>();
            choices.add(null);
            choices.addAll(dimensions(visibleStats()));
            int at = Math.max(0, choices.indexOf(picked));
            int step = button.id == ID_NEXT ? 1 : -1;
            picked = choices.get(Math.floorMod(at + step, choices.size()));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT) {
            actionPerformed((GuiButton) buttonList.get(keyCode == Keyboard.KEY_LEFT ? 1 : 2));
        }
    }

    /** The last count if it is of this world. */
    private static Stats visibleStats() {
        Stats stats = last;
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        return stats != null && worldDirectory != null && stats.worldDirectory.equals(worldDirectory) ? stats : null;
    }

    private int row(String label, String value, int y, int valueColor) {
        Theme.text(fontRendererObj, label, left + 14, y, Theme.TEXT);
        Theme.text(fontRendererObj, value, right - 120, y, valueColor);
        return y + ROW_HEIGHT;
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.title"), left + 10, top + 10, Theme.ACCENT);
        Theme.fill(left + 10, top + 22, right - 10, top + 23, Theme.ACCENT_DIM);

        Stats stats = visibleStats();
        boolean updating = counting != null;
        // Old numbers are dimmed while they are counted again.
        int value = updating ? Theme.TEXT_DISABLED : Theme.ACCENT;
        String unknown = "-";

        // All worlds.
        int y = top + 30;
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.all_worlds"), left + 10, y, Theme.TEXT_MUTED);
        y += ROW_HEIGHT + 2;
        y = row(I18n.format("wayfarmap.stats.worlds"), stats == null ? unknown : number(stats.worlds), y, value);
        y = row(I18n.format("wayfarmap.stats.flat"), stats == null ? unknown : bytes(stats.all[0]), y, value);
        y = row(I18n.format("wayfarmap.stats.iso"), stats == null ? unknown : bytes(stats.all[1]), y, value);
        row(
            I18n.format("wayfarmap.stats.total"),
            stats == null ? unknown : bytes(stats.all[0] + stats.all[1] + stats.all[2]),
            y,
            value);

        // This world: all its dimensions, or the one picked.
        y = top + PICKER_Y - 18;
        Theme.fill(left + 10, y - 4, right - 10, y - 3, Theme.BORDER);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.this_world"), left + 10, y, Theme.TEXT_MUTED);
        if (picked != null && !dimensions(stats).contains(picked)) {
            picked = null;
        }
        String title = picked == null ? I18n.format("wayfarmap.stats.all_dims") : dimensionName(picked);
        Theme.centered(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, title, right - left - 80),
            (left + right) / 2,
            top + PICKER_Y + 4,
            Theme.TEXT);

        y = top + PICKER_Y + 24;
        Row row = stats == null ? null : picked == null ? null : stats.rows.get(picked);
        String flat = stats == null ? unknown : bytes(picked == null ? stats.flat() : row == null ? 0 : row.flat);
        String iso = stats == null ? unknown : bytes(picked == null ? stats.iso() : row == null ? 0 : row.iso);
        y = row(I18n.format("wayfarmap.stats.flat"), flat, y, value);
        y = row(I18n.format("wayfarmap.stats.iso"), iso, y, value);
        if (picked == null && stats != null && stats.sprites > 0) {
            y = row(I18n.format("wayfarmap.stats.sprites"), bytes(stats.sprites), y, value);
        }
        y = row(I18n.format("wayfarmap.stats.waypoints"), number(sum(waypoints, picked)), y, Theme.ACCENT);
        if (prospected != null) {
            Map<Integer, Integer> veins = new HashMap<>(), fluids = new HashMap<>();
            for (Map.Entry<Integer, int[]> entry : prospected.entrySet()) {
                veins.put(entry.getKey(), entry.getValue()[0]);
                fluids.put(entry.getKey(), entry.getValue()[1]);
            }
            y = row(I18n.format("wayfarmap.stats.veins"), number(sum(veins, picked)), y, Theme.ACCENT);
            y = row(I18n.format("wayfarmap.stats.fluids"), number(sum(fluids, picked)), y, Theme.ACCENT);
        }
        if (nodes != null) {
            row(I18n.format("wayfarmap.stats.nodes"), number(sum(nodes, picked)), y, Theme.ACCENT);
        }

        // Bottom left: counting now, or when the numbers were counted.
        String status;
        int statusColor;
        if (updating) {
            int dots = (int) (System.currentTimeMillis() / 400 % 4);
            status = I18n.format("wayfarmap.stats.updating") + "...".substring(0, dots);
            statusColor = Theme.ACCENT;
        } else if (stats != null) {
            status = I18n.format(
                "wayfarmap.stats.updated",
                new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(stats.countedAt)));
            statusColor = Theme.SUCCESS;
        } else {
            status = I18n.format("wayfarmap.stats.none");
            statusColor = Theme.TEXT_MUTED;
        }
        Theme.text(fontRendererObj, status, left + 10, bottom - 34, statusColor);
        String note = Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.stats.note"), right - 100 - left - 10);
        Theme.text(fontRendererObj, note, left + 10, bottom - 21, Theme.TEXT_MUTED);
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
