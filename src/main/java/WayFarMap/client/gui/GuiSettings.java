package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.BlockColors;

/** All mod settings: categories in a sidebar, the options of the selected one on the right. */
public class GuiSettings extends ScaledScreen {

    private static final int SIDEBAR_WIDTH = 112;
    private static final int ROW_HEIGHT = 22;
    /** Height of a section title between the options. */
    private static final int HEADER_HEIGHT = 18;
    /** How far options that depend on a switch are moved in under it. */
    private static final int INDENT = 10;
    private static final int CONTROL_WIDTH = 104;
    private static final int ID_RESET = 100, ID_DONE = 101, ID_RESET_ALL = 102, ID_RESET_TAB = 103,
        ID_RESET_CANCEL = 104;

    /** Last opened category, kept while the game runs. */
    private static int selectedCategory;

    private final GuiScreen parent;
    private final boolean textureColorsBefore = Config.useTextureColors;

    private int left, top, right, bottom;
    private int contentLeft, contentTop, contentBottom;
    private int scroll;
    private Config.Option draggingSlider;
    /** The reset button was pressed: it is replaced by "reset all", "reset this tab" and "cancel". */
    private boolean confirmingReset;

    /** A line of the options list: a section title, or an option. */
    private static final class Row {

        /** The option, or null for a section title. */
        final Config.Option option;
        final String group;
        /** Top, from the top of the list (not scrolled). */
        final int y;
        final int height;

        Row(Config.Option option, String group, int y, int height) {
            this.option = option;
            this.group = group;
            this.y = y;
            this.height = height;
        }
    }

    public GuiSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 500);
        int panelHeight = Math.min(height - 16, 320);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 10;
        contentTop = top + 28;
        contentBottom = bottom - 8;

        buttonList.clear();
        for (int i = 0; i < Config.CATEGORIES.size(); i++) {
            FlatButton button = new FlatButton(
                i,
                left + 6,
                top + 26 + i * 22,
                SIDEBAR_WIDTH - 12,
                18,
                I18n.format("wayfarmap.settings." + Config.CATEGORIES.get(i)));
            button.active = i == selectedCategory;
            buttonList.add(button);
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
            new FlatButton(
                ID_RESET_CANCEL,
                left + 6,
                bottom - 48,
                SIDEBAR_WIDTH - 12,
                18,
                I18n.format("gui.cancel")));
        showResetButtons();
        clampScroll();
    }

    /**
     * Either the reset button, or the three buttons asking what to reset. Applied when drawing, not right on the
     * click: the click goes on to the buttons after the pressed one, and "cancel" sits where "reset" was.
     */
    private void showResetButtons() {
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id == ID_RESET) {
                button.visible = !confirmingReset;
            } else if (button.id == ID_RESET_ALL || button.id == ID_RESET_TAB || button.id == ID_RESET_CANCEL) {
                button.visible = confirmingReset;
            }
        }
    }

    private List<Config.Option> options() {
        return Config.getOptions(Config.CATEGORIES.get(selectedCategory));
    }

    /** The options of the category with a title before each section. */
    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        String group = null;
        int y = 0;
        for (Config.Option option : options()) {
            if (!option.group.isEmpty() && !option.group.equals(group)) {
                // A little room above every title but the first.
                y += rows.isEmpty() ? 0 : 4;
                rows.add(new Row(null, option.group, y, HEADER_HEIGHT));
                y += HEADER_HEIGHT;
            }
            group = option.group;
            rows.add(new Row(option, group, y, ROW_HEIGHT));
            y += ROW_HEIGHT;
        }
        return rows;
    }

    private int maxScroll() {
        List<Row> rows = rows();
        int height = rows.isEmpty() ? 0 : rows.get(rows.size() - 1).y + rows.get(rows.size() - 1).height;
        return Math.max(0, height - (contentBottom - contentTop));
    }

    /** How far the option's name is moved in: under the switch it depends on, in the same section. */
    private static int indent(Config.Option option) {
        return option.parent != null && option.parent.group.equals(option.group) ? INDENT : 0;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int controlX() {
        return right - 10 - CONTROL_WIDTH;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < Config.CATEGORIES.size()) {
            selectedCategory = button.id;
            scroll = 0;
            confirmingReset = false;
            for (Object o : buttonList) {
                GuiButton other = (GuiButton) o;
                if (other.id < Config.CATEGORIES.size()) {
                    ((FlatButton) other).active = other.id == selectedCategory;
                }
            }
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
        Config.save();
        if (Config.useTextureColors != textureColorsBefore) {
            // Chunks are rescanned over time and pick up the new colors.
            BlockColors.clearCache();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE && confirmingReset) {
            confirmingReset = false;
        } else if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
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
        if (mouseY < contentTop || mouseY >= contentBottom || mouseX < controlX() || mouseX >= right - 10) {
            return;
        }
        for (Row row : rows()) {
            int y = contentTop + row.y - scroll;
            if (row.option == null || mouseY < y + 3 || mouseY >= y + ROW_HEIGHT - 3) {
                continue;
            }
            Config.Option option = row.option;
            if (option instanceof Config.BoolOption) {
                Config.BoolOption bool = (Config.BoolOption) option;
                bool.set(!bool.get());
            } else if (option instanceof Config.ChoiceOption) {
                Config.ChoiceOption choice = (Config.ChoiceOption) option;
                // Left half goes back, right half forward; right click also goes back.
                boolean back = button == 1 || mouseX < controlX() + CONTROL_WIDTH / 2;
                int count = choice.values.length;
                choice.set((choice.get() + (back ? count - 1 : 1)) % count);
            } else if (option instanceof Config.ColorOption) {
                if (button == 0) {
                    Config.ColorOption color = (Config.ColorOption) option;
                    mc.displayGuiScreen(new GuiColorPicker(this, color.get(), color::set));
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

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        showResetButtons();
        if (draggingSlider != null) {
            if (Mouse.isButtonDown(0)) {
                updateSlider(mouseX);
            } else {
                draggingSlider = null;
            }
        }

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.fill(left + 1, top + 1, left + SIDEBAR_WIDTH, bottom - 1, Theme.PANEL_ALT);
        Theme.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, bottom - 1, Theme.BORDER);
        Theme.text(fontRendererObj, "Wayfarer's Map", left + 8, top + 9, Theme.ACCENT);

        String category = Config.CATEGORIES.get(selectedCategory);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.settings." + category), contentLeft, top + 9, Theme.TEXT);
        Theme.fill(contentLeft, top + 20, right - 10, top + 21, Theme.BORDER);

        Config.Option hovered = null;
        int hoveredY = 0;
        for (Row row : rows()) {
            int y = contentTop + row.y - scroll;
            if (y + row.height <= contentTop || y >= contentBottom) {
                continue;
            }
            boolean whole = y >= contentTop && y + row.height <= contentBottom;
            if (row.option == null) {
                if (whole) {
                    String title = I18n.format("wayfarmap.settings.group." + row.group);
                    Theme.text(fontRendererObj, title, contentLeft, y + 6, Theme.ACCENT);
                    int lineX = contentLeft + fontRendererObj.getStringWidth(title) + 6;
                    Theme.fill(lineX, y + 10, right - 10, y + 11, Theme.BORDER);
                }
                continue;
            }
            Config.Option option = row.option;
            int visibleTop = Math.max(y, contentTop);
            int visibleBottom = Math.min(y + ROW_HEIGHT, contentBottom);
            if (Theme.inside(mouseX, mouseY, contentLeft - 4, visibleTop, right - 6, visibleBottom)) {
                Theme.fill(contentLeft - 4, visibleTop, right - 6, visibleBottom, Theme.ROW_HOVER);
                // The description only over the name: over the control it would hide the rows being changed.
                if (mouseX < controlX() - 4) {
                    hovered = option;
                    hoveredY = y;
                }
            }
            if (whole) {
                int x = contentLeft + indent(option);
                if (x > contentLeft) {
                    // A short line from the switch this one depends on.
                    Theme.fill(contentLeft + 2, y + 3, contentLeft + 3, y + 12, Theme.BORDER);
                    Theme.fill(contentLeft + 2, y + 11, x - 2, y + 12, Theme.BORDER);
                }
                boolean off = option.parent != null && !option.parent.get();
                String name = Theme.ellipsize(fontRendererObj, I18n.format(option.langKey()), controlX() - x - 48);
                Theme.text(fontRendererObj, name, x, y + 7, off ? Theme.TEXT_MUTED : Theme.TEXT);
                drawControl(option, controlX(), y + 3, mouseX, mouseY);
            }
        }
        if (maxScroll() > 0) {
            int track = contentBottom - contentTop;
            int bar = Math.max(12, track * track / (track + maxScroll()));
            int barY = contentTop + (track - bar) * scroll / maxScroll();
            Theme.fill(right - 5, barY, right - 3, barY + bar, Theme.BORDER);
        }
        if (hovered != null) {
            drawDescription(hovered, hoveredY);
        }

        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    /**
     * The option's description in full, in a box under its row (above it when there is no room below), so long
     * descriptions are never cut off.
     */
    private void drawDescription(Config.Option option, int rowY) {
        String text = I18n.format(option.langKey() + ".desc");
        int boxLeft = contentLeft - 4, boxRight = right - 6;
        List<?> lines = fontRendererObj.listFormattedStringToWidth(text, boxRight - boxLeft - 10);
        if (lines.isEmpty()) {
            return;
        }
        int boxHeight = lines.size() * 10 + 8;
        int boxTop = rowY + ROW_HEIGHT;
        if (boxTop + boxHeight > bottom - 4) {
            boxTop = Math.max(top + 4, rowY - boxHeight);
        }
        Theme.fill(boxLeft, boxTop, boxRight, boxTop + boxHeight, 0xFF12161B);
        Theme.outline(boxLeft, boxTop, boxRight, boxTop + boxHeight, Theme.ACCENT_DIM);
        for (int i = 0; i < lines.size(); i++) {
            Theme.text(fontRendererObj, String.valueOf(lines.get(i)), boxLeft + 5, boxTop + 5 + i * 10, Theme.TEXT);
        }
    }

    private void drawControl(Config.Option option, int x, int y, int mouseX, int mouseY) {
        int h = ROW_HEIGHT - 6;
        boolean hovered = Theme.inside(mouseX, mouseY, x, y, x + CONTROL_WIDTH, y + h);
        if (option instanceof Config.BoolOption) {
            boolean on = ((Config.BoolOption) option).get();
            int switchX = x + CONTROL_WIDTH - 24;
            int switchY = y + (h - 12) / 2;
            Theme.fill(switchX, switchY, switchX + 24, switchY + 12, on ? Theme.ACCENT : Theme.CONTROL);
            Theme.outline(switchX, switchY, switchX + 24, switchY + 12, hovered ? Theme.ACCENT : Theme.BORDER);
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
            Theme.text(fontRendererObj, "<", x + 4, y + (h - 8) / 2, Theme.TEXT_MUTED);
            Theme.text(fontRendererObj, ">", x + CONTROL_WIDTH - 9, y + (h - 8) / 2, Theme.TEXT_MUTED);
            String value = I18n.format(option.langKey() + "." + choice.values[choice.get()]);
            Theme.centered(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, value, CONTROL_WIDTH - 24),
                x + CONTROL_WIDTH / 2,
                y + (h - 8) / 2,
                Theme.TEXT);
        } else if (option instanceof Config.ColorOption) {
            int rgb = ((Config.ColorOption) option).get();
            Theme.fill(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(x, y, x + CONTROL_WIDTH, y + h, hovered ? Theme.ACCENT : Theme.BORDER);
            // A swatch of the color, then its code.
            Theme.fill(x + 3, y + 3, x + 3 + 2 * (h - 6), y + h - 3, 0xFF000000 | rgb);
            Theme.outline(x + 3, y + 3, x + 3 + 2 * (h - 6), y + h - 3, Theme.BORDER);
            Theme.text(
                fontRendererObj,
                Config.ColorOption.hex(rgb),
                x + 8 + 2 * (h - 6),
                y + (h - 8) / 2,
                Theme.TEXT);
        } else {
            double t;
            String value;
            if (option instanceof Config.IntOption) {
                Config.IntOption intOption = (Config.IntOption) option;
                t = (intOption.get() - intOption.min) / (double) (intOption.max - intOption.min);
                value = intOption.get() == 0 && "maxDistance".equals(option.key)
                    ? I18n.format("wayfarmap.settings.unlimited")
                    : "isoQuality".equals(option.key) ? I18n.format("wayfarmap.iso.quality_value", 8 << intOption.get())
                        : String.valueOf(intOption.get());
            } else {
                Config.DoubleOption doubleOption = (Config.DoubleOption) option;
                t = (doubleOption.get() - doubleOption.min) / (doubleOption.max - doubleOption.min);
                value = String.format(Locale.ROOT, "%.2f", doubleOption.get());
            }
            boolean active = hovered || draggingSlider == option;
            int trackY = y + h / 2 - 1;
            Theme.fill(x + 3, trackY, x + CONTROL_WIDTH - 3, trackY + 3, Theme.CONTROL);
            int knob = x + 3 + (int) Math.round(t * (CONTROL_WIDTH - 6));
            Theme.fill(x + 3, trackY, knob, trackY + 3, Theme.ACCENT_DIM);
            Theme.fill(knob - 3, y + 2, knob + 3, y + h - 2, active ? Theme.ACCENT : Theme.TEXT);
            Theme.text(
                fontRendererObj,
                value,
                x - 8 - fontRendererObj.getStringWidth(value),
                y + (h - 8) / 2,
                active ? Theme.TEXT : Theme.TEXT_MUTED);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
