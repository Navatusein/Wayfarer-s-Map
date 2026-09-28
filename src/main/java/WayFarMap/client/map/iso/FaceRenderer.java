package WayFarMap.client.map.iso;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
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
 * Takes pictures of block sides as the game draws them in place, for blocks the 3D map can't draw from their icon:
 * connected textures (Chisel), tile entities with their own renderer (chests, signs, heads, modded machines), modded
 * block renderers, machine fronts and other sides that depend on the world. Each block is drawn by the game's own
 * renderer into an off-screen buffer, looking straight at each of its six sides, with the world around it (so
 * connected textures connect) and without lighting; the tracer then shows these pictures on the block's sides.
 * Render thread only.
 */
final class FaceRenderer {

    /** Picture size, and the buffer: 16x16 pictures, 256 of them per read back. */
    private static final int SLOT = 16, SIZE = 256, SLOTS = (SIZE / SLOT) * (SIZE / SLOT);
    /** Brightness the game gives each side; taken out again, the tracer shades sides itself. */
    private static final float[] SIDE_SHADE = { 0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f };
    private static final int[][] OFFSETS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };
    /**
     * Per side, the camera: from the block (relative to its center) to eye coordinates, the side being the near
     * plane and its picture laid out like the tracer reads it (row-major 3x4: x, y, z rows with a translation).
     */
    private static final float[][] VIEWS = { { 1, 0, 0, 0, 0, 0, -1, 0, 0, -1, 0, -0.5f }, // down
        { 1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, -0.5f }, // up
        { -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, -0.5f }, // north
        { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, -0.5f }, // south
        { 0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, -0.5f }, // west
        { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, -0.5f } }; // east

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static FloatBuffer matrixBuffer;
    private static boolean broken;
    /** Pictures of blocks without a tile entity, by block and everything around it. */
    private static final Map<Long, int[]> BY_SURROUNDINGS = new HashMap<>();
    private static int cacheGeneration;
    /** Whether a block class draws sides depending on the world (overrides the world-aware getIcon). */
    private static final Map<Class<?>, Boolean> WORLD_ICONS = new HashMap<>();

    private FaceRenderer() {}

    /** A block to take pictures of. */
    private static final class Pending {

        final int cellIndex, x, y, z;
        final Block block;
        final TileEntity tileEntity;
        final boolean ownRenderer;
        final long surroundings;
        final int[] ids = new int[6];

        Pending(int cellIndex, int x, int y, int z, Block block, TileEntity tileEntity, boolean ownRenderer,
            long surroundings) {
            this.cellIndex = cellIndex;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.tileEntity = tileEntity;
            this.ownRenderer = ownRenderer;
            this.surroundings = surroundings;
        }
    }

    /** Whether pictures can be taken (off-screen buffers are available and nothing went wrong). */
    static boolean available() {
        return !broken && OpenGlHelper.isFramebufferEnabled();
    }

    /** Resource packs changed: pictures are taken again. */
    static void clear() {
        BY_SURROUNDINGS.clear();
    }

    /** Finds the chunk's blocks that need pictures, takes them and stores their ids in {@code blocks}. */
    static void addFaces(World world, Chunk chunk, ChunkBlocks blocks, FacePalette palette) {
        if (!available()) {
            return;
        }
        if (cacheGeneration != palette.generation) {
            BY_SURROUNDINGS.clear();
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
            if (look.shape != BlockLooks.SHAPE_BOXES) {
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
            long surroundings = tileEntity != null ? 0 : surroundings(world, block, x, y, z);
            Pending pending = new Pending(i, x, y, z, block, tileEntity, ownRenderer, surroundings);
            found.add(pending);
            if (tileEntity == null) {
                int[] known = BY_SURROUNDINGS.get(surroundings);
                if (known != null) {
                    System.arraycopy(known, 0, pending.ids, 0, 6);
                    continue;
                }
                List<Pending> same = waiting.get(surroundings);
                if (same != null) {
                    // Drawn once for all the blocks with the same surroundings.
                    same.add(pending);
                    continue;
                }
                same = new ArrayList<>();
                waiting.put(surroundings, same);
            }
            toDraw.add(pending);
        }
        if (found.isEmpty()) {
            return;
        }
        int perBatch = SLOTS / 6;
        for (int from = 0; from < toDraw.size() && !broken; from += perBatch) {
            List<Pending> batch = toDraw.subList(from, Math.min(toDraw.size(), from + perBatch));
            draw(world, batch, palette);
            for (Pending pending : batch) {
                if (pending.tileEntity == null) {
                    BY_SURROUNDINGS.put(pending.surroundings, pending.ids.clone());
                    List<Pending> same = waiting.get(pending.surroundings);
                    if (same != null) {
                        for (Pending other : same) {
                            System.arraycopy(pending.ids, 0, other.ids, 0, 6);
                        }
                    }
                }
            }
        }
        if (broken) {
            return;
        }
        int[] faceCells = new int[found.size()];
        int[] ids = new int[found.size() * 6];
        for (int n = 0; n < found.size(); n++) {
            Pending pending = found.get(n);
            faceCells[n] = pending.cellIndex;
            System.arraycopy(pending.ids, 0, ids, n * 6, 6);
        }
        blocks.setFaces(palette.generation, faceCells, ids);
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
        if (!overridesWorldIcon(block.getClass())) {
            return false;
        }
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

    /** Hash of the block and the 26 around it (connected textures depend on them), with its icons in place. */
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

    /** Draws the blocks' six sides into the buffer, reads it back and stores the pictures in the palette. */
    private static void draw(World world, List<Pending> batch, FacePalette palette) {
        Minecraft mc = Minecraft.getMinecraft();
        Tessellator tessellator = Tessellator.instance;
        if (tessellator.isDrawing) {
            return;
        }
        int ambientOcclusion = mc.gameSettings.ambientOcclusion;
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
            // Smooth lighting would darken corners by the light around; the pictures are taken unlit.
            mc.gameSettings.ambientOcclusion = 0;
            RenderBlocks renderBlocks = new RenderBlocks(world);

            int slot = 0;
            for (Pending pending : batch) {
                for (int side = 0; side < 6; side++, slot++) {
                    GL11.glViewport((slot % (SIZE / SLOT)) * SLOT, (slot / (SIZE / SLOT)) * SLOT, SLOT, SLOT);
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glLoadIdentity();
                    GL11.glOrtho(-0.5, 0.5, -0.5, 0.5, -0.01, 1.01);
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    loadView(side);
                    GL11.glTranslated(-(pending.x + 0.5), -(pending.y + 0.5), -(pending.z + 0.5));
                    // Again for every picture: a tile entity renderer may have changed any of it.
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
            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            int[] all = new int[SIZE * SIZE];
            readBuffer.get(all);
            slot = 0;
            int[] image = new int[FacePalette.PIXELS];
            for (Pending pending : batch) {
                for (int side = 0; side < 6; side++, slot++) {
                    int sx = (slot % (SIZE / SLOT)) * SLOT, sy = (slot / (SIZE / SLOT)) * SLOT;
                    float shade = pending.ownRenderer ? 1f : SIDE_SHADE[side];
                    for (int row = 0; row < SLOT; row++) {
                        // Read back bottom-up; pictures are top-down.
                        int from = (sy + SLOT - 1 - row) * SIZE + sx;
                        for (int column = 0; column < SLOT; column++) {
                            image[row * SLOT + column] = unshade(all[from + column], shade);
                        }
                    }
                    pending.ids[side] = palette.idOf(image);
                }
            }
        } catch (Throwable t) {
            // Something in this driver or a mod's renderer does not like this: pictures are not taken any more.
            broken = true;
            WayFarMap.LOG.warn("The 3D map can't take pictures of blocks; it uses their icons instead", t);
        } finally {
            mc.gameSettings.ambientOcclusion = ambientOcclusion;
            if (tessellator.isDrawing) {
                try {
                    tessellator.draw();
                } catch (RuntimeException ignored) {}
            }
            if (bound) {
                framebuffer.unbindFramebuffer();
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** The block as the world draws it, then its tile entity and those next to it (a double chest's other half). */
    private static void drawBlock(Minecraft mc, RenderBlocks renderBlocks, Tessellator tessellator, Pending pending) {
        try {
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            tessellator.startDrawingQuads();
            renderBlocks.renderBlockByRenderType(pending.block, pending.x, pending.y, pending.z);
            tessellator.draw();
        } catch (RuntimeException e) {
            if (tessellator.isDrawing) {
                tessellator.draw();
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

    private static void loadView(int side) {
        float[] m = VIEWS[side];
        matrixBuffer.clear();
        // Column-major.
        matrixBuffer.put(m[0])
            .put(m[4])
            .put(m[8])
            .put(0f);
        matrixBuffer.put(m[1])
            .put(m[5])
            .put(m[9])
            .put(0f);
        matrixBuffer.put(m[2])
            .put(m[6])
            .put(m[10])
            .put(0f);
        matrixBuffer.put(m[3])
            .put(m[7])
            .put(m[11])
            .put(1f);
        matrixBuffer.flip();
        GL11.glLoadMatrix(matrixBuffer);
    }

    /** Takes the game's side shading out of a pixel, so the tracer can shade it by its own light. */
    private static int unshade(int argb, float shade) {
        if (shade >= 1f || (argb >>> 24) == 0) {
            return argb;
        }
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) / shade + 0.5f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) / shade + 0.5f));
        int b = Math.min(255, (int) ((argb & 0xFF) / shade + 0.5f));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }
}
