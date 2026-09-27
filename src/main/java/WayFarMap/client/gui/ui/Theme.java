package WayFarMap.client.gui.ui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/** Colors and drawing helpers of the flat, minimal look shared by all WayFarMap screens. */
public final class Theme {

    public static final int SCREEN_DIM = 0xC0080A0D;
    public static final int PANEL = 0xF0161A20;
    public static final int PANEL_ALT = 0xF01C2129;
    public static final int BORDER = 0xFF2A313B;
    public static final int CONTROL = 0xFF222831;
    public static final int CONTROL_HOVER = 0xFF2C3440;
    public static final int CONTROL_DISABLED = 0xFF1A1E24;
    public static final int ACCENT = 0xFF4C9AFF;
    public static final int ACCENT_DIM = 0xFF2B5A96;
    public static final int DANGER = 0xFFE5534B;
    public static final int SUCCESS = 0xFF3FB950;
    public static final int TEXT = 0xFFE6EAF0;
    public static final int TEXT_MUTED = 0xFF8A93A0;
    public static final int TEXT_DISABLED = 0xFF555D68;
    public static final int ROW_HOVER = 0x20FFFFFF;
    public static final int LABEL_BG = 0xC0101418;

    private Theme() {}

    public static void fill(int x0, int y0, int x1, int y1, int color) {
        Gui.drawRect(x0, y0, x1, y1, color);
    }

    /** One pixel outline inside the rectangle. */
    public static void outline(int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y0 + 1, color);
        fill(x0, y1 - 1, x1, y1, color);
        fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** Panel with a thin border. */
    public static void panel(int x0, int y0, int x1, int y1) {
        fill(x0, y0, x1, y1, PANEL);
        outline(x0, y0, x1, y1, BORDER);
    }

    public static void text(FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x, y, color);
    }

    public static void centered(FontRenderer font, String text, int centerX, int y, int color) {
        font.drawString(text, centerX - font.getStringWidth(text) / 2, y, color);
    }

    /** Cuts the text to {@code maxWidth} pixels, ending with "..." when it doesn't fit. */
    public static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth(dots))) + dots;
    }

    public static boolean inside(int mouseX, int mouseY, int x0, int y0, int x1, int y1) {
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }
}
