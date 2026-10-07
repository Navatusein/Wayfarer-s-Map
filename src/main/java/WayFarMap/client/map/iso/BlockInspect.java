package WayFarMap.client.map.iso;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import WayFarMap.Config;

/**
 * Everything about how one block gets onto the 3D map, for {@code /wfmap3d x y z}: the game's view of it (renderer,
 * render passes, bounds, tile entity and its render box), the blocks around it, what the map's copy of its chunk holds
 * for it (its look, the ids of its pictures), its pictures as stored and taken again now in several ways (PNG), and
 * rays through it followed step by step (which cell, which picture and pixel, what was added), with a picture of the
 * map around it as the tracer draws it. Written to a folder and a zip of it, to be sent as one file. Render thread.
 */
public final class BlockInspect {

    private static final String[] SIDES = { "down", "up", "north", "south", "west", "east" };
    /** Rays followed through the block, per row and column of a grid over its outline on the screen. */
    private static final int RAY_GRID = 5;
    /** Blocks of the projection plane around the block in the map picture, and its pixels per block. */
    private static final int AROUND = 3, PICTURE_PIXELS_PER_BLOCK = 64, PICTURE_ZOOM = 2;

    private BlockInspect() {}

    /** Writes the report; returns the zip, or throws if it couldn't be written. */
    public static File run(World world, int x, int y, int z) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        String time = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        File base = new File(new File(mc.mcDataDir, "wayfarmap"), "inspect");
        File directory = new File(base, time + "_" + x + "_" + y + "_" + z);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        StringBuilder r = new StringBuilder();
        r.append("WayFarMap 3D map: block ")
            .append(x)
            .append(',')
            .append(y)
            .append(',')
            .append(z)
            .append(" in dimension ")
            .append(world.provider.dimensionId)
            .append(", ")
            .append(time)
            .append("\nmap view side (rotation) ")
            .append(Config.isoRotation & 3)
            .append(", pictures per block side: 3D quality ")
            .append(Config.isoPixelsPerBlock())
            .append(" px/block\n");
        int key = game(r, world, x, y, z);
        around(r, world, x, y, z);
        FacePalette palette = IsoMap.INSTANCE.palette();
        IsoMap.Dimension dimension = IsoMap.INSTANCE.dimension(world.provider.dimensionId);
        stored(r, directory, dimension, palette, x, y, z);
        fresh(r, directory, world, palette, x, y, z, key);
        rays(r, directory, dimension, palette, x, y, z);
        try (Writer out = new OutputStreamWriter(
            new FileOutputStream(new File(directory, "report.txt")),
            StandardCharsets.UTF_8)) {
            out.write(r.toString());
        }
        File zip = new File(base, directory.getName() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            File[] files = directory.listFiles();
            if (files != null) {
                Arrays.sort(files);
                byte[] buffer = new byte[1 << 15];
                for (File file : files) {
                    out.putNextEntry(new ZipEntry(directory.getName() + "/" + file.getName()));
                    try (FileInputStream in = new FileInputStream(file)) {
                        for (int n; (n = in.read(buffer)) > 0;) {
                            out.write(buffer, 0, n);
                        }
                    }
                    out.closeEntry();
                }
            }
        }
        return zip;
    }

    // ---------------------------------------------------------------- the game's view

    private static int game(StringBuilder r, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        int key = Block.getIdFromBlock(block) | meta << 16;
        r.append("\n== BLOCK (as the game has it)\n")
            .append(BlockDiag.describe(key, BlockLooks.get(key)))
            .append('\n');
        try {
            block.setBlockBoundsBasedOnState(world, x, y, z);
            r.append(
                String.format(
                    Locale.ROOT,
                    "bounds here: %.3f,%.3f,%.3f..%.3f,%.3f,%.3f%n",
                    block.getBlockBoundsMinX(),
                    block.getBlockBoundsMinY(),
                    block.getBlockBoundsMinZ(),
                    block.getBlockBoundsMaxX(),
                    block.getBlockBoundsMaxY(),
                    block.getBlockBoundsMaxZ()));
        } catch (Throwable t) {
            r.append("bounds here: failed ")
                .append(t)
                .append('\n');
        }
        r.append("icons here (world-aware):");
        for (int side = 0; side < 6; side++) {
            String icon;
            try {
                IIcon i = block.getIcon(world, x, y, z, side);
                icon = i == null ? "null" : i.getIconName();
            } catch (Throwable t) {
                icon = "error:" + t;
            }
            r.append(' ')
                .append(SIDES[side])
                .append('=')
                .append(icon);
        }
        r.append("\nlight: sky=")
            .append(world.getSavedLightValue(EnumSkyBlock.Sky, x, y, z))
            .append(" block=")
            .append(world.getSavedLightValue(EnumSkyBlock.Block, x, y, z))
            .append(" lightValue=")
            .append(safe(() -> block.getLightValue(world, x, y, z)))
            .append(" lightOpacity=")
            .append(safe(() -> block.getLightOpacity(world, x, y, z)))
            .append('\n');
        TileEntity tileEntity = world.getTileEntity(x, y, z);
        if (tileEntity == null) {
            r.append("tile entity: none\n");
        } else {
            boolean special = TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity);
            r.append("tile entity: ")
                .append(
                    tileEntity.getClass()
                        .getName())
                .append(" renderer=")
                .append(
                    special ? TileEntityRendererDispatcher.instance.getSpecialRenderer(tileEntity)
                        .getClass()
                        .getName() : "none")
                .append(" renderBox=")
                .append(safe(tileEntity::getRenderBoundingBox))
                .append(" inPass0=")
                .append(safe(() -> tileEntity.shouldRenderInPass(0)))
                .append(" inPass1=")
                .append(safe(() -> tileEntity.shouldRenderInPass(1)))
                .append(" maxRenderDistanceSq=")
                .append(safe(tileEntity::getMaxRenderDistanceSquared))
                .append('\n');
        }
        return key;
    }

    private static void around(StringBuilder r, World world, int x, int y, int z) {
        int covered = FaceRenderer.coveredSides(world, x, y, z);
        r.append(
            "\n== AROUND (sides hidden by a neighbour are left out of its sprites: a solid cube, or a block whose own"
                + " picture filled its whole outline; read before the pictures below were taken)\n");
        int[][] offsets = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        for (int side = 0; side < 6; side++) {
            int nx = x + offsets[side][0], ny = y + offsets[side][1], nz = z + offsets[side][2];
            Block block = world.getBlock(nx, ny, nz);
            int key = Block.getIdFromBlock(block) | world.getBlockMetadata(nx, ny, nz) << 16;
            BlockLooks.Look look = BlockLooks.get(key);
            TileEntity tileEntity = world.getTileEntity(nx, ny, nz);
            r.append(String.format(Locale.ROOT, "%-5s %d,%d,%d ", SIDES[side], nx, ny, nz))
                .append(BlockDiag.name(key))
                .append(" opaque=")
                .append(look.opaque)
                .append(" fullCube=")
                .append(look.fullCube)
                .append(" translucent=")
                .append(look.translucent)
                .append(" complex=")
                .append(look.complex)
                .append(
                    tileEntity == null ? ""
                        : " tileEntity=" + tileEntity.getClass()
                            .getSimpleName())
                .append((covered & 1 << side) != 0 ? "  -> HIDES this side" : "")
                .append('\n');
        }
    }

    // ---------------------------------------------------------------- the map's copy

    private static void stored(StringBuilder r, File directory, IsoMap.Dimension dimension, FacePalette palette, int x,
        int y, int z) {
        r.append("\n== THE MAP'S COPY OF IT\n");
        if (dimension == null) {
            r.append("no 3D map open\n");
            return;
        }
        int cx = x >> 4, cz = z >> 4;
        ChunkBlocks blocks = dimension.store.chunk(cx, cz);
        if (blocks == null) {
            r.append("chunk ")
                .append(cx)
                .append(',')
                .append(cz)
                .append(" not copied yet\n");
            return;
        }
        r.append("chunk ")
            .append(cx)
            .append(',')
            .append(cz)
            .append(" copied ")
            .append((System.currentTimeMillis() - dimension.store.time(cx, cz)) / 1000)
            .append(" s ago, heights ")
            .append(blocks.yMin)
            .append("..")
            .append(blocks.yMax)
            .append(", pictures of palette generation ")
            .append(blocks.faceGeneration)
            .append(palette == null ? " (no palette)" : " (palette is " + palette.generation + ")")
            .append('\n');
        if (y < blocks.yMin || y > blocks.yMax) {
            r.append("height ")
                .append(y)
                .append(" not in the copy\n");
            return;
        }
        int index = ((y - blocks.yMin) << 8) | ((z & 15) << 4) | (x & 15);
        int cell = blocks.cells[index];
        r.append("cell: ")
            .append(ChunkBlocks.blockId(cell) == 0 ? "air" : BlockDiag.name(ChunkBlocks.lookKey(cell)))
            .append(" sky light ")
            .append(ChunkBlocks.skyLight(cell))
            .append(" block light ")
            .append(ChunkBlocks.blockLight(cell))
            .append('\n');
        int n = Arrays.binarySearch(blocks.faceCells, index);
        if (n < 0) {
            r.append("no pictures kept for it: drawn from its icons\n");
            return;
        }
        BlockLooks.Look look = BlockLooks.get(ChunkBlocks.lookKey(cell));
        boolean cube = look.opaque;
        int views = cube ? 6 : ChunkBlocks.VIEWS;
        int[][] images = new int[views][];
        r.append("picture ids (")
            .append(cube ? "per side: down up north south west east" : "per view side 0..3")
            .append("):");
        for (int slot = 0; slot < views; slot++) {
            int id = blocks.faceIds[n * ChunkBlocks.PER_CELL + slot];
            FacePalette.Sprite sprite = palette == null || id <= 0 || id == FacePalette.EMPTY ? null
                : palette.sprite(id);
            r.append(' ')
                .append(id == 0 ? "0(not taken)" : id == FacePalette.EMPTY ? "EMPTY" : String.valueOf(id))
                .append(id > 0 && id != FacePalette.EMPTY && sprite == null ? "(unreadable)" : "");
            if (sprite != null) {
                images[slot] = sprite.mips[0];
            }
        }
        r.append("\n-> stored pictures: stored.png\n");
        BlockDiag.writePng(new File(directory, "stored.png"), cube, views, images);
    }

    private static void fresh(StringBuilder r, File directory, World world, FacePalette palette, int x, int y, int z,
        int key) {
        r.append("\n== PICTURES TAKEN NOW\n");
        if (palette == null) {
            r.append("no palette: the 3D map isn't open\n");
            return;
        }
        FaceRenderer.Inspection inspection = FaceRenderer.inspect(world, x, y, z, palette);
        r.append("as ")
            .append(
                inspection.cube ? "6 side pictures (solid cube)"
                    : "4 view sprites (two blocks wide, around its center)")
            .append(" ownRenderer=")
            .append(inspection.ownRenderer)
            .append(" glassLike=")
            .append(inspection.glassLike)
            .append(" wide=")
            .append(inspection.wide)
            .append(inspection.wide ? " (model reaches far: sprites 4 blocks wide, 256 px)" : "")
            .append(" drawnOverByFartherTileEntity=")
            .append(inspection.overBig)
            .append(" sides left out (hidden by neighbours)=")
            .append(sides(inspection.covered))
            .append(" surroundings=")
            .append(Long.toHexString(inspection.surroundings))
            .append('\n');
        if (inspection.cached != null) {
            r.append("kept by place: ")
                .append(inspection.cached)
                .append('\n');
        }
        for (String over : inspection.drawnOver) {
            r.append("also drawn over it: ")
                .append(over)
                .append('\n');
        }
        if (inspection.ids != null) {
            r.append("fresh ids: ")
                .append(Arrays.toString(inspection.ids))
                .append('\n');
        }
        int views = inspection.cube ? 6 : ChunkBlocks.VIEWS;
        for (int v = 0; v < FaceRenderer.VARIANTS.length; v++) {
            BlockDiag.Shot shot = inspection.shots[v];
            if (shot == null) {
                r.append(FaceRenderer.VARIANTS[v])
                    .append(": not taken (pictures can't be taken now)\n");
                continue;
            }
            String file = "fresh-" + FaceRenderer.VARIANTS[v] + ".png";
            r.append(FaceRenderer.VARIANTS[v])
                .append(": game drew pass0=")
                .append(shot.passBytes0 < 0 ? "not in pass" : shot.passBytes0 + "B")
                .append(" pass1=")
                .append(shot.passBytes1 < 0 ? "not in pass" : shot.passBytes1 + "B")
                .append(" tileEntityRenders=")
                .append(shot.tileEntitiesDrawn);
            for (int view = 0; view < views; view++) {
                r.append(" | ")
                    .append(inspection.cube ? SIDES[view] : "view" + view)
                    .append(' ')
                    .append(shot.coverage[view])
                    .append("% drawn, ")
                    .append(shot.solid[view])
                    .append("% solid, bright ")
                    .append(shot.brightness[view]);
            }
            if (shot.error != null) {
                r.append(" ERROR ")
                    .append(shot.error);
            }
            r.append(" -> ")
                .append(file)
                .append('\n');
            if (shot.images != null) {
                BlockDiag.writePng(new File(directory, file), inspection.cube, views, shot.images);
            }
        }
        r.append(
            "(PNG: top row the pictures over a checkerboard, bottom row their alpha; usual = what the map uses, the"
                + " others only to compare: noClip = not cut to its column, cullBackFaces, noItemLighting = tile"
                + " entities without the game's item lights)\n");
    }

    // ---------------------------------------------------------------- rays

    private static void rays(StringBuilder r, File directory, IsoMap.Dimension dimension, FacePalette palette, int x,
        int y, int z) {
        r.append("\n== RAYS THROUGH IT (map view side ")
            .append(Config.isoRotation & 3)
            .append(")\n");
        if (dimension == null) {
            r.append("no 3D map open\n");
            return;
        }
        IsoProjection projection = IsoProjection.of(Config.isoRotation);
        IsoTracer tracer = new IsoTracer(dimension.store, palette, dimension.noSky);
        tracer.reset(projection, 0);
        double uc = projection.u(x + 0.5, z + 0.5), vc = projection.v(x + 0.5, y + 0.5, z + 0.5);
        // The map around it, as the tracer draws it at the finest detail; the rays below marked on a copy.
        int pixels = AROUND * PICTURE_PIXELS_PER_BLOCK;
        double u0 = uc - AROUND / 2.0, v0 = vc - AROUND / 2.0;
        BufferedImage picture = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int color = tracer
                    .trace(u0 + (px + 0.5) / PICTURE_PIXELS_PER_BLOCK, v0 + (py + 0.5) / PICTURE_PIXELS_PER_BLOCK);
                int alpha = color >>> 24;
                int checker = ((px >> 3) + (py >> 3) & 1) == 0 ? 0x30 : 0x20;
                int red = ((color >> 16 & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                int green = ((color >> 8 & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                int blue = ((color & 0xFF) * alpha + checker * (255 - alpha)) / 255;
                picture.setRGB(px, py, red << 16 | green << 8 | blue);
            }
        }
        int zoomed = pixels * PICTURE_ZOOM;
        BufferedImage map = new BufferedImage(zoomed, zoomed, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = map.createGraphics();
        g.drawImage(picture, 0, 0, zoomed, zoomed, null);
        g.dispose();
        BufferedImage marked = new BufferedImage(zoomed, zoomed, BufferedImage.TYPE_INT_RGB);
        g = marked.createGraphics();
        g.drawImage(map, 0, 0, null);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        g.setStroke(new BasicStroke(1f));
        // The block's outline on the screen.
        g.setColor(Color.YELLOW);
        double[][] corners = outline(projection, x, y, z);
        for (int i = 0; i < corners.length; i++) {
            double[] a = corners[i], b = corners[(i + 1) % corners.length];
            g.drawLine(screen(a[0], u0), screen(a[1], v0), screen(b[0], u0), screen(b[1], v0));
        }
        r.append("Rays on a grid over the block's outline on the screen, named by row A-")
            .append((char) ('A' + RAY_GRID - 1))
            .append(" (top to bottom) and column 1-")
            .append(RAY_GRID)
            .append(" (left to right); marked on map-rays.png (map.png without marks, ")
            .append(PICTURE_PIXELS_PER_BLOCK)
            .append(" px/block shown ")
            .append(PICTURE_ZOOM)
            .append("x, the block's outline in yellow).\n");
        double[] bounds = bounds(corners);
        for (int row = 0; row < RAY_GRID; row++) {
            for (int column = 0; column < RAY_GRID; column++) {
                double u = bounds[0] + (bounds[2] - bounds[0]) * (column + 0.5) / RAY_GRID;
                double v = bounds[1] + (bounds[3] - bounds[1]) * (row + 0.5) / RAY_GRID;
                String name = "" + (char) ('A' + row) + (column + 1);
                tracer.debug = new StringBuilder();
                int color = tracer.trace(u, v);
                r.append(
                    String.format(
                        Locale.ROOT,
                        "%nRAY %s at u=%.4f v=%.4f (from the block's center %+.3f,%+.3f): color argb=%08x%n",
                        name,
                        u,
                        v,
                        u - uc,
                        v - vc,
                        color))
                    .append(tracer.debug);
                tracer.debug = null;
                int sx = screen(u, u0), sy = screen(v, v0);
                g.setColor(Color.RED);
                g.drawLine(sx - 2, sy, sx + 2, sy);
                g.drawLine(sx, sy - 2, sx, sy + 2);
                g.setColor(Color.WHITE);
                g.drawString(name, sx + 3, sy - 2);
            }
        }
        g.dispose();
        try {
            ImageIO.write(map, "png", new File(directory, "map.png"));
            ImageIO.write(marked, "png", new File(directory, "map-rays.png"));
        } catch (IOException e) {
            r.append("map pictures could not be saved: ")
                .append(e)
                .append('\n');
        }
    }

    private static int screen(double plane, double origin) {
        return (int) Math.round((plane - origin) * PICTURE_PIXELS_PER_BLOCK * PICTURE_ZOOM);
    }

    /** The block's outline on the projection plane: the hull of its 8 corners, in turn. */
    private static double[][] outline(IsoProjection p, int x, int y, int z) {
        double[][] points = new double[8][];
        for (int i = 0; i < 8; i++) {
            double cx = x + (i & 1), cy = y + (i >> 1 & 1), cz = z + (i >> 2 & 1);
            points[i] = new double[] { p.u(cx, cz), p.v(cx, cy, cz) };
        }
        Arrays.sort(points, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        // Monotone chain: the lower and upper hull.
        double[][] hull = new double[16][];
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = n;
            for (int k = 0; k < 8; k++) {
                double[] q = points[pass == 0 ? k : 7 - k];
                while (n >= start + 2 && cross(hull[n - 2], hull[n - 1], q) <= 1e-9) {
                    n--;
                }
                hull[n++] = q;
            }
            n--;
        }
        return Arrays.copyOf(hull, n);
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static double[] bounds(double[][] corners) {
        double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (double[] c : corners) {
            minU = Math.min(minU, c[0]);
            maxU = Math.max(maxU, c[0]);
            minV = Math.min(minV, c[1]);
            maxV = Math.max(maxV, c[1]);
        }
        return new double[] { minU, minV, maxU, maxV };
    }

    private static String sides(int mask) {
        StringBuilder b = new StringBuilder();
        for (int side = 0; side < 6; side++) {
            if ((mask & 1 << side) != 0) {
                b.append(b.length() == 0 ? "" : ",")
                    .append(SIDES[side]);
            }
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private interface Getter {

        Object get() throws Throwable;
    }

    private static String safe(Getter getter) {
        try {
            return String.valueOf(getter.get());
        } catch (Throwable t) {
            return "error:" + t;
        }
    }
}
