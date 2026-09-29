package WayFarMap.client.map;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

/**
 * A 512x512 block area of the map (32x32 chunks). Pixels are ARGB; a pixel with alpha 0 has not been explored. Each
 * pixel can also carry one extra byte (0 = unknown): the height to stand on for the surface map, the biome for the
 * biome map. It is saved next to the image as {@code r.X.Z.dat}.
 */
public class MapRegion implements PixelSource {

    public static final int SIZE = 512;
    public static final int SHIFT = 9;
    /** Chunks per side. */
    public static final int CHUNKS = SIZE / 16;

    /** Upload buffer shared by all regions; uploads only happen on the render thread. */
    private static IntBuffer uploadBuffer;
    /**
     * Changed pixels are sent to the texture at most this often: every upload also rebuilds the mipmaps of the whole
     * 512x512 texture, and around the player the map changes every tick.
     */
    private static final long UPLOAD_INTERVAL_MS = 100;

    public final int rx;
    public final int rz;
    private final int[] pixels = new int[SIZE * SIZE];
    /** Allocated on first use; most maps (caves) never need it. */
    private byte[] extra;
    /**
     * When each of the 32x32 chunks was last mapped (by us or a teammate), in milliseconds; 0 = never. When maps
     * are merged, the newer chunk wins.
     */
    private final long[] chunkTimes = new long[CHUNKS * CHUNKS];
    /**
     * One bit per chunk: its current version came from a teammate, so it isn't ours to upload to a team again (the
     * team has it already). Cleared when we map the chunk ourselves.
     */
    private final long[] fromTeam = new long[CHUNKS * CHUNKS / 64];

    /**
     * Block light (torches, lamps, lava) just above the surface of each pixel, 0-15; null while there is none. At
     * night the map glows there ({@link #bindGlowTexture}).
     */
    private byte[] light;
    private int glowTextureId = -1;
    private boolean glowDirty;
    private long lastGlowUpload;
    /** Light levels of the glow: how much of the lit color shows over the dark map. */
    private static final int[] GLOW_ALPHA = new int[16];
    /** Warm light of torches over the map colors. */
    private static final float GLOW_R = 1.0f, GLOW_G = 0.86f, GLOW_B = 0.62f;
    private static final long GLOW_UPLOAD_INTERVAL_MS = 1000;

    static {
        for (int level = 1; level < 16; level++) {
            GLOW_ALPHA[level] = Math.min(255, (int) (255 * Math.pow(level / 14.0, 1.4)));
        }
    }

    private int textureId = -1;
    /** Area changed since the last upload, in local pixel coordinates (inclusive); minX > maxX when clean. */
    private int dirtyMinX, dirtyMinZ, dirtyMaxX = -1, dirtyMaxZ = -1;
    private long lastUpload;
    /** When the area waiting for upload was first changed (for the flat map log). */
    private long dirtySince;
    private volatile boolean saveDirty;
    private volatile boolean saving;
    /** Incremented on every change, so derived images (e.g. search highlights) know when to rebuild. */
    private int changes;

    public MapRegion(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
    }

    public void setPixel(int localX, int localZ, int argb) {
        int index = localZ * SIZE + localX;
        if (pixels[index] != argb) {
            pixels[index] = argb;
            saveDirty = true;
            changes++;
            if (light != null) {
                glowDirty = true;
            }
            if (textureId != -1) {
                markTextureDirty(localX, localZ);
            }
        }
    }

    /** Sets the pixel and its extra byte (0..255, 0 = unknown). */
    public void setPixel(int localX, int localZ, int argb, int extraValue) {
        setPixel(localX, localZ, argb);
        int index = localZ * SIZE + localX;
        if (extra == null) {
            if (extraValue == 0) {
                return;
            }
            extra = new byte[SIZE * SIZE];
        }
        if (extra[index] != (byte) extraValue) {
            extra[index] = (byte) extraValue;
            saveDirty = true;
            changes++;
        }
    }

    /** Sets the block light (0-15) above the pixel's surface. */
    public void setLight(int localX, int localZ, int level) {
        int index = localZ * SIZE + localX;
        if (light == null) {
            if (level == 0) {
                return;
            }
            light = new byte[SIZE * SIZE];
        }
        if (light[index] != (byte) level) {
            light[index] = (byte) level;
            saveDirty = true;
            glowDirty = true;
        }
    }

    /** Whether any pixel is lit by a block (the map glows there at night). */
    public boolean hasLight() {
        return light != null;
    }

    /**
     * Binds the glow texture: the map's colors in warm light where blocks light the surface, transparent elsewhere,
     * drawn over the night-darkened map. Rebuilt at most once a second while it changes. Render thread.
     */
    public void bindGlowTexture() {
        boolean create = glowTextureId == -1;
        if (create) {
            glowTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTextureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTextureId);
        }
        long now = System.currentTimeMillis();
        if (create || glowDirty && now - lastGlowUpload >= GLOW_UPLOAD_INTERVAL_MS) {
            glowDirty = false;
            lastGlowUpload = now;
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            byte[] levels = light;
            for (int i = 0; i < SIZE * SIZE; i++) {
                int level = levels == null ? 0 : levels[i] & 15;
                int argb = pixels[i];
                if (level == 0 || (argb >>> 24) == 0) {
                    uploadBuffer.put(0);
                    continue;
                }
                int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * GLOW_R));
                int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * GLOW_G));
                int b = Math.min(255, (int) ((argb & 0xFF) * GLOW_B));
                uploadBuffer.put(GLOW_ALPHA[level] << 24 | r << 16 | g << 8 | b);
            }
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

    @Override
    public int getChanges() {
        return changes;
    }

    @Override
    public int size() {
        return SIZE;
    }

    /** The live pixel array, for building the reduced copy on the render thread. */
    int[] pixelArray() {
        return pixels;
    }

    /** The live extra bytes (null if none), for building the reduced copy on the render thread. */
    byte[] extraArray() {
        return extra;
    }

    public boolean hasTexture() {
        return textureId != -1;
    }

    /** @return the extra byte of the pixel, 0 if unknown */
    @Override
    public int getExtra(int localX, int localZ) {
        return extra == null ? 0 : extra[localZ * SIZE + localX] & 0xFF;
    }

    private void markTextureDirty(int localX, int localZ) {
        if (dirtyMaxX < dirtyMinX) {
            dirtySince = System.nanoTime();
            dirtyMinX = dirtyMaxX = localX;
            dirtyMinZ = dirtyMaxZ = localZ;
        } else {
            dirtyMinX = Math.min(dirtyMinX, localX);
            dirtyMaxX = Math.max(dirtyMaxX, localX);
            dirtyMinZ = Math.min(dirtyMinZ, localZ);
            dirtyMaxZ = Math.max(dirtyMaxZ, localZ);
        }
    }

    /** True if any column of the chunk (region-local chunk coordinates) is explored. */
    public boolean hasPixels(int localChunkX, int localChunkZ) {
        for (int z = 0; z < 16; z++) {
            int row = (localChunkZ * 16 + z) * SIZE + localChunkX * 16;
            for (int x = 0; x < 16; x++) {
                if ((pixels[row + x] >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getPixel(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    /**
     * Binds the GL texture of this region, uploading only the part that changed since the last call. Must be called on
     * the render thread.
     */
    public void bindTexture() {
        if (textureId == -1) {
            textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            // Crisp pixels when zoomed in, smooth mipmapped filtering (no flicker while panning) when zoomed out.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // The driver regenerates the mipmaps on every upload.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                SIZE,
                SIZE,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                (IntBuffer) null);
            dirtyMinX = 0;
            dirtyMinZ = 0;
            dirtyMaxX = SIZE - 1;
            dirtyMaxZ = SIZE - 1;
            dirtySince = 0;
            FlatLog.textureMade(rx, rz, false);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        }
        long now = System.currentTimeMillis();
        if (dirtyMaxX >= dirtyMinX && (now - lastUpload >= UPLOAD_INTERVAL_MS || isFullyDirty())) {
            lastUpload = now;
            int width = dirtyMaxX - dirtyMinX + 1, height = dirtyMaxZ - dirtyMinZ + 1;
            long start = System.nanoTime();
            upload(dirtyMinX, dirtyMinZ, width, height);
            if (FlatLog.on()) {
                long end = System.nanoTime();
                FlatLog.uploaded(rx, rz, width, height, end - start, dirtySince == 0 ? 0 : end - dirtySince, false);
            }
            dirtyMaxX = -1;
            dirtyMinX = 0;
        }
    }

    private boolean isFullyDirty() {
        return dirtyMinX == 0 && dirtyMinZ == 0 && dirtyMaxX == SIZE - 1 && dirtyMaxZ == SIZE - 1;
    }

    private void upload(int x, int z, int width, int height) {
        if (uploadBuffer == null) {
            uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
        }
        uploadBuffer.clear();
        for (int row = 0; row < height; row++) {
            uploadBuffer.put(pixels, (z + row) * SIZE + x, width);
        }
        uploadBuffer.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            x,
            z,
            width,
            height,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            uploadBuffer);
    }

    public void deleteTexture() {
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
            dirtyMaxX = -1;
            dirtyMinX = 0;
        }
        if (glowTextureId != -1) {
            GL11.glDeleteTextures(glowTextureId);
            glowTextureId = -1;
        }
        BiomeHighlight.forget(this);
    }

    /** When the chunk (region-local chunk coordinates 0-31) was last mapped; 0 = never. */
    public long getChunkTime(int localChunkX, int localChunkZ) {
        return chunkTimes[localChunkZ * CHUNKS + localChunkX];
    }

    /** Sets when we mapped the chunk ourselves. */
    public void setChunkTime(int localChunkX, int localChunkZ, long time) {
        setChunkTime(localChunkX, localChunkZ, time, false);
    }

    /** Sets when the chunk was mapped, and whether this version of it came from a teammate. */
    public void setChunkTime(int localChunkX, int localChunkZ, long time, boolean teammate) {
        int index = localChunkZ * CHUNKS + localChunkX;
        long bit = 1L << (index & 63);
        boolean wasTeammate = (fromTeam[index >> 6] & bit) != 0;
        if (chunkTimes[index] != time || wasTeammate != teammate) {
            chunkTimes[index] = time;
            if (teammate) {
                fromTeam[index >> 6] |= bit;
            } else {
                fromTeam[index >> 6] &= ~bit;
            }
            saveDirty = true;
        }
    }

    /** True if the chunk as we have it came from a teammate (see {@link #setChunkTime(int, int, long, boolean)}). */
    public boolean isFromTeammate(int localChunkX, int localChunkZ) {
        int index = localChunkZ * CHUNKS + localChunkX;
        return (fromTeam[index >> 6] & 1L << (index & 63)) != 0;
    }

    public boolean isSaveDirty() {
        return saveDirty;
    }

    /** Copies the data for saving on another thread and clears the dirty flag. */
    public Snapshot snapshotForSave() {
        saveDirty = false;
        saving = true;
        return new Snapshot(
            pixels.clone(),
            extra != null ? extra.clone() : null,
            chunkTimes.clone(),
            fromTeam.clone(),
            light != null ? light.clone() : null);
    }

    public static final class Snapshot {

        final int[] pixels;
        final byte[] extra;
        final long[] chunkTimes;
        final long[] fromTeam;
        final byte[] light;

        Snapshot(int[] pixels, byte[] extra, long[] chunkTimes, long[] fromTeam, byte[] light) {
            this.pixels = pixels;
            this.extra = extra;
            this.chunkTimes = chunkTimes;
            this.fromTeam = fromTeam;
            this.light = light;
        }
    }

    /** Whether a save of this region is queued or running, i.e. the file on disk may be out of date. */
    public boolean isSaving() {
        return saving;
    }

    public void onSaved() {
        saving = false;
    }

    public static File getFile(File dimensionDir, int rx, int rz) {
        return new File(dimensionDir, "r." + rx + "." + rz + ".png");
    }

    /** Size of the region's files on disk (image, extra, light, times), for the log. */
    static long bytesOnDisk(File imageFile) {
        return imageFile.length() + getExtraFile(imageFile).length()
            + getLightFile(imageFile).length()
            + getTimesFile(imageFile).length();
    }

    /** Which of the region's files are on disk and their sizes in KB, for the log. */
    static String partsOnDisk(File imageFile) {
        StringBuilder parts = new StringBuilder();
        File[] files = { imageFile, getExtraFile(imageFile), getLightFile(imageFile), getTimesFile(imageFile) };
        String[] names = { "png", "dat", "light", "time" };
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) {
                if (parts.length() > 0) {
                    parts.append('+');
                }
                parts.append(names[i])
                    .append(':')
                    .append((files[i].length() + 1023) >> 10)
                    .append("KB");
            }
        }
        return parts.length() == 0 ? "-" : parts.toString();
    }

    private static File getExtraFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".dat");
    }

    private static File getLightFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".light");
    }

    private static File getTimesFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".time");
    }

    public static void write(File file, Snapshot snapshot) throws IOException {
        writeImage(file, snapshot.pixels);
        if (snapshot.extra != null) {
            File extraFile = getExtraFile(file);
            File tmp = new File(extraFile.getPath() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
                out.write(snapshot.extra);
            }
            replace(tmp, extraFile);
        }
        if (snapshot.light != null) {
            File lightFile = getLightFile(file);
            File lightTmp = new File(lightFile.getPath() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(new FileOutputStream(lightTmp))) {
                out.write(snapshot.light);
            }
            replace(lightTmp, lightFile);
        }
        File timesFile = getTimesFile(file);
        File tmp = new File(timesFile.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            for (long time : snapshot.chunkTimes) {
                out.writeLong(time);
            }
            // Added later: older versions stop reading before it.
            for (long bits : snapshot.fromTeam) {
                out.writeLong(bits);
            }
        }
        replace(tmp, timesFile);
    }

    /** Puts the new file in place in one step where the file system can: a crash never leaves no file at all. */
    private static void replace(File tmp, File file) throws IOException {
        try {
            Files
                .move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeImage(File file, int[] data) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIZE, SIZE, data, 0, SIZE);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        if (!ImageIO.write(image, "png", tmp)) {
            throw new IOException("No PNG writer available");
        }
        replace(tmp, file);
    }

    public static MapRegion read(File file, int rx, int rz) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
            throw new IOException("Invalid map region image " + file);
        }
        MapRegion region = new MapRegion(rx, rz);
        image.getRGB(0, 0, SIZE, SIZE, region.pixels, 0, SIZE);
        File extraFile = getExtraFile(file);
        if (extraFile.isFile()) {
            byte[] extra = new byte[SIZE * SIZE];
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(extraFile)))) {
                in.readFully(extra);
                region.extra = extra;
            } catch (IOException e) {
                // Only the extra data is lost; the map image is still fine.
            }
        }
        File lightFile = getLightFile(file);
        if (lightFile.isFile()) {
            byte[] levels = new byte[SIZE * SIZE];
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(lightFile)))) {
                in.readFully(levels);
                region.light = levels;
            } catch (IOException e) {
                // Only the night glow is lost.
            }
        }
        File timesFile = getTimesFile(file);
        boolean haveTimes = false;
        if (timesFile.isFile()) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(timesFile)))) {
                for (int i = 0; i < region.chunkTimes.length; i++) {
                    region.chunkTimes[i] = in.readLong();
                }
                haveTimes = true;
                try {
                    for (int i = 0; i < region.fromTeam.length; i++) {
                        region.fromTeam[i] = in.readLong();
                    }
                } catch (EOFException e) {
                    // Saved before it was kept: everything counts as ours.
                    Arrays.fill(region.fromTeam, 0L);
                }
            } catch (IOException e) {
                // Fall back to the file's date below.
            }
        }
        if (!haveTimes) {
            // Saved before chunk times existed: every explored chunk counts as mapped when the file was written.
            long modified = file.lastModified();
            for (int cz = 0; cz < CHUNKS; cz++) {
                for (int cx = 0; cx < CHUNKS; cx++) {
                    if (region.hasPixels(cx, cz)) {
                        region.chunkTimes[cz * CHUNKS + cx] = modified;
                    }
                }
            }
        }
        return region;
    }
}
