package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.BlockColors;

/**
 * All mod settings: categories in a sidebar, the options of the selected one on the right. A search box over the
 * options finds them on every tab; changed options are marked and can be put back to their default one by one.
 */
public class GuiSettings extends ScaledScreen {

    private static final int SIDEBAR_WIDTH = 112;
    private static final int ROW_HEIGHT = 22;
    /** Height of a section title between the options. */
    private static final int HEADER_HEIGHT = 18;
    /** How far options that depend on a switch are moved in under it. */
    private static final int INDENT = 10;
    private static final int CONTROL_WIDTH = 104;
    /** Size of the "back to default" button right of a changed option's control. */
    private static final int RESET_SIZE = 9;
    private static final int SEARCH_WIDTH = 120;
    private static final int ID_RESET = 100, ID_DONE = 101, ID_RESET_ALL = 102, ID_RESET_TAB = 103,
        ID_RESET_CANCEL = 104;

    /** A circular arrow: back to the default value. */
    private static final String[] RESET_ICON = { "..###.#", ".#...##", "#...###", "#......", "#.....#", ".#...#.",
        "..###.." };

    /** Last opened category, kept while the game runs. */
    private static int selectedCategory;

    private final GuiScreen parent;
    private final boolean textureColorsBefore = Config.useTextureColors;

    private int left, top, right, bottom;
    private int contentLeft, contentTop, contentBottom;
    private int scroll;
    private Config.Option draggingSlider;
    /** The scrollbar is being dragged; where on its thumb it was taken. */
    private boolean draggingScrollbar;
    private int scrollbarGrab;
    /** The reset button was pressed: it is replaced by "reset all", "reset this tab" and "cancel". */
    private boolean confirmingReset;
    private FlatTextField searchField;
    /** The search typed, kept when the screen is laid out again (window resized, back from the color picker). */
    private String searchText = "";
    /** The lines of the list, rebuilt when the tab or the search changes. */
    private List<Row> rows;
    /** The option under the mouse, changed with the arrow keys. */
    private Config.Option hoveredOption;

    /** A line of the options list: a section title, or an option. */
    private static final class Row {

        /** The option, or null for a section title. */
        final Config.Option option;
        /** The section title, translated. */
        final String title;
        /** Top, from the top of the list (not scrolled). */
        final int y;
        final int height;
        /** How far the name is moved in: under the switch it depends on. */
        final int indent;

        Row(Config.Option option, String title, int y, int height, int indent) {
            this.option = option;
            this.title = title;
            this.y = y;
            this.height = height;
            this.indent = indent;
        }
    }

    public GuiSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelWidth = Math.min(width - 16, 520);
        int panelHeight = Math.min(height - 16, 340);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 10;
        contentTop = top + 28;
        contentBottom = bottom - 8;

        boolean searchFocused = searchField != null && searchField.isFocused();
        searchField = new FlatTextField(fontRendererObj, right - 10 - SEARCH_WIDTH, top + 5, SEARCH_WIDTH, 15)
            .setHint(I18n.format("wayfarmap.settings.search"));
        searchField.setMaxStringLength(40);
        searchField.setText(searchText);
        searchField.setFocused(searchFocused);

        buttonList.clear();
        for (int i = 0; i < Config.CATEGORIES.size(); i++) {
            buttonList.add(
                new FlatButton(
                    i,
                    left + 6,
                    top + 26 + i * 20,
                    SIDEBAR_WIDTH - 12,
                    18,
                    I18n.format("wayfarmap.settings." + Config.CATEGORIES.get(i))));
        }
        buttonList.add(
            new FlatButton(
                ID_RESET,
                left + 6,
                bottom - 48,
                SIDEBAR_WIDTH - 12,
                18,
                I18n.format("wayfarmap.settings.reset")));
        buttonList.add(new FlatButton(ID_DONE, left + 6, bottom - 26, SIDEBAR_WIDTH - 12, 18, I18n.format("gui.done")));
        // Asked after the reset button: stacked over where it was.
        FlatButton resetAll = new FlatButton(
            ID_RESET_ALL,
            left + 6,
            bottom - 92,
            SIDEBAR_WIDTH - 12,
            18,
            I18n.format("wayfarmap.settings.reset_all"));
        resetAll.danger = true;
        buttonList.add(resetAll);
        FlatButton resetTab = new FlatButton(
            ID_RESET_TAB,
            left + 6,
            bottom - 70,
            SIDEBAR_WIDTH - 12,
            18,
            I18n.format("wayfarmap.settings.reset_tab"));
        resetTab.danger = true;
        buttonList.add(resetTab);
        buttonList.add(
            new FlatButton(ID_RESET_CANCEL, left + 6, bottom - 48, SIDEBAR_WIDTH - 12, 18, I18n.format("gui.cancel")));
        updateButtons();
        rows = null;
        clampScroll();
    }

    /**
     * Highlights the open tab (none while searching), and shows either the reset button or the three buttons asking
     * what to reset. Applied when drawing, not right on the click: the click goes on to the buttons after the pressed
     * one, and "cancel" sits where "reset" was.
     */
    private void updateButtons() {
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id < Config.CATEGORIES.size()) {
                ((FlatButton) button).active = !searching() && button.id == selectedCategory;
            } else if (button.id == ID_RESET) {
                button.visible = !confirmingReset;
            } else if (button.id == ID_RESET_ALL || button.id == ID_RESET_TAB || button.id == ID_RESET_CANCEL) {
                button.visible = confirmingReset;
            }
        }
    }

    private boolean searching() {
        String query = searchText.trim();
        return !query.isEmpty();
    }

    private List<Config.Option> options() {
        return Config.getOptions(Config.CATEGORIES.get(selectedCategory));
    }

    private List<Row> rows() {
        if (rows == null) {
            rows = searching() ? searchRows() : tabRows();
        }
        return rows;
    }

    /** The options of the open tab with a title before each section. */
    private List<Row> tabRows() {
        List<Row> result = new ArrayList<>();
        String group = null;
        int y = 0;
        for (Config.Option option : options()) {
            if (!option.group.isEmpty() && !option.group.equals(group)) {
                // A little room above every title but the first.
                y += result.isEmpty() ? 0 : 4;
                result.add(new Row(null, groupTitle(option.group), y, HEADER_HEIGHT, 0));
                y += HEADER_HEIGHT;
            }
            group = option.group;
            int indent = option.parent != null && option.parent.group.equals(option.group) ? INDENT : 0;
            result.add(new Row(option, null, y, ROW_HEIGHT, indent));
            y += ROW_HEIGHT;
        }
        return result;
    }

    /** The options of every tab whose name or description has the search in it, under "tab / section" titles. */
    private List<Row> searchRows() {
        String query = searchText.trim();
        query = query.toLowerCase(Locale.ROOT);
        List<Row> result = new ArrayList<>();
        int y = 0;
        for (String tab : Config.CATEGORIES) {
            String title = null;
            Set<Config.Option> shown = new HashSet<>();
            for (Config.Option option : Config.getOptions(tab)) {
                String text = I18n.format(option.langKey()) + "\n" + I18n.format(option.langKey() + ".desc");
                text = text.toLowerCase(Locale.ROOT);
                if (!text.contains(query)) {
                    continue;
                }
                String optionTitle = I18n.format("wayfarmap.settings." + tab)
                    + (option.group.isEmpty() ? "" : " / " + groupTitle(option.group));
                if (!optionTitle.equals(title)) {
                    y += result.isEmpty() ? 0 : 4;
                    result.add(new Row(null, optionTitle, y, HEADER_HEIGHT, 0));
                    y += HEADER_HEIGHT;
                    title = optionTitle;
                    shown.clear();
                }
                // Moved in only when the switch it depends on was found too, just above.
                int indent = option.parent != null && shown.contains(option.parent) ? INDENT : 0;
                shown.add(option);
                result.add(new Row(option, null, y, ROW_HEIGHT, indent));
                y += ROW_HEIGHT;
            }
        }
        return result;
    }

    private static String groupTitle(String group) {
        return I18n.format("wayfarmap.settings.group." + group);
    }

    private int listHeight() {
        List<Row> list = rows();
        return list.isEmpty() ? 0 : list.get(list.size() - 1).y + list.get(list.size() - 1).height;
    }

    private int maxScroll() {
        return Math.max(0, listHeight() - (contentBottom - contentTop));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int resetX() {
        return right - 10 - RESET_SIZE;
    }

    private int controlX() {
        return resetX() - 4 - CONTROL_WIDTH;
    }

    private void selectCategory(int category) {
        selectedCategory = category;
        scroll = 0;
        confirmingReset = false;
        searchText = "";
        searchField.setText("");
        searchField.setFocused(false);
        rows = null;
    }

    /** The search changed: the list is built again from its top. */
    private void searchChanged() {
        String text = searchField.getText();
        if (!text.equals(searchText)) {
            searchText = text;
            scroll = 0;
            rows = null;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < Config.CATEGORIES.size()) {
            selectCategory(button.id);
        } else if (button.id == ID_RESET) {
            confirmingReset = true;
        } else if (button.id == ID_RESET_ALL || button.id == ID_RESET_TAB || button.id == ID_RESET_CANCEL) {
            if (button.id != ID_RESET_CANCEL) {
                for (Config.Option option : button.id == ID_RESET_ALL ? Config.OPTIONS : options()) {
                    option.reset();
                }
            }
            confirmingReset = false;
        } else if (button.id == ID_DONE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        Config.save();
        if (Config.useTextureColors != textureColorsBefore) {
            // Chunks are rescanned over time and pick up the new colors.
            BlockColors.clearCache();
        }
    }

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (confirmingReset) {
                confirmingReset = false;
            } else if (searching() || searchField.isFocused()) {
                searchField.setText("");
                searchField.setFocused(false);
                searchChanged();
            } else {
                mc.displayGuiScreen(parent);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_F && isCtrlKeyDown()) {
            searchField.setFocused(true);
            return;
        }
        if (searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchField.setFocused(false);
            } else {
                searchField.textboxKeyTyped(typedChar, keyCode);
                searchChanged();
            }
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_TAB: {
                int count = Config.CATEGORIES.size();
                selectCategory((selectedCategory + (isShiftKeyDown() ? count - 1 : 1)) % count);
                return;
            }
            case Keyboard.KEY_PRIOR:
                scroll -= contentBottom - contentTop - ROW_HEIGHT;
                break;
            case Keyboard.KEY_NEXT:
                scroll += contentBottom - contentTop - ROW_HEIGHT;
                break;
            case Keyboard.KEY_HOME:
                scroll = 0;
                break;
            case Keyboard.KEY_END:
                scroll = maxScroll();
                break;
            case Keyboard.KEY_UP:
                scroll -= ROW_HEIGHT;
                break;
            case Keyboard.KEY_DOWN:
                scroll += ROW_HEIGHT;
                break;
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_RIGHT:
                if (hoveredOption != null) {
                    step(hoveredOption, keyCode == Keyboard.KEY_RIGHT);
                }
                break;
            default:
                // Typing anywhere starts a search.
                if (Character.isLetterOrDigit(typedChar)) {
                    searchField.setFocused(true);
                    searchField.textboxKeyTyped(typedChar, keyCode);
                    searchChanged();
                }
        }
        clampScroll();
    }

    /** One step of the option's value: a slider by its step, a list to the next value, a switch flipped. */
    private static void step(Config.Option option, boolean forward) {
        if (option instanceof Config.BoolOption) {
            Config.BoolOption bool = (Config.BoolOption) option;
            bool.set(!bool.get());
        } else if (option instanceof Config.ChoiceOption) {
            Config.ChoiceOption choice = (Config.ChoiceOption) option;
            int count = choice.values.length;
            choice.set((choice.get() + (forward ? 1 : count - 1)) % count);
        } else if (option instanceof Config.IntOption) {
            Config.IntOption intOption = (Config.IntOption) option;
            intOption.set(intOption.get() + (forward ? intOption.step : -intOption.step));
        } else if (option instanceof Config.DoubleOption) {
            Config.DoubleOption doubleOption = (Config.DoubleOption) option;
            doubleOption.set(doubleOption.get() + (forward ? doubleOption.step : -doubleOption.step));
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * ROW_HEIGHT;
            clampScroll();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        searchField.mouseClicked(mouseX, mouseY, button);
        if (mouseY < contentTop || mouseY >= contentBottom) {
            return;
        }
        if (button == 0 && maxScroll() > 0 && mouseX >= right - 7 && mouseX < right - 1) {
            int[] thumb = scrollbarThumb();
            draggingScrollbar = true;
            // Taken by the thumb: it keeps its place under the mouse; elsewhere it jumps there, centered.
            boolean onThumb = mouseY >= thumb[0] && mouseY < thumb[1];
            scrollbarGrab = onThumb ? mouseY - thumb[0] : (thumb[1] - thumb[0]) / 2;
            dragScrollbar(mouseY);
            return;
        }
        if (mouseX < contentLeft - 4 || mouseX >= right - 6) {
            return;
        }
        for (Row row : rows()) {
            int y = contentTop + row.y - scroll;
            if (row.option == null || mouseY < y || mouseY >= y + ROW_HEIGHT) {
                continue;
            }
            Config.Option option = row.option;
            int controlRight = controlX() + CONTROL_WIDTH;
            boolean onControl = Theme.inside(mouseX, mouseY, controlX(), y + 3, controlRight, y + ROW_HEIGHT - 3);
            if (!option.isDefault() && mouseX >= resetX() - 2) {
                if (button == 0) {
                    option.reset();
                }
            } else if (option instanceof Config.BoolOption) {
                // The whole row flips a switch, not only the switch itself.
                step(option, true);
            } else if (!onControl) {
                return;
            } else if (option instanceof Config.ChoiceOption) {
                // Left half goes back, right half forward; right click also goes back.
                step(option, button != 1 && mouseX >= controlX() + CONTROL_WIDTH / 2);
            } else if (option instanceof Config.ColorOption) {
                if (button == 0) {
                    Config.ColorOption color = (Config.ColorOption) option;
                    mc.displayGuiScreen(new GuiColorPicker(this, color.get(), color::set));
                }
            } else if (option instanceof Config.PositionOption) {
                if (button == 0) {
                    mc.displayGuiScreen(new GuiMinimapPosition(this));
                }
            } else if (button == 0) {
                draggingSlider = option;
                updateSlider(mouseX);
            }
            return;
        }
    }

    /** Sets the dragged slider from the mouse position. */
    private void updateSlider(int mouseX) {
        double t = Math.max(0, Math.min(1, (mouseX - controlX() - 3) / (double) (CONTROL_WIDTH - 6)));
        if (draggingSlider instanceof Config.IntOption) {
            Config.IntOption option = (Config.IntOption) draggingSlider;
            double raw = option.min + t * (option.max - option.min);
            option.set(option.min + (int) Math.round((raw - option.min) / option.step) * option.step);
        } else if (draggingSlider instanceof Config.DoubleOption) {
            Config.DoubleOption option = (Config.DoubleOption) draggingSlider;
            option.set(option.min + t * (option.max - option.min));
        }
    }

    /** Top and bottom of the scrollbar's thumb. */
    private int[] scrollbarThumb() {
        int track = contentBottom - contentTop;
        int bar = Math.max(16, track * track / (track + maxScroll()));
        int barY = contentTop + (track - bar) * scroll / Math.max(1, maxScroll());
        return new int[] { barY, barY + bar };
    }

    private void dragScrollbar(int mouseY) {
        int[] thumb = scrollbarThumb();
        int free = contentBottom - contentTop - (thumb[1] - thumb[0]);
        if (free > 0) {
            scroll = (int) Math.round((mouseY - scrollbarGrab - contentTop) * (double) maxScroll() / free);
            clampScroll();
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        updateButtons();
        if (draggingSlider != null) {
            if (Mouse.isButtonDown(0)) {
                updateSlider(mouseX);
            } else {
                draggingSlider = null;
            }
        }
        if (draggingScrollbar) {
            if (Mouse.isButtonDown(0)) {
                dragScrollbar(mouseY);
            } else {
                draggingScrollbar = false;
            }
        }

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.fill(left + 1, top + 1, left + SIDEBAR_WIDTH, bottom - 1, Theme.PANEL_ALT);
        Theme.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, bottom - 1, Theme.BORDER);
        Icons.drawLogo(left + 7, top + 5);
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, "Wayfarer's Map", SIDEBAR_WIDTH - 32),
            left + 8 + Icons.LOGO_SIZE + 4,
            top + 9,
            Theme.ACCENT);

        String category = Config.CATEGORIES.get(selectedCategory);
        String titleKey = searching() ? "search_results" : category;
        String title = I18n.format("wayfarmap.settings." + titleKey);
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, title, right - 10 - SEARCH_WIDTH - 8 - contentLeft),
            contentLeft,
            top + 9,
            Theme.TEXT);
        Theme.fill(contentLeft, top + 22, right - 10, top + 23, Theme.BORDER);
        searchField.drawTextBox();

        Config.Option hovered = null;
        int hoveredY = 0;
        boolean overReset = false;
        hoveredOption = null;
        for (Row row : rows()) {
            int y = contentTop + row.y - scroll;
            if (y + row.height <= contentTop || y >= contentBottom) {
                continue;
            }
            boolean whole = y >= contentTop && y + row.height <= contentBottom;
            if (row.option == null) {
                if (whole) {
                    Theme.text(fontRendererObj, row.title, contentLeft, y + 6, Theme.ACCENT);
                    int lineX = contentLeft + fontRendererObj.getStringWidth(row.title) + 6;
                    Theme.fill(lineX, y + 10, right - 10, y + 11, Theme.BORDER);
                }
                continue;
            }
            Config.Option option = row.option;
            boolean changed = !option.isDefault();
            int visibleTop = Math.max(y, contentTop);
            int visibleBottom = Math.min(y + ROW_HEIGHT, contentBottom);
            boolean rowHovered = Theme.inside(mouseX, mouseY, contentLeft - 4, visibleTop, right - 6, visibleBottom);
            if (rowHovered && !draggingScrollbar) {
                Theme.fill(contentLeft - 4, visibleTop, right - 6, visibleBottom, Theme.ROW_HOVER);
                hoveredOption = option;
                if (changed && mouseX >= resetX() - 2) {
                    overReset = true;
                    hovered = option;
                    hoveredY = y;
                } else if (mouseX < controlX() - 4) {
                    // The description only over the name: over the control it would hide the rows being changed.
                    hovered = option;
                    hoveredY = y;
                }
            }
            if (whole) {
                if (changed) {
                    // Changed from the default: a mark at the start of the row.
                    Theme.fill(contentLeft - 4, y + 4, contentLeft - 2, y + ROW_HEIGHT - 4, Theme.ACCENT);
                }
                int x = contentLeft + row.indent;
                if (row.indent > 0) {
                    // A short line from the switch this one depends on.
                    Theme.fill(contentLeft + 2, y + 3, contentLeft + 3, y + 12, Theme.BORDER);
                    Theme.fill(contentLeft + 2, y + 11, x - 2, y + 12, Theme.BORDER);
                }
                boolean off = option.parent != null && !option.parent.get();
                int nameWidth = controlX() - x - controlLeftWidth(option) - 6;
                String name = Theme.ellipsize(fontRendererObj, I18n.format(option.langKey()), nameWidth);
                Theme.text(fontRendererObj, name, x, y + 7, off ? Theme.TEXT_MUTED : Theme.TEXT);
                drawControl(option, controlX(), y + 3, mouseX, mouseY);
                if (changed) {
                    boolean over = overReset && hovered == option;
                    Icons.draw(
                        RESET_ICON,
                        resetX() + 1,
                        y + (ROW_HEIGHT - RESET_ICON.length) / 2,
                        over ? Theme.ACCENT : Theme.TEXT_MUTED);
                }
            }
        }
        if (rows().isEmpty()) {
            Theme.centered(
                fontRendererObj,
                I18n.format("wayfarmap.settings.no_results"),
                (contentLeft + right - 10) / 2,
                contentTop + 30,
                Theme.TEXT_MUTED);
        }
        if (!searching() && Config.CATEGORY_PLAYER_MARKER.equals(category)) {
            drawMarkerPreview();
        }
        if (maxScroll() > 0) {
            int[] thumb = scrollbarThumb();
            boolean over = Theme.inside(mouseX, mouseY, right - 7, contentTop, right - 1, contentBottom);
            over |= draggingScrollbar;
            Theme.fill(right - 5, contentTop, right - 3, contentBottom, Theme.CONTROL);
            Theme.fill(right - 5, thumb[0], right - 3, thumb[1], over ? Theme.ACCENT : Theme.BORDER);
        }

        super.drawScaled(mouseX, mouseY, partialTicks);
        drawChangedMarks();
        if (hovered != null) {
            drawDescription(hovered, hoveredY, overReset);
        }
    }

    /** A dot on the sidebar's tabs that have options changed from their defaults. */
    private void drawChangedMarks() {
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id >= Config.CATEGORIES.size()) {
                continue;
            }
            for (Config.Option option : Config.getOptions(Config.CATEGORIES.get(button.id))) {
                if (!option.isDefault()) {
                    FlatButton tab = (FlatButton) button;
                    int x = tab.xPosition + tab.getWidth() - 7, y = tab.yPosition + 7;
                    // On the open tab's accent background the accent would not show.
                    boolean open = tab.active;
                    Theme.fill(x, y, x + 4, y + 4, open ? Theme.TEXT : Theme.ACCENT);
                    break;
                }
            }
        }
    }

    /** Under the marker's options: the marker as on the world map, on dark and on light ground. */
    private void drawMarkerPreview() {
        int y = contentTop + listHeight() + 10 - scroll;
        int boxHeight = 64, boxRight = right - 10;
        if (y + 12 < contentTop || y + 12 + boxHeight > contentBottom) {
            return;
        }
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.marker_preview"), contentLeft, y, Theme.ACCENT);
        y += 12;
        int middle = (contentLeft + boxRight) / 2;
        Theme.fill(contentLeft, y, middle, y + boxHeight, 0xFF0C0E11);
        Theme.fill(middle, y, boxRight, y + boxHeight, 0xFFC9D3B4);
        Theme.outline(contentLeft, y, boxRight, y + boxHeight, Theme.BORDER);
        // Turning slowly, so its direction shows. The world map's size (5 at 100%).
        float yaw = (System.currentTimeMillis() % 8000L) * 360f / 8000f;
        double cy = y + boxHeight / 2.0;
        MapDrawer.drawPlayerArrow((contentLeft + middle) / 2.0, cy, yaw, 5f);
        MapDrawer.drawPlayerArrow((middle + boxRight) / 2.0, cy, yaw, 5f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * The option's description in full with its default value, in a box under its row (above it when there is no room
     * below), so long descriptions are never cut off. Over the reset button, only what it resets to.
     */
    private void drawDescription(Config.Option option, int rowY, boolean reset) {
        int boxLeft = contentLeft - 4, boxRight = right - 6;
        List<String> lines = new ArrayList<>();
        if (!reset) {
            String description = I18n.format(option.langKey() + ".desc");
            for (Object line : fontRendererObj.listFormattedStringToWidth(description, boxRight - boxLeft - 10)) {
                lines.add(String.valueOf(line));
            }
        }
        String defaultValue = defaultText(option);
        String footer = null;
        if (defaultValue != null) {
            footer = I18n.format(reset ? "wayfarmap.settings.reset_to" : "wayfarmap.settings.default", defaultValue);
        } else if (reset) {
            footer = I18n.format("wayfarmap.settings.reset_option");
        }
        if (lines.isEmpty() && footer == null) {
            return;
        }
        int boxHeight = lines.size() * 10 + 8;
        if (footer != null) {
            // Under a thin line when there is a description above.
            boxHeight += lines.isEmpty() ? 10 : 13;
        }
        int boxTop = rowY + ROW_HEIGHT;
        if (boxTop + boxHeight > bottom - 4) {
            boxTop = Math.max(top + 4, rowY - boxHeight);
        }
        Theme.fill(boxLeft, boxTop, boxRight, boxTop + boxHeight, 0xFF12161B);
        Theme.outline(boxLeft, boxTop, boxRight, boxTop + boxHeight, Theme.ACCENT_DIM);
        for (int i = 0; i < lines.size(); i++) {
            Theme.text(fontRendererObj, lines.get(i), boxLeft + 5, boxTop + 5 + i * 10, Theme.TEXT);
        }
        if (footer != null) {
            int y = boxTop + 5 + lines.size() * 10;
            if (!lines.isEmpty()) {
                Theme.fill(boxLeft + 5, y, boxRight - 5, y + 1, Theme.BORDER);
                y += 3;
            }
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, footer, boxRight - boxLeft - 10),
                boxLeft + 5,
                y,
                Theme.TEXT_MUTED);
        }
    }

    /** The option's default value as shown on its control; null when it has none to show (the minimap position). */
    private String defaultText(Config.Option option) {
        if (option instanceof Config.BoolOption) {
            return I18n.format(((Config.BoolOption) option).defaultValue ? "options.on" : "options.off");
        } else if (option instanceof Config.ChoiceOption) {
            Config.ChoiceOption choice = (Config.ChoiceOption) option;
            return I18n.format(option.langKey() + "." + choice.values[choice.defaultValue]);
        } else if (option instanceof Config.IntOption) {
            return intText((Config.IntOption) option, ((Config.IntOption) option).defaultValue);
        } else if (option instanceof Config.DoubleOption) {
            return String.format(Locale.ROOT, "%.2f", ((Config.DoubleOption) option).defaultValue);
        } else if (option instanceof Config.ColorOption) {
            return Config.ColorOption.hex(((Config.ColorOption) option).defaultValue);
        }
        return null;
    }

    /** The slider's value as shown next to it. */
    private static String sliderText(Config.Option option) {
        if (option instanceof Config.IntOption) {
            return intText((Config.IntOption) option, ((Config.IntOption) option).get());
        }
        return String.format(Locale.ROOT, "%.2f", ((Config.DoubleOption) option).get());
    }

    private static boolean isSlider(Config.Option option) {
        if (option instanceof Config.ChoiceOption) {
            return false;
        }
        return option instanceof Config.IntOption || option instanceof Config.DoubleOption;
    }

    /** How far left of its control the option draws: a slider's value box. */
    private int controlLeftWidth(Config.Option option) {
        return isSlider(option) ? fontRendererObj.getStringWidth(sliderText(option)) + 12 : 0;
    }

    private static String intText(Config.IntOption option, int value) {
        if (value == 0 && "maxDistance".equals(option.key)) {
            return I18n.format("wayfarmap.settings.unlimited");
        }
        if ("isoQuality".equals(option.key)) {
            return I18n.format("wayfarmap.iso.quality_value", 8 << value);
        }
        return String.valueOf(value);
    }

    private void drawControl(Config.Option option, int x, int y, int mouseX, int mouseY) {
        int h = ROW_HEIGHT - 6;
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + CONTROL_WIDTH, y + h);
        if (option instanceof Config.BoolOption) {
            boolean on = ((Config.BoolOption) option).get();
            int switchX = x + CONTROL_WIDTH - 24;
            int switchY = y + (h - 12) / 2;
            boolean rowHovered = hoveredOption == option;
            Theme.fill(switchX, switchY, switchX + 24, switchY + 12, on ? Theme.ACCENT : Theme.CONTROL);
            Theme.outline(switchX, switchY, switchX + 24, switchY + 12, rowHovered ? Theme.ACCENT : Theme.BORDER);
            int knobX = on ? switchX + 14 : switchX + 2;
            Theme.fill(knobX, switchY + 2, knobX + 8, switchY + 10, Theme.TEXT);
            String state = I18n.format(on ? "options.on" : "options.off");
            Theme.text(
                fontRendererObj,
                state,
                switchX - 6 - fontRendererObj.getStringWidth(state),
                y + (h - 8) / 2,
                on ? Theme.TEXT : Theme.TEXT_MUTED);
        } else if (option instanceof Config.ChoiceOption) {
            Config.ChoiceOption choice = (Config.ChoiceOption) option;
            Theme.fill(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.ACCENT : Theme.BORDER);
            boolean backHovered = hovered && mouseX < x + CONTROL_WIDTH / 2;
            Theme.text(fontRendererObj, "<", x + 4, y + (h - 8) / 2, backHovered ? Theme.ACCENT : Theme.TEXT_MUTED);
            Theme.text(
                fontRendererObj,
                ">",
                x + CONTROL_WIDTH - 9,
                y + (h - 8) / 2,
                hovered && !backHovered ? Theme.ACCENT : Theme.TEXT_MUTED);
            String value = I18n.format(option.langKey() + "." + choice.values[choice.get()]);
            Theme.centered(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, value, CONTROL_WIDTH - 24),
                x + CONTROL_WIDTH / 2,
                y + (h - 8) / 2,
                Theme.TEXT);
            // Which of the values it is: a dot each along the bottom edge.
            int count = choice.values.length;
            if (count <= 12) {
                int dotsX = x + CONTROL_WIDTH / 2 - (count * 4 - 2) / 2;
                for (int i = 0; i < count; i++) {
                    Theme.fill(
                        dotsX + i * 4,
                        y + h - 3,
                        dotsX + i * 4 + 2,
                        y + h - 2,
                        i == choice.get() ? Theme.ACCENT : Theme.BORDER);
                }
            }
        } else if (option instanceof Config.ColorOption) {
            int rgb = ((Config.ColorOption) option).get();
            Theme.fill(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.ACCENT : Theme.BORDER);
            // A swatch of the color, then its code.
            Theme.fill(x + 3, y + 3, x + 3 + 2 * (h - 6), y + h - 3, 0xFF000000 | rgb);
            Theme.outline(x + 3, y + 3, x + 3 + 2 * (h - 6), y + h - 3, Theme.BORDER);
            Theme.text(fontRendererObj, Config.ColorOption.hex(rgb), x + 8 + 2 * (h - 6), y + (h - 8) / 2, Theme.TEXT);
        } else if (option instanceof Config.PositionOption) {
            Theme.fill(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.ACCENT : Theme.BORDER);
            Theme.centered(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.minimap_position.change"), CONTROL_WIDTH - 8),
                x + CONTROL_WIDTH / 2,
                y + (h - 8) / 2,
                Theme.TEXT);
        } else {
            double t;
            String value = sliderText(option);
            if (option instanceof Config.IntOption) {
                Config.IntOption intOption = (Config.IntOption) option;
                t = (intOption.get() - intOption.min) / (double) (intOption.max - intOption.min);
            } else {
                Config.DoubleOption doubleOption = (Config.DoubleOption) option;
                t = (doubleOption.get() - doubleOption.min) / (doubleOption.max - doubleOption.min);
            }
            boolean active = hovered || draggingSlider == option;
            int trackY = y + h / 2 - 1;
            Theme.fill(x + 3, trackY, x + CONTROL_WIDTH - 3, trackY + 3, Theme.CONTROL);
            int knob = x + 3 + (int) Math.round(t * (CONTROL_WIDTH - 6));
            Theme.fill(x + 3, trackY, knob, trackY + 3, active ? Theme.ACCENT : Theme.ACCENT_DIM);
            Theme.fill(knob - 3, y + 2, knob + 3, y + h - 2, active ? Theme.ACCENT : Theme.TEXT);
            Theme.outline(knob - 3, y + 2, knob + 3, y + h - 2, Theme.BORDER);
            // The value in a small box left of the slider.
            int boxX = x - controlLeftWidth(option);
            Theme.fill(boxX, y + 1, x - 4, y + h - 1, 0xFF0F1216);
            Theme.outline(boxX, y + 1, x - 4, y + h - 1, active ? Theme.ACCENT_DIM : Theme.BORDER);
            int valueColor = active ? Theme.TEXT : Theme.TEXT_MUTED;
            Theme.text(fontRendererObj, value, boxX + 4, y + (h - 8) / 2, valueColor);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
