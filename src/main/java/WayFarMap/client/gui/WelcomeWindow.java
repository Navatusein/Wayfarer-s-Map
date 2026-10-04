package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import WayFarMap.Tags;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * The window shown over the world map the first time it is opened: the mod's logo, name and version, what it can
 * do, the first things to know, who made and tested it with links to the author's pages, and buttons to start or to
 * open the help. Its parts slide in one after another when it opens.
 */
final class WelcomeWindow {

    /** What a click on the window did. */
    enum Click {
        NONE,
        CLOSE,
        HELP
    }

    private static final int WIDTH = 340, PAD = 14;
    private static final int HERO_HEIGHT = 104;
    private static final int FEATURE_ROW = 16, STEP_HEIGHT = 16, SECTION_TITLE = 14;
    /** Columns of the features' grid. */
    private static final int FEATURE_COLUMNS = 2;
    private static final int BUTTON_HEIGHT = 20, LINK_HEIGHT = 16;
    /** How long a part takes to slide in, and how much later each part starts than the one before (ms). */
    private static final long SLIDE_MS = 260, STAGGER_MS = 70;
    /** Color of a key cap: the yellow of §e, as in the help. */
    private static final int KEY_CAP_COLOR = 0xFFFF55;

    /** What the mod can do: {icon, translation key}. */
    private static final Object[][] FEATURES = { { Icons.ISO, "map" }, { Icons.WAYPOINTS, "waypoints" },
        { Icons.CAVES, "caves" }, { Icons.TOPO, "topo" }, { Icons.ORE, "mods" }, { Icons.TEAM, "team" } };
    /** The author's pages: {name, url, icon, color}. */
    private static final Object[][] LINKS = { { "GitHub", GuiAbout.GITHUB_URL, Icons.GITHUB, 0xFFE6EAF0 },
        { "Boosty", GuiAbout.BOOSTY_URL, Icons.BOOSTY, 0xFFF15F2C },
        { "Telegram", GuiAbout.TELEGRAM_URL, Icons.TELEGRAM, 0xFF2AABEE } };

    private final FontRenderer font;
    private final long openedAt = System.currentTimeMillis();
    /** Where the buttons and links were drawn last, for the clicks: {x0, y0, x1, y1}. */
    private int[] startRect = new int[4], helpRect = new int[4];
    private final int[][] linkRects = new int[LINKS.length][4];
    /** How lit each button and link is by the mouse. */
    private final Smooth startLight = new Smooth(0), helpLight = new Smooth(0);
    private final Smooth[] linkLight = { new Smooth(0), new Smooth(0), new Smooth(0) };

    WelcomeWindow(FontRenderer font) {
        this.font = font;
    }

    private static int featureRows() {
        return (FEATURES.length + FEATURE_COLUMNS - 1) / FEATURE_COLUMNS;
    }

    private static int textWidth() {
        return WIDTH - 2 * PAD;
    }

    private List<?> tagline() {
        return font.listFormattedStringToWidth(I18n.format("wayfarmap.welcome.text"), textWidth());
    }

    /** The first things to know: {key, what it does}. */
    private static String[][] steps() {
        String waypointKey = KeyHandler.keyName("new_waypoint");
        List<String[]> steps = new ArrayList<>();
        steps.add(new String[] { I18n.format("wayfarmap.welcome.key_drag"), I18n.format("wayfarmap.welcome.drag") });
        steps.add(new String[] { I18n.format("wayfarmap.welcome.key_menu"), I18n.format("wayfarmap.welcome.menu") });
        if (waypointKey != null) {
            steps.add(new String[] { waypointKey, I18n.format("wayfarmap.welcome.waypoint") });
        }
        steps.add(new String[] { "?", I18n.format("wayfarmap.welcome.help") });
        return steps.toArray(new String[0][]);
    }

    private int keyColumn(String[][] steps) {
        int widest = 0;
        for (String[] step : steps) {
            widest = Math.max(widest, font.getStringWidth(step[0]) + 8);
        }
        return widest;
    }

    private List<?> stepLines(String[] step, int keyColumn) {
        return font.listFormattedStringToWidth(step[1], textWidth() - keyColumn - 8);
    }

    private int stepHeight(String[] step, int keyColumn) {
        return Math.max(STEP_HEIGHT, stepLines(step, keyColumn).size() * 10 + 6);
    }

    /** Height of the whole window. */
    private int height() {
        String[][] steps = steps();
        int keyColumn = keyColumn(steps);
        int stepsHeight = 0;
        for (String[] step : steps) {
            stepsHeight += stepHeight(step, keyColumn);
        }
        int features = featureRows() * FEATURE_ROW;
        return HERO_HEIGHT + 10
            + tagline().size() * 10
            + 10
            + SECTION_TITLE
            + features
            + 8
            + SECTION_TITLE
            + stepsHeight
            + 6
            + SECTION_TITLE
            + 24
            + LINK_HEIGHT
            + 12
            + BUTTON_HEIGHT
            + PAD;
    }

    private static int left(int screenWidth) {
        return (screenWidth - WIDTH) / 2;
    }

    private int top(int screenHeight) {
        return Math.max(4, (screenHeight - height()) / 2);
    }

    /** How far a part (0 the first) is still below its place, sliding up into it. */
    private int slide(int part) {
        double t = (System.currentTimeMillis() - openedAt - part * STAGGER_MS) / (double) SLIDE_MS;
        t = Math.max(0, Math.min(1, t));
        return (int) Math.round(Math.pow(1 - t, 3) * 12);
    }

    void draw(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int left = left(screenWidth), top = top(screenHeight), right = left + WIDTH, bottom = top + height();
        int centerX = left + WIDTH / 2;
        Theme.panel(left, top, right, bottom);
        // Parts slide in under the panel's edges, so they are cut to it.
        Theme.clip(left + 1, top + 1, right - 1, bottom - 1);

        // The top: the logo glowing softly, the name and the version.
        int y = top + slide(0);
        Theme.fill(left + 1, y + 1, right - 1, y + HERO_HEIGHT, Theme.PANEL_ALT);
        Theme.fill(left + 1, y + 1, right - 1, y + 3, Theme.ACCENT);
        Theme.fill(left + 1, y + HERO_HEIGHT, right - 1, y + HERO_HEIGHT + 1, Theme.BORDER);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 600.0);
        int logoCenterY = y + 34;
        int glow = Theme.ACCENT & 0xFFFFFF;
        Theme.disc(centerX, logoCenterY, 34, (int) (0x0C + 0x08 * pulse) << 24 | glow);
        Theme.disc(centerX, logoCenterY, 27, (int) (0x10 + 0x0C * pulse) << 24 | glow);
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - Icons.LOGO_SIZE * 3 / 2f, logoCenterY - Icons.LOGO_SIZE * 3 / 2f, 0f);
        GL11.glScalef(3f, 3f, 1f);
        Icons.drawLogo(0, 0);
        GL11.glPopMatrix();
        String title = "Wayfarer's Map";
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - font.getStringWidth(title), y + 62, 0f);
        GL11.glScalef(2f, 2f, 1f);
        font.drawStringWithShadow(title, 0, 0, Theme.TEXT);
        GL11.glPopMatrix();
        String version = Theme.ellipsize(font, I18n.format("wayfarmap.about.version", Tags.VERSION), WIDTH - 60);
        int versionWidth = font.getStringWidth(version) + 12;
        int pillLeft = centerX - versionWidth / 2;
        Theme.fill(pillLeft, y + 84, pillLeft + versionWidth, y + 96, Theme.CONTROL);
        Theme.outline(pillLeft, y + 84, pillLeft + versionWidth, y + 96, Theme.ACCENT_DIM);
        Theme.text(font, version, pillLeft + 6, y + 86, Theme.TEXT_MUTED);

        // What it is, in a line or two.
        y = top + HERO_HEIGHT + 10 + slide(1);
        for (Object line : tagline()) {
            String text = String.valueOf(line);
            Theme.centered(font, text, centerX, y, Theme.TEXT);
            y += 10;
        }
        int base = top + HERO_HEIGHT + 10 + tagline().size() * 10 + 10;

        // What it can do: icons and names in three columns.
        y = base + slide(2);
        sectionTitle(I18n.format("wayfarmap.welcome.features_title"), left, right, y);
        y += SECTION_TITLE;
        int column = textWidth() / FEATURE_COLUMNS;
        for (int i = 0; i < FEATURES.length; i++) {
            String[] icon = (String[]) FEATURES[i][0];
            int x = left + PAD + (i % FEATURE_COLUMNS) * column;
            int rowY = y + (i / FEATURE_COLUMNS) * FEATURE_ROW;
            int iconY = rowY + (FEATURE_ROW - 2 - icon.length) / 2;
            Icons.draw(icon, x + (12 - Icons.width(icon)) / 2, iconY, Theme.ACCENT);
            String name = I18n.format("wayfarmap.welcome.feature." + FEATURES[i][1]);
            Theme.text(font, Theme.ellipsize(font, name, column - 20), x + 16, rowY + 3, Theme.TEXT);
        }
        base += SECTION_TITLE + featureRows() * FEATURE_ROW + 8;

        // The first things to know, on key caps.
        y = base + slide(3);
        sectionTitle(I18n.format("wayfarmap.welcome.start_title"), left, right, y);
        y += SECTION_TITLE;
        String[][] steps = steps();
        int keyColumn = keyColumn(steps);
        int stepsHeight = 0;
        for (String[] step : steps) {
            drawKeyCap(step[0], left + PAD, y);
            int lineY = y + 2;
            for (Object line : stepLines(step, keyColumn)) {
                Theme.text(font, String.valueOf(line), left + PAD + keyColumn + 8, lineY, Theme.TEXT_MUTED);
                lineY += 10;
            }
            int h = stepHeight(step, keyColumn);
            y += h;
            stepsHeight += h;
        }
        base += SECTION_TITLE + stepsHeight + 6;

        // Who made and tested it, and the author's pages.
        y = base + slide(4);
        sectionTitle(I18n.format("wayfarmap.welcome.credits_title"), left, right, y);
        y += SECTION_TITLE;
        int half = left + WIDTH / 2;
        Theme.text(font, I18n.format("wayfarmap.about.author"), left + PAD, y, Theme.TEXT_MUTED);
        Theme.text(font, GuiAbout.AUTHOR, left + PAD, y + 11, Theme.ACCENT);
        Theme.text(font, I18n.format("wayfarmap.about.testers"), half + 4, y, Theme.TEXT_MUTED);
        String testers = GuiAbout.TESTER + ", " + I18n.format("wayfarmap.about.tester_chat");
        Theme.text(font, Theme.ellipsize(font, testers, right - PAD - half - 4), half + 4, y + 11, Theme.TEXT);
        y += 24;
        int gap = 6, linkWidth = (textWidth() - 2 * gap) / 3;
        for (int i = 0; i < LINKS.length; i++) {
            int x0 = left + PAD + i * (linkWidth + gap);
            linkRects[i] = new int[] { x0, y, x0 + linkWidth, y + LINK_HEIGHT };
            drawLink(i, linkRects[i], mouseX, mouseY);
        }
        base += SECTION_TITLE + 24 + LINK_HEIGHT + 12;

        // Start, or read the help first.
        y = base + slide(5);
        int buttonWidth = (textWidth() - gap) / 2;
        helpRect = new int[] { left + PAD, y, left + PAD + buttonWidth, y + BUTTON_HEIGHT };
        startRect = new int[] { right - PAD - buttonWidth, y, right - PAD, y + BUTTON_HEIGHT };
        drawButton(helpRect, I18n.format("wayfarmap.welcome.open_help"), false, helpLight, mouseX, mouseY);
        drawButton(startRect, I18n.format("wayfarmap.welcome.ok"), true, startLight, mouseX, mouseY);
        Theme.unclip();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A small title in the accent color with a line after it, like the settings' sections. */
    private void sectionTitle(String title, int left, int right, int y) {
        Theme.text(font, title, left + PAD, y, Theme.ACCENT);
        int lineX = left + PAD + font.getStringWidth(title) + 6;
        Theme.fill(lineX, y + 4, right - PAD, y + 5, Theme.BORDER);
    }

    /** A key cap with the text on it, as in the help. */
    private void drawKeyCap(String key, int x, int y) {
        int w = font.getStringWidth(key) + 8;
        Theme.fill(x, y, x + w, y + 12, 0x26000000 | KEY_CAP_COLOR);
        // A darker edge at the bottom, like a key.
        Theme.fill(x, y + 12, x + w, y + 13, 0x60000000 | KEY_CAP_COLOR);
        Theme.text(font, key, x + 4, y + 2, 0xFF000000 | KEY_CAP_COLOR);
    }

    /** One of the author's pages: its logo in the site's color and its name, lit in that color under the mouse. */
    private void drawLink(int i, int[] r, int mouseX, int mouseY) {
        int brand = (Integer) LINKS[i][3];
        boolean hovered = Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
        double lit = linkLight[i].update(hovered ? 1 : 0, 22);
        Theme.fill(r[0], r[1], r[2], r[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(r[0], r[1], r[2], r[3], Theme.blend(Theme.BORDER, brand, lit));
        String[] icon = (String[]) LINKS[i][2];
        String name = (String) LINKS[i][0];
        int contentWidth = Icons.width(icon) + 4 + font.getStringWidth(name);
        int x = (r[0] + r[2] - contentWidth) / 2;
        Icons.draw(icon, x, r[1] + (LINK_HEIGHT - icon.length) / 2, brand);
        Theme.text(font, name, x + Icons.width(icon) + 4, r[1] + 4, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    private void drawButton(int[] r, String label, boolean primary, Smooth light, int mouseX, int mouseY) {
        boolean hovered = Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
        double lit = light.update(hovered ? 1 : 0, 22);
        int background = primary ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
            : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
        Theme.fill(r[0], r[1], r[2], r[3], background);
        Theme.outline(r[0], r[1], r[2], r[3], primary ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        Theme.centered(font, label, (r[0] + r[2]) / 2, r[1] + (BUTTON_HEIGHT - 8) / 2, Theme.TEXT);
    }

    /** Handles a click: the buttons close the window (the help one opens the help), the links open their page. */
    Click click(int mouseX, int mouseY) {
        if (Theme.inside(mouseX, mouseY, startRect[0], startRect[1], startRect[2], startRect[3])) {
            return Click.CLOSE;
        }
        if (Theme.inside(mouseX, mouseY, helpRect[0], helpRect[1], helpRect[2], helpRect[3])) {
            return Click.HELP;
        }
        for (int i = 0; i < LINKS.length; i++) {
            int[] r = linkRects[i];
            if (Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                GuiAbout.openLink((String) LINKS[i][1]);
                return Click.NONE;
            }
        }
        return Click.NONE;
    }
}
