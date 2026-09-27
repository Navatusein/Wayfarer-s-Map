package WayFarMap.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;

/**
 * Base of the mod's screens: they are laid out at their own scale instead of Minecraft's GUI scale, so they look the
 * same whatever that is set to. "Auto" picks the largest whole scale that still leaves about 800x450 GUI pixels, so
 * the world map's header fits and nothing ends up tiny. Subclasses draw in {@link #drawScaled}.
 */
public abstract class ScaledScreen extends GuiScreen {

    private static final int MIN_WIDTH = 800, MIN_HEIGHT = 450;

    /** Scale the screen being drawn uses (screen pixels per GUI pixel), 0 when none of ours is drawing. */
    private static int activeFactor;

    private int appliedFactor;

    /** Screen pixels per GUI pixel of the mod's screens. */
    public static int factor(Minecraft mc) {
        if (Config.uiScale > 0) {
            return Config.uiScale;
        }
        int fit = Math.min(mc.displayWidth / MIN_WIDTH, mc.displayHeight / MIN_HEIGHT);
        return Math.max(1, fit);
    }

    /**
     * Screen pixels per GUI pixel of what is being drawn right now: the mod's screen scale inside its screens,
     * Minecraft's GUI scale elsewhere (the HUD).
     */
    public static int currentFactor() {
        if (activeFactor > 0) {
            return activeFactor;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        appliedFactor = factor(mc);
        super.setWorldAndResolution(
            mc,
            (mc.displayWidth + appliedFactor - 1) / appliedFactor,
            (mc.displayHeight + appliedFactor - 1) / appliedFactor);
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (factor(mc) != appliedFactor) {
            // The scale option changed (or the window): lay the screen out again.
            setWorldAndResolution(mc, 0, 0);
        }
        int minecraftFactor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        float scale = (float) appliedFactor / minecraftFactor;
        // Minecraft passes the mouse in its own GUI pixels; ours are different.
        int x = Mouse.getX() * width / mc.displayWidth;
        int y = height - Mouse.getY() * height / mc.displayHeight - 1;
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1f);
        activeFactor = appliedFactor;
        try {
            drawScaled(x, y, partialTicks);
        } finally {
            activeFactor = 0;
            GL11.glPopMatrix();
        }
    }

    /** Draws the screen in its own GUI pixels; the default draws the buttons. */
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
