package WayFarMap.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Draws the minimap in a corner of the HUD. */
public class MinimapRenderer {

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !Config.minimapEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        MapDimension dimension = MapManager.INSTANCE.getDimension();
        if (player == null || mc.theWorld == null
            || dimension == null
            || mc.gameSettings.showDebugInfo
            || mc.currentScreen instanceof GuiWorldMap) {
            return;
        }

        float partialTicks = event.partialTicks;
        double px = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double pz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        List<String> lines = new ArrayList<>();
        int blockX = MathHelper.floor_double(player.posX);
        int blockZ = MathHelper.floor_double(player.posZ);
        if (Config.minimapShowCoordinates) {
            lines.add(blockX + ", " + MathHelper.floor_double(player.boundingBox.minY) + ", " + blockZ);
        }
        if (Config.minimapShowBiome) {
            lines.add(mc.theWorld.getBiomeGenForCoords(blockX, blockZ).biomeName);
        }

        int size = Config.minimapSize;
        int screenWidth = event.resolution.getScaledWidth();
        int screenHeight = event.resolution.getScaledHeight();
        int x = Config.minimapCorner % 2 == 0 ? MARGIN : screenWidth - MARGIN - size;
        int y = Config.minimapCorner < 2 ? MARGIN : screenHeight - MARGIN - size - lines.size() * LINE_HEIGHT;
        double scale = Config.MINIMAP_ZOOMS[Math.max(0, Math.min(Config.MINIMAP_ZOOMS.length - 1, Config.minimapZoom))];

        GL11.glPushMatrix();
        Gui.drawRect(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000);
        Gui.drawRect(x, y, x + size, y + size, 0xFF202020);

        MapDrawer.drawMap(dimension, px, pz, scale, x, y, size, size);
        if (Config.showOtherPlayers) {
            MapDrawer.drawOtherPlayers(mc, px, pz, scale, x, y, size, size, partialTicks, false);
        }
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        MapDrawer.drawPlayerArrow(x + size / 2.0, y + size / 2.0, yaw, 3.5f, 0xFFFFFFFF);

        FontRenderer font = mc.fontRenderer;
        font.drawStringWithShadow("N", x + size / 2 - font.getStringWidth("N") / 2, y + 2, 0xFFFFFF);
        int textY = y + size + 3;
        for (String line : lines) {
            font.drawStringWithShadow(line, x + size / 2 - font.getStringWidth(line) / 2, textY, 0xFFFFFF);
            textY += LINE_HEIGHT;
        }

        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }
}
