package WayFarMap.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.Config;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.Theme;

/**
 * Puts the minimap anywhere on screen: it is dragged with the mouse (or moved with the arrow keys), can't leave the
 * screen and sticks to the edges and the middle when brought close to them. Laid out in the HUD's GUI pixels, not the
 * mod's screen scale, so the box matches the minimap drawn under it.
 */
public class GuiMinimapPosition extends GuiScreen {

    /** How close to an edge or the middle (GUI pixels) the minimap is pulled onto it. */
    private static final int SNAP = 8;
    private static final int ID_DONE = 0, ID_RESET = 1;

    private final GuiScreen parent;
    private boolean dragging;
    /** Where in the minimap it was grabbed. */
    private int grabX, grabY;
    /** The minimap is on the middle line across / down the screen: shown as a guide while dragging. */
    private boolean centeredX, centeredY;

    public GuiMinimapPosition(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int middle = width / 2;
        int y = height / 2 + 14;
        buttonList.add(new FlatButton(ID_RESET, middle - 82, y, 80, 18, I18n.format("wayfarmap.settings.reset")));
        buttonList.add(new FlatButton(ID_DONE, middle + 2, y, 80, 18, I18n.format("gui.done")));
    }

    private int boxLeft() {
        return MinimapRenderer.left(width);
    }

    private int boxTop() {
        return MinimapRenderer.top(height);
    }

    /** Room the minimap can move in, across and down. */
    private int freeWidth() {
        return Math.max(0, width - 2 * MinimapRenderer.MARGIN - Config.minimapSize);
    }

    private int freeHeight() {
        return Math.max(0, height - 2 * MinimapRenderer.MARGIN - MinimapRenderer.boxHeight());
    }

    /** Puts the minimap's top left at (x, y), kept on screen and pulled onto the edges and the middle nearby. */
    private void moveTo(int x, int y, boolean snap) {
        int freeX = freeWidth(), freeY = freeHeight();
        int dx = Math.max(0, Math.min(freeX, x - MinimapRenderer.MARGIN));
        int dy = Math.max(0, Math.min(freeY, y - MinimapRenderer.MARGIN));
        centeredX = centeredY = false;
        if (snap) {
            dx = snapped(dx, freeX);
            dy = snapped(dy, freeY);
            centeredX = freeX > 0 && dx * 2 == freeX;
            centeredY = freeY > 0 && dy * 2 == freeY;
        }
        Config.PositionOption.set(freeX > 0 ? dx / (double) freeX : 0, freeY > 0 ? dy / (double) freeY : 0);
    }

    /** The offset pulled onto 0, the end of the room or its middle when it is near one of them. */
    private static int snapped(int offset, int room) {
        if (offset < SNAP) {
            return 0;
        }
        if (offset > room - SNAP) {
            return room;
        }
        if (Math.abs(offset * 2 - room) < SNAP * 2) {
            return room / 2;
        }
        return offset;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_RESET) {
            for (Config.Option option : Config.OPTIONS) {
                if (option instanceof Config.PositionOption) {
                    option.reset();
                }
            }
        } else if (button.id == ID_DONE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        for (Object o : buttonList) {
            if (((GuiButton) o).mousePressed(mc, mouseX, mouseY)) {
                super.mouseClicked(mouseX, mouseY, button);
                return;
            }
        }
        int x = boxLeft(), y = boxTop();
        int x1 = x + Config.minimapSize, y1 = y + MinimapRenderer.boxHeight();
        if (button == 0 && Theme.inside(mouseX, mouseY, x - 2, y - 2, x1 + 2, y1)) {
            dragging = true;
            grabX = mouseX - x;
            grabY = mouseY - y;
        }
    }

    /**
     * Follows the mouse while the minimap is dragged. Called every frame, before the HUD draws the minimap: mouse
     * events only come in game ticks (20 a second), which made the minimap move in jerks behind the cursor.
     */
    public void updateDrag() {
        if (!dragging) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            dragging = false;
            centeredX = centeredY = false;
            return;
        }
        int mouseX = Mouse.getX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getY() * height / mc.displayHeight - 1;
        // Shift moves freely, without pulling it onto the edges and the middle.
        moveTo(mouseX - grabX, mouseY - grabY, !isShiftKeyDown());
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        int step = isShiftKeyDown() ? 10 : 1;
        int dx = 0, dy = 0;
        if (keyCode == Keyboard.KEY_LEFT) {
            dx = -step;
        } else if (keyCode == Keyboard.KEY_RIGHT) {
            dx = step;
        } else if (keyCode == Keyboard.KEY_UP) {
            dy = -step;
        } else if (keyCode == Keyboard.KEY_DOWN) {
            dy = step;
        } else if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (dx != 0 || dy != 0) {
            moveTo(boxLeft() + dx, boxTop() + dy, false);
        }
    }

    @Override
    public void onGuiClosed() {
        Config.save();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // The minimap itself is drawn by the HUD under this screen; here only its outline and the hints.
        updateDrag();
        int x = boxLeft(), y = boxTop();
        int x1 = x + Config.minimapSize, y1 = y + MinimapRenderer.boxHeight();
        if (centeredX) {
            Theme.fill(width / 2, 0, width / 2 + 1, height, Theme.ACCENT_DIM);
        }
        if (centeredY) {
            int middle = MinimapRenderer.MARGIN + freeHeight() / 2 + MinimapRenderer.boxHeight() / 2;
            Theme.fill(0, middle, width, middle + 1, Theme.ACCENT_DIM);
        }
        if (!Config.minimapEnabled) {
            Theme.fill(x, y, x1, y1, Theme.PANEL);
        }
        boolean hovered = dragging || Theme.inside(mouseX, mouseY, x - 2, y - 2, x1 + 2, y1);
        Theme.fill(x - 3, y - 3, x1 + 3, y1 + 1, hovered ? 0x304C9AFF : 0x184C9AFF);
        Theme.outline(x - 3, y - 3, x1 + 3, y1 + 1, hovered ? Theme.ACCENT : Theme.ACCENT_DIM);

        String title = I18n.format("wayfarmap.minimap_position.title");
        List<?> hints = fontRendererObj.listFormattedStringToWidth(I18n.format("wayfarmap.minimap_position.hint"), 220);
        int panelTop = height / 2 - 18 - hints.size() * 10, panelBottom = height / 2 + 38;
        Theme.panel(width / 2 - 120, panelTop, width / 2 + 120, panelBottom);
        Theme.centered(fontRendererObj, title, width / 2, panelTop + 6, Theme.ACCENT);
        for (int i = 0; i < hints.size(); i++) {
            String hint = String.valueOf(hints.get(i));
            Theme.centered(fontRendererObj, hint, width / 2, panelTop + 20 + i * 10, Theme.TEXT_MUTED);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
