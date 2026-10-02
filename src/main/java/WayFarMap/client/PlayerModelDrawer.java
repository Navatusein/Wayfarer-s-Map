package WayFarMap.client;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.iso.IsoProjection;

/**
 * Draws a player as the game's own 3D model on the 3D world map, seen from the same side and height as the map (so it
 * stands in it like the blocks do), with its skin, armor, walking and where it looks. Done like the inventory screen
 * draws the player, with the map's view instead of the inventory's.
 */
public final class PlayerModelDrawer {

    /** Smallest size it is drawn at when the map is zoomed out, in GUI pixels per block (a player is 1.8 tall). */
    private static final float MIN_PIXELS_PER_BLOCK = 9f;
    /** In front of the map and what is drawn on it. */
    private static final float DEPTH = 200f;

    /** Set once drawing the model failed (a mod's player renderer): the arrow is drawn from then on. */
    private static boolean failed;

    private PlayerModelDrawer() {}

    /**
     * Draws the entity standing with its feet on the screen point.
     *
     * @param pixelsPerBlock the map's scale, GUI pixels per block
     * @return false if it can't be drawn (draw the arrow instead)
     */
    public static boolean draw(EntityLivingBase entity, double sx, double sy, double pixelsPerBlock,
        IsoProjection projection, float partialTicks) {
        if (failed) {
            return false;
        }
        float scale = (float) Math.max(MIN_PIXELS_PER_BLOCK, pixelsPerBlock);
        RenderManager manager = RenderManager.instance;
        float viewY = manager.playerViewY, viewX = manager.playerViewX;
        boolean ok = true;
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glTranslated(sx, sy, DEPTH);
            // World up is screen up.
            GL11.glScalef(scale, -scale, scale);
            // The map's view: looked at from 30 degrees above, from the side the map is turned to (the direction
            // toward the viewer ends up out of the screen).
            GL11.glRotatef((float) Math.toDegrees(IsoProjection.ELEVATION), 1f, 0f, 0f);
            double azimuth = Math.toDegrees(Math.atan2(projection.towardX, projection.towardZ));
            GL11.glRotatef((float) -azimuth, 0f, 1f, 0f);
            // Lit from above, fixed in the world like the map's light.
            RenderHelper.enableStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            // The game draws the player that far below its position.
            GL11.glTranslatef(0f, entity.yOffset, 0f);
            // Name tags would face this way (the player's own has none).
            manager.playerViewY = (float) (180 - azimuth);
            manager.playerViewX = (float) Math.toDegrees(IsoProjection.ELEVATION);
            float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            manager.renderEntityWithPosYaw(entity, 0, 0, 0, yaw, partialTicks);
        } catch (Throwable t) {
            ok = false;
            failed = true;
            WayFarMap.LOG.warn("Could not draw the player on the 3D map; the arrow is used instead", t);
        } finally {
            manager.playerViewY = viewY;
            manager.playerViewX = viewX;
            GL11.glPopMatrix();
            // Back as the inventory screen leaves it.
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            // The model's depth would hide what the screen draws over it afterwards (menus, tooltips).
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            if (!depthTest) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
        return ok;
    }
}
