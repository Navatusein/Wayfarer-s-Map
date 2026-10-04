package WayFarMap.client.map.export;

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
     * at most {@link #CLIPBOARD_MAX} pixels long. With the system's own tool first (PowerShell on Windows, osascript
     * on macOS, wl-copy or xclip on Linux): Java's clipboard is often unusable in the game (headless with lwjgl3ify,
     * and on Windows it hands the picture over only when pasted, which fails); it is the last try.
     *
     * @param done told on that thread: null if it worked, else why not
     */
    public static void copy(Picture picture, java.util.function.Consumer<String> done) {
        Thread thread = new Thread(() -> {
            File file = null;
            try {
                BufferedImage read = read(picture.image, CLIPBOARD_MAX);
                if (read == null) {
                    done.accept("unreadable");
                    return;
                }
                BufferedImage opaque = opaque(read);
                file = File.createTempFile("wayfarmap-copy", ".png");
                file.deleteOnExit();
                if (!ImageIO.write(opaque, "png", file)) {
                    done.accept("no PNG writer");
                    return;
                }
                String nativeFailure = copyWithSystem(file);
                if (nativeFailure == null) {
                    done.accept(null);
                    return;
                }
                if (GraphicsEnvironment.isHeadless()) {
                    done.accept(nativeFailure);
                    return;
                }
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new ImageSelection(opaque), null);
                done.accept(null);
            } catch (Throwable t) {
                WayFarMap.LOG.warn("Could not copy the map picture", t);
                done.accept(t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                if (file != null) {
                    file.delete();
                }
            }
        }, "WayFarMap copy picture");
        thread.setDaemon(true);
        thread.start();
    }

    /** The picture on the dark background, without see-through parts (many programs paste them black). */
    private static BufferedImage opaque(BufferedImage image) {
        BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = opaque.createGraphics();
        g.setColor(new java.awt.Color(BACKGROUND, true));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return opaque;
    }

    /** Puts the PNG file on the clipboard with the system's tool; null if it worked, else why not. */
    private static String copyWithSystem(File png) {
        String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        String path = png.getAbsolutePath();
        List<List<String>> tries = new ArrayList<>();
        if (os.contains("win")) {
            // Copies the picture itself into the clipboard, so it stays there after PowerShell ends.
            String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                + "$i = [System.Drawing.Image]::FromFile('"
                + path.replace("'", "''")
                + "'); [System.Windows.Forms.Clipboard]::SetImage($i); $i.Dispose()";
            tries.add(Arrays.asList("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command", script));
        } else if (os.contains("mac")) {
            String escaped = path.replace("\\", "\\\\")
                .replace("\"", "\\\"");
            tries.add(
                Arrays.asList(
                    "osascript",
                    "-e",
                    "set the clipboard to (read (POSIX file \"" + escaped + "\") as \u00ABclass PNGf\u00BB)"));
        } else {
            if (System.getenv("WAYLAND_DISPLAY") != null) {
                tries.add(Arrays.asList("wl-copy", "--type", "image/png"));
            }
            tries.add(Arrays.asList("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path));
        }
        String failure = "no clipboard tool";
        for (List<String> command : tries) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                // Not read: the tools may stay running to serve the clipboard (xclip, wl-copy), keeping a pipe open.
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                String tool = command.get(0);
                if (tool.equals("wl-copy")) {
                    builder.redirectInput(png);
                }
                Process process = builder.start();
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroy();
                    failure = tool + " took too long";
                } else if (process.exitValue() != 0) {
                    failure = tool + " failed (" + process.exitValue() + ")";
                } else {
                    return null;
                }
            } catch (IOException e) {
                failure = "no " + command.get(0) + " (install xclip or wl-clipboard)";
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return "interrupted";
            }
        }
        WayFarMap.LOG.warn("Could not copy the map picture with the system's tool: {}", failure);
        return failure;
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
