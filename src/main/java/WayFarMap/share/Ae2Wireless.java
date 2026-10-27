package WayFarMap.share;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileWireless;

/**
 * AE2 access for {@link WirelessServer}; only loaded where AE2 is installed. Finds the networks the way a wireless
 * terminal does: the terminal holds the serial of the security terminal it was linked at, AE2's locatable registry
 * gives that security terminal, and its network is the terminal's one.
 */
final class Ae2Wireless {

    private static Method baublesGet;
    private static boolean baublesLooked;

    private Ae2Wireless() {}

    /** The access points of the player's terminals' networks; infinite[0] is set if one has infinite range. */
    static List<WirelessPoint> collect(EntityPlayer player, boolean[] infinite) {
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack stack : terminals(player)) {
            IWirelessTermHandler handler = AEApi.instance()
                .registries()
                .wireless()
                .getWirelessTerminalHandler(stack);
            if (handler == null) {
                continue;
            }
            IGrid grid = grid(handler.getEncryptionKey(stack));
            if (grid == null) {
                continue;
            }
            grids.add(grid);
            if (hasInfinityRange(handler, stack)) {
                infinite[0] = true;
            }
        }
        if (grids.isEmpty()) {
            return Collections.emptyList();
        }
        // A set: two terminals of one network, or one access point seen twice, give it once.
        Set<WirelessPoint> points = new LinkedHashSet<>();
        for (IGrid grid : grids) {
            for (IGridNode node : grid.getMachines(TileWireless.class)) {
                if (!(node.getMachine() instanceof IWirelessAccessPoint)) {
                    continue;
                }
                IWirelessAccessPoint wap = (IWirelessAccessPoint) node.getMachine();
                DimensionalCoord at = wap.getLocation();
                points.add(
                    new WirelessPoint(
                        at.getWorld().provider.dimensionId,
                        at.x,
                        at.y,
                        at.z,
                        (float) wap.getRange(),
                        wap.isActive()));
            }
        }
        return new ArrayList<>(points);
    }

    private static IGrid grid(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        ILocatable station;
        try {
            station = AEApi.instance()
                .registries()
                .locatable()
                .getLocatableBy(Long.parseLong(key));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(station instanceof IGridHost)) {
            return null;
        }
        IGridNode node = ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    /** {@code hasInfinityRange} came with AE2 rv3-beta-754; older ones have no such card. */
    private static boolean hasInfinityRange(IWirelessTermHandler handler, ItemStack stack) {
        try {
            return handler.hasInfinityRange(stack);
        } catch (LinkageError e) {
            return false;
        }
    }

    /** The wireless terminals in the inventory and the bauble slots. */
    private static List<ItemStack> terminals(EntityPlayer player) {
        List<ItemStack> found = new ArrayList<>();
        for (ItemStack stack : player.inventory.mainInventory) {
            addIfTerminal(found, stack);
        }
        IInventory baubles = baubles(player);
        if (baubles != null) {
            for (int i = 0; i < baubles.getSizeInventory(); i++) {
                addIfTerminal(found, baubles.getStackInSlot(i));
            }
        }
        return found;
    }

    private static void addIfTerminal(List<ItemStack> found, ItemStack stack) {
        if (stack != null && AEApi.instance()
            .registries()
            .wireless()
            .isWirelessTerminal(stack)) {
            found.add(stack);
        }
    }

    private static IInventory baubles(EntityPlayer player) {
        if (!baublesLooked) {
            baublesLooked = true;
            try {
                baublesGet = Class.forName("baubles.api.BaublesApi")
                    .getMethod("getBaubles", EntityPlayer.class);
            } catch (Throwable ignored) {
                // No Baubles.
            }
        }
        if (baublesGet == null) {
            return null;
        }
        try {
            Object inventory = baublesGet.invoke(null, player);
            return inventory instanceof IInventory ? (IInventory) inventory : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
