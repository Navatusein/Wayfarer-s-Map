package WayFarMap.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.iso.IsoProjection;

/**
 * Draws the player and the mobs on the 3D world map as the game's own 3D models (skin, armor, walking, where they
 * look), seen from the same side and height as the map so they stand in it like the blocks. Done like the inventory
 * screen draws the player, with the map's view instead of the inventory's.
 * <p>
 * Mobs are drawn only where the map shows them: in sight of the map's viewer (not in caves, under roofs or trees) and
 * big enough to make out; anywhere else they are simply not there.
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

    /** Kinds of mobs whose renderer failed here: not drawn from then on. */
    private static final Set<Class<?>> BROKEN = new HashSet<>();
    /** Whether each mob was in sight, and when that was checked (world time). */
    private static final Map<EntityLivingBase, long[]> SIGHT = new WeakHashMap<>();
    /** Set once drawing the player failed (a mod's player renderer): the arrow is drawn from then on. */
    private static boolean playerFailed;

    private IsoEntityDrawer() {}

    private static final class Item {

        final EntityLivingBase entity;
        final double sx, sy, depth;
        final float scale;

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
        RenderManager manager = RenderManager.instance;
        float viewY = manager.playerViewY, viewX = manager.playerViewX;
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        double azimuth = Math.toDegrees(Math.atan2(projection.towardX, projection.towardZ));
        // Name tags would face this way.
        manager.playerViewY = (float) (180 - azimuth);
        manager.playerViewX = (float) Math.toDegrees(IsoProjection.ELEVATION);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        try {
            for (Item item : items) {
                // Each model only hides itself (its own back parts): the order above does the rest.
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                boolean drawn = drawModel(item, azimuth, partialTicks);
                if (item.entity == mc.thePlayer) {
                    playerDrawn = drawn;
                    playerFailed |= !drawn;
                } else if (!drawn) {
                    BROKEN.add(item.entity.getClass());
                }
            }
        } finally {
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
        return playerDrawn;
    }

    private static Item item(EntityLivingBase entity, MapDrawer.Projection screen, IsoProjection projection,
        float partialTicks, float scale) {
        double x = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
        double y = entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks - entity.yOffset;
        double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
        double[] at = screen.toScreen(x, y, z);
        // Nearer to the viewer: toward it on the ground, and higher.
        double depth = projection.toward(x, z) * IsoProjection.COS + y * IsoProjection.SIN;
        return new Item(entity, at[0], at[1], depth, scale);
    }

    /** Draws one model standing with its feet on its screen point; false if its renderer failed. */
    private static boolean drawModel(Item item, double azimuth, float partialTicks) {
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
            // Lit from above, fixed in the world like the map's light.
            RenderHelper.enableStandardItemLighting();
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
