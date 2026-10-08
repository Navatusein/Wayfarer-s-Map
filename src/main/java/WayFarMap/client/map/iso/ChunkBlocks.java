package WayFarMap.client.map.iso;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
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
     * Per column, the lowest solid block of the column (id | meta << 16) and its height ({@code (y + 1) << 20}):
     * the ground below {@link #yMin} is drawn from it in layers; 0 until the tracer worked it out.
     */
    final int[] filler = new int[256];
    /**
     * Lowest height whose blocks get pictures taken by the game (only while copying): the part of an edge chunk kept
     * below its surface is drawn from icons.
     */
    int picturesFrom;

    /** {@link FacePalette#generation} the face ids belong to; ids of another one are not used. */
    int faceGeneration;
    /** Cells (indices into {@link #cells}, ascending) that have sprites. */
    int[] faceCells = new int[0];
    /**
     * {@link #PER_CELL} picture ids per cell of {@link #faceCells}: by side for solid cubes, by the map's view side
     * ({@link IsoProjection#rotation}) for other blocks; {@link FacePalette#EMPTY} if nothing of it shows there.
     */
    int[] faceIds = new int[0];
    /**
     * Only while copying: no pictures could be taken this time at all (the game can't take them now); the ones of
     * the copy before are kept.
     */
    boolean picturesMissing;
    /**
     * Only while copying: cells of {@link #faceCells} (ascending) whose pictures were taken while a chunk next to
     * them wasn't loaded (connected textures and pipes drawn as if the world ended there); the ones of the copy
     * before are kept for them.
     */
    int[] unsureCells = new int[0];

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
     * Keeps the pictures of the copy before where this copy has none or worse ones: pictures not taken in time (a
     * chunk copied as it is let go gets only a moment for them), none at all, or taken without the chunk next door.
     * Without this, a new copy would lose the tile entities and connected textures the one before showed right.
     * Only for the same block (id and metadata) at the same place, and pictures of the same palette.
     *
     * @param old        the chunk's copy stored before, or null
     * @param generation {@link FacePalette#generation} of the palette in use
     */
    void keepPicturesFrom(ChunkBlocks old, int generation) {
        if (old == null || old.faceGeneration != generation || old.faceCells.length == 0) {
            return;
        }
        if (picturesMissing) {
            // None were taken: all of the old ones that still fit.
            int[] cellsKept = new int[old.faceCells.length];
            int[] idsKept = new int[old.faceIds.length];
            int n = 0;
            for (int i = 0; i < old.faceCells.length; i++) {
                int index = moved(old, old.faceCells[i]);
                if (index < 0) {
                    continue;
                }
                cellsKept[n] = index;
                System.arraycopy(old.faceIds, i * PER_CELL, idsKept, n * PER_CELL, PER_CELL);
                n++;
            }
            if (n > 0) {
                setFaces(generation, Arrays.copyOf(cellsKept, n), Arrays.copyOf(idsKept, n * PER_CELL));
            }
            return;
        }
        int unsure = 0;
        for (int n = 0; n < faceCells.length; n++) {
            int oldIndex = movedBack(old, faceCells[n]);
            if (oldIndex < 0) {
                continue;
            }
            int j = Arrays.binarySearch(old.faceCells, oldIndex);
            if (j < 0) {
                continue;
            }
            int at = n * PER_CELL, oldAt = j * PER_CELL;
            while (unsure < unsureCells.length && unsureCells[unsure] < faceCells[n]) {
                unsure++;
            }
            if (unsure < unsureCells.length && unsureCells[unsure] == faceCells[n] && complete(old.faceIds, oldAt)) {
                System.arraycopy(old.faceIds, oldAt, faceIds, at, PER_CELL);
                continue;
            }
            for (int slot = 0; slot < PER_CELL; slot++) {
                int id = faceIds[at + slot], oldId = old.faceIds[oldAt + slot];
                // Not taken, or left out as hidden while the copy before has a picture there (better than icons
                // should a ray get there after all).
                if (id == 0 || id == FacePalette.HIDDEN && oldId != 0) {
                    faceIds[at + slot] = oldId;
                }
            }
        }
    }

    /** Whether all the pictures of a cell were taken: its four view sides, and a cube's other two sides too. */
    private static boolean complete(int[] ids, int at) {
        for (int slot = 0; slot < VIEWS; slot++) {
            if (ids[at + slot] == 0) {
                return false;
            }
        }
        return (ids[at + 4] == 0) == (ids[at + 5] == 0);
    }

    /** Index in this copy of a cell of the old one if it holds the same block, else -1. */
    private int moved(ChunkBlocks old, int oldIndex) {
        int y = old.yMin + (oldIndex >> 8);
        if (y < yMin || y > yMax) {
            return -1;
        }
        int index = ((y - yMin) << 8) | (oldIndex & 255);
        return lookKey(cells[index]) == lookKey(old.cells[oldIndex]) ? index : -1;
    }

    /** Index in the old copy of a cell of this one if it holds the same block, else -1. */
    private int movedBack(ChunkBlocks old, int index) {
        int y = yMin + (index >> 8);
        if (y < old.yMin || y > old.yMax) {
            return -1;
        }
        int oldIndex = ((y - old.yMin) << 8) | (index & 255);
        return lookKey(old.cells[oldIndex]) == lookKey(cells[index]) ? oldIndex : -1;
    }

    /**
     * Hash of everything of the copy but its pictures: the heights kept, the blocks with their light, the biome
     * colors. Copies with the same hash look the same but for the pictures of blocks drawn by the game, which change
     * on their own (a blinking ME controller, a GregTech machine turning on) and are not a reason to copy again.
     */
    long signature() {
        long h = 0xCBF29CE484222325L;
        h = (h ^ yMin) * 0x100000001B3L;
        h = (h ^ yMax) * 0x100000001B3L;
        for (int[] values : new int[][] { cells, grass, foliage, water }) {
            for (int value : values) {
                h = (h ^ value) * 0x100000001B3L;
            }
        }
        // 0 stands for "no signature".
        return h == 0 ? 1 : h;
    }

    /**
     * {@link #signature} without what is noise ({@link BlockNoise#quiet}): fluids flowing, leaves marked for decay,
     * light. Copies with the same quiet signature differ at most by that.
     */
    long quietSignature() {
        long h = 0xCBF29CE484222325L;
        h = (h ^ yMin) * 0x100000001B3L;
        h = (h ^ yMax) * 0x100000001B3L;
        for (int cell : cells) {
            h = (h ^ BlockNoise.quiet(cell)) * 0x100000001B3L;
        }
        for (int[] values : new int[][] { grass, foliage, water }) {
            for (int value : values) {
                h = (h ^ value) * 0x100000001B3L;
            }
        }
        return h == 0 ? 1 : h;
    }

    /**
     * Whether this copy is the old one but for noise: the same heights and colors, cells that differ only by
     * {@link BlockNoise#quiet} or are air or fluid in both (a fluid spreading or drawing back), and no picture the old
     * one lacked (pictures taken again differ on their own).
     */
    boolean onlyNoiseChanged(ChunkBlocks old) {
        if (old == null || old.yMin != yMin
            || old.yMax != yMax
            || old.faceGeneration != faceGeneration
            || !Arrays.equals(old.grass, grass)
            || !Arrays.equals(old.foliage, foliage)
            || !Arrays.equals(old.water, water)) {
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            int a = old.cells[i], b = cells[i];
            if (a != b && BlockNoise.quiet(a) != BlockNoise.quiet(b)
                && !(BlockNoise.airOrLiquid(a) && BlockNoise.airOrLiquid(b))) {
                return false;
            }
        }
        for (int n = 0; n < faceCells.length; n++) {
            int j = Arrays.binarySearch(old.faceCells, faceCells[n]);
            for (int slot = 0; slot < PER_CELL; slot++) {
                int id = faceIds[n * PER_CELL + slot];
                int oldId = j < 0 ? 0 : old.faceIds[j * PER_CELL + slot];
                if (id != 0 && id != FacePalette.HIDDEN && (oldId == 0 || oldId == FacePalette.HIDDEN)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The blocks that differ from the old copy (block, light or pictures), in the chunk's own coordinates: {x0, y0,
     * z0, x1, y1, z1}, both ends included; null if the whole chunk is to count (no old copy, other heights or
     * colors), and an empty array if nothing differs.
     */
    int[] changedBox(ChunkBlocks old) {
        if (old == null || old.yMin != yMin
            || old.yMax != yMax
            || !Arrays.equals(old.grass, grass)
            || !Arrays.equals(old.foliage, foliage)
            || !Arrays.equals(old.water, water)) {
            return null;
        }
        int[] box = { 16, 256, 16, -1, -1, -1 };
        for (int i = 0; i < cells.length; i++) {
            if (old.cells[i] != cells[i]) {
                include(box, i);
            }
        }
        // Pictures: cells with pictures in one copy only, or other ones (the arrays are ascending).
        int a = 0, b = 0;
        while (a < old.faceCells.length || b < faceCells.length) {
            int oldCell = a < old.faceCells.length ? old.faceCells[a] : Integer.MAX_VALUE;
            int cell = b < faceCells.length ? faceCells[b] : Integer.MAX_VALUE;
            if (oldCell < cell) {
                include(box, oldCell);
                a++;
            } else if (cell < oldCell) {
                include(box, cell);
                b++;
            } else {
                for (int slot = 0; slot < PER_CELL; slot++) {
                    if (old.faceIds[a * PER_CELL + slot] != faceIds[b * PER_CELL + slot]) {
                        include(box, cell);
                        break;
                    }
                }
                a++;
                b++;
            }
        }
        return box[3] < 0 ? new int[0] : box;
    }

    private void include(int[] box, int index) {
        int x = index & 15, z = (index >> 4) & 15, y = yMin + (index >> 8);
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }

    /**
     * Whether this (stored) copy has every picture of the palette's generation: taken with the palette in use, and
     * none of its blocks with pictures lacking one (a copy stored after giving up keeps them at 0).
     */
    boolean allPicturesTaken(int generation) {
        if (faceGeneration != generation) {
            return false;
        }
        for (int n = 0; n < faceCells.length; n++) {
            if (!complete(faceIds, n * PER_CELL)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this copy is the old one with only its pictures taken again: the same blocks, light and colors, and no
     * picture the old one lacked. Such a copy isn't stored: pictures of animated blocks differ each time they are
     * taken, and storing them would redraw the tiles for nothing.
     */
    boolean onlyRetakenPictures(ChunkBlocks old) {
        if (old == null || old.yMin != yMin
            || old.yMax != yMax
            || old.faceGeneration != faceGeneration
            || !Arrays.equals(old.cells, cells)
            || !Arrays.equals(old.grass, grass)
            || !Arrays.equals(old.foliage, foliage)
            || !Arrays.equals(old.water, water)) {
            return false;
        }
        for (int n = 0; n < faceCells.length; n++) {
            int j = Arrays.binarySearch(old.faceCells, faceCells[n]);
            if (j < 0) {
                // A block with pictures now that had none.
                return false;
            }
            for (int slot = 0; slot < PER_CELL; slot++) {
                int oldId = old.faceIds[j * PER_CELL + slot], id = faceIds[n * PER_CELL + slot];
                if ((oldId == 0 || oldId == FacePalette.HIDDEN) && id != 0 && id != FacePalette.HIDDEN) {
                    // A picture missing or left out before (hidden then): this copy fills it in.
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a block (id and metadata) differs from the old copy, or the heights kept: light, colors and pictures
     * don't count. Such a change may let blocks around be seen that weren't (a roof taken off).
     */
    boolean blocksDiffer(ChunkBlocks old) {
        if (old == null || old.yMin != yMin || old.yMax != yMax) {
            return true;
        }
        for (int i = 0; i < cells.length; i++) {
            if (lookKey(cells[i]) != lookKey(old.cells[i])) {
                return true;
            }
        }
        return false;
    }

    /** Whether some picture was left out as hidden from the map ({@link FacePalette#HIDDEN}). */
    boolean hasHidden() {
        for (int id : faceIds) {
            if (id == FacePalette.HIDDEN) {
                return true;
            }
        }
        return false;
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
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(
            new InflaterInputStream(new ByteArrayInputStream(data), inflater, 8192))) {
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
        } finally {
            inflater.end();
        }
    }

    /** Bits 0-15 of a layer of {@link #airBricks}: every brick of the layer is air. */
    static final int ALL_AIR = 0xFFFF;

    /** See {@link #airBricks}; null until worked out. */
    private volatile int[] airBricks;
    /** For the tracer: its blocks whose sprites reach past their cell, worked out when first needed. */
    volatile Object overhangs;
    /** See {@link #airFloor}; set before {@link #airBricks}. */
    private byte[] airFloors;

    /**
     * Where the chunk holds only air, in bricks of 4x4x4 blocks, so rays can pass over empty space at once instead of
     * block by block. One int per layer of bricks (4 blocks high, from {@link #yMin}): bits 0-15 the bricks of the
     * layer that are only air (bit {@code (z >> 2) << 2 | x >> 2}); if the whole layer is, bits 16-23 the lowest layer
     * from which up to this one all layers are only air. Worked out once per chunk.
     */
    int[] airBricks() {
        int[] air = airBricks;
        if (air != null) {
            return air;
        }
        int height = yMax - yMin + 1;
        air = new int[(height + 3) >> 2];
        byte[] floors = new byte[air.length << 4];
        for (int layer = 0; layer < air.length; layer++) {
            int mask = ALL_AIR;
            int end = Math.min(height, (layer + 1) << 2) << 8;
            for (int i = layer << 10; i < end && mask != 0; i++) {
                if (blockId(cells[i]) != 0) {
                    mask &= ~(1 << ((i >> 6 & 3) << 2 | (i & 15) >> 2));
                }
            }
            int floor = layer;
            if (mask == ALL_AIR && layer > 0 && (air[layer - 1] & ALL_AIR) == ALL_AIR) {
                floor = air[layer - 1] >>> 16;
            }
            air[layer] = mask | floor << 16;
            for (int brick = 0; brick < 16; brick++) {
                boolean below = layer > 0 && (air[layer - 1] & 1 << brick) != 0;
                floors[layer << 4 | brick] = (byte) (below ? floors[(layer - 1) << 4 | brick] : layer);
            }
        }
        airFloors = floors;
        airBricks = air;
        return air;
    }

    /**
     * The lowest layer from which up to this one the brick and all under it are only air (the brick must be); call
     * {@link #airBricks} first.
     */
    int airFloor(int layer, int brick) {
        return airFloors[layer << 4 | brick];
    }

    /** See {@link #lookKeys}; null until worked out. */
    private volatile int[] lookKeys;
    /** The looks of all its blocks were worked out (the tracer asked for them at once). */
    volatile boolean looksReady;

    /** The different blocks in the chunk ({@link #lookKey}s, air left out). Worked out once. */
    int[] lookKeys() {
        int[] keys = lookKeys;
        if (keys != null) {
            return keys;
        }
        // Open addressing: the same few blocks fill most of the chunk.
        int[] table = new int[256];
        int count = 0;
        int previous = -1;
        for (int cell : cells) {
            if (blockId(cell) == 0) {
                continue;
            }
            int key = lookKey(cell);
            if (key == previous) {
                continue;
            }
            previous = key;
            int slot = (key * 0x9E3779B1) >>> 16 & (table.length - 1);
            while (table[slot] != 0 && table[slot] != key + 1) {
                slot = (slot + 1) & (table.length - 1);
            }
            if (table[slot] != 0) {
                continue;
            }
            table[slot] = key + 1;
            if (++count * 2 > table.length) {
                int[] bigger = new int[table.length * 2];
                for (int entry : table) {
                    if (entry != 0) {
                        int s = ((entry - 1) * 0x9E3779B1) >>> 16 & (bigger.length - 1);
                        while (bigger[s] != 0) {
                            s = (s + 1) & (bigger.length - 1);
                        }
                        bigger[s] = entry;
                    }
                }
                table = bigger;
            }
        }
        keys = new int[count];
        int n = 0;
        for (int entry : table) {
            if (entry != 0) {
                keys[n++] = entry - 1;
            }
        }
        lookKeys = keys;
        return keys;
    }

    /** Memory it takes, roughly, in ints. */
    int weight() {
        return cells.length + 4 * 256 + faceCells.length * (PER_CELL + 1);
    }
}
