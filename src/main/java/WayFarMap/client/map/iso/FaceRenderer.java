package WayFarMap.client.map.iso;

import java.lang.reflect.Method;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;

/**
 * Takes sprites of blocks as the game draws them in place, for blocks the 3D map can't draw by itself: connected
 * textures (Chisel), tile entities with their own renderer (chests, signs, heads, modded machines), GregTech pipes and
 * cables, crops on sticks, beds, rails, redstone, fences, modded block renderers, machine fronts and other sides that
 * depend on the world. Each block is drawn by the game's own renderers into an off-screen buffer, from exactly the
 * direction the 3D map looks from (one sprite per view side), with the world around it (so connected textures
 * connect and pipes join) and without lighting; the tracer shows the sprite's pixel where its ray meets the block.
 * Render thread only.
 */
final class FaceRenderer {

    /** Room for one picture (sprites fill it, pictures of sides a quarter), and the buffer: 256 per read back. */
    private static final int SLOT = FacePalette.SPRITE_SIZE, SIZE = 1024, PER_ROW = SIZE / SLOT,
        SLOTS = PER_ROW * PER_ROW;
    /** How far outside the block the clip planes are (less than the gap to a chest's other half, 1/16). */
    private static final double CLIP_MARGIN = 1 / 32.0;
    /** Brightness the game gives each side; taken out of pictures of sides, the tracer shades sides itself. */
    private static final float[] SIDE_SHADE = { 0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f };
    /**
     * Per side, the camera looking straight at it: from the block (relative to its center) to eye coordinates, the
     * side being the near plane and its picture laid out like the tracer reads a side's texture (row-major 3x4: x,
     * y, z rows with a translation).
     */
    private static final float[][] SIDE_VIEWS = { { 1, 0, 0, 0, 0, 0, -1, 0, 0, -1, 0, -0.5f }, // down
        { 1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, -0.5f }, // up
        { -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, -0.5f }, // north
        { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -0.5f }, // south
        { 0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, -0.5f }, // west
        { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, -0.5f } }; // east
    private static final int[][] OFFSETS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static FloatBuffer matrixBuffer;
    private static DoubleBuffer planeBuffer;
    private static boolean broken;
    /** Failures in a row; pictures are given up only after several (one mod's renderer failing once is enough). */
    private static int failures;
    /**
     * Pictures of blocks with a tile entity, by place: they depend on what is in it, so they aren't shared between
     * places, but the chunks near the player are copied every few seconds and are not taken again each time.
     */
    private static final Map<Long, Cached> BY_PLACE = new HashMap<>();
    /** How long pictures of a block with a tile entity are kept before they are taken again. */
    private static final long PLACE_KEEP_MS = 60_000;

    private static final class Cached {

        final long surroundings;
        final int[] ids;
        final long time;

        Cached(long surroundings, int[] ids, long time) {
            this.surroundings = surroundings;
            this.ids = ids;
            this.time = time;
        }
    }
    /** Sprites of blocks without a tile entity, by block and everything around it. */
    private static final Map<Long, int[]> BY_SURROUNDINGS = new HashMap<>();
    private static int cacheGeneration;
    /** Whether a block class draws sides depending on the world (overrides the world-aware getIcon). */
    private static final Map<Class<?>, Boolean> WORLD_ICONS = new HashMap<>();

    private FaceRenderer() {}

    /** A block to take sprites of. */
    private static final class Pending {

        final int cellIndex, x, y, z;
        final Block block;
        final TileEntity tileEntity;
        final long surroundings;
        /** A solid cube: pictures of its six sides; otherwise sprites from the four view sides. */
        final boolean cube;
        final boolean ownRenderer;
        final int[] ids = new int[ChunkBlocks.PER_CELL];

        Pending(int cellIndex, int x, int y, int z, Block block, TileEntity tileEntity, long surroundings,
            boolean cube, boolean ownRenderer) {
            this.cellIndex = cellIndex;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.tileEntity = tileEntity;
            this.surroundings = surroundings;
            this.cube = cube;
            this.ownRenderer = ownRenderer;
        }

        int views() {
            return cube ? 6 : ChunkBlocks.VIEWS;
        }
    }

    /** Whether sprites can be taken (off-screen buffers are available and nothing went wrong). */
    static boolean available() {
        return !broken && OpenGlHelper.isFramebufferEnabled();
    }

    /** Resource packs changed: sprites are taken again. */
    static void clear() {
        BY_SURROUNDINGS.clear();
        BY_PLACE.clear();
    }

    /** Finds the chunk's blocks that need sprites, takes them and stores their ids in {@code blocks}. */
    static void addFaces(World world, Chunk chunk, ChunkBlocks blocks, FacePalette palette) {
        if (!available()) {
            return;
        }
        if (cacheGeneration != palette.generation) {
            BY_SURROUNDINGS.clear();
            BY_PLACE.clear();
            cacheGeneration = palette.generation;
        }
        int baseX = chunk.xPosition * 16, baseZ = chunk.zPosition * 16;
        List<Pending> found = new ArrayList<>();
        List<Pending> toDraw = new ArrayList<>();
        Map<Long, List<Pending>> waiting = new HashMap<>();
        int[] cells = blocks.cells;
        for (int i = 0; i < cells.length; i++) {
            int cell = cells[i];
            if (ChunkBlocks.blockId(cell) == 0) {
                continue;
            }
            int key = ChunkBlocks.lookKey(cell);
            BlockLooks.Look look = BlockLooks.get(key);
            if (look.shape == BlockLooks.SHAPE_NONE && !look.complex || look.shape == BlockLooks.SHAPE_LIQUID) {
                continue;
            }
            Block block = Block.getBlockById(ChunkBlocks.blockId(cell));
            int meta = ChunkBlocks.meta(cell);
            boolean maybe;
            try {
                maybe = look.complex || block.hasTileEntity(meta)
                    || (look.renderType == 0 && overridesWorldIcon(block.getClass()));
            } catch (RuntimeException e) {
                maybe = false;
            }
            if (!maybe) {
                // Most blocks: drawn from their icons.
                continue;
            }
            int lx = i & 15, lz = (i >> 4) & 15, y = blocks.yMin + (i >> 8);
            int x = baseX + lx, z = baseZ + lz;
            int exposed = exposedSides(world, blocks, lx, y, lz, x, z);
            if (exposed == 0) {
                continue;
            }
            TileEntity tileEntity = null;
            boolean ownRenderer = false;
            try {
                if (block.hasTileEntity(meta)) {
                    tileEntity = world.getTileEntity(x, y, z);
                    ownRenderer = tileEntity != null
                        && TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity);
                }
            } catch (RuntimeException e) {
                tileEntity = null;
            }
            boolean needed = look.complex || ownRenderer
                || (look.renderType == 0 && sidesDependOnWorld(world, block, meta, x, y, z, exposed));
            if (!needed) {
                continue;
            }
            // Blocks with a tile entity (pipes, machines, chests) look by what is in it: each is drawn.
            long surroundings = surroundings(world, block, x, y, z);
            Pending pending = new Pending(i, x, y, z, block, tileEntity, surroundings, look.opaque, ownRenderer);
            found.add(pending);
            if (tileEntity != null) {
                Cached cached = BY_PLACE.get(place(x, y, z));
                if (cached != null && cached.surroundings == surroundings
                    && System.currentTimeMillis() - cached.time < PLACE_KEEP_MS) {
                    System.arraycopy(cached.ids, 0, pending.ids, 0, pending.ids.length);
                    continue;
                }
            } else {
                int[] known = BY_SURROUNDINGS.get(surroundings);
                if (known != null) {
                    System.arraycopy(known, 0, pending.ids, 0, pending.ids.length);
                    continue;
                }
                List<Pending> same = waiting.get(surroundings);
                if (same != null) {
                    // Drawn once for all the blocks with the same surroundings.
                    same.add(pending);
                    continue;
                }
                waiting.put(surroundings, new ArrayList<>());
            }
            toDraw.add(pending);
        }
        if (found.isEmpty()) {
            return;
        }
        for (int from = 0; from < toDraw.size() && !broken;) {
            // As many blocks as their pictures fit in the buffer.
            int to = from, slots = 0;
            while (to < toDraw.size() && slots + toDraw.get(to)
                .views() <= SLOTS) {
                slots += toDraw.get(to++)
                    .views();
            }
            List<Pending> batch = toDraw.subList(from, to);
            from = to;
            draw(world, batch, palette);
            for (Pending pending : batch) {
                if (pending.tileEntity != null) {
                    if (BY_PLACE.size() > 100_000) {
                        BY_PLACE.clear();
                    }
                    BY_PLACE.put(
                        place(pending.x, pending.y, pending.z),
                        new Cached(pending.surroundings, pending.ids.clone(), System.currentTimeMillis()));
                } else {
                    BY_SURROUNDINGS.put(pending.surroundings, pending.ids.clone());
                    List<Pending> same = waiting.get(pending.surroundings);
                    if (same != null) {
                        for (Pending other : same) {
                            System.arraycopy(pending.ids, 0, other.ids, 0, pending.ids.length);
                        }
                    }
                }
            }
        }
        if (broken) {
            return;
        }
        int[] faceCells = new int[found.size()];
        int[] ids = new int[found.size() * ChunkBlocks.PER_CELL];
        for (int n = 0; n < found.size(); n++) {
            Pending pending = found.get(n);
            faceCells[n] = pending.cellIndex;
            System.arraycopy(pending.ids, 0, ids, n * ChunkBlocks.PER_CELL, ChunkBlocks.PER_CELL);
        }
        blocks.setFaces(palette.generation, faceCells, ids);
    }

    private static long place(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Bits of the sides (1 << side) that are not covered by a solid block next to them. */
    private static int exposedSides(World world, ChunkBlocks blocks, int lx, int y, int lz, int x, int z) {
        int exposed = 0;
        for (int side = 0; side < 6; side++) {
            int nx = lx + OFFSETS[side][0], ny = y + OFFSETS[side][1], nz = lz + OFFSETS[side][2];
            boolean covered;
            if (ny > blocks.yMax || ny > 255) {
                covered = false;
            } else if (ny < 0) {
                covered = true;
            } else if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < blocks.yMin) {
                covered = world.getBlock(x + OFFSETS[side][0], ny, z + OFFSETS[side][2])
                    .isOpaqueCube();
            } else {
                int neighbour = blocks.cell(nx, ny, nz);
                covered = ChunkBlocks.blockId(neighbour) != 0 && BlockLooks.get(ChunkBlocks.lookKey(neighbour)).opaque;
            }
            if (!covered) {
                exposed |= 1 << side;
            }
        }
        return exposed;
    }

    /** Whether any open side of the block shows another icon in the world than its plain icon for the metadata. */
    private static boolean sidesDependOnWorld(IBlockAccess world, Block block, int meta, int x, int y, int z,
        int exposed) {
        try {
            for (int side = 0; side < 6; side++) {
                if ((exposed & 1 << side) != 0 && block.getIcon(world, x, y, z, side) != block.getIcon(side, meta)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Whether the class (or a parent below Block) has its own world-aware getIcon; found by signature. */
    private static boolean overridesWorldIcon(Class<?> type) {
        Boolean known = WORLD_ICONS.get(type);
        if (known != null) {
            return known;
        }
        boolean overrides = false;
        for (Class<?> c = type; c != null && c != Block.class && !overrides; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == IIcon.class && params.length == 5
                    && params[0] == IBlockAccess.class
                    && params[1] == int.class
                    && params[2] == int.class
                    && params[3] == int.class
                    && params[4] == int.class) {
                    overrides = true;
                    break;
                }
            }
        }
        WORLD_ICONS.put(type, overrides);
        return overrides;
    }

    /** Hash of the block and the 26 around it (connections depend on them), with its icons in place. */
    private static long surroundings(World world, Block block, int x, int y, int z) {
        long h = 0xCBF29CE484222325L;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int ny = y + dy;
                    int value = 0;
                    if (ny >= 0 && ny <= 255) {
                        value = Block.getIdFromBlock(world.getBlock(x + dx, ny, z + dz)) | world.getBlockMetadata(
                            x + dx,
                            ny,
                            z + dz) << 16;
                    }
                    h = (h ^ value) * 0x100000001B3L;
                }
            }
        }
        try {
            for (int side = 0; side < 6; side++) {
                IIcon icon = block.getIcon(world, x, y, z, side);
                h = (h ^ System.identityHashCode(icon)) * 0x100000001B3L;
            }
        } catch (RuntimeException ignored) {}
        return h;
    }

    /** Draws each block from the four view sides into the buffer, reads it back, stores the sprites. */
    private static void draw(World world, List<Pending> batch, FacePalette palette) {
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.instance;
        int ambientOcclusion = mc.gameSettings.ambientOcclusion;
        int previousFramebuffer = GL11.glGetInteger(0x8CA6); // GL_FRAMEBUFFER_BINDING
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        boolean bound = false;
        try {
            if (framebuffer == null) {
                framebuffer = new Framebuffer(SIZE, SIZE, true);
                readBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
                matrixBuffer = BufferUtils.createFloatBuffer(16);
                planeBuffer = BufferUtils.createDoubleBuffer(4);
            }
            framebuffer.bindFramebuffer(true);
            bound = true;
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            // No light map: every block fully lit, the tracer adds the light of the place.
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // Smooth lighting would darken corners by the light around; the sprites are taken unlit.
            mc.gameSettings.ambientOcclusion = 0;
            RenderBlocks renderBlocks = new RenderBlocks(world);

            int slot = 0;
            for (Pending pending : batch) {
                for (int view = 0; view < pending.views(); view++, slot++) {
                    int pixels = pending.cube ? FacePalette.FACE_SIZE : FacePalette.SPRITE_SIZE;
                    GL11.glViewport((slot % PER_ROW) * SLOT, (slot / PER_ROW) * SLOT, pixels, pixels);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    if (pending.cube) {
                        // Straight at one side: the picture covers exactly that side, like its texture.
                        GL11.glOrtho(-0.5, 0.5, -0.5, 0.5, -0.01, 1.01);
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        loadSideView(view);
                    } else {
                        // Two blocks of the projection plane around the block's center, like the tracer reads it.
                        GL11.glOrtho(-1, 1, -1, 1, -2, 2);
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        loadView(IsoProjection.of(view));
                    }
                    GL11.glTranslated(-(pending.x + 0.5), -(pending.y + 0.5), -(pending.z + 0.5));
                    // Only what is inside the block's column: not the other half of a double chest, not neighbours.
                    // A little outside the block, or its own sides, which lie on these planes, get cut off.
                    clip(0, 1, 0, -(pending.x - CLIP_MARGIN));
                    clip(1, -1, 0, pending.x + 1 + CLIP_MARGIN);
                    clip(2, 0, 1, -(pending.z - CLIP_MARGIN));
                    clip(3, 0, -1, pending.z + 1 + CLIP_MARGIN);
                    // Again for every sprite: a tile entity renderer may have changed any of it.
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDisable(GL11.GL_FOG);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    GL11.glDepthMask(true);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glColor4f(1f, 1f, 1f, 1f);
                    drawBlock(mc, renderBlocks, tessellator, pending);
                }
            }
            for (int plane = 0; plane < 4; plane++) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            }
            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            int[] all = new int[SIZE * SIZE];
            readBuffer.get(all);
            slot = 0;
            int[] faceImage = new int[FacePalette.FACE_SIZE * FacePalette.FACE_SIZE];
            int[] spriteImage = new int[FacePalette.SPRITE_SIZE * FacePalette.SPRITE_SIZE];
            for (Pending pending : batch) {
                int pixels = pending.cube ? FacePalette.FACE_SIZE : FacePalette.SPRITE_SIZE;
                int[] image = pending.cube ? faceImage : spriteImage;
                for (int view = 0; view < pending.views(); view++, slot++) {
                    int sx = (slot % PER_ROW) * SLOT, sy = (slot / PER_ROW) * SLOT;
                    // A side seen straight on is shaded by the game for that side; the tracer shades it itself.
                    float shade = pending.cube && !pending.ownRenderer ? SIDE_SHADE[view] : 1f;
                    for (int row = 0; row < pixels; row++) {
                        // Read back bottom-up; pictures are top-down.
                        int from = (sy + pixels - 1 - row) * SIZE + sx;
                        for (int column = 0; column < pixels; column++) {
                            image[row * pixels + column] = unshade(all[from + column], shade);
                        }
                    }
                    pending.ids[view] = palette.idOf(image);
                }
            }
            failures = 0;
        } catch (Throwable t) {
            // These blocks are drawn from their icons this time.
            for (Pending pending : batch) {
                Arrays.fill(pending.ids, 0);
            }
            if (++failures >= 5) {
                // Something in this driver or the mods' renderers does not like this: no more pictures.
                broken = true;
                WayFarMap.LOG.warn("The 3D map can't take pictures of blocks; it uses their icons instead", t);
            } else {
                WayFarMap.LOG.debug("Could not take pictures of blocks for the 3D map", t);
            }
        } finally {
            mc.gameSettings.ambientOcclusion = ambientOcclusion;
            if (bound) {
                // Back to the buffer bound before (the game's own while a frame is drawn).
                IconReader.rebind(mc, previousFramebuffer, framebuffer);
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Keeps what is on the positive side of a vertical plane: a * x + b * z + d >= 0 (world coordinates). */
    private static void clip(int plane, double a, double b, double d) {
        planeBuffer.clear();
        planeBuffer.put(a)
            .put(0)
            .put(b)
            .put(d);
        planeBuffer.flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + plane, planeBuffer);
        GL11.glEnable(GL11.GL_CLIP_PLANE0 + plane);
    }

    /** The block as the world draws it, then its tile entity and those next to it (a double chest's other half). */
    private static void drawBlock(Minecraft mc, RenderBlocks renderBlocks, Tessellator tessellator, Pending pending) {
        try {
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
        } catch (RuntimeException e) {
            return;
        }
        // Whether this drawing is open is tracked here: the tessellator keeps it to itself.
        boolean drawing = false;
        try {
            tessellator.startDrawingQuads();
            drawing = true;
            renderBlocks.renderBlockByRenderType(pending.block, pending.x, pending.y, pending.z);
            drawing = false;
            tessellator.draw();
        } catch (RuntimeException e) {
            if (drawing) {
                try {
                    tessellator.draw();
                } catch (RuntimeException ignored) {}
            }
        }
        if (pending.tileEntity == null) {
            return;
        }
        World world = pending.tileEntity.getWorldObj();
        for (int n = -1; n < 4; n++) {
            TileEntity tileEntity = n < 0 ? pending.tileEntity
                : world == null ? null
                    : world.getTileEntity(
                        pending.x + (n == 0 ? -1 : n == 1 ? 1 : 0),
                        pending.y,
                        pending.z + (n == 2 ? -1 : n == 3 ? 1 : 0));
            if (tileEntity == null || !TileEntityRendererDispatcher.instance.hasSpecialRenderer(tileEntity)) {
                continue;
            }
            try {
                TileEntityRendererDispatcher.instance
                    .renderTileEntityAt(tileEntity, tileEntity.xCoord, tileEntity.yCoord, tileEntity.zCoord, 0f);
            } catch (RuntimeException e) {
                // A renderer that needs more than this; the block's own drawing stays.
            }
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL11.GL_LIGHTING);
        }
    }

    private static void loadSideView(int side) {
        float[] m = SIDE_VIEWS[side];
        matrixBuffer.clear();
        // Column-major.
        for (int column = 0; column < 4; column++) {
            matrixBuffer.put(m[column])
                .put(m[4 + column])
                .put(m[8 + column])
                .put(column == 3 ? 1f : 0f);
        }
        matrixBuffer.flip();
        GL11.glLoadMatrix(matrixBuffer);
    }

    /** Takes the game's side shading out of a pixel, so the tracer can shade it by the light of its place. */
    private static int unshade(int argb, float shade) {
        if (shade >= 1f || (argb >>> 24) == 0) {
            return argb;
        }
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) / shade + 0.5f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) / shade + 0.5f));
        int b = Math.min(255, (int) ((argb & 0xFF) / shade + 0.5f));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * The 3D map's camera for a view side: eye x is the projection plane's u (right), eye y is -v (up on screen),
     * eye z points back toward the viewer (along the rays, reversed).
     */
    private static void loadView(IsoProjection p) {
        double sin = IsoProjection.SIN, cos = IsoProjection.COS;
        // Rows of the rotation: right, up, back.
        double[][] rows = { { p.rightX, 0, p.rightZ }, { -sin * p.towardX, cos, -sin * p.towardZ },
            { cos * p.towardX, sin, cos * p.towardZ } };
        matrixBuffer.clear();
        // Column-major.
        for (int column = 0; column < 3; column++) {
            matrixBuffer.put((float) rows[0][column])
                .put((float) rows[1][column])
                .put((float) rows[2][column])
                .put(0f);
        }
        matrixBuffer.put(0f)
            .put(0f)
            .put(0f)
            .put(1f);
        matrixBuffer.flip();
        GL11.glLoadMatrix(matrixBuffer);
    }
}
