package WayFarMap.client.gui;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.map.BlockColors;

/** All mod settings: categories in a sidebar, the options of the selected one on the right. */
public class GuiSettings extends GuiScreen {

    private static final int SIDEBAR_WIDTH = 112;
    private static final int ROW_HEIGHT = 22;
    private static final int CONTROL_WIDTH = 104;
    private static final int ID_RESET = 100, ID_DONE = 101;

    /** Last opened category, kept while the game runs. */
    private static int selectedCategory;

    private final GuiScreen parent;
    private final boolean textureColorsBefore = Config.useTextureColors;

    private int left, top, right, bottom;
    private int contentLeft, contentTop, contentBottom;
    private int scroll;
    private Config.Option draggingSlider;

    public GuiSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 460);
        int panelHeight = Math.min(height - 16, 300);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 10;
        contentTop = top + 28;
        contentBottom = bottom - 34;

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
            new FlatButton(ID_RESET, left + 6, bottom - 48, SIDEBAR_WIDTH - 12, 18, I18n.format("wayfarmap.settings.reset")));
        buttonList.add(
            new FlatButton(ID_DONE, left + 6, bottom - 26, SIDEBAR_WIDTH - 12, 18, I18n.format("gui.done")));
        clampScroll();
    }

    private List<Config.Option> options() {
        return Config.getOptions(Config.CATEGORIES.get(selectedCategory));
    }

    private int maxScroll() {
        return Math.max(0, options().size() * ROW_HEIGHT - (contentBottom - contentTop));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int rowY(int index) {
        return contentTop + index * ROW_HEIGHT - scroll;
    }

    private int controlX() {
        return right - 10 - CONTROL_WIDTH;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < Config.CATEGORIES.size()) {
            selectedCategory = button.id;
            scroll = 0;
            for (Object o : buttonList) {
                GuiButton other = (GuiButton) o;
                if (other.id < Config.CATEGORIES.size()) {
                    ((FlatButton) other).active = other.id == selectedCategory;
                }
            }
        } else if (button.id == ID_RESET) {
            for (Config.Option option : options()) {
                option.reset();
            }
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
        if (keyCode == Keyboard.KEY_ESCAPE) {
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
        List<Config.Option> options = options();
        for (int i = 0; i < options.size(); i++) {
            int y = rowY(i);
            if (mouseY < y + 3 || mouseY >= y + ROW_HEIGHT - 3) {
                continue;
            }
            Config.Option option = options.get(i);
            if (option instanceof Config.BoolOption) {
                Config.BoolOption bool = (Config.BoolOption) option;
                bool.set(!bool.get());
            } else if (option instanceof Config.ChoiceOption) {
                Config.ChoiceOption choice = (Config.ChoiceOption) option;
                // Left half goes back, right half forward; right click also goes back.
                boolean back = button == 1 || mouseX < controlX() + CONTROL_WIDTH / 2;
                int count = choice.values.length;
                choice.set((choice.get() + (back ? count - 1 : 1)) % count);
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
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
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

        List<Config.Option> options = options();
        Config.Option hovered = null;
        for (int i = 0; i < options.size(); i++) {
            int y = rowY(i);
            if (y + ROW_HEIGHT <= contentTop || y >= contentBottom) {
                continue;
            }
            Config.Option option = options.get(i);
            int visibleTop = Math.max(y, contentTop);
            int visibleBottom = Math.min(y + ROW_HEIGHT, contentBottom);
            if (Theme.inside(mouseX, mouseY, contentLeft - 4, visibleTop, right - 6, visibleBottom)) {
                hovered = option;
                Theme.fill(contentLeft - 4, visibleTop, right - 6, visibleBottom, Theme.ROW_HOVER);
            }
            if (y >= contentTop && y + ROW_HEIGHT <= contentBottom) {
                String name = Theme.ellipsize(fontRendererObj, I18n.format(option.langKey()), controlX() - contentLeft - 48);
                Theme.text(fontRendererObj, name, contentLeft, y + 7, Theme.TEXT);
                drawControl(option, controlX(), y + 3, mouseX, mouseY);
            }
        }
        if (maxScroll() > 0) {
            int track = contentBottom - contentTop;
            int bar = Math.max(12, track * track / (track + maxScroll()));
            int barY = contentTop + (track - bar) * scroll / maxScroll();
            Theme.fill(right - 5, barY, right - 3, barY + bar, Theme.BORDER);
        }

        // Description of the option under the mouse, like a footer.
        Theme.fill(contentLeft, contentBottom + 2, right - 10, contentBottom + 3, Theme.BORDER);
        if (hovered != null) {
            List<?> lines = fontRendererObj.listFormattedStringToWidth(
                I18n.format(hovered.langKey() + ".desc"),
                right - 10 - contentLeft);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                Theme.text(
                    fontRendererObj,
                    String.valueOf(lines.get(i)),
                    contentLeft,
                    contentBottom + 8 + i * 10,
                    Theme.TEXT_MUTED);
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
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
        } else {
            double t;
            String value;
            if (option instanceof Config.IntOption) {
                Config.IntOption intOption = (Config.IntOption) option;
                t = (intOption.get() - intOption.min) / (double) (intOption.max - intOption.min);
                value = intOption.get() == 0 && "maxDistance".equals(option.key)
                    ? I18n.format("wayfarmap.settings.unlimited")
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
