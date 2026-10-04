package WayFarMap.client.map.export;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

/**
 * Writes a map as a pyramid of PNG tiles with a page that shows it in a browser, zooming from the whole map down to
 * single blocks (like Dynmap's web map, without a server): {@code tiles/<level>/<x>_<y>.png}, where level 0 is the
 * finest and every next level is half as detailed, {@code map.js} describing them, {@code index.html}, and the whole
 * map as one picture at full detail, {@code overview.png} (written row by row, so it can be far bigger than the
 * memory), with a small copy, {@code preview.png}.
 */
public final class TilePyramid {

    /** The longest side of {@code preview.png}. */
    private static final int PREVIEW_MAX = 4096;
    /** Memory for a band of rows of {@code overview.png}. */
    private static final long BAND_BYTES = 64L << 20;
    private static final int MAX_LEVELS = 24;

    /** The finest tiles of a map. */
    public interface Source {

        /** Pixels per side of a tile. */
        int tileSize();

        /** Background threads to draw tiles with. */
        int threads();

        /** The finest tiles that may show something, as {@link #key} (they may still turn out empty). */
        Set<Long> tiles();

        /** The tile's pixels (ARGB, row by row), or null if it shows nothing. Called from several threads. */
        int[] render(int x, int y) throws IOException;
    }

    /** What the page needs to know besides the tiles. */
    public static final class Info {

        /** Shown as the page's title. */
        public String title = "";
        /** "2d" or "3d". */
        public String mode = "2d";
        /** Finest tile pixels per block (on the flat map pixel 0 of tile 0 is block 0, 0, shown under the mouse). */
        public double pixelsPerBlock = 1;
        /** Most screen pixels per finest tile pixel the page zooms in to. */
        public double maxZoom = 32;
        public int background = 0x0C0E11;
    }

    /** How the work goes; called from the writing thread. */
    public interface Progress {

        boolean cancelled();

        void progress(long done, long total);
    }

    public static final class CancelledException extends Exception {

        private static final long serialVersionUID = 1L;
    }

    private TilePyramid() {}

    public static long key(int x, int y) {
        return (long) x << 32 | y & 0xFFFFFFFFL;
    }

    /** Width and height in pixels of the whole map made of these tiles, {0, 0} if none. */
    public static long[] pictureSize(Set<Long> tiles, int size) {
        if (tiles.isEmpty()) {
            return new long[] { 0, 0 };
        }
        int[] b = bounds(tiles);
        return new long[] { (long) (b[2] - b[0] + 1) * size, (long) (b[3] - b[1] + 1) * size };
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyY(long key) {
        return (int) key;
    }

    /**
     * Draws and writes everything into the folder.
     *
     * @return the number of non-empty finest tiles
     */
    public static int write(Source source, Info info, File folder, Progress progress)
        throws IOException, CancelledException {
        int size = source.tileSize();
        File tiles = new File(folder, "tiles");
        Set<Long> wanted = source.tiles();
        long[] total = { wanted.size() + wanted.size() / 3 + 1 };
        AtomicLong done = new AtomicLong();

        // The finest level: drawn by the source.
        List<Set<Long>> levels = new ArrayList<>();
        Set<Long> finest = renderFinest(source, wanted, new File(tiles, "0"), progress, done, total);
        levels.add(finest);
        int[] finestBounds = bounds(finest);
        int tileRows = finestBounds[3] - finestBounds[1] + 1;
        total[0] = done.get() + finest.size() / 3 + tileRows + 1;

        // Coarser levels: each tile from the four below it, half the size, until the map fits in a couple of tiles.
        while (levels.size() < MAX_LEVELS && extent(levels.get(levels.size() - 1)) > 2) {
            int level = levels.size();
            Set<Long> children = levels.get(level - 1);
            Set<Long> parents = new HashSet<>();
            for (long child : children) {
                parents.add(key(Math.floorDiv(keyX(child), 2), Math.floorDiv(keyY(child), 2)));
            }
            File childDir = new File(tiles, String.valueOf(level - 1));
            File dir = new File(tiles, String.valueOf(level));
            mkdirs(dir);
            runAll(Math.min(4, source.threads()), new ArrayList<>(parents), key -> {
                int x = keyX(key), y = keyY(key);
                int[] pixels = new int[size * size];
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        long child = key(x * 2 + dx, y * 2 + dy);
                        if (children.contains(child)) {
                            int[] from = readPng(new File(childDir, keyX(child) + "_" + keyY(child) + ".png"), size);
                            if (from != null) {
                                halve(from, size, pixels, dx * size / 2, dy * size / 2);
                            }
                        }
                    }
                }
                writePng(new File(dir, x + "_" + y + ".png"), pixels, size);
                progress.progress(done.incrementAndGet(), Math.max(total[0], done.get() + 1));
            }, progress);
            levels.add(parents);
        }

        writePreview(levels, tiles, size, new File(folder, "preview.png"));
        writeOverview(
            finest,
            new File(tiles, "0"),
            size,
            new File(folder, "overview.png"),
            progress,
            () -> { progress.progress(done.incrementAndGet(), Math.max(total[0], done.get() + 1)); });
        writeScript(levels, size, info, new File(folder, "map.js"));
        copyViewer(new File(folder, "index.html"));
        progress.progress(total[0], total[0]);
        return finest.size();
    }

    /**
     * The whole map as one picture at full detail and nothing else: the finest tiles are drawn into a hidden folder
     * next to it, put together row by row (so it can be far bigger than the memory), and deleted.
     *
     * @return the number of non-empty finest tiles
     */
    public static int writePicture(Source source, File file, Progress progress) throws IOException, CancelledException {
        int size = source.tileSize();
        Set<Long> wanted = source.tiles();
        long[] total = { wanted.size() + 1 };
        AtomicLong done = new AtomicLong();
        File work = new File(file.getParentFile(), "." + file.getName() + ".tiles");
        try {
            Set<Long> finest = renderFinest(source, wanted, work, progress, done, total);
            int[] b = bounds(finest);
            total[0] = done.get() + b[3] - b[1] + 1;
            writeOverview(
                finest,
                work,
                size,
                file,
                progress,
                () -> { progress.progress(done.incrementAndGet(), Math.max(total[0], done.get() + 1)); });
            progress.progress(total[0], total[0]);
            return finest.size();
        } finally {
            deleteTree(work);
        }
    }

    /** Draws the source's finest tiles into the folder; the ones that show something. */
    private static Set<Long> renderFinest(Source source, Set<Long> wanted, File dir, Progress progress,
        AtomicLong done, long[] total) throws IOException, CancelledException {
        int size = source.tileSize();
        progress.progress(0, total[0]);
        Set<Long> finest = ConcurrentHashMap.newKeySet();
        mkdirs(dir);
        runAll(source.threads(), new ArrayList<>(wanted), key -> {
            int x = keyX(key), y = keyY(key);
            int[] pixels = source.render(x, y);
            if (pixels != null) {
                writePng(new File(dir, x + "_" + y + ".png"), pixels, size);
                finest.add(key);
            }
            progress.progress(done.incrementAndGet(), total[0]);
        }, progress);
        if (finest.isEmpty()) {
            throw new IOException("Nothing to export");
        }
        return finest;
    }

    /** Deletes a file or a folder with everything in it. */
    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private interface TileTask {

        void run(long key) throws IOException;
    }

    /** Runs the task for every key on a few threads; stops early when cancelled or on the first error. */
    private static void runAll(int threads, List<Long> keys, TileTask task, Progress progress)
        throws IOException, CancelledException {
        // Nearby tiles one after the other (along a Z curve, square blocks at a time): they need the same chunks
        // or regions.
        if (!keys.isEmpty()) {
            int[] b = bounds(new HashSet<>(keys));
            Collections.sort(keys, (a, c) -> Long.compare(morton(a, b), morton(c, b)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread thread = new Thread(r, "WayFarMap export");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long key : keys) {
                futures.add(pool.submit(() -> {
                    if (!progress.cancelled()) {
                        task.run(key);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                    throw new CancelledException();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (progress.cancelled()) {
            throw new CancelledException();
        }
    }

    private static long morton(long key, int[] bounds) {
        long x = keyX(key) - (long) bounds[0], y = keyY(key) - (long) bounds[1];
        long code = 0;
        for (int bit = 0; bit < 31; bit++) {
            code |= (x >> bit & 1) << 2 * bit | (y >> bit & 1) << 2 * bit + 1;
        }
        return code;
    }

    /** Tiles across the longer side of the area the tiles cover. */
    private static int extent(Set<Long> keys) {
        int[] b = bounds(keys);
        return Math.max(b[2] - b[0], b[3] - b[1]) + 1;
    }

    /** {minX, minY, maxX, maxY} of the tiles. */
    private static int[] bounds(Set<Long> keys) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (long key : keys) {
            minX = Math.min(minX, keyX(key));
            minY = Math.min(minY, keyY(key));
            maxX = Math.max(maxX, keyX(key));
            maxY = Math.max(maxY, keyY(key));
        }
        return new int[] { minX, minY, maxX, maxY };
    }

    /** Puts the tile at half size into the target at (tx, ty): each pixel the average of four, by their alpha. */
    static void halve(int[] from, int size, int[] target, int tx, int ty) {
        int half = size / 2;
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                int i = y * 2 * size + x * 2;
                target[(ty + y) * size + tx + x] = average(from[i], from[i + 1], from[i + size], from[i + size + 1]);
            }
        }
    }

    private static int average(int c0, int c1, int c2, int c3) {
        int a = 0, r = 0, g = 0, b = 0;
        for (int c : new int[] { c0, c1, c2, c3 }) {
            int alpha = c >>> 24;
            a += alpha;
            r += (c >> 16 & 0xFF) * alpha;
            g += (c >> 8 & 0xFF) * alpha;
            b += (c & 0xFF) * alpha;
        }
        if (a == 0) {
            return 0;
        }
        return (a + 2) / 4 << 24 | r / a << 16 | g / a << 8 | b / a;
    }

    /** A small picture of the whole map: the finest level that fits in {@link #PREVIEW_MAX} pixels. */
    private static void writePreview(List<Set<Long>> levels, File tiles, int size, File file) throws IOException {
        int level = 0;
        while (level < levels.size() - 1 && extent(levels.get(level)) * size > PREVIEW_MAX) {
            level++;
        }
        Set<Long> keys = levels.get(level);
        int[] b = bounds(keys);
        int width = (b[2] - b[0] + 1) * size, height = (b[3] - b[1] + 1) * size;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        File dir = new File(tiles, String.valueOf(level));
        for (long key : keys) {
            int[] pixels = readPng(new File(dir, keyX(key) + "_" + keyY(key) + ".png"), size);
            if (pixels != null) {
                image.setRGB((keyX(key) - b[0]) * size, (keyY(key) - b[1]) * size, size, size, pixels, 0, size);
            }
        }
        ImageIO.write(image, "png", file);
    }

    /**
     * The whole map at full detail in one picture, from the finest tiles, a band of rows at a time: as many rows as
     * fit in {@link #BAND_BYTES} (tiles are read again for every band of theirs when a row is very long).
     */
    private static void writeOverview(Set<Long> keys, File dir, int size, File file, Progress progress,
        Runnable tileRowDone) throws IOException, CancelledException {
        int[] b = bounds(keys);
        long wide = (long) (b[2] - b[0] + 1) * size, high = (long) (b[3] - b[1] + 1) * size;
        if (wide * 4 + 1 > Integer.MAX_VALUE || high > Integer.MAX_VALUE) {
            throw new IOException("The map is too big for one picture: " + wide + "x" + high);
        }
        int width = (int) wide, height = (int) high;
        int bandRows = (int) Math.max(1, Math.min(size, BAND_BYTES / (4L * width)));
        int[] band = new int[bandRows * width];
        File tmp = new File(file.getPath() + ".tmp");
        try (PngStream png = new PngStream(Files.newOutputStream(tmp.toPath()), width, height)) {
            for (int ty = b[1]; ty <= b[3]; ty++) {
                for (int r0 = 0; r0 < size; r0 += bandRows) {
                    if (progress.cancelled()) {
                        throw new CancelledException();
                    }
                    int rows = Math.min(bandRows, size - r0);
                    Arrays.fill(band, 0, rows * width, 0);
                    for (int tx = b[0]; tx <= b[2]; tx++) {
                        if (!keys.contains(key(tx, ty))) {
                            continue;
                        }
                        int[] pixels = readPng(new File(dir, tx + "_" + ty + ".png"), size);
                        if (pixels == null) {
                            continue;
                        }
                        for (int r = 0; r < rows; r++) {
                            System.arraycopy(pixels, (r0 + r) * size, band, r * width + (tx - b[0]) * size, size);
                        }
                    }
                    for (int r = 0; r < rows; r++) {
                        png.row(band, r * width);
                    }
                }
                tileRowDone.run();
            }
        } catch (IOException | CancelledException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
        if ((file.exists() && !file.delete()) || !tmp.renameTo(file)) {
            throw new IOException("Could not write " + file);
        }
    }

    /** {@code map.js}: the tiles of every level and how to show them (a script, so the page works from a file). */
    private static void writeScript(List<Set<Long>> levels, int size, Info info, File file) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("var WFM = {\n");
        s.append("  title: \"")
            .append(
                info.title.replace("\\", "\\\\")
                    .replace("\"", "\\\""))
            .append("\",\n");
        s.append("  mode: \"")
            .append(info.mode)
            .append("\",\n");
        s.append("  tile: ")
            .append(size)
            .append(",\n");
        s.append("  pixelsPerBlock: ")
            .append(info.pixelsPerBlock)
            .append(",\n");
        s.append("  maxZoom: ")
            .append(info.maxZoom)
            .append(",\n");
        s.append("  background: \"#")
            .append(String.format("%06X", info.background & 0xFFFFFF))
            .append("\",\n");
        int[] b = bounds(levels.get(0));
        s.append("  overview: [")
            .append((long) (b[2] - b[0] + 1) * size)
            .append(", ")
            .append((long) (b[3] - b[1] + 1) * size)
            .append("],\n");
        s.append("  levels: [\n");
        for (Set<Long> keys : levels) {
            s.append("    [");
            boolean first = true;
            for (long key : keys) {
                if (!first) {
                    s.append(',');
                }
                first = false;
                s.append(keyX(key))
                    .append(',')
                    .append(keyY(key));
            }
            s.append("],\n");
        }
        s.append("  ]\n};\n");
        try (Writer out = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            out.write(s.toString());
        }
    }

    private static void copyViewer(File file) throws IOException {
        try (InputStream in = TilePyramid.class.getResourceAsStream("/assets/wayfarmap/export/index.html")) {
            if (in == null) {
                throw new IOException("The map page is missing from the mod");
            }
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writePng(File file, int[] pixels, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("No PNG writer available");
            }
        }
    }

    /** The tile's pixels, or null if it can't be read. */
    static int[] readPng(File file, int size) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null || image.getWidth() != size || image.getHeight() != size) {
            return null;
        }
        int[] pixels = new int[size * size];
        image.getRGB(0, 0, size, size, pixels, 0, size);
        return pixels;
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
    }
}
