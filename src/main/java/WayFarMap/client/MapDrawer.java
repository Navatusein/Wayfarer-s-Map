package WayFarMap.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.map.FlatLog;
import WayFarMap.client.map.LodTile;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;

/** Shared drawing code of the minimap and the fullscreen map. */
public final class MapDrawer {

    private MapDrawer() {}

    /**
     * Degrees the drawing is turned by (the minimap turning with the player); entity icons and player heads are
     * turned back so they stay upright.
     */
    public static float iconRotation;

    /** Turns the next drawing around (sx, sy) so it stays upright on a turned map; pair with glPopMatrix. */
    private static void pushUpright(double sx, double sy) {
        GL11.glPushMatrix();
        if (iconRotation != 0f) {
            GL11.glTranslated(sx, sy, 0);
            GL11.glRotatef(-iconRotation, 0f, 0f, 1f);
            GL11.glTranslated(-sx, -sy, 0);
        }
    }

    /** New region textures created per frame; the rest pop in over the next frames instead of one long stall. */
    private static final int NEW_TEXTURES_PER_FRAME = 4;
    private static final int NEW_LOD_TEXTURES_PER_FRAME = 32;

    /**
     * Whether the map is drawn from reduced region copies (one pixel per 4x4 blocks): when a block is at most half a
     * screen pixel wide the full resolution can't be seen anyway, and it would need far more memory.
     */
    public static boolean useLod(double scale) {
        int factor = ScaledScreen.currentFactor();
        return scale * factor <= 0.5;
    }

    /**
     * Draws the map into the screen rectangle ({@code x}, {@code y}, {@code width}, {@code height}).
     *
     * @param centerX world X shown at the center of the rectangle
     * @param centerZ world Z shown at the center of the rectangle
     * @param scale   screen pixels per block
     */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height) {
        drawMap(dimension, centerX, centerZ, scale, x, y, width, height, false);
    }

    /** @param minimap drawn by the minimap (kept apart in the flat map log) */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, boolean minimap) {
        long frameStart = System.nanoTime();
        int drawnCount = 0, loadingCount = 0, missingCount = 0, textureLimited = 0;
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
        // Dark caves are drawn as at night with their own tint: only torches and other lights show them.
        boolean darkCave = isDarkCave(dimension);
        float night = darkCave ? 1f : nightAmount(Minecraft.getMinecraft());
        float[] tint = darkCave ? CAVE_TINT : tint(night);
        GL11.glColor4f(tint[0], tint[1], tint[2], 1f);

        Tessellator tessellator = Tessellator.instance;
        boolean lod = useLod(scale);
        int newTextures = lod ? NEW_LOD_TEXTURES_PER_FRAME : NEW_TEXTURES_PER_FRAME;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                // Regions on disk are read in the background; they pop in once loaded.
                MapRegion region = null;
                LodTile tile = null;
                if (lod) {
                    tile = dimension.requestLod(rx, rz);
                    if (tile == null) {
                        if (dimension.isKnownMissing(rx, rz)) {
                            missingCount++;
                        } else {
                            loadingCount++;
                        }
                        continue;
                    }
                    if (!tile.hasTexture() && newTextures-- <= 0) {
                        textureLimited++;
                        continue;
                    }
                } else {
                    region = dimension.requestRegion(rx, rz);
                    if (region == null) {
                        if (dimension.isKnownMissing(rx, rz)) {
                            missingCount++;
                        } else {
                            loadingCount++;
                        }
                        continue;
                    }
                    if (!region.hasTexture() && newTextures-- <= 0) {
                        textureLimited++;
                        continue;
                    }
                }
                drawnCount++;

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

                if (lod) {
                    tile.bindTexture();
                } else {
                    region.bindTexture();
                }
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
                if (!lod && night > 0.01f && region.hasLight()) {
                    // At night, torches and lamps light up the map around them.
                    region.bindGlowTexture();
                    GL11.glColor4f(1f, 1f, 1f, night);
                    tessellator.startDrawingQuads();
                    tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                    tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                    tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                    tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                    tessellator.draw();
                    GL11.glColor4f(tint[0], tint[1], tint[2], 1f);
                }
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
        if (FlatLog.on()) {
            FlatLog.frame(
                minimap,
                lod,
                (rx1 - rx0 + 1) * (rz1 - rz0 + 1),
                drawnCount,
                loadingCount,
                missingCount,
                textureLimited,
                System.nanoTime() - frameStart);
        }
    }

    /**
     * Draws chunk borders (every 16 blocks) and region borders (every 512 blocks) over the map rectangle. Chunk lines
     * are left out when zoomed out so far that they would be closer than a few pixels. Every pixel of the grid is
     * drawn once: where lines cross, see-through lines drawn over each other made the crossings brighter.
     */
    public static void drawChunkGrid(double centerX, double centerZ, double scale, int x, int y, int width,
        int height) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        boolean chunks = 16 * scale >= 6;
        int step = chunks ? 16 : MapRegion.SIZE;

        // Lines are placed and sized in real screen pixels: snapping to GUI pixels (2-4 screen pixels each) made them
        // jump behind the smoothly moving map.
        double pixel = 1.0 / ScaledScreen.currentFactor();
        // Thick lines never fill more than half the room between two of them.
        double room = Math.max(1, Math.floor(step * scale / 2 / pixel)) * pixel;
        double thickness = Math.min(pixel * Config.gridLineWidth, room);
        int chunkLine = (Math.round(Config.gridChunkOpacity * 2.55f) << 24) | Config.gridChunkColor;
        int regionLine = (Math.round(Config.gridRegionOpacity * 2.55f) << 24) | Config.gridRegionColor;

        // Lines as {from, to, region ? 1 : 0}, left to right and top to bottom.
        List<double[]> columns = new ArrayList<>();
        int firstX = (int) Math.floor(left / step) * step;
        for (int bx = firstX;; bx += step) {
            double sx = x + (bx - left) * scale;
            if (sx > x + width) {
                break;
            }
            if (sx >= x) {
                double lx = Math.floor(sx / pixel) * pixel;
                columns.add(
                    new double[] { lx, Math.min(lx + thickness, x + width),
                        Math.floorMod(bx, MapRegion.SIZE) == 0 ? 1 : 0 });
            }
        }
        List<double[]> rows = new ArrayList<>();
        int firstZ = (int) Math.floor(top / step) * step;
        for (int bz = firstZ;; bz += step) {
            double sy = y + (bz - top) * scale;
            if (sy > y + height) {
                break;
            }
            if (sy >= y) {
                double ly = Math.floor(sy / pixel) * pixel;
                rows.add(
                    new double[] { ly, Math.min(ly + thickness, y + height),
                        Math.floorMod(bz, MapRegion.SIZE) == 0 ? 1 : 0 });
            }
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        // Columns between the rows, rows between the columns, then the crossings: a region line wins there.
        for (double[] column : columns) {
            double from = y;
            for (double[] row : rows) {
                addRect(tessellator, column[0], from, column[1], row[0], column[2] > 0 ? regionLine : chunkLine);
                from = Math.max(from, row[1]);
            }
            addRect(tessellator, column[0], from, column[1], y + height, column[2] > 0 ? regionLine : chunkLine);
        }
        for (double[] row : rows) {
            double from = x;
            for (double[] column : columns) {
                addRect(tessellator, from, row[0], column[0], row[1], row[2] > 0 ? regionLine : chunkLine);
                from = Math.max(from, column[1]);
            }
            addRect(tessellator, from, row[0], x + width, row[1], row[2] > 0 ? regionLine : chunkLine);
            for (double[] column : columns) {
                boolean region = row[2] > 0 || column[2] > 0;
                addRect(tessellator, column[0], row[0], column[1], row[1], region ? regionLine : chunkLine);
            }
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A rectangle into quads being drawn; nothing if it is empty. */
    private static void addRect(Tessellator tessellator, double x0, double y0, double x1, double y1, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    /** Map color multiplier for night. */
    private static final float[] NIGHT_TINT = { 0.28f, 0.32f, 0.5f };

    /** Map color multiplier for caves: no sun or moon down there, only torches light them up. */
    private static final float[] CAVE_TINT = { 0.2f, 0.2f, 0.23f };

    /** RGB multiplier for the map according to {@link Config#mapLightMode} and the time of day. */
    public static float[] lightTint(Minecraft mc) {
        return tint(nightAmount(mc));
    }

    private static float[] tint(float night) {
        float day = 1f - night;
        return new float[] { NIGHT_TINT[0] + (1f - NIGHT_TINT[0]) * day, NIGHT_TINT[1] + (1f - NIGHT_TINT[1]) * day,
            NIGHT_TINT[2] + (1f - NIGHT_TINT[2]) * day };
    }

    /**
     * Cave layers of dimensions with a sky are dark at any time of day, lit only where torches and other lights are
     * (unless the map is fixed to day). Dimensions without a sky, like the Nether, keep their caves lit.
     */
    private static boolean isDarkCave(MapDimension dimension) {
        return dimension.cave && Config.mapLightMode != Config.LIGHT_DAY
            && !MapManager.INSTANCE.hasNoSky(dimension.dimensionId);
    }

    /** How much the map shows night: 0 at day, 1 at night (fixed by the day/night buttons, else the sun). */
    public static float nightAmount(Minecraft mc) {
        float day;
        if (Config.mapLightMode == Config.LIGHT_DAY) {
            day = 1f;
        } else if (Config.mapLightMode == Config.LIGHT_NIGHT) {
            day = 0f;
        } else
            if (mc.theWorld == null || mc.theWorld.provider.hasNoSky || MapManager.INSTANCE.getActiveCaveLayer() >= 0) {
                // No sunlight underground: caves look the same at any time of day.
                day = 1f;
            } else {
                // Sun brightness goes from about 0.2 at midnight to 1.0 at noon.
                float sun = mc.theWorld.getSunBrightness(1f);
                day = Math.max(0f, Math.min(1f, (sun - 0.2f) / 0.8f));
            }
        return 1f - day;
    }

    /**
     * Draws the player's marker at the given screen position, pointing where the player looks, in the look, size and
     * color of the settings; {@code size} is the marker's size at 100%.
     */
    public static void drawPlayerArrow(double sx, double sy, float yaw, float size) {
        drawPlayerMarker(
            sx,
            sy,
            yaw,
            size * Config.playerMarkerScale / 100f,
            Config.playerMarkerStyle,
            0xFF000000 | Config.playerMarkerColor,
            Config.playerMarkerOutline ? 0xFF000000 | Config.playerMarkerOutlineColor : 0);
    }

    /** Draws a player marker of the given look; yaw 180 points up. An outline color of 0 draws none. */
    public static void drawPlayerMarker(double sx, double sy, float yaw, float size, int style, int color,
        int outline) {
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
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        if (outline != 0) {
            // The shape in the outline color shifted all around, then in its color on top: an even line around any
            // shape.
            tessellator.setColorRGBA_I(outline & 0xFFFFFF, (outline >>> 24) & 0xFF);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        markerShape(tessellator, style, size, dx * 0.8, dy * 0.8);
                    }
                }
            }
        }
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        markerShape(tessellator, style, size, 0, 0);
        tessellator.draw();

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static final int MARKER_SEGMENTS = 24;

    /** Triangles of a marker pointing up (-y), centered on (ox, oy). */
    private static void markerShape(Tessellator t, int style, float s, double ox, double oy) {
        switch (style) {
            case Config.MARKER_TRIANGLE:
                triangle(t, ox, oy, 0, -s, -s * 0.7, s * 0.8, s * 0.7, s * 0.8);
                break;
            case Config.MARKER_CHEVRON:
                // An arrow with a deep notch: a thin V.
                triangle(t, ox, oy, 0, -s, -s * 0.8, s * 0.85, 0, -s * 0.15);
                triangle(t, ox, oy, 0, -s, 0, -s * 0.15, s * 0.8, s * 0.85);
                break;
            case Config.MARKER_KITE:
                triangle(t, ox, oy, 0, -s, -s * 0.6, s * 0.3, 0, s * 0.8);
                triangle(t, ox, oy, 0, -s, 0, s * 0.8, s * 0.6, s * 0.3);
                break;
            case Config.MARKER_CIRCLE:
                // A disc with a nose showing the direction.
                disc(t, ox, oy, s * 0.6);
                triangle(t, ox, oy, 0, -s * 1.2, -s * 0.42, -s * 0.38, s * 0.42, -s * 0.38);
                break;
            case Config.MARKER_DOT:
                disc(t, ox, oy, s * 0.6);
                break;
            default:
                // Arrow head with a notch at the back.
                triangle(t, ox, oy, 0, -s, -s * 0.75, s, 0, s * 0.45);
                triangle(t, ox, oy, 0, -s, 0, s * 0.45, s * 0.75, s);
                break;
        }
    }

    private static void triangle(Tessellator t, double ox, double oy, double x0, double y0, double x1, double y1,
        double x2, double y2) {
        t.addVertex(ox + x0, oy + y0, 0);
        t.addVertex(ox + x1, oy + y1, 0);
        t.addVertex(ox + x2, oy + y2, 0);
    }

    private static void disc(Tessellator t, double ox, double oy, double radius) {
        for (int i = 0; i < MARKER_SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / MARKER_SEGMENTS, a1 = 2 * Math.PI * (i + 1) / MARKER_SEGMENTS;
            triangle(
                t,
                ox,
                oy,
                0,
                0,
                Math.cos(a0) * radius,
                Math.sin(a0) * radius,
                Math.cos(a1) * radius,
                Math.sin(a1) * radius);
        }
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
                // Teammates are drawn by drawTeammates, always.
                if (Config.showOtherPlayers && !TeamMates.INSTANCE.isTeammate(entity.getUniqueID())) {
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
            if (i >= firstIcon && sx >= x + half
                && sy >= y + half
                && sx <= x + width - half
                && sy <= y + height - half) {
                pushUpright(sx, sy);
                drawEntityIcon(entity, sx, sy, iconSize, color);
                GL11.glPopMatrix();
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
            pushUpright(sx, sy);
            drawPlayerHead(mc, other, sx, sy, playerSize);
            GL11.glPopMatrix();
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
        drawHead(
            mc,
            player instanceof AbstractClientPlayer ? ((AbstractClientPlayer) player).getLocationSkin() : null,
            sx,
            sy,
            size,
            0xFF000000);
    }

    /** Team color frame around teammates' heads. */
    private static final int TEAMMATE_FRAME = 0xFF4CB4FF;

    /**
     * Draws the online teammates in the given dimension (reported by the server, so even far away or in another
     * dimension than the player's), with a blue frame and, on the world map, their names.
     */
    public static void drawTeammates(Minecraft mc, int dimension, double centerX, double centerZ, double scale, int x,
        int y, int width, int height, float partialTicks, float playerSize, boolean showNames) {
        drawTeammates(
            mc,
            dimension,
            (px, py,
                pz) -> new double[] { x + width / 2.0 + (px - centerX) * scale,
                    y + height / 2.0 + (pz - centerZ) * scale },
            scale,
            x,
            y,
            width,
            height,
            partialTicks,
            playerSize,
            showNames);
    }

    /** Where a world point is drawn on the screen: {x, y}. */
    public interface Projection {

        double[] toScreen(double x, double y, double z);
    }

    /** Like the other drawTeammates, with any projection (the 3D map's). */
    public static void drawTeammates(Minecraft mc, int dimension, Projection projection, double scale, int x, int y,
        int width, int height, float partialTicks, float playerSize, boolean showNames) {
        List<TeamMates.Mate> mates = TeamMates.INSTANCE.all();
        if (mates.isEmpty()) {
            return;
        }
        float zoomFactor = (float) Math.max(0.5, Math.min(1.0, Math.pow(scale, 0.4)));
        float size = Math.max(5f, (playerSize + 1f) * zoomFactor);
        float half = size / 2f;
        FontRenderer font = mc.fontRenderer;
        for (TeamMates.Mate mate : mates) {
            if (mate.dimension != dimension) {
                continue;
            }
            double[] position = TeamMates.INSTANCE.position(mate, partialTicks);
            double[] screen = projection.toScreen(position[0], position[1], position[2]);
            double sx = screen[0], sy = screen[1];
            if (sx < x + half || sy < y + half || sx > x + width - half || sy > y + height - half) {
                continue;
            }
            ResourceLocation skin = AbstractClientPlayer.getLocationSkin(mate.name);
            // Starts the skin download for players the client hasn't seen.
            AbstractClientPlayer.getDownloadImageSkin(skin, mate.name);
            pushUpright(sx, sy);
            drawHead(mc, skin, sx, sy, size, TEAMMATE_FRAME);
            GL11.glPopMatrix();
            if (showNames) {
                font.drawStringWithShadow(
                    mate.name,
                    (int) sx - font.getStringWidth(mate.name) / 2,
                    (int) (sy + half) + 2,
                    0x9FD4FF);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void drawHead(Minecraft mc, ResourceLocation skin, double sx, double sy, float size, int frame) {
        double half = size / 2.0;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        fillRect(tessellator, sx - half - 1, sy - half - 1, sx + half + 1, sy + half + 1, frame);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (skin == null) {
            return;
        }
        mc.getTextureManager()
            .bindTexture(skin);
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
