package WayFarMap.share;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * One chunk of a map as teammates share it: the 16x16 pixel colors, their extra bytes (surface: height to stand on)
 * and, for the surface, the biome of each column. {@code layer} is -1 for the surface, otherwise the cave layer.
 */
public final class ChunkRecord {

    public static final int AREA = 256;

    public int chunkX, chunkZ;
    public int layer;
    /** When the server received it (milliseconds); 0 in uploads. */
    public long time;
    public final int[] colors = new int[AREA];
    public final byte[] extra = new byte[AREA];
    /** Biome id + 1 per column (0 = unknown); null for cave layers. */
    public byte[] biomes;

    public void write(DataOutput out) throws IOException {
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeByte(layer);
        out.writeLong(time);
        for (int color : colors) {
            out.writeInt(color);
        }
        out.write(extra);
        out.writeBoolean(biomes != null);
        if (biomes != null) {
            out.write(biomes);
        }
    }

    public static ChunkRecord read(DataInput in) throws IOException {
        ChunkRecord record = new ChunkRecord();
        record.chunkX = in.readInt();
        record.chunkZ = in.readInt();
        record.layer = in.readByte();
        record.time = in.readLong();
        for (int i = 0; i < AREA; i++) {
            record.colors[i] = in.readInt();
        }
        in.readFully(record.extra);
        if (in.readBoolean()) {
            record.biomes = new byte[AREA];
            in.readFully(record.biomes);
        }
        if (record.layer < -1 || record.layer > 15) {
            throw new IOException("Bad map layer " + record.layer);
        }
        return record;
    }

    /** Content hash, so a chunk that looks the same as last time isn't sent again. */
    public int contentHash() {
        int hash = Arrays.hashCode(colors);
        hash = hash * 31 + Arrays.hashCode(extra);
        return hash * 31 + (biomes != null ? Arrays.hashCode(biomes) : 0);
    }

    /** True if the sender had explored at least one column of it. */
    public boolean hasPixels() {
        for (int color : colors) {
            if ((color >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }
}
