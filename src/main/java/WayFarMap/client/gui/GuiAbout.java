package WayFarMap.client.gui;

import java.net.URI;
import java.util.Collections;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import WayFarMap.Tags;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/** About the mod: its name and version, who made and tested it, and links to the author's pages. */
public class GuiAbout extends ScaledScreen {

    private static final int WIDTH = 260, HEIGHT = 220;
    private static final int ID_CLOSE = 0, ID_GITHUB = 1, ID_BOOSTY = 2, ID_TELEGRAM = 3;

    /** Who made and who tested the mod, and the author's pages; the welcome window shows them too. */
    static final String AUTHOR = "EvgenWarGold";
    static final String TESTER = "Faotik";
    static final String GITHUB_URL = "https://github.com/evgengoldwar", BOOSTY_URL = "https://boosty.to/evgenwargold",
        TELEGRAM_URL = "https://t.me/Shaterplay4";

    private final GuiScreen parent;
    private int left, top;

    public GuiAbout(GuiScreen parent) {
        this.parent = parent;
    }

    /** A button opening one of the author's pages: a pixel logo in the site's color and its name. */
    private static final class LinkButton extends FlatButton {

        final String url;
        final int brand;
        private final Smooth hover = new Smooth(0);

        LinkButton(int id, int x, int y, int width, String text, String url, String[] icon, int brand) {
            super(id, x, y, width, 20, text);
            this.url = url;
            this.icon = icon;
            this.brand = brand;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            boolean hovered = isMouseOver(mouseX, mouseY);
            double lit = hover.update(hovered ? 1 : 0, 22);
            int background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            Theme.fill(xPosition, yPosition, xPosition + width, yPosition + height, background);
            int border = Theme.blend(Theme.BORDER, brand, lit);
            Theme.outline(xPosition, yPosition, xPosition + width, yPosition + height, border);
            // The site's color as a strip along the bottom.
            Theme.fill(xPosition + 1, yPosition + height - 2, xPosition + width - 1, yPosition + height - 1, brand);
            int iconWidth = Icons.width(icon);
            int textWidth = mc.fontRenderer.getStringWidth(displayString);
            int x = xPosition + (width - iconWidth - 4 - textWidth) / 2;
            Icons.draw(icon, x, yPosition + (height - 1 - icon.length) / 2, brand);
            Theme.text(
                mc.fontRenderer,
                displayString,
                x + iconWidth + 4,
                yPosition + (height - 9) / 2,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        }
    }

    @Override
    public void initGui() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        buttonList.clear();
        int gap = 6;
        int linkWidth = (WIDTH - 20 - 2 * gap) / 3;
        int linkY = top + 166;
        buttonList.add(
            new LinkButton(ID_GITHUB, left + 10, linkY, linkWidth, "GitHub", GITHUB_URL, Icons.GITHUB, 0xFFE6EAF0));
        buttonList.add(
            new LinkButton(
                ID_BOOSTY,
                left + 10 + linkWidth + gap,
                linkY,
                linkWidth,
                "Boosty",
                BOOSTY_URL,
                Icons.BOOSTY,
                0xFFF15F2C));
        buttonList.add(
            new LinkButton(
                ID_TELEGRAM,
                left + WIDTH - 10 - linkWidth,
                linkY,
                linkWidth,
                "Telegram",
                TELEGRAM_URL,
                Icons.TELEGRAM,
                0xFF2AABEE));
        buttonList
            .add(new FlatButton(ID_CLOSE, left + 10, top + 193, WIDTH - 20, 18, I18n.format("wayfarmap.help.close")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof LinkButton) {
            openLink(((LinkButton) button).url);
        } else if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(parent);
        }
    }

    /** Opens the page in the system browser. */
    static void openLink(String url) {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop")
                .invoke(null);
            desktopClass.getMethod("browse", URI.class)
                .invoke(desktop, new URI(url));
            return;
        } catch (Throwable ignored) {
            // No AWT desktop (common on Linux): LWJGL knows the platform's own way.
        }
        try {
            Sys.openURL(url);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Couldn't open " + url, t);
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
        Theme.panel(left, top, left + WIDTH, top + HEIGHT);
        // Accent line along the top of the panel.
        Theme.fill(left + 1, top + 1, left + WIDTH - 1, top + 3, Theme.ACCENT);
        int centerX = left + WIDTH / 2;

        // The compass logo and the title, both twice the size.
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - Icons.LOGO_SIZE, top + 12, 0f);
        GL11.glScalef(2f, 2f, 1f);
        Icons.drawLogo(0, 0);
        GL11.glPopMatrix();
        String title = "Wayfarer's Map";
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - fontRendererObj.getStringWidth(title), top + 48, 0f);
        GL11.glScalef(2f, 2f, 1f);
        Theme.text(fontRendererObj, title, 0, 0, Theme.TEXT);
        GL11.glPopMatrix();
        // A long version (a dev build's git description) is cut to the panel; the full one shows on hover.
        String version = I18n.format("wayfarmap.about.version", Tags.VERSION);
        String shownVersion = Theme.ellipsize(fontRendererObj, version, WIDTH - 20);
        Theme.centered(fontRendererObj, shownVersion, centerX, top + 70, Theme.TEXT_MUTED);
        int versionWidth = fontRendererObj.getStringWidth(shownVersion);
        boolean versionHovered = !shownVersion.equals(version) && Theme
            .inside(mouseX, mouseY, centerX - versionWidth / 2, top + 69, centerX + versionWidth / 2 + 1, top + 79);

        divider(top + 84);
        Theme.centered(fontRendererObj, I18n.format("wayfarmap.about.author"), centerX, top + 92, Theme.TEXT_MUTED);
        Theme.centered(fontRendererObj, AUTHOR, centerX, top + 103, Theme.ACCENT);
        Theme.centered(fontRendererObj, I18n.format("wayfarmap.about.testers"), centerX, top + 120, Theme.TEXT_MUTED);
        Theme.centered(fontRendererObj, TESTER, centerX, top + 131, Theme.TEXT);
        Theme.centered(fontRendererObj, I18n.format("wayfarmap.about.tester_chat"), centerX, top + 142, Theme.TEXT);
        divider(top + 158);

        super.drawScaled(mouseX, mouseY, partialTicks);

        if (versionHovered) {
            drawHoveringText(Collections.singletonList(version), mouseX, mouseY, fontRendererObj);
        }
        for (Object o : buttonList) {
            if (o instanceof LinkButton && ((LinkButton) o).isMouseOver(mouseX, mouseY)) {
                drawHoveringText(Collections.singletonList(((LinkButton) o).url), mouseX, mouseY, fontRendererObj);
            }
        }
    }

    /** Thin line across the panel, short of its edges. */
    private void divider(int y) {
        Theme.fill(left + 20, y, left + WIDTH - 20, y + 1, Theme.BORDER);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
