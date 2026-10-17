package WayFarMap.mixins.late.openblocks;

import org.spongepowered.asm.mixin.Mixin;

import WayFarMap.client.waypoint.DeathMarker;
import openblocks.common.tileentity.TileEntityGrave;
import openmods.tileentity.SyncedTileEntity;

/**
 * OpenBlocks' grave in the client's world: when it is removed (its block broken, or set to air once its owner took
 * everything out of it), its owner's death waypoint next to it is removed too. A grave leaving with its chunk is not
 * removed: only {@code onChunkUnload} runs then, not {@code invalidate}.
 */
@Mixin(value = TileEntityGrave.class, remap = false)
public abstract class MixinTileEntityGrave extends SyncedTileEntity {

    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj != null && worldObj.isRemote) {
            DeathMarker.graveRemoved(
                worldObj.provider.dimensionId,
                xCoord,
                yCoord,
                zCoord,
                ((TileEntityGrave) (Object) this).getUsername());
        }
    }
}
