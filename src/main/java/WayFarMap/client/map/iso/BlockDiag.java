package WayFarMap.client.map.iso;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;

/**
 * Diagnostics of how blocks look on the 3D map, for {@link IsoLog}: what is known of each kind of block and how it is
 * drawn (from its icons or from pictures the game takes, and why), what each picture came out like (how much of it is
 * drawn, how bright, whether the game drew anything), per chunk what its blocks got, and where the tiles fell back to
 * icons for want of a picture. To find blocks drawn wrong: glass with shadows, an altar without its sides.
 */
final class BlockDiag {

    /** Pictures logged per kind of block when nothing is wrong with them; wrong ones up to {@link #MAX_BAD}. */
    private static final int SAMPLES = 3, MAX_BAD = 40;

    private BlockDiag() {}

    /** Kinds of block (id and metadata) described this session. */
    private static final Set<Integer> DESCRIBED = new HashSet<>();
    /** Per kind: pictures logged fine, pictures logged wrong, render errors logged. */
    private static final Map<Integer, int[]> LOGGED = new HashMap<>();
    /** Per kind, rays of the tiles that drew it from icons for want of a picture: see {@link #tracerFallbacks}. */
    private static final Map<Integer, long[]> FALLBACKS = new ConcurrentHashMap<>();

    /** Pictures saved as PNG per kind, fine and wrong, and in all this session. */
    private static final int PNG_OK = 2, PNG_BAD = 4, PNG_MAX = 3000;
    private static final Map<Integer, int[]> SAVED = new HashMap<>();
    private static final AtomicInteger savedFiles = new AtomicInteger();
    private static ExecutorService pngWriter;

    static void clear() {
        DESCRIBED.clear();
        LOGGED.clear();
        FALLBACKS.clear();
        SAVED.clear();
        VARIED.clear();
        savedFiles.set(0);
    }

    /** Kinds taken again in other ways to compare (see FaceRenderer.tryVariants). */
    private static final Set<Integer> VARIED = new HashSet<>();

    /** Whether the first block of this kind drawn by a tile entity renderer is to be taken in other ways too. */
    static boolean wantsVariants(int key) {
        return IsoLog.on() && VARIED.size() < 60 && VARIED.add(key);
    }

    /** A block taken another way, to compare with its usual pictures: logged and saved as PNG. */
    static void variant(Block block, int key, int x, int y, int z, String how, boolean cube, int views, Shot shot) {
        String png = savePng(block, key, x, y, z, "VARIANT-" + how, cube, views, shot.images);
        StringBuilder b = new StringBuilder("PICTURE_VARIANT ").append(how)
            .append(' ')
            .append(name(block))
            .append(':')
            .append((key >>> 16) & 15)
            .append(" at ")
            .append(x)
            .append(',')
            .append(y)
            .append(',')
            .append(z)
            .append(" gameDrew[pass0=")
            .append(shot.passBytes0 < 0 ? "not in pass" : shot.passBytes0 + "B")
            .append(" pass1=")
            .append(shot.passBytes1 < 0 ? "not in pass" : shot.passBytes1 + "B")
            .append(" tileEntityRenders=")
            .append(shot.tileEntitiesDrawn)
            .append("] views[");
        for (int view = 0; view < views; view++) {
            b.append(view == 0 ? "" : " ")
                .append("view")
                .append(view)
                .append('=')
                .append(shot.coverage[view])
                .append("%/bright")
                .append(shot.brightness[view]);
        }
        b.append(']');
        if (shot.error != null) {
            b.append(" error=")
                .append(shot.error);
        }
        if (png != null) {
            b.append(" png=")
                .append(png);
        }
        IsoLog.log(b.toString());
    }

    /** Whether the pictures of a block of this kind may still be saved as PNG (keep a copy of them). */
    static boolean wantsImages(int key) {
        if (!IsoLog.on() || savedFiles.get() >= PNG_MAX) {
            return false;
        }
        int[] saved = SAVED.get(key);
        return saved == null || saved[0] < PNG_OK || saved[1] < PNG_BAD;
    }

    static String name(Block block) {
        try {
            return String.valueOf(Block.blockRegistry.getNameForObject(block));
        } catch (RuntimeException e) {
            return block == null ? "null"
                : block.getClass()
                    .getName();
        }
    }

    static String name(int lookKey) {
        return name(Block.getBlockById(ChunkBlocks.blockId(lookKey))) + ":" + ((lookKey >>> 16) & 15);
    }

    private static String shape(int shape) {
        switch (shape) {
            case BlockLooks.SHAPE_NONE:
                return "none";
            case BlockLooks.SHAPE_BOXES:
                return "boxes";
            case BlockLooks.SHAPE_PLANES:
                return "planes";
            case BlockLooks.SHAPE_LIQUID:
                return "liquid";
            default:
                return String.valueOf(shape);
        }
    }

    /**
     * Describes a kind of block the first time a chunk with it is copied (render thread): the game's view of it and
     * the 3D map's, and whether the map may take pictures of it.
     */
    static void kind(int key, BlockLooks.Look look) {
        if (!IsoLog.on() || !DESCRIBED.add(key)) {
            return;
        }
        IsoLog.log("BLOCK_KIND " + describe(key, look));
    }

    /** The game's view of a kind of block and the 3D map's, and whether the map may take pictures of it. */
    static String describe(int key, BlockLooks.Look look) {
        int id = ChunkBlocks.blockId(key), meta = (key >>> 16) & 15;
        Block block = Block.getBlockById(id);
        StringBuilder b = new StringBuilder(name(block)).append(':')
            .append(meta)
            .append(" id=")
            .append(id)
            .append(" class=")
            .append(
                block == null ? "null"
                    : block.getClass()
                        .getName());
        try {
            b.append(" renderType=")
                .append(block.getRenderType())
                .append(" renderPass=")
                .append(block.getRenderBlockPass())
                .append(" inPass0=")
                .append(block.canRenderInPass(0))
                .append(" inPass1=")
                .append(block.canRenderInPass(1))
                .append(" opaqueCube=")
                .append(block.isOpaqueCube())
                .append(" normalBlock=")
                .append(block.renderAsNormalBlock())
                .append(" material=")
                .append(
                    block.getMaterial()
                        .isLiquid() ? "liquid"
                            : block.getMaterial()
                                .isOpaque() ? "opaque" : "see-through")
                .append(" tileEntity=")
                .append(block.hasTileEntity(meta))
                .append(" worldIcons=")
                .append(FaceRenderer.overridesWorldIconOf(block));
            b.append(
                String.format(
                    Locale.ROOT,
                    " bounds=%.2f,%.2f,%.2f..%.2f,%.2f,%.2f",
                    block.getBlockBoundsMinX(),
                    block.getBlockBoundsMinY(),
                    block.getBlockBoundsMinZ(),
                    block.getBlockBoundsMaxX(),
                    block.getBlockBoundsMaxY(),
                    block.getBlockBoundsMaxZ()));
        } catch (Throwable t) {
            b.append(" (game info failed: ")
                .append(t)
                .append(')');
        }
        b.append(" look[shape=")
            .append(shape(look.shape))
            .append(" complex=")
            .append(look.complex)
            .append(" fullCube=")
            .append(look.fullCube)
            .append(" opaque=")
            .append(look.opaque)
            .append(" translucent=")
            .append(look.translucent)
            .append(" skipSame=")
            .append(look.skipSame)
            .append(" noPictures=")
            .append(look.noPictures)
            .append(" lightPasses=")
            .append(look.lightPasses)
            .append(" tint=")
            .append(look.tint)
            .append("] icons[");
        String[] sides = { "down", "up", "north", "south", "west", "east" };
        for (int side = 0; side < 6; side++) {
            String icon;
            try {
                IIcon i = block == null ? null : block.getIcon(side, meta);
                icon = i == null ? "null" : i.getIconName();
            } catch (Throwable t) {
                icon = "error:" + t.getClass()
                    .getSimpleName();
            }
            b.append(side == 0 ? "" : " ")
                .append(sides[side])
                .append('=')
                .append(icon)
                .append(look.textures[side] == null ? "(UNREADABLE)" : "");
        }
        b.append("] pictures=")
            .append(
                look.noPictures ? "never (drawn from icons: noPictures)"
                    : look.shape == BlockLooks.SHAPE_LIQUID ? "never (liquid)"
                        : look.shape == BlockLooks.SHAPE_NONE && !look.complex ? "never (not drawn)"
                            : look.complex ? "yes (the map doesn't imitate its renderer)"
                                : "if needed (tile entity, glass touching glass, sides depending on the world)")
            .append(look.opaque ? " as 6 side pictures (solid cube)" : " as 4 view sprites (drawn from each map side)");
        return b.toString();
    }

    /** One block's pictures, as taken: how the game drew it and what each view came out like. */
    static final class Shot {

        int passBytes0 = -1, passBytes1 = -1, tileEntitiesDrawn;
        String error;
        /** Percent of pixels drawn at all, and fully (alpha over half); mean brightness of the drawn ones. */
        final int[] coverage = new int[6], solid = new int[6], brightness = new int[6];
        /** The shading taken out of each side of a cube (1 for none). */
        final float[] shade = { 1, 1, 1, 1, 1, 1 };
        /** Copies of the pictures, to save as PNG; null if not kept. */
        int[][] images;
    }

    /** The game failed drawing a block for its pictures. */
    static String error(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        return t.getClass()
            .getSimpleName() + ": "
            + t.getMessage()
            + (trace.length > 0 ? " at " + trace[0] : "");
    }

    /** Share of drawn pixels (percent) and their mean brightness (0-255) of a picture. */
    static void measure(int[] image, Shot shot, int view) {
        long sum = 0, weight = 0;
        int drawn = 0, solid = 0;
        for (int pixel : image) {
            int alpha = pixel >>> 24;
            if (alpha >= 8) {
                drawn++;
                if (alpha >= 128) {
                    solid++;
                }
                weight += alpha;
                sum += (long) alpha
                    * ((((pixel >> 16) & 0xFF) * 299 + ((pixel >> 8) & 0xFF) * 587 + (pixel & 0xFF) * 114) / 1000);
            }
        }
        // Rounded up: a picture with a few pixels drawn isn't 0%.
        shot.coverage[view] = (drawn * 100 + image.length - 1) / image.length;
        shot.solid[view] = (solid * 100 + image.length - 1) / image.length;
        shot.brightness[view] = weight == 0 ? 0 : (int) (sum / weight);
        if (shot.images != null && view < shot.images.length) {
            shot.images[view] = image.clone();
        }
    }

    /**
     * A block's pictures were taken (render thread): logged for the first few of its kind, and whenever they look
     * wrong: nothing drawn by the game, empty although a side the map sees is open, some views empty, a side much
     * darker than the others (shadows), holes in a solid cube, a render error.
     */
    static void picture(Block block, int key, TileEntity tileEntity, int x, int y, int z, boolean cube, int exposed,
        String why, int[] ids, int views, Shot shot) {
        if (!IsoLog.on()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        int empty = 0, brightest = 0;
        for (int view = 0; view < views; view++) {
            if (ids[view] == FacePalette.EMPTY || shot.coverage[view] == 0) {
                empty++;
            }
            // Only sides the map can show (a hidden side's picture shows the block from inside, never used).
            if (!cube || (exposed & 1 << view) != 0) {
                brightest = Math.max(brightest, shot.brightness[view]);
            }
        }
        boolean drewNothing = shot.passBytes0 <= 0 && shot.passBytes1 <= 0 && shot.tileEntitiesDrawn == 0;
        if (shot.error != null) {
            problems.add("RENDER_ERROR");
        }
        if (drewNothing) {
            problems.add("GAME_DREW_NOTHING");
        }
        // Sides the views can see: up and the four around (the views look from above).
        boolean seen = (exposed & ~1) != 0;
        if (empty == views && seen) {
            problems.add("EMPTY_BUT_VISIBLE");
        } else if (empty > 0 && !cube && (exposed & 2) != 0) {
            // With its top open every view sees some of it (a glass pane open to one side only is empty from the
            // views behind it, as it should be).
            problems.add("SOME_VIEWS_EMPTY");
        }
        for (int view = 0; view < views; view++) {
            boolean open = !cube || (exposed & 1 << view) != 0;
            if (!open || shot.coverage[view] == 0) {
                continue;
            }
            // Views of a sprite show different sides of it (a machine's front): compared only between a cube's sides.
            if (cube && brightest > 40 && shot.brightness[view] < brightest * 55 / 100) {
                problems.add("DARK_VIEW" + view);
            }
            if (cube && shot.solid[view] < 95 && shot.coverage[view] > 0) {
                problems.add("HOLES_SIDE" + view);
            }
        }
        int[] logged = LOGGED.computeIfAbsent(key, k -> new int[3]);
        boolean bad = !problems.isEmpty();
        String png = null;
        if (shot.images != null) {
            int[] saved = SAVED.computeIfAbsent(key, k -> new int[2]);
            if (bad ? saved[1] < PNG_BAD : saved[0] < PNG_OK) {
                saved[bad ? 1 : 0]++;
                png = savePng(block, key, x, y, z, bad ? String.join("-", problems) : "OK", cube, views, shot.images);
            }
        }
        if (bad ? logged[1]++ >= MAX_BAD : logged[0]++ >= SAMPLES) {
            if (png == null) {
                return;
            }
        }
        StringBuilder b = new StringBuilder("PICTURE ").append(bad ? String.join(",", problems) : "OK")
            .append(' ')
            .append(name(block))
            .append(':')
            .append((key >>> 16) & 15)
            .append(
                tileEntity == null ? ""
                    : " [" + tileEntity.getClass()
                        .getSimpleName() + "]")
            .append(" at ")
            .append(x)
            .append(',')
            .append(y)
            .append(',')
            .append(z)
            .append(cube ? " cube(6 sides)" : " sprite(4 views)")
            .append(" why=")
            .append(why)
            .append(" openSides=")
            .append(sides(exposed))
            .append(" gameDrew[pass0=")
            .append(shot.passBytes0 < 0 ? "not in pass" : shot.passBytes0 + "B")
            .append(" pass1=")
            .append(shot.passBytes1 < 0 ? "not in pass" : shot.passBytes1 + "B")
            .append(" tileEntityRenders=")
            .append(shot.tileEntitiesDrawn)
            .append("] views[");
        for (int view = 0; view < views; view++) {
            b.append(view == 0 ? "" : " ")
                .append(cube ? new String[] { "down", "up", "north", "south", "west", "east" }[view] : "view" + view)
                .append('=')
                .append(
                    ids[view] == FacePalette.EMPTY ? "EMPTY"
                        : ids[view] == 0 ? "NOT_TAKEN"
                            : shot.coverage[view] + "%(solid " + shot.solid[view] + "%)/bright" + shot.brightness[view])
                .append(cube && (exposed & 1 << view) == 0 ? "(hidden)" : "")
                .append(
                    cube && shot.shade[view] < 1f ? String.format(Locale.ROOT, "(shade %.2f)", shot.shade[view]) : "");
        }
        b.append(']');
        if (png != null) {
            b.append(" png=")
                .append(png);
        }
        if (shot.error != null) {
            b.append(" error=")
                .append(shot.error);
        }
        IsoLog.log(b.toString());
    }

    /**
     * Saves a block's pictures side by side as a PNG next to the log (in the background): the top row over a
     * checkerboard (see-through parts show it), the bottom row their alpha (white drawn, black empty); sides of a cube
     * are 32 pixels, drawn 4 times larger. Returns the file's name, null if it isn't saved.
     */
    private static String savePng(Block block, int key, int x, int y, int z, String what, boolean cube, int views,
        int[][] images) {
        File directory = IsoLog.pictureDirectory();
        if (directory == null || savedFiles.incrementAndGet() > PNG_MAX) {
            return null;
        }
        String name = (name(block) + "_" + ((key >>> 16) & 15) + "_" + x + "_" + y + "_" + z + "_" + what)
            .replaceAll("[^A-Za-z0-9._-]", "_") + ".png";
        int[][] copies = images.clone();
        synchronized (BlockDiag.class) {
            if (pngWriter == null) {
                pngWriter = Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "WayFarMap 3D log pictures");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
            }
        }
        pngWriter.submit(() -> writePng(new File(directory, name), cube, views, copies));
        return name;
    }

    static void writePng(File file, boolean cube, int views, int[][] images) {
        try {
            int cell = 128;
            BufferedImage out = new BufferedImage(views * cell, cell * 2, BufferedImage.TYPE_INT_RGB);
            for (int view = 0; view < views; view++) {
                int[] image = images[view];
                if (image == null) {
                    continue;
                }
                int side = (int) Math.round(Math.sqrt(image.length));
                for (int py = 0; py < cell; py++) {
                    for (int px = 0; px < cell; px++) {
                        // Larger ones (wide sprites) shrunk to fit.
                        int pixel = image[(py * side / cell) * side + px * side / cell];
                        int alpha = pixel >>> 24;
                        int checker = ((px >> 3) + (py >> 3) & 1) == 0 ? 0xC0 : 0x80;
                        int r = ((pixel >> 16 & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                        int g = ((pixel >> 8 & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                        int b = ((pixel & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                        out.setRGB(view * cell + px, py, r << 16 | g << 8 | b);
                        out.setRGB(view * cell + px, cell + py, alpha << 16 | alpha << 8 | alpha);
                    }
                }
            }
            File parent = file.getParentFile();
            if (parent.isDirectory() || parent.mkdirs()) {
                ImageIO.write(out, "png", file);
            }
        } catch (Throwable t) {
            IsoLog.log("PNG_FAILED " + file.getName() + " " + t);
        }
    }

    private static String sides(int mask) {
        StringBuilder b = new StringBuilder();
        String[] names = { "D", "U", "N", "S", "W", "E" };
        for (int side = 0; side < 6; side++) {
            if ((mask & 1 << side) != 0) {
                b.append(names[side]);
            }
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    /**
     * What the blocks of a stored copy got (writer thread), per kind: cells, cells with pictures, those with every
     * picture, with some not taken, with all empty.
     */
    static void chunkReport(int cx, int cz, ChunkBlocks blocks) {
        if (!IsoLog.on()) {
            return;
        }
        Map<Integer, int[]> kinds = new HashMap<>();
        for (int cell : blocks.cells) {
            if (ChunkBlocks.blockId(cell) != 0) {
                kinds.computeIfAbsent(ChunkBlocks.lookKey(cell), k -> new int[5])[0]++;
            }
        }
        int picturesTotal = 0, notTaken = 0, allEmpty = 0;
        for (int n = 0; n < blocks.faceCells.length; n++) {
            int cell = blocks.cells[blocks.faceCells[n]];
            int[] stats = kinds.computeIfAbsent(ChunkBlocks.lookKey(cell), k -> new int[5]);
            stats[1]++;
            picturesTotal++;
            int missing = 0, empty = 0;
            for (int slot = 0; slot < ChunkBlocks.VIEWS; slot++) {
                int id = blocks.faceIds[n * ChunkBlocks.PER_CELL + slot];
                if (id == 0) {
                    missing++;
                } else if (id == FacePalette.EMPTY) {
                    empty++;
                }
            }
            if (missing > 0) {
                stats[3]++;
                notTaken++;
            } else {
                stats[2]++;
            }
            if (empty == ChunkBlocks.VIEWS) {
                stats[4]++;
                allEmpty++;
            }
        }
        List<Map.Entry<Integer, int[]>> list = new ArrayList<>(kinds.entrySet());
        list.sort((a, c) -> c.getValue()[0] - a.getValue()[0]);
        StringBuilder b = new StringBuilder("CHUNK_REPORT ").append(cx)
            .append(',')
            .append(cz)
            .append(" heights=")
            .append(blocks.yMin)
            .append("..")
            .append(blocks.yMax)
            .append(" kinds=")
            .append(kinds.size())
            .append(" withPictures=")
            .append(picturesTotal)
            .append(" picturesNotTaken=")
            .append(notTaken)
            .append(" allViewsEmpty=")
            .append(allEmpty)
            .append(" blocks[");
        boolean first = true;
        for (Map.Entry<Integer, int[]> kind : list) {
            int[] s = kind.getValue();
            b.append(first ? "" : "; ")
                .append(name(kind.getKey()))
                .append(" x")
                .append(s[0]);
            if (s[1] > 0) {
                b.append(" pics=")
                    .append(s[2])
                    .append('/')
                    .append(s[1]);
                if (s[3] > 0) {
                    b.append(" NOT_TAKEN=")
                        .append(s[3]);
                }
                if (s[4] > 0) {
                    b.append(" allEmpty=")
                        .append(s[4]);
                }
            }
            first = false;
        }
        IsoLog.log(
            b.append(']')
                .toString());
    }

    /** Rays of a tile that drew blocks from icons for want of a picture, merged in (renderer threads). */
    static void tracerFallbacks(Map<Integer, int[]> counts) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            long[] total = FALLBACKS.computeIfAbsent(entry.getKey(), k -> new long[3]);
            synchronized (total) {
                for (int i = 0; i < 3; i++) {
                    total[i] += entry.getValue()[i];
                }
            }
        }
    }

    /** For the summaries: kinds of block the tiles drew from icons though pictures should have shown them. */
    static void fallbackSummary(String title) {
        List<Map.Entry<Integer, long[]>> list = new ArrayList<>(FALLBACKS.entrySet());
        if (list.isEmpty()) {
            return;
        }
        list.sort((a, c) -> Long.compare(sum(c.getValue()), sum(a.getValue())));
        IsoLog.log(
            title + " tiles drawing blocks from icons for want of a picture (rays): noPicture = the copy has none for"
                + " the block, spriteUnreadable = its picture couldn't be read, sideNoPicture = a solid cube's side"
                + " without one");
        for (int n = 0; n < Math.min(30, list.size()); n++) {
            long[] c = list.get(n)
                .getValue();
            IsoLog.log(
                title + "   fallback#"
                    + (n + 1)
                    + " "
                    + name(
                        list.get(n)
                            .getKey())
                    + " noPicture="
                    + c[0]
                    + " spriteUnreadable="
                    + c[1]
                    + " sideNoPicture="
                    + c[2]);
        }
    }

    private static long sum(long[] values) {
        return values[0] + values[1] + values[2];
    }
}
