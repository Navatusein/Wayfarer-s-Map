package WayFarMap.client.gui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;

/**
 * In-game help: sections in a sidebar, the text of the selected one on the right, like the settings screen.
 * <p>
 * The text comes from {@code assets/wayfarmap/help/<language>.txt} (falling back to en_US): "# Title" starts a
 * section, "! " marks an important note, "> " a tip, "- " a list item, an empty line a gap. Minecraft color codes
 * (§) can be used anywhere.
 */
public class GuiHelp extends ScaledScreen {

    private static final int SIDEBAR_WIDTH = 124;
    private static final int LINE_HEIGHT = 10;
    private static final int ID_BACK = 1000;

    /** A section of the help file: its title and raw lines. */
    private static final class Section {

        final String title;
        final List<String> lines = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }
    }

    /** A wrapped line ready to draw. */
    private static final class Line {

        final String text;
        /** 0 = normal, 1 = important, 2 = tip, 3 = gap. */
        final int kind;
        final boolean bullet;
        /** Wrapped continuation of a list item, indented like its first line. */
        final boolean continuation;

        Line(String text, int kind, boolean bullet, boolean continuation) {
            this.text = text;
            this.kind = kind;
            this.bullet = bullet;
            this.continuation = continuation;
        }
    }

    private static int selected;

    private final GuiScreen parent;
    private List<Section> sections;
    private final List<Line> lines = new ArrayList<>();
    private int left, top, right, bottom, contentLeft, contentTop, contentBottom;
    private int scroll;

    public GuiHelp(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (sections == null) {
            sections = load();
        }
        selected = Math.max(0, Math.min(selected, sections.size() - 1));
        int panelWidth = Math.min(width - 16, 520);
        int panelHeight = Math.min(height - 16, 320);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 12;
        contentTop = top + 28;
        contentBottom = bottom - 8;

        buttonList.clear();
        for (int i = 0; i < sections.size(); i++) {
            FlatButton button = new FlatButton(
                i,
                left + 6,
                top + 26 + i * 20,
                SIDEBAR_WIDTH - 12,
                16,
                sections.get(i).title);
            button.active = i == selected;
            buttonList.add(button);
        }
        buttonList.add(
            new FlatButton(ID_BACK, left + 6, bottom - 24, SIDEBAR_WIDTH - 12, 18, I18n.format("gui.done")));
        layout();
    }

    /** Wraps the selected section to the content width. */
    private void layout() {
        lines.clear();
        scroll = 0;
        if (sections.isEmpty()) {
            return;
        }
        int textWidth = right - 12 - contentLeft;
        for (String raw : sections.get(selected).lines) {
            if (raw.trim()
                .isEmpty()) {
                lines.add(new Line("", 3, false, false));
                continue;
            }
            int kind = 0;
            boolean bullet = false;
            String text = raw;
            if (text.startsWith("! ")) {
                kind = 1;
                text = text.substring(2);
            } else if (text.startsWith("> ")) {
                kind = 2;
                text = text.substring(2);
            } else if (text.startsWith("- ")) {
                bullet = true;
                text = text.substring(2);
            }
            int indent = kind != 0 ? 8 : bullet ? 10 : 0;
            List<?> wrapped = fontRendererObj.listFormattedStringToWidth(text, textWidth - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(String.valueOf(wrapped.get(i)), kind, bullet && i == 0, bullet && i > 0));
            }
        }
    }

    private static List<Section> load() {
        Minecraft mc = Minecraft.getMinecraft();
        String language = mc.getLanguageManager()
            .getCurrentLanguage()
            .getLanguageCode();
        List<Section> sections = read(language);
        if (sections.isEmpty()) {
            sections = read("en_US");
        }
        return sections;
    }

    private static List<Section> read(String language) {
        List<Section> sections = new ArrayList<>();
        ResourceLocation location = new ResourceLocation("wayfarmap", "help/" + language + ".txt");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location)
                    .getInputStream(),
                StandardCharsets.UTF_8))) {
            Section current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# ")) {
                    current = new Section(line.substring(2)
                        .trim());
                    sections.add(current);
                } else if (current != null) {
                    current.lines.add(line);
                }
            }
        } catch (Exception e) {
            // Missing translation: the caller falls back to English.
        }
        return sections;
    }

    private int maxScroll() {
        return Math.max(0, lines.size() * LINE_HEIGHT - (contentBottom - contentTop));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
        } else if (button.id < sections.size()) {
            selected = button.id;
            for (Object o : buttonList) {
                GuiButton other = (GuiButton) o;
                if (other.id < sections.size()) {
                    ((FlatButton) other).active = other.id == selected;
                }
            }
            layout();
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
            scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(wheel) * LINE_HEIGHT * 3));
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.fill(left + 1, top + 1, left + SIDEBAR_WIDTH, bottom - 1, Theme.PANEL_ALT);
        Theme.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, bottom - 1, Theme.BORDER);
        Theme.text(fontRendererObj, "Wayfarer's Map", left + 8, top + 9, Theme.ACCENT);

        if (!sections.isEmpty()) {
            Theme.text(fontRendererObj, sections.get(selected).title, contentLeft, top + 9, Theme.TEXT);
        }
        Theme.fill(contentLeft, top + 20, right - 10, top + 21, Theme.BORDER);

        for (int i = 0; i < lines.size(); i++) {
            int y = contentTop + i * LINE_HEIGHT - scroll;
            if (y < contentTop || y + LINE_HEIGHT > contentBottom) {
                continue;
            }
            Line line = lines.get(i);
            int x = contentLeft;
            if (line.kind == 1 || line.kind == 2) {
                // Important notes and tips: a tinted band with a colored bar on the left.
                int color = line.kind == 1 ? Theme.DANGER : Theme.SUCCESS;
                Theme.fill(x, y - 1, right - 12, y + LINE_HEIGHT - 1, (color & 0xFFFFFF) | 0x22000000);
                Theme.fill(x, y - 1, x + 2, y + LINE_HEIGHT - 1, color);
                x += 8;
            } else if (line.bullet) {
                Theme.fill(x + 2, y + 3, x + 5, y + 6, Theme.ACCENT);
                x += 10;
            } else if (line.continuation) {
                x += 10;
            }
            fontRendererObj.drawString(line.text, x, y, Theme.TEXT);
        }
        if (maxScroll() > 0) {
            int track = contentBottom - contentTop;
            int bar = Math.max(12, track * track / (track + maxScroll()));
            int barY = contentTop + (track - bar) * scroll / maxScroll();
            Theme.fill(right - 6, barY, right - 4, barY + bar, Theme.BORDER);
        }
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
