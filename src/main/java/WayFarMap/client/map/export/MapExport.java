package WayFarMap.client.map.export;

import java.io.File;
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
 * Saves the whole map, flat or 3D, as a folder under {@code screenshots/wayfarmap/} that a browser shows zoomable
 * down to single blocks ({@link TilePyramid}). One export at a time, in the background; the chat says when it is done
 * with a link to open it.
 */
public final class MapExport {

    private static final class Job {

        final File folder;
        volatile long done, total;
        volatile boolean cancelled;
        /** Set when finished: the message for the chat. */
        volatile String result;
        volatile boolean failed;
        volatile boolean waitingForSave = true;

        Job(File folder) {
            this.folder = folder;
        }
    }

    private static volatile Job job;

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
     * @param name what the folder is called, before the date
     */
    public static void start(TilePyramid.Source source, TilePyramid.Info info, String name) {
        if (running()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String date = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        File folder = new File(new File(new File(mc.mcDataDir, "screenshots"), "wayfarmap"), safe(name) + "_" + date);
        Job current = new Job(folder);
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
            int tiles = TilePyramid.write(source, info, current.folder, new TilePyramid.Progress() {

                @Override
                public boolean cancelled() {
                    return current.cancelled;
                }

                @Override
                public void progress(long done, long total) {
                    current.total = total;
                    current.done = done;
                }
            });
            WayFarMap.LOG.info("Exported the map ({} tiles) to {}", tiles, current.folder);
            current.result = "done";
        } catch (TilePyramid.CancelledException e) {
            current.failed = true;
            current.result = "cancelled";
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not export the map", t);
            current.failed = true;
            current.result = t.getMessage() == null ? t.toString() : t.getMessage();
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
            style.setChatClickEvent(
                new ClickEvent(ClickEvent.Action.OPEN_FILE, new File(current.folder, "index.html").getAbsolutePath()));
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
