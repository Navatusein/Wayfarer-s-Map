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
final class CoverageDrawer {

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
