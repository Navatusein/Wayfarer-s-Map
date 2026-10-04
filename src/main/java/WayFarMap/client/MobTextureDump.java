package WayFarMap.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.passive.EntityVillager;
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

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.VillagerRegistry;

/**
 * {@code /wfdumpmobs}: writes the skins of every mob the game knows (vanilla and mods) to
 * {@code wayfarmap/dumps/mob-textures-<date>/<mod>/<mob>/}, with an {@code index.txt} of where each came from. Besides
 * a mob's own skin it finds its variants: the skins it gets with other values of its synced data (villager professions,
 * cat and horse kinds, wither skeletons...), the pictures its renderer keeps (eyes, saddles, overlays) and, for sheep,
 * the wool in all 16 dye colors. Skins put together by the game (a horse with its markings) are read back from the
 * graphics card. Client side only; the mobs are made but never put in the world.
 */
public final class MobTextureDump extends CommandBase {

    public static final String COMMAND = "wfdumpmobs";

    /** Bytes tried in each synced data slot: small kinds and single flags (a tamed wolf, a charged creeper). */
    private static final byte[] BYTE_TRIES = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 32, 64,
        -128 };
    /** Most kinds mods keep in an int; a horse's is its color (0-6) plus its markings (0-4) times 256. */
    private static final int MAX_INT_TRY = 15;
    private static final int HORSE_TYPES = 5, HORSE_COLORS = 7, HORSE_MARKINGS = 5;
    /** Wool colors in the order of {@link EntitySheep#fleeceColorTable} (by the wool's metadata). */
    private static final String[] WOOL = { "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black" };
    /** Base renderers of the game whose own pictures (enchant glint, armor) are not a mob's. */
    private static final Set<Class<?>> BASE_RENDERERS = new HashSet<>();

    static {
        BASE_RENDERERS.add(Render.class);
        BASE_RENDERERS.add(RendererLivingEntity.class);
        BASE_RENDERERS.add(RenderLiving.class);
        BASE_RENDERERS.add(RenderBiped.class);
    }

    private static Method textureMethod;

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
        try {
            Dump dump = new Dump(mc);
            dump.run();
            ChatComponentText message = new ChatComponentText(
                EnumChatFormatting.GREEN + I18n.format(
                    "wayfarmap.mobdump.done",
                    dump.mobs,
                    dump.written,
                    dump.failed,
                    dump.folder.getName()));
            // Click: the folder with the dump.
            message.setChatStyle(
                new ChatStyle().setChatClickEvent(
                    new ClickEvent(ClickEvent.Action.OPEN_FILE, dump.folder.getAbsolutePath())));
            sender.addChatMessage(message);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not dump the mob textures", t);
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + I18n.format("wayfarmap.mobdump.failed", t)));
        }
    }

    /** One run of the command. */
    private static final class Dump {

        final Minecraft mc;
        final File folder;
        final TextureManager textures;
        /** Written pictures by texture, so a picture many mobs share is read once. */
        final Map<ResourceLocation, BufferedImage> read = new LinkedHashMap<>();
        int mobs, written, failed;

        Dump(Minecraft mc) {
            this.mc = mc;
            this.textures = mc.getTextureManager();
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            folder = new File(new File(new File(mc.mcDataDir, "wayfarmap"), "dumps"), "mob-textures-" + date);
        }

        void run() throws IOException, NoSuchMethodException {
            if (!folder.mkdirs() && !folder.isDirectory()) {
                throw new IOException("Could not make " + folder);
            }
            findTextureMethod();
            // By mod, then by name.
            Map<String, Class<?>> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) EntityList.classToStringMapping).entrySet()) {
                if (entry.getKey() instanceof Class && entry.getValue() instanceof String) {
                    Class<?> kind = (Class<?>) entry.getKey();
                    if (isMob(kind)) {
                        kinds.put(modOf(kind, (String) entry.getValue()) + "/" + entry.getValue(), kind);
                    }
                }
            }
            // Mobs with a renderer but no name (made only by other mobs or by code).
            for (Object key : ((Map<?, ?>) RenderManager.instance.entityRenderMap).keySet()) {
                if (key instanceof Class && isMob((Class<?>) key) && !kinds.containsValue(key)) {
                    Class<?> kind = (Class<?>) key;
                    kinds.put(modOf(kind, null) + "/" + kind.getSimpleName(), kind);
                }
            }
            try (PrintWriter index = new PrintWriter(new File(folder, "index.txt"), StandardCharsets.UTF_8.name())) {
                index.println("Mob textures dumped by Wayfarer's Map (/" + COMMAND + ")");
                index.println("Sources: main = the mob's own skin;");
                index.println("  data[slot]=value = the skin with that synced value;");
                index.println("  horse/profession = the mob's kind set the way the game does;");
                index.println("  renderer.field = a picture its renderer keeps; wool:color = sheep wool dyed;");
                index.println("  (gpu) = read back from the graphics card, the game made the picture itself.");
                index.println();
                for (Map.Entry<String, Class<?>> entry : kinds.entrySet()) {
                    dumpMob(index, entry.getKey(), entry.getValue());
                }
                index.println();
                index.println(
                    "Mobs: " + mobs + ", pictures written: " + written + ", could not be read: " + failed);
            }
        }

        private void dumpMob(PrintWriter index, String key, Class<?> kind) {
            mobs++;
            File mobFolder = new File(folder, safe(key));
            index.println(key + "  (" + kind.getName() + ")");
            Entity entity;
            Render render;
            try {
                entity = create(kind);
                render = RenderManager.instance.getEntityClassRenderObject(kind);
            } catch (Throwable t) {
                index.println("    could not be made: " + t);
                failed++;
                return;
            }
            index.println("    renderer: " + (render == null ? "none" : render.getClass().getName()));
            if (render == null) {
                return;
            }
            // Each texture with the first way it was found.
            Map<ResourceLocation, String> found = new LinkedHashMap<>();
            ResourceLocation main = texture(render, entity);
            if (main != null) {
                found.put(main, "main");
            }
            dataVariants(render, entity, found);
            knownVariants(render, entity, found);
            rendererFields(render, found);
            Set<String> names = new HashSet<>();
            for (Map.Entry<ResourceLocation, String> texture : found.entrySet()) {
                write(index, mobFolder, names, texture.getKey(), texture.getValue());
            }
            if (entity instanceof EntitySheep) {
                woolColors(index, mobFolder, names, found.keySet());
            }
        }

        /** Every value tried in each synced data slot holding a byte or an int, one slot at a time. */
        private void dataVariants(Render render, Entity entity, Map<ResourceLocation, String> found) {
            DataWatcher watcher = entity.getDataWatcher();
            List<?> watched = watcher == null ? null : watcher.getAllWatched();
            if (watched == null) {
                return;
            }
            for (Object item : watched) {
                DataWatcher.WatchableObject slot = (DataWatcher.WatchableObject) item;
                Object original = slot.getObject();
                List<Object> tries = new ArrayList<>();
                if (original instanceof Byte) {
                    for (byte value : BYTE_TRIES) {
                        tries.add(value);
                    }
                } else if (original instanceof Integer) {
                    for (int value = 0; value <= MAX_INT_TRY; value++) {
                        tries.add(value);
                    }
                    for (int markings = 1; markings < HORSE_MARKINGS; markings++) {
                        for (int color = 0; color < HORSE_COLORS; color++) {
                            tries.add(markings << 8 | color);
                        }
                    }
                } else {
                    continue;
                }
                try {
                    for (Object value : tries) {
                        slot.setObject(value);
                        add(found, texture(render, entity), "data[" + slot.getDataValueId() + "]=" + value);
                    }
                } finally {
                    slot.setObject(original);
                }
            }
        }

        /**
         * Mobs whose skin is kept once worked out, so changing their synced data alone doesn't show the others: horses
         * (kinds, colors and markings) and villagers (every profession, mods' too), changed the way the game does.
         */
        private void knownVariants(Render render, Entity entity, Map<ResourceLocation, String> found) {
            try {
                if (entity instanceof EntityHorse) {
                    EntityHorse horse = (EntityHorse) entity;
                    for (int type = 0; type < HORSE_TYPES; type++) {
                        for (int markings = 0; markings < HORSE_MARKINGS; markings++) {
                            for (int color = 0; color < HORSE_COLORS; color++) {
                                horse.setHorseType(type);
                                horse.setHorseVariant(markings << 8 | color);
                                add(found, texture(render, horse), "horse type=" + type + " variant=" + color
                                    + "+" + markings);
                            }
                        }
                    }
                } else if (entity instanceof EntityVillager) {
                    EntityVillager villager = (EntityVillager) entity;
                    Set<Integer> professions = new TreeSet<>();
                    for (int profession = 0; profession <= MAX_INT_TRY; profession++) {
                        professions.add(profession);
                    }
                    for (Object profession : VillagerRegistry.getRegisteredVillagers()) {
                        if (profession instanceof Integer) {
                            professions.add((Integer) profession);
                        }
                    }
                    for (int profession : professions) {
                        villager.setProfession(profession);
                        add(found, texture(render, villager), "profession=" + profession);
                    }
                }
            } catch (Throwable ignored) {
                // A mod's horse or villager that works differently: its other skins are found as for any mob.
            }
        }

        private void add(Map<ResourceLocation, String> found, ResourceLocation texture, String source) {
            if (texture != null && !found.containsKey(texture)) {
                found.put(texture, source);
            }
        }

        /** Pictures the renderer keeps in its fields: eyes, saddles, overlays, lists of skins. */
        private void rendererFields(Render render, Map<ResourceLocation, String> found) {
            for (Class<?> type = render.getClass(); type != null && !BASE_RENDERERS.contains(type)
                && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(render);
                        collect(value, type.getSimpleName() + "." + field.getName(), found, 0);
                    } catch (Throwable ignored) {
                        // A field that can't be read has no picture to give.
                    }
                }
            }
        }

        private void collect(Object value, String source, Map<ResourceLocation, String> found, int depth) {
            if (value instanceof ResourceLocation) {
                add(found, (ResourceLocation) value, source);
            } else if (depth < 2 && value instanceof Object[]) {
                for (Object item : (Object[]) value) {
                    collect(item, source, found, depth + 1);
                }
            } else if (depth < 2 && value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    collect(item, source, found, depth + 1);
                }
            } else if (depth < 2 && value instanceof Map) {
                collect(((Map<?, ?>) value).values(), source, found, depth + 1);
            }
        }

        /** The wool picture dyed in each color, as the game tints it when drawing. */
        private void woolColors(PrintWriter index, File mobFolder, Set<String> names,
            Collection<ResourceLocation> found) {
            for (ResourceLocation texture : found) {
                if (!texture.getResourcePath()
                    .contains("fur")) {
                    continue;
                }
                BufferedImage fur = read.get(texture);
                if (fur == null) {
                    continue;
                }
                for (int color = 0; color < WOOL.length && color < EntitySheep.fleeceColorTable.length; color++) {
                    float[] tint = EntitySheep.fleeceColorTable[color];
                    BufferedImage dyed = new BufferedImage(
                        fur.getWidth(),
                        fur.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < fur.getHeight(); y++) {
                        for (int x = 0; x < fur.getWidth(); x++) {
                            int argb = fur.getRGB(x, y);
                            int r = (int) ((argb >> 16 & 0xFF) * tint[0]), g = (int) ((argb >> 8 & 0xFF) * tint[1]);
                            int b = (int) ((argb & 0xFF) * tint[2]);
                            dyed.setRGB(x, y, argb & 0xFF000000 | r << 16 | g << 8 | b);
                        }
                    }
                    String name = unique(names, "wool_" + WOOL[color] + ".png");
                    try {
                        mobFolder.mkdirs();
                        ImageIO.write(dyed, "png", new File(mobFolder, name));
                        index.println("    " + name + "  <- " + texture + "  [wool:" + WOOL[color] + "]");
                        written++;
                    } catch (IOException e) {
                        index.println("    " + name + "  could not be written: " + e);
                        failed++;
                    }
                }
            }
        }

        /** Writes one picture: the file from the resource packs, else read back from the graphics card. */
        private void write(PrintWriter index, File mobFolder, Set<String> names, ResourceLocation texture,
            String source) {
            String path = texture.getResourcePath();
            String base = safe(path.substring(path.lastIndexOf('/') + 1));
            String name = unique(
                names,
                base.toLowerCase(Locale.ROOT)
                    .endsWith(".png") ? base : base + ".png");
            File file = new File(mobFolder, name);
            try {
                mobFolder.mkdirs();
                try (InputStream in = mc.getResourceManager()
                    .getResource(texture)
                    .getInputStream()) {
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (!read.containsKey(texture)) {
                    read.put(texture, ImageIO.read(file));
                }
                index.println("    " + name + "  <- " + texture + "  [" + source + "]");
                written++;
                return;
            } catch (Throwable notAFile) {
                // Not in any resource pack: maybe the game made it (a horse's layers).
            }
            BufferedImage image = readBack(texture);
            if (image == null) {
                index.println("    " + texture + "  [" + source + "]  missing");
                failed++;
                return;
            }
            try {
                ImageIO.write(image, "png", file);
                read.put(texture, image);
                index.println("    " + name + "  <- " + texture + "  [" + source + "] (gpu)");
                written++;
            } catch (IOException e) {
                index.println("    " + name + "  could not be written: " + e);
                failed++;
            }
        }

        /** The texture as the graphics card has it, or null if the game has no such picture. */
        private BufferedImage readBack(ResourceLocation texture) {
            try {
                textures.bindTexture(texture);
                ITextureObject object = textures.getTexture(texture);
                if (object == null || object == TextureUtil.missingTexture) {
                    return null;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, object.getGlTextureId());
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width <= 0 || height <= 0 || (long) width * height > 4096L * 4096L) {
                    return null;
                }
                IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                int[] argb = new int[width * height];
                pixels.get(argb);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, width, height, argb, 0, width);
                return image;
            } catch (Throwable t) {
                return null;
            }
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

    /** The renderer's {@code getEntityTexture}, found by its type so it works with any names. */
    private static void findTextureMethod() throws NoSuchMethodException {
        if (textureMethod != null) {
            return;
        }
        for (Method method : Render.class.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() == ResourceLocation.class && params.length == 1 && params[0] == Entity.class) {
                method.setAccessible(true);
                textureMethod = method;
                return;
            }
        }
        throw new NoSuchMethodException("Render.getEntityTexture");
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
        return name.replaceAll("[^A-Za-z0-9._/-]", "_");
    }

    private static String unique(Set<String> names, String name) {
        String result = name;
        int dot = name.lastIndexOf('.');
        for (int n = 2; !names.add(result.toLowerCase(Locale.ROOT)); n++) {
            result = dot > 0 ? name.substring(0, dot) + "_" + n + name.substring(dot) : name + "_" + n;
        }
        return result;
    }
}
