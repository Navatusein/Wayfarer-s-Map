package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointGroup;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;

/** Creates or edits a waypoint: name, coordinates, group, icon and outline color. */
public class GuiEditWaypoint extends GuiScreen {

    private static final int[] PALETTE = { 0xFFFFFF, 0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x00AA00, 0x55FFFF,
        0x00AAAA, 0x5555FF, 0x0000AA, 0xFF55FF, 0xAA00AA, 0xAAAAAA, 0x555555, 0x000000, 0xAA5500 };
    private static final int DEFAULT_OUTLINE = 0xFF5555;
    private static final int SWATCH = 13;

    private static final int ID_GROUP = 1, ID_NEW_GROUP = 2, ID_ICON = 3, ID_OUTLINE = 4, ID_SAVE = 5, ID_DELETE = 6,
        ID_CANCEL = 7;

    private final GuiScreen parent;
    /** Waypoint being edited, or null when creating a new one. */
    private final Waypoint target;
    /** Working copy; written to {@link #target} on save. */
    private final Waypoint edited;
    /** Last outline color, remembered while the outline is switched off. */
    private int outlineColor;

    private GuiTextField nameField, xField, yField, zField, newGroupField, colorField;
    private final List<GuiTextField> fields = new ArrayList<>();
    private GuiButton deleteButton;
    private boolean confirmDelete;
    private int left, top;

    /** Editor for a new waypoint at the given position. */
    public static GuiEditWaypoint create(GuiScreen parent, int x, int y, int z, int dimension) {
        return new GuiEditWaypoint(parent, null, new Waypoint("", x, y, z, dimension));
    }

    public static GuiEditWaypoint edit(GuiScreen parent, Waypoint waypoint) {
        return new GuiEditWaypoint(parent, waypoint, waypoint.copy());
    }

    private GuiEditWaypoint(GuiScreen parent, Waypoint target, Waypoint edited) {
        this.parent = parent;
        this.target = target;
        this.edited = edited;
        this.outlineColor = edited.outlineColor != null ? edited.outlineColor : DEFAULT_OUTLINE;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = width / 2 - 110;
        top = Math.max(4, height / 2 - 118);
        fields.clear();
        buttonList.clear();

        nameField = field(left, top + 24, 220, edited.name, 48);
        xField = field(left, top + 58, 70, String.valueOf(edited.x), 9);
        yField = field(left + 75, top + 58, 70, String.valueOf(edited.y), 4);
        zField = field(left + 150, top + 58, 70, String.valueOf(edited.z), 9);
        newGroupField = field(left, top + 107, 170, "", 32);
        colorField = field(left + 144, top + 155, 76, String.format("#%06X", outlineColor), 7);
        nameField.setFocused(true);

        buttonList.add(new GuiButton(ID_GROUP, left, top + 82, 220, 20, ""));
        buttonList.add(new GuiButton(ID_NEW_GROUP, left + 174, top + 106, 46, 20, "+"));
        buttonList.add(new GuiButton(ID_ICON, left, top + 130, 220, 20, ""));
        buttonList.add(new GuiButton(ID_OUTLINE, left, top + 154, 140, 20, ""));
        buttonList.add(new GuiButton(ID_SAVE, left, top + 198, 70, 20, I18n.format("wayfarmap.gui.save")));
        deleteButton = new GuiButton(ID_DELETE, left + 75, top + 198, 70, 20, "");
        deleteButton.enabled = target != null;
        buttonList.add(deleteButton);
        buttonList.add(new GuiButton(ID_CANCEL, left + 150, top + 198, 70, 20, I18n.format("gui.cancel")));
        updateButtons();
    }

    private GuiTextField field(int x, int y, int width, String text, int maxLength) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, y, width, 18);
        field.setMaxStringLength(maxLength);
        field.setText(text);
        fields.add(field);
        return field;
    }

    private void updateButtons() {
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            switch (button.id) {
                case ID_GROUP:
                    button.displayString = I18n.format("wayfarmap.gui.group") + ": "
                        + (edited.group == null ? I18n.format("wayfarmap.gui.no_group") : edited.group);
                    break;
                case ID_ICON:
                    ItemStack icon = edited.getIcon();
                    button.displayString = I18n.format("wayfarmap.gui.icon") + ": "
                        + (icon == null ? I18n.format("wayfarmap.gui.none") : safeName(icon));
                    break;
                case ID_OUTLINE:
                    button.displayString = I18n.format("wayfarmap.gui.outline") + ": "
                        + I18n.format(edited.outlineColor != null ? "options.on" : "options.off");
                    break;
                case ID_DELETE:
                    button.displayString = I18n.format(confirmDelete ? "wayfarmap.gui.confirm" : "wayfarmap.gui.delete");
                    break;
                default:
                    break;
            }
        }
        colorField.setEnabled(edited.outlineColor != null);
    }

    private static String safeName(ItemStack stack) {
        try {
            return stack.getDisplayName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Copies the text fields into the working copy. Returns false if a coordinate is not a number. */
    private boolean readFields() {
        edited.name = nameField.getText()
            .trim();
        try {
            edited.x = Integer.parseInt(xField.getText()
                .trim());
            edited.y = Integer.parseInt(yField.getText()
                .trim());
            edited.z = Integer.parseInt(zField.getText()
                .trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<String> groupOptions() {
        List<String> options = new ArrayList<>();
        options.add(null);
        for (WaypointGroup group : WaypointManager.INSTANCE.getGroups()) {
            options.add(group.name);
        }
        return options;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id != ID_DELETE) {
            confirmDelete = false;
        }
        switch (button.id) {
            case ID_GROUP: {
                List<String> options = groupOptions();
                int index = options.indexOf(edited.group);
                edited.group = options.get((index + 1) % options.size());
                break;
            }
            case ID_NEW_GROUP: {
                String name = newGroupField.getText()
                    .trim();
                if (!name.isEmpty()) {
                    edited.group = WaypointManager.INSTANCE.createGroup(name).name;
                    newGroupField.setText("");
                }
                break;
            }
            case ID_ICON:
                readFields();
                mc.displayGuiScreen(new GuiItemPicker(this, stack -> {
                    edited.setIcon(stack);
                }));
                return;
            case ID_OUTLINE:
                edited.outlineColor = edited.outlineColor == null ? (Integer) outlineColor : null;
                break;
            case ID_SAVE:
                save();
                return;
            case ID_DELETE:
                if (!confirmDelete) {
                    confirmDelete = true;
                } else {
                    WaypointManager.INSTANCE.removeWaypoint(target);
                    mc.displayGuiScreen(parent);
                    return;
                }
                break;
            case ID_CANCEL:
                mc.displayGuiScreen(parent);
                return;
            default:
                break;
        }
        updateButtons();
    }

    private void save() {
        if (!readFields()) {
            return;
        }
        if (target == null) {
            WaypointManager.INSTANCE.addWaypoint(edited);
        } else {
            target.copyFrom(edited);
            WaypointManager.INSTANCE.waypointChanged();
        }
        mc.displayGuiScreen(parent);
    }

    private void setOutline(int color) {
        outlineColor = color & 0xFFFFFF;
        edited.outlineColor = outlineColor;
        colorField.setText(String.format("#%06X", outlineColor));
        updateButtons();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (newGroupField.isFocused()) {
                actionPerformed((GuiButton) buttonList.get(1));
            } else {
                save();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            // Move focus to the next text field.
            int focused = -1;
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i)
                    .isFocused()) {
                    focused = i;
                }
                fields.get(i)
                    .setFocused(false);
            }
            fields.get((focused + 1) % fields.size())
                .setFocused(true);
            return;
        }
        for (GuiTextField field : fields) {
            if (field.isFocused()) {
                field.textboxKeyTyped(typedChar, keyCode);
            }
        }
        if (colorField.isFocused()) {
            String text = colorField.getText()
                .trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            if (text.length() == 6) {
                try {
                    outlineColor = Integer.parseInt(text, 16);
                    edited.outlineColor = outlineColor;
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        for (GuiTextField field : fields) {
            field.mouseClicked(mouseX, mouseY, button);
        }
        int swatchY = top + 179;
        if (button == 0 && mouseY >= swatchY && mouseY < swatchY + 12 && mouseX >= left) {
            int index = (mouseX - left) / SWATCH;
            if (index < PALETTE.length) {
                setOutline(PALETTE[index]);
            }
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField field : fields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            I18n.format(target == null ? "wayfarmap.gui.new_waypoint" : "wayfarmap.gui.edit_waypoint"),
            width / 2,
            top,
            0xFFFFFF);

        fontRendererObj.drawString(I18n.format("wayfarmap.gui.name"), left, top + 14, 0xA0A0A0);
        fontRendererObj.drawString("X", left, top + 48, 0xA0A0A0);
        fontRendererObj.drawString("Y", left + 75, top + 48, 0xA0A0A0);
        fontRendererObj.drawString("Z", left + 150, top + 48, 0xA0A0A0);
        for (GuiTextField field : fields) {
            field.drawTextBox();
        }
        if (newGroupField.getText()
            .isEmpty() && !newGroupField.isFocused()) {
            fontRendererObj.drawString(I18n.format("wayfarmap.gui.new_group_hint"), left + 4, top + 112, 0x707070);
        }

        for (int i = 0; i < PALETTE.length; i++) {
            int x = left + i * SWATCH;
            int y = top + 179;
            boolean selected = edited.outlineColor != null && edited.outlineColor == PALETTE[i];
            drawRect(x, y, x + SWATCH - 1, y + 12, selected ? 0xFFFFFFFF : 0xFF000000);
            drawRect(x + 1, y + 1, x + SWATCH - 2, y + 11, 0xFF000000 | PALETTE[i]);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Preview of the marker next to the icon button.
        WaypointRenderer.drawMapMarker(edited, left - 16, top + 140, 16f, false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
