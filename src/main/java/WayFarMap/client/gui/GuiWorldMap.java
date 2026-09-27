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

    /** How fast the zoom animation approaches the target zoom (higher is faster). */
    private static final double ZOOM_SPEED = 18.0;

    private double centerX;
    private double centerZ;
    /** Currently displayed scale; animates towards {@code Config.MAP_ZOOMS[zoomIndex]}. */
    private double scale = Config.MAP_ZOOMS[zoomIndex];
    private boolean initialized;

    /** World point that stays under {@link #anchorScreenX}/{@link #anchorScreenY} while the zoom animates. */
    private double anchorWorldX, anchorWorldZ, anchorScreenX, anchorScreenY;
    private boolean zooming;

    private boolean dragging;
    private int lastRawMouseX;
    private int lastRawMouseY;
    private long lastFrameNanos;

    @Override
    public void initGui() {
        super.initGui();
        if (!initialized && mc.thePlayer != null) {
            // Only on first open, not when the window is resized.
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            initialized = true;
        }
        lastFrameNanos = System.nanoTime();
    }

    /** Moves and zooms the view once per frame, so panning is as smooth as the frame rate allows. */
    private void updateView() {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;

        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                dragging = false;
            } else {
                // Raw window coordinates give sub-GUI-pixel precision at any GUI scale.
                int rawX = Mouse.getX();
                int rawY = Mouse.getY();
                double dx = (rawX - lastRawMouseX) * (double) width / mc.displayWidth;
                double dy = -(rawY - lastRawMouseY) * (double) height / mc.displayHeight;
                lastRawMouseX = rawX;
                lastRawMouseY = rawY;
                centerX -= dx / scale;
                centerZ -= dy / scale;
                if (zooming) {
                    anchorWorldX -= dx / scale;
                    anchorWorldZ -= dy / scale;
                }
            }
        }

        if (zooming) {
            double target = Config.MAP_ZOOMS[zoomIndex];
            // Interpolate in log space so every zoom step feels equally fast.
            double t = 1.0 - Math.exp(-ZOOM_SPEED * seconds);
            double logScale = Math.log(scale) + (Math.log(target) - Math.log(scale)) * t;
            scale = Math.exp(logScale);
            if (Math.abs(scale - target) < target * 0.002) {
                scale = target;
                zooming = false;
            }
            centerX = anchorWorldX - (anchorScreenX - width / 2.0) / scale;
            centerZ = anchorWorldZ - (anchorScreenY - height / 2.0) / scale;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF101010);

        MapDimension dimension = MapManager.INSTANCE.getDimension();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        updateView();
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
        if (Config.showOtherPlayers) {
            MapDrawer.drawOtherPlayers(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, true);
        }

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double playerScreenX = width / 2.0 + (px - centerX) * scale;
        double playerScreenY = height / 2.0 + (pz - centerZ) * scale;
        if (playerScreenX >= 0 && playerScreenY >= 0 && playerScreenX <= width && playerScreenY <= height) {
            float yaw = mc.thePlayer.prevRotationYaw
                + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;
            MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, yaw, 5f, 0xFFFFFFFF);
        }

        // Header and footer.
        drawRect(0, 0, width, 14, 0xA0000000);
        drawCenteredString(fontRendererObj, I18n.format("wayfarmap.gui.title"), width / 2, 3, 0xFFFFFF);
        double targetScale = Config.MAP_ZOOMS[zoomIndex];
        String zoomText = targetScale >= 1 ? (int) targetScale + ":1" : "1:" + (int) Math.round(1 / targetScale);
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
        int newIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoomIndex + (wheel > 0 ? 1 : -1)));
        if (newIndex == zoomIndex) {
            return;
        }
        // Keep the block under the cursor in place while zooming.
        anchorScreenX = Mouse.getEventX() * (double) width / mc.displayWidth;
        anchorScreenY = height - Mouse.getEventY() * (double) height / mc.displayHeight;
        anchorWorldX = centerX + (anchorScreenX - width / 2.0) / scale;
        anchorWorldZ = centerZ + (anchorScreenY - height / 2.0) / scale;
        zoomIndex = newIndex;
        zooming = true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            dragging = true;
            lastRawMouseX = Mouse.getX();
            lastRawMouseY = Mouse.getY();
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
            zooming = false;
            scale = Config.MAP_ZOOMS[zoomIndex];
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
