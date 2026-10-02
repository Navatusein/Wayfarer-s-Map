package WayFarMap.client.waypoint;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;

/**
 * Pictures of items exactly as the game draws them in the inventory (blocks in 3D, items with their own renderer,
 * several layers), taken once in an off-screen buffer with the game's GUI setup and kept as textures. Waypoint icons
 * are drawn from them anywhere: in the world a block's icon alone was one face of it (a chest showed its planks),
 * items drawn by their own renderer have no icon at all and were invisible, and on the maps the state around could
 * cut blocks off. Render thread only.
 */
final class ItemSprites {

    /** Pixels per side of a picture (an inventory slot is 16 GUI pixels). */
    private static final int SIZE = 32;
    /** Pictures kept (4 KB each): more than the waypoints of a world use. */
    private static final int MAX_PICTURES = 1024;
    /**
     * New pictures taken per frame at most: each waits for the graphics card. Until an item's turn it is drawn the
     * old way.
     */
    private static final int NEW_PER_FRAME = 4;
    private static final long FRAME_NANOS = 10_000_000L;
    /** GL_FRAMEBUFFER_BINDING (same value for the EXT and core versions). */
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    private static final RenderItem RENDER_ITEM = new RenderItem();
    private static final Map<String, DynamicTexture> PICTURES = new LinkedHashMap<String, DynamicTexture>(
        64,
        0.75f,
        true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DynamicTexture> eldest) {
            if (size() > MAX_PICTURES) {
                eldest.getValue()
                    .deleteGlTexture();
                return true;
            }
            return false;
        }
    };
    /** Items whose picture came out empty, and when: drawn the old way until tried again. */
    private static final Map<String, Long> EMPTY = new HashMap<>();
    /** Pictures asked for where they can't be taken (while the world is drawn), taken while the HUD is drawn. */
    private static final Map<String, ItemStack> QUEUE = new LinkedHashMap<>();
    /** After joining a world, how long before pictures are taken. */
    private static final long SETTLE_MS = 3000;
    /** How long before an empty picture is tried again. */
    private static final long RETRY_MS = 10_000;
    private static long worldSince;

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static int failures;
    private static long frameStart;
    private static int takenThisFrame;
    /** The world the pictures were taken in. */
    private static Object pictureWorld;

    private ItemSprites() {}

    /**
     * Draws the item's picture as a square of {@code size} centered on (cx, cy) in the current coordinates (a GUI or
     * a billboard in the world), with the current blending.
     *
     * @return false if there is no picture of it (then it is drawn another way)
     */
    static boolean draw(ItemStack stack, double cx, double cy, double size, boolean mayTake) {
        DynamicTexture picture = picture(stack, mayTake);
        if (picture == null) {
            return false;
        }
        double x0 = cx - size / 2, y0 = cy - size / 2, x1 = x0 + size, y1 = y0 + size;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, picture.getGlTextureId());
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex3d(x0, y1, 0);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex3d(x1, y1, 0);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex3d(x1, y0, 0);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex3d(x0, y0, 0);
        GL11.glEnd();
        return true;
    }

    /**
     * Takes the pictures asked for while the world was drawn. Called while the HUD is drawn: the same state as the
     * inventory, which the world's drawing is not (pictures taken in it could come out broken).
     */
    static void takeQueued() {
        if (QUEUE.isEmpty()) {
            return;
        }
        for (ItemStack stack : new ArrayList<>(QUEUE.values())) {
            picture(stack, true);
        }
    }

    private static DynamicTexture picture(ItemStack stack, boolean mayTake) {
        Item item = stack.getItem();
        if (item == null || failures >= 5 || !OpenGlHelper.isFramebufferEnabled()) {
            return null;
        }
        Object world = Minecraft.getMinecraft().theWorld;
        long now = System.currentTimeMillis();
        if (world != pictureWorld) {
            // Taken again in each world, and only once it has settled: a picture taken while the game was still
            // setting the world up could be wrong.
            pictureWorld = world;
            worldSince = now;
            for (DynamicTexture old : PICTURES.values()) {
                old.deleteGlTexture();
            }
            PICTURES.clear();
            EMPTY.clear();
            QUEUE.clear();
        }
        // Items drawn from their NBT (Tinkers' tools) look different with each.
        String key = Item.getIdFromItem(item) + ":"
            + stack.getItemDamage()
            + (stack.hasTagCompound() ? ":" + stack.getTagCompound()
                .toString()
                .hashCode() : "");
        DynamicTexture picture = PICTURES.get(key);
        if (picture != null) {
            return picture;
        }
        Long emptyAt = EMPTY.get(key);
        if (emptyAt != null && now - emptyAt < RETRY_MS) {
            return null;
        }
        if (!mayTake || now - worldSince < SETTLE_MS) {
            QUEUE.put(key, stack);
            return null;
        }
        long nanos = System.nanoTime();
        if (nanos - frameStart > FRAME_NANOS) {
            frameStart = nanos;
            takenThisFrame = 0;
        }
        if (takenThisFrame >= NEW_PER_FRAME) {
            QUEUE.put(key, stack);
            return null;
        }
        takenThisFrame++;
        QUEUE.remove(key);
        int[] pixels = take(stack);
        if (pixels == null) {
            // Drawn the old way for now; tried again later (a mod's textures may not have been ready).
            EMPTY.put(key, now);
            return null;
        }
        picture = new DynamicTexture(SIZE, SIZE);
        System.arraycopy(pixels, 0, picture.getTextureData(), 0, pixels.length);
        picture.updateDynamicTexture();
        // Smooth when drawn smaller than taken.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, picture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        PICTURES.put(key, picture);
        return picture;
    }

    /** Draws the item as in an inventory slot into the buffer and reads it back; null if nothing was drawn. */
    private static int[] take(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        int previous = GL11.glGetInteger(FRAMEBUFFER_BINDING);
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
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // The game's GUI projection for a 16x16 slot.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 16, 16, 0, 1000, 3000);
            // No light map, as in the inventory: taken while the world is drawn (the in-world marker) it tinted the
            // picture with the light of some place, often black, and the broken picture stayed.
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper
                .glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslatef(0f, 0f, -2000f);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            RenderHelper.enableGUIStandardItemLighting();
            boolean rendered = WaypointRenderer.renderItemSafely(RENDER_ITEM, mc, stack);
            RenderHelper.disableStandardItemLighting();
            if (!rendered) {
                return null;
            }

            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            int[] all = new int[SIZE * SIZE];
            readBuffer.get(all);
            // The top of the picture is read last: turn the rows around.
            int[] pixels = new int[SIZE * SIZE];
            boolean drawn = false;
            for (int row = 0; row < SIZE; row++) {
                System.arraycopy(all, (SIZE - 1 - row) * SIZE, pixels, row * SIZE, SIZE);
            }
            for (int pixel : pixels) {
                if ((pixel >>> 24) != 0) {
                    drawn = true;
                    break;
                }
            }
            failures = 0;
            return drawn ? pixels : null;
        } catch (Throwable t) {
            // A modded renderer that fails here: the item is drawn another way.
            if (++failures >= 5) {
                WayFarMap.LOG.warn("Waypoint icons can't be drawn off-screen; plain icons are used", t);
            }
            return null;
        } finally {
            if (bound) {
                // Back to the buffer bound before: the game's own while a frame is drawn.
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
}
