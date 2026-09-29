package WayFarMap.client.map.iso;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    static void clear() {
        DESCRIBED.clear();
        LOGGED.clear();
        FALLBACKS.clear();
    }

    static String name(Block block) {
        try {
            return String.valueOf(Block.blockRegistry.getNameForObject(block));
        } catch (RuntimeException e) {
            return block == null ? "null" : block.getClass()
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
        int id = ChunkBlocks.blockId(key), meta = (key >>> 16) & 15;
        Block block = Block.getBlockById(id);
        StringBuilder b = new StringBuilder("BLOCK_KIND ").append(name(block))
            .append(':')
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
            .append(
                look.opaque ? " as 6 side pictures (solid cube)" : " as 4 view sprites (drawn from each map side)");
        IsoLog.log(b.toString());
    }

    /** One block's pictures, as taken: how the game drew it and what each view came out like. */
    static final class Shot {

        int passBytes0 = -1, passBytes1 = -1, tileEntitiesDrawn;
        String error;
        final int[] coverage = new int[6], brightness = new int[6];
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
        long sum = 0;
        int drawn = 0;
        for (int pixel : image) {
            if ((pixel >>> 24) >= 128) {
                drawn++;
                sum += (((pixel >> 16) & 0xFF) * 299 + ((pixel >> 8) & 0xFF) * 587 + (pixel & 0xFF) * 114) / 1000;
            }
        }
        shot.coverage[view] = drawn * 100 / image.length;
        shot.brightness[view] = drawn == 0 ? 0 : (int) (sum / drawn);
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
            brightest = Math.max(brightest, shot.brightness[view]);
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
        } else if (empty > 0 && !cube) {
            problems.add("SOME_VIEWS_EMPTY");
        }
        for (int view = 0; view < views; view++) {
            boolean open = !cube || (exposed & 1 << view) != 0;
            if (!open || shot.coverage[view] == 0) {
                continue;
            }
            if (brightest > 40 && shot.brightness[view] < brightest * 55 / 100) {
                problems.add("DARK_VIEW" + view);
            }
            if (cube && shot.coverage[view] < 95 && shot.coverage[view] > 0) {
                problems.add("HOLES_SIDE" + view);
            }
        }
        int[] logged = LOGGED.computeIfAbsent(key, k -> new int[3]);
        boolean bad = !problems.isEmpty();
        if (bad ? logged[1]++ >= MAX_BAD : logged[0]++ >= SAMPLES) {
            return;
        }
        StringBuilder b = new StringBuilder("PICTURE ").append(bad ? String.join(",", problems) : "OK")
            .append(' ')
            .append(name(block))
            .append(':')
            .append((key >>> 16) & 15)
            .append(tileEntity == null ? ""
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
                        : ids[view] == 0 ? "NOT_TAKEN" : shot.coverage[view] + "%/bright" + shot.brightness[view]);
        }
        b.append(']');
        if (shot.error != null) {
            b.append(" error=")
                .append(shot.error);
        }
        IsoLog.log(b.toString());
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
