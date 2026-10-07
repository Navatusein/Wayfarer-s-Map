package WayFarMap.client.map.export;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * shows zoomable down to single blocks ({@link TilePyramid}). One export at a time, in the background (it may be
 * several pictures, made one after the other); the chat says when each is done with a link to open it.
 */
public final class MapExport {

    /** One picture to make: what is drawn, the page's details and what it is called (before the date). */
    public static final class Request {

        final TilePyramid.Source source;
        final TilePyramid.Info info;
        final String name;
        /** Shown while it is being made, e.g. "Topography". */
        final String label;

        public Request(TilePyramid.Source source, TilePyramid.Info info, String name, String label) {
            this.source = source;
            this.info = info;
            this.name = name;
            this.label = label;
        }
    }

    private static final class Job {

        /** The picture, or the folder of the page. */
        final File folder;
        final boolean site;
        final String label;
        volatile long done, total;
        /** Set when finished: the message for the chat. */
        volatile String result;
        volatile boolean failed;

        Job(File folder, boolean site, String label) {
            this.folder = folder;
            this.site = site;
            this.label = label;
        }
    }

    /** The pictures asked for at once, made one after the other. */
    private static final class Batch {

        final int size;
        volatile int index;
        volatile Job job;
        volatile boolean cancelled;
        volatile boolean waitingForSave = true;
        volatile boolean over;

        Batch(int size) {
            this.size = size;
        }
    }

    private static volatile Batch batch;
    /** Pictures that ended, for the chat. */
    private static final Queue<Job> ENDED = new ConcurrentLinkedQueue<>();
    /** Exports finished so far, so a screen showing the pictures knows when to look again. */
    private static volatile int finished;

    private MapExport() {}

    /** Whether an export is running. */
    public static boolean running() {
        Batch current = batch;
        return current != null && !current.over;
    }

    /** Progress of the running export for the map screen, or null if none is running. */
    public static String statusText() {
        Batch current = batch;
        if (current == null || current.over) {
            return null;
        }
        Job job = current.job;
        if (current.waitingForSave || job == null || job.total <= 0) {
            return I18n.format("wayfarmap.export.preparing");
        }
        long percent = Math.min(99, job.done * 100 / Math.max(1, job.total));
        if (current.size > 1) {
            return I18n.format("wayfarmap.export.progress_of", current.index + 1, current.size, percent);
        }
        return I18n.format("wayfarmap.export.progress", percent);
    }

    /** How far the running export is, all its pictures together, 0 to 1; -1 while it prepares or none runs. */
    public static double progress() {
        Batch current = batch;
        if (current == null || current.over || current.waitingForSave) {
            return -1;
        }
        Job job = current.job;
        double part = job == null || job.total <= 0 ? 0 : Math.min(0.99, job.done / (double) job.total);
        return Math.min(0.99, (current.index + part) / current.size);
    }

    /** How far the picture being made now is, 0 to 1; -1 while it prepares or none runs. */
    public static double pictureProgress() {
        Batch current = batch;
        Job job = current == null || current.over ? null : current.job;
        if (job == null || current.waitingForSave || job.total <= 0) {
            return -1;
        }
        return Math.min(0.99, job.done / (double) job.total);
    }

    /** Which picture of the running export is being made (from 0) and of how many; null if none runs. */
    public static int[] position() {
        Batch current = batch;
        return current == null || current.over ? null : new int[] { current.index, current.size };
    }

    /** What the picture being made now shows, or null. */
    public static String currentLabel() {
        Batch current = batch;
        Job job = current == null || current.over ? null : current.job;
        return job == null ? null : job.label;
    }

    /** How many exports have finished (well or not) since the game started. */
    public static int finished() {
        return finished;
    }

    /** Where the pictures go: {@code screenshots/wayfarmap}, only the mod's. */
    public static File folder() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "screenshots"), "wayfarmap");
    }

    /** Stops the running export, and the pictures still waiting; what was written so far stays. */
    public static void cancel() {
        Batch current = batch;
        if (current != null) {
            current.cancelled = true;
        }
    }

    /**
     * Starts exporting (render thread). The map is saved first, so the files have everything.
     *
     * @param site the page that zooms in a browser (a folder); false for one picture
     */
    public static void start(TilePyramid.Source source, TilePyramid.Info info, String name, boolean site) {
        start(Collections.singletonList(new Request(source, info, name, null)), site);
    }

    /**
     * Starts exporting the pictures one after the other (render thread), all with the same date in their names. The
     * map is saved first, so the files have everything.
     *
     * @param site pages that zoom in a browser (folders); false for single pictures
     */
    public static void start(List<Request> requests, boolean site) {
        if (running() || requests.isEmpty()) {
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        Batch current = new Batch(requests.size());
        batch = current;
        List<Future<?>> saving = MapManager.INSTANCE.saveAll();
        List<Request> copy = new ArrayList<>(requests);
        Thread thread = new Thread(() -> run(current, copy, date, site, saving), "WayFarMap export");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    private static void run(Batch current, List<Request> requests, String date, boolean site, List<Future<?>> saving) {
        try {
            for (Future<?> future : saving) {
                future.get();
            }
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not save the map before exporting it", t);
        }
        current.waitingForSave = false;
        try {
            for (int i = 0; i < requests.size() && !current.cancelled; i++) {
                Request request = requests.get(i);
                File folder = new File(folder(), safe(request.name) + "_" + date + (site ? "" : ".png"));
                Job job = new Job(folder, site, request.label);
                current.index = i;
                current.job = job;
                write(current, job, request);
                ENDED.add(job);
                finished++;
            }
        } finally {
            current.over = true;
        }
    }

    private static void write(Batch current, Job job, Request request) {
        try {
            TilePyramid.Progress progress = new TilePyramid.Progress() {

                @Override
                public boolean cancelled() {
                    return current.cancelled;
                }

                @Override
                public void progress(long done, long total) {
                    job.total = total;
                    job.done = done;
                }
            };
            int tiles;
            if (job.site) {
                tiles = TilePyramid.write(request.source, request.info, job.folder, progress);
            } else {
                File parent = job.folder.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent);
                }
                tiles = TilePyramid.writePicture(request.source, job.folder, progress);
            }
            WayFarMap.LOG.info("Exported the map ({} tiles) to {}", tiles, job.folder);
            job.result = "done";
        } catch (TilePyramid.CancelledException e) {
            job.failed = true;
            job.result = "cancelled";
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not export the map", t);
            job.failed = true;
            job.result = t.getMessage() == null ? t.toString() : t.getMessage();
        }
    }

    /** Tells in the chat when an export ended (render thread, every tick). */
    public static void tick() {
        Batch running = batch;
        if (running != null && running.over && ENDED.isEmpty()) {
            batch = null;
        }
        Job current;
        while ((current = ENDED.poll()) != null) {
            report(current);
        }
    }

    private static void report(Job current) {
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
