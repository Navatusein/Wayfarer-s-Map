package WayFarMap.client.map.iso;

import java.lang.reflect.Method;
import java.util.Collection;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;

/**
 * ForgeMultipart's blocks (microblocks, wires) get their parts in packets of their own, after the chunk: a copy taken
 * before has them empty and their pictures come out empty. Without the mod, nothing is ever missing.
 */
final class MultipartParts {

    private static boolean looked;
    private static Class<?> tileClass;
    private static Method partList;

    private MultipartParts() {}

    /** Whether a ForgeMultipart block of the chunk has no parts yet (render thread). */
    static boolean missing(Chunk chunk) {
        if (!looked) {
            looked = true;
            try {
                tileClass = Class.forName("codechicken.multipart.TileMultipart");
                partList = tileClass.getMethod("jPartList");
            } catch (Throwable t) {
                tileClass = null;
            }
        }
        if (tileClass == null) {
            return false;
        }
        try {
            for (Object o : chunk.chunkTileEntityMap.values()) {
                if (tileClass.isInstance(o) && !((TileEntity) o).isInvalid()) {
                    Object parts = partList.invoke(o);
                    if (parts instanceof Collection && ((Collection<?>) parts).isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // Another version of the mod: not waited for.
            tileClass = null;
        }
        return false;
    }
}
