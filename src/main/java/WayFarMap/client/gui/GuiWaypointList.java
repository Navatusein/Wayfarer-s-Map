package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointGroup;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;

/** All waypoints sorted into their groups; groups can be hidden, collapsed, renamed, reordered and deleted. */
public class GuiWaypointList extends ScaledScreen {

    private static final int ROW_HEIGHT = 20;
    private static final int ID_GROUP_ACTION = 0, ID_NEW_WAYPOINT = 1, ID_DONE = 2;
    private static final long CONFIRM_MS = 3000;
    /** Width taken by the "on the map" button in a waypoint row. */
    private static final int SHOW_ON_MAP_ROOM = 56;
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

    private FlatTextField groupField;
    private FlatButton groupActionButton;
    private WaypointGroup renamingGroup;
    /** Waypoint under the mouse when the left button went down; becomes a drag once the mouse moves. */
    private Waypoint pressedWaypoint;
    private int pressX, pressY;
    private boolean draggingWaypoint;
    private long lastAutoScroll;

    private Object pendingDelete;
    private long pendingDeleteTime;

    public GuiWaypointList(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelWidth = Math.min(width - 20, 420);
        listLeft = (width - panelWidth) / 2;
        listRight = listLeft + panelWidth;
        listTop = 28;
        listBottom = height - 58;

        buttonList.clear();
        int bottom = height - 50;
        String oldText = groupField != null ? groupField.getText() : "";
        groupField = new FlatTextField(fontRendererObj, listLeft, bottom, panelWidth - 124, 18)
            .setHint(I18n.format("wayfarmap.gui.new_group_hint"));
        groupField.setMaxStringLength(32);
        groupField.setText(oldText);
        groupActionButton = new FlatButton(ID_GROUP_ACTION, listRight - 120, bottom, 120, 18, "");
        buttonList.add(groupActionButton);
        int half = (panelWidth - 4) / 2;
        FlatButton newWaypoint = new FlatButton(
            ID_NEW_WAYPOINT,
            listLeft,
            bottom + 24,
            half,
            18,
            I18n.format("wayfarmap.gui.new_waypoint"));
        newWaypoint.active = true;
        buttonList.add(newWaypoint);
        buttonList.add(new FlatButton(ID_DONE, listRight - half, bottom + 24, half, 18, I18n.format("gui.done")));
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
        Row row = rowAt(mouseX, mouseY);
        if (row != null && row.waypoint != null) {
            pressedWaypoint = row.waypoint;
            pressX = mouseX;
            pressY = mouseY;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button == 0) {
            finishDrag(mouseX, mouseY);
        }
    }

    /**
     * Drops the dragged waypoint into the group under the mouse. Called from whichever notices the release first: the
     * frame (mouse state) or the tick (mouse event), since GUI mouse events only arrive 20 times per second.
     */
    private void finishDrag(int mouseX, int mouseY) {
        if (pressedWaypoint != null && draggingWaypoint) {
            Row target = rowAt(mouseX, mouseY);
            if (target != null) {
                String group = target.ungrouped ? null : target.group.name;
                if (group == null ? pressedWaypoint.group != null : !group.equals(pressedWaypoint.group)) {
                    WaypointManager.INSTANCE.moveToGroup(pressedWaypoint, group);
                    rebuildRows();
                }
            }
        }
        pressedWaypoint = null;
        draggingWaypoint = false;
    }

    private Row rowAt(int mouseX, int mouseY) {
        if (mouseX < listLeft || mouseX >= listRight || mouseY < listTop || mouseY >= listBottom) {
            return null;
        }
        int index = scroll + (mouseY - listTop) / ROW_HEIGHT;
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /** Whether the row belongs to the same group as the target row (header or waypoint). */
    private static boolean sameGroup(Row row, Row target) {
        return row.ungrouped == target.ungrouped && row.group == target.group;
    }

    private void updateDrag(int mouseX, int mouseY) {
        if (pressedWaypoint == null) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            finishDrag(mouseX, mouseY);
            return;
        }
        if (!draggingWaypoint && Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 3) {
            draggingWaypoint = true;
        }
        // Scroll while holding the waypoint near the top or bottom of the list.
        long now = System.currentTimeMillis();
        if (draggingWaypoint && now - lastAutoScroll > 80) {
            if (mouseY < listTop + 10 && scroll > 0) {
                scroll--;
                lastAutoScroll = now;
            } else if (mouseY > listBottom - 10 && scroll < maxScroll()) {
                scroll++;
                lastAutoScroll = now;
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
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(listLeft - 8, 6, listRight + 8, height - 6);
        Theme.text(fontRendererObj, I18n.format("wayfarmap.gui.waypoints"), listLeft, 14, Theme.ACCENT);
        Theme.fill(listLeft, listTop - 1, listRight, listBottom + 1, 0xFF0F1216);
        Theme.outline(listLeft - 1, listTop - 2, listRight + 1, listBottom + 2, Theme.BORDER);

        updateDrag(mouseX, mouseY);
        if (!draggingWaypoint) {
            String hint = I18n.format("wayfarmap.gui.drag_hint");
            Theme
                .text(fontRendererObj, hint, listRight - fontRendererObj.getStringWidth(hint), 14, Theme.TEXT_DISABLED);
        }
        Row dropTarget = draggingWaypoint ? rowAt(mouseX, mouseY) : null;

        hits.clear();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = listTop + i * ROW_HEIGHT;
            boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (row.waypoint == null) {
                drawGroupRow(row, y, mouseX, mouseY);
            } else {
                if (hovered && !draggingWaypoint) {
                    drawRect(listLeft, y, listRight, y + ROW_HEIGHT, Theme.ROW_HOVER);
                }
                drawWaypointRow(row.waypoint, y, mouseX, mouseY);
                if (draggingWaypoint && row.waypoint == pressedWaypoint) {
                    drawRect(listLeft, y, listRight, y + ROW_HEIGHT, 0x80101418);
                }
            }
            if (dropTarget != null && sameGroup(row, dropTarget)) {
                // Every row of the group the waypoint would land in.
                drawRect(listLeft, y, listLeft + 2, y + ROW_HEIGHT, Theme.ACCENT);
                if (row.waypoint == null) {
                    Theme.outline(listLeft, y, listRight, y + ROW_HEIGHT, Theme.ACCENT);
                }
            }
        }
        if (rows.size() > visibleRows) {
            // Scroll bar.
            int trackHeight = listBottom - listTop;
            int barHeight = Math.max(10, trackHeight * visibleRows / rows.size());
            int barY = listTop + (trackHeight - barHeight) * scroll / Math.max(1, maxScroll());
            drawRect(listRight - 3, barY, listRight - 1, barY + barHeight, Theme.BORDER);
        }

        groupField.drawTextBox();
        super.drawScaled(mouseX, mouseY, partialTicks);

        if (draggingWaypoint) {
            // The dragged waypoint follows the mouse.
            String name = pressedWaypoint.name.isEmpty() ? "-"
                : Theme.ellipsize(fontRendererObj, pressedWaypoint.name, 140);
            int w = fontRendererObj.getStringWidth(name) + 24;
            int x = mouseX + 6, y = mouseY - 6;
            Theme.fill(x, y, x + w, y + 14, Theme.PANEL);
            Theme.outline(x, y, x + w, y + 14, Theme.ACCENT);
            WaypointRenderer.drawMapMarker(pressedWaypoint, x + 8, y + 7, 9f, false);
            Theme.text(fontRendererObj, name, x + 17, y + 3, Theme.TEXT);
        }
    }

    private void drawGroupRow(Row row, int y, int mouseX, int mouseY) {
        WaypointManager manager = WaypointManager.INSTANCE;
        drawRect(listLeft, y, listRight, y + ROW_HEIGHT, Theme.PANEL_ALT);
        drawRect(listLeft, y + ROW_HEIGHT - 1, listRight, y + ROW_HEIGHT, Theme.BORDER);
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
        fontRendererObj.drawString(title, x, y + 6, visible ? Theme.TEXT : Theme.TEXT_DISABLED);
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
            Theme.DANGER,
            mouseX,
            mouseY,
            () -> confirmDelete(group, () -> manager.removeGroup(group)));
        bx = drawTextButton(bx, y + 4, I18n.format("wayfarmap.gui.rename"), Theme.TEXT, mouseX, mouseY, () -> {
            renamingGroup = group;
            groupField.setText(group.name);
            groupField.setFocused(true);
            updateGroupButton();
        });
        bx = drawTextButton(bx, y + 4, "v", Theme.TEXT, mouseX, mouseY, () -> {
            manager.moveGroup(group, 1);
            rebuildRows();
        });
        drawTextButton(bx, y + 4, "^", Theme.TEXT, mouseX, mouseY, () -> {
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
        String name = waypoint.name.isEmpty() ? "-" : Theme.ellipsize(fontRendererObj, waypoint.name, 110);
        fontRendererObj.drawString(name, x, y + 6, shown ? Theme.TEXT : Theme.TEXT_DISABLED);
        x += fontRendererObj.getStringWidth(name) + 6;

        String info = waypoint.x + " " + waypoint.y + " " + waypoint.z;
        if (mc.theWorld != null && waypoint.dimension != mc.theWorld.provider.dimensionId) {
            info += "  [DIM " + waypoint.dimension + "]";
        } else if (mc.thePlayer != null) {
            double dx = waypoint.x + 0.5 - mc.thePlayer.posX;
            double dz = waypoint.z + 0.5 - mc.thePlayer.posZ;
            info += "  " + (int) Math.sqrt(dx * dx + dz * dz) + "m";
        }
        // Leave room for the row's buttons on the right.
        info = Theme.ellipsize(fontRendererObj, info, Math.max(0, listRight - 184 - SHOW_ON_MAP_ROOM - x));
        fontRendererObj.drawString(info, x, y + 6, Theme.TEXT_MUTED);

        int bx = listRight - 4;
        bx = drawTextButton(
            bx,
            y + 4,
            I18n.format(isPendingDelete(waypoint) ? "wayfarmap.gui.confirm" : "wayfarmap.gui.delete"),
            Theme.DANGER,
            mouseX,
            mouseY,
            () -> confirmDelete(waypoint, () -> manager.removeWaypoint(waypoint)));
        bx = drawTextButton(
            bx,
            y + 4,
            I18n.format("wayfarmap.gui.edit"),
            Theme.TEXT,
            mouseX,
            mouseY,
            () -> mc.displayGuiScreen(GuiEditWaypoint.edit(this, waypoint)));
        bx = drawTextButton(
            bx,
            y + 4,
            I18n.format("wayfarmap.gui.show_on_map"),
            Theme.TEXT,
            mouseX,
            mouseY,
            () -> mc.displayGuiScreen(GuiWorldMap.showing(waypoint)));
        // Teleporting needs /tp permission and the same dimension.
        if (Teleport.isAllowed() && mc.theWorld != null && waypoint.dimension == mc.theWorld.provider.dimensionId) {
            drawTextButton(bx, y + 4, I18n.format("wayfarmap.gui.teleport"), Theme.ACCENT, mouseX, mouseY, () -> {
                mc.displayGuiScreen(null);
                Teleport.teleport(waypoint.x, waypoint.y, waypoint.z);
            });
        }
    }

    private void drawCheckbox(int x, int y, boolean checked, int mouseX, int mouseY, Runnable action) {
        boolean hovered = mouseX >= x && mouseX < x + 10 && mouseY >= y && mouseY < y + 10;
        drawRect(x, y, x + 10, y + 10, checked ? Theme.ACCENT : Theme.CONTROL);
        Theme.outline(x, y, x + 10, y + 10, hovered ? Theme.TEXT : checked ? Theme.ACCENT : Theme.BORDER);
        if (checked) {
            drawRect(x + 3, y + 3, x + 7, y + 7, Theme.TEXT);
        }
        hits.add(new Hit(x - 1, y - 1, x + 11, y + 11, action));
    }

    /** Draws a small button ending at {@code right}; returns the x where the next button (to its left) ends. */
    private int drawTextButton(int right, int y, String label, int color, int mouseX, int mouseY, Runnable action) {
        int w = fontRendererObj.getStringWidth(label) + 8;
        int x0 = right - w;
        boolean hovered = mouseX >= x0 && mouseX < right && mouseY >= y && mouseY < y + 12;
        drawRect(x0, y, right, y + 12, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
        Theme.outline(x0, y, right, y + 12, hovered ? Theme.ACCENT : Theme.BORDER);
        fontRendererObj.drawString(label, x0 + 4, y + 2, color);
        hits.add(new Hit(x0, y, right, y + 12, action));
        return x0 - 3;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
