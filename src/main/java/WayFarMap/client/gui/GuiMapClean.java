package WayFarMap.client.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.MapCleaner;
import WayFarMap.client.map.MapManager;

/**
 * Cleaning: empties the logs folder, deletes the flat (2D) or the 3D map of every dimension of this world, or the 2D,
 * 3D or whole map of one dimension. Each button asks again before it deletes. Waypoints are never deleted; the map
 * around the player is drawn again as the chunks are seen.
 */
public class GuiMapClean extends ScaledScreen {

    private static final int ID_DONE = 0, ID_LOGS = 1, ID_ALL_2D = 2, ID_ALL_3D = 3, ID_DIM_2D = 4, ID_DIM_3D = 5,
        ID_DIM_ALL = 6, ID_DIMENSION = 7;
    private static final int ROW = 20, BUTTON_WIDTH = 96;
    /** Height of a line of the dimension list. */
    private static final int LIST_ROW = 14;
    /** How long a button waits for the second click that confirms it. */
    private static final long CONFIRM_MS = 4000;

    /** Sizes on disk, counted in the background. */
    private static final class Sizes {

        long logs, all2d, all3d;
        /** Per dimension: {2D, 3D}. */
        final Map<Integer, long[]> dimensions = new TreeMap<>();
    }

    private final GuiScreen parent;
    private final File worldDirectory;
    private final File logsDirectory;
    private int left, top, right, bottom;
    private volatile Sizes sizes;
    private volatile boolean counting;
    /** Dimension picked for the lower buttons, null until there is one. */
    private Integer picked;
    /** Button waiting for its second click, and since when. */
    private int armed = -1;
    private long armedAt;
    /** The dimension list is open, and how far it is scrolled (lines). */
    private boolean listOpen;
    private int listScroll;
    private FlatButton dimensionButton;
    private String status = "";
    private int statusColor = Theme.TEXT_MUTED;

    public GuiMapClean(GuiScreen parent) {
        this.parent = parent;
        this.worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        this.logsDirectory = new File(new File(mc().mcDataDir, "wayfarmap"), "logs");
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getMinecraft();
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 380);
        int panelHeight = Math.min(height - 16, 300);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        buttonList.clear();
        int x = right - 10 - BUTTON_WIDTH;
        buttonList.add(button(ID_LOGS, x, rowY(0)));
        buttonList.add(button(ID_ALL_2D, x, rowY(1)));
        buttonList.add(button(ID_ALL_3D, x, rowY(2)));
        dimensionButton = new FlatButton(ID_DIMENSION, left + 10, dimensionPickerY(), right - left - 20, 16, "");
        buttonList.add(dimensionButton);
        listOpen = false;
        buttonList.add(button(ID_DIM_2D, x, rowY(3)));
        buttonList.add(button(ID_DIM_3D, x, rowY(4)));
        buttonList.add(button(ID_DIM_ALL, x, rowY(5)));
        buttonList.add(new FlatButton(ID_DONE, right - 88, bottom - 26, 80, 18, I18n.format("gui.done")));
        if (sizes == null && !counting) {
            count();
        }
    }

    private static FlatButton button(int id, int x, int y) {
        FlatButton button = new FlatButton(id, x, y - 4, BUTTON_WIDTH, 16, "");
        button.danger = true;
        return button;
    }

    /** Top of a row: logs, this world (2D, 3D), then the picked dimension (2D, 3D, all). */
    private int rowY(int row) {
        if (row == 0) {
            return top + 46;
        }
        if (row <= 2) {
            return top + 46 + 30 + (row - 1) * ROW;
        }
        return dimensionPickerY() + 26 + (row - 3) * ROW;
    }

    private int dimensionPickerY() {
        return top + 46 + 30 + 2 * ROW + 26;
    }

    /** Counts the sizes in the background: walking a big map's folders takes a while. */
    private void count() {
        if (worldDirectory == null) {
            return;
        }
        counting = true;
        Thread thread = new Thread(() -> {
            try {
                Sizes counted = new Sizes();
                counted.logs = MapCleaner.size(logsDirectory);
                for (File dimension : MapCleaner.dimensions(worldDirectory)) {
                    long[] row = { MapCleaner.size2d(dimension), MapCleaner.size3d(dimension) };
                    counted.all2d += row[0];
                    counted.all3d += row[1];
                    try {
                        counted.dimensions.put(
                            Integer.parseInt(
                                dimension.getName()
                                    .substring(3)),
                            row);
                    } catch (NumberFormatException e) {
                        // Not a dimension folder.
                    }
                }
                counted.all3d += new File(worldDirectory, "iso-sprites.dat").length();
                sizes = counted;
            } finally {
                counting = false;
            }
        }, "Wayfarer's Map clean");
        thread.setDaemon(true);
        thread.start();
    }

    private List<Integer> dimensionIds() {
        Sizes s = sizes;
        List<Integer> ids = new ArrayList<>();
        if (s != null) {
            for (Map.Entry<Integer, long[]> entry : s.dimensions.entrySet()) {
                if (entry.getValue()[0] + entry.getValue()[1] > 0) {
                    ids.add(entry.getKey());
                }
            }
        }
        return ids;
    }

    /** Lines of the list shown at once: down to the bottom of the panel. */
    private int listVisible() {
        int room = (bottom - 8 - (dimensionPickerY() + 17)) / LIST_ROW;
        return Math.max(1, Math.min(dimensionIds().size(), room));
    }

    /** Index into {@link #dimensionIds()} of the list line under the mouse, -1 if none. */
    private int listIndexAt(int mouseX, int mouseY) {
        int x0 = dimensionButton.xPosition, y0 = dimensionPickerY() + 17;
        if (!listOpen || mouseX < x0 || mouseX >= x0 + dimensionButton.getWidth() || mouseY < y0) {
            return -1;
        }
        int line = (mouseY - y0) / LIST_ROW;
        return line < listVisible() ? line + listScroll : -1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (listOpen) {
            // While it is open, a click picks from the list or closes it.
            int index = listIndexAt(mouseX, mouseY);
            List<Integer> ids = dimensionIds();
            if (index >= 0 && index < ids.size() && button == 0) {
                picked = ids.get(index);
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
            int max = Math.max(0, dimensionIds().size() - listVisible());
            listScroll = Math.max(0, Math.min(max, listScroll + (wheel > 0 ? -1 : 1)));
        }
    }

    private void drawList(int mouseX, int mouseY) {
        List<Integer> ids = dimensionIds();
        if (ids.isEmpty()) {
            listOpen = false;
            return;
        }
        int x0 = dimensionButton.xPosition, x1 = x0 + dimensionButton.getWidth();
        int y0 = dimensionPickerY() + 17, visible = listVisible();
        listScroll = Math.max(0, Math.min(listScroll, ids.size() - visible));
        Theme.fill(x0, y0, x1, y0 + visible * LIST_ROW, Theme.PANEL_ALT);
        Theme.outline(x0, y0, x1, y0 + visible * LIST_ROW, Theme.BORDER);
        int hovered = listIndexAt(mouseX, mouseY);
        for (int line = 0; line < visible; line++) {
            int index = line + listScroll;
            int id = ids.get(index);
            int y = y0 + line * LIST_ROW;
            if (index == hovered) {
                Theme.fill(x0 + 1, y, x1 - 1, y + LIST_ROW, Theme.CONTROL_HOVER);
            }
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, dimensionName(id), x1 - x0 - 12),
                x0 + 6,
                y + 3,
                picked != null && picked == id ? Theme.ACCENT : Theme.TEXT);
        }
        if (ids.size() > visible) {
            // Scroll bar.
            int height = visible * LIST_ROW;
            int bar = Math.max(8, height * visible / ids.size());
            int barY = y0 + (height - bar) * listScroll / (ids.size() - visible);
            Theme.fill(x1 - 4, barY, x1 - 2, barY + bar, Theme.BORDER);
        }
    }

    private static String dimensionName(int id) {
        return "[" + id + "] " + MapManager.INSTANCE.getDimensionName(id);
    }

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
        if (worldDirectory == null) {
            return;
        }
        if ((id == ID_DIM_2D || id == ID_DIM_3D || id == ID_DIM_ALL) && picked == null) {
            return;
        }
        File dimension = picked == null ? null : new File(worldDirectory, "dim" + picked);
        long[] freed = new long[1];
        MapManager.INSTANCE.resetMaps(() -> {
            switch (id) {
                case ID_LOGS:
                    freed[0] = MapCleaner.clearLogs(logsDirectory);
                    break;
                case ID_ALL_2D:
                    freed[0] = MapCleaner.deleteAll(worldDirectory, false);
                    break;
                case ID_ALL_3D:
                    freed[0] = MapCleaner.deleteAll(worldDirectory, true);
                    break;
                case ID_DIM_2D:
                    freed[0] = MapCleaner.delete2d(dimension);
                    break;
                case ID_DIM_3D:
                    freed[0] = MapCleaner.delete3d(dimension);
                    break;
                default:
                    freed[0] = MapCleaner.delete2d(dimension) + MapCleaner.delete3d(dimension);
                    break;
            }
        });
        status = I18n.format("wayfarmap.clean.done", bytes(freed[0]));
        statusColor = Theme.SUCCESS;
        sizes = null;
        count();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (listOpen) {
                listOpen = false;
            } else {
                mc.displayGuiScreen(parent);
            }
        }
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

    private void row(String label, Long size, int y) {
        Theme.text(fontRendererObj, label, left + 14, y, Theme.TEXT);
        String value = size == null ? "-" : bytes(size);
        Theme.text(
            fontRendererObj,
            value,
            right - 20 - BUTTON_WIDTH - fontRendererObj.getStringWidth(value),
            y,
            counting ? Theme.TEXT_DISABLED : Theme.ACCENT);
    }

    private void section(String title, int y) {
        Theme.fill(left + 10, y - 4, right - 10, y - 3, Theme.BORDER);
        Theme.text(fontRendererObj, title, left + 10, y, Theme.TEXT_MUTED);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (armed != -1 && System.currentTimeMillis() - armedAt > CONFIRM_MS) {
            armed = -1;
        }
        Sizes s = sizes;
        if (picked != null && !dimensionIds().contains(picked) && s != null) {
            picked = null;
        }
        if (picked == null && s != null && !dimensionIds().isEmpty()) {
            // The first one until another is picked.
            picked = dimensionIds().get(0);
        }
        for (Object o : buttonList) {
            FlatButton b = (FlatButton) o;
            if (b.id == ID_DIMENSION) {
                String name = picked == null ? I18n.format("wayfarmap.clean.no_dimension") : dimensionName(picked);
                b.displayString = Theme.ellipsize(fontRendererObj, name, b.getWidth() - 24)
                    + (listOpen ? " \u25B4" : " \u25BE");
                b.enabled = picked != null;
            } else if (b.id != ID_DONE) {
                b.displayString = I18n.format(
                    armed == b.id ? "wayfarmap.clean.confirm"
                        : b.id == ID_LOGS ? "wayfarmap.clean.clear" : "wayfarmap.clean.delete");
                b.active = armed == b.id;
                b.enabled = worldDirectory != null && (b.id < ID_DIM_2D || picked != null);
            }
        }

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.clean.title"), left + 10, top + 10, Theme.ACCENT);
        Theme.fill(left + 10, top + 22, right - 10, top + 23, Theme.ACCENT_DIM);

        Theme.text(fontRendererObj, I18n.format("wayfarmap.clean.logs_section"), left + 10, top + 30, Theme.TEXT_MUTED);
        row(I18n.format("wayfarmap.clean.logs"), s == null ? null : s.logs, rowY(0));
        section(I18n.format("wayfarmap.clean.world_section"), rowY(1) - 16);
        row(I18n.format("wayfarmap.stats.flat"), s == null ? null : s.all2d, rowY(1));
        row(I18n.format("wayfarmap.stats.iso"), s == null ? null : s.all3d, rowY(2));
        section(I18n.format("wayfarmap.clean.dimension_section"), dimensionPickerY() - 14);
        long[] dim = s == null || picked == null ? null : s.dimensions.get(picked);
        row(I18n.format("wayfarmap.stats.flat"), dim == null ? null : dim[0], rowY(3));
        row(I18n.format("wayfarmap.stats.iso"), dim == null ? null : dim[1], rowY(4));
        row(I18n.format("wayfarmap.clean.whole_dimension"), dim == null ? null : dim[0] + dim[1], rowY(5));

        String note = armed != -1 ? I18n.format("wayfarmap.clean.confirm_hint") : status;
        if (!note.isEmpty()) {
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, note, right - left - 110),
                left + 10,
                bottom - 34,
                armed != -1 ? Theme.DANGER : statusColor);
        }
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.clean.note"), right - left - 110),
            left + 10,
            bottom - 21,
            Theme.TEXT_MUTED);
        super.drawScaled(mouseX, mouseY, partialTicks);
        if (listOpen) {
            // Over everything else.
            drawList(mouseX, mouseY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
