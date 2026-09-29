package WayFarMap.client.map.iso;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.ColorizerFoliage;
import net.minecraft.world.ColorizerGrass;

import WayFarMap.WayFarMap;

/**
 * How each block (id and metadata) looks on the 3D map: its shape, the textures of its six sides and how it is
 * tinted. Worked out on the render thread (block icons and resource packs are not meant for other threads); the tile
 * renderers ask for missing ones and wait.
 */
public final class BlockLooks {

    public static final int SHAPE_NONE = 0, SHAPE_BOXES = 1, SHAPE_PLANES = 2, SHAPE_LIQUID = 3;
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_FIXED = 4;
    /** Plane kinds: at x = offset, at z = offset, at y = offset, and the two diagonals of crossed plants. */
    public static final int PLANE_X = 0, PLANE_Z = 1, PLANE_Y = 2, PLANE_DIAGONAL = 3, PLANE_ANTIDIAGONAL = 4;

    /** A texture with its reduced copies: 16x16, 8x8, 4x4, 2x2 and 1x1, ARGB. */
    public static final class Texture {

        final int[][] mips = new int[5][];

        /** Texel at (u, v) in 0..1 of the copy with {@code 16 >> mip} texels per side. */
        int texel(double u, double v, int mip) {
            int size = 16 >> mip;
            int tx = (int) (u * size), ty = (int) (v * size);
            tx = tx < 0 ? 0 : tx >= size ? size - 1 : tx;
            ty = ty < 0 ? 0 : ty >= size ? size - 1 : ty;
            return mips[mip][ty * size + tx];
        }

        static Texture of(int[] pixels16) {
            Texture texture = new Texture();
            texture.mips[0] = pixels16;
            for (int mip = 1; mip < 5; mip++) {
                int size = 16 >> mip;
                int[] source = texture.mips[mip - 1];
                int[] target = new int[size * size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        target[y * size + x] = average(
                            source,
                            new int[] { (y * 2) * size * 2 + x * 2, (y * 2) * size * 2 + x * 2 + 1,
                                (y * 2 + 1) * size * 2 + x * 2, (y * 2 + 1) * size * 2 + x * 2 + 1 });
                    }
                }
                texture.mips[mip] = target;
            }
            return texture;
        }

        static Texture solid(int rgb) {
            int[] pixels = new int[256];
            Arrays.fill(pixels, 0xFF000000 | rgb);
            return of(pixels);
        }
    }

    /** Average of texels, colors weighted by their alpha so transparent texels don't darken the result. */
    private static int average(int[] source, int[] indices) {
        long a = 0, r = 0, g = 0, b = 0;
        for (int i : indices) {
            int argb = source[i];
            int alpha = argb >>> 24;
            a += alpha;
            r += ((argb >> 16) & 0xFF) * alpha;
            g += ((argb >> 8) & 0xFF) * alpha;
            b += (argb & 0xFF) * alpha;
        }
        if (a == 0) {
            return 0;
        }
        return (int) (a / indices.length) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    public static final class Look {

        public int shape;
        /** Boxes {minX, minY, minZ, maxX, maxY, maxZ} inside the block. */
        public float[] boxes;
        /** Planes {kind, offset}. */
        public float[] planes;
        /** Per side (0 down, 1 up, 2 north, 3 south, 4 west, 5 east). */
        public final Texture[] textures = new Texture[6];
        /** Drawn over the side, tinted (the grass on the sides of grass blocks); null if none. */
        public final Texture[] overlays = new Texture[6];
        /** Whether the side's texture (not its overlay) is tinted. */
        public final boolean[] tintSide = new boolean[6];
        public int tint;
        public int tintColor;
        /** A solid cube that hides everything behind it; the ground under a chunk is made of these. */
        public boolean opaque;
        /** Fills its whole cell, opaque or not (glass, modded blocks with their own renderer). */
        public boolean fullCube;
        /** See-through by the texture's alpha (water, ice, stained glass) instead of holes. */
        public boolean translucent;
        /** No faces between two of these next to each other (glass, water). */
        public boolean skipSame;
        /** Its own cell has a meaningful light level (not a solid or light-blocking block). */
        public boolean lightPasses;
        /** Fixed opacity for liquids, 0 to use the texture's. */
        public float alpha;
        /**
         * Drawn by a renderer this class doesn't imitate (modded block renderers, beds, rails, fences, pipes...):
         * sprites of it are taken from the game ({@link FaceRenderer}) and shown instead of its shape.
         */
        public boolean complex;
        /** The game's render type, to tell plain cubes apart. */
        public int renderType;
        /**
         * Drawn from its icons even if pictures of it were stored (leaves, double plants): taken by an older version,
         * those pictures were wrong.
         */
        public boolean noPictures;
    }

    private static final Map<Integer, Look> LOOKS = new ConcurrentHashMap<>();
    private static final Map<String, Texture> TEXTURES = new ConcurrentHashMap<>();
    /** Marks textures that could not be read, so they aren't tried again. */
    private static final Texture UNREADABLE = new Texture();
    private static final Queue<Request> REQUESTS = new ConcurrentLinkedQueue<>();
    private static volatile Thread renderThread;
    /** Per thread: whether a look was not had in time since last asked (a plain gray block was given instead). */
    private static final ThreadLocal<boolean[]> MISSED = ThreadLocal.withInitial(() -> new boolean[1]);

    private static final class Request {

        final int key;
        final CompletableFuture<Look> result = new CompletableFuture<>();

        Request(int key) {
            this.key = key;
        }
    }

    private BlockLooks() {}

    /** Look of the block (key = id | meta << 16). Any thread; others wait for the render thread to work it out. */
    public static Look get(int key) {
        Look look = LOOKS.get(key);
        if (look != null) {
            return look;
        }
        if (Thread.currentThread() == renderThread) {
            return resolve(key);
        }
        Request request = new Request(key);
        REQUESTS.add(request);
        try {
            return request.result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The game is busy (or the map closed): a plain block this time, asked again next time.
            MISSED.get()[0] = true;
            return fallback();
        }
    }

    /**
     * Makes sure the looks of these blocks are worked out, asking for all missing ones at once: a renderer waits for
     * the render thread once per chunk instead of once per block (a frame each). Any thread.
     *
     * @return false if some weren't had in time
     */
    static boolean prepare(int[] keys) {
        if (Thread.currentThread() == renderThread) {
            for (int key : keys) {
                resolve(key);
            }
            return true;
        }
        Request[] requests = null;
        int n = 0;
        for (int key : keys) {
            if (LOOKS.containsKey(key)) {
                continue;
            }
            if (requests == null) {
                requests = new Request[keys.length];
            }
            Request request = new Request(key);
            requests[n++] = request;
            REQUESTS.add(request);
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (int i = 0; i < n; i++) {
            try {
                requests[i].result.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** Looks the map will want soon, worked out when no renderer waits (render thread only). */
    private static final ArrayDeque<Integer> WARM = new ArrayDeque<>();
    private static final Set<Integer> WARM_KEYS = new HashSet<>();

    /**
     * The blocks of a chunk just copied (render thread): their looks are worked out in the next ticks, so the 3D map
     * doesn't wait for them when it opens.
     */
    static void warm(int[] keys) {
        for (int key : keys) {
            if (!LOOKS.containsKey(key) && WARM_KEYS.add(key)) {
                WARM.add(key);
            }
        }
    }

    /** Whether this thread was given a plain block for a look it waited for in vain since last asked; resets it. */
    static boolean takeMissed() {
        boolean[] missed = MISSED.get();
        boolean was = missed[0];
        missed[0] = false;
        return was;
    }

    /** For the log: looks the renderers wait for / looks asked for ahead. */
    static String pending() {
        return REQUESTS.size() + "/" + WARM.size();
    }

    /** Works out the looks the renderers wait for, for up to the given time (render thread). */
    public static void pump(long budgetNanos) {
        renderThread = Thread.currentThread();
        long end = System.nanoTime() + budgetNanos;
        Request request;
        while (System.nanoTime() < end && (request = REQUESTS.poll()) != null) {
            try {
                request.result.complete(resolve(request.key));
            } catch (Throwable t) {
                request.result.complete(fallback());
            }
        }
        // Then the ones asked for ahead, while there is time and no renderer waits.
        while (System.nanoTime() < end && REQUESTS.isEmpty() && !WARM.isEmpty()) {
            int key = WARM.poll();
            WARM_KEYS.remove(key);
            try {
                resolve(key);
            } catch (Throwable t) {
                LOOKS.put(key, fallback());
            }
        }
    }

    /** Resource packs changed: textures are read again. */
    public static void clear() {
        LOOKS.clear();
        WARM.clear();
        WARM_KEYS.clear();
        TEXTURES.clear();
    }

    private static Look fallbackLook;

    private static Look fallback() {
        if (fallbackLook == null) {
            Look look = new Look();
            look.shape = SHAPE_BOXES;
            look.boxes = new float[] { 0, 0, 0, 1, 1, 1 };
            Texture gray = Texture.solid(0x808080);
            for (int side = 0; side < 6; side++) {
                look.textures[side] = gray;
            }
            look.opaque = true;
            fallbackLook = look;
        }
        return fallbackLook;
    }

    private static Look resolve(int key) {
        Look look = LOOKS.get(key);
        if (look == null) {
            try {
                look = build(key & 0xFFFF, (key >>> 16) & 15);
            } catch (Throwable t) {
                WayFarMap.LOG.debug("No 3D look for block {}", key & 0xFFFF, t);
                look = fallback();
            }
            LOOKS.put(key, look);
        }
        return look;
    }

    private static Look build(int id, int meta) {
        Look look = new Look();
        Block block = Block.getBlockById(id);
        Material material = block == null ? Material.air : block.getMaterial();
        if (block == null || material == Material.air) {
            look.shape = SHAPE_NONE;
            look.lightPasses = true;
            return look;
        }
        int renderType = block.getRenderType();
        look.renderType = renderType;
        // Sprites taken from the game for everything drawn in a way not imitated here (orientation, connections,
        // models); the shapes below are only used when sprites can't be taken.
        look.complex = !material.isLiquid() && !isImitated(renderType);
        look.noPictures = material == Material.leaves || renderType == 40;
        // Double plants: the plant is in the low bits of either half (the top half's are filled in when copied).
        int iconMeta = renderType == 40 ? meta & 7 : meta;
        look.lightPasses = block.getLightOpacity() < 255;
        look.translucent = block.getRenderBlockPass() == 1;
        for (int side = 0; side < 6; side++) {
            look.textures[side] = texture(block, side, iconMeta);
            look.tintSide[side] = true;
        }
        if (block == Blocks.grass) {
            // Like the game: only the top and the grass over the sides are tinted, the dirt is not.
            Texture overlay = iconTexture(BlockGrass.getIconSideOverlay());
            look.tintSide[0] = false;
            for (int side = 2; side < 6; side++) {
                look.tintSide[side] = false;
                look.overlays[side] = overlay;
            }
        }
        tint(look, block, meta, material);
        if (renderType == 40) {
            // Double tall grass and large ferns take the biome's grass color in the world, the flowers none.
            int plant = meta & 7;
            look.tint = plant == 2 || plant == 3 ? TINT_GRASS : TINT_NONE;
        }

        if (material.isLiquid()) {
            look.shape = SHAPE_LIQUID;
            look.skipSame = true;
            look.alpha = material == Material.water ? 0.6f : 1f;
            look.translucent = material == Material.water;
            return look;
        }
        switch (renderType) {
            case 1: // flowers, saplings, grass, reeds
            case 3: // fire
            case 19: // stems
            case 40: // double plants
                look.shape = SHAPE_PLANES;
                look.planes = new float[] { PLANE_DIAGONAL, 0, PLANE_ANTIDIAGONAL, 0 };
                return look;
            case 6: // crops
                look.shape = SHAPE_PLANES;
                look.planes = new float[] { PLANE_X, 0.25f, PLANE_X, 0.75f, PLANE_Z, 0.25f, PLANE_Z, 0.75f };
                return look;
            case 5: // redstone
            case 9: // rails
                look.shape = SHAPE_PLANES;
                look.planes = new float[] { PLANE_Y, 1 / 16f };
                return look;
            case 23: // lily pads
                look.shape = SHAPE_PLANES;
                look.planes = new float[] { PLANE_Y, 1 / 64f };
                return look;
            case 18: // panes
                look.shape = SHAPE_PLANES;
                look.planes = new float[] { PLANE_X, 0.5f, PLANE_Z, 0.5f };
                return look;
            case 20: // vines, on the sides of their metadata
                look.shape = SHAPE_PLANES;
                look.planes = vinePlanes(meta);
                return look;
            case 8: // ladders
                look.shape = SHAPE_PLANES;
                look.planes = ladderPlane(meta);
                return look;
            case 12: // levers
            case 29: // tripwire hooks
            case 30: // tripwire
                look.shape = SHAPE_NONE;
                look.lightPasses = true;
                return look;
            case 2: // torches
                return boxes(look, 7 / 16f, 0, 7 / 16f, 9 / 16f, 10 / 16f, 9 / 16f);
            case 10: // stairs
                return stairs(look, meta);
            case 11: // fences: the post
                return boxes(look, 6 / 16f, 0, 6 / 16f, 10 / 16f, 1, 10 / 16f);
            case 32: // walls: the post
                return boxes(look, 0.25f, 0, 0.25f, 0.75f, 1, 0.75f);
            case 21: // fence gates
                return (meta & 1) == 0 ? boxes(look, 0, 5 / 16f, 7 / 16f, 1, 15 / 16f, 9 / 16f)
                    : boxes(look, 7 / 16f, 5 / 16f, 0, 9 / 16f, 15 / 16f, 1);
            case 13: // cactus
                return boxes(look, 1 / 16f, 0, 1 / 16f, 15 / 16f, 1, 15 / 16f);
            case 14: // beds
                return boxes(look, 0, 0, 0, 1, 9 / 16f, 1);
            case 15: // repeaters
            case 36: // comparators
                return boxes(look, 0, 0, 0, 1, 1 / 8f, 1);
            case 22: // chests
                return boxes(look, 1 / 16f, 0, 1 / 16f, 15 / 16f, 14 / 16f, 15 / 16f);
            default:
                break;
        }
        // Everything else: the block's box (modded renderers: the whole block).
        float[] box = renderType != 0 ? new float[] { 0, 0, 0, 1, 1, 1 } : bounds(block, meta);
        boxes(look, box[0], box[1], box[2], box[3], box[4], box[5]);
        boolean full = box[0] <= 0 && box[1] <= 0 && box[2] <= 0 && box[3] >= 1 && box[4] >= 1 && box[5] >= 1;
        look.fullCube = full;
        look.opaque = full && block.isOpaqueCube() && !look.translucent;
        look.skipSame = !look.opaque && material != Material.leaves;
        return look;
    }

    /**
     * Render types drawn right by the shapes here: cubes, crossed plants, crops, stairs, cactus, vines, ladders, lily
     * pads, logs and pillars (whose sides depend only on the metadata), double plants (their top half gets the plant
     * of the bottom half when copied, see {@link BlockCapture}).
     */
    private static boolean isImitated(int renderType) {
        switch (renderType) {
            case 0:
            case 1:
            case 6:
            case 8:
            case 10:
            case 13:
            case 20:
            case 23:
            case 31:
            case 39:
            case 40:
                return true;
            default:
                return false;
        }
    }

    private static Look boxes(Look look, float... boxes) {
        look.shape = SHAPE_BOXES;
        look.boxes = boxes;
        return look;
    }

    /** The bottom (or top) half and the raised half on the side the stairs go up to. */
    private static Look stairs(Look look, int meta) {
        boolean upsideDown = (meta & 4) != 0;
        float y0 = upsideDown ? 0.5f : 0, y1 = upsideDown ? 1 : 0.5f;
        float h0 = upsideDown ? 0 : 0.5f, h1 = upsideDown ? 0.5f : 1;
        float[] step;
        switch (meta & 3) {
            case 0:
                step = new float[] { 0.5f, h0, 0, 1, h1, 1 };
                break;
            case 1:
                step = new float[] { 0, h0, 0, 0.5f, h1, 1 };
                break;
            case 2:
                step = new float[] { 0, h0, 0.5f, 1, h1, 1 };
                break;
            default:
                step = new float[] { 0, h0, 0, 1, h1, 0.5f };
                break;
        }
        return boxes(look, 0, y0, 0, 1, y1, 1, step[0], step[1], step[2], step[3], step[4], step[5]);
    }

    private static float[] vinePlanes(int meta) {
        float near = 1 / 32f, far = 1 - near;
        float[] planes = new float[8];
        int n = 0;
        if ((meta & 1) != 0) {
            planes[n++] = PLANE_Z;
            planes[n++] = far;
        }
        if ((meta & 2) != 0) {
            planes[n++] = PLANE_X;
            planes[n++] = near;
        }
        if ((meta & 4) != 0) {
            planes[n++] = PLANE_Z;
            planes[n++] = near;
        }
        if ((meta & 8) != 0) {
            planes[n++] = PLANE_X;
            planes[n++] = far;
        }
        if (n == 0) {
            return new float[] { PLANE_Y, 1 - near };
        }
        return Arrays.copyOf(planes, n);
    }

    private static float[] ladderPlane(int meta) {
        float near = 1 / 16f, far = 1 - near;
        switch (meta) {
            case 2:
                return new float[] { PLANE_Z, far };
            case 3:
                return new float[] { PLANE_Z, near };
            case 4:
                return new float[] { PLANE_X, far };
            default:
                return new float[] { PLANE_X, near };
        }
    }

    /** Box of the block as an item shows it; slabs and snow by their metadata. */
    private static float[] bounds(Block block, int meta) {
        if (block instanceof BlockSlab && !block.isOpaqueCube()) {
            return (meta & 8) != 0 ? new float[] { 0, 0.5f, 0, 1, 1, 1 } : new float[] { 0, 0, 0, 1, 0.5f, 1 };
        }
        if (block instanceof BlockSnow) {
            return new float[] { 0, 0, 0, 1, ((meta & 7) + 1) / 8f, 1 };
        }
        try {
            block.setBlockBoundsForItemRender();
        } catch (Throwable ignored) {}
        float[] box = { (float) block.getBlockBoundsMinX(), (float) block.getBlockBoundsMinY(),
            (float) block.getBlockBoundsMinZ(), (float) block.getBlockBoundsMaxX(), (float) block.getBlockBoundsMaxY(),
            (float) block.getBlockBoundsMaxZ() };
        if (box[3] <= box[0] || box[4] <= box[1] || box[5] <= box[2]) {
            return new float[] { 0, 0, 0, 1, 1, 1 };
        }
        return box;
    }

    /**
     * Which biome color tints the block: the game's inventory color tells grass and leaves (their default biome
     * color) apart from fixed colors like birch and spruce leaves or lily pads.
     */
    private static void tint(Look look, Block block, int meta, Material material) {
        if (material == Material.water) {
            look.tint = TINT_WATER;
            return;
        }
        int color;
        try {
            color = block.getRenderColor(meta) & 0xFFFFFF;
        } catch (Throwable t) {
            color = 0xFFFFFF;
        }
        if (color == 0xFFFFFF) {
            look.tint = TINT_NONE;
        } else if (color == (ColorizerGrass.getGrassColor(0.5, 1.0) & 0xFFFFFF)) {
            look.tint = TINT_GRASS;
        } else if (color == (ColorizerFoliage.getFoliageColorBasic() & 0xFFFFFF)) {
            look.tint = TINT_FOLIAGE;
        } else {
            look.tint = TINT_FIXED;
            look.tintColor = color;
        }
    }

    private static Texture texture(Block block, int side, int meta) {
        IIcon icon;
        try {
            icon = block.getIcon(side, meta);
        } catch (Throwable t) {
            icon = null;
        }
        Texture texture = icon == null ? null : iconTexture(icon);
        if (texture != null) {
            return texture;
        }
        // No readable texture: the block's map color.
        int color = 0x808080;
        try {
            MapColor mapColor = block.getMapColor(meta);
            if (mapColor != null && mapColor.colorValue != 0) {
                color = mapColor.colorValue & 0xFFFFFF;
            }
        } catch (Throwable ignored) {}
        return Texture.solid(color);
    }

    private static Texture iconTexture(IIcon icon) {
        if (icon == null) {
            return null;
        }
        String name = icon.getIconName();
        String key = name != null ? name : "@" + System.identityHashCode(icon);
        Texture texture = TEXTURES.get(key);
        if (texture == null) {
            // As the game has it loaded (modded icons often have no file of their own), else from its file.
            int[] pixels = IconReader.read(icon);
            if (pixels == null && name != null) {
                pixels = readTexture(name);
            }
            texture = pixels == null ? UNREADABLE : Texture.of(pixels);
            TEXTURES.put(key, texture);
        }
        // Unreadable: the caller falls back to the map color, which beats plain gray.
        return texture == UNREADABLE ? null : texture;
    }

    /** The first frame of the block texture, scaled to 16x16 (averaged down, or repeated up). */
    private static int[] readTexture(String iconName) {
        String domain = "minecraft";
        String path = iconName;
        int colon = iconName.indexOf(':');
        if (colon >= 0) {
            domain = iconName.substring(0, colon);
            path = iconName.substring(colon + 1);
        }
        ResourceLocation location = new ResourceLocation(domain, "textures/blocks/" + path + ".png");
        try (InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(location)
            .getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return null;
            }
            int size = Math.min(image.getWidth(), image.getHeight());
            int[] source = image.getRGB(0, 0, size, size, null, 0, size);
            int[] pixels = new int[256];
            if (size >= 16) {
                int step = size / 16;
                int[] indices = new int[step * step];
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int n = 0;
                        for (int dy = 0; dy < step; dy++) {
                            for (int dx = 0; dx < step; dx++) {
                                indices[n++] = (y * step + dy) * size + x * step + dx;
                            }
                        }
                        pixels[y * 16 + x] = average(source, indices);
                    }
                }
            } else {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        pixels[y * 16 + x] = source[(y * size / 16) * size + x * size / 16];
                    }
                }
            }
            return pixels;
        } catch (Exception e) {
            WayFarMap.LOG.debug("Could not read texture {} for the 3D map", location);
            return null;
        }
    }
}
