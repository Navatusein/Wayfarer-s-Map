package WayFarMap.client.waypoint;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.google.gson.Gson;

import WayFarMap.WayFarMap;

/**
 * Icons a waypoint can show instead of an item: the Phosphor icons (phosphoricons.com, MIT license, in
 * {@code LICENSE-phosphor.txt}), each a white picture in one atlas, tinted when drawn. Listed with words to find them
 * by in {@code symbols.json}. Render thread only.
 */
public final class Symbols {

    private static final ResourceLocation ATLAS = new ResourceLocation("wayfarmap", "textures/gui/symbols.png");
    private static final ResourceLocation INDEX = new ResourceLocation("wayfarmap", "symbols.json");
    /**
     * Smaller copies of the atlas used when an icon is drawn small (else it flickers), down to an eighth: smaller
     * ones would mix neighbouring icons.
     */
    private static final int MIPMAP_LEVELS = 3;

    /** How {@code symbols.json} is laid out. */
    private static final class Index {

        int cell, columns, width, height;
        List<List<String>> icons;
    }

    private static List<String> names;
    private static List<String> tags;
    private static Map<String, Integer> positions;
    private static int cell = 64, columns = 64, width = 4096, height = 1536;
    /** The atlas texture its smaller copies were made for (made again if the resources are reloaded). */
    private static int prepared = -1;

    private Symbols() {}

    private static void load() {
        if (names != null) {
            return;
        }
        names = new ArrayList<>();
        tags = new ArrayList<>();
        positions = new HashMap<>();
        try (Reader reader = new InputStreamReader(
            Minecraft.getMinecraft()
                .getResourceManager()
                .getResource(INDEX)
                .getInputStream(),
            StandardCharsets.UTF_8)) {
            Index index = new Gson().fromJson(reader, Index.class);
            cell = index.cell;
            columns = index.columns;
            width = index.width;
            height = index.height;
            for (List<String> icon : index.icons) {
                positions.put(icon.get(0), names.size());
                names.add(icon.get(0));
                tags.add(icon.size() > 1 ? icon.get(1) : "");
            }
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not read the waypoint icons", e);
        }
    }

    /** The names of all icons, in the order to show them. */
    public static List<String> names() {
        load();
        return Collections.unmodifiableList(names);
    }

    /** Words the icon can be found by (besides its name). */
    public static String tags(String name) {
        load();
        Integer position = positions.get(name);
        return position == null ? "" : tags.get(position);
    }

    public static boolean exists(String name) {
        load();
        return name != null && positions.containsKey(name);
    }

    /** The name to show: "air-traffic-control" as "Air traffic control". */
    public static String title(String name) {
        String words = name.replace('-', ' ');
        return words.isEmpty() ? words
            : words.substring(0, 1)
                .toUpperCase(Locale.ROOT) + words.substring(1);
    }

    /**
     * Draws the icon as a square of {@code size} centered on (cx, cy) in the current coordinates (a screen or a
     * billboard in the world), in the color (ARGB).
     *
     * @return false if there is no such icon
     */
    public static boolean draw(String name, double cx, double cy, double size, int color) {
        load();
        Integer position = name == null ? null : positions.get(name);
        if (position == null) {
            return false;
        }
        bindAtlas();
        double u0 = (position % columns) * cell / (double) width, v0 = (position / columns) * cell / (double) height;
        double u1 = u0 + cell / (double) width, v1 = v0 + cell / (double) height;
        double half = size / 2;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(
            (color >> 16 & 0xFF) / 255f,
            (color >> 8 & 0xFF) / 255f,
            (color & 0xFF) / 255f,
            (color >>> 24) / 255f);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(cx - half, cy + half, 0, u0, v1);
        tessellator.addVertexWithUV(cx + half, cy + half, 0, u1, v1);
        tessellator.addVertexWithUV(cx + half, cy - half, 0, u1, v0);
        tessellator.addVertexWithUV(cx - half, cy - half, 0, u0, v0);
        tessellator.draw();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return true;
    }

    /** Binds the atlas; the first time, makes its smaller copies and sets it to be drawn smooth. */
    private static void bindAtlas() {
        TextureManager textures = Minecraft.getMinecraft()
            .getTextureManager();
        textures.bindTexture(ATLAS);
        ITextureObject texture = textures.getTexture(ATLAS);
        int id = texture == null ? -1 : texture.getGlTextureId();
        if (id == prepared) {
            return;
        }
        prepared = id;
        boolean mipmaps = generateMipmaps(MIPMAP_LEVELS);
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D,
            GL11.GL_TEXTURE_MIN_FILTER,
            mipmaps ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    /**
     * Makes the smaller copies of the bound texture, up to {@code levels} of them. The game sets its textures to
     * have none (up to level 0), which is raised first. False if the graphics card can't.
     */
    static boolean generateMipmaps(int levels) {
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            if (!capabilities.OpenGL30 && !capabilities.GL_EXT_framebuffer_object) {
                return false;
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, levels);
            if (capabilities.OpenGL30) {
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            } else {
                EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
            }
            return true;
        } catch (Throwable t) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            return false;
        }
    }
}
