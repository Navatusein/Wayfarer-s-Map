package WayFarMap.client.map;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

/**
 * A region shrunk 4 times (128x128 pixels, one per 4x4 blocks) for drawing the map zoomed out: a full region takes
 * about 1.3 MB of memory and as much video memory, so hundreds of them on screen would not fit. The tile keeps only
 * the reduced pixels; the full region is not kept in memory for it.
 */
public final class LodTile implements PixelSource {

    public static final int FACTOR = 4;
    public static final int SIZE = MapRegion.SIZE / FACTOR;

    private static IntBuffer uploadBuffer;

    private final int[] pixels = new int[SIZE * SIZE];
    /** Extra byte of one explored block per pixel (biome for the biome map); null if the region has none. */
    private byte[] extra;
    /** {@link MapRegion#getChanges()} of the region it was built from, -1 when built from the file. */
    int sourceChanges = -1;
    /** When it was last built, to rebuild regions that change all the time (around the player) only now and then. */
    long builtAt;
    private int changes;
    private int textureId = -1;
    private boolean uploadPending;

    /** Reduces a full region's pixels; safe on any thread when the arrays aren't changed meanwhile. */
    static LodTile of(int[] regionPixels, byte[] regionExtra) {
        LodTile tile = new LodTile();
        tile.fill(regionPixels, regionExtra);
        return tile;
    }

    /** Rebuilds the tile from the live region (render thread). */
    void update(MapRegion region) {
        fill(region.pixelArray(), region.extraArray());
        sourceChanges = region.getChanges();
        builtAt = System.currentTimeMillis();
    }

    private void fill(int[] source, byte[] sourceExtra) {
        if (sourceExtra != null && extra == null) {
            extra = new byte[SIZE * SIZE];
        }
        int full = MapRegion.SIZE;
        for (int tz = 0; tz < SIZE; tz++) {
            for (int tx = 0; tx < SIZE; tx++) {
                // Average color of the explored blocks of the 4x4 square.
                int r = 0, g = 0, b = 0, count = 0, extraValue = 0;
                for (int dz = 0; dz < FACTOR; dz++) {
                    int row = (tz * FACTOR + dz) * full + tx * FACTOR;
                    for (int dx = 0; dx < FACTOR; dx++) {
                        int argb = source[row + dx];
                        if ((argb >>> 24) == 0) {
                            continue;
                        }
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        count++;
                        if (extraValue == 0 && sourceExtra != null) {
                            extraValue = sourceExtra[row + dx] & 0xFF;
                        }
                    }
                }
                int index = tz * SIZE + tx;
                pixels[index] = count == 0 ? 0 : 0xFF000000 | (r / count) << 16 | (g / count) << 8 | (b / count);
                if (extra != null) {
                    extra[index] = (byte) extraValue;
                }
            }
        }
        changes++;
        uploadPending = true;
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int getPixel(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    @Override
    public int getExtra(int localX, int localZ) {
        return extra == null ? 0 : extra[localZ * SIZE + localX] & 0xFF;
    }

    @Override
    public int getChanges() {
        return changes;
    }

    public boolean hasTexture() {
        return textureId != -1;
    }

    /** Binds the tile's texture, uploading it if it changed. Render thread only. */
    public void bindTexture() {
        if (textureId == -1) {
            textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            uploadPending = true;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        }
        if (uploadPending) {
            uploadPending = false;
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            uploadBuffer.put(pixels);
            uploadBuffer.flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                SIZE,
                SIZE,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                uploadBuffer);
        }
    }

    public void deleteTexture() {
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
        }
        BiomeHighlight.forget(this);
    }
}
