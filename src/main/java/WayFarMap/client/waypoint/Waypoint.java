package WayFarMap.client.waypoint;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

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
    /**
     * The icon item's NBT, compressed and in Base64, or null: some items are drawn from it (Tinkers' Construct tools
     * are made of their parts in it, and without it they are invisible).
     */
    public String iconNbt;
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
        copy.iconNbt = iconNbt;
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
        iconNbt = other.iconNbt;
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
                    cachedIcon.setTagCompound(readNbt(iconNbt));
                }
            }
        }
        return cachedIcon;
    }

    public void setIcon(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            iconItem = null;
            iconMeta = 0;
            iconNbt = null;
        } else {
            iconItem = GameData.getItemRegistry()
                .getNameForObject(stack.getItem());
            iconMeta = stack.getItemDamage();
            iconNbt = writeNbt(stack.getTagCompound());
        }
        iconResolved = false;
    }

    /** The tag compressed and in Base64, null for none or if it can't be written. */
    static String writeNbt(NBTTagCompound tag) {
        if (tag == null || tag.hasNoTags()) {
            return null;
        }
        try {
            return Base64.getEncoder()
                .encodeToString(CompressedStreamTools.compress(tag));
        } catch (Exception e) {
            return null;
        }
    }

    /** The tag written by {@link #writeNbt}, null for none or if it can't be read. */
    static NBTTagCompound readNbt(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(text)));
        } catch (Exception e) {
            return null;
        }
    }
}
