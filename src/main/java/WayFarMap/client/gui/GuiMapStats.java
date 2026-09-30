package WayFarMap.client.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

/**
 * How much disk the map of this world takes, the flat (2D) and the 3D map apart, per dimension. Counted again in the
 * background each time the screen is opened; until then the last count is shown, marked as being updated.
 */
public class GuiMapStats extends ScaledScreen {

    private static final int ID_CLOSE = 0;
    private static final int ROW_HEIGHT = 12;

    /** Sizes of one dimension's map, in bytes. */
    private static final class Row {

        final int id;
        String name;
        long flat, iso;

        Row(int id) {
            this.id = id;
        }
    }

    /** One count of the world's map folder. */
    private static final class Stats {

        final File worldDirectory;
        final List<Row> rows = new ArrayList<>();
        /** Pictures of blocks for the 3D map, shared by all dimensions. */
        long sprites;
        /** Everything else (waypoints, where the map was left...). */
        long other;
        long countedAt;
        boolean named;

        Stats(File worldDirectory) {
            this.worldDirectory = worldDirectory;
        }

        long flat() {
            long sum = 0;
            for (Row row : rows) {
                sum += row.flat;
            }
            return sum;
        }

        long iso() {
            long sum = sprites;
            for (Row row : rows) {
                sum += row.iso;
            }
            return sum;
        }
    }

    /** The last count, kept while the game runs so it shows at once the next time. */
    private static volatile Stats last;
    /** The count running now, if any. */
    private static volatile Thread counting;

    private final GuiScreen parent;
    private int left, top, right, bottom;
    /** Counted once per opening (not again when the window is resized). */
    private boolean started;
    /** Found with the mods, counted on opening: ore veins, underground fluids, aura nodes; -1 without the mod. */
    private int veins = -1, fluids = -1, nodes = -1;

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
        if (!started) {
            started = true;
            if (counting == null) {
                startCount();
            }
            countFound();
        }
    }

    /** What was found with VisualProspecting (GregTech ore veins, underground fluids) and TCNodeTracker (nodes). */
    private void countFound() {
        if (Mods.isVisualProspectingLoaded()) {
            try {
                int[] found = ProspectingLayer.countFound();
                veins = found[0];
                fluids = found[1];
            } catch (Throwable t) {
                veins = fluids = -1;
            }
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            try {
                nodes = ThaumcraftNodes.countFound();
            } catch (Throwable t) {
                nodes = -1;
            }
        }
    }

    /** Counts the sizes in the background: walking a big map's folders takes a while. */
    private static void startCount() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                last = count(worldDirectory);
            } finally {
                counting = null;
            }
        }, "Wayfarer's Map stats");
        thread.setDaemon(true);
        counting = thread;
        thread.start();
    }

    private static Stats count(File worldDirectory) {
        Stats stats = new Stats(worldDirectory);
        File[] files = worldDirectory.listFiles();
        if (files == null) {
            return stats;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && name.startsWith("dim")) {
                try {
                    Row row = new Row(Integer.parseInt(name.substring(3)));
                    File[] parts = file.listFiles();
                    if (parts != null) {
                        for (File part : parts) {
                            // The 3D map keeps its blocks and its drawn tiles apart; the rest is the flat map
                            // (surface, biomes, cave layers).
                            long size = size(part);
                            if (part.isDirectory() && (part.getName()
                                .equals("blocks")
                                || part.getName()
                                    .equals("iso"))) {
                                row.iso += size;
                            } else {
                                row.flat += size;
                            }
                        }
                    }
                    stats.rows.add(row);
                    continue;
                } catch (NumberFormatException e) {
                    // Not a dimension's folder.
                }
            }
            if (name.equals("iso-sprites.dat")) {
                stats.sprites += size(file);
            } else {
                stats.other += size(file);
            }
        }
        stats.rows.sort((a, b) -> Integer.compare(a.id, b.id));
        stats.countedAt = System.currentTimeMillis();
        return stats;
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

    /** {label, count} of what was found with each installed mod. */
    private List<String[]> foundLines() {
        List<String[]> lines = new ArrayList<>();
        if (veins >= 0) {
            lines.add(new String[] { I18n.format("wayfarmap.stats.veins"), number(veins) });
        }
        if (fluids >= 0) {
            lines.add(new String[] { I18n.format("wayfarmap.stats.fluids"), number(fluids) });
        }
        if (nodes >= 0) {
            lines.add(new String[] { I18n.format("wayfarmap.stats.nodes"), number(nodes) });
        }
        return lines;
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
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.title"), left + 10, top + 10, Theme.ACCENT);
        Theme.fill(left + 10, top + 22, right - 10, top + 23, Theme.ACCENT_DIM);

        Stats stats = last;
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (stats != null && worldDirectory != null && !stats.worldDirectory.equals(worldDirectory)) {
            // Counted for another world.
            stats = null;
        }
        boolean updating = counting != null;
        int colFlat = right - 150, colIso = right - 80;
        int y = top + 30;
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.dimension"), left + 10, y, Theme.TEXT_MUTED);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.flat"), colFlat, y, Theme.TEXT_MUTED);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.iso"), colIso, y, Theme.TEXT_MUTED);
        y += ROW_HEIGHT + 2;

        if (stats == null) {
            Theme.text(
                fontRendererObj,
                I18n.format(updating ? "wayfarmap.stats.updating" : "wayfarmap.stats.none"),
                left + 10,
                y,
                Theme.TEXT_MUTED);
        } else {
            if (!stats.named) {
                // Names on this thread: they may come from the world or read its dimension's info file.
                for (Row row : stats.rows) {
                    row.name = MapManager.INSTANCE.getDimensionName(row.id);
                }
                stats.named = true;
            }
            // Old numbers are dimmed while they are counted again.
            int value = updating ? Theme.TEXT_DISABLED : Theme.TEXT;
            int limit = bottom - 70 - foundLines().size() * ROW_HEIGHT - (foundLines().isEmpty() ? 0 : 20);
            for (int i = 0; i < stats.rows.size(); i++) {
                Row row = stats.rows.get(i);
                if (y > limit) {
                    Theme.text(
                        fontRendererObj,
                        I18n.format("wayfarmap.stats.more", stats.rows.size() - i),
                        left + 10,
                        y,
                        Theme.TEXT_MUTED);
                    y += ROW_HEIGHT;
                    break;
                }
                String name = Theme.ellipsize(fontRendererObj, "[" + row.id + "] " + row.name, colFlat - left - 20);
                Theme.text(fontRendererObj, name, left + 10, y, value);
                Theme.text(fontRendererObj, bytes(row.flat), colFlat, y, value);
                Theme.text(fontRendererObj, bytes(row.iso), colIso, y, value);
                y += ROW_HEIGHT;
            }
            if (stats.sprites > 0) {
                Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.sprites"), left + 10, y, Theme.TEXT_MUTED);
                Theme.text(fontRendererObj, bytes(stats.sprites), colIso, y, value);
                y += ROW_HEIGHT;
            }
            y += 2;
            Theme.fill(left + 10, y, right - 10, y + 1, Theme.BORDER);
            y += 4;
            Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.total"), left + 10, y, value);
            Theme.text(fontRendererObj, bytes(stats.flat()), colFlat, y, updating ? value : Theme.ACCENT);
            Theme.text(fontRendererObj, bytes(stats.iso()), colIso, y, updating ? value : Theme.ACCENT);
            y += ROW_HEIGHT;
            Theme.text(
                fontRendererObj,
                I18n.format("wayfarmap.stats.all", bytes(stats.flat() + stats.iso() + stats.other)),
                left + 10,
                y,
                Theme.TEXT_MUTED);
        }
        y += ROW_HEIGHT;

        // Found with the mods, if they are installed.
        List<String[]> found = foundLines();
        if (!found.isEmpty()) {
            y += 6;
            Theme.text(fontRendererObj, I18n.format("wayfarmap.stats.found"), left + 10, y, Theme.TEXT_MUTED);
            y += ROW_HEIGHT + 2;
            for (String[] line : found) {
                Theme.text(fontRendererObj, line[0], left + 10, y, Theme.TEXT);
                Theme.text(fontRendererObj, line[1], colFlat, y, Theme.ACCENT);
                y += ROW_HEIGHT;
            }
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
            status = "";
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
