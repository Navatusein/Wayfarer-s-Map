package WayFarMap.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;

import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.Theme;

/** Asks for the height to teleport to when the ground at the target isn't known (unexplored, not loaded). */
public class GuiTeleportY extends GuiScreen {

    private static final int WIDTH = 220, HEIGHT = 78;
    private static final int ID_TELEPORT = 0, ID_CANCEL = 1;

    private final GuiScreen parent;
    private final int x, z;
    private FlatTextField yField;
    private FlatButton teleportButton;
    private int left, top;

    public GuiTeleportY(GuiScreen parent, int x, int z) {
        this.parent = parent;
        this.x = x;
        this.z = z;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        String text = yField != null ? yField.getText()
            : String.valueOf(mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.boundingBox.minY) : 64);
        yField = new FlatTextField(fontRendererObj, left + 10, top + 30, WIDTH - 20, 16);
        yField.setMaxStringLength(4);
        yField.setText(text);
        yField.setFocused(true);

        buttonList.clear();
        int w = (WIDTH - 24) / 2;
        teleportButton = new FlatButton(ID_TELEPORT, left + 10, top + 52, w, 18, I18n.format("wayfarmap.gui.teleport"));
        teleportButton.active = true;
        buttonList.add(teleportButton);
        buttonList.add(new FlatButton(ID_CANCEL, left + WIDTH - 10 - w, top + 52, w, 18, I18n.format("gui.cancel")));
        validate();
    }

    /** @return the entered height, or -1 if it isn't a valid Y */
    private int enteredY() {
        try {
            int y = Integer.parseInt(yField.getText()
                .trim());
            return y >= 0 && y <= 255 ? y : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void validate() {
        teleportButton.enabled = enteredY() >= 0 && Teleport.isAllowed();
    }

    private void teleport() {
        int y = enteredY();
        if (y < 0 || !Teleport.isAllowed()) {
            return;
        }
        mc.displayGuiScreen(null);
        Teleport.teleport(x, y, z);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_TELEPORT) {
            teleport();
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
            teleport();
            return;
        }
        // Digits and editing keys only.
        if (Character.isDigit(typedChar) || typedChar < 32 || keyCode == Keyboard.KEY_DELETE) {
            yField.textboxKeyTyped(typedChar, keyCode);
            validate();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        yField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void updateScreen() {
        yField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + WIDTH, top + HEIGHT);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.teleport_y_title"), left + 10, top + 8, Theme.ACCENT);
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, I18n.format("wayfarmap.gui.teleport_y_hint", x, z), WIDTH - 20),
            left + 10,
            top + 19,
            Theme.TEXT_MUTED);
        yField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
