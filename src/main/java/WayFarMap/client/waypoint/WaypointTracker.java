package WayFarMap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.gui.ui.Theme;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** The tracked waypoint: an arrow at the top of the screen pointing to it, until the player gets there. */
public class WaypointTracker {

    public static final WaypointTracker INSTANCE = new WaypointTracker();

    /** Closer than this (in blocks, horizontally) the waypoint is reached and no longer tracked. */
    private static final double ARRIVAL_DISTANCE = 3.0;
    private static final int DEFAULT_COLOR = 0x4C9AFF;

    private WaypointTracker() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        Waypoint tracked = WaypointManager.INSTANCE.getTracked();
        if (tracked == null || player == null || mc.theWorld == null || player.getHealth() <= 0) {
            return;
        }
        if (tracked.dimension == mc.theWorld.provider.dimensionId
            && horizontalDistance(tracked, player.posX, player.posZ) < ARRIVAL_DISTANCE) {
            WaypointManager.INSTANCE.setTracked(null);
            player.addChatMessage(new ChatComponentText(I18n.format("wayfarmap.track.arrived", tracked.name)));
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        Waypoint tracked = WaypointManager.INSTANCE.getTracked();
        if (tracked == null || player == null
            || mc.theWorld == null
            || mc.gameSettings.hideGUI
            || mc.currentScreen instanceof GuiWorldMap) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        boolean here = tracked.dimension == mc.theWorld.provider.dimensionId;
        float partialTicks = event.partialTicks;
        double px = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double pz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        String name = Theme.ellipsize(font, tracked.name.isEmpty() ? "-" : tracked.name, 160);
        String text = here ? name + "  " + Math.round(horizontalDistance(tracked, px, pz)) + "m"
            : name + "  [DIM " + tracked.dimension + "]";
        int color = tracked.outlineColor != null ? tracked.outlineColor : DEFAULT_COLOR;

        int textWidth = font.getStringWidth(text);
        int arrowRoom = here ? 16 : 0;
        int boxWidth = arrowRoom + textWidth + 8;
        int x0 = (event.resolution.getScaledWidth() - boxWidth) / 2;
        // Below the boss health bar when there is one.
        int y0 = BossStatus.bossName != null && BossStatus.statusBarTime > 0 ? 22 : 3;
        int y1 = y0 + 16;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Gui.drawRect(x0 - 1, y0 - 1, x0 + boxWidth + 1, y1 + 1, 0xFF000000 | color);
        Gui.drawRect(x0, y0, x0 + boxWidth, y1, 0xC0101418);
        if (here) {
            double dx = tracked.x + 0.5 - px;
            double dz = tracked.z + 0.5 - pz;
            float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
            drawArrow(x0 + 10, y0 + 8, targetYaw - yaw, color);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        font.drawStringWithShadow(text, x0 + 4 + arrowRoom, y0 + 4, 0xFFFFFFFF);
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** An arrow centered on (cx, cy): pointing up means straight ahead, turned clockwise by {@code degrees}. */
    private static void drawArrow(double cx, double cy, float degrees, int color) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, 0);
        GL11.glRotatef(degrees, 0f, 0f, 1f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        arrow(1.35, 0xFF000000);
        arrow(1.0, 0xFF000000 | color);
        GL11.glPopMatrix();
    }

    private static void arrow(double size, int color) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(0, -6 * size, 0);
        tessellator.addVertex(-5 * size, 5 * size, 0);
        tessellator.addVertex(0, 2 * size, 0);
        tessellator.addVertex(0, -6 * size, 0);
        tessellator.addVertex(0, 2 * size, 0);
        tessellator.addVertex(5 * size, 5 * size, 0);
        tessellator.draw();
    }

    private static double horizontalDistance(Waypoint waypoint, double x, double z) {
        double dx = waypoint.x + 0.5 - x;
        double dz = waypoint.z + 0.5 - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
