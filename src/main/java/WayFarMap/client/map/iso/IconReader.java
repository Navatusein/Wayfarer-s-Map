package WayFarMap.client.map.iso;

import java.nio.IntBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.IIcon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;

/**
 * Reads a block icon's pixels from the game's block texture atlas on the graphics card: exactly what the game draws
 * with, whatever resource pack or mod made it (many modded icons have no plain file to read). The icon is drawn into
 * a 16x16 off-screen buffer and read back. Render thread only.
 */
final class IconReader {

    private static final int SIZE = 16;
    /** GL_FRAMEBUFFER_BINDING (same value for the EXT and core versions). */
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    private static Framebuffer framebuffer;
    private static IntBuffer readBuffer;
    private static int failures;

    private IconReader() {}

    /** Whether icons can be read from the graphics card. */
    static boolean available() {
        return failures < 5 && OpenGlHelper.isFramebufferEnabled();
    }

    /** The icon as 16x16 ARGB, or null if it can't be read. */
    static int[] read(IIcon icon) {
        if (icon == null || !available()) {
            return null;
        }
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
                framebuffer = new Framebuffer(SIZE, SIZE, false);
                readBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            framebuffer.bindFramebuffer(true);
            bound = true;
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 1, 1, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            double u0 = icon.getMinU(), u1 = icon.getMaxU(), v0 = icon.getMinV(), v1 = icon.getMaxV();
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(0, 1, 0, u0, v1);
            tessellator.addVertexWithUV(1, 1, 0, u1, v1);
            tessellator.addVertexWithUV(1, 0, 0, u1, v0);
            tessellator.addVertexWithUV(0, 0, 0, u0, v0);
            tessellator.draw();
            readBuffer.clear();
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
            int[] all = new int[SIZE * SIZE];
            readBuffer.get(all);
            // The icon's top is at the top of the buffer, which is read last: turn the rows around.
            int[] pixels = new int[SIZE * SIZE];
            for (int row = 0; row < SIZE; row++) {
                System.arraycopy(all, (SIZE - 1 - row) * SIZE, pixels, row * SIZE, SIZE);
            }
            failures = 0;
            for (int pixel : pixels) {
                if ((pixel >>> 24) != 0) {
                    return pixels;
                }
            }
            // Nothing there (not an icon of the block atlas): read from its file instead.
            return null;
        } catch (Throwable t) {
            if (++failures >= 5) {
                WayFarMap.LOG.warn("The 3D map can't read block textures from the graphics card", t);
            }
            return null;
        } finally {
            if (bound) {
                rebind(mc, previous, framebuffer);
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /**
     * Binds the frame buffer that was bound before: the game's own while a frame is drawn (the world map is drawn
     * into it), none between frames.
     */
    static void rebind(Minecraft mc, int previous, Framebuffer own) {
        Framebuffer game = mc.getFramebuffer();
        if (game != null && previous != 0 && previous == game.framebufferObject) {
            game.bindFramebuffer(false);
        } else {
            own.unbindFramebuffer();
        }
    }
}
