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
 * finest and every next level is half as detailed, {@code map.js} describing them, {@code index.html} and a picture
 * of the whole map, {@code overview.png}.
 */
public final class TilePyramid {

    /** The longest side of {@code overview.png}. */
    private static final int OVERVIEW_MAX = 4096;
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
        progress.progress(0, total[0]);

        // The finest level: drawn by the source.
        List<Set<Long>> levels = new ArrayList<>();
        Set<Long> finest = ConcurrentHashMap.newKeySet();
        File level0 = new File(tiles, "0");
        mkdirs(level0);
        runAll(source.threads(), new ArrayList<>(wanted), key -> {
            int x = keyX(key), y = keyY(key);
            int[] pixels = source.render(x, y);
            if (pixels != null) {
                writePng(new File(level0, x + "_" + y + ".png"), pixels, size);
                finest.add(key);
            }
            progress.progress(done.incrementAndGet(), total[0]);
        }, progress);
        levels.add(finest);
        if (finest.isEmpty()) {
            throw new IOException("Nothing to export");
        }
        total[0] = done.get() + finest.size() / 3 + 1;

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

        writeOverview(levels, tiles, size, new File(folder, "overview.png"));
        writeScript(levels, size, info, new File(folder, "map.js"));
        copyViewer(new File(folder, "index.html"));
        progress.progress(total[0], total[0]);
        return finest.size();
    }

    private interface TileTask {

        void run(long key) throws IOException;
    }

    /** Runs the task for every key on a few threads; stops early when cancelled or on the first error. */
    private static void runAll(int threads, List<Long> keys, TileTask task, Progress progress)
        throws IOException, CancelledException {
        // Nearby tiles one after the other: they need the same chunks.
        Collections.sort(keys, (a, b) -> {
            int c = Integer.compare(keyY(a), keyY(b));
            return c != 0 ? c : Integer.compare(keyX(a), keyX(b));
        });
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

    /** The whole map in one picture: the finest level that fits in {@link #OVERVIEW_MAX} pixels. */
    private static void writeOverview(List<Set<Long>> levels, File tiles, int size, File file) throws IOException {
        int level = 0;
        while (level < levels.size() - 1 && extent(levels.get(level)) * size > OVERVIEW_MAX) {
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
