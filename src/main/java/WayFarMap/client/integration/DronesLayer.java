package WayFarMap.client.integration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.map.MapManager;
import WayFarMap.share.DroneCentres;
import WayFarMap.share.MachinePoint;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * GregTech's Drone Centre: a circle around each, where a drone downlink module connects to it, drawn like the AE2
 * coverage ({@link CoverageDrawer}).
 * <p>
 * The range follows from the drone level. A server with this mod sends the centres with it
 * ({@code share.DroneServer}); without it the client reads the level itself where GregTech tells it (5.09.52.615 on,
 * see {@link DroneCentres}). On older GregTech the client can't know it: such a centre gets only its mark, no circle.
 * Centres seen by the client are kept in {@code drones.json} of the world's map folder.
 */
public final class DronesLayer {

    public static final DronesLayer INSTANCE = new DronesLayer();

    private static final int ACTIVE_COLOR = 0x1DE9B6;
    private static final int INACTIVE_COLOR = 0xFF3030;
    private static final int UNKNOWN_COLOR = 0xB0BEC5;
    private static final int SCAN_TICKS = 20;
    private static final long SAVE_DELAY_MS = 5000;
    private static final String FILE_NAME = "drones.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap drone centres saver");
        thread.setDaemon(true);
        return thread;
    });

    private DronesLayer() {}

    /** A centre the client saw itself; {@code known} when this GregTech told it the drone level. */
    private static final class Seen {

        final int x, y, z;
        int level, range;
        boolean known, active;

        Seen(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Queue<ShareNetwork.DroneList> inbox = new ConcurrentLinkedQueue<>();
    /** From the server; null while it sent none. */
    private List<MachinePoint> fromServer;
    private final Map<Integer, Map<Long, Seen>> seen = new HashMap<>();
    private File worldDirectory;
    private boolean inWorld, dirty;
    private long dirtySince;
    private int tick;

    /** From the network thread. */
    public void receive(ShareNetwork.DroneList message) {
        inbox.add(message);
    }

    // ---------------------------------------------------------------- client tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Mods.isDronesAvailable()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            if (inWorld) {
                inWorld = false;
                saveNow();
                seen.clear();
                fromServer = null;
                worldDirectory = null;
                inbox.clear();
            }
            return;
        }
        inWorld = true;
        File directory = MapManager.INSTANCE.getWorldDirectory();
        if (directory != null && !directory.equals(worldDirectory)) {
            saveNow();
            worldDirectory = directory;
            load();
        }
        ShareNetwork.DroneList message;
        while ((message = inbox.poll()) != null) {
            fromServer = message.points;
        }
        try {
            if (++tick >= SCAN_TICKS) {
                tick = 0;
                scan(mc, mc.theWorld.provider.dimensionId);
            }
        } catch (Throwable t) {
            Mods.fail(Mods.Addon.DRONES, t);
        }
        if (dirty && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
            saveNow();
        }
    }

    /** Adds the centres in loaded chunks and drops those whose loaded chunk no longer has them. */
    private void scan(Minecraft mc, int dimension) {
        World world = mc.theWorld;
        int radius = Math.min(32, Math.max(12, mc.gameSettings.renderDistanceChunks) + 2);
        int chunkX = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int chunkZ = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        boolean known = DroneCentres.clientKnowsLevel();
        Set<Long> present = new HashSet<>();
        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                if (chunk instanceof EmptyChunk || !chunk.isChunkLoaded) {
                    continue;
                }
                for (Object o : chunk.chunkTileEntityMap.values()) {
                    TileEntity tile = (TileEntity) o;
                    Object centre = tile.isInvalid() ? null : DroneCentres.centre(tile);
                    if (centre == null) {
                        continue;
                    }
                    present.add(pack(tile.xCoord, tile.yCoord, tile.zCoord));
                    Seen point = seenAt(dimension, tile.xCoord, tile.yCoord, tile.zCoord);
                    int level = known ? DroneCentres.level(centre) : 0;
                    int range = known ? DroneCentres.range(centre) : 0;
                    boolean active = DroneCentres.isActive(tile);
                    if (point.level != level || point.range != range
                        || point.known != known
                        || point.active != active) {
                        point.level = level;
                        point.range = range;
                        point.known = known;
                        point.active = active;
                        markDirty();
                    }
                }
            }
        }
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return;
        }
        for (Iterator<Map.Entry<Long, Seen>> it = points.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<Long, Seen> entry = it.next();
            Seen point = entry.getValue();
            if (present.contains(entry.getKey()) || !isLoaded(world, point.x, point.z)) {
                continue;
            }
            if (DroneCentres.centre(world.getTileEntity(point.x, point.y, point.z)) == null) {
                it.remove();
                markDirty();
            }
        }
    }

    private static boolean isLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded;
    }

    private Seen seenAt(int dimension, int x, int y, int z) {
        Map<Long, Seen> points = seen.computeIfAbsent(dimension, d -> new HashMap<>());
        long key = pack(x, y, z);
        Seen point = points.get(key);
        if (point == null) {
            point = new Seen(x, y, z);
            points.put(key, point);
            markDirty();
        }
        return point;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
    }

    // ---------------------------------------------------------------- storage

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }

    /** The file: {@code {"centres": [{dimension, x, y, z, level, range, known, active}, ...]}}. */
    private static final class Saved {

        List<Entry> centres = new ArrayList<>();

        static final class Entry {

            int dimension, x, y, z, level, range;
            boolean known, active;
        }
    }

    private void load() {
        seen.clear();
        File file = new File(worldDirectory, FILE_NAME);
        if (file.isFile()) {
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Saved saved = GSON.fromJson(in, Saved.class);
                if (saved != null && saved.centres != null) {
                    for (Saved.Entry e : saved.centres) {
                        if (e == null) {
                            continue;
                        }
                        Seen point = seenAt(e.dimension, e.x, e.y, e.z);
                        point.level = e.level;
                        point.range = Math.max(0, e.range);
                        point.known = e.known;
                        point.active = e.active;
                    }
                }
            } catch (IOException | RuntimeException e) {
                WayFarMap.LOG.warn("Could not read " + file, e);
            }
        }
        dirty = false;
    }

    private void saveNow() {
        if (!dirty || worldDirectory == null) {
            return;
        }
        dirty = false;
        Saved saved = new Saved();
        for (Map.Entry<Integer, Map<Long, Seen>> dimension : seen.entrySet()) {
            for (Seen p : dimension.getValue()
                .values()) {
                Saved.Entry e = new Saved.Entry();
                e.dimension = dimension.getKey();
                e.x = p.x;
                e.y = p.y;
                e.z = p.z;
                e.level = p.level;
                e.range = p.range;
                e.known = p.known;
                e.active = p.active;
                saved.centres.add(e);
            }
        }
        File file = new File(worldDirectory, FILE_NAME);
        String content = GSON.toJson(saved);
        WRITER.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                out.write(content);
            } catch (IOException e) {
                WayFarMap.LOG.warn("Could not write " + file, e);
                return;
            }
            if (!(file.delete() || !file.exists()) || !tmp.renameTo(file)) {
                WayFarMap.LOG.warn("Could not replace " + file);
            }
        });
    }

    // ---------------------------------------------------------------- what is shown

    /** One centre to draw. {@code known}: its drone level is known (0 means no drone). */
    private static final class Shown {

        final int x, y, z, level;
        final double range;
        final boolean known, active;

        Shown(int x, int y, int z, int level, double range, boolean known, boolean active) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.level = level;
            this.range = range;
            this.known = known;
            this.active = active;
        }
    }

    private List<Shown> shown(int dimension) {
        List<Shown> result = new ArrayList<>();
        if (fromServer != null) {
            for (MachinePoint p : fromServer) {
                if (p.dimension == dimension) {
                    result.add(new Shown(p.x, p.y, p.z, p.tier, p.range, true, p.active));
                }
            }
            return result;
        }
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return Collections.emptyList();
        }
        for (Seen p : points.values()) {
            result.add(new Shown(p.x, p.y, p.z, p.level, p.range, p.known, p.active));
        }
        return result;
    }

    // ---------------------------------------------------------------- drawing

    private static Shown hovered;
    /** The machine whose circle the mouse is in (the smallest one, across the layers), and that circle. */
    private static Shown areaHovered;
    private static double[] areaCircle;
    private static int hoveredDimension;

    /**
     * Draws the drone centres of the dimension into the map rectangle, centered on (centerX, centerZ) at
     * {@code scale} pixels per block, and remembers the centre under the mouse (world map only).
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap, int mouseX, int mouseY) {
        if (!minimap) {
            hovered = null;
            areaHovered = null;
            areaCircle = null;
        }
        List<Shown> points = INSTANCE.shown(dimension);
        if (points.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean here = mc.theWorld != null && mc.thePlayer != null && mc.theWorld.provider.dimensionId == dimension;
        double feet = here ? mc.thePlayer.boundingBox.minY : 0;
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;

        List<CoverageDrawer.Circle> coverage = new ArrayList<>();
        List<Shown> marked = new ArrayList<>();
        List<double[]> marks = new ArrayList<>();
        for (Shown p : points) {
            double sx = x + (p.x - left) * scale;
            double sy = y + (p.z - top) * scale;
            double full = p.range * scale;
            if (sx + full + 4 < x || sy + full + 4 < y || sx - full - 4 > x + width || sy - full - 4 > y + height) {
                continue;
            }
            if (p.range > 0) {
                double slice = here ? Math.sqrt(Math.max(0, p.range * p.range - sq(p.y - feet))) * scale : full;
                coverage.add(
                    new CoverageDrawer.Circle(sx, sy, full, slice, color(p), p.active ? 48 : 40, p.active ? 1 : 0));
                if (!minimap && CoverageHover.track(
                    "drone:" + dimension + ":" + p.x + ":" + p.y + ":" + p.z,
                    sx,
                    sy,
                    Config.dronesFullRange ? full : slice,
                    mouseX,
                    mouseY)) {
                    areaHovered = p;
                    areaCircle = new double[] { sx, sy, full, slice };
                }
            }
            marked.add(p);
            marks.add(new double[] { sx, sy });
            if (!minimap && Math.abs(mouseX - sx) <= 6 && Math.abs(mouseY - sy) <= 6) {
                hovered = p;
                hoveredDimension = dimension;
            }
        }
        if (marked.isEmpty()) {
            return;
        }
        CoverageDrawer.draw(coverage, minimap, Config.dronesFullRange);
        if (areaCircle != null) {
            CoverageDrawer.highlight(
                areaCircle[0],
                areaCircle[1],
                areaCircle[2],
                areaCircle[3],
                color(areaHovered),
                Config.dronesFullRange,
                minimap);
        }

        for (int i = 0; i < marked.size(); i++) {
            double[] c = marks.get(i);
            Shown p = marked.get(i);
            boolean broken = !p.active || p.known && p.range <= 0;
            CoverageDrawer.mark(
                c[0],
                c[1],
                Icons.DRONE,
                p.known ? ACTIVE_COLOR : UNKNOWN_COLOR,
                broken,
                !minimap && (p == hovered || p == areaHovered),
                minimap);
        }
    }

    private static int color(Shown p) {
        return !p.known ? UNKNOWN_COLOR : p.active && p.range > 0 ? ACTIVE_COLOR : INACTIVE_COLOR;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---------------------------------------------------------------- tooltip

    /** Tooltip of the centre under the mouse on the world map, or null. */
    public static List<String> getHoveredTooltip() {
        Shown p = hovered;
        if (p == null) {
            p = areaHovered;
            // In its circle, not on its mark: only what it is.
            return p == null ? null : Collections.singletonList("§3" + Lang.format("wayfarmap.drones.title"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("§3" + Lang.format("wayfarmap.drones.title"));
        lines.add("§7" + p.x + ", " + p.y + ", " + p.z);
        if (!p.known) {
            lines.add("§7" + Lang.format("wayfarmap.drones.range_unknown"));
        } else if (p.level <= 0 || p.range <= 0) {
            lines.add("§7" + Lang.format("wayfarmap.drones.no_drone"));
        } else {
            lines.add("§7" + Lang.format("wayfarmap.drones.level", p.level));
            lines.add("§7" + Lang.format("wayfarmap.wireless.range", blocks(p.range)));
            if (p.level >= 4) {
                lines.add("§7" + Lang.format("wayfarmap.drones.key"));
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld != null && mc.thePlayer != null && mc.theWorld.provider.dimensionId == hoveredDimension) {
                double dy = p.y - mc.thePlayer.boundingBox.minY;
                double slice = Math.sqrt(Math.max(0, p.range * p.range - dy * dy));
                lines.add(
                    "§7" + (slice > 0 ? Lang.format("wayfarmap.wireless.at_height", blocks(slice))
                        : Lang.format("wayfarmap.wireless.out_of_height")));
            }
        }
        lines.add(
            p.active ? "§a" + Lang.format("wayfarmap.wireless.active")
                : "§c" + Lang.format("wayfarmap.drones.inactive"));
        if (INSTANCE.fromServer == null) {
            lines.add("§8" + Lang.format("wayfarmap.drones.local"));
        }
        return lines;
    }

    private static String blocks(double range) {
        return String.format(Locale.ROOT, "%,d", (long) Math.rint(range))
            .replace(',', ' ');
    }
}
