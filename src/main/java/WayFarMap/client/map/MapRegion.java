package WayFarMap.client.map;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * A 512x512 block area of the map (32x32 chunks). Pixels are ARGB; a pixel with alpha 0 has not been explored.
 */
public class MapRegion {

    public static final int SIZE = 512;
    public static final int SHIFT = 9;

    /** Minimum time between two texture uploads of the same region. */
    private static final long UPLOAD_INTERVAL_MS = 250;

    public final int rx;
    public final int rz;
    private final int[] pixels = new int[SIZE * SIZE];

    private DynamicTexture texture;
    private boolean textureDirty = true;
    private long lastUpload;
    private volatile boolean saveDirty;
    private volatile boolean saving;

    public MapRegion(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
    }

    public void setPixel(int localX, int localZ, int argb) {
        int index = localZ * SIZE + localX;
        if (pixels[index] != argb) {
            pixels[index] = argb;
            textureDirty = true;
            saveDirty = true;
        }
    }

    public int getPixel(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    /** Returns the GL texture of this region, uploading pending changes first. Must be called on the render thread. */
    public int getTextureId() {
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE);
            textureDirty = true;
            lastUpload = 0;
        }
        long now = System.currentTimeMillis();
        if (textureDirty && now - lastUpload >= UPLOAD_INTERVAL_MS) {
            System.arraycopy(pixels, 0, texture.getTextureData(), 0, pixels.length);
            texture.updateDynamicTexture();
            textureDirty = false;
            lastUpload = now;
        }
        return texture.getGlTextureId();
    }

    public void deleteTexture() {
        if (texture != null) {
            texture.deleteGlTexture();
            texture = null;
        }
    }

    public boolean isSaveDirty() {
        return saveDirty;
    }

    /** Copies the pixels for saving on another thread and clears the dirty flag. */
    public int[] snapshotForSave() {
        saveDirty = false;
        saving = true;
        return pixels.clone();
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

    public static void write(File file, int[] data) throws IOException {
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
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("Could not rename " + tmp + " to " + file);
        }
    }

    public static MapRegion read(File file, int rx, int rz) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
            throw new IOException("Invalid map region image " + file);
        }
        MapRegion region = new MapRegion(rx, rz);
        image.getRGB(0, 0, SIZE, SIZE, region.pixels, 0, SIZE);
        return region;
    }
}
