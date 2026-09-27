package WayFarMap.client.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.IIcon;

import org.lwjgl.opengl.GL11;

import WayFarMap.WayFarMap;

/**
 * GregTech power failures ("powerfails"): machines of the player's team that ran out of power, as GregTech's own
 * Navigator map layer shows them. GregTech keeps them on the client in
 * {@code GTMod.clientProxy().powerfailRenderer.powerfails} (see {@link #containers()}) and clears
 * one with a {@code GTPacketClearPowerfail} to the server. Everything is reached by reflection, so GregTech versions
 * without powerfails simply don't get the button.
 */
public final class PowerfailLayer {

    private static final int FRAME_COLOR = 0xFF3333;
    private static final int SEARCH_COLOR = 0xFFD34D;
    private static final int LABEL_BACKGROUND = 0xB4000000;
    private static final long REFRESH_MS = 250;

    private PowerfailLayer() {}

    // ---------------------------------------------------------------- GregTech access

    private static Boolean available;
    private static Method clientProxy;
    private static Field rendererField, powerfailsField, iconField;
    private static Field dimField, xField, yField, zField, countField, lastField;
    private static Method nameMethod, durationMethod;
    private static Constructor<?> clearPacket;
    private static Field networkField;
    private static Method sendToServer;

    /** Looks the GregTech classes up once; false if this GregTech has no powerfails. */
    public static boolean isAvailable() {
        if (available == null) {
            try {
                clientProxy = Class.forName("gregtech.GTMod")
                    .getMethod("clientProxy");
                rendererField = clientProxy.getReturnType()
                    .getField("powerfailRenderer");
                Class<?> renderer = Class.forName("gregtech.client.GTPowerfailRenderer");
                powerfailsField = renderer.getField("powerfails");
                iconField = renderer.getField("powerfailIcon");
                Class<?> powerfail = Class.forName("gregtech.common.data.GTPowerfailTracker$Powerfail");
                dimField = powerfail.getField("dim");
                xField = powerfail.getField("x");
                yField = powerfail.getField("y");
                zField = powerfail.getField("z");
                countField = powerfail.getField("count");
                lastField = powerfail.getField("lastOccurrence");
                nameMethod = powerfail.getMethod("getMTEName");
                durationMethod = optionalMethod(powerfail, "getDurationText");
                clearPacket = Class.forName("gregtech.api.net.GTPacketClearPowerfail")
                    .getConstructor(powerfail);
                networkField = Class.forName("gregtech.api.enums.GTValues")
                    .getField("NW");
                sendToServer = networkField.getType()
                    .getMethod("sendToServer", Class.forName("gregtech.api.net.GTPacket"));
                available = true;
                WayFarMap.LOG.info("GregTech power failures found, showing them on the map");
            } catch (Throwable t) {
                WayFarMap.LOG.info("GregTech power failures are not available: {}", t.toString());
                available = false;
            }
        }
        return available;
    }

    private static Method optionalMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object renderer() throws Exception {
        return rendererField.get(clientProxy.invoke(null));
    }

    /** One power failure, read from GregTech's object. */
    public static final class Powerfail {

        final Object source;
        public final int dimension, x, y, z, count;
        final Date last;
        final String name;
        final String duration;

        Powerfail(Object source) throws Exception {
            this.source = source;
            dimension = dimField.getInt(source);
            x = xField.getInt(source);
            y = yField.getInt(source);
            z = zField.getInt(source);
            count = countField.getInt(source);
            last = (Date) lastField.get(source);
            name = String.valueOf(nameMethod.invoke(source));
            duration = durationMethod != null ? String.valueOf(durationMethod.invoke(source)) : null;
        }

        /** "Machine ×3 · 5 minutes ago" style summary for the map label. */
        String summary() {
            String text = name + " ×" + count;
            return duration != null ? text + " · " + I18n.format("wayfarmap.powerfail.ago", duration) : text;
        }
    }

    private static List<Powerfail> cached = Collections.emptyList();
    private static int cachedDimension = Integer.MIN_VALUE;
    private static long lastRefresh;
    private static boolean warned;

    /** Powerfails of the dimension, re-read from GregTech a few times a second. */
    private static List<Powerfail> powerfails(int dimension) {
        long now = System.currentTimeMillis();
        if (dimension == cachedDimension && now - lastRefresh < REFRESH_MS) {
            return cached;
        }
        cachedDimension = dimension;
        lastRefresh = now;
        List<Powerfail> result = new ArrayList<>();
        try {
            for (Object source : allSources()) {
                Powerfail powerfail = new Powerfail(source);
                if (powerfail.dimension == dimension) {
                    result.add(powerfail);
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                WayFarMap.LOG.warn("Could not read GregTech power failures", t);
            }
        }
        cached = result;
        return result;
    }

    /**
     * The collections GregTech keeps powerfails in: newer versions have a map per dimension (dimension -> coordinate
     * -> powerfail), the first ones (5.09.51.413 on) a single map for all dimensions (coordinate -> powerfail).
     */
    private static List<Collection<?>> containers() throws Exception {
        Map<?, ?> top = (Map<?, ?>) powerfailsField.get(renderer());
        List<Collection<?>> containers = new ArrayList<>();
        boolean nested = false;
        for (Object value : top.values()) {
            if (value instanceof Map) {
                containers.add(((Map<?, ?>) value).values());
                nested = true;
            }
        }
        if (!nested) {
            containers.add(top.values());
        }
        return containers;
    }

    private static List<Object> allSources() throws Exception {
        List<Object> sources = new ArrayList<>();
        for (Collection<?> container : containers()) {
            sources.addAll(container);
        }
        return sources;
    }

    private static IIcon icon() {
        try {
            Object icon = iconField.get(renderer());
            return icon instanceof IIcon ? (IIcon) icon : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Asks the server to forget the power failure (like GregTech's map action key) and hides it right away. */
    public static void clear(Object handle) {
        Powerfail powerfail = (Powerfail) handle;
        try {
            sendToServer.invoke(networkField.get(null), clearPacket.newInstance(powerfail.source));
            for (Collection<?> container : containers()) {
                container.remove(powerfail.source);
            }
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not clear a GregTech power failure", t);
        }
        lastRefresh = 0;
    }

    // ---------------------------------------------------------------- search

    private static String[] searchTokens = {};

    /** Machine name search: powerfails of other machines are dimmed and get no label. */
    public static void setSearch(String text) {
        String q = text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
        searchTokens = q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    public static boolean isSearchActive() {
        return searchTokens.length > 0;
    }

    private static boolean matches(Powerfail powerfail) {
        String name = powerfail.name.toLowerCase(Locale.ROOT);
        for (String token : searchTokens) {
            if (!name.contains(token)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- drawing

    private static Powerfail hovered;

    /**
     * Draws the power failures of the dimension into the map rectangle, centered on (centerX, centerZ) at
     * {@code scale} pixels per block, and remembers the one under the mouse (world map only).
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap, int mouseX, int mouseY) {
        if (!minimap) {
            hovered = null;
        }
        List<Powerfail> all = powerfails(dimension);
        if (all.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double size = minimap ? 8 : Math.max(10, Math.min(16, 12 * Math.pow(scale, 0.3)));
        double half = size / 2;

        List<Powerfail> visible = new ArrayList<>();
        List<double[]> positions = new ArrayList<>();
        for (Powerfail powerfail : all) {
            double sx = x + (powerfail.x + 0.5 - left) * scale;
            double sy = y + (powerfail.z + 0.5 - top) * scale;
            if (sx + half < x || sy + half < y || sx - half > x + width || sy - half > y + height) {
                continue;
            }
            visible.add(powerfail);
            positions.add(new double[] { sx, sy });
            if (!minimap && Math.abs(mouseX - sx) <= half && Math.abs(mouseY - sy) <= half) {
                hovered = powerfail;
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        boolean searching = !minimap && isSearchActive();
        Tessellator tessellator = Tessellator.instance;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Dark backing with a red frame, so the warning sign reads on any terrain.
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        for (int i = 0; i < visible.size(); i++) {
            double[] p = positions.get(i);
            boolean dim = searching && !matches(visible.get(i));
            int frame = searching && !dim ? SEARCH_COLOR : FRAME_COLOR;
            quad(tessellator, p[0] - half - 1, p[1] - half - 1, p[0] + half + 1, p[1] + half + 1, frame, dim ? 90 : 255);
            quad(tessellator, p[0] - half, p[1] - half, p[0] + half, p[1] + half, 0x000000, dim ? 90 : 170);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        IIcon icon = icon();
        if (icon != null) {
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            tessellator.startDrawingQuads();
            for (int i = 0; i < visible.size(); i++) {
                double[] p = positions.get(i);
                boolean dim = searching && !matches(visible.get(i));
                tessellator.setColorRGBA_I(0xFFFFFF, dim ? 90 : 255);
                double s = half - 0.5;
                tessellator.addVertexWithUV(p[0] - s, p[1] + s, 0, icon.getMinU(), icon.getMaxV());
                tessellator.addVertexWithUV(p[0] + s, p[1] + s, 0, icon.getMaxU(), icon.getMaxV());
                tessellator.addVertexWithUV(p[0] + s, p[1] - s, 0, icon.getMaxU(), icon.getMinV());
                tessellator.addVertexWithUV(p[0] - s, p[1] - s, 0, icon.getMinU(), icon.getMinV());
            }
            tessellator.draw();
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);

        // Labels on the world map when zoomed in enough, like GregTech's layer.
        if (minimap || scale < 1) {
            return;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        List<double[]> placed = new ArrayList<>();
        for (int i = visible.size() - 1; i >= 0; i--) {
            Powerfail powerfail = visible.get(i);
            if (searching && !matches(powerfail)) {
                continue;
            }
            double[] p = positions.get(i);
            String text = ellipsize(font, powerfail.summary(), 160);
            int w = font.getStringWidth(text);
            double[] box = { p[0] - w / 2.0 - 2, p[1] + half + 2, p[0] + w / 2.0 + 2, p[1] + half + 12 };
            if (powerfail != hovered && overlaps(box, placed)) {
                continue;
            }
            placed.add(box);
            label(font, text, p[0], p[1] + half + 3);
        }
    }

    private static void quad(Tessellator tessellator, double x0, double y0, double x1, double y1, int rgb, int alpha) {
        tessellator.setColorRGBA_I(rgb, alpha);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    private static void label(FontRenderer font, String text, double cx, double ty) {
        int w = font.getStringWidth(text);
        int lx = (int) Math.round(cx - w / 2.0);
        int ly = (int) Math.round(ty);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        quad(tessellator, lx - 2, ly - 1, lx + w + 2, ly + 9, LABEL_BACKGROUND & 0xFFFFFF, LABEL_BACKGROUND >>> 24);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        font.drawString(text, lx, ly, 0xFFFFFF);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth("..."))) + "...";
    }

    private static boolean overlaps(double[] box, Collection<double[]> others) {
        for (double[] other : others) {
            if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- world map interaction

    /** The power failure under the mouse on the world map (for the right click menu), or null. */
    public static Object getHovered() {
        return hovered;
    }

    public static int[] position(Object handle) {
        Powerfail powerfail = (Powerfail) handle;
        return new int[] { powerfail.x, powerfail.y, powerfail.z, powerfail.dimension };
    }

    /** Tooltip of the power failure under the mouse, or null. */
    public static List<String> getHoveredTooltip() {
        if (hovered == null) {
            return null;
        }
        Powerfail p = hovered;
        List<String> lines = new ArrayList<>();
        lines.add("§c" + I18n.format("wayfarmap.powerfail.title") + ": §f" + p.name);
        lines.add("§7" + p.x + ", " + p.y + ", " + p.z);
        lines.add("§7" + I18n.format("wayfarmap.powerfail.count", p.count));
        if (p.last != null) {
            String when = DateFormat.getDateTimeInstance()
                .format(p.last);
            lines.add(
                "§7" + I18n.format("wayfarmap.powerfail.last", when)
                    + (p.duration != null ? " (" + I18n.format("wayfarmap.powerfail.ago", p.duration) + ")" : ""));
        }
        lines.add("§8" + I18n.format("wayfarmap.powerfail.hint"));
        return lines;
    }
}
