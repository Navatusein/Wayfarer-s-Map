package WayFarMap.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;

/** Shared drawing code of the minimap and the fullscreen map. */
public final class MapDrawer {

    private MapDrawer() {}

    /**
     * Draws the map into the screen rectangle ({@code x}, {@code y}, {@code width}, {@code height}).
     *
     * @param centerX world X shown at the center of the rectangle
     * @param centerZ world Z shown at the center of the rectangle
     * @param scale   screen pixels per block
     */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;

        int rx0 = floor(left) >> MapRegion.SHIFT;
        int rz0 = floor(top) >> MapRegion.SHIFT;
        int rx1 = floor(right) >> MapRegion.SHIFT;
        int rz1 = floor(bottom) >> MapRegion.SHIFT;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float[] tint = lightTint(Minecraft.getMinecraft());
        GL11.glColor4f(tint[0], tint[1], tint[2], 1f);

        Tessellator tessellator = Tessellator.instance;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                // Regions on disk are read in the background; they pop in once loaded.
                MapRegion region = dimension.requestRegion(rx, rz);
                if (region == null) {
                    continue;
                }

                // Part of the region that is inside the view, in block coordinates.
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }

                region.bindTexture();
                double u0 = (bx0 - regionX) / MapRegion.SIZE;
                double v0 = (bz0 - regionZ) / MapRegion.SIZE;
                double u1 = (bx1 - regionX) / MapRegion.SIZE;
                double v1 = (bz1 - regionZ) / MapRegion.SIZE;
                double sx0 = x + (bx0 - left) * scale;
                double sy0 = y + (bz0 - top) * scale;
                double sx1 = x + (bx1 - left) * scale;
                double sy1 = y + (bz1 - top) * scale;

                tessellator.startDrawingQuads();
                tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                tessellator.draw();
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static final int CHUNK_LINE = 0x30FFFFFF;
    private static final int REGION_LINE = 0x70FFFFFF;

    /**
     * Draws chunk borders (every 16 blocks) and region borders (every 512 blocks) over the map rectangle. Chunk lines
     * are left out when zoomed out so far that they would be closer than a few pixels.
     */
    public static void drawChunkGrid(double centerX, double centerZ, double scale, int x, int y, int width, int height) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        boolean chunks = 16 * scale >= 6;
        int step = chunks ? 16 : MapRegion.SIZE;

        // Lines are placed and sized in real screen pixels: snapping to GUI pixels (2-4 screen pixels each) made them
        // jump behind the smoothly moving map.
        Minecraft mc = Minecraft.getMinecraft();
        double pixel = 1.0 / new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        int firstX = (int) Math.floor(left / step) * step;
        for (int bx = firstX; ; bx += step) {
            double sx = x + (bx - left) * scale;
            if (sx > x + width) {
                break;
            }
            if (sx >= x) {
                int color = Math.floorMod(bx, MapRegion.SIZE) == 0 ? REGION_LINE : CHUNK_LINE;
                double lx = Math.floor(sx / pixel) * pixel;
                fillRect(tessellator, lx, y, lx + pixel, y + height, color);
            }
        }
        int firstZ = (int) Math.floor(top / step) * step;
        for (int bz = firstZ; ; bz += step) {
            double sy = y + (bz - top) * scale;
            if (sy > y + height) {
                break;
            }
            if (sy >= y) {
                int color = Math.floorMod(bz, MapRegion.SIZE) == 0 ? REGION_LINE : CHUNK_LINE;
                double ly = Math.floor(sy / pixel) * pixel;
                fillRect(tessellator, x, ly, x + width, ly + pixel, color);
            }
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Map color multiplier for night. */
    private static final float[] NIGHT_TINT = { 0.28f, 0.32f, 0.5f };

    /** RGB multiplier for the map according to {@link Config#mapLightMode} and the time of day. */
    public static float[] lightTint(Minecraft mc) {
        float day;
        if (Config.mapLightMode == Config.LIGHT_DAY) {
            day = 1f;
        } else if (Config.mapLightMode == Config.LIGHT_NIGHT) {
            day = 0f;
        } else if (mc.theWorld == null || mc.theWorld.provider.hasNoSky
            || MapManager.INSTANCE.getActiveCaveLayer() >= 0) {
            // No sunlight underground: caves look the same at any time of day.
            day = 1f;
        } else {
            // Sun brightness goes from about 0.2 at midnight to 1.0 at noon.
            float sun = mc.theWorld.getSunBrightness(1f);
            day = Math.max(0f, Math.min(1f, (sun - 0.2f) / 0.8f));
        }
        return new float[] { NIGHT_TINT[0] + (1f - NIGHT_TINT[0]) * day, NIGHT_TINT[1] + (1f - NIGHT_TINT[1]) * day,
            NIGHT_TINT[2] + (1f - NIGHT_TINT[2]) * day };
    }

    /** Draws an arrow at the given screen position pointing where the player looks. */
    public static void drawPlayerArrow(double sx, double sy, float yaw, float size, int color) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glTranslated(sx, sy, 0);
        // Yaw 180 faces north, which is "up" on the map.
        GL11.glRotatef(yaw + 180f, 0f, 0f, 1f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Tessellator tessellator = Tessellator.instance;
        // Dark outline, then the colored arrow on top.
        drawArrowShape(tessellator, size + 1.2f, 0xFF000000);
        drawArrowShape(tessellator, size, color);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static void drawArrowShape(Tessellator tessellator, float size, int color) {
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        // Two triangles forming an arrow head with a notch at the back.
        tessellator.addVertex(0, -size, 0);
        tessellator.addVertex(-size * 0.75f, size, 0);
        tessellator.addVertex(0, size * 0.45f, 0);
        tessellator.addVertex(0, -size, 0);
        tessellator.addVertex(0, size * 0.45f, 0);
        tessellator.addVertex(size * 0.75f, size, 0);
        tessellator.draw();
    }

    /** Draws a filled square marker centered on the given position. */
    public static void drawDot(double sx, double sy, float radius, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        fillRect(tessellator, sx - radius - 1, sy - radius - 1, sx + radius + 1, sy + radius + 1, 0xFF000000);
        fillRect(tessellator, sx - radius, sy - radius, sx + radius, sy + radius, color);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void fillRect(Tessellator tessellator, double x0, double y0, double x1, double y1, int color) {
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
    }

    private static final int HOSTILE_COLOR = 0xFFFF4040;
    private static final int PASSIVE_COLOR = 0xFF60E060;
    private static final int OTHER_COLOR = 0xFFFFE040;

    /**
     * Draws mobs (colored dots) and other players (their face) that are within the rectangle.
     *
     * @param playerSize size of player heads in GUI pixels
     * @param showNames  draw player names under their heads
     */
    public static void drawEntities(Minecraft mc, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, float partialTicks, float playerSize, boolean showNames) {
        double playerY = mc.thePlayer.posY;
        List<EntityPlayer> players = new ArrayList<>();
        List<EntityLivingBase> mobs = new ArrayList<>();
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityLivingBase) || o == mc.thePlayer) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) o;
            if (entity instanceof EntityPlayer) {
                if (Config.showOtherPlayers) {
                    players.add((EntityPlayer) entity);
                }
                continue;
            }
            if (entity.isInvisible() || entity.isDead
                || Math.abs(entity.posY - playerY) > Config.entityVerticalRange
                || entityColor(entity) == 0) {
                continue;
            }
            double sx = x + width / 2.0 + (entity.posX - centerX) * scale;
            double sy = y + height / 2.0 + (entity.posZ - centerZ) * scale;
            if (sx < x + 2 || sy < y + 2 || sx > x + width - 2 || sy > y + height - 2) {
                continue;
            }
            mobs.add(entity);
        }

        // The nearest mobs get an icon, the rest a dot; far ones are drawn first so near ones end up on top.
        final double cx = centerX, cz = centerZ;
        mobs.sort((a, b) -> Double.compare(distanceSq(b, cx, cz), distanceSq(a, cx, cz)));
        int firstIcon = Config.entityIcons ? Math.max(0, mobs.size() - Config.entityIconLimit) : mobs.size();
        // Icons shrink when zooming out (like waypoints) so they don't cover the map, down to half their size.
        float zoomFactor = (float) Math.max(0.5, Math.min(1.0, Math.pow(scale, 0.4)));
        float iconSize = Math.max(4f, (playerSize + 2f) * zoomFactor);
        playerSize = Math.max(4f, playerSize * zoomFactor);
        for (int i = 0; i < mobs.size(); i++) {
            EntityLivingBase entity = mobs.get(i);
            double ex = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
            double ez = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
            double sx = x + width / 2.0 + (ex - centerX) * scale;
            double sy = y + height / 2.0 + (ez - centerZ) * scale;
            int color = entityColor(entity);
            float half = iconSize / 2f;
            if (i >= firstIcon && sx >= x + half && sy >= y + half && sx <= x + width - half && sy <= y + height - half) {
                drawEntityIcon(entity, sx, sy, iconSize, color);
            } else {
                drawDot(sx, sy, 1f, color);
            }
        }

        // Players on top of mobs.
        FontRenderer font = mc.fontRenderer;
        float half = playerSize / 2f;
        for (EntityPlayer other : players) {
            double px = other.prevPosX + (other.posX - other.prevPosX) * partialTicks;
            double pz = other.prevPosZ + (other.posZ - other.prevPosZ) * partialTicks;
            double sx = x + width / 2.0 + (px - centerX) * scale;
            double sy = y + height / 2.0 + (pz - centerZ) * scale;
            if (sx < x + half || sy < y + half || sx > x + width - half || sy > y + height - half) {
                continue;
            }
            drawPlayerHead(mc, other, sx, sy, playerSize);
            if (showNames) {
                String name = other.getCommandSenderName();
                font.drawStringWithShadow(
                    name,
                    (int) sx - font.getStringWidth(name) / 2,
                    (int) (sy + half) + 2,
                    0xFFFFFF);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static double distanceSq(EntityLivingBase entity, double x, double z) {
        double dx = entity.posX - x, dz = entity.posZ - z;
        return dx * dx + dz * dz;
    }

    /** Marker color by kind of mob, or 0 if that kind is hidden. */
    private static int entityColor(EntityLivingBase entity) {
        if (entity instanceof IMob) {
            return Config.showHostileMobs ? HOSTILE_COLOR : 0;
        }
        if (entity instanceof IAnimals) {
            return Config.showPassiveMobs ? PASSIVE_COLOR : 0;
        }
        return Config.showOtherEntities ? OTHER_COLOR : 0;
    }

    /**
     * Draws the mob as a flat icon: its face on a small tile framed in the color of its kind (hostile, passive, other).
     * Falls back to a dot when the mob's face can't be found.
     */
    private static void drawEntityIcon(EntityLivingBase entity, double sx, double sy, float size, int color) {
        double half = size / 2.0;
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        fillRect(tessellator, sx - half - 1, sy - half - 1, sx + half + 1, sy + half + 1, color);
        fillRect(tessellator, sx - half, sy - half, sx + half, sy + half, 0xFF101418);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (!EntityIcons.drawFace(entity, sx, sy, size - 1f)) {
            drawDot(sx, sy, 1f, color);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Draws the face of the player's skin (with the hat layer), with a dark border. */
    private static void drawPlayerHead(Minecraft mc, EntityPlayer player, double sx, double sy, float size) {
        double half = size / 2.0;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        fillRect(tessellator, sx - half - 1, sy - half - 1, sx + half + 1, sy + half + 1, 0xFF000000);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (!(player instanceof AbstractClientPlayer)) {
            return;
        }
        mc.getTextureManager()
            .bindTexture(((AbstractClientPlayer) player).getLocationSkin());
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // 1.7.10 skins are 64x32: the face is at (8, 8), the hat layer at (40, 8), both 8x8.
        drawSkinPart(tessellator, sx, sy, half, 8, 8);
        drawSkinPart(tessellator, sx, sy, half, 40, 8);
    }

    private static void drawSkinPart(Tessellator tessellator, double sx, double sy, double half, int u, int v) {
        double u0 = u / 64.0, u1 = (u + 8) / 64.0, v0 = v / 32.0, v1 = (v + 8) / 32.0;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(sx - half, sy + half, 0, u0, v1);
        tessellator.addVertexWithUV(sx + half, sy + half, 0, u1, v1);
        tessellator.addVertexWithUV(sx + half, sy - half, 0, u1, v0);
        tessellator.addVertexWithUV(sx - half, sy - half, 0, u0, v0);
        tessellator.draw();
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
