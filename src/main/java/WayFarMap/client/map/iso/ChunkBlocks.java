package WayFarMap.client.map.iso;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

/**
 * The blocks of one chunk the 3D map is drawn from: every block from the lowest floor the sky can see (under water,
 * leaves and glass) up to the highest block, with their light, and the biome colors of each column. What is below
 * {@link #yMin} counts as solid ground ({@link #filler}); what is above {@link #yMax} is open sky.
 * <p>
 * A cell is an int: block id (bits 0-15), metadata (16-19), block light (20-23), sky light (24-27).
 * <p>
 * Blocks the game draws in a way the map doesn't imitate (connected textures of Chisel, tile entities like chests,
 * pipes and cables, modded block renderers, beds, rails, machine fronts) also have sprites of how the game draws them
 * there, one per side the 3D map looks from, kept in the {@link FacePalette} ({@link #faceIds}).
 */
public final class ChunkBlocks {

    private static final int FORMAT_PLAIN = 1, FORMAT_SIDES = 2, FORMAT_SPRITES = 3, FORMAT = 4;
    /** Sprites of a block that isn't a solid cube: one for each side the 3D map looks from. */
    static final int VIEWS = 4;
    /**
     * Picture ids per cell: a solid cube has one picture for each of its six sides (they fit its sides exactly),
     * other blocks a sprite for each of the {@link #VIEWS} view sides.
     */
    static final int PER_CELL = 6;
    /** Cell of air in full daylight. */
    public static final int OPEN_SKY = 15 << 24;

    public final int yMin, yMax;
    public final int[] cells;
    /** Biome colors of each column (index {@code z * 16 + x}), RGB. */
    public final int[] grass, foliage, water;
    /**
     * Per column, the block (id | meta << 16) that fills everything below {@link #yMin}: the lowest solid block of
     * the column; 0 until the tracer worked it out.
     */
    final int[] filler = new int[256];

    /** {@link FacePalette#generation} the face ids belong to; ids of another one are not used. */
    int faceGeneration;
    /** Cells (indices into {@link #cells}, ascending) that have sprites. */
    int[] faceCells = new int[0];
    /**
     * {@link #PER_CELL} picture ids per cell of {@link #faceCells}: by side for solid cubes, by the map's view side
     * ({@link IsoProjection#rotation}) for other blocks; {@link FacePalette#EMPTY} if nothing of it shows there.
     */
    int[] faceIds = new int[0];

    public ChunkBlocks(int yMin, int yMax, int[] cells, int[] grass, int[] foliage, int[] water) {
        this.yMin = yMin;
        this.yMax = yMax;
        this.cells = cells;
        this.grass = grass;
        this.foliage = foliage;
        this.water = water;
    }

    public static int index(int x, int y, int z, int yMin) {
        return ((y - yMin) << 8) | (z << 4) | x;
    }

    public int cell(int x, int y, int z) {
        return cells[((y - yMin) << 8) | (z << 4) | x];
    }

    public static int blockId(int cell) {
        return cell & 0xFFFF;
    }

    public static int meta(int cell) {
        return (cell >>> 16) & 15;
    }

    public static int blockLight(int cell) {
        return (cell >>> 20) & 15;
    }

    public static int skyLight(int cell) {
        return (cell >>> 24) & 15;
    }

    /** Key of a block look: id and metadata. */
    public static int lookKey(int cell) {
        return cell & 0xFFFFF;
    }

    /** Sets the pictures (cells ascending, {@link #PER_CELL} ids each). */
    void setFaces(int generation, int[] cells, int[] ids) {
        faceGeneration = generation;
        faceCells = cells;
        faceIds = ids;
    }

    /**
     * Picture id of the cell (index into {@link #cells}): the side (solid cubes) or view side (other blocks), 0 if it
     * has none.
     */
    int pictureId(int cellIndex, int slot) {
        if (faceCells.length == 0) {
            return 0;
        }
        int i = Arrays.binarySearch(faceCells, cellIndex);
        return i < 0 ? 0 : faceIds[i * PER_CELL + slot];
    }

    public byte[] encode() {
        // Layer by layer and field by field: long runs of the same bytes compress far better.
        int faces = faceCells.length;
        byte[] raw = new byte[5 + cells.length * 4 + 3 * 3 * 256 + 8 + faces * 4 + faces * PER_CELL * 4];
        raw[0] = FORMAT;
        raw[1] = (byte) (yMin >> 8);
        raw[2] = (byte) yMin;
        raw[3] = (byte) (yMax >> 8);
        raw[4] = (byte) yMax;
        int n = 5;
        for (int shift = 0; shift < 32; shift += 8) {
            for (int cell : cells) {
                raw[n++] = (byte) (cell >>> shift);
            }
        }
        for (int[] colors : new int[][] { grass, foliage, water }) {
            for (int shift = 16; shift >= 0; shift -= 8) {
                for (int color : colors) {
                    raw[n++] = (byte) (color >>> shift);
                }
            }
        }
        n = putInt(raw, n, faceGeneration);
        n = putInt(raw, n, faces);
        for (int cell : faceCells) {
            n = putInt(raw, n, cell);
        }
        for (int id : faceIds) {
            n = putInt(raw, n, id);
        }
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length / 8 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                bytes.write(buffer, 0, deflater.deflate(buffer));
            }
            return bytes.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static int putInt(byte[] raw, int n, int value) {
        raw[n] = (byte) (value >>> 24);
        raw[n + 1] = (byte) (value >>> 16);
        raw[n + 2] = (byte) (value >>> 8);
        raw[n + 3] = (byte) value;
        return n + 4;
    }

    public static ChunkBlocks decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            int format = in.readUnsignedByte();
            if (format < FORMAT_PLAIN || format > FORMAT) {
                throw new IOException("Unknown chunk format " + format);
            }
            int yMin = in.readShort();
            int yMax = in.readShort();
            if (yMin < 0 || yMax > 255 || yMax < yMin) {
                throw new IOException("Bad chunk height " + yMin + ".." + yMax);
            }
            int[] cells = new int[(yMax - yMin + 1) << 8];
            byte[] plane = new byte[cells.length];
            for (int shift = 0; shift < 32; shift += 8) {
                in.readFully(plane);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] |= (plane[i] & 0xFF) << shift;
                }
            }
            int[][] colors = new int[3][256];
            byte[] channel = new byte[256];
            for (int[] color : colors) {
                for (int shift = 16; shift >= 0; shift -= 8) {
                    in.readFully(channel);
                    for (int i = 0; i < 256; i++) {
                        color[i] |= (channel[i] & 0xFF) << shift;
                    }
                }
            }
            ChunkBlocks blocks = new ChunkBlocks(yMin, yMax, cells, colors[0], colors[1], colors[2]);
            if (format != FORMAT_PLAIN) {
                int generation = in.readInt();
                int faces = in.readInt();
                if (faces < 0 || faces > cells.length) {
                    throw new IOException("Bad face count " + faces);
                }
                int perCell = format == FORMAT_SPRITES ? VIEWS : PER_CELL;
                int[] faceCells = new int[faces];
                int[] ids = new int[faces * perCell];
                try {
                    for (int i = 0; i < faces; i++) {
                        faceCells[i] = in.readInt();
                    }
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = in.readInt();
                    }
                } catch (EOFException e) {
                    throw new IOException("Truncated faces", e);
                }
                if (format == FORMAT) {
                    blocks.setFaces(generation, faceCells, ids);
                }
                // Older pictures (version 2: sides of every block, 3: sprites of every block) are taken again.
            }
            return blocks;
        }
    }

    /** Memory it takes, roughly, in ints. */
    int weight() {
        return cells.length + 4 * 256 + faceCells.length * (PER_CELL + 1);
    }
}
