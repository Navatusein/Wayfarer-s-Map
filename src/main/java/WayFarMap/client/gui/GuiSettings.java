package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.MobPreview;
import WayFarMap.client.PlayerTrail;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.BlockColors;

/**
 * All mod settings: categories in a sidebar, the options of the selected one on the right and the description of the
 * one under the mouse below them. A search box over the options finds them on every tab; changed options are marked
 * and can be put back to their default one by one; sections can be closed.
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
    /** Room under the options for the description: its name, two lines and the default value. */
    private static final int DESCRIPTION_HEIGHT = 52;
    /** Height of the trail's preview, and the last of the trail's options, which it comes after. */
    private static final int TRAIL_PREVIEW_HEIGHT = 84;
    private static final String TRAIL_LAST_OPTION = "playerTrailAnimated";
    /** Room over the minimap's options for its preview. */
    private static final int PREVIEW_HEIGHT = 96;
    /** Room over the mobs' options for their preview. */
    private static final int MOBS_PREVIEW_HEIGHT = 100;
    /** Room over the player icon's options for its preview. */
    private static final int MARKER_PREVIEW_HEIGHT = 84;
    /** Size of the squares the preview's ground is drawn with. */
    private static final int PREVIEW_CELL = 2;
    /** How long the options slide in after another tab is opened, in milliseconds. */
    private static final long TAB_SLIDE_MS = 160;
    private static final int ID_RESET = 100, ID_DONE = 101, ID_RESET_ALL = 102, ID_RESET_TAB = 103,
        ID_RESET_CANCEL = 104;

    /** A circular arrow: back to the default value. */
    private static final String[] RESET_ICON = { "..###.#", ".#...##", "#...###", "#......", "#.....#", ".#...#.",
        "..###.." };
    private static final String[] COMPASS_LETTERS = { "N", "E", "S", "W" };

    /** Last opened category, kept while the game runs. */
    private static int selectedCategory;
    /** Closed sections ("tab/group"), kept while the game runs. */
    private static final Set<String> CLOSED_SECTIONS = new HashSet<>();

    private final GuiScreen parent;
    private final boolean textureColorsBefore = Config.useTextureColors;

    private int left, top, right, bottom;
    private int contentLeft, contentTop, contentBottom;
    /** Where the list scrolls to, and where it is drawn while it gets there. */
    private int scroll;
    private double shownScroll;
    private Config.Option draggingSlider;
    /** The scrollbar is being dragged; where on its thumb it was taken. */
    private boolean draggingScrollbar;
    private int scrollbarGrab;
    /** The reset button was pressed: it is replaced by "reset all", "reset this tab" and "cancel". */
    private boolean confirmingReset;
    private FlatTextField searchField;
    /** The search typed, kept when the screen is laid out again (window resized, back from the color picker). */
    private String searchText = "";
    /** The lines of the list, rebuilt when the tab, the search or a closed section changes. */
    private List<Row> rows;
    /** The option under the mouse, changed with the arrow keys. */
    private Config.Option hoveredOption;
    /** The slider whose value is being typed in, and the field it is typed in; null when none. */
    private Config.Option editedOption;
    private FlatTextField valueField;

    /** Per option: how much its row is lit by the mouse, and where its switch's knob is (0 off to 1 on). */
    private final Map<Config.Option, float[]> animations = new IdentityHashMap<>();
    private long lastFrame;
    /** Seconds since the last frame, for the animations. */
    private float frameTime;
    /** When the open tab was chosen, to slide its options in. */
    private long tabOpenedAt;
    /** Top of the mark beside the open tab, moving to it when another one is chosen; below 0 before the first. */
    private float tabMarkY = -1;

    /** A line of the options list: a section title, or an option. */
    private static final class Row {

        /** The option, or null for a section title. */
        final Config.Option option;
        /** The section title, translated. */
        final String title;
        /** The section the title opens and closes ("tab/group"); null when it can't be closed. */
        final String section;
        /** Options under the title while its section is closed. */
        final int hidden;
        /** Top, from the top of the list (not scrolled). */
        final int y;
        final int height;
        /** How far the name is moved in: under the switch it depends on. */
        final int indent;
        /** The player's trail drawn as it is set, under the trail's options. */
        boolean trailPreview;

        private Row(Config.Option option, String title, String section, int hidden, int y, int height, int indent) {
            this.option = option;
            this.title = title;
            this.section = section;
            this.hidden = hidden;
            this.y = y;
            this.height = height;
            this.indent = indent;
        }

        static Row title(String title, String section, int hidden, int y) {
            return new Row(null, title, section, hidden, y, HEADER_HEIGHT, 0);
        }

        static Row option(Config.Option option, int y, int indent) {
            return new Row(option, null, null, 0, y, ROW_HEIGHT, indent);
        }

        static Row trailPreview(int y) {
            Row row = new Row(null, null, null, 0, y, TRAIL_PREVIEW_HEIGHT, 0);
            row.trailPreview = true;
            return row;
        }
    }

    /**
     * Where the list was scrolled and what was searched when the screen was last closed: it opens the same way again
     * until the game is restarted, so settings can be tried in the game and changed further.
     */
    private static int savedScroll;
    private static String savedSearch = "";

    public GuiSettings(GuiScreen parent) {
        this.parent = parent;
        scroll = savedScroll;
        searchText = savedSearch;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelWidth = Math.min(width - 16, 520);
        int panelHeight = Math.min(height - 16, 380);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 10;
        contentTop = top + 28;
        contentBottom = bottom - 8 - DESCRIPTION_HEIGHT - 4;

        boolean searchFocused = searchField != null && searchField.isFocused();
        searchField = new FlatTextField(fontRendererObj, right - 10 - SEARCH_WIDTH, top + 5, SEARCH_WIDTH, 15)
            .setHint(I18n.format("wayfarmap.settings.search"));
        searchField.setMaxStringLength(40);
        searchField.setText(searchText);
        searchField.setFocused(searchFocused);

        buttonList.clear();
        // The tabs' icons are centered in one column and their names start on one line.
        int iconSlot = 0;
        for (String category : Config.CATEGORIES) {
            iconSlot = Math.max(iconSlot, Icons.width(tabIcon(category)));
        }
        for (int i = 0; i < Config.CATEGORIES.size(); i++) {
            String category = Config.CATEGORIES.get(i);
            FlatButton tab = new FlatButton(
                i,
                left + 6,
                top + 26 + i * 20,
                SIDEBAR_WIDTH - 12,
                18,
                I18n.format("wayfarmap.settings." + category));
            tab.icon = tabIcon(category);
            tab.iconColor = tabColor(category);
            tab.iconSlot = iconSlot;
            buttonList.add(tab);
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
        shownScroll = scroll;
        tabMarkY = -1;
    }

    private static String[] tabIcon(String category) {
        switch (category) {
            case Config.CATEGORY_MINIMAP:
                return Icons.MINIMAP;
            case Config.TAB_MAP_2D:
                return Icons.MAP2D;
            case Config.TAB_MAP_3D:
                return Icons.ISO;
            case Config.CATEGORY_PLAYER_MARKER:
                return Icons.MARKER;
            case Config.CATEGORY_ENTITIES:
                return Icons.TEAM;
            case Config.TAB_MOBS:
                return Icons.MOBS;
            case Config.CATEGORY_WAYPOINTS:
                return Icons.WAYPOINTS;
            case Config.CATEGORY_COMMANDS:
                return Icons.CHUNKLOAD;
            case Config.CATEGORY_LOGS:
                return Icons.LOGS;
            default:
                return Icons.FLAT;
        }
    }

    /** Each tab's color, on its icon in the sidebar. */
    private static int tabColor(String category) {
        switch (category) {
            case Config.CATEGORY_MINIMAP:
                return 0xFF4C9AFF;
            case Config.TAB_MAP_2D:
                return 0xFF5BD6E0;
            case Config.TAB_MAP_3D:
                return 0xFFE8A040;
            case Config.CATEGORY_PLAYER_MARKER:
                return 0xFFE6EAF0;
            case Config.CATEGORY_ENTITIES:
                return 0xFFF2C14E;
            case Config.TAB_MOBS:
                return 0xFF8BD450;
            case Config.CATEGORY_WAYPOINTS:
                return 0xFFE5534B;
            case Config.CATEGORY_COMMANDS:
                return 0xFFC08CFF;
            case Config.CATEGORY_LOGS:
                return 0xFFAAB4C3;
            default:
                return 0xFF6CC24A;
        }
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
        return !query().isEmpty();
    }

    /** The search, trimmed and in lower case. */
    private String query() {
        String query = searchText.trim();
        return query.toLowerCase(Locale.ROOT);
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

    /** The options of the open tab with a title before each section; a closed section only has its title. */
    private List<Row> tabRows() {
        String tab = Config.CATEGORIES.get(selectedCategory);
        List<Config.Option> options = options();
        List<Row> result = new ArrayList<>();
        String group = null;
        boolean closed = false;
        int y = 0;
        for (int i = 0; i < options.size(); i++) {
            Config.Option option = options.get(i);
            if (!option.group.equals(group)) {
                closed = false;
                if (!option.group.isEmpty()) {
                    String section = tab + "/" + option.group;
                    closed = CLOSED_SECTIONS.contains(section);
                    // A little room above every title but the first.
                    y += result.isEmpty() ? 0 : 4;
                    result.add(Row.title(groupTitle(option.group), section, closed ? groupSize(options, i) : 0, y));
                    y += HEADER_HEIGHT;
                }
            }
            group = option.group;
            if (closed) {
                continue;
            }
            int indent = option.parent != null && option.parent.group.equals(option.group) ? INDENT : 0;
            result.add(Row.option(option, y, indent));
            y += ROW_HEIGHT;
            if (TRAIL_LAST_OPTION.equals(option.key)) {
                // Under the trail's options: the trail as they make it.
                result.add(Row.trailPreview(y));
                y += TRAIL_PREVIEW_HEIGHT;
            }
        }
        return result;
    }

    /** How many options in a row from {@code from} are in the same section. */
    private static int groupSize(List<Config.Option> options, int from) {
        String group = options.get(from).group;
        int count = 0;
        for (int i = from; i < options.size() && options.get(i).group.equals(group); i++) {
            count++;
        }
        return count;
    }

    /** The options of every tab whose name or description has the search in it, under "tab / section" titles. */
    private List<Row> searchRows() {
        String query = query();
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
                    result.add(Row.title(optionTitle, null, 0, y));
                    y += HEADER_HEIGHT;
                    title = optionTitle;
                    shown.clear();
                }
                // Moved in only when the switch it depends on was found too, just above.
                int indent = option.parent != null && shown.contains(option.parent) ? INDENT : 0;
                shown.add(option);
                result.add(Row.option(option, y, indent));
                y += ROW_HEIGHT;
            }
        }
        return result;
    }

    private static String groupTitle(String group) {
        return I18n.format("wayfarmap.settings.group." + group);
    }

    /** The minimap's tab shows a preview of it over the options. */
    private boolean showsMinimapPreview() {
        return !searching() && Config.CATEGORY_MINIMAP.equals(Config.CATEGORIES.get(selectedCategory));
    }

    /** The mobs' tab shows a preview of them over the options. */
    private boolean showsMobsPreview() {
        return !searching() && Config.TAB_MOBS.equals(Config.CATEGORIES.get(selectedCategory));
    }

    /** The player icon's tab shows a preview of it over the options. */
    private boolean showsMarkerPreview() {
        return !searching() && Config.CATEGORY_PLAYER_MARKER.equals(Config.CATEGORIES.get(selectedCategory));
    }

    /** Top of the options list. */
    private int listTop() {
        if (showsMinimapPreview()) {
            return contentTop + PREVIEW_HEIGHT;
        }
        if (showsMarkerPreview()) {
            return contentTop + MARKER_PREVIEW_HEIGHT;
        }
        return contentTop + (showsMobsPreview() ? MOBS_PREVIEW_HEIGHT : 0);
    }

    private int listHeight() {
        List<Row> list = rows();
        return list.isEmpty() ? 0 : list.get(list.size() - 1).y + list.get(list.size() - 1).height;
    }

    private int maxScroll() {
        return Math.max(0, listHeight() - (contentBottom - listTop()));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Scroll the list is drawn (and clicked) with. */
    private int drawnScroll() {
        return (int) Math.round(shownScroll);
    }

    private int resetX() {
        return right - 10 - RESET_SIZE;
    }

    private int controlX() {
        return resetX() - 4 - CONTROL_WIDTH;
    }

    /** The first switch the option depends on, directly or through others, that is off; null if none. */
    private static Config.BoolOption offParent(Config.Option option) {
        for (Config.BoolOption parent = option.parent; parent != null; parent = parent.parent) {
            if (!parent.get()) {
                return parent;
            }
        }
        return null;
    }

    private void selectCategory(int category) {
        if (category != selectedCategory || searching()) {
            tabOpenedAt = System.currentTimeMillis();
        }
        selectedCategory = category;
        confirmingReset = false;
        searchText = "";
        searchField.setText("");
        searchField.setFocused(false);
        listChanged();
    }

    /** The list is built again and shown from its top. */
    private void listChanged() {
        rows = null;
        scroll = 0;
        shownScroll = 0;
    }

    /** The search changed: the list is built again from its top. */
    private void searchChanged() {
        String text = searchField.getText();
        if (!text.equals(searchText)) {
            searchText = text;
            listChanged();
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
        if (editedOption != null) {
            stopEditing(true);
        }
        savedScroll = scroll;
        savedSearch = searchText;
        MobPreview.release();
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
        if (editedOption != null) {
            valueField.updateCursorCounter();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (editedOption != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                stopEditing(false);
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                stopEditing(true);
            } else {
                valueField.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }
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
                scroll -= contentBottom - listTop() - ROW_HEIGHT;
                break;
            case Keyboard.KEY_NEXT:
                scroll += contentBottom - listTop() - ROW_HEIGHT;
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
                if (hoveredOption != null && offParent(hoveredOption) == null) {
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
        if (wheel != 0 && editedOption == null) {
            scroll -= Integer.signum(wheel) * ROW_HEIGHT * 2;
            clampScroll();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (editedOption != null) {
            if (valueField.isMouseOver(mouseX, mouseY)) {
                valueField.mouseClicked(mouseX, mouseY, button);
                return;
            }
            // A click anywhere else keeps what was typed.
            stopEditing(true);
        }
        super.mouseClicked(mouseX, mouseY, button);
        searchField.mouseClicked(mouseX, mouseY, button);
        int listTop = listTop();
        if (mouseY < listTop || mouseY >= contentBottom) {
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
            int y = listTop + row.y - drawnScroll();
            if (mouseY < y || mouseY >= y + row.height) {
                continue;
            }
            if (row.option == null) {
                if (row.section != null && button == 0) {
                    // A section's title opens and closes it.
                    if (!CLOSED_SECTIONS.remove(row.section)) {
                        CLOSED_SECTIONS.add(row.section);
                    }
                    rows = null;
                    clampScroll();
                }
                return;
            }
            Config.Option option = row.option;
            int controlRight = controlX() + CONTROL_WIDTH;
            boolean onControl = Theme.inside(mouseX, mouseY, controlX(), y + 3, controlRight, y + ROW_HEIGHT - 3);
            if (!option.isDefault() && mouseX >= resetX() - 2) {
                if (button == 0) {
                    option.reset();
                }
            } else if (offParent(option) != null) {
                // Waits for the switch it depends on.
                return;
            } else if (option instanceof Config.BoolOption) {
                // The whole row flips a switch, not only the switch itself.
                step(option, true);
            } else if (!onControl) {
                int valueLeft = controlX() - controlLeftWidth(option);
                boolean onValue = mouseX >= valueLeft && mouseX < controlX() - 4 && mouseY >= y + 3;
                if (onValue && mouseY < y + ROW_HEIGHT - 3 && button == 0 && isTypable(option)) {
                    startEditing(option, y + 3);
                }
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

    /** Sliders whose value can be typed in: not the 3D quality, which is shown as a size, not its setting. */
    private static boolean isTypable(Config.Option option) {
        return isSlider(option) && !"isoQuality".equals(option.key);
    }

    /** Puts a text field over the slider's value, with the value in it, selected. */
    private void startEditing(Config.Option option, int y) {
        editedOption = option;
        // The list stays where it is while the field is open, under the row.
        shownScroll = scroll;
        String value;
        if (option instanceof Config.IntOption) {
            value = String.valueOf(((Config.IntOption) option).get());
        } else {
            value = String.format(Locale.ROOT, "%.2f", ((Config.DoubleOption) option).get());
        }
        int fieldWidth = Math.max(controlLeftWidth(option) - 4, 46);
        valueField = new FlatTextField(fontRendererObj, controlX() - 4 - fieldWidth, y, fieldWidth, ROW_HEIGHT - 6);
        valueField.setMaxStringLength(12);
        valueField.setText(value);
        valueField.setFocused(true);
        valueField.setCursorPositionEnd();
        valueField.setSelectionPos(0);
    }

    /** Closes the value field, setting the typed value (kept in the slider's range) when {@code apply}. */
    private void stopEditing(boolean apply) {
        Config.Option option = editedOption;
        editedOption = null;
        if (!apply) {
            return;
        }
        String text = valueField.getText();
        text = text.trim();
        text = text.replace(',', '.');
        try {
            if (option instanceof Config.IntOption) {
                Config.IntOption intOption = (Config.IntOption) option;
                long typed = Math.round(Double.parseDouble(text));
                long clamped = Math.max(intOption.min, Math.min(intOption.max, typed));
                // Onto the slider's steps, counted from its start.
                long steps = Math.round((clamped - intOption.min) / (double) intOption.step);
                intOption.set((int) (intOption.min + steps * intOption.step));
            } else {
                ((Config.DoubleOption) option).set(Double.parseDouble(text));
            }
        } catch (NumberFormatException e) {
            // Not a number: the value stays as it was.
        }
    }

    /** Top and bottom of the scrollbar's thumb, where the list is drawn. */
    private int[] scrollbarThumb() {
        int listTop = listTop();
        int track = contentBottom - listTop;
        int bar = Math.max(16, track * track / (track + maxScroll()));
        int barY = listTop + (int) Math.round((track - bar) * shownScroll / Math.max(1, maxScroll()));
        return new int[] { barY, barY + bar };
    }

    private void dragScrollbar(int mouseY) {
        int[] thumb = scrollbarThumb();
        int free = contentBottom - listTop() - (thumb[1] - thumb[0]);
        if (free > 0) {
            scroll = (int) Math.round((mouseY - scrollbarGrab - listTop()) * (double) maxScroll() / free);
            clampScroll();
            // Follows the mouse without easing.
            shownScroll = scroll;
        }
    }

    /** Moves {@code value} toward {@code target}, faster the higher {@code speed}, the same at any frame rate. */
    private float approach(float value, float target, float speed) {
        return value + (target - value) * (float) (1 - Math.exp(-frameTime * speed));
    }

    /** Animation state of the option: how lit its row is, where its switch's knob is. */
    private float[] animation(Config.Option option) {
        float[] state = animations.get(option);
        if (state == null) {
            state = new float[] { 0f, isOn(option) ? 1f : 0f };
            animations.put(option, state);
        }
        return state;
    }

    private static boolean isOn(Config.Option option) {
        return option instanceof Config.BoolOption && ((Config.BoolOption) option).get();
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        frameTime = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

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
        clampScroll();
        shownScroll = approach((float) shownScroll, scroll, 18f);
        if (Math.abs(shownScroll - scroll) < 0.5) {
            shownScroll = scroll;
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
        drawTabMark();

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

        // The options slide in a little after another tab is opened.
        float opened = Math.min(1f, (System.currentTimeMillis() - tabOpenedAt) / (float) TAB_SLIDE_MS);
        int slide = Math.round((1 - opened) * (1 - opened) * 8);
        if (showsMinimapPreview()) {
            drawMinimapPreview(contentTop + slide);
        }
        if (showsMobsPreview()) {
            drawMobsPreview(contentTop + slide);
        }
        if (showsMarkerPreview()) {
            drawMarkerPreview(contentTop + slide);
        }

        int listTop = listTop();
        Config.Option hovered = null;
        boolean overReset = false;
        hoveredOption = null;
        Theme.clip(contentLeft - 4, listTop, right - 6, contentBottom);
        for (Row row : rows()) {
            int y = listTop + row.y - drawnScroll() + slide;
            if (y + row.height <= listTop || y >= contentBottom) {
                continue;
            }
            if (row.trailPreview) {
                drawTrailPreview(y);
                continue;
            }
            if (row.option == null) {
                drawSectionTitle(row, y, mouseX, mouseY);
                continue;
            }
            Config.Option option = row.option;
            boolean changed = !option.isDefault();
            boolean rowHovered = Theme.inside(mouseX, mouseY, contentLeft - 4, y, right - 6, y + ROW_HEIGHT);
            rowHovered &= mouseY >= listTop && mouseY < contentBottom && !draggingScrollbar;
            float[] state = animation(option);
            state[0] = approach(state[0], rowHovered ? 1f : 0f, 20f);
            state[1] = approach(state[1], isOn(option) ? 1f : 0f, 18f);
            if (state[0] > 0.02f) {
                int light = Math.round(state[0] * 0x20) << 24 | 0xFFFFFF;
                Theme.fill(contentLeft - 4, y, right - 6, y + ROW_HEIGHT, light);
            }
            if (rowHovered) {
                hoveredOption = option;
                hovered = option;
                overReset = changed && mouseX >= resetX() - 2;
            }
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
            boolean locked = offParent(option) != null;
            int nameWidth = controlX() - x - controlLeftWidth(option) - 6;
            String name = Theme.ellipsize(fontRendererObj, I18n.format(option.langKey()), nameWidth);
            drawName(name, x, y + 7, locked ? Theme.TEXT_DISABLED : Theme.TEXT);
            drawControl(option, controlX(), y + 3, mouseX, mouseY, state[1]);
            if (locked) {
                // Waiting for the switch it depends on: the control is dimmed and can't be used.
                int lockedLeft = controlX() - controlLeftWidth(option);
                Theme.fill(lockedLeft, y + 3, controlX() + CONTROL_WIDTH, y + ROW_HEIGHT - 3, 0xB0161A20);
            }
            if (changed) {
                boolean over = overReset && hovered == option;
                Icons.draw(
                    RESET_ICON,
                    resetX() + 1,
                    y + (ROW_HEIGHT - RESET_ICON.length) / 2,
                    over ? Theme.ACCENT : Theme.TEXT_MUTED);
            }
        }
        Theme.unclip();
        if (editedOption != null) {
            valueField.drawTextBox();
        }
        if (rows().isEmpty()) {
            Theme.centered(
                fontRendererObj,
                I18n.format("wayfarmap.settings.no_results"),
                (contentLeft + right - 10) / 2,
                listTop + 30,
                Theme.TEXT_MUTED);
        }
        if (maxScroll() > 0) {
            int[] thumb = scrollbarThumb();
            boolean over = Theme.inside(mouseX, mouseY, right - 7, listTop, right - 1, contentBottom);
            over |= draggingScrollbar;
            Theme.fill(right - 5, listTop, right - 3, contentBottom, Theme.CONTROL);
            Theme.fill(right - 5, thumb[0], right - 3, thumb[1], over ? Theme.ACCENT : Theme.BORDER);
        }

        super.drawScaled(mouseX, mouseY, partialTicks);
        drawChangedMarks();
        drawDescription(hovered, overReset);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A bar beside the open tab, sliding to the one chosen. */
    private void drawTabMark() {
        if (searching()) {
            return;
        }
        float target = top + 26 + selectedCategory * 20;
        tabMarkY = tabMarkY < 0 ? target : approach(tabMarkY, target, 16f);
        int y = Math.round(tabMarkY);
        Theme.fill(left + 2, y + 3, left + 4, y + 15, Theme.ACCENT);
    }

    /** A section's title: an arrow to open or close it, and how many options a closed one hides. */
    private void drawSectionTitle(Row row, int y, int mouseX, int mouseY) {
        int x = contentLeft;
        boolean hovered = false;
        if (row.section != null) {
            hovered = Theme.inside(mouseX, mouseY, contentLeft - 4, y, right - 6, y + row.height);
            String[] arrow = row.hidden > 0 ? Icons.SECTION_CLOSED : Icons.SECTION_OPEN;
            Icons.draw(arrow, x, y + 7 + (5 - arrow.length) / 2, hovered ? Theme.TEXT : Theme.ACCENT_DIM);
            x += 9;
        }
        String title = row.title;
        if (row.hidden > 0) {
            title += " (" + row.hidden + ")";
        }
        Theme.text(fontRendererObj, title, x, y + 6, hovered ? Theme.TEXT : Theme.ACCENT);
        int lineX = x + fontRendererObj.getStringWidth(title) + 6;
        Theme.fill(lineX, y + 10, right - 10, y + 11, Theme.BORDER);
    }

    /** The option's name, with what was searched for marked in it. */
    private void drawName(String name, int x, int y, int color) {
        String query = query();
        if (!query.isEmpty()) {
            String lower = name.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(query);
            if (at >= 0 && at + query.length() <= name.length()) {
                int from = x + fontRendererObj.getStringWidth(name.substring(0, at));
                int to = from + fontRendererObj.getStringWidth(name.substring(at, at + query.length()));
                Theme.fill(from - 1, y - 1, to, y + 9, Theme.ACCENT_DIM);
            }
        }
        Theme.text(fontRendererObj, name, x, y, color);
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

    /**
     * Under the trail's options: a walk on a made-up map with the trail drawn the way it is set, starting over every
     * few seconds; dimmed with a note while the trail is off.
     */
    private void drawTrailPreview(int y) {
        int x0 = contentLeft, x1 = right - 10, y0 = y + 2, y1 = y + TRAIL_PREVIEW_HEIGHT - 4;
        drawPreviewGround(x0, y0, x1, y1);
        PlayerTrail.drawPreview(x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.marker_preview"), x0 + 5, y0 + 4, Theme.ACCENT);
        if (!Config.playerTrail) {
            Theme.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xC0101418);
            Theme.centered(
                fontRendererObj,
                I18n.format("wayfarmap.settings.trail_off"),
                (x0 + x1) / 2,
                (y0 + y1) / 2 - 4,
                Theme.TEXT_MUTED);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Dark green ground with a hint of a pattern, so a preview reads as a map; with its outline. */
    private static void drawPreviewGround(int x0, int y0, int x1, int y1) {
        Theme.fill(x0, y0, x1, y1, 0xFF13211A);
        for (int gx = x0; gx < x1; gx += 8) {
            for (int gy = y0; gy < y1; gy += 8) {
                if (((gx - x0) * 7 + (gy - y0) * 13) % 5 == 0) {
                    Theme.fill(gx, gy, Math.min(gx + 8, x1), Math.min(gy + 8, y1), 0xFF172A1F);
                }
            }
        }
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
    }

    /**
     * Over the mobs' options: two mobs of each kind on a made-up map, drawn the way the maps draw them (icons or dots,
     * frame, size, arrows, pets' names); the kinds that are hidden are dimmed.
     */
    private void drawMobsPreview(int y0) {
        int x0 = contentLeft, x1 = right - 10, y1 = y0 + MOBS_PREVIEW_HEIGHT - 6;
        drawPreviewGround(x0, y0, x1, y1);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.marker_preview"), x0 + 6, y0 + 6, Theme.ACCENT);
        Theme.clip(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        MobPreview.draw(fontRendererObj, x0 + 1, y0 + 20, x1 - 1, y1 - 2);
        Theme.unclip();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Over the player icon's options: the icon as on the world map, on dark and on light ground. */
    private void drawMarkerPreview(int y0) {
        int x0 = contentLeft, x1 = right - 10, y1 = y0 + MARKER_PREVIEW_HEIGHT - 6;
        int middle = (x0 + x1) / 2;
        Theme.fill(x0, y0, middle, y1, 0xFF0C0E11);
        Theme.fill(middle, y0, x1, y1, 0xFFC9D3B4);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.marker_preview"), x0 + 6, y0 + 6, Theme.ACCENT);
        // Turning slowly, so its direction shows. The world map's size (5 at 100%).
        float yaw = (System.currentTimeMillis() % 8000L) * 360f / 8000f;
        double cy = (y0 + 12 + y1) / 2.0;
        MapDrawer.drawPlayerArrow((x0 + middle) / 2.0, cy, yaw, 5f);
        MapDrawer.drawPlayerArrow((middle + x1) / 2.0, cy, yaw, 5f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Over the minimap's options: a made-up piece of land drawn the way the minimap would show it (shape, frame,
     * zoom, turning, compass, text under it), and where on the screen it is.
     */
    private void drawMinimapPreview(int y0) {
        int x0 = contentLeft, x1 = right - 10, y1 = y0 + PREVIEW_HEIGHT - 6;
        Theme.fill(x0, y0, x1, y1, 0xFF0F1216);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.marker_preview"), x0 + 6, y0 + 6, Theme.ACCENT);

        // Smaller than the real one, but growing with it.
        int size = 28 + (Config.minimapSize - 48) * 28 / 208;
        List<String> lines = new ArrayList<>();
        if (Config.minimapShowCoordinates) {
            lines.add("128, 64, -256");
        }
        if (Config.minimapShowBiome) {
            lines.add("Plains");
        }
        double textScale = Config.minimapTextScale;
        int lineHeight = Math.max(1, (int) Math.round(10 * textScale));
        int blockHeight = size + (lines.isEmpty() ? 0 : Config.minimapTextGap + lines.size() * lineHeight - 3);
        int mapX = x0 + (x1 - x0) * 2 / 5 - size / 2;
        int mapY = y0 + (y1 - y0 - blockHeight) / 2;
        drawPreviewMap(mapX, mapY, size);
        int textY = mapY + size + Config.minimapTextGap;
        for (String line : lines) {
            // At the size and with the room from the map the minimap has.
            GL11.glPushMatrix();
            GL11.glTranslated(mapX + size / 2.0 - fontRendererObj.getStringWidth(line) * textScale / 2, textY, 0);
            GL11.glScaled(textScale, textScale, 1);
            fontRendererObj.drawStringWithShadow(line, 0, 0, 0xFFFFFF);
            GL11.glPopMatrix();
            textY += lineHeight;
        }
        drawScreenThumbnail(x1 - 8 - 96, y0 + 17, 96, 54);

        if (!Config.minimapEnabled) {
            Theme.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xC00F1216);
            Theme.centered(
                fontRendererObj,
                I18n.format("wayfarmap.settings.minimap_off"),
                (x0 + x1) / 2,
                (y0 + y1) / 2 - 4,
                Theme.TEXT_MUTED);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** The preview's minimap, its top left at (x, y). */
    private void drawPreviewMap(int x, int y, int size) {
        boolean round = Config.minimapShape == Config.SHAPE_ROUND;
        double half = size / 2.0;
        double centerX = x + half, centerY = y + half;
        // The frame as the minimap draws it: its line right around the map, as see-through and thick as set.
        float opacity = Config.minimapFrameOpacity / 100f;
        int frameColor = Math.round(255 * opacity) << 24 | Config.minimapFrameColor;
        int line = Config.minimapFrameWidth;
        if (round) {
            if (Config.minimapFrame) {
                fillRing(centerX, centerY, half, half + line, frameColor);
            }
        } else if (Config.minimapFrame) {
            frameBand(x - line, y - line, x + size + line, y + size + line, line, frameColor);
        }

        // The player turns round slowly, so the minimap's turning shows.
        float yaw = (System.currentTimeMillis() % 12000L) * 360f / 12000f;
        float rotation = Config.minimapRotate ? -180f - yaw : 0f;
        int zoom = Math.max(0, Math.min(Config.MINIMAP_ZOOMS.length - 1, Config.minimapZoom));
        // As many blocks across as the real minimap shows.
        double blocksPerPixel = Config.minimapSize / Config.MINIMAP_ZOOMS[zoom] / size;
        double angle = Math.toRadians(-rotation), cos = Math.cos(angle), sin = Math.sin(angle);
        for (int row = 0; row < size; row += PREVIEW_CELL) {
            int rowBottom = Math.min(size, row + PREVIEW_CELL);
            double dy = row + PREVIEW_CELL / 2.0 - half;
            int from = 0, to = size;
            if (round) {
                double reach = Math.sqrt(Math.max(0, half * half - dy * dy));
                from = (int) Math.round(half - reach);
                to = (int) Math.round(half + reach);
            }
            // Cells of one color next to each other are drawn as one rectangle.
            int runStart = from, runColor = 0;
            for (int col = from; col < to; col += PREVIEW_CELL) {
                double dx = col + PREVIEW_CELL / 2.0 - half;
                double worldX = (dx * cos - dy * sin) * blocksPerPixel;
                double worldZ = (dx * sin + dy * cos) * blocksPerPixel;
                int color = groundColor(worldX + 40, worldZ - 25);
                if (col > from && color != runColor) {
                    Theme.fill(x + runStart, y + row, x + col, y + rowBottom, runColor);
                    runStart = col;
                }
                runColor = color;
            }
            if (to > from) {
                Theme.fill(x + runStart, y + row, x + to, y + rowBottom, runColor);
            }
        }

        MapDrawer.drawPlayerArrow(centerX, centerY, yaw + rotation, 3f);
        if (Config.minimapCompass) {
            double letterScale = Config.minimapCompassScale;
            double edge = half - 5 * letterScale;
            for (int i = 0; i < COMPASS_LETTERS.length; i++) {
                String letter = COMPASS_LETTERS[i];
                // North up, then clockwise, turning with the map.
                double a = Math.toRadians(rotation + i * 90);
                double ux = Math.sin(a), uy = -Math.cos(a);
                double reach = round ? edge : edge / Math.max(Math.abs(ux), Math.abs(uy));
                double letterX = centerX + ux * reach
                    - (fontRendererObj.getStringWidth(letter) / 2.0 - 1) * letterScale;
                double letterY = centerY + uy * reach - 3 * letterScale;
                GL11.glPushMatrix();
                GL11.glTranslated(letterX, letterY, 0);
                GL11.glScaled(letterScale, letterScale, 1);
                fontRendererObj.drawStringWithShadow(letter, 0, 0, 0xFFFFFF);
                GL11.glPopMatrix();
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A small screen with the minimap where it is on the real one. */
    private void drawScreenThumbnail(int x, int y, int w, int h) {
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings.on_screen"), x, y - 11, Theme.TEXT_MUTED);
        Theme.fill(x, y, x + w, y + h, 0xFF1B2430);
        Theme.outline(x, y, x + w, y + h, Theme.BORDER);
        // The hotbar, so it reads as the game's screen.
        Theme.fill(x + w / 2 - 18, y + h - 5, x + w / 2 + 18, y + h - 2, Theme.BORDER);
        ScaledResolution hud = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int screenWidth = Math.max(1, hud.getScaledWidth()), screenHeight = Math.max(1, hud.getScaledHeight());
        int mapLeft = x + MinimapRenderer.left(screenWidth) * w / screenWidth;
        int mapTop = y + MinimapRenderer.top(screenHeight) * h / screenHeight;
        int mapWidth = Math.max(3, Config.minimapSize * w / screenWidth);
        int mapHeight = Math.max(3, MinimapRenderer.boxHeight() * h / screenHeight);
        Theme.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, Theme.ACCENT_DIM);
        Theme.outline(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, Theme.ACCENT);
    }

    /** A band {@code thickness} wide inside the rectangle's edges, in four pieces that don't overlap. */
    private static void frameBand(int x0, int y0, int x1, int y1, int thickness, int color) {
        Theme.fill(x0, y0, x1, y0 + thickness, color);
        Theme.fill(x0, y1 - thickness, x1, y1, color);
        Theme.fill(x0, y0 + thickness, x0 + thickness, y1 - thickness, color);
        Theme.fill(x1 - thickness, y0 + thickness, x1, y1 - thickness, color);
    }

    /** A ring between two radii, drawn as rectangles per row that don't overlap. */
    private static void fillRing(double centerX, double centerY, double inner, double outer, int color) {
        int top = (int) Math.floor(centerY - outer), bottom = (int) Math.ceil(centerY + outer);
        for (int y = top; y < bottom; y++) {
            double dy = y + 0.5 - centerY;
            double reach = Math.sqrt(Math.max(0, outer * outer - dy * dy));
            int from = (int) Math.round(centerX - reach), to = (int) Math.round(centerX + reach);
            if (Math.abs(dy) < inner) {
                double hole = Math.sqrt(inner * inner - dy * dy);
                int holeFrom = (int) Math.round(centerX - hole), holeTo = (int) Math.round(centerX + hole);
                Theme.fill(from, y, holeFrom, y + 1, color);
                Theme.fill(holeTo, y, to, y + 1, color);
            } else if (to > from) {
                Theme.fill(from, y, to, y + 1, color);
            }
        }
    }

    /** A filled circle, drawn as one rectangle per row. */
    private static void fillDisc(double centerX, double centerY, double radius, int color) {
        int top = (int) Math.floor(centerY - radius), bottom = (int) Math.ceil(centerY + radius);
        for (int y = top; y < bottom; y++) {
            double dy = y + 0.5 - centerY;
            double reach = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int from = (int) Math.round(centerX - reach), to = (int) Math.round(centerX + reach);
            if (to > from) {
                Theme.fill(from, y, to, y + 1, color);
            }
        }
    }

    /** Made-up land for the preview: water, sand, grass and forest from smooth noise. */
    private static int groundColor(double x, double z) {
        double height = noise(x / 40, z / 40) * 0.75 + noise(x / 12 + 100, z / 12) * 0.25;
        if (height < 0.36) {
            return 0xFF2F5BA8;
        }
        if (height < 0.42) {
            return 0xFF3F76C8;
        }
        if (height < 0.46) {
            return 0xFFD8CC8E;
        }
        return height < 0.66 ? 0xFF5E9E3A : 0xFF3E7A2A;
    }

    /** Value noise in 0..1, smooth between whole coordinates. */
    private static double noise(double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
        double upper = a + (b - a) * fx, lower = c + (d - c) * fx;
        return upper + (lower - upper) * fz;
    }

    private static double hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /**
     * The panel under the options: the name and full description of the option under the mouse, with its default
     * value and the switch it waits for. It grows up over the list when the description is long. Over the reset
     * button, what it resets to; with no option under the mouse, a hint.
     */
    private void drawDescription(Config.Option option, boolean reset) {
        int boxLeft = contentLeft - 4, boxRight = right - 6, boxBottom = bottom - 8;
        int textWidth = boxRight - boxLeft - 12;
        String title = null;
        List<String> lines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (option == null) {
            addWrapped(lines, I18n.format("wayfarmap.settings.hint"), textWidth);
        } else {
            title = I18n.format(option.langKey());
            String defaultValue = defaultText(option);
            if (reset) {
                String key = defaultValue == null ? "wayfarmap.settings.reset_option" : "wayfarmap.settings.reset_to";
                notes.add(I18n.format(key, defaultValue));
            } else {
                addWrapped(lines, I18n.format(option.langKey() + ".desc"), textWidth);
                if (defaultValue != null) {
                    notes.add(I18n.format("wayfarmap.settings.default", defaultValue));
                }
                if (isTypable(option) && offParent(option) == null) {
                    notes.add(I18n.format("wayfarmap.settings.type_value"));
                }
                Config.BoolOption waitsFor = offParent(option);
                if (waitsFor != null) {
                    notes.add(I18n.format("wayfarmap.settings.requires", I18n.format(waitsFor.langKey())));
                }
            }
        }
        int needed = 8 + lines.size() * 10;
        if (title != null) {
            needed += 11;
        }
        if (!notes.isEmpty()) {
            needed += 3 + notes.size() * 10;
        }
        int boxTop = Math.max(top + 26, boxBottom - Math.max(DESCRIPTION_HEIGHT, needed));
        Theme.fill(boxLeft, boxTop, boxRight, boxBottom, 0xFF12161B);
        Theme.outline(boxLeft, boxTop, boxRight, boxBottom, option != null ? Theme.ACCENT_DIM : Theme.BORDER);
        int x = boxLeft + 6, y = boxTop + 5;
        if (title != null) {
            Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, title, textWidth), x, y, Theme.ACCENT);
            y += 11;
        }
        for (String line : lines) {
            Theme.text(fontRendererObj, line, x, y, option != null ? Theme.TEXT : Theme.TEXT_MUTED);
            y += 10;
        }
        if (!notes.isEmpty()) {
            if (title != null || !lines.isEmpty()) {
                Theme.fill(x, y, boxRight - 6, y + 1, Theme.BORDER);
            }
            y += 3;
            for (String note : notes) {
                Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, note, textWidth), x, y, Theme.TEXT_MUTED);
                y += 10;
            }
        }
    }

    private void addWrapped(List<String> lines, String text, int width) {
        for (Object line : fontRendererObj.listFormattedStringToWidth(text, width)) {
            lines.add(String.valueOf(line));
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

    /** The option's control at (x, y); {@code knob} is where a switch's knob is drawn, 0 off to 1 on. */
    private void drawControl(Config.Option option, int x, int y, int mouseX, int mouseY, float knob) {
        int h = ROW_HEIGHT - 6;
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + CONTROL_WIDTH, y + h);
        if (option instanceof Config.BoolOption) {
            boolean on = ((Config.BoolOption) option).get();
            int switchX = x + CONTROL_WIDTH - 24;
            int switchY = y + (h - 12) / 2;
            boolean rowHovered = hoveredOption == option;
            Theme.fill(switchX, switchY, switchX + 24, switchY + 12, Theme.blend(Theme.CONTROL, Theme.ACCENT, knob));
            Theme.outline(switchX, switchY, switchX + 24, switchY + 12, rowHovered ? Theme.ACCENT : Theme.BORDER);
            int knobX = switchX + 2 + Math.round(12 * knob);
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
            int knobX = x + 3 + (int) Math.round(t * (CONTROL_WIDTH - 6));
            Theme.fill(x + 3, trackY, knobX, trackY + 3, active ? Theme.ACCENT : Theme.ACCENT_DIM);
            Theme.fill(knobX - 3, y + 2, knobX + 3, y + h - 2, active ? Theme.ACCENT : Theme.TEXT);
            Theme.outline(knobX - 3, y + 2, knobX + 3, y + h - 2, Theme.BORDER);
            // The value in a small box left of the slider.
            int boxX = x - controlLeftWidth(option);
            // The value can be clicked to type it in: lit under the mouse.
            boolean valueHovered = isTypable(option) && Theme.inside(mouseX, mouseY, boxX, y + 1, x - 4, y + h - 1);
            active |= valueHovered;
            Theme.fill(boxX, y + 1, x - 4, y + h - 1, 0xFF0F1216);
            int boxBorder = valueHovered ? Theme.ACCENT : active ? Theme.ACCENT_DIM : Theme.BORDER;
            Theme.outline(boxX, y + 1, x - 4, y + h - 1, boxBorder);
            int valueColor = active ? Theme.TEXT : Theme.TEXT_MUTED;
            Theme.text(fontRendererObj, value, boxX + 4, y + (h - 8) / 2, valueColor);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
