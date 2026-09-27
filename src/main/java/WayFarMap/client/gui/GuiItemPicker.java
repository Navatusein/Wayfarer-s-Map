package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.waypoint.WaypointRenderer;
import cpw.mods.fml.common.registry.GameData;

/** Grid of every item in the game with a search field; used to pick a waypoint icon. */
public class GuiItemPicker extends ScaledScreen {

    public interface Callback {

        /** @param stack the chosen item, or null for "no icon" */
        void onPicked(ItemStack stack);
    }

    private static final int CELL = 18;

    /** All item stacks, built once per game session (it can be large in modpacks). */
    private static List<ItemStack> allStacks;
    private static List<String> allNames;

    private final GuiScreen parent;
    private final Callback callback;
    private final List<ItemStack> filtered = new ArrayList<>();

    private FlatTextField search;
    private int gridX, gridY, columns, rows;
    private int scrollRow;

    public GuiItemPicker(GuiScreen parent, Callback callback) {
        this.parent = parent;
        this.callback = callback;
    }

    @SuppressWarnings("unchecked")
    private static void buildItemList() {
        if (allStacks != null) {
            return;
        }
        allStacks = new ArrayList<>();
        allNames = new ArrayList<>();
        for (Object o : GameData.getItemRegistry()) {
            Item item = (Item) o;
            List<ItemStack> subItems = new ArrayList<>();
            try {
                item.getSubItems(item, item.getCreativeTab(), subItems);
            } catch (Throwable ignored) {}
            if (subItems.isEmpty()) {
                subItems.add(new ItemStack(item));
            }
            for (ItemStack stack : subItems) {
                if (stack == null || stack.getItem() == null) {
                    continue;
                }
                String name;
                try {
                    name = stack.getDisplayName();
                } catch (Throwable t) {
                    name = String.valueOf(
                        GameData.getItemRegistry()
                            .getNameForObject(item));
                }
                allStacks.add(stack);
                allNames.add(
                    (name + " "
                        + GameData.getItemRegistry()
                            .getNameForObject(item)).toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    public void initGui() {
        buildItemList();
        Keyboard.enableRepeatEvents(true);
        columns = Math.max(4, Math.min(24, (width - 40) / CELL));
        rows = Math.max(3, (height - 90) / CELL);
        gridX = (width - columns * CELL) / 2;
        gridY = 44;

        String oldText = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, gridX, 22, columns * CELL, 16)
            .setHint(I18n.format("wayfarmap.gui.search"));
        search.setMaxStringLength(64);
        search.setText(oldText);
        search.setFocused(true);

        buttonList.clear();
        int buttonY = gridY + rows * CELL + 8;
        int half = (columns * CELL - 4) / 2;
        buttonList.add(new FlatButton(0, gridX, buttonY, half, 18, I18n.format("wayfarmap.gui.no_icon")));
        buttonList.add(new FlatButton(1, gridX + columns * CELL - half, buttonY, half, 18, I18n.format("gui.cancel")));
        applyFilter();
    }

    private void applyFilter() {
        filtered.clear();
        String query = search.getText()
            .trim()
            .toLowerCase(Locale.ROOT);
        for (int i = 0; i < allStacks.size(); i++) {
            if (query.isEmpty() || allNames.get(i)
                .contains(query)) {
                filtered.add(allStacks.get(i));
            }
        }
        scrollRow = 0;
    }

    private int maxScroll() {
        int totalRows = (filtered.size() + columns - 1) / columns;
        return Math.max(0, totalRows - rows);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            callback.onPicked(null);
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (search.textboxKeyTyped(typedChar, keyCode)) {
            applyFilter();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow + (wheel > 0 ? -2 : 2)));
        }
    }

    private int stackIndexAt(int mouseX, int mouseY) {
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + columns * CELL || mouseY >= gridY + rows * CELL) {
            return -1;
        }
        int index = ((mouseY - gridY) / CELL + scrollRow) * columns + (mouseX - gridX) / CELL;
        return index < filtered.size() ? index : -1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        int index = stackIndexAt(mouseX, mouseY);
        if (index >= 0 && button == 0) {
            callback.onPicked(filtered.get(index));
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(gridX - 8, 4, gridX + columns * CELL + 8, gridY + rows * CELL + 34);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.pick_icon"), gridX, 11, Theme.ACCENT);
        search.drawTextBox();

        Theme.fill(gridX, gridY, gridX + columns * CELL, gridY + rows * CELL, 0xFF0F1216);
        Theme.outline(gridX - 1, gridY - 1, gridX + columns * CELL + 1, gridY + rows * CELL + 1, Theme.BORDER);
        int hovered = stackIndexAt(mouseX, mouseY);
        int first = scrollRow * columns;
        for (int i = 0; i < rows * columns; i++) {
            int index = first + i;
            if (index >= filtered.size()) {
                break;
            }
            int cx = gridX + (i % columns) * CELL;
            int cy = gridY + (i / columns) * CELL;
            if (index == hovered) {
                drawRect(cx, cy, cx + CELL, cy + CELL, Theme.CONTROL_HOVER);
                Theme.outline(cx, cy, cx + CELL, cy + CELL, Theme.ACCENT);
            }
            WaypointRenderer.drawItem(filtered.get(index), cx + CELL / 2.0, cy + CELL / 2.0, 16f);
        }

        String count = filtered.size() + "";
        Theme.text(
            fontRendererObj,
            count,
            gridX + columns * CELL - fontRendererObj.getStringWidth(count),
            11,
            Theme.TEXT_MUTED);

        super.drawScaled(mouseX, mouseY, partialTicks);

        if (hovered >= 0) {
            List<String> tooltip = new ArrayList<>();
            try {
                tooltip.add(
                    filtered.get(hovered)
                        .getDisplayName());
            } catch (Throwable t) {
                tooltip.add("?");
            }
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
