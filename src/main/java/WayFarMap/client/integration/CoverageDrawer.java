package WayFarMap.client.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/**
 * Coverage circles drawn as their union: one even fill and one outline around circles that overlap, instead of fills
 * darkening each other and outlines crossing inside. Each circle has a filled slice (where it reaches at the player's
 * height) and a thin ring of its full range; both are joined separately.
 * <p>
 * Circles are joined only with those of the same {@link Circle#priority}; each priority is a layer drawn whole,
 * the higher ones over it: a broken access point's circle shows complete under the working ones' circles.
 * <p>
 * Outlines: the arcs of each circle that lie inside another circle of its layer are cut out (the two crossing points
 * of two circles), on the CPU. Fills: through the depth buffer, every pixel is filled by the
 * first circle that reaches it and skipped by the others; the stencil buffer would be the usual way, but in 1.7.10
 * the window often has none. The depth of the filled pixels is reset to the far value before and after, so what is
 * drawn around the layer isn't affected.
 */
public final class CoverageDrawer {

    /** One circle, in screen pixels. */
    static final class Circle {

        final double x, y, full, slice;
        final int rgb, fillAlpha, priority;

        Circle(double x, double y, double full, double slice, int rgb, int fillAlpha, int priority) {
            this.x = x;
            this.y = y;
            this.full = full;
            this.slice = slice;
            this.rgb = rgb;
            this.fillAlpha = fillAlpha;
            this.priority = priority;
        }
    }

    private CoverageDrawer() {}

    /** {@code fullRange}: the thin rings of the full range too, not only the slices. */
    static void draw(List<Circle> circles, boolean minimap, boolean fullRange) {
        if (circles.isEmpty()) {
            return;
        }
        List<Circle> ordered = new ArrayList<>(circles);
        ordered.sort((a, b) -> Integer.compare(a.priority, b.priority));
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int start = 0; start < ordered.size();) {
            int end = start;
            while (end < ordered.size() && ordered.get(end).priority == ordered.get(start).priority) {
                end++;
            }
            drawLayer(tessellator, ordered.subList(start, end), minimap, fullRange);
            start = end;
        }
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Circles of one priority: their joined fill, then their joined outlines. */
    private static void drawLayer(Tessellator tessellator, List<Circle> layer, boolean minimap, boolean fullRange) {
        fills(tessellator, layer);
        List<List<double[]>> sliceArcs = new ArrayList<>(), fullArcs = new ArrayList<>();
        for (int i = 0; i < layer.size(); i++) {
            Circle c = layer.get(i);
            sliceArcs.add(c.slice > 0.5 ? visibleArcs(layer, i, true) : Collections.emptyList());
            fullArcs.add(fullRange && c.full - c.slice > 1 ? visibleArcs(layer, i, false) : Collections.emptyList());
        }
        // Each line over a dark one a bit wider, so it reads on any terrain.
        float lineWidth = minimap ? 1.5f : 2f;
        for (int pass = 0; pass < 2; pass++) {
            boolean backing = pass == 0;
            GL11.glLineWidth(backing ? lineWidth + 2 : lineWidth);
            for (int i = 0; i < layer.size(); i++) {
                Circle c = layer.get(i);
                int rgb = backing ? 0x000000 : c.rgb;
                arcs(tessellator, c.x, c.y, c.full, fullArcs.get(i), rgb, backing ? 60 : 130);
                arcs(tessellator, c.x, c.y, c.slice, sliceArcs.get(i), rgb, backing ? 120 : 235);
            }
        }
    }

    // ---------------------------------------------------------------- drawn over all the layers

    private static final List<Runnable> HIGHLIGHTS = new ArrayList<>(), MARKS = new ArrayList<>();

    /**
     * The circle under the mouse: its slice (and its full range ring) outlined in a lighter shade of its color. Drawn
     * by {@link #flush}, over
     * all the layers: a later layer's fill (a big red circle) would tint it otherwise.
     */
    static void highlight(double x, double y, double full, double slice, int rgb, boolean fullRange, boolean minimap) {
        HIGHLIGHTS.add(() -> drawHighlight(x, y, full, slice, lighter(rgb), fullRange, minimap));
    }

    /**
     * A circle's mark (see {@link #drawMark(double, double, String[], int, boolean, boolean, boolean)}), drawn by
     * {@link #flush}.
     */
    static void mark(double cx, double cy, String[] icon, int rgb, boolean broken, boolean hovered, boolean minimap) {
        MARKS.add(() -> drawMark(cx, cy, icon, rgb, broken, hovered, minimap));
    }

    /** After all the coverage layers of the map or the minimap: their highlight, then their marks, on top. */
    public static void flush() {
        try {
            for (Runnable r : HIGHLIGHTS) {
                r.run();
            }
            for (Runnable r : MARKS) {
                r.run();
            }
        } finally {
            HIGHLIGHTS.clear();
            MARKS.clear();
        }
    }

    private static void drawHighlight(double x, double y, double full, double slice, int light, boolean fullRange,
        boolean minimap) {
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        List<double[]> whole = Collections.singletonList(new double[] { 0, Math.PI * 2 });
        float width = minimap ? 2f : 2.5f;
        for (int pass = 0; pass < 2; pass++) {
            boolean backing = pass == 0;
            GL11.glLineWidth(backing ? width + 2 : width);
            int rgb = backing ? 0x000000 : light;
            if (fullRange && full - slice > 1) {
                arcs(tessellator, x, y, full, whole, rgb, backing ? 90 : 160);
            }
            if (slice > 0.5) {
                arcs(tessellator, x, y, slice, whole, rgb, backing ? 140 : 255);
            }
        }
        GL11.glLineWidth(1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** The color halfway to white. */
    static int lighter(int rgb) {
        int r = (rgb >> 16 & 0xFF) + 0xFF >> 1, g = (rgb >> 8 & 0xFF) + 0xFF >> 1, b = (rgb & 0xFF) + 0xFF >> 1;
        return r << 16 | g << 8 | b;
    }

    // ---------------------------------------------------------------- marks

    /**
     * The mark in the middle of a circle: the layer's pixel icon on a dark plate framed in the icon's color (white
     * when hovered), with a red warning triangle at the corner when the machine doesn't work. Returns the half size,
     * for telling whether the mouse is over it.
     */
    private static double drawMark(double cx, double cy, String[] icon, int rgb, boolean broken, boolean hovered,
        boolean minimap) {
        double pixel = minimap ? 0.75 : 1;
        int columns = 0;
        for (String row : icon) {
            columns = Math.max(columns, row.length());
        }
        double w = columns * pixel, h = icon.length * pixel;
        double half = Math.max(w, h) / 2 + 1.5;
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        tessellator.startDrawingQuads();
        quad(
            tessellator,
            cx - half - 1,
            cy - half - 1,
            cx + half + 1,
            cy + half + 1,
            hovered ? lighter(rgb) : rgb,
            230);
        quad(tessellator, cx - half, cy - half, cx + half, cy + half, 0x101418, 220);
        double left = cx - w / 2, top = cy - h / 2;
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    quad(
                        tessellator,
                        left + start * pixel,
                        top + row * pixel,
                        left + col * pixel,
                        top + (row + 1) * pixel,
                        rgb,
                        255);
                    start = -1;
                }
            }
        }
        tessellator.draw();
        if (broken) {
            warning(tessellator, cx + half, cy - half, minimap ? 3.5 : 4.5);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return half + 1;
    }

    /** A red warning triangle with a "!" centered on (cx, cy), {@code r} from the middle to a corner. */
    private static void warning(Tessellator tessellator, double cx, double cy, double r) {
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        triangle(tessellator, cx, cy, r + 1, 0x000000, 230);
        triangle(tessellator, cx, cy, r, 0xFF3030, 255);
        tessellator.draw();
        double s = r / 4.5;
        tessellator.startDrawingQuads();
        quad(tessellator, cx - 0.5 * s, cy - 2 * s, cx + 0.5 * s, cy + 0.8 * s, 0xFFFFFF, 255);
        quad(tessellator, cx - 0.5 * s, cy + 1.6 * s, cx + 0.5 * s, cy + 2.6 * s, 0xFFFFFF, 255);
        tessellator.draw();
    }

    private static void triangle(Tessellator tessellator, double cx, double cy, double r, int rgb, int alpha) {
        tessellator.setColorRGBA_I(rgb, alpha);
        tessellator.addVertex(cx, cy - r, 0);
        tessellator.addVertex(cx - r, cy + r * 0.75, 0);
        tessellator.addVertex(cx + r, cy + r * 0.75, 0);
    }

    private static void quad(Tessellator tessellator, double x0, double y0, double x1, double y1, int rgb, int alpha) {
        tessellator.setColorRGBA_I(rgb, alpha);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    // ---------------------------------------------------------------- fills

    private static void fills(Tessellator tessellator, List<Circle> ordered) {
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);

        resetDepth(tessellator, ordered);
        // Every fill at the nearest depth: the first one there passes "less than far", the next ones don't.
        GL11.glDepthRange(0, 0);
        GL11.glDepthFunc(GL11.GL_LESS);
        for (Circle c : ordered) {
            if (c.slice > 0.5) {
                fan(tessellator, c.x, c.y, c.slice, c.rgb, c.fillAlpha);
            }
        }
        resetDepth(tessellator, ordered);

        GL11.glDepthRange(0, 1);
        GL11.glDepthFunc(depthFunc);
        GL11.glDepthMask(depthMask);
        if (!depthTest) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    /** Sets the depth under the fills to the far value, drawing nothing. */
    private static void resetDepth(Tessellator tessellator, List<Circle> ordered) {
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthRange(1, 1);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        for (Circle c : ordered) {
            if (c.slice > 0.5) {
                fan(tessellator, c.x, c.y, c.slice, 0, 255);
            }
        }
        GL11.glColorMask(true, true, true, true);
    }

    private static void fan(Tessellator tessellator, double cx, double cy, double r, int rgb, int alpha) {
        int n = segments(r, Math.PI * 2);
        tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
        tessellator.setColorRGBA_I(rgb, alpha);
        tessellator.addVertex(cx, cy, 0);
        for (int i = n; i >= 0; i--) {
            double a = Math.PI * 2 * i / n;
            tessellator.addVertex(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 0);
        }
        tessellator.draw();
    }

    // ---------------------------------------------------------------- outlines

    /**
     * The arcs of circle i of the layer (its slice or its full range) not inside another circle of the layer, as
     * {from, to} angles. Of two equal circles only the first keeps its outline, or both would lose it.
     */
    private static List<double[]> visibleArcs(List<Circle> layer, int i, boolean slice) {
        Circle c = layer.get(i);
        double r = slice ? c.slice : c.full;
        List<double[]> hidden = new ArrayList<>();
        for (int j = 0; j < layer.size(); j++) {
            Circle o = layer.get(j);
            if (j == i) {
                continue;
            }
            double ro = slice ? o.slice : o.full;
            if (ro <= 0.5) {
                continue;
            }
            double dx = o.x - c.x, dy = o.y - c.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-6 && Math.abs(r - ro) < 1e-6) {
                if (j < i) {
                    return Collections.emptyList();
                }
                continue;
            }
            if (d >= r + ro || d + ro <= r) {
                // Apart, or the other one is inside this one: no part of this outline is covered.
                continue;
            }
            if (d + r <= ro) {
                return Collections.emptyList();
            }
            double middle = Math.atan2(dy, dx);
            double half = Math.acos(Math.max(-1, Math.min(1, (r * r + d * d - ro * ro) / (2 * r * d))));
            addInterval(hidden, middle - half, 2 * half);
        }
        return complement(hidden);
    }

    /** Adds the arc from {@code from} over {@code span} radians, moved into [0, 2π) and split where it wraps. */
    private static void addInterval(List<double[]> intervals, double from, double span) {
        double turn = Math.PI * 2;
        from = (from % turn + turn) % turn;
        double to = from + Math.min(turn, span);
        if (to > turn) {
            intervals.add(new double[] { from, turn });
            intervals.add(new double[] { 0, to - turn });
        } else {
            intervals.add(new double[] { from, to });
        }
    }

    /** The parts of the circle not in the intervals. */
    private static List<double[]> complement(List<double[]> hidden) {
        double turn = Math.PI * 2;
        List<double[]> visible = new ArrayList<>();
        if (hidden.isEmpty()) {
            visible.add(new double[] { 0, turn });
            return visible;
        }
        hidden.sort((a, b) -> Double.compare(a[0], b[0]));
        double at = 0;
        for (double[] interval : hidden) {
            if (interval[0] > at) {
                visible.add(new double[] { at, interval[0] });
            }
            at = Math.max(at, interval[1]);
        }
        if (at < turn) {
            visible.add(new double[] { at, turn });
        }
        return visible;
    }

    private static void arcs(Tessellator tessellator, double cx, double cy, double r, List<double[]> arcs, int rgb,
        int alpha) {
        for (double[] arc : arcs) {
            double length = arc[1] - arc[0];
            if (length * r < 0.5) {
                continue;
            }
            int n = segments(r, length);
            tessellator.startDrawing(GL11.GL_LINE_STRIP);
            tessellator.setColorRGBA_I(rgb, alpha);
            for (int i = 0; i <= n; i++) {
                double a = arc[0] + length * i / n;
                tessellator.addVertex(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 0);
            }
            tessellator.draw();
        }
    }

    /** Segments for an arc of that radius and angle: about 3 pixels each, at least a few. */
    private static int segments(double radius, double angle) {
        int full = Math.max(24, Math.min(180, (int) (radius / 3)));
        return Math.max(2, (int) Math.ceil(full * angle / (Math.PI * 2)));
    }
}
