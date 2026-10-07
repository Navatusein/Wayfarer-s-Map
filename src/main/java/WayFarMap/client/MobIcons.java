package WayFarMap.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelQuadruped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;

/**
 * Mob icons drawn by the game itself: each kind of mob is drawn once by its own renderer into an off-screen buffer,
 * facing the viewer, and its head cut out of the picture: the mob is drawn twice, with its head and with it hidden,
 * and what differs is the head, with everything the renderer puts on it (wool, glowing eyes, a helmet) and nothing
 * behind it, however the renderer scales or moves the model. A mob whose head can't be found is shown whole. Kept
 * per kind, skin and age; render thread only, taken while the HUD or a screen is drawn.
 */
public final class MobIcons {

    /** Pixels per side of the buffer and of the icons. */
    private static final int SIZE = 128, ICON = SIZE;
    /** Smaller copies down to 8 pixels, for the small icons of the minimap. */
    private static final int MIPMAP_LEVELS = 4;
    /** How many pixels far the colors at the edge are spread into the see-through ones around (see {@link #bleed}). */
    private static final int BLEED = 8;
    /** New icons per frame at most: a few renders each. */
    private static final int NEW_PER_FRAME = 1;
    private static final long FRAME_NANOS = 8_000_000L;
    /** How long before a mob whose icon failed is tried again. */
    private static final long RETRY_MS = 30_000;
    /** Head parts tried, best first, before the mob is shown whole. */
    private static final int CANDIDATES = 4;
    /** A head must take at least this share of the mob's picture (else it is some hidden part, or a speck). */
    private static final float MIN_HEAD_SHARE = 0.03f;
    private static final int MAX_ICONS = 256;
    /** GL_FRAMEBUFFER_BINDING (same value for the EXT and core versions). */
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    /** Textures of the icons by {@link #key}. */
    private static final Map<String, Integer> ICONS = new LinkedHashMap<String, Integer>(
        64,
        0.75f,
        true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            if (size() > MAX_ICONS) {
                GL11.glDeleteTextures(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private static final Map<String, Long> FAILED = new HashMap<>();

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static int failures;
    private static long frameStart;
    private static int takenThisFrame;
    private static Object iconWorld;

    private static Method textureMethod;
    private static Field mainModelField, renderPassModelField;
    private static boolean reflectionFailed;

    private MobIcons() {}

    /**
     * Draws the mob's icon as a square of {@code size} centered on (sx, sy), at the given opacity.
     *
     * @return false if it has none (yet), so the caller can draw something else
     */
    public static boolean draw(EntityLivingBase entity, double sx, double sy, float size, float alpha) {
        Integer icon = icon(entity);
        if (icon == null) {
            return false;
        }
        double half = size / 2.0;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, icon);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex3d(sx - half, sy + half, 0);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex3d(sx + half, sy + half, 0);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex3d(sx + half, sy - half, 0);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex3d(sx - half, sy - half, 0);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return true;
    }

    private static Integer icon(EntityLivingBase entity) {
        if (entity == null || failures >= 5 || !OpenGlHelper.isFramebufferEnabled() || !initReflection()) {
            return null;
        }
        Object world = Minecraft.getMinecraft().theWorld;
        if (world != iconWorld) {
            // Made again in each world: a resource pack or a mod's skins may have changed.
            iconWorld = world;
            for (int old : ICONS.values()) {
                GL11.glDeleteTextures(old);
            }
            ICONS.clear();
            FAILED.clear();
        }
        Render render = RenderManager.instance.getEntityRenderObject(entity);
        if (!(render instanceof RendererLivingEntity)) {
            return null;
        }
        String key = key(entity, render);
        if (key == null) {
            return null;
        }
        Integer icon = ICONS.get(key);
        if (icon != null) {
            return icon;
        }
        long now = System.currentTimeMillis();
        Long failedAt = FAILED.get(key);
        if (failedAt != null && now - failedAt < RETRY_MS) {
            return null;
        }
        long nanos = System.nanoTime();
        if (nanos - frameStart > FRAME_NANOS) {
            frameStart = nanos;
            takenThisFrame = 0;
        }
        if (takenThisFrame >= NEW_PER_FRAME) {
            return null;
        }
        takenThisFrame++;
        int[] pixels = make(entity, (RendererLivingEntity) render);
        if (pixels == null) {
            FAILED.put(key, now);
            return null;
        }
        icon = upload(pixels);
        ICONS.put(key, icon);
        return icon;
    }

    /** What makes mobs of a kind look alike: renderer, skin, age, and a sheep's wool. */
    private static String key(EntityLivingBase entity, Render render) {
        ResourceLocation texture;
        try {
            texture = (ResourceLocation) textureMethod.invoke(render, entity);
        } catch (Throwable t) {
            return null;
        }
        StringBuilder key = new StringBuilder(
            entity.getClass()
                .getName()).append('|')
                    .append(texture)
                    .append(entity.isChild() ? "|child" : "");
        if (entity instanceof EntitySheep) {
            EntitySheep sheep = (EntitySheep) entity;
            key.append("|wool")
                .append(sheep.getSheared() ? -1 : sheep.getFleeceColor());
        }
        return key.toString();
    }

    /** The icon's pixels (ICON x ICON, top row first, see-through around), or null if the mob couldn't be drawn. */
    private static int[] make(EntityLivingBase entity, RendererLivingEntity render) {
        Pose pose = new Pose(entity);
        try {
            pose.face();
            // The whole mob, framed by its size; again farther out if it reaches the edges (scaled by its renderer).
            float half = Math.max(entity.height, entity.width) * 0.6f + 0.2f, centerY = entity.height / 2f;
            int[] whole = null;
            int[] box = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                whole = shot(entity, 0f, centerY, half, null, null);
                if (whole == null) {
                    return null;
                }
                box = bounds(whole, null);
                if (box == null) {
                    return null;
                }
                if (box[0] > 0 && box[1] > 0 && box[2] < SIZE - 1 && box[3] < SIZE - 1) {
                    break;
                }
                half *= 2.5f;
                centerY *= 1.5f;
            }
            int wholeArea = count(whole);
            // A mob that moves by the clock (not by its ticks) differs from one picture to the next: no head can be
            // told from it.
            int[] again = shot(entity, 0f, centerY, half, null, null);
            boolean still = again != null && differing(whole, again) == 0;
            ModelBase main = model(mainModelField, render), pass = model(renderPassModelField, render);
            List<Integer> candidates = headCandidates(main);
            for (int n = 0; still && n < candidates.size() && n < CANDIDATES; n++) {
                ModelRenderer head = part(main, candidates.get(n));
                ModelRenderer passHead = pass != null && pass.getClass() == main.getClass()
                    ? part(pass, candidates.get(n))
                    : passHeadOf(pass);
                int[] headless = shot(entity, 0f, centerY, half, head, passHead);
                if (headless == null) {
                    continue;
                }
                int[] headBox = bounds(whole, headless);
                if (headBox == null || differing(whole, headless) < wholeArea * MIN_HEAD_SHARE) {
                    continue;
                }
                // The head up close: framed on what differed, drawn again with and without it.
                float[] frame = frame(headBox, 0f, centerY, half);
                int[] near = shot(entity, frame[0], frame[1], frame[2], null, null);
                int[] nearHeadless = shot(entity, frame[0], frame[1], frame[2], head, passHead);
                if (near == null || nearHeadless == null) {
                    continue;
                }
                return masked(near, nearHeadless);
            }
            // No head: the mob whole, framed on what was drawn.
            float[] frame = frame(box, 0f, centerY, half);
            int[] near = shot(entity, frame[0], frame[1], frame[2], null, null);
            return near == null ? null : masked(near, null);
        } catch (Throwable t) {
            if (++failures >= 5) {
                WayFarMap.LOG.warn("Mob icons can't be drawn off-screen; their faces are cut out of the skins", t);
            }
            return null;
        } finally {
            pose.restore();
        }
    }

    /**
     * Parts of the model that may be its head, best first: the head of bipeds and four-legged models, then the
     * parts with a box about as wide as tall and deep (not legs, arms or rods), highest and most in front first.
     */
    private static List<Integer> headCandidates(ModelBase model) {
        List<Integer> candidates = new ArrayList<>();
        if (model == null || model.boxList == null) {
            return candidates;
        }
        ModelRenderer known = model instanceof ModelBiped ? ((ModelBiped) model).bipedHead
            : model instanceof ModelQuadruped ? ((ModelQuadruped) model).head : null;
        int knownIndex = known == null ? -1 : model.boxList.indexOf(known);
        if (knownIndex >= 0) {
            candidates.add(knownIndex);
        }
        List<float[]> scored = new ArrayList<>();
        for (int i = 0; i < model.boxList.size(); i++) {
            Object o = model.boxList.get(i);
            if (i == knownIndex || !(o instanceof ModelRenderer)) {
                continue;
            }
            ModelRenderer part = (ModelRenderer) o;
            if (part.cubeList == null || part.cubeList.isEmpty() || !(part.cubeList.get(0) instanceof ModelBox)) {
                continue;
            }
            ModelBox box = largest(part);
            float w = box.posX2 - box.posX1, h = box.posY2 - box.posY1, d = box.posZ2 - box.posZ1;
            float min = Math.min(w, Math.min(h, d)), max = Math.max(w, Math.max(h, d));
            if (min < 2 || min < max * 0.5f) {
                continue;
            }
            float top = part.rotationPointY + box.posY1, front = part.rotationPointZ + box.posZ1;
            scored.add(new float[] { i, top + front * 0.5f });
        }
        scored.sort((a, b) -> Float.compare(a[1], b[1]));
        for (float[] s : scored) {
            candidates.add((int) s[0]);
        }
        return candidates;
    }

    private static ModelBox largest(ModelRenderer part) {
        ModelBox best = null;
        float volume = -1;
        for (Object o : part.cubeList) {
            if (o instanceof ModelBox) {
                ModelBox box = (ModelBox) o;
                float v = (box.posX2 - box.posX1) * (box.posY2 - box.posY1) * (box.posZ2 - box.posZ1);
                if (v > volume) {
                    volume = v;
                    best = box;
                }
            }
        }
        return best;
    }

    private static ModelRenderer part(ModelBase model, int index) {
        return model != null && model.boxList != null
            && index < model.boxList.size()
            && model.boxList.get(index) instanceof ModelRenderer ? (ModelRenderer) model.boxList.get(index) : null;
    }

    /** The head of the model drawn over the main one (a sheep's wool), if it has a known one. */
    private static ModelRenderer passHeadOf(ModelBase pass) {
        return pass instanceof ModelBiped ? ((ModelBiped) pass).bipedHead
            : pass instanceof ModelQuadruped ? ((ModelQuadruped) pass).head : null;
    }

    /**
     * A square around the pixels in {@code box} ({x0, y0, x1, y1} of a picture of {@code half} blocks around
     * (cx, cy)), a little larger: {cx, cy, half} of it.
     */
    private static float[] frame(int[] box, float cx, float cy, float half) {
        float perPixel = 2 * half / SIZE;
        float x0 = cx - half + box[0] * perPixel, x1 = cx - half + (box[2] + 1) * perPixel;
        float y1 = cy + half - box[1] * perPixel, y0 = cy + half - (box[3] + 1) * perPixel;
        float size = Math.max(x1 - x0, y1 - y0) * 0.54f;
        return new float[] { (x0 + x1) / 2, (y0 + y1) / 2, Math.max(size, perPixel * 4) };
    }

    /** {x0, y0, x1, y1} of the pixels drawn (or, with {@code other}, differing from it); null if none. */
    private static int[] bounds(int[] pixels, int[] other) {
        int x0 = SIZE, y0 = SIZE, x1 = -1, y1 = -1;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int p = pixels[y * SIZE + x];
                boolean on = other == null ? p >>> 24 != 0 : p != other[y * SIZE + x];
                if (on) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] { x0, y0, x1, y1 };
    }

    private static int count(int[] pixels) {
        int n = 0;
        for (int p : pixels) {
            if (p >>> 24 != 0) {
                n++;
            }
        }
        return n;
    }

    private static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                n++;
            }
        }
        return n;
    }

    /** The picture, only where it differs from {@code without} if given (the head alone). */
    private static int[] masked(int[] pixels, int[] without) {
        int[] icon = pixels.clone();
        if (without != null) {
            for (int i = 0; i < icon.length; i++) {
                if (icon[i] == without[i]) {
                    icon[i] = 0;
                }
            }
        }
        return icon;
    }

    /**
     * Puts the icon in a texture of its own with its smaller copies, made here rather than by the graphics card: it
     * mixed the see-through black around the head into its edges, and small icons came out dark and speckled.
     */
    private static int upload(int[] pixels) {
        bleed(pixels, ICON);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int levels = 0;
        int[] level = pixels;
        for (int side = ICON; ; side /= 2, levels++) {
            IntBuffer buffer = BufferUtils.createIntBuffer(side * side);
            buffer.put(level)
                .flip();
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                levels,
                GL11.GL_RGBA,
                side,
                side,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                buffer);
            if (levels == MIPMAP_LEVELS || side == 1) {
                break;
            }
            level = half(level, side);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    /** A copy half as large, each pixel the average of four weighted by how much they cover. */
    private static int[] half(int[] pixels, int side) {
        int small = side / 2;
        int[] result = new int[small * small];
        for (int y = 0; y < small; y++) {
            for (int x = 0; x < small; x++) {
                int i = y * 2 * side + x * 2;
                result[y * small + x] = average(pixels[i], pixels[i + 1], pixels[i + side], pixels[i + side + 1]);
            }
        }
        // Their see-through pixels take the colors next to them too.
        bleed(result, small);
        return result;
    }

    private static int average(int... colors) {
        long a = 0, r = 0, g = 0, b = 0;
        long cr = 0, cg = 0, cb = 0;
        for (int c : colors) {
            int alpha = c >>> 24;
            a += alpha;
            r += (c >> 16 & 0xFF) * alpha;
            g += (c >> 8 & 0xFF) * alpha;
            b += (c & 0xFF) * alpha;
            cr += c >> 16 & 0xFF;
            cg += c >> 8 & 0xFF;
            cb += c & 0xFF;
        }
        int n = colors.length;
        if (a == 0) {
            // All see-through: keep their (spread) color, for the copies smaller still.
            return (int) (cr / n) << 16 | (int) (cg / n) << 8 | (int) (cb / n);
        }
        return (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    /**
     * Gives the see-through pixels near the picture the color of the drawn ones next to them (still see-through):
     * blending at the edges then mixes in that color, not black.
     */
    private static void bleed(int[] pixels, int side) {
        boolean[] colored = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            colored[i] = pixels[i] >>> 24 != 0;
        }
        int[] next = new int[pixels.length];
        for (int pass = 0; pass < BLEED; pass++) {
            boolean changed = false;
            boolean[] nowColored = colored.clone();
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int i = y * side + x;
                    if (colored[i]) {
                        continue;
                    }
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= side || ny >= side || !colored[ny * side + nx]) {
                                continue;
                            }
                            int c = pixels[ny * side + nx];
                            r += c >> 16 & 0xFF;
                            g += c >> 8 & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    if (n > 0) {
                        next[i] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                        nowColored[i] = true;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return;
            }
            for (int i = 0; i < pixels.length; i++) {
                if (nowColored[i] && !colored[i]) {
                    pixels[i] = next[i];
                }
            }
            colored = nowColored;
        }
    }

    /**
     * The mob drawn by its renderer, lit as in the inventory, seen straight on (no perspective), {@code half} blocks
     * around (cx, cy) from its feet, with the given parts hidden. Pixels top row first, or null if it failed.
     */
    private static int[] shot(EntityLivingBase entity, float cx, float cy, float half, ModelRenderer hide,
        ModelRenderer hidePass) {
        Minecraft mc = Minecraft.getMinecraft();
        int previous = GL11.glGetInteger(FRAMEBUFFER_BINDING);
        boolean hidden = hide != null && hide.isHidden, passHidden = hidePass != null && hidePass.isHidden;
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
            }
            framebuffer.bindFramebuffer(true);
            bound = true;
            // The map may be drawn clipped (a round minimap): nothing of that here.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(cx - half, cx + half, cy - half, cy + half, -50, 50);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            // No light map: fully lit, as in the inventory.
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper
                .glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            // Unlit: the skin's own colors, like its texture (lit as in the inventory, faces came out dark).
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            if (hide != null) {
                hide.isHidden = true;
            }
            if (hidePass != null) {
                hidePass.isHidden = true;
            }
            RenderManager manager = RenderManager.instance;
            float viewY = manager.playerViewY;
            manager.playerViewY = 180f;
            try {
                manager.renderEntityWithPosYaw(entity, 0, 0, 0, 0f, 1f);
            } finally {
                manager.playerViewY = viewY;
            }
            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            int[] all = new int[SIZE * SIZE];
            readBuffer.get(all);
            // Read back bottom-up.
            int[] pixels = new int[SIZE * SIZE];
            for (int row = 0; row < SIZE; row++) {
                System.arraycopy(all, (SIZE - 1 - row) * SIZE, pixels, row * SIZE, SIZE);
            }
            return pixels;
        } catch (Throwable t) {
            return null;
        } finally {
            if (hide != null) {
                hide.isHidden = hidden;
            }
            if (hidePass != null) {
                hidePass.isHidden = passHidden;
            }
            if (bound) {
                Framebuffer game = mc.getFramebuffer();
                if (game != null && previous != 0 && previous == game.framebufferObject) {
                    game.bindFramebuffer(false);
                } else {
                    framebuffer.unbindFramebuffer();
                }
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** A mob's pose, kept while it is turned to face the viewer standing still, then put back. */
    private static final class Pose {

        final EntityLivingBase e;
        final float yaw, prevYaw, body, prevBody, head, prevHead, pitch, prevPitch, swing, prevSwing, limb, prevLimb;
        final int hurt, death;

        Pose(EntityLivingBase e) {
            this.e = e;
            yaw = e.rotationYaw;
            prevYaw = e.prevRotationYaw;
            body = e.renderYawOffset;
            prevBody = e.prevRenderYawOffset;
            head = e.rotationYawHead;
            prevHead = e.prevRotationYawHead;
            pitch = e.rotationPitch;
            prevPitch = e.prevRotationPitch;
            swing = e.swingProgress;
            prevSwing = e.prevSwingProgress;
            limb = e.limbSwingAmount;
            prevLimb = e.prevLimbSwingAmount;
            hurt = e.hurtTime;
            death = e.deathTime;
        }

        void face() {
            e.rotationYaw = e.prevRotationYaw = 0f;
            e.renderYawOffset = e.prevRenderYawOffset = 0f;
            e.rotationYawHead = e.prevRotationYawHead = 0f;
            e.rotationPitch = e.prevRotationPitch = 0f;
            e.swingProgress = e.prevSwingProgress = 0f;
            e.limbSwingAmount = e.prevLimbSwingAmount = 0f;
            // Not red from a hit, not lying down dying.
            e.hurtTime = 0;
            e.deathTime = 0;
        }

        void restore() {
            e.rotationYaw = yaw;
            e.prevRotationYaw = prevYaw;
            e.renderYawOffset = body;
            e.prevRenderYawOffset = prevBody;
            e.rotationYawHead = head;
            e.prevRotationYawHead = prevHead;
            e.rotationPitch = pitch;
            e.prevRotationPitch = prevPitch;
            e.swingProgress = swing;
            e.prevSwingProgress = prevSwing;
            e.limbSwingAmount = limb;
            e.prevLimbSwingAmount = prevLimb;
            e.hurtTime = hurt;
            e.deathTime = death;
        }
    }

    private static ModelBase model(Field field, Render render) {
        try {
            return field == null ? null : (ModelBase) field.get(render);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Looks up the private members by type, so it works with both dev and obfuscated names. */
    private static boolean initReflection() {
        if (textureMethod != null) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            for (Method method : Render.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == ResourceLocation.class && params.length == 1
                    && params[0] == Entity.class) {
                    method.setAccessible(true);
                    textureMethod = method;
                    break;
                }
            }
            // mainModel is declared before renderPassModel.
            for (Field field : RendererLivingEntity.class.getDeclaredFields()) {
                if (field.getType() == ModelBase.class) {
                    field.setAccessible(true);
                    if (mainModelField == null) {
                        mainModelField = field;
                    } else {
                        renderPassModelField = field;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Mob icons are unavailable", t);
        }
        if (textureMethod == null || mainModelField == null) {
            reflectionFailed = true;
            textureMethod = null;
            return false;
        }
        return true;
    }
}
