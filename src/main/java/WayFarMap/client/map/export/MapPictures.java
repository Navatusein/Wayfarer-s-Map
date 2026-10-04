package WayFarMap.client.map.export;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.lwjgl.Sys;

import WayFarMap.WayFarMap;

/**
 * The pictures of the map the mod made, in {@link MapExport#folder()}: listing them, reading them small enough to
 * show (however big they are), copying one to the clipboard, opening and deleting them.
 */
public final class MapPictures {

    /** Color under the see-through parts of a picture copied to the clipboard (many programs show them black). */
    private static final int BACKGROUND = 0xFF0C0E11;
    /** Longest side of a picture put on the clipboard: bigger ones go there smaller. */
    public static final int CLIPBOARD_MAX = 4096;

    /** One picture: a PNG file, or the folder of a page that zooms in a browser. */
    public static final class Picture {

        /** The file or the folder: what is deleted. */
        public final File root;
        /** A page for the browser, with its whole picture in {@link #image}. */
        public final boolean site;
        /** The picture at full detail. */
        public final File image;
        /** A small copy to show, or the picture itself. */
        public final File preview;
        public final String name;
        public final long modified;
        /** Bytes on disk, of everything in a page's folder. */
        public final long bytes;

        Picture(File root, boolean site, File image, File preview, long bytes) {
            this.root = root;
            this.site = site;
            this.image = image;
            this.preview = preview;
            String fileName = root.getName();
            this.name = site ? fileName : fileName.substring(0, fileName.length() - 4);
            this.modified = root.lastModified();
            this.bytes = bytes;
        }

        /** What to open: the page in a browser, or the picture. */
        public File openable() {
            return site ? new File(root, "index.html") : image;
        }
    }

    private MapPictures() {}

    /** The pictures, newest first; ones being written or half written are left out. */
    public static List<Picture> list() {
        List<Picture> pictures = new ArrayList<>();
        File[] files = MapExport.folder()
            .listFiles();
        if (files == null) {
            return pictures;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (file.isFile() && name.toLowerCase()
                .endsWith(".png")) {
                pictures.add(new Picture(file, false, file, file, file.length()));
            } else if (file.isDirectory()) {
                File overview = new File(file, "overview.png");
                File preview = new File(file, "preview.png");
                // The page is written last: without it the export isn't done (or was stopped).
                boolean done = new File(file, "index.html").isFile();
                if (done && (overview.isFile() || preview.isFile())) {
                    File image = overview.isFile() ? overview : preview;
                    pictures.add(new Picture(file, true, image, preview.isFile() ? preview : image, size(file)));
                }
            }
        }
        pictures.sort((a, b) -> Long.compare(b.modified, a.modified));
        return pictures;
    }

    private static long size(File file) {
        File[] children = file.listFiles();
        if (children == null) {
            return file.length();
        }
        long total = 0;
        for (File child : children) {
            total += size(child);
        }
        return total;
    }

    /** Width and height of a picture from its header, or null if it can't be read. */
    public static int[] dimensions(File file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            ImageReader reader = reader(in);
            if (reader == null) {
                return null;
            }
            try {
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The picture with its longer side at most {@code maxSide} pixels, read skipping pixels so even a picture far
     * bigger than the memory fits; null if it can't be read.
     */
    public static BufferedImage read(File file, int maxSide) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            ImageReader reader = reader(in);
            if (reader == null) {
                return null;
            }
            try {
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, (longest + maxSide - 1) / maxSide);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            WayFarMap.LOG.warn("Could not read the map picture " + file, e);
            return null;
        }
    }

    private static ImageReader reader(ImageInputStream in) {
        if (in == null) {
            return null;
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        reader.setInput(in, true, true);
        return reader;
    }

    /**
     * Puts the picture on the clipboard (on a background thread: big pictures take a moment), on a dark background,
     * at most {@link #CLIPBOARD_MAX} pixels long.
     *
     * @param done told on that thread: null if it worked, else why not
     */
    public static void copy(Picture picture, java.util.function.Consumer<String> done) {
        Thread thread = new Thread(() -> {
            try {
                BufferedImage read = read(picture.image, CLIPBOARD_MAX);
                if (read == null) {
                    done.accept("unreadable");
                    return;
                }
                BufferedImage opaque = new BufferedImage(
                    read.getWidth(),
                    read.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
                Graphics2D g = opaque.createGraphics();
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setColor(new java.awt.Color(BACKGROUND, true));
                g.fillRect(0, 0, read.getWidth(), read.getHeight());
                g.drawImage(read, 0, 0, null);
                g.dispose();
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new ImageSelection(opaque), null);
                done.accept(null);
            } catch (Throwable t) {
                WayFarMap.LOG.warn("Could not copy the map picture", t);
                done.accept(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, "WayFarMap copy picture");
        thread.setDaemon(true);
        thread.start();
    }

    /** A picture for the clipboard. */
    private static final class ImageSelection implements Transferable {

        private final Image image;

        ImageSelection(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.imageFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    /** Opens the file (a picture, a page, a folder) with what the system uses for it. */
    public static void open(File file) {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop")
                .invoke(null);
            desktopClass.getMethod("open", File.class)
                .invoke(desktop, file);
            return;
        } catch (Throwable ignored) {
            // No AWT desktop (common on Linux): LWJGL knows the platform's own way.
        }
        try {
            URI uri = file.toURI();
            Sys.openURL(uri.toString());
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Couldn't open " + file, t);
        }
    }

    /** Deletes the picture (the file, or the page's folder); only inside the mod's pictures folder. */
    public static boolean delete(Picture picture) {
        if (!inside(picture.root)) {
            return false;
        }
        TilePyramid.deleteTree(picture.root);
        return !picture.root.exists();
    }

    /** Deletes every picture the mod made, and what unfinished ones left. */
    public static int deleteAll() {
        File[] files = MapExport.folder()
            .listFiles();
        if (files == null) {
            return 0;
        }
        int deleted = 0;
        for (File file : files) {
            if (inside(file)) {
                TilePyramid.deleteTree(file);
                deleted += file.exists() ? 0 : 1;
            }
        }
        return deleted;
    }

    /** Whether the file is in the mod's pictures folder, so deleting it can't reach anything else. */
    private static boolean inside(File file) {
        try {
            String folder = MapExport.folder()
                .getCanonicalPath() + File.separator;
            return file.getCanonicalPath()
                .startsWith(folder);
        } catch (IOException e) {
            return false;
        }
    }
}
