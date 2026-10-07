package WayFarMap.client;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderLivingEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.client.map.iso.IsoProjection;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws the player and the mobs on the 3D world map as the game's own 3D models (skin, armor, walking, where they
 * look), seen from the same side and height as the map so they stand in it like the blocks. Done like the inventory
 * screen draws the player, with the map's view instead of the inventory's.
 * <p>
 * Mobs are drawn only where the map shows them: in sight of the map's viewer (not in caves, under roofs or trees) and
 * big enough to make out; anywhere else they are simply not there. The parts of a model behind what the map shows
 * (a wall in front of the player, a roof) are hidden, pixel by pixel, by the depth of the map's tiles.
 */
public final class IsoEntityDrawer {

    /** Smallest size the player is drawn at when the map is zoomed out, in GUI pixels per block (1.8 tall). */
    private static final float PLAYER_MIN_PIXELS_PER_BLOCK = 9f;
    /** Mobs are left out below this zoom, in GUI pixels per block: too small to make out. */
    private static final double MOB_MIN_PIXELS_PER_BLOCK = 4;
    /** In front of the map and what is drawn on it. */
    private static final float DEPTH = 200f;
    /** Mobs drawn at most (the nearest to the middle of the screen), so a crowded farm can't slow the map down. */
    private static final int MOB_LIMIT = 256;
    /** How often a mob's view to the viewer is checked again, in ticks. */
    private static final int SIGHT_TICKS = 4;
    /** How far along the line of sight blocks are looked for, in blocks. */
    private static final double SIGHT_DISTANCE = 96;

    /** Depth of the parts of the map hiding a model: in front of every model (they are drawn at {@link #DEPTH}). */
    private static final float HIDING_DEPTH = 700f;
    /** Points of the map looked at per model at most, for hiding its parts behind the map. */
    private static final int HIDING_SAMPLES = 40_000;
    /** How far (blocks toward the viewer) the map must be in front of a model to hide it. */
    private static final double HIDING_MARGIN = 0.1;
    private static float[] hidingGrid = new float[0];

    /** Kinds of mobs whose renderer failed here: not drawn from then on. */
    private static final Set<Class<?>> BROKEN = new HashSet<>();
    /** Whether each mob was in sight, and when that was checked (world time). */
    private static final Map<EntityLivingBase, long[]> SIGHT = new WeakHashMap<>();
    /** Set once drawing the player failed (a mod's player renderer): the arrow is drawn from then on. */
    private static boolean playerFailed;

    private IsoEntityDrawer() {}

    /** While the map draws the models: the game's own name tags over players are left out (the map names them). */
    private static boolean drawingModels;

    /**
     * Leaves out the game's name tag over the players the map draws: the map writes their name itself (a teammate's
     * in the team color), and both showed one over the other. Registered on the Forge bus.
     */
    public static final class NameTags {

        @SubscribeEvent
        public void onSpecials(RenderLivingEvent.Specials.Pre event) {
            if (drawingModels && event.entity instanceof EntityPlayer && event.isCancelable()) {
                event.setCanceled(true);
            }
        }
    }

    private static final class Item {

        final EntityLivingBase entity;
        /** Name shown over the model (other players), or null; and its color. */
        String name;
        int nameColor;
        final double sx, sy, depth;
        final float scale;
        /** Where it stands in the world. */
        double x, y, z;

        Item(EntityLivingBase entity, double sx, double sy, double depth, float scale) {
            this.entity = entity;
            this.sx = sx;
            this.sy = sy;
            this.depth = depth;
            this.scale = scale;
        }
    }

    /**
     * Draws the mobs in sight and, if {@code withPlayer}, the player.
     *
     * @param pixelsPerBlock the map's scale, GUI pixels per block
     * @return whether the player was drawn (if not, draw the arrow)
     */
    public static boolean draw(Minecraft mc, MapDrawer.Projection screen, double pixelsPerBlock,
        IsoProjection projection, int width, int height, float partialTicks, boolean withMobs, boolean withPlayer) {
        List<Item> items = new ArrayList<>();
        boolean player = withPlayer && !playerFailed;
        if (player) {
            float playerScale = (float) Math.max(PLAYER_MIN_PIXELS_PER_BLOCK, pixelsPerBlock);
            items.add(item(mc.thePlayer, screen, projection, partialTicks, playerScale));
        }
        if (withMobs) {
            addPlayers(mc, screen, projection, width, height, partialTicks, pixelsPerBlock, items);
        }
        if (withMobs && pixelsPerBlock >= MOB_MIN_PIXELS_PER_BLOCK) {
            List<Item> mobs = new ArrayList<>();
            long now = mc.theWorld.getTotalWorldTime();
            float margin = (float) (pixelsPerBlock * 3);
            for (Object o : mc.theWorld.loadedEntityList) {
                if (!(o instanceof EntityLiving)) {
                    continue;
                }
                EntityLiving mob = (EntityLiving) o;
                if (mob.isDead || mob.isInvisible()
                    || BROKEN.contains(mob.getClass())
                    || MapDrawer.entityColor(mob) == 0) {
                    continue;
                }
                Item item = item(mob, screen, projection, partialTicks, (float) pixelsPerBlock);
                if (item.sx < -margin || item.sy < -margin || item.sx > width + margin || item.sy > height + margin) {
                    continue;
                }
                if (inSight(mc.theWorld, mob, projection, now)) {
                    mobs.add(item);
                }
            }
            if (mobs.size() > MOB_LIMIT) {
                final double cx = width / 2.0, cy = height / 2.0;
                mobs.sort(
                    (a, b) -> Double.compare(
                        (a.sx - cx) * (a.sx - cx) + (a.sy - cy) * (a.sy - cy),
                        (b.sx - cx) * (b.sx - cx) + (b.sy - cy) * (b.sy - cy)));
                mobs = mobs.subList(0, MOB_LIMIT);
            }
            items.addAll(mobs);
        }
        if (items.isEmpty()) {
            return false;
        }
        // Far ones first, each over what is behind it.
        items.sort((a, b) -> Double.compare(a.depth, b.depth));

        boolean playerDrawn = false;
        // Night as the map shows it (the time of day, or the day/night buttons): the models get darker with it.
        float night = MapDrawer.isoNightAmount(mc, mc.theWorld.provider.dimensionId);
        float[] tint = MapDrawer.isoLightTint(mc, mc.theWorld.provider.dimensionId);
        RenderManager manager = RenderManager.instance;
        float viewY = manager.playerViewY, viewX = manager.playerViewX;
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        double azimuth = Math.toDegrees(Math.atan2(projection.towardX, projection.towardZ));
        // Name tags would face this way.
        manager.playerViewY = (float) (180 - azimuth);
        manager.playerViewX = (float) Math.toDegrees(IsoProjection.ELEVATION);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        int dimension = mc.theWorld.provider.dimensionId;
        int factor = ScaledScreen.currentFactor();
        drawingModels = true;
        try {
            for (Item item : items) {
                // Each model only hides itself (its own back parts): the order above does the rest.
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                if (!hideBehindMap(item, projection, pixelsPerBlock, dimension, factor)) {
                    // Wholly behind what the map shows: the player is shown by the arrow, others not at all (a
                    // teammate keeps the name).
                    if (item.entity == mc.thePlayer) {
                        playerDrawn = false;
                    }
                    continue;
                }
                boolean drawn = drawModel(item, azimuth, partialTicks, light(mc.theWorld, item.entity, night, tint));
                if (item.entity == mc.thePlayer) {
                    playerDrawn = drawn;
                    playerFailed |= !drawn;
                } else if (!drawn) {
                    BROKEN.add(item.entity.getClass());
                }
            }
        } finally {
            drawingModels = false;
            manager.playerViewY = viewY;
            manager.playerViewX = viewX;
            // Back as the inventory screen leaves it.
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            // The models' depth would hide what the screen draws over them afterwards (menus, tooltips).
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            if (!depthTest) {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
        // Names over the players, on top of all the models.
        for (Item item : items) {
            if (item.name != null && !BROKEN.contains(item.entity.getClass())) {
                drawName(mc, item);
            }
        }
        return playerDrawn;
    }

    /** Teammates' names, in the color the map gives teammates. */
    private static final int TEAMMATE_NAME = 0x9FD4FF;

    /**
     * Other players: teammates (ServerUtilities team) always, like the player, the size of the player at least;
     * others like mobs, only in sight of the map's viewer and close enough, if players are shown. Both named.
     */
    private static void addPlayers(Minecraft mc, MapDrawer.Projection screen, IsoProjection projection, int width,
        int height, float partialTicks, double pixelsPerBlock, List<Item> items) {
        long now = mc.theWorld.getTotalWorldTime();
        float margin = (float) (Math.max(pixelsPerBlock, PLAYER_MIN_PIXELS_PER_BLOCK) * 3);
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer) || o == mc.thePlayer) {
                continue;
            }
            EntityPlayer other = (EntityPlayer) o;
            if (other.isDead || BROKEN.contains(other.getClass())) {
                continue;
            }
            boolean teammate = TeamMates.INSTANCE.isTeammate(other.getUniqueID());
            float scale;
            if (teammate) {
                scale = (float) Math.max(PLAYER_MIN_PIXELS_PER_BLOCK, pixelsPerBlock);
            } else {
                if (!Config.showOtherPlayers || other.isInvisible() || pixelsPerBlock < MOB_MIN_PIXELS_PER_BLOCK) {
                    continue;
                }
                scale = (float) pixelsPerBlock;
            }
            Item item = item(other, screen, projection, partialTicks, scale);
            if (item.sx < -margin || item.sy < -margin || item.sx > width + margin || item.sy > height + margin) {
                continue;
            }
            if (!teammate && !inSight(mc.theWorld, other, projection, now)) {
                continue;
            }
            item.name = other.getCommandSenderName();
            item.nameColor = teammate ? TEAMMATE_NAME : 0xFFFFFF;
            items.add(item);
        }
    }

    /** Whether teammates near enough to be in the client's world are drawn as their model (not as a head). */
    public static boolean drawsPlayers() {
        return !BROKEN.contains(EntityOtherPlayerMP.class);
    }

    /** A player's name over the model's head, small, with a shadow. */
    private static void drawName(Minecraft mc, Item item) {
        // The top of the head on the screen: the model's height turned up by the map's view.
        double top = item.sy - (item.entity.height + 0.25) * IsoProjection.COS * item.scale;
        float textScale = 0.75f;
        int width = mc.fontRenderer.getStringWidth(item.name);
        GL11.glPushMatrix();
        GL11.glTranslated(item.sx, top - 9 * textScale, 0);
        GL11.glScalef(textScale, textScale, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRenderer.drawStringWithShadow(item.name, -width / 2, 0, item.nameColor);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static Item item(EntityLivingBase entity, MapDrawer.Projection screen, IsoProjection projection,
        float partialTicks, float scale) {
        double x = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
        double y = entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks - entity.yOffset;
        double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
        double[] at = screen.toScreen(x, y, z);
        // Nearer to the viewer: toward it on the ground, and higher.
        double depth = projection.toward(x, z) * IsoProjection.COS + y * IsoProjection.SIN;
        Item item = new Item(entity, at[0], at[1], depth, scale);
        item.x = x;
        item.y = y;
        item.z = z;
        return item;
    }

    /**
     * Fills the depth buffer in front of the model wherever the map shows something solid nearer to the viewer than
     * the model (a wall in front of it, a roof over it), so those parts of the model aren't drawn. The model is taken
     * as an upright cylinder of its width and height: where a line of sight passes through it, the map must be in
     * front of where the line enters it; elsewhere (arms, a big head) in front of its outermost point.
     *
     * @return false if all of the model is hidden (nothing to draw)
     */
    private static boolean hideBehindMap(Item item, IsoProjection projection, double scale, int dimension, int factor) {
        EntityLivingBase entity = item.entity;
        double radius = Math.max(0.2, entity.width / 2), height = Math.max(0.3, entity.height);
        double sin = IsoProjection.SIN, cos = IsoProjection.COS;
        // The screen rectangle the model can cover, in GUI pixels.
        double left = item.sx - (radius * 1.5 + 0.5) * scale, right = item.sx + (radius * 1.5 + 0.5) * scale;
        double top = item.sy - (height * cos + radius * sin + 0.5) * scale;
        double bottom = item.sy + (radius * 1.5 * sin + 0.3) * scale;
        double step = Math.max(1.0 / Math.max(1, factor), Math.sqrt((right - left) * (bottom - top) / HIDING_SAMPLES));
        int columns = Math.max(1, (int) Math.ceil((right - left) / step));
        int rows = Math.max(1, (int) Math.ceil((bottom - top) / step));
        if (hidingGrid.length < columns * rows) {
            hidingGrid = new float[columns * rows];
        }
        float[] grid = hidingGrid;
        double um = projection.u(item.x, item.z), vm = projection.v(item.x, item.y, item.z);
        double tm = projection.toward(item.x, item.z);
        // The plane's point at the middle of the rectangle's first cell, and the cells' size in blocks.
        double u0 = um + (left + step / 2 - item.sx) / scale, v0 = vm + (top + step / 2 - item.sy) / scale;
        double blockStep = step / scale;
        if (!IsoMap.INSTANCE
            .solidToward(dimension, projection.rotation, scale, factor, u0, v0, blockStep, columns, rows, grid)) {
            return true;
        }
        int body = 0, bodySeen = 0;
        boolean anyHidden = false;
        boolean[] hidden = new boolean[columns * rows];
        for (int j = 0; j < rows; j++) {
            double v = v0 + j * blockStep;
            // Toward the viewer of this line of sight at the model's feet and at its top.
            double feet = (v + item.y * cos) / sin, head = (v + (item.y + height) * cos) / sin;
            for (int i = 0; i < columns; i++) {
                double du = u0 + i * blockStep - um;
                double front = tm + radius;
                boolean through = false;
                if (Math.abs(du) < radius) {
                    double half = Math.sqrt(radius * radius - du * du);
                    if (head >= tm - half && feet <= tm + half) {
                        through = true;
                        front = Math.min(tm + half, head);
                    }
                }
                float solid = grid[j * columns + i];
                boolean behind = !Float.isNaN(solid) && solid > front + HIDING_MARGIN;
                hidden[j * columns + i] = behind;
                anyHidden |= behind;
                if (through) {
                    body++;
                    if (!behind) {
                        bodySeen++;
                    }
                }
            }
        }
        if (body > 0 && bodySeen == 0) {
            return false;
        }
        if (!anyHidden) {
            return true;
        }
        // Only depth: the map stays as drawn, the model's parts behind these cells fail the depth test.
        // Model renderers leave back faces culled; these cells must not be.
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(true);
        GL11.glBegin(GL11.GL_QUADS);
        for (int j = 0; j < rows; j++) {
            double y0 = top + j * step, y1 = y0 + step;
            int i = 0;
            while (i < columns) {
                if (!hidden[j * columns + i]) {
                    i++;
                    continue;
                }
                int start = i;
                while (i < columns && hidden[j * columns + i]) {
                    i++;
                }
                double x0 = left + start * step, x1 = left + i * step;
                GL11.glVertex3d(x0, y0, HIDING_DEPTH);
                GL11.glVertex3d(x0, y1, HIDING_DEPTH);
                GL11.glVertex3d(x1, y1, HIDING_DEPTH);
                GL11.glVertex3d(x1, y0, HIDING_DEPTH);
            }
        }
        GL11.glEnd();
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (cull) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        return true;
    }

    /** Draws one model standing with its feet on its screen point; false if its renderer failed. */
    private static boolean drawModel(Item item, double azimuth, float partialTicks, float[] light) {
        EntityLivingBase entity = item.entity;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(item.sx, item.sy, DEPTH);
            // World up is screen up.
            GL11.glScalef(item.scale, -item.scale, item.scale);
            // The map's view: looked at from 30 degrees above, from the side the map is turned to (the direction
            // toward the viewer ends up out of the screen).
            GL11.glRotatef((float) Math.toDegrees(IsoProjection.ELEVATION), 1f, 0f, 0f);
            GL11.glRotatef((float) -azimuth, 0f, 1f, 0f);
            // Lit from above, fixed in the world like the map's light; never by the light where the mob really is.
            fullBright();
            RenderHelper.enableStandardItemLighting();
            dimLights(light);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            // The game draws the player that far below its position.
            GL11.glTranslatef(0f, entity.yOffset, 0f);
            float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            RenderManager.instance.renderEntityWithPosYaw(entity, 0, 0, 0, yaw, partialTicks);
            return true;
        } catch (Throwable t) {
            WayFarMap.LOG.warn(
                "Could not draw " + entity.getClass()
                    .getName() + " on the 3D map; it is left out",
                t);
            return false;
        } finally {
            GL11.glPopMatrix();
        }
    }

    /**
     * Turns off the game's light map (the light of blocks and sky at a place) for the next model. Left on by the
     * world's
     * drawing or by renderers that light parts themselves (spider and enderman eyes, which also leave the previous
     * mob's darkness set), it drew a mob standing in the shade or at night all black.
     */
    private static void fullBright() {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Warm light of torches and lamps, as on the 3D map at night. */
    private static final float[] WARM = { 1.05f, 0.88f, 0.62f };
    /** The game's own light of the inventory models (RenderHelper): of each lamp, and all around. */
    private static final float LAMP = 0.6f, AMBIENT = 0.4f;
    private static final FloatBuffer LIGHT_BUFFER = BufferUtils.createFloatBuffer(4);

    /**
     * Light of a model as the 3D map lights the place: full by day; by night the map's moonlight tint, warmed by the
     * torches and lamps lighting the block the mob stands in.
     */
    private static float[] light(World world, EntityLivingBase entity, float night, float[] tint) {
        if (night <= 0.01f) {
            return new float[] { 1f, 1f, 1f };
        }
        int x = MathHelper.floor_double(entity.posX), z = MathHelper.floor_double(entity.posZ);
        int y = Math.max(0, Math.min(255, MathHelper.floor_double(entity.boundingBox.minY + 0.5)));
        int level = Math.max(0, Math.min(15, world.getSavedLightValue(EnumSkyBlock.Block, x, y, z)));
        float lamps = world.provider.lightBrightnessTable[level];
        float[] light = new float[3];
        for (int i = 0; i < 3; i++) {
            light[i] = Math.min(1f, tint[i] + night * WARM[i] * lamps * 0.8f);
        }
        return light;
    }

    /** Scales the game's model lights (set up by RenderHelper just before) by the light of the place. */
    private static void dimLights(float[] light) {
        if (light[0] >= 1f && light[1] >= 1f && light[2] >= 1f) {
            return;
        }
        lightColor(LAMP, light);
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, LIGHT_BUFFER);
        lightColor(LAMP, light);
        GL11.glLight(GL11.GL_LIGHT1, GL11.GL_DIFFUSE, LIGHT_BUFFER);
        lightColor(AMBIENT, light);
        GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, LIGHT_BUFFER);
    }

    private static void lightColor(float strength, float[] light) {
        LIGHT_BUFFER.clear();
        LIGHT_BUFFER.put(strength * light[0])
            .put(strength * light[1])
            .put(strength * light[2])
            .put(1f);
        LIGHT_BUFFER.flip();
    }

    /** Whether the map's viewer sees the mob (checked again every few ticks). */
    private static boolean inSight(World world, EntityLivingBase mob, IsoProjection projection, long now) {
        long[] known = SIGHT.get(mob);
        if (known != null && now - known[0] < SIGHT_TICKS && now >= known[0]) {
            return known[1] != 0;
        }
        boolean seen = clearLine(world, mob.posX, mob.boundingBox.maxY - 0.1, mob.posZ, projection)
            || clearLine(world, mob.posX, mob.boundingBox.minY + 0.2, mob.posZ, projection);
        SIGHT.put(mob, new long[] { now, seen ? 1 : 0 });
        return seen;
    }

    /**
     * Whether nothing the map draws as solid is between the point and the viewer: walks the blocks along the line
     * toward the viewer (up at 30 degrees), as a ray from the map's camera would come.
     */
    private static boolean clearLine(World world, double x, double y, double z, IsoProjection projection) {
        double dx = projection.towardX * IsoProjection.COS, dy = IsoProjection.SIN;
        double dz = projection.towardZ * IsoProjection.COS;
        int bx = MathHelper.floor_double(x), by = MathHelper.floor_double(y), bz = MathHelper.floor_double(z);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        // Distance along the line to the next block border on each axis, and between borders.
        double tx = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : ((dx > 0 ? bx + 1 - x : x - bx) / Math.abs(dx));
        double ty = (by + 1 - y) / dy;
        double tz = Math.abs(dz) < 1e-9 ? Double.MAX_VALUE : ((dz > 0 ? bz + 1 - z : z - bz) / Math.abs(dz));
        double sx = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : 1 / Math.abs(dx), sy = 1 / dy;
        double sz = Math.abs(dz) < 1e-9 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        // The mob's own block is not looked at: it may stand in grass or a door.
        while (true) {
            double t = Math.min(tx, Math.min(ty, tz));
            if (t > SIGHT_DISTANCE) {
                return true;
            }
            if (t == tx) {
                bx += stepX;
                tx += sx;
            } else if (t == ty) {
                by++;
                ty += sy;
            } else {
                bz += stepZ;
                tz += sz;
            }
            if (by > 255) {
                return true;
            }
            if (by >= 0 && blocksSight(world.getBlock(bx, by, bz))) {
                return false;
            }
        }
    }

    /** Blocks that hide what is behind them on the map: solid ones and leaves (not glass, water or plants). */
    private static boolean blocksSight(Block block) {
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid()) {
            return false;
        }
        return block.isOpaqueCube() || material == Material.leaves;
    }
}
