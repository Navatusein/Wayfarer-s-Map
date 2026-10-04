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
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.waypoint.WaypointRenderer;
import cpw.mods.fml.common.registry.GameData;

/** Grid of every item in the game with a search field; used to pick a waypoint icon. */
public class GuiItemPicker extends ScaledScreen {

    public interface Callback {

        /** @param stack the chosen item, or null for "no icon" */
        void onPicked(ItemStack stack);
    }

    private static final int CELL = 18;
    /** How much lower the search and the grid are than under a plain title: room for the header. */
    private static final int SEARCH_DOWN = WindowHeader.HEIGHT - 12;

    /** All item stacks, built once per game session (it can be large in modpacks). */
    private static List<ItemStack> allStacks;
    private static List<String> allNames;

    private final GuiScreen parent;
    private final Callback callback;
    private final List<ItemStack> filtered = new ArrayList<>();

    private FlatTextField search;
    private int gridX, gridY, columns, rows;
    private int scrollRow;
    /** Where the grid is drawn while it eases to {@link #scrollRow} (in rows). */
    private final Smooth shownRow = new Smooth(0);

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
                if (stack == null || stack.getItem() == null || WaypointRenderer.isUndrawable(stack)) {
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
        rows = Math.max(3, (height - 90 - SEARCH_DOWN) / CELL);
        gridX = (width - columns * CELL) / 2;
        gridY = 44 + SEARCH_DOWN;

        // Made like the fields of the waypoint editor: a click on it gives the focus, a click elsewhere takes it away.
        // Not focused at first: typing starts once the field is clicked.
        String oldText = search != null ? search.getText() : "";
        search = new FlatTextField(fontRendererObj, gridX, 20 + SEARCH_DOWN, columns * CELL, 18);
        search.setHint(I18n.format("wayfarmap.gui.search"));
        search.setMaxStringLength(64);
        search.setText(oldText);

        buttonList.clear();
        int buttonY = gridY + rows * CELL + 8;
        int half = (columns * CELL - 4) / 2;
        buttonList.add(new FlatButton(0, gridX, buttonY, half, 18, I18n.format("wayfarmap.gui.no_icon")));
        buttonList.add(new FlatButton(1, gridX + columns * CELL - half, buttonY, half, 18, I18n.format("gui.cancel")));
        buttonList.add(WindowHeader.closeButton(1, gridX + columns * CELL + 8, 4));
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
        shownRow.set(0);
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
        // As in the waypoint editor: the key goes to the field if it has the focus; the list follows its text.
        if (search.isFocused()) {
            String before = search.getText();
            search.textboxKeyTyped(typedChar, keyCode);
            if (!before.equals(search.getText())) {
                applyFilter();
            }
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
        int row = (int) Math.floor(shownRow.get() + (mouseY - gridY) / (double) CELL);
        int index = row * columns + (mouseX - gridX) / CELL;
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
        WindowHeader.draw(
            fontRendererObj,
            gridX - 8,
            4,
            gridX + columns * CELL + 8,
            gridX + columns * CELL + 8 - WindowHeader.CLOSE_ROOM,
            Icons.ORE,
            I18n.format("wayfarmap.gui.pick_icon_title"),
            I18n.format("wayfarmap.gui.pick_icon_hint"),
            Theme.TEXT_MUTED,
            String.valueOf(filtered.size()));
        search.drawTextBox();

        Theme.fill(gridX, gridY, gridX + columns * CELL, gridY + rows * CELL, 0xFF0F1216);
        Theme.outline(gridX - 1, gridY - 1, gridX + columns * CELL + 1, gridY + rows * CELL + 1, Theme.BORDER);
        double shown = shownRow.update(scrollRow, 16);
        int hovered = stackIndexAt(mouseX, mouseY);
        int firstRow = (int) Math.floor(shown);
        // One row more than fits: the grid moves smoothly and is cut at its edges.
        Theme.clip(gridX, gridY, gridX + columns * CELL, gridY + rows * CELL);
        for (int i = 0; i < (rows + 1) * columns; i++) {
            int index = firstRow * columns + i;
            if (index >= filtered.size()) {
                break;
            }
            int cx = gridX + (i % columns) * CELL;
            int cy = gridY + (int) Math.round((firstRow + i / columns - shown) * CELL);
            if (index == hovered) {
                drawRect(cx, cy, cx + CELL, cy + CELL, Theme.CONTROL_HOVER);
                Theme.outline(cx, cy, cx + CELL, cy + CELL, Theme.ACCENT);
            }
            WaypointRenderer.drawItemDirect(filtered.get(index), cx + CELL / 2.0, cy + CELL / 2.0, 16f);
        }
        Theme.unclip();
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int totalRows = rows + maxScroll;
            int x = gridX + columns * CELL + 3;
            Theme.scrollbar(x, gridY, gridY + rows * CELL, rows, totalRows, shown / maxScroll, false);
        }


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
