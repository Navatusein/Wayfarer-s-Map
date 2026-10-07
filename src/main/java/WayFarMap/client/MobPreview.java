package WayFarMap.client;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.Theme;

/**
 * The preview over the mobs' options: two mobs of each kind (hostile, neutral, ambient, friendly, pets) under its name,
 * drawn the way the maps draw them with the icons, frames and arrows as set; the kinds that are hidden are dimmed.
 */
public final class MobPreview {

    private static final String[] KINDS = { "hostile", "neutral", "ambient", "friendly", "pets" };
    private static final int[] COLORS = { MapDrawer.HOSTILE_COLOR, MapDrawer.NEUTRAL_COLOR, MapDrawer.AMBIENT_COLOR,
        MapDrawer.FRIENDLY_COLOR, MapDrawer.PET_COLOR };
    /** Size of the icons at 100%: a little bigger than on the world map, so the faces read in the preview. */
    private static final float ICON_SIZE = 12f;

    /** The mobs shown, by kind; made in the world they were made for (none in the main menu). */
    private static EntityLivingBase[][] mobs;
    private static World madeIn;

    private MobPreview() {}

    /** Lets go of the mobs (and the world they were made in) when the settings close. */
    public static void release() {
        mobs = null;
        madeIn = null;
    }

    private static boolean shown(int kind) {
        switch (kind) {
            case 0:
                return Config.showHostileMobs;
            case 1:
                return Config.showPassiveMobs;
            case 2:
                return Config.showAmbientMobs;
            case 3:
                return Config.showOtherEntities;
            default:
                return Config.showPets;
        }
    }

    /** A mob made only to be drawn, never added to the world; null if it can't be made. */
    private static EntityLivingBase make(Supplier<EntityLivingBase> maker) {
        try {
            return maker.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private static EntityLivingBase[][] mobs(World world) {
        if (mobs == null || madeIn != world) {
            madeIn = world;
            mobs = new EntityLivingBase[][] {
                { make(() -> new EntityZombie(world)), make(() -> new EntityCreeper(world)) }, { make(() -> {
                    // Named with a name tag, like the wolf: its name shows too.
                    EntityCow cow = new EntityCow(world);
                    cow.setCustomNameTag(I18n.format("wayfarmap.settings.mobs.mob_name"));
                    return cow;
                }), make(() -> new EntityPig(world)) }, { make(() -> new EntityBat(world)), make(() -> {
                    EntityBat bat = new EntityBat(world);
                    bat.setIsBatHanging(true);
                    return bat;
                }) },
                { make(() -> new EntityVillager(world)), make(() -> new EntityIronGolem(world)) }, { make(() -> {
                    EntityWolf wolf = new EntityWolf(world);
                    wolf.setTamed(true);
                    wolf.setCustomNameTag(I18n.format("wayfarmap.settings.mobs.pet_name"));
                    return wolf;
                }), make(() -> {
                    EntityOcelot cat = new EntityOcelot(world);
                    cat.setTamed(true);
                    cat.setTameSkin(1);
                    return cat;
                }) } };
        }
        return mobs;
    }

    /** Draws the kinds side by side in the area, their names along its top. */
    public static void draw(FontRenderer font, int left, int top, int right, int bottom) {
        EntityLivingBase[][] all = mobs(Minecraft.getMinecraft().theWorld);
        float size = ICON_SIZE * Config.mobIconScale / 100f;
        int frame = Config.entityIcons ? Math.max(0, Config.mobFrameWidth) : 0;
        double seconds = System.currentTimeMillis() / 1000.0;
        double columnWidth = (right - left) / (double) KINDS.length;
        // A little over the middle under the names: room for a pet's name under its icon.
        double iconsY = (top + 12 + bottom) / 2.0 - 3;
        for (int kind = 0; kind < KINDS.length; kind++) {
            double cx = left + columnWidth * (kind + 0.5);
            boolean shown = shown(kind);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            String label = Theme
                .ellipsize(font, I18n.format("wayfarmap.settings.mobs." + KINDS[kind]), (int) columnWidth - 4);
            int labelX = (int) Math.round(cx - font.getStringWidth(label) / 2.0);
            font.drawStringWithShadow(label, labelX, top, shown ? COLORS[kind] : Theme.TEXT_DISABLED);

            EntityLivingBase[] row = all[kind];
            double step = Math.min(size + 2 * frame + 8, (columnWidth - 4) / row.length);
            for (int i = 0; i < row.length; i++) {
                double sx = cx + (i - (row.length - 1) / 2.0) * step;
                EntityLivingBase mob = row[i];
                if (mob != null) {
                    // Looking around slowly, each its own way, so the arrows of where they look move.
                    double look = Math.sin(seconds * 0.8 + kind * 1.7 + i * 2.3) * 70;
                    mob.rotationYawHead = mob.prevRotationYawHead = (float) (kind * 90 + i * 50 + look);
                }
                MapDrawer.drawMob(font, mob, sx, iconsY, size, COLORS[kind], shown ? 1f : 0.25f, 0.8f);
            }
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
