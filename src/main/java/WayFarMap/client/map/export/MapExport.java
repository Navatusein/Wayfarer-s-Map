package WayFarMap.client.map.export;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import WayFarMap.WayFarMap;
import WayFarMap.client.map.MapManager;

/**
 * Saves the whole map, flat or 3D, under {@code screenshots/wayfarmap/}: as one picture, or as a folder that a browser
 * shows zoomable down to single blocks ({@link TilePyramid}). One export at a time, in the background; the chat says
 * when it is done with a link to open it.
 */
public final class MapExport {

    private static final class Job {

        /** The picture, or the folder of the page. */
        final File folder;
        final boolean site;
        volatile long done, total;
        volatile boolean cancelled;
        /** Set when finished: the message for the chat. */
        volatile String result;
        volatile boolean failed;
        volatile boolean waitingForSave = true;

        Job(File folder, boolean site) {
            this.folder = folder;
            this.site = site;
        }
    }

    private static volatile Job job;
    /** Exports finished so far, so a screen showing the pictures knows when to look again. */
    private static volatile int finished;

    private MapExport() {}

    /** Whether an export is running. */
    public static boolean running() {
        Job current = job;
        return current != null && current.result == null;
    }

    /** Progress of the running export for the map screen, or null if none is running. */
    public static String statusText() {
        Job current = job;
        if (current == null || current.result != null) {
            return null;
        }
        if (current.waitingForSave || current.total <= 0) {
            return I18n.format("wayfarmap.export.preparing");
        }
        long percent = Math.min(99, current.done * 100 / Math.max(1, current.total));
        return I18n.format("wayfarmap.export.progress", percent);
    }

    /** How far the running export is, 0 to 1; -1 while it prepares or when none runs. */
    public static double progress() {
        Job current = job;
        if (current == null || current.result != null || current.waitingForSave || current.total <= 0) {
            return -1;
        }
        return Math.min(0.99, current.done / (double) current.total);
    }

    /** How many exports have finished (well or not) since the game started. */
    public static int finished() {
        return finished;
    }

    /** Where the pictures go: {@code screenshots/wayfarmap}, only the mod's. */
    public static File folder() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "screenshots"), "wayfarmap");
    }

    /** Stops the running export; what was written so far stays. */
    public static void cancel() {
        Job current = job;
        if (current != null) {
            current.cancelled = true;
        }
    }

    /**
     * Starts exporting (render thread). The map is saved first, so the files have everything.
     *
     * @param name what the picture or the folder is called, before the date
     * @param site the page that zooms in a browser (a folder); false for one picture
     */
    public static void start(TilePyramid.Source source, TilePyramid.Info info, String name, boolean site) {
        if (running()) {
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        File folder = new File(folder(), safe(name) + "_" + date + (site ? "" : ".png"));
        Job current = new Job(folder, site);
        job = current;
        List<Future<?>> saving = MapManager.INSTANCE.saveAll();
        Thread thread = new Thread(() -> run(current, source, info, saving), "WayFarMap export");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    private static void run(Job current, TilePyramid.Source source, TilePyramid.Info info, List<Future<?>> saving) {
        try {
            for (Future<?> future : saving) {
                future.get();
            }
            current.waitingForSave = false;
            TilePyramid.Progress progress = new TilePyramid.Progress() {

                @Override
                public boolean cancelled() {
                    return current.cancelled;
                }

                @Override
                public void progress(long done, long total) {
                    current.total = total;
                    current.done = done;
                }
            };
            int tiles;
            if (current.site) {
                tiles = TilePyramid.write(source, info, current.folder, progress);
            } else {
                File parent = current.folder.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent);
                }
                tiles = TilePyramid.writePicture(source, current.folder, progress);
            }
            WayFarMap.LOG.info("Exported the map ({} tiles) to {}", tiles, current.folder);
            current.result = "done";
        } catch (TilePyramid.CancelledException e) {
            current.failed = true;
            current.result = "cancelled";
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not export the map", t);
            current.failed = true;
            current.result = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            finished++;
        }
    }

    /** Tells in the chat when an export ended (render thread, every tick). */
    public static void tick() {
        Job current = job;
        if (current == null || current.result == null) {
            return;
        }
        job = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        ChatComponentText message;
        if (!current.failed) {
            message = new ChatComponentText(I18n.format("wayfarmap.export.done") + " ");
            ChatComponentText link = new ChatComponentText(current.folder.getName());
            ChatStyle style = new ChatStyle();
            style.setUnderlined(true);
            style.setColor(EnumChatFormatting.AQUA);
            File open = current.site ? new File(current.folder, "index.html") : current.folder;
            style.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, open.getAbsolutePath()));
            link.setChatStyle(style);
            message.appendSibling(link);
        } else if ("cancelled".equals(current.result)) {
            message = new ChatComponentText(I18n.format("wayfarmap.export.cancelled"));
        } else {
            message = new ChatComponentText(
                EnumChatFormatting.RED + I18n.format("wayfarmap.export.failed", current.result));
        }
        mc.thePlayer.addChatMessage(message);
    }

    /** A file name from any text. */
    private static String safe(String name) {
        String s = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        return s.isEmpty() ? "map" : s.length() > 60 ? s.substring(0, 60) : s;
    }
}
