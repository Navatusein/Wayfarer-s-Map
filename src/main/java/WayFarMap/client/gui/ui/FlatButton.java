package WayFarMap.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Flat button without the vanilla stone texture. */
public class FlatButton extends GuiButton {

    /** Highlighted with the accent color, e.g. a toggle that is on or the selected tab. */
    public boolean active;
    /** Red text, for destructive actions. */
    public boolean danger;

    public FlatButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return visible && Theme.inside(mouseX, mouseY, xPosition, yPosition, xPosition + width, yPosition + height);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        boolean hovered = enabled && isMouseOver(mouseX, mouseY);
        int background;
        if (!enabled) {
            background = Theme.CONTROL_DISABLED;
        } else if (active) {
            background = hovered ? Theme.ACCENT : Theme.ACCENT_DIM;
        } else {
            background = hovered ? Theme.CONTROL_HOVER : Theme.CONTROL;
        }
        Theme.fill(xPosition, yPosition, xPosition + width, yPosition + height, background);
        Theme.outline(
            xPosition,
            yPosition,
            xPosition + width,
            yPosition + height,
            hovered || active ? Theme.ACCENT : Theme.BORDER);

        int color = !enabled ? Theme.TEXT_DISABLED : danger ? Theme.DANGER : Theme.TEXT;
        String text = Theme.ellipsize(mc.fontRenderer, displayString, width - 6);
        Theme.centered(mc.fontRenderer, text, xPosition + width / 2, yPosition + (height - 8) / 2, color);
    }
}
