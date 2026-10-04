package WayFarMap.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.google.gson.GsonBuilder;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.registry.EntityRegistry;

/**
 * {@code /wfmobicons}: material to make the map's mob icons from, for every mob the game knows (vanilla and mods).
 * Per mob: the icon the map draws now, the mob drawn by the game itself from the front, from three quarters and its
 * head up close, its skin, and its model (each part's boxes, where they are and their texture squares) in
 * {@code mobs.json}; all mobs side by side in {@code sheet-N.png}. Everything is packed in one zip to send. Client
 * side only; the mobs are made but never put in the world.
 */
public final class MobIconDump extends CommandBase {

    public static final String COMMAND = "wfmobicons";

    /** Pixels per side of each picture. */
    private static final int SIZE = 128;
    /** Mobs per contact sheet, and the label's height under each row of pictures. */
    private static final int SHEET_ROWS = 40, LABEL = 14;
    private static final String[] SHOTS = { "current", "front", "threeQuarter", "head" };
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    private static Method textureMethod;
    private static Field mainModelField, renderPassModelField, quadListField;

    @Override
    public String getCommandName() {
        return COMMAND;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + COMMAND;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        if (!OpenGlHelper.isFramebufferEnabled()) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + I18n.format("wayfarmap.mobicons.noFramebuffer")));
            return;
        }
        try {
            Dump dump = new Dump(mc);
            dump.run();
            ChatComponentText message = new ChatComponentText(
                EnumChatFormatting.GREEN
                    + I18n.format("wayfarmap.mobicons.done", dump.mobs, dump.failed, dump.zip.getName()));
            // Click: the folder with the zip.
            message.setChatStyle(
                new ChatStyle().setChatClickEvent(
                    new ClickEvent(
                        ClickEvent.Action.OPEN_FILE,
                        dump.zip.getParentFile()
                            .getAbsolutePath())));
            sender.addChatMessage(message);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not dump the mob icons", t);
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + I18n.format("wayfarmap.mobicons.failed", t)));
        }
    }

    /** One mob's pictures (null where one couldn't be made) and what is known of it. */
    private static final class Mob {

        final String key;
        final BufferedImage[] shots = new BufferedImage[SHOTS.length];
        final Map<String, Object> info = new LinkedHashMap<>();

        Mob(String key) {
            this.key = key;
        }
    }

    /** One run of the command. */
    private static final class Dump {

        final Minecraft mc;
        final File folder, zip;
        final TextureManager textures;
        final List<Mob> done = new ArrayList<>();
        Framebuffer framebuffer;
        IntBuffer readBuffer;
        int mobs, failed;

        Dump(Minecraft mc) {
            this.mc = mc;
            this.textures = mc.getTextureManager();
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            File dumps = new File(new File(mc.mcDataDir, "wayfarmap"), "dumps");
            folder = new File(dumps, "mob-icons-" + date);
            zip = new File(dumps, "mob-icons-" + date + ".zip");
        }

        void run() throws Exception {
            if (!folder.mkdirs() && !folder.isDirectory()) {
                throw new IOException("Could not make " + folder);
            }
            findReflection();
            Map<String, Class<?>> kinds = kinds();
            try {
                framebuffer = new Framebuffer(SIZE, SIZE, true);
                readBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
                for (Map.Entry<String, Class<?>> kind : kinds.entrySet()) {
                    mobs++;
                    Mob mob = dumpMob(kind.getKey(), kind.getValue());
                    done.add(mob);
                    for (int shot = 0; shot < SHOTS.length; shot++) {
                        if (mob.shots[shot] != null) {
                            File file = new File(new File(folder, SHOTS[shot]), safe(mob.key) + ".png");
                            file.getParentFile()
                                .mkdirs();
                            ImageIO.write(mob.shots[shot], "png", file);
                        }
                    }
                }
            } finally {
                if (framebuffer != null) {
                    framebuffer.deleteFramebuffer();
                }
                Framebuffer game = mc.getFramebuffer();
                if (game != null) {
                    game.bindFramebuffer(true);
                }
            }
            List<Map<String, Object>> infos = new ArrayList<>();
            for (Mob mob : done) {
                infos.add(mob.info);
            }
            try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(folder, "mobs.json")),
                StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting()
                    .serializeSpecialFloatingPointValues()
                    .create()
                    .toJson(infos, writer);
            }
            writeSheets();
            zipFolder();
        }

        /** Living mobs by "mod/name": the named ones, then those with a renderer but no name. Not players. */
        private Map<String, Class<?>> kinds() {
            Map<String, Class<?>> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) EntityList.classToStringMapping).entrySet()) {
                if (entry.getKey() instanceof Class && entry.getValue() instanceof String
                    && isMob((Class<?>) entry.getKey())) {
                    Class<?> kind = (Class<?>) entry.getKey();
                    kinds.put(modOf(kind, (String) entry.getValue()) + "/" + entry.getValue(), kind);
                }
            }
            for (Object key : ((Map<?, ?>) RenderManager.instance.entityRenderMap).keySet()) {
                if (key instanceof Class && isMob((Class<?>) key) && !kinds.containsValue(key)) {
                    Class<?> kind = (Class<?>) key;
                    kinds.put(modOf(kind, null) + "/" + kind.getSimpleName(), kind);
                }
            }
            return kinds;
        }

        private Mob dumpMob(String key, Class<?> kind) {
            Mob mob = new Mob(key);
            Map<String, Object> info = mob.info;
            info.put("key", key);
            info.put("class", kind.getName());
            EntityLivingBase entity;
            try {
                entity = (EntityLivingBase) create(kind);
            } catch (Throwable t) {
                info.put("error", "could not be made: " + t);
                failed++;
                return mob;
            }
            // Facing the camera (south), standing still.
            entity.rotationYaw = entity.prevRotationYaw = 0f;
            entity.renderYawOffset = entity.prevRenderYawOffset = 0f;
            entity.rotationYawHead = entity.prevRotationYawHead = 0f;
            entity.rotationPitch = entity.prevRotationPitch = 0f;
            info.put("width", entity.width);
            info.put("height", entity.height);
            Render render = RenderManager.instance.getEntityClassRenderObject(kind);
            info.put(
                "renderer",
                render == null ? null
                    : render.getClass()
                        .getName());
            if (render == null) {
                failed++;
                return mob;
            }
            ResourceLocation texture = texture(render, entity);
            info.put("texture", texture == null ? null : texture.toString());
            if (texture != null) {
                writeSkin(texture, mob);
            }
            float[] head = null;
            if (render instanceof RendererLivingEntity) {
                ModelBase main = model(mainModelField, render), pass = model(renderPassModelField, render);
                info.put(
                    "model",
                    main == null ? null
                        : main.getClass()
                            .getName());
                info.put("modelParts", main == null ? null : describe(main));
                if (pass != null) {
                    info.put(
                        "renderPassModel",
                        pass.getClass()
                            .getName());
                    info.put("renderPassParts", describe(pass));
                }
                head = headBox(main, entity);
                info.put("headGuess", head);
            }
            mob.shots[0] = shot(() -> EntityIcons.drawFace(entity, SIZE / 2.0, SIZE / 2.0, SIZE - 8), true);
            float fit = Math.max(entity.height, entity.width) * 0.6f + 0.1f;
            float centerY = entity.height / 2f;
            mob.shots[1] = shot(() -> drawMob(entity, 0f, centerY, fit, 0f, 0f), false);
            mob.shots[2] = shot(() -> drawMob(entity, 0f, centerY, fit * 1.1f, 20f, -35f), false);
            if (head != null) {
                float[] box = head;
                mob.shots[3] = shot(() -> drawMob(entity, box[0], box[1], box[3], 0f, 0f), false);
            }
            for (int shot = 0; shot < SHOTS.length; shot++) {
                info.put(SHOTS[shot], mob.shots[shot] == null ? "none" : "ok");
            }
            if (mob.shots[1] == null) {
                failed++;
            }
            return mob;
        }

        /** Something drawn into the buffer and read back; null if nothing was drawn or it failed. */
        private BufferedImage shot(Drawing drawing, boolean screen) {
            int previous = GL11.glGetInteger(FRAMEBUFFER_BINDING);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                framebuffer.bindFramebuffer(true);
                GL11.glClearColor(0f, 0f, 0f, 0f);
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                if (screen) {
                    // As on the map: screen pixels, y down.
                    GL11.glOrtho(0, SIZE, SIZE, 0, -100, 100);
                }
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                // No light map: fully lit, as in the inventory.
                OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                OpenGlHelper.glBlendFunc(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                if (!drawing.draw()) {
                    return null;
                }
                readBuffer.clear();
                GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
                int[] all = new int[SIZE * SIZE];
                readBuffer.get(all);
                BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                boolean drawn = false;
                for (int row = 0; row < SIZE; row++) {
                    // Read back bottom-up.
                    image.setRGB(0, row, SIZE, 1, all, (SIZE - 1 - row) * SIZE, SIZE);
                    for (int x = 0; x < SIZE && !drawn; x++) {
                        drawn = all[row * SIZE + x] >>> 24 != 0;
                    }
                }
                return drawn ? image : null;
            } catch (Throwable t) {
                return null;
            } finally {
                RenderHelper.disableStandardItemLighting();
                Framebuffer game = mc.getFramebuffer();
                if (game != null && previous != 0 && previous == game.framebufferObject) {
                    game.bindFramebuffer(false);
                } else {
                    framebuffer.unbindFramebuffer();
                }
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        /**
         * The mob drawn by its own renderer, lit as in the inventory, seen straight (no perspective): {@code half}
         * blocks around ({@code x}, {@code y}) of it, turned by {@code pitch} and {@code yaw} degrees.
         */
        private boolean drawMob(EntityLivingBase entity, float x, float y, float half, float pitch, float yaw) {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glOrtho(x - half, x + half, y - half, y + half, -50, 50);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glTranslatef(0f, y, 0f);
            GL11.glRotatef(pitch, 1f, 0f, 0f);
            GL11.glRotatef(yaw, 0f, 1f, 0f);
            GL11.glTranslatef(0f, -y, 0f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            RenderHelper.enableStandardItemLighting();
            RenderManager manager = RenderManager.instance;
            float viewY = manager.playerViewY;
            manager.playerViewY = 180f;
            try {
                manager.renderEntityWithPosYaw(entity, 0, 0, 0, 0f, 1f);
            } finally {
                manager.playerViewY = viewY;
            }
            return true;
        }

        /**
         * Where the head is, in blocks from the mob's feet when facing the camera: {x, y, z, half its size}, from the
         * model's head box (the biped head, else the first part). Null if not found. A renderer scaling its model
         * (a ghast) puts it elsewhere: kept within the mob.
         */
        private float[] headBox(ModelBase model, EntityLivingBase entity) {
            ModelRenderer head = EntityIconsAccess.head(model);
            if (head == null) {
                return null;
            }
            ModelBox box = (ModelBox) head.cubeList.get(0);
            // Model units are 1/16 block, y down from 1.5 blocks up; the mob is turned to face the camera.
            float cx = head.rotationPointX + (box.posX1 + box.posX2) / 2;
            float cy = head.rotationPointY + (box.posY1 + box.posY2) / 2;
            float cz = head.rotationPointZ + (box.posZ1 + box.posZ2) / 2;
            float size = Math.max(box.posX2 - box.posX1, box.posY2 - box.posY1) / 16f;
            float y = 1.5078125f - cy / 16f;
            if (y < 0 || y > entity.height * 1.2f + 0.5f) {
                y = entity.height - size / 2;
            }
            return new float[] { cx / 16f, y, -cz / 16f, Math.max(0.2f, size * 0.75f) };
        }

        /** Every part of a model: its name, place, turn and boxes with the texture square of each box's front. */
        private List<Map<String, Object>> describe(ModelBase model) {
            List<Map<String, Object>> parts = new ArrayList<>();
            if (model.boxList == null) {
                return parts;
            }
            for (Object o : model.boxList) {
                if (!(o instanceof ModelRenderer)) {
                    continue;
                }
                ModelRenderer part = (ModelRenderer) o;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", part.boxName);
                p.put("rotationPoint", new float[] { part.rotationPointX, part.rotationPointY, part.rotationPointZ });
                p.put("rotateAngle", new float[] { part.rotateAngleX, part.rotateAngleY, part.rotateAngleZ });
                p.put("hidden", part.isHidden || !part.showModel);
                p.put("texture", new float[] { part.textureWidth, part.textureHeight });
                p.put("children", part.childModels == null ? 0 : part.childModels.size());
                List<Map<String, Object>> boxes = new ArrayList<>();
                if (part.cubeList != null) {
                    for (Object c : part.cubeList) {
                        if (!(c instanceof ModelBox)) {
                            continue;
                        }
                        ModelBox box = (ModelBox) c;
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("from", new float[] { box.posX1, box.posY1, box.posZ1 });
                        b.put("to", new float[] { box.posX2, box.posY2, box.posZ2 });
                        b.put("frontUv", frontUv(box, model));
                        boxes.add(b);
                    }
                }
                p.put("boxes", boxes);
                parts.add(p);
            }
            return parts;
        }

        /** {u0, v0, u1, v1} in texture pixels of the front (-Z) of a box. */
        private float[] frontUv(ModelBox box, ModelBase model) {
            try {
                TexturedQuad[] quads = (TexturedQuad[]) quadListField.get(box);
                float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
                for (PositionTextureVertex vertex : quads[4].vertexPositions) {
                    u0 = Math.min(u0, vertex.texturePositionX);
                    u1 = Math.max(u1, vertex.texturePositionX);
                    v0 = Math.min(v0, vertex.texturePositionY);
                    v1 = Math.max(v1, vertex.texturePositionY);
                }
                return new float[] { u0 * model.textureWidth, v0 * model.textureHeight, u1 * model.textureWidth,
                    v1 * model.textureHeight };
            } catch (Throwable t) {
                return null;
            }
        }

        /** The skin: the file from the resource packs, else read back from the graphics card (horses). */
        private void writeSkin(ResourceLocation texture, Mob mob) {
            File file = new File(new File(folder, "skin"), safe(mob.key) + ".png");
            file.getParentFile()
                .mkdirs();
            try (InputStream in = mc.getResourceManager()
                .getResource(texture)
                .getInputStream()) {
                Files.copy(in, file.toPath());
                mob.info.put("skin", "file");
                return;
            } catch (Throwable notAFile) {
                // Made by the game: read it back.
            }
            try {
                textures.bindTexture(texture);
                ITextureObject object = textures.getTexture(texture);
                if (object == null || object == TextureUtil.missingTexture) {
                    mob.info.put("skin", "missing");
                    return;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, object.getGlTextureId());
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width <= 0 || height <= 0 || width * height > 4096 * 4096) {
                    mob.info.put("skin", "unreadable");
                    return;
                }
                IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                int[] argb = new int[width * height];
                pixels.get(argb);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, width, height, argb, 0, width);
                ImageIO.write(image, "png", file);
                mob.info.put("skin", "gpu");
            } catch (Throwable t) {
                mob.info.put("skin", "unreadable: " + t);
            }
        }

        /** All mobs side by side, {@link #SHEET_ROWS} per sheet: the four pictures on a checkered ground, named. */
        private void writeSheets() throws IOException {
            int cell = SIZE / 2, width = cell * SHOTS.length + 260, row = cell + LABEL / 2;
            for (int first = 0, sheet = 1; first < done.size(); first += SHEET_ROWS, sheet++) {
                int count = Math.min(SHEET_ROWS, done.size() - first);
                BufferedImage image = new BufferedImage(width, count * row + LABEL, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(0x30, 0x30, 0x30));
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(Color.WHITE);
                for (int s = 0; s < SHOTS.length; s++) {
                    g.drawString(SHOTS[s], s * cell + 2, LABEL - 3);
                }
                for (int n = 0; n < count; n++) {
                    Mob mob = done.get(first + n);
                    int top = LABEL + n * row;
                    for (int s = 0; s < SHOTS.length; s++) {
                        checkers(g, s * cell, top, cell);
                        if (mob.shots[s] != null) {
                            g.drawImage(mob.shots[s], s * cell, top, cell, cell, null);
                        }
                    }
                    g.setColor(Color.WHITE);
                    g.drawString(first + n + 1 + ". " + mob.key, SHOTS.length * cell + 6, top + cell / 2);
                }
                g.dispose();
                ImageIO.write(image, "png", new File(folder, "sheet-" + sheet + ".png"));
            }
        }

        private void checkers(Graphics2D g, int x, int y, int size) {
            int square = size / 8;
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    g.setColor((i + j) % 2 == 0 ? new Color(0x55, 0x55, 0x55) : new Color(0x48, 0x48, 0x48));
                    g.fillRect(x + i * square, y + j * square, square, square);
                }
            }
        }

        private void zipFolder() throws IOException {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                addToZip(out, folder, folder.getName());
            }
        }

        private void addToZip(ZipOutputStream out, File file, String name) throws IOException {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(out, child, name + "/" + child.getName());
                }
                return;
            }
            out.putNextEntry(new ZipEntry(name));
            Files.copy(file.toPath(), out);
            out.closeEntry();
        }
    }

    /** Something to draw into the buffer: false if there was nothing to draw. */
    private interface Drawing {

        boolean draw() throws Exception;
    }

    /** Finds the model's head the way the map's icons do. */
    private static final class EntityIconsAccess {

        static ModelRenderer head(ModelBase model) {
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

    private static boolean isMob(Class<?> kind) {
        return EntityLivingBase.class.isAssignableFrom(kind) && !EntityPlayer.class.isAssignableFrom(kind)
            && !Modifier.isAbstract(kind.getModifiers());
    }

    /** The mod that added the mob: from Forge's list, else the "mod.Name" prefix of its name, else the game. */
    @SuppressWarnings("unchecked")
    private static String modOf(Class<?> kind, String name) {
        try {
            EntityRegistry.EntityRegistration registration = EntityRegistry.instance()
                .lookupModSpawn((Class<? extends Entity>) kind, false);
            if (registration != null) {
                return registration.getContainer()
                    .getModId();
            }
        } catch (Throwable ignored) {
            // Not registered through Forge.
        }
        if (name != null && name.indexOf('.') > 0) {
            return name.substring(0, name.indexOf('.'));
        }
        return kind.getName()
            .startsWith("net.minecraft.") ? "minecraft" : "unknown";
    }

    /** A mob made for its pictures only (not put in the world). */
    private static Entity create(Class<?> kind) throws ReflectiveOperationException {
        Constructor<?> constructor = kind.getDeclaredConstructor(World.class);
        constructor.setAccessible(true);
        return (Entity) constructor.newInstance(Minecraft.getMinecraft().theWorld);
    }

    /** The members needed, found by their types so it works with any names. */
    private static void findReflection() throws NoSuchMethodException {
        if (textureMethod != null) {
            return;
        }
        for (Method method : Render.class.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() == ResourceLocation.class && params.length == 1 && params[0] == Entity.class) {
                method.setAccessible(true);
                textureMethod = method;
                break;
            }
        }
        // mainModel is declared before renderPassModel.
        for (Field field : RendererLivingEntity.class.getDeclaredFields()) {
            if (field.getType() == ModelBase.class) {
                field.setAccessible(true);
                if (mainModelField == null) {
                    mainModelField = field;
                } else {
                    renderPassModelField = field;
                    break;
                }
            }
        }
        for (Field field : ModelBox.class.getDeclaredFields()) {
            if (field.getType() == TexturedQuad[].class) {
                field.setAccessible(true);
                quadListField = field;
                break;
            }
        }
        if (textureMethod == null) {
            throw new NoSuchMethodException("Render.getEntityTexture");
        }
    }

    private static ModelBase model(Field field, Render render) {
        try {
            return field == null ? null : (ModelBase) field.get(render);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ResourceLocation texture(Render render, Entity entity) {
        try {
            return (ResourceLocation) textureMethod.invoke(render, entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A file name made of safe letters only. */
    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
