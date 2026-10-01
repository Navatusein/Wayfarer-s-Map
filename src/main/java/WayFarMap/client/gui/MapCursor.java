package WayFarMap.client.gui;

import java.nio.IntBuffer;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;

/**
 * The mouse cursor of the world map, when one of the shapes is picked instead of the system cursor: drawn by the map
 * over everything else, in the color and size of the settings, while the system cursor is hidden.
 */
public final class MapCursor {

    private static final int CIRCLE_SEGMENTS = 32;
    /** Arrow outline, the tip at (0, 0), one unit high. */
    private static final double[][] ARROW = { { 0, 0 }, { 0, 0.95 }, { 0.27, 0.7 }, { 0.68, 0.68 } };

    /** An empty system cursor, made once; null if the system cannot hide its cursor. */
    private static Cursor empty;
    private static boolean emptyMade;
    /** The system cursor is hidden by us now. */
    private static boolean hidden;

    private MapCursor() {}

    public static boolean isCustom() {
        return Config.cursorStyle != Config.CURSOR_SYSTEM;
    }

    /** Hides the system cursor while a shape is drawn instead, shows it again otherwise. */
    public static void updateSystemCursor() {
        boolean hide = isCustom();
        if (hide == hidden) {
            return;
        }
        try {
            if (hide) {
                Cursor cursor = emptyCursor();
                if (cursor == null) {
                    return;
                }
                Mouse.setNativeCursor(cursor);
            } else {
                Mouse.setNativeCursor(null);
            }
            hidden = hide;
        } catch (LWJGLException | RuntimeException e) {
            // The system cursor stays; the shape is drawn over it.
        }
    }

    /** The system cursor back, when the map is closed. */
    public static void restoreSystemCursor() {
        if (!hidden) {
            return;
        }
        try {
            Mouse.setNativeCursor(null);
        } catch (LWJGLException | RuntimeException e) {
            // Nothing else to do.
        }
        hidden = false;
    }

    private static Cursor emptyCursor() throws LWJGLException {
        if (!emptyMade) {
            emptyMade = true;
            if ((Cursor.getCapabilities() & Cursor.CURSOR_ONE_BIT_TRANSPARENCY) != 0) {
                int size = Math.max(1, Cursor.getMinCursorSize());
                IntBuffer pixels = BufferUtils.createIntBuffer(size * size);
                empty = new Cursor(size, size, 0, 0, 1, pixels, null);
            }
        }
        return empty;
    }

    /** Draws the cursor of the settings at (x, y), in GUI pixels; nothing for the system one. */
    public static void draw(double x, double y) {
        draw(Config.cursorStyle, x, y, Config.cursorSize, 0xFF000000 | Config.cursorColor, Config.cursorOutline);
    }

    public static void draw(int style, double x, double y, double size, int color, boolean outline) {
        if (style == Config.CURSOR_SYSTEM) {
            return;
        }
        // Lines get thicker with the size, at least a GUI pixel.
        double thickness = Math.max(1, size / 12);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        if (outline) {
            // The shape in black around itself, then in its color on top.
            double o = Math.max(0.75, thickness * 0.6);
            setColor(tessellator, 0xFF000000);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        shape(tessellator, style, x + dx * o, y + dy * o, size, thickness);
                    }
                }
            }
        }
        setColor(tessellator, color);
        shape(tessellator, style, x, y, size, thickness);
        tessellator.draw();
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void setColor(Tessellator tessellator, int argb) {
        tessellator.setColorRGBA_I(argb & 0xFFFFFF, (argb >>> 24) & 0xFF);
    }

    /** Triangles of the shape: the arrow's tip at (x, y), the others centered on it. */
    private static void shape(Tessellator t, int style, double x, double y, double size, double thickness) {
        double half = size / 2, line = thickness / 2;
        switch (style) {
            case Config.CURSOR_ARROW:
                for (int i = 1; i + 1 < ARROW.length; i++) {
                    vertex(t, x + ARROW[0][0] * size, y + ARROW[0][1] * size);
                    vertex(t, x + ARROW[i][0] * size, y + ARROW[i][1] * size);
                    vertex(t, x + ARROW[i + 1][0] * size, y + ARROW[i + 1][1] * size);
                }
                break;
            case Config.CURSOR_CROSSHAIR: {
                // Four arms with a gap at the middle.
                double gap = Math.max(thickness, size * 0.2);
                rect(t, x - half, y - line, x - gap, y + line);
                rect(t, x + gap, y - line, x + half, y + line);
                rect(t, x - line, y - half, x + line, y - gap);
                rect(t, x - line, y + gap, x + line, y + half);
                break;
            }
            case Config.CURSOR_PLUS:
                rect(t, x - half, y - line, x + half, y + line);
                rect(t, x - line, y - half, x + line, y - line);
                rect(t, x - line, y + line, x + line, y + half);
                break;
            case Config.CURSOR_DOT:
                disc(t, x, y, half * 0.5);
                break;
            case Config.CURSOR_CIRCLE:
                ring(t, x, y, half - thickness, half);
                break;
            case Config.CURSOR_TARGET:
                ring(t, x, y, half - thickness, half);
                disc(t, x, y, Math.max(thickness, size * 0.1));
                break;
            case Config.CURSOR_SQUARE:
                rect(t, x - half, y - half, x + half, y - half + thickness);
                rect(t, x - half, y + half - thickness, x + half, y + half);
                rect(t, x - half, y - half + thickness, x - half + thickness, y + half - thickness);
                rect(t, x + half - thickness, y - half + thickness, x + half, y + half - thickness);
                break;
            default:
                break;
        }
    }

    private static void vertex(Tessellator t, double x, double y) {
        t.addVertex(x, y, 0);
    }

    private static void rect(Tessellator t, double x0, double y0, double x1, double y1) {
        vertex(t, x0, y0);
        vertex(t, x0, y1);
        vertex(t, x1, y1);
        vertex(t, x0, y0);
        vertex(t, x1, y1);
        vertex(t, x1, y0);
    }

    private static void disc(Tessellator t, double x, double y, double radius) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / CIRCLE_SEGMENTS, a1 = 2 * Math.PI * (i + 1) / CIRCLE_SEGMENTS;
            vertex(t, x, y);
            vertex(t, x + Math.cos(a1) * radius, y + Math.sin(a1) * radius);
            vertex(t, x + Math.cos(a0) * radius, y + Math.sin(a0) * radius);
        }
    }

    private static void ring(Tessellator t, double x, double y, double inner, double outer) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / CIRCLE_SEGMENTS, a1 = 2 * Math.PI * (i + 1) / CIRCLE_SEGMENTS;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            vertex(t, x + c0 * inner, y + s0 * inner);
            vertex(t, x + c1 * outer, y + s1 * outer);
            vertex(t, x + c0 * outer, y + s0 * outer);
            vertex(t, x + c0 * inner, y + s0 * inner);
            vertex(t, x + c1 * inner, y + s1 * inner);
            vertex(t, x + c1 * outer, y + s1 * outer);
        }
    }
}
