package WayFarMap.client.waypoint;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.registry.GameData;

/** A named point in the world. Stored as JSON, so all non-transient fields are persisted. */
public class Waypoint {

    public String name = "";
    public int x;
    public int y;
    public int z;
    public int dimension;
    /** RGB color of the outline, or null for no outline. */
    public Integer outlineColor;
    /** Registry name of the icon item ("modid:name"), or null for no icon. */
    public String iconItem;
    public int iconMeta;
    /** Name of the group, or null when the waypoint is in no group. */
    public String group;
    public boolean enabled = true;
    /** A beacon-like beam of light rises from it in the world. */
    public boolean beam;
    /** Placed automatically where the player died; only the latest few are kept. */
    public boolean death;

    private transient ItemStack cachedIcon;
    private transient boolean iconResolved;

    public Waypoint() {}

    public Waypoint(String name, int x, int y, int z, int dimension) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
    }

    public Waypoint copy() {
        Waypoint copy = new Waypoint(name, x, y, z, dimension);
        copy.outlineColor = outlineColor;
        copy.iconItem = iconItem;
        copy.iconMeta = iconMeta;
        copy.group = group;
        copy.enabled = enabled;
        copy.beam = beam;
        copy.death = death;
        return copy;
    }

    public void copyFrom(Waypoint other) {
        name = other.name;
        x = other.x;
        y = other.y;
        z = other.z;
        dimension = other.dimension;
        outlineColor = other.outlineColor;
        iconItem = other.iconItem;
        iconMeta = other.iconMeta;
        group = other.group;
        enabled = other.enabled;
        beam = other.beam;
        death = other.death;
        iconResolved = false;
    }

    /** @return the icon as an item stack, or null if there is no icon or the item no longer exists. */
    public ItemStack getIcon() {
        if (!iconResolved) {
            iconResolved = true;
            cachedIcon = null;
            if (iconItem != null) {
                Item item = GameData.getItemRegistry()
                    .getObject(iconItem);
                if (item != null) {
                    cachedIcon = new ItemStack(item, 1, iconMeta);
                }
            }
        }
        return cachedIcon;
    }

    public void setIcon(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            iconItem = null;
            iconMeta = 0;
        } else {
            iconItem = GameData.getItemRegistry()
                .getNameForObject(stack.getItem());
            iconMeta = stack.getItemDamage();
        }
        iconResolved = false;
    }
}
