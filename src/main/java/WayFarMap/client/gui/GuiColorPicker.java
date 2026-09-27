package WayFarMap.client.gui;

import java.awt.Color;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;

/**
 * Picks any color: a saturation/brightness square for the current hue, a hue bar, and a hex field. Shows the old and
 * the new color side by side.
 */
public class GuiColorPicker extends ScaledScreen {

    public interface Callback {

        void onPicked(int rgb);
    }

    private static final int SQUARE = 120, BAR_WIDTH = 12, PANEL_WIDTH = 226, PANEL_HEIGHT = 186;
    private static final int ID_DONE = 0, ID_CANCEL = 1;
    /** Hue stops of the bar, top to bottom. */
    private static final int[] HUE_STOPS = { 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000 };

    private final GuiScreen parent;
    private final Callback callback;
    private final int oldColor;

    private float hue, saturation, brightness;
    private FlatTextField hexField;
    private int left, top;
    /** 1 = dragging in the square, 2 = on the hue bar, 0 = none. */
    private int dragging;

    public GuiColorPicker(GuiScreen parent, int color, Callback callback) {
        this.parent = parent;
        this.callback = callback;
        this.oldColor = color & 0xFFFFFF;
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        hue = hsb[0];
        saturation = hsb[1];
        brightness = hsb[2];
    }

    private int color() {
        return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

    private int squareX() {
        return left + 10;
    }

    private int squareY() {
        return top + 24;
    }

    private int barX() {
        return squareX() + SQUARE + 8;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int sideX = barX() + BAR_WIDTH + 8;
        int sideWidth = left + PANEL_WIDTH - 10 - sideX;
        hexField = new FlatTextField(fontRendererObj, sideX, squareY() + 72, sideWidth, 16);
        hexField.setMaxStringLength(7);
        updateHexField();

        buttonList.clear();
        int w = (PANEL_WIDTH - 24) / 2;
        FlatButton done = new FlatButton(ID_DONE, left + 10, top + PANEL_HEIGHT - 28, w, 18, I18n.format("gui.done"));
        done.active = true;
        buttonList.add(done);
        buttonList.add(
            new FlatButton(
                ID_CANCEL,
                left + PANEL_WIDTH - 10 - w,
                top + PANEL_HEIGHT - 28,
                w,
                18,
                I18n.format("gui.cancel")));
    }

    private void updateHexField() {
        hexField.setText(String.format("#%06X", color()));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DONE) {
            callback.onPicked(color());
            mc.displayGuiScreen(parent);
        } else if (button.id == ID_CANCEL) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            callback.onPicked(color());
            mc.displayGuiScreen(parent);
            return;
        }
        if (hexField.textboxKeyTyped(typedChar, keyCode)) {
            String text = hexField.getText()
                .trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            if (text.length() == 6) {
                try {
                    int rgb = Integer.parseInt(text, 16);
                    float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
                    hue = hsb[0];
                    saturation = hsb[1];
                    brightness = hsb[2];
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        hexField.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        int sideX = barX() + BAR_WIDTH + 8;
        if (Theme.inside(mouseX, mouseY, sideX, squareY() + 46, left + PANEL_WIDTH - 10, squareY() + 66)) {
            // Clicking the old color goes back to it.
            float[] hsb = Color.RGBtoHSB((oldColor >> 16) & 0xFF, (oldColor >> 8) & 0xFF, oldColor & 0xFF, null);
            hue = hsb[0];
            saturation = hsb[1];
            brightness = hsb[2];
            updateHexField();
            return;
        }
        if (Theme.inside(mouseX, mouseY, squareX(), squareY(), squareX() + SQUARE, squareY() + SQUARE)) {
            dragging = 1;
        } else if (Theme.inside(mouseX, mouseY, barX() - 2, squareY(), barX() + BAR_WIDTH + 2, squareY() + SQUARE)) {
            dragging = 2;
        }
        updateDrag(mouseX, mouseY);
    }

    /** Applies the mouse position while a button is held; done every frame for smooth dragging. */
    private void updateDrag(int mouseX, int mouseY) {
        if (dragging == 0) {
            return;
        }
        float ty = Math.max(0f, Math.min(1f, (mouseY - squareY()) / (float) SQUARE));
        if (dragging == 1) {
            saturation = Math.max(0f, Math.min(1f, (mouseX - squareX()) / (float) SQUARE));
            brightness = 1f - ty;
        } else {
            hue = Math.min(ty, 0.9999f);
        }
        updateHexField();
    }

    @Override
    public void updateScreen() {
        hexField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (dragging != 0) {
            if (Mouse.isButtonDown(0)) {
                updateDrag(mouseX, mouseY);
            } else {
                dragging = 0;
            }
        }

        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.pick_color"), left + 10, top + 9, Theme.ACCENT);

        int sx = squareX(), sy = squareY();
        Theme.outline(sx - 1, sy - 1, sx + SQUARE + 1, sy + SQUARE + 1, Theme.BORDER);
        // White to the pure hue left to right, fading to black downwards: exactly HSV with bilinear shading.
        int pureHue = Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorOpaque_I(0x000000);
        tessellator.addVertex(sx, sy + SQUARE, 0);
        tessellator.addVertex(sx + SQUARE, sy + SQUARE, 0);
        tessellator.setColorOpaque_I(pureHue);
        tessellator.addVertex(sx + SQUARE, sy, 0);
        tessellator.setColorOpaque_I(0xFFFFFF);
        tessellator.addVertex(sx, sy, 0);
        tessellator.draw();

        int bx = barX();
        Theme.outline(bx - 1, sy - 1, bx + BAR_WIDTH + 1, sy + SQUARE + 1, Theme.BORDER);
        double segment = SQUARE / (double) (HUE_STOPS.length - 1);
        tessellator.startDrawingQuads();
        for (int i = 0; i < HUE_STOPS.length - 1; i++) {
            double y0 = sy + i * segment, y1 = sy + (i + 1) * segment;
            tessellator.setColorOpaque_I(HUE_STOPS[i + 1]);
            tessellator.addVertex(bx, y1, 0);
            tessellator.addVertex(bx + BAR_WIDTH, y1, 0);
            tessellator.setColorOpaque_I(HUE_STOPS[i]);
            tessellator.addVertex(bx + BAR_WIDTH, y0, 0);
            tessellator.addVertex(bx, y0, 0);
        }
        tessellator.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        // Markers: a ring in the square, a line on the bar.
        int mx = sx + Math.round(saturation * SQUARE);
        int my = sy + Math.round((1f - brightness) * SQUARE);
        int ring = brightness > 0.5f && saturation < 0.5f ? 0xFF000000 : 0xFFFFFFFF;
        Theme.outline(mx - 3, my - 3, mx + 4, my + 4, ring);
        int hy = sy + Math.round(hue * SQUARE);
        Theme.fill(bx - 2, hy - 1, bx + BAR_WIDTH + 2, hy + 1, 0xFFFFFFFF);
        Theme.outline(bx - 3, hy - 2, bx + BAR_WIDTH + 3, hy + 2, 0xFF000000);

        // Old and new color.
        int sideX = bx + BAR_WIDTH + 8;
        int sideWidth = left + PANEL_WIDTH - 10 - sideX;
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.color_new"), sideX, sy, Theme.TEXT_MUTED);
        Theme.fill(sideX, sy + 10, sideX + sideWidth, sy + 30, 0xFF000000 | color());
        Theme.outline(sideX, sy + 10, sideX + sideWidth, sy + 30, Theme.BORDER);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.color_old"), sideX, sy + 36, Theme.TEXT_MUTED);
        Theme.fill(sideX, sy + 46, sideX + sideWidth, sy + 66, 0xFF000000 | oldColor);
        Theme.outline(sideX, sy + 46, sideX + sideWidth, sy + 66, Theme.BORDER);
        hexField.drawTextBox();

        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
