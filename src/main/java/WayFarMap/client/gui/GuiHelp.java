package WayFarMap.client.gui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.KeyHandler;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * In-game help: sections in a sidebar (scrolled when they don't all fit), the text of the selected one on the right,
 * like the settings screen; the arrow keys go to the section before and after it.
 * <p>
 * The text comes from {@code assets/wayfarmap/help/<language>.txt} (falling back to en_US): "# Title" starts a
 * section, "! " marks an important note, "> " a tip, "- " a list item, an empty line a gap. Minecraft color codes
 * (§) can be used anywhere. {@code {key:open_map}} is the key the mod's binding of that name is set to now (from
 * Minecraft's controls, so a key changed there shows changed here), or "not set".
 */
public class GuiHelp extends ScaledScreen {

    private static final int SIDEBAR_WIDTH = 156;
    private static final int LINE_HEIGHT = 11;
    /** Height of a section's entry in the sidebar. */
    private static final int ITEM_HEIGHT = 17;
    private static final int ID_CLOSE = 1000;

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
        /** First and last line of an important note or tip, for the band's padding. */
        final boolean first, last;

        Line(String text, int kind, boolean bullet, boolean continuation, boolean first, boolean last) {
            this.text = text;
            this.kind = kind;
            this.bullet = bullet;
            this.continuation = continuation;
            this.first = first;
            this.last = last;
        }
    }

    private static int selected;

    private final GuiScreen parent;
    private List<Section> sections;
    private final List<Line> lines = new ArrayList<>();
    private int left, top, right, bottom, contentLeft, contentRight, contentTop, contentBottom;
    /** The sidebar's list of sections: where it is and how far it is scrolled. */
    private int listTop, listBottom, listScroll;
    private int scroll;
    /** Where the sidebar's list and the text are drawn while they ease to their scroll. */
    private final Smooth shownListScroll = new Smooth(0), shownScroll = new Smooth(0);
    /** How lit each section's entry in the sidebar is by the mouse. */
    private Smooth[] sectionLight = new Smooth[0];

    public GuiHelp(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (sections == null) {
            sections = load();
        }
        selected = Math.max(0, Math.min(selected, sections.size() - 1));
        int panelWidth = Math.min(width - 16, 600);
        int panelHeight = Math.min(height - 16, 360);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 14;
        contentRight = right - 14;
        contentTop = top + 32;
        contentBottom = bottom - 10;
        listTop = top + 26;
        listBottom = bottom - 34;

        buttonList.clear();
        // The close button stays at the bottom of the sidebar, under the list of sections, whatever their number.
        String close = I18n.format("wayfarmap.help.close");
        buttonList.add(new FlatButton(ID_CLOSE, left + 6, bottom - 26, SIDEBAR_WIDTH - 12, 18, close));
        select(selected);
    }

    /** Shows a section: its text from the top, its entry in the sidebar in view. */
    private void select(int index) {
        if (sections.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(index, sections.size() - 1));
        int itemTop = selected * ITEM_HEIGHT;
        int visible = listBottom - listTop;
        if (itemTop < listScroll) {
            listScroll = itemTop;
        } else if (itemTop + ITEM_HEIGHT > listScroll + visible) {
            listScroll = itemTop + ITEM_HEIGHT - visible;
        }
        clampListScroll();
        layout();
    }

    /** Wraps the selected section to the content width. */
    private void layout() {
        lines.clear();
        scroll = 0;
        // Another section's text starts at its top at once.
        shownScroll.set(0);
        if (sections.isEmpty()) {
            return;
        }
        int textWidth = contentRight - 8 - contentLeft;
        List<String> raw = sections.get(selected).lines;
        // Gaps at the start and the end of a section only push the text around.
        int from = 0, to = raw.size();
        while (from < to && raw.get(from)
            .trim()
            .isEmpty()) {
            from++;
        }
        while (to > from && raw.get(to - 1)
            .trim()
            .isEmpty()) {
            to--;
        }
        for (int n = from; n < to; n++) {
            String text = withKeys(raw.get(n));
            if (text.trim()
                .isEmpty()) {
                lines.add(new Line("", 3, false, false, false, false));
                continue;
            }
            int kind = 0;
            boolean bullet = false;
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
            if (kind != 0 && !lines.isEmpty() && lines.get(lines.size() - 1).kind != 3) {
                // A note or tip right after other text or another note: a little room so their bands don't touch.
                lines.add(new Line("", 3, false, false, false, false));
            }
            int indent = kind != 0 ? 12 : bullet ? 10 : 0;
            List<?> wrapped = fontRendererObj.listFormattedStringToWidth(text, textWidth - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(
                    new Line(
                        String.valueOf(wrapped.get(i)),
                        kind,
                        bullet && i == 0,
                        bullet && i > 0,
                        i == 0,
                        i == wrapped.size() - 1));
            }
        }
    }

    private static final Pattern KEY = Pattern.compile("\\{key:([a-z_]+)\\}");

    /** The text with each {@code {key:name}} put as the key set now, a gray "not set" for a binding without one. */
    private static String withKeys(String text) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        Matcher matcher = KEY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = KeyHandler.keyName(matcher.group(1));
            String shown = key != null ? key : "\u00A77" + I18n.format("wayfarmap.help.key_none") + "\u00A7e";
            matcher.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(out);
        return out.toString();
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
                    current = new Section(
                        line.substring(2)
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

    /** Height of a line: gaps are half a line. */
    private static int height(Line line) {
        return line.kind == 3 ? LINE_HEIGHT / 2 + 1 : LINE_HEIGHT;
    }

    private int textHeight() {
        int sum = 0;
        for (Line line : lines) {
            sum += height(line);
        }
        return sum;
    }

    private int maxScroll() {
        return Math.max(0, textHeight() - (contentBottom - contentTop));
    }

    private int maxListScroll() {
        return Math.max(0, sections.size() * ITEM_HEIGHT - (listBottom - listTop));
    }

    private void clampListScroll() {
        listScroll = Math.max(0, Math.min(maxListScroll(), listScroll));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        int index = sectionAt(mouseX, mouseY);
        if (button == 0 && index >= 0) {
            select(index);
        }
    }

    /** The section whose entry in the sidebar is under the mouse, or -1. */
    private int sectionAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, left + 6, listTop, left + SIDEBAR_WIDTH - 6, listBottom)) {
            return -1;
        }
        int index = (mouseY - listTop + (int) Math.round(shownListScroll.get())) / ITEM_HEIGHT;
        return index < sections.size() ? index : -1;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_LEFT) {
            select(selected - 1);
        } else if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_RIGHT) {
            select(selected + 1);
        } else if (keyCode == Keyboard.KEY_PRIOR || keyCode == Keyboard.KEY_NEXT) {
            int page = contentBottom - contentTop - LINE_HEIGHT * 2;
            scroll += keyCode == Keyboard.KEY_NEXT ? page : -page;
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getX() * width / mc.displayWidth;
        if (mouseX < left + SIDEBAR_WIDTH) {
            listScroll -= Integer.signum(wheel) * ITEM_HEIGHT * 2;
            clampListScroll();
        } else {
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
        drawSections(mouseX, mouseY);
        // The close button is set apart from the list.
        Theme.fill(left + 6, bottom - 32, left + SIDEBAR_WIDTH - 6, bottom - 31, Theme.BORDER);

        if (!sections.isEmpty()) {
            Theme.text(fontRendererObj, sections.get(selected).title, contentLeft, top + 10, Theme.TEXT);
            String counter = (selected + 1) + " / " + sections.size();
            Theme.text(
                fontRendererObj,
                counter,
                contentRight - fontRendererObj.getStringWidth(counter),
                top + 10,
                Theme.TEXT_MUTED);
        }
        Theme.fill(contentLeft, top + 22, contentRight, top + 23, Theme.ACCENT_DIM);
        drawText();
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    /** The sidebar's list: the selected section marked, the one under the mouse lit, cut to the list's area. */
    private void drawSections(int mouseX, int mouseY) {
        int hovered = sectionAt(mouseX, mouseY);
        int maxScroll = maxListScroll();
        int x0 = left + 6, x1 = left + SIDEBAR_WIDTH - (maxScroll > 0 ? 9 : 6);
        if (sectionLight.length != sections.size()) {
            sectionLight = new Smooth[sections.size()];
            for (int i = 0; i < sectionLight.length; i++) {
                sectionLight[i] = new Smooth(0);
            }
        }
        double shown = shownListScroll.update(listScroll, 16);
        Theme.clip(x0, listTop, x1, listBottom);
        for (int i = 0; i < sections.size(); i++) {
            int y = listTop + i * ITEM_HEIGHT - (int) Math.round(shown);
            double lit = sectionLight[i].update(i == hovered ? 1 : 0, 20);
            if (y + ITEM_HEIGHT <= listTop || y >= listBottom) {
                continue;
            }
            boolean current = i == selected;
            if (current) {
                Theme.fill(x0, y, x1, y + ITEM_HEIGHT - 1, Theme.CONTROL_HOVER);
                Theme.fill(x0, y, x0 + 2, y + ITEM_HEIGHT - 1, Theme.ACCENT);
            } else if (lit > 0.02) {
                Theme.fill(x0, y, x1, y + ITEM_HEIGHT - 1, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
            }
            String title = Theme.ellipsize(fontRendererObj, sections.get(i).title, x1 - x0 - 12);
            int color = current ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Theme.text(fontRendererObj, title, x0 + 7, y + (ITEM_HEIGHT - 8) / 2, color);
        }
        Theme.unclip();
        if (maxScroll > 0) {
            boolean over = Theme.inside(mouseX, mouseY, left, listTop, left + SIDEBAR_WIDTH, listBottom);
            int total = sections.size() * ITEM_HEIGHT;
            int x = left + SIDEBAR_WIDTH - 6;
            Theme.scrollbar(x, listTop, listBottom, listBottom - listTop, total, shown / maxScroll, over);
        }
    }

    /** The section's text: notes and tips on a tinted band, list items with a dot. */
    private void drawText() {
        double shown = shownScroll.update(scroll, 16);
        int y = contentTop - (int) Math.round(shown);
        // Cut a little above the top, where a note's band starts over its first line.
        Theme.clip(contentLeft, contentTop - 3, contentRight, contentBottom + 1);
        for (Line line : lines) {
            int h = height(line);
            if (y + h > contentTop - 3 && y < contentBottom + 1 && line.kind != 3) {
                int x = contentLeft;
                if (line.kind == 1 || line.kind == 2) {
                    // Important notes and tips: a tinted band with a colored bar on the left.
                    int color = line.kind == 1 ? Theme.DANGER : Theme.SUCCESS;
                    int bandTop = y - (line.first ? 3 : 1), bandBottom = y + LINE_HEIGHT - (line.last ? -1 : 1);
                    Theme.fill(x, bandTop, contentRight, bandBottom, (color & 0xFFFFFF) | 0x26000000);
                    Theme.fill(x, bandTop, x + 2, bandBottom, color);
                    x += 12;
                } else if (line.bullet) {
                    Theme.fill(x + 2, y + 3, x + 5, y + 6, Theme.ACCENT);
                    x += 10;
                } else if (line.continuation) {
                    x += 10;
                }
                fontRendererObj.drawString(line.text, x, y, Theme.TEXT);
            }
            y += h;
        }
        Theme.unclip();
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int visible = contentBottom - contentTop;
            double position = shown / maxScroll;
            Theme.scrollbar(right - 8, contentTop, contentBottom, visible, visible + maxScroll, position, false);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
