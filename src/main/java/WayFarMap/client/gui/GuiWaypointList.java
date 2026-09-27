package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointGroup;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;

/** All waypoints sorted into their groups; groups can be hidden, collapsed, renamed, reordered and deleted. */
public class GuiWaypointList extends GuiScreen {

    private static final int ROW_HEIGHT = 20;
    private static final int ID_GROUP_ACTION = 0, ID_NEW_WAYPOINT = 1, ID_DONE = 2;
    private static final long CONFIRM_MS = 3000;
    private static final String UNGROUPED_KEY = "\u0000ungrouped";

    /** Collapsed groups (by name) stay collapsed while the game runs. */
    private static final Set<String> collapsed = new HashSet<>();

    private final GuiScreen parent;

    /** One line of the list: a group header (waypoint == null) or a waypoint. */
    private static class Row {

        final WaypointGroup group;
        final boolean ungrouped;
        final Waypoint waypoint;

        Row(WaypointGroup group, boolean ungrouped, Waypoint waypoint) {
            this.group = group;
            this.ungrouped = ungrouped;
            this.waypoint = waypoint;
        }
    }

    /** Clickable area registered while drawing. */
    private static class Hit {

        final int x0, y0, x1, y1;
        final Runnable action;

        Hit(int x0, int y0, int x1, int y1, Runnable action) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.action = action;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int listLeft, listRight, listTop, listBottom;
    private int scroll;

    private GuiTextField groupField;
    private GuiButton groupActionButton;
    private WaypointGroup renamingGroup;
    private Object pendingDelete;
    private long pendingDeleteTime;

    public GuiWaypointList(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelWidth = Math.min(width - 20, 380);
        listLeft = (width - panelWidth) / 2;
        listRight = listLeft + panelWidth;
        listTop = 24;
        listBottom = height - 56;

        buttonList.clear();
        int bottom = height - 50;
        String oldText = groupField != null ? groupField.getText() : "";
        groupField = new GuiTextField(fontRendererObj, listLeft, bottom + 1, panelWidth - 124, 18);
        groupField.setMaxStringLength(32);
        groupField.setText(oldText);
        groupActionButton = new GuiButton(ID_GROUP_ACTION, listRight - 120, bottom, 120, 20, "");
        buttonList.add(groupActionButton);
        buttonList.add(
            new GuiButton(ID_NEW_WAYPOINT, width / 2 - 154, bottom + 24, 150, 20, I18n.format("wayfarmap.gui.new_waypoint")));
        buttonList.add(new GuiButton(ID_DONE, width / 2 + 4, bottom + 24, 150, 20, I18n.format("gui.done")));
        updateGroupButton();
        rebuildRows();
    }

    private void updateGroupButton() {
        groupActionButton.displayString = I18n
            .format(renamingGroup != null ? "wayfarmap.gui.rename_group" : "wayfarmap.gui.create_group");
    }

    private void rebuildRows() {
        rows.clear();
        WaypointManager manager = WaypointManager.INSTANCE;
        for (WaypointGroup group : manager.getGroups()) {
            rows.add(new Row(group, false, null));
            if (!collapsed.contains(group.name)) {
                for (Waypoint waypoint : manager.getWaypointsInGroup(group.name)) {
                    rows.add(new Row(group, false, waypoint));
                }
            }
        }
        rows.add(new Row(null, true, null));
        if (!collapsed.contains(UNGROUPED_KEY)) {
            for (Waypoint waypoint : manager.getWaypointsInGroup(null)) {
                rows.add(new Row(null, true, waypoint));
            }
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int maxScroll() {
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        return Math.max(0, rows.size() - visibleRows);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_GROUP_ACTION:
                applyGroupField();
                break;
            case ID_NEW_WAYPOINT:
                if (mc.thePlayer != null) {
                    mc.displayGuiScreen(
                        GuiEditWaypoint.create(
                            this,
                            MathHelper.floor_double(mc.thePlayer.posX),
                            MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
                            MathHelper.floor_double(mc.thePlayer.posZ),
                            mc.theWorld.provider.dimensionId));
                }
                break;
            case ID_DONE:
                mc.displayGuiScreen(parent);
                break;
            default:
                break;
        }
    }

    private void applyGroupField() {
        String name = groupField.getText()
            .trim();
        if (name.isEmpty()) {
            return;
        }
        if (renamingGroup != null) {
            String oldName = renamingGroup.name;
            if (WaypointManager.INSTANCE.renameGroup(renamingGroup, name) && collapsed.remove(oldName)) {
                collapsed.add(renamingGroup.name);
            }
            renamingGroup = null;
        } else {
            WaypointManager.INSTANCE.createGroup(name);
        }
        groupField.setText("");
        updateGroupButton();
        rebuildRows();
    }

    /** Runs {@code action} on the second click within a few seconds on the same object. */
    private void confirmDelete(Object object, Runnable action) {
        long now = System.currentTimeMillis();
        if (pendingDelete == object && now - pendingDeleteTime < CONFIRM_MS) {
            pendingDelete = null;
            action.run();
            rebuildRows();
        } else {
            pendingDelete = object;
            pendingDeleteTime = now;
        }
    }

    private boolean isPendingDelete(Object object) {
        return pendingDelete == object && System.currentTimeMillis() - pendingDeleteTime < CONFIRM_MS;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (renamingGroup != null) {
                renamingGroup = null;
                groupField.setText("");
                updateGroupButton();
            } else {
                mc.displayGuiScreen(parent);
            }
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && groupField.isFocused()) {
            applyGroupField();
            return;
        }
        groupField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (wheel > 0 ? -1 : 1)));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        groupField.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        for (Hit hit : new ArrayList<>(hits)) {
            if (mouseX >= hit.x0 && mouseX < hit.x1 && mouseY >= hit.y0 && mouseY < hit.y1) {
                hit.action.run();
                return;
            }
        }
    }

    @Override
    public void updateScreen() {
        groupField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("wayfarmap.gui.waypoints"), width / 2, 8, 0xFFFFFF);
        drawRect(listLeft - 1, listTop - 1, listRight + 1, listBottom + 1, 0xFF000000);
        drawRect(listLeft, listTop, listRight, listBottom, 0xC0101010);

        hits.clear();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = listTop + i * ROW_HEIGHT;
            boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (row.waypoint == null) {
                drawGroupRow(row, y, mouseX, mouseY);
            } else {
                if (hovered) {
                    drawRect(listLeft, y, listRight, y + ROW_HEIGHT, 0x30FFFFFF);
                }
                drawWaypointRow(row.waypoint, y, mouseX, mouseY);
            }
        }
        if (rows.size() > visibleRows) {
            // Scroll bar.
            int trackHeight = listBottom - listTop;
            int barHeight = Math.max(10, trackHeight * visibleRows / rows.size());
            int barY = listTop + (trackHeight - barHeight) * scroll / Math.max(1, maxScroll());
            drawRect(listRight - 3, barY, listRight - 1, barY + barHeight, 0xFF808080);
        }

        groupField.drawTextBox();
        if (groupField.getText()
            .isEmpty() && !groupField.isFocused()) {
            fontRendererObj
                .drawString(I18n.format("wayfarmap.gui.new_group_hint"), listLeft + 4, height - 44, 0x707070);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawGroupRow(Row row, int y, int mouseX, int mouseY) {
        WaypointManager manager = WaypointManager.INSTANCE;
        drawRect(listLeft, y, listRight, y + ROW_HEIGHT, 0x60404060);
        String key = row.ungrouped ? UNGROUPED_KEY : row.group.name;
        boolean visible = row.ungrouped ? manager.isUngroupedVisible() : row.group.visible;
        final WaypointGroup group = row.group;

        int x = listLeft + 4;
        drawCheckbox(x, y + 5, visible, mouseX, mouseY, () -> {
            if (group == null) {
                manager.setUngroupedVisible(!manager.isUngroupedVisible());
            } else {
                manager.setGroupVisible(group, !group.visible);
            }
        });
        x += 14;

        boolean isCollapsed = collapsed.contains(key);
        String title = (isCollapsed ? "+ " : "- ")
            + (row.ungrouped ? I18n.format("wayfarmap.gui.no_group") : group.name)
            + " ("
            + manager.getWaypointsInGroup(row.ungrouped ? null : group.name)
                .size()
            + ")";
        int titleWidth = fontRendererObj.getStringWidth(title);
        fontRendererObj.drawStringWithShadow(title, x, y + 6, visible ? 0xFFFFA0 : 0x808080);
        hits.add(new Hit(x, y, x + titleWidth, y + ROW_HEIGHT, () -> {
            if (!collapsed.remove(key)) {
                collapsed.add(key);
            }
            rebuildRows();
        }));

        if (row.ungrouped) {
            return;
        }
        int bx = listRight - 4;
        bx = drawTextButton(
            bx,
            y + 4,
            I18n.format(isPendingDelete(group) ? "wayfarmap.gui.confirm" : "wayfarmap.gui.delete"),
            0xFF5555,
            mouseX,
            mouseY,
            () -> confirmDelete(group, () -> manager.removeGroup(group)));
        bx = drawTextButton(bx, y + 4, I18n.format("wayfarmap.gui.rename"), 0xFFFFFF, mouseX, mouseY, () -> {
            renamingGroup = group;
            groupField.setText(group.name);
            groupField.setFocused(true);
            updateGroupButton();
        });
        bx = drawTextButton(bx, y + 4, "v", 0xFFFFFF, mouseX, mouseY, () -> {
            manager.moveGroup(group, 1);
            rebuildRows();
        });
        drawTextButton(bx, y + 4, "^", 0xFFFFFF, mouseX, mouseY, () -> {
            manager.moveGroup(group, -1);
            rebuildRows();
        });
    }

    private void drawWaypointRow(final Waypoint waypoint, int y, int mouseX, int mouseY) {
        WaypointManager manager = WaypointManager.INSTANCE;
        int x = listLeft + 16;
        drawCheckbox(x, y + 5, waypoint.enabled, mouseX, mouseY, () -> {
            waypoint.enabled = !waypoint.enabled;
            manager.waypointChanged();
        });
        x += 14;
        WaypointRenderer.drawMapMarker(waypoint, x + 7, y + ROW_HEIGHT / 2.0, 12f, false);
        x += 18;

        boolean shown = manager.isVisible(waypoint);
        String name = waypoint.name.isEmpty() ? "-" : waypoint.name;
        fontRendererObj.drawStringWithShadow(name, x, y + 6, shown ? 0xFFFFFF : 0x808080);
        x += fontRendererObj.getStringWidth(name) + 6;

        String info = waypoint.x + " " + waypoint.y + " " + waypoint.z;
        if (mc.theWorld != null && waypoint.dimension != mc.theWorld.provider.dimensionId) {
            info += "  [DIM " + waypoint.dimension + "]";
        } else if (mc.thePlayer != null) {
            double dx = waypoint.x + 0.5 - mc.thePlayer.posX;
            double dz = waypoint.z + 0.5 - mc.thePlayer.posZ;
            info += "  " + (int) Math.sqrt(dx * dx + dz * dz) + "m";
        }
        fontRendererObj.drawString(info, x, y + 6, 0x909090);

        int bx = listRight - 4;
        bx = drawTextButton(
            bx,
            y + 4,
            I18n.format(isPendingDelete(waypoint) ? "wayfarmap.gui.confirm" : "wayfarmap.gui.delete"),
            0xFF5555,
            mouseX,
            mouseY,
            () -> confirmDelete(waypoint, () -> manager.removeWaypoint(waypoint)));
        drawTextButton(
            bx,
            y + 4,
            I18n.format("wayfarmap.gui.edit"),
            0xFFFFFF,
            mouseX,
            mouseY,
            () -> mc.displayGuiScreen(GuiEditWaypoint.edit(this, waypoint)));
    }

    private void drawCheckbox(int x, int y, boolean checked, int mouseX, int mouseY, Runnable action) {
        boolean hovered = mouseX >= x && mouseX < x + 10 && mouseY >= y && mouseY < y + 10;
        drawRect(x, y, x + 10, y + 10, hovered ? 0xFFFFFFFF : 0xFFA0A0A0);
        drawRect(x + 1, y + 1, x + 9, y + 9, 0xFF202020);
        if (checked) {
            drawRect(x + 2, y + 2, x + 8, y + 8, 0xFF55FF55);
        }
        hits.add(new Hit(x - 1, y - 1, x + 11, y + 11, action));
    }

    /** Draws a small button ending at {@code right}; returns the x where the next button (to its left) ends. */
    private int drawTextButton(int right, int y, String label, int color, int mouseX, int mouseY, Runnable action) {
        int w = fontRendererObj.getStringWidth(label) + 8;
        int x0 = right - w;
        boolean hovered = mouseX >= x0 && mouseX < right && mouseY >= y && mouseY < y + 12;
        drawRect(x0, y, right, y + 12, hovered ? 0xFF606060 : 0xFF383838);
        fontRendererObj.drawString(label, x0 + 4, y + 2, color);
        hits.add(new Hit(x0, y, right, y + 12, action));
        return x0 - 3;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
