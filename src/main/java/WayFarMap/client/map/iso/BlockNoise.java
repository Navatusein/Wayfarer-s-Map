package WayFarMap.client.map.iso;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;

/**
 * Changes of a chunk that don't change how the 3D map looks enough to copy and draw it again: fluids flowing (their
 * level, flowing or still water), leaves marked to be checked for decay, light. A chunk next to an oil spring or a
 * lava fall changed every few seconds by a few blocks of these and was copied again and again (with its pictures),
 * its tiles drawn again each time: in a flight's log, 60% of the chunks stored were such copies.
 */
final class BlockNoise {

    private static final byte UNKNOWN = 0, PLAIN = 1, LIQUID = 2, LEAVES = 3;
    /** By block id: what kind of block it is for this, worked out on first use. */
    private static final byte[] KINDS = new byte[1 << 16];
    /** By block id: the id it counts as (flowing water and lava as still), worked out with {@link #KINDS}. */
    private static final char[] SAME_AS = new char[1 << 16];

    private BlockNoise() {}

    private static byte kind(int id) {
        byte kind = KINDS[id];
        if (kind != UNKNOWN) {
            return kind;
        }
        int sameAs = id;
        kind = PLAIN;
        try {
            Block block = Block.getBlockById(id);
            Material material = block.getMaterial();
            if (material.isLiquid()) {
                kind = LIQUID;
                if (block == Blocks.flowing_water) {
                    sameAs = Block.getIdFromBlock(Blocks.water);
                } else if (block == Blocks.flowing_lava) {
                    sameAs = Block.getIdFromBlock(Blocks.lava);
                }
            } else if (material == Material.leaves) {
                kind = LEAVES;
            }
        } catch (RuntimeException e) {
            // An id the registry doesn't know: a plain block.
        }
        // Benign race: every thread works out the same values.
        SAME_AS[id] = (char) sameAs;
        KINDS[id] = kind;
        return kind;
    }

    /**
     * The cell without what is noise: no light; a fluid without its level, flowing as still; leaves without the bit
     * marking them to be checked for decay. Cells alike this way look alike on the map.
     */
    static int quiet(int cell) {
        int id = ChunkBlocks.blockId(cell);
        switch (kind(id)) {
            case LIQUID:
                int sameAs = SAME_AS[id];
                return sameAs == 0 ? id : sameAs;
            case LEAVES:
                return cell & 0x7FFFF;
            default:
                return cell & 0xFFFFF;
        }
    }

    /** Air or a fluid: a fluid spreading or drawing back changes only such cells. */
    static boolean airOrLiquid(int cell) {
        int id = ChunkBlocks.blockId(cell);
        return id == 0 || kind(id) == LIQUID;
    }
}
