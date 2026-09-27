package WayFarMap.client.gui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;

/** Fullscreen world map: drag to pan, mouse wheel to zoom. */
public class GuiWorldMap extends GuiScreen {

    private static final int DEFAULT_ZOOM = 3;

    /** Zoom level is kept between openings of the map. */
    private static int zoomIndex = DEFAULT_ZOOM;

    private double centerX;
    private double centerZ;
    private boolean dragging;
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void initGui() {
        super.initGui();
        if (mc.thePlayer != null) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
        }
    }

    private double scale() {
        return Config.MAP_ZOOMS[zoomIndex];
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF101010);

        MapDimension dimension = MapManager.INSTANCE.getDimension();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        double scale = scale();
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
        if (Config.showOtherPlayers) {
            MapDrawer.drawOtherPlayers(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, true);
        }

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double playerScreenX = width / 2.0 + (px - centerX) * scale;
        double playerScreenY = height / 2.0 + (pz - centerZ) * scale;
        if (playerScreenX >= 0 && playerScreenY >= 0 && playerScreenX <= width && playerScreenY <= height) {
            MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, mc.thePlayer.rotationYaw, 5f, 0xFFFFFFFF);
        }

        // Header and footer.
        drawRect(0, 0, width, 14, 0xA0000000);
        drawCenteredString(fontRendererObj, I18n.format("wayfarmap.gui.title"), width / 2, 3, 0xFFFFFF);
        String zoomText = scale >= 1 ? (int) scale + ":1" : "1:" + (int) Math.round(1 / scale);
        fontRendererObj.drawStringWithShadow(zoomText, width - 4 - fontRendererObj.getStringWidth(zoomText), 3, 0xAAAAAA);

        drawRect(0, height - 14, width, height, 0xA0000000);
        int hoverX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale);
        int hoverZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale);
        String cursorText = "X: " + hoverX + "  Z: " + hoverZ;
        if (!isExplored(dimension, hoverX, hoverZ)) {
            cursorText += "  (?)";
        }
        fontRendererObj.drawStringWithShadow(cursorText, 4, height - 11, 0xFFFFFF);
        String help = I18n.format("wayfarmap.gui.help");
        fontRendererObj
            .drawStringWithShadow(help, width - 4 - fontRendererObj.getStringWidth(help), height - 11, 0xAAAAAA);

        GL11.glColor4f(1f, 1f, 1f, 1f);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private static boolean isExplored(MapDimension dimension, int x, int z) {
        MapRegion region = dimension.getLoadedRegion(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        return region != null
            && (region.getPixel(x & (MapRegion.SIZE - 1), z & (MapRegion.SIZE - 1)) >>> 24) != 0;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        // Keep the block under the cursor in place while zooming.
        double oldScale = scale();
        double anchorX = centerX + (mouseX - width / 2.0) / oldScale;
        double anchorZ = centerZ + (mouseY - height / 2.0) / oldScale;
        zoomIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoomIndex + (wheel > 0 ? 1 : -1)));
        double newScale = scale();
        centerX = anchorX - (mouseX - width / 2.0) / newScale;
        centerZ = anchorZ - (mouseY - height / 2.0) / newScale;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceClick) {
        super.mouseClickMove(mouseX, mouseY, button, timeSinceClick);
        if (dragging && button == 0) {
            centerX -= (mouseX - lastMouseX) / scale();
            centerZ -= (mouseY - lastMouseY) / scale();
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button == 0) {
            dragging = false;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == KeyHandler.OPEN_MAP.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_SPACE && mc.thePlayer != null) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        MapManager.INSTANCE.trimAroundPlayer(mc.thePlayer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
