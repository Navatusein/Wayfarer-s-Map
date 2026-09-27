package WayFarMap.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import WayFarMap.WayFarMap;

/**
 * Flat 2D icons of mobs, cut out of their own skin: the front face of the head of the model the game renders them
 * with. Works for vanilla mobs and for most modded mobs that use a regular model.
 */
public final class EntityIcons {

    /** Front face of a head on the skin texture: normalized UVs and size in texture pixels. */
    private static final class Face {

        final float u0, v0, u1, v1;
        final float width, height;

        Face(float u0, float v0, float u1, float v1, float width, float height) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
        }
    }

    private static final Face NONE = new Face(0, 0, 0, 0, 0, 0);
    /** Renderers are shared by all entities of a type, so the face is looked up once per renderer. */
    private static final Map<Render, Face> FACES = new IdentityHashMap<>();

    private static Method textureMethod;
    private static Field mainModelField;
    private static Field quadListField;
    private static boolean reflectionFailed;

    private EntityIcons() {}

    /**
     * Draws the mob's face centered on the given point, fitted in a {@code size} square.
     *
     * @return false if the mob has no usable face, so the caller can draw something else
     */
    public static boolean drawFace(EntityLivingBase entity, double sx, double sy, float size) {
        Render render = RenderManager.instance.getEntityRenderObject(entity);
        if (!(render instanceof RendererLivingEntity) || !initReflection()) {
            return false;
        }
        Face face = FACES.get(render);
        if (face == null) {
            face = findFace((RendererLivingEntity) render);
            FACES.put(render, face);
        }
        if (face == NONE) {
            return false;
        }
        ResourceLocation texture;
        try {
            texture = (ResourceLocation) textureMethod.invoke(render, entity);
        } catch (Throwable t) {
            return false;
        }
        if (texture == null) {
            return false;
        }

        // Keep the face's aspect ratio inside the square.
        double halfW = size / 2.0, halfH = size / 2.0;
        if (face.width > face.height) {
            halfH *= face.height / face.width;
        } else if (face.height > face.width) {
            halfW *= face.width / face.height;
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(sx - halfW, sy + halfH, 0, face.u0, face.v1);
        tessellator.addVertexWithUV(sx + halfW, sy + halfH, 0, face.u1, face.v1);
        tessellator.addVertexWithUV(sx + halfW, sy - halfH, 0, face.u1, face.v0);
        tessellator.addVertexWithUV(sx - halfW, sy - halfH, 0, face.u0, face.v0);
        tessellator.draw();
        return true;
    }

    /** Looks up the private/protected members by type, so it works with both dev and obfuscated names. */
    private static boolean initReflection() {
        if (textureMethod != null) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            for (Method method : Render.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == ResourceLocation.class && params.length == 1
                    && params[0] == Entity.class) {
                    method.setAccessible(true);
                    textureMethod = method;
                    break;
                }
            }
            // mainModel is declared before renderPassModel.
            for (Field field : RendererLivingEntity.class.getDeclaredFields()) {
                if (field.getType() == ModelBase.class) {
                    field.setAccessible(true);
                    mainModelField = field;
                    break;
                }
            }
            for (Field field : ModelBox.class.getDeclaredFields()) {
                if (field.getType() == TexturedQuad[].class) {
                    field.setAccessible(true);
                    quadListField = field;
                    break;
                }
            }
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Mob icons are unavailable", t);
        }
        if (textureMethod == null || mainModelField == null || quadListField == null) {
            reflectionFailed = true;
            textureMethod = null;
            return false;
        }
        return true;
    }

    private static Face findFace(RendererLivingEntity render) {
        try {
            ModelBase model = (ModelBase) mainModelField.get(render);
            ModelRenderer head = findHead(model);
            if (head == null) {
                return NONE;
            }
            ModelBox box = (ModelBox) head.cubeList.get(0);
            TexturedQuad[] quads = (TexturedQuad[]) quadListField.get(box);
            // ModelBox creates its faces in a fixed order; index 4 is the front (-Z), where mobs have their face.
            TexturedQuad front = quads[4];
            float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
            for (PositionTextureVertex vertex : front.vertexPositions) {
                u0 = Math.min(u0, vertex.texturePositionX);
                u1 = Math.max(u1, vertex.texturePositionX);
                v0 = Math.min(v0, vertex.texturePositionY);
                v1 = Math.max(v1, vertex.texturePositionY);
            }
            float width = (u1 - u0) * model.textureWidth;
            float height = (v1 - v0) * model.textureHeight;
            if (width <= 0 || height <= 0) {
                return NONE;
            }
            return new Face(u0, v0, u1, v1, width, height);
        } catch (Throwable t) {
            return NONE;
        }
    }

    /**
     * The head of bipeds is known; for other models the first part is used, which is the head for most of them
     * (quadrupeds, creepers, spiders, chickens, wolves, villagers, golems, slimes, ghasts...).
     */
    private static ModelRenderer findHead(ModelBase model) {
        if (model instanceof ModelBiped) {
            return usable(((ModelBiped) model).bipedHead);
        }
        if (model == null || model.boxList == null) {
            return null;
        }
        for (Object part : model.boxList) {
            ModelRenderer renderer = usable((ModelRenderer) part);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
    }

    private static ModelRenderer usable(ModelRenderer renderer) {
        return renderer != null && renderer.cubeList != null
            && !renderer.cubeList.isEmpty()
            && renderer.cubeList.get(0) instanceof ModelBox ? renderer : null;
    }
}
