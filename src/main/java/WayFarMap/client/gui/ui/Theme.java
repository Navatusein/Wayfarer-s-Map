package WayFarMap.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

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

    /** The color between {@code from} (t = 0) and {@code to} (t = 1), alpha included. */
    public static int blend(int from, int to, double t) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
            result |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }

    /**
     * Draws only inside the rectangle (GUI pixels of the screen being drawn) until {@link #unclip}: a scrolled list
     * is cut at its edges instead of rows popping in and out.
     */
    public static void clip(int x0, int y0, int x1, int y1) {
        int factor = ScaledScreen.currentFactor();
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        // The scissor is in window pixels: the screen's slide while it opens is not applied to it.
        int offset = ScaledScreen.currentOffset();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * factor, displayHeight - (y1 + offset) * factor, (x1 - x0) * factor, (y1 - y0) * factor);
    }

    public static void unclip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * The color of an icon that has a color of its own: full when lit (hovered, on or chosen), brighter on an accent
     * background, a darker shade of it when dimmed (off, not chosen), so it can still be told apart.
     */
    public static int iconShade(int color, boolean lit, boolean onAccent, boolean dimmed) {
        if (onAccent) {
            return blend(color, 0xFFFFFFFF, 0.35);
        }
        if (dimmed && !lit) {
            return blend(color, PANEL | 0xFF000000, 0.55);
        }
        return color;
    }

    /** A filled circle, one rectangle per row of pixels. */
    public static void disc(double centerX, double centerY, double radius, int color) {
        int top = (int) Math.floor(centerY - radius), bottom = (int) Math.ceil(centerY + radius);
        for (int y = top; y < bottom; y++) {
            double dy = y + 0.5 - centerY;
            double reach = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int from = (int) Math.round(centerX - reach), to = (int) Math.round(centerX + reach);
            if (to > from) {
                fill(from, y, to, y + 1, color);
            }
        }
    }

    /** A thin scrollbar: its track, and the thumb lit while {@code lit}; {@code position} is 0 at the top to 1. */
    public static void scrollbar(int x, int y0, int y1, int visible, int total, double position, boolean lit) {
        int track = y1 - y0;
        int bar = Math.max(12, track * visible / Math.max(visible, total));
        int barY = y0 + (int) Math.round((track - bar) * Math.max(0, Math.min(1, position)));
        fill(x, y0, x + 2, y1, CONTROL);
        fill(x, barY, x + 2, barY + bar, lit ? ACCENT : BORDER);
    }
}
