package WayFarMap.client.map;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import WayFarMap.Config;
import WayFarMap.WayFarMap;

/**
 * A detailed log of the flat (2D) map, for finding what is slow or wrong: every chunk from the scanner finding it to
 * its pixels on screen (seen, settled, scanned or put off), every region read from disk (asked for, read, picked up,
 * or read in the way of the game), made, reduced for zooming out, saved and let go, textures made and uploaded, what
 * each frame of the map drew, teammates' chunks written. Statistics every second while busy, summaries every 5
 * minutes and when the world is left. Written to {@code .minecraft/wayfarmap/logs/2d-*.log} by a thread of its own.
 * Only while {@link Config#log2d} is on.
 */
public final class FlatLog {

    private static final int FILES_KEPT = 8;
    /** Past this the detailed lines stop (the statistics and summaries go on). */
    private static final long MAX_BYTES = 200L << 20;
    private static final int MAX_SAMPLES = 200_000;

    private static final long START = System.nanoTime();
    private static final LinkedBlockingQueue<String> LINES = new LinkedBlockingQueue<>();
    private static volatile File file;
    private static volatile boolean detailsStopped;
    private static final AtomicLong written = new AtomicLong();
    private static Thread thread;

    /** Chunks seen by the scanner and not scanned yet: when first seen. */
    private static final Map<Long, Long> SEEN = new ConcurrentHashMap<>();
    /**
     * Chunks waiting to settle: {surface signature last seen, times marked changed, of those with the surface really
     * changed}. Tells the game's "changed" marks that change nothing seen from above (light, water) from real ones.
     */
    private static final Map<Long, long[]> SETTLING = new ConcurrentHashMap<>();
    /** Surface signature of each chunk when last scanned, to tell whether a scan could change anything. */
    private static final Map<Long, Long> SCANNED_SIGNATURE = new ConcurrentHashMap<>();
    /** Chunks the game loaded and the scanner hasn't seen yet: when loaded. */
    private static final Map<Long, Long> LOADED = new ConcurrentHashMap<>();
    /** What each chunk looked like from above when the scanner first saw it (see {@link ArrivalCheck}). */
    private static final Map<Long, ArrivalCheck.Snapshot> ARRIVED = new ConcurrentHashMap<>();
    /**
     * For tuning how long new chunks wait before being mapped: when each chunk was first seen, and what it looked
     * like from above at its last look (seen, marked changed, scanned), until the game lets it go.
     */
    private static final Map<Long, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    private static final Map<Long, ArrivalCheck.Snapshot> LAST_LOOK = new ConcurrentHashMap<>();
    /** Chunks put off while their region is read: when first. */
    private static final Map<Long, Long> DEFERRED = new ConcurrentHashMap<>();
    /**
     * Lines for the next log file, written while no log was open (a cleaning closes the maps and the log, and
     * deletes the files before the next one opens).
     */
    private static final List<String> PENDING = Collections.synchronizedList(new ArrayList<>());
    /** Regions asked to be read: when (per map folder and region). */
    private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();

    /** Samples for the summaries, in nanoseconds. */
    private static final String[] SAMPLE_NAMES = { "seen->scanned (new chunks)", "scan of a chunk",
        "region read from disk", "region asked->picked up", "region read in the game's way (blocking)",
        "region save (writing)", "region save (copying, render thread)", "texture upload", "reduced copy built",
        "frame of the world map (flat)", "scan queue built", "put off->scanned (region read)",
        "map tick (render thread, 2D+3D)", "game hitch (gap between ticks)", "seen->settled (quiet+neighbours)",
        "seen->settled (timeout)", "chunk loaded->seen by the scanner", "region read: PNG decode",
        "region save: PNG encode", "region read: waiting for a loader thread", "region save: waiting for the saver",
        "region changes unsaved (changed->copied)", "autosave round (render thread)",
        "leaving: saving everything (waited)" };
    static final int SEEN_TO_SCANNED = 0, SCAN = 1, READ = 2, ASKED_TO_PICKED = 3, BLOCKING_READ = 4, SAVE_WRITE = 5,
        SAVE_COPY = 6, UPLOAD = 7, LOD_BUILD = 8, FRAME = 9, QUEUE_BUILD = 10, DEFER_TO_SCAN = 11, MAP_TICK = 12,
        HITCH = 13, SETTLE_QUIET = 14, SETTLE_TIMEOUT = 15, LOAD_TO_SEEN = 16, PNG_DECODE = 17, PNG_ENCODE = 18,
        READ_QUEUE = 19, SAVE_QUEUE = 20, UNSAVED = 21, AUTOSAVE = 22, CLOSE_SAVE = 23;
    @SuppressWarnings("unchecked")
    private static final List<Long>[] SAMPLES = new List[SAMPLE_NAMES.length];
    static {
        for (int i = 0; i < SAMPLES.length; i++) {
            SAMPLES[i] = Collections.synchronizedList(new ArrayList<>());
        }
    }

    // Counters since the last statistics line.
    private static final AtomicLong scans = new AtomicLong(), scanNanos = new AtomicLong(),
        scansDeferred = new AtomicLong(), unloadScans = new AtomicLong(), readsAsked = new AtomicLong(),
        readsDone = new AtomicLong(), readNanos = new AtomicLong(), readBytes = new AtomicLong(),
        blockingReads = new AtomicLong(), blockingNanos = new AtomicLong(), regionsMade = new AtomicLong(),
        lodBuilt = new AtomicLong(), lodNanos = new AtomicLong(), saves = new AtomicLong(),
        saveNanos = new AtomicLong(), saveBytes = new AtomicLong(), uploads = new AtomicLong(),
        uploadNanos = new AtomicLong(), uploadPixels = new AtomicLong(), texturesMade = new AtomicLong(),
        frames = new AtomicLong(), frameNanos = new AtomicLong(), frameMaxNanos = new AtomicLong(),
        drawn = new AtomicLong(), notLoaded = new AtomicLong(), noFile = new AtomicLong(),
        textureWait = new AtomicLong(), shared = new AtomicLong(), sharedOlder = new AtomicLong(),
        sharedWaiting = new AtomicLong(), scansNoPixels = new AtomicLong(), scansSameSurface = new AtomicLong(),
        marksNoise = new AtomicLong(), marksReal = new AtomicLong(), settleTimeouts = new AtomicLong(),
        settleTimeoutsEdge = new AtomicLong(), hitches = new AtomicLong(), hitchNanos = new AtomicLong(),
        mapTickNanos = new AtomicLong(), mapTickMaxNanos = new AtomicLong(), readProblems = new AtomicLong(),
        saveProblems = new AtomicLong();
    /** All of them, to start a new file from zero (saves of the world left would count in the next one). */
    private static final AtomicLong[] COUNTERS = { scans, scanNanos, scansDeferred, unloadScans, readsAsked, readsDone,
        readNanos, readBytes, blockingReads, blockingNanos, regionsMade, lodBuilt, lodNanos, saves, saveNanos,
        saveBytes, uploads, uploadNanos, uploadPixels, texturesMade, frames, frameNanos, frameMaxNanos, drawn,
        notLoaded, noFile, textureWait, shared, sharedOlder, sharedWaiting, scansNoPixels, scansSameSurface, marksNoise,
        marksReal, settleTimeouts, settleTimeoutsEdge, hitches, hitchNanos, mapTickNanos, mapTickMaxNanos, readProblems,
        saveProblems };
    /** Problems with region files since the log started, per kind (READ_WARN, SAVE_FAILED...), for the summaries. */
    private static final Map<String, AtomicLong> PROBLEMS = new ConcurrentHashMap<>();
    private static long lastStats, lastSummary;
    private static final AtomicLong mapTickCount = new AtomicLong();

    private FlatLog() {}

    public static boolean on() {
        return Config.log2d && file != null;
    }

    // ---------------------------------------------------------------- file

    /** A world was joined: a new log file in {@code dataDirectory/wayfarmap/logs}. */
    public static synchronized void open(File dataDirectory, File worldDirectory, int dimension) {
        close();
        if (!Config.log2d) {
            return;
        }
        File directory = new File(new File(dataDirectory, "wayfarmap"), "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        File[] old = directory.listFiles((d, name) -> name.startsWith("2d-") && name.endsWith(".log"));
        if (old != null && old.length >= FILES_KEPT) {
            Arrays.sort(
                old,
                (a, b) -> a.getName()
                    .compareTo(b.getName()));
            for (int i = 0; i <= old.length - FILES_KEPT; i++) {
                old[i].delete();
            }
        }
        File target = new File(
            directory,
            "2d-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".log");
        BufferedWriter out;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8));
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not create the flat map log " + target, e);
            return;
        }
        file = target;
        detailsStopped = false;
        written.set(0);
        LINES.clear();
        for (List<Long> samples : SAMPLES) {
            samples.clear();
        }
        SEEN.clear();
        FIRST_SEEN.clear();
        LAST_LOOK.clear();
        ASKED.clear();
        PROBLEMS.clear();
        SETTLING.clear();
        SCANNED_SIGNATURE.clear();
        DEFERRED.clear();
        LOADED.clear();
        ARRIVED.clear();
        for (AtomicLong counter : ARRIVAL_COUNTERS) {
            counter.set(0);
        }
        lastPlayerAt = 0;
        mapTickCount.set(0);
        mapTickIsoNanos.set(0);
        mapTickIsoMaxNanos.set(0);
        mapTickUnloadNanos.set(0);
        mapTickUnloadMaxNanos.set(0);
        for (AtomicLong counter : COUNTERS) {
            counter.set(0);
        }
        lastSummary = System.currentTimeMillis();
        Thread writer = new Thread(() -> writeLines(out), "WayFarMap 2D log");
        writer.setDaemon(true);
        writer.start();
        thread = writer;
        WayFarMap.LOG.info("Flat map log: {}", target);
        Runtime runtime = Runtime.getRuntime();
        line(
            "START world=" + worldDirectory
                + " dimension="
                + dimension
                + " cpus="
                + runtime.availableProcessors()
                + " maxMemMB="
                + (runtime.maxMemory() >> 20)
                + " chunksScannedPerTick="
                + Config.chunksScannedPerTick
                + " autosaveSec="
                + Config.autosaveIntervalSeconds
                + " java="
                + System.getProperty("java.version"));
        line(
            "LEGEND times in ms since the log started; [thread]. A chunk: SEEN (loaded, waiting to be whole) -> "
                + "SETTLED -> SCAN (pixels into its region; SCAN_DEFERRED while the region is read) -> the region's "
                + "texture UPLOADed when the map draws it. Regions (map folder, r.X.Z): READ_ASKED -> READ (loader "
                + "thread: file read) -> PICKED (in memory); BLOCKING_READ = read by the game's thread, which waits; "
                + "MADE = new, no file; LOD = reduced copy for zooming out; SAVE (saver thread) after a COPY on the "
                + "game's thread; RETAIN = let go. DRAW every second while the map is open. STATS every second while "
                + "busy, SUMMARY every minute and at the end. surface=same/changed: whether what is seen from above "
                + "(heights and top blocks) changed since the chunk was last scanned; MARKS noise/real: the game "
                + "marked a chunk changed and the surface did not / did change; HITCH: the game stood still between "
                + "two ticks, with the map's own time in the tick before. ARRIVAL: a chunk first mapped compared "
                + "with how it looked from above when first seen (complete = nothing changed while it waited; snow, "
                + "ice, tree added after; whether snow and ice predicted from the biome's temperature were right). "
                + "GONE_UNSEEN: loaded and let go before the scanner saw it. RELOADED: a mapped chunk loaded again "
                + "(came back to it, or the server sent it again), mapped again as new. CHANGE / NEIGHBOURS / "
                + "LET_GO / MARK (times from when the chunk was first seen): what of its surface changed at each "
                + "look, when its 8 neighbours were all loaded, when the game let it go, each changed mark while "
                + "waiting. "
                + "chunksNotYetSentInView: "
                + "chunks within "
                + "the view distance the server hasn't sent (yet).");
        synchronized (PENDING) {
            for (String pending : PENDING) {
                line(pending + " (while no log was open, before this one)");
            }
            PENDING.clear();
        }
        line(
            "LEGEND region files: READ and SAVE list each part of the region (png = the map picture, dat = heights, "
                + "light = night glow, time = when each chunk was mapped) with its time and size on disk; png "
                + "shows the PNG decode / encode and the pixel copy apart (imageType other than INT_ARGB makes the "
                + "copy slow), ratio = size on disk against the raw data; moves = files put in place atomically or "
                + "in two steps; content = chunks explored, with times, from teammates, heights and light kept. "
                + "READ: for = what asked for it, queuedMs = waiting for a loader thread, loaderBacklog = reads "
                + "queued or running; PARTIAL = read with a part lost. SAVE: queuedMs = waiting for the saver, "
                + "saverBacklogWhenQueued, unsavedForMs = how long its changes waited for this save. Problems get "
                + "lines of their own: READ_WARN / READ_FAILED / SAVE_WARN / SAVE_FAILED, counted in the SUMMARY. "
                + "AUTOSAVE / CLOSE_SAVE: a round of saves of every map, and saving all when the world is left.");
    }

    /** The world was left: the summary, then the file is closed. */
    public static synchronized void close() {
        Thread writer = thread;
        if (writer == null) {
            return;
        }
        summary("END");
        LINES.add("\u0000");
        try {
            writer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        thread = null;
        file = null;
    }

    private static void writeLines(BufferedWriter out) {
        try {
            while (true) {
                String line = LINES.poll(500, TimeUnit.MILLISECONDS);
                if (line == null) {
                    out.flush();
                    continue;
                }
                if (line.equals("\u0000")) {
                    break;
                }
                out.write(line);
                out.newLine();
            }
        } catch (IOException | InterruptedException e) {
            WayFarMap.LOG.warn("Flat map log stopped", e);
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {}
        }
    }

    static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1e6);
    }

    private static void line(String text) {
        String line = String.format(Locale.ROOT, "%10.1f", (System.nanoTime() - START) / 1e6) + " ["
            + Thread.currentThread()
                .getName()
            + "] "
            + text;
        boolean summaryLine = text.startsWith("STATS") || text.startsWith("SUMMARY")
            || text.startsWith("END")
            || text.startsWith("DRAW")
            || text.startsWith("READ_WARN")
            || text.startsWith("READ_FAILED")
            || text.startsWith("SAVE_WARN")
            || text.startsWith("SAVE_FAILED")
            || text.startsWith("CLOSE_SAVE");
        if (written.addAndGet(line.length() + 1) > MAX_BYTES && !summaryLine) {
            if (!detailsStopped) {
                detailsStopped = true;
                LINES.add("LOG over " + (MAX_BYTES >> 20) + " MB: detailed lines stop here");
            }
            return;
        }
        LINES.add(line);
    }

    public static void log(String text) {
        if (on()) {
            line(text);
        }
    }

    /** A line written now, or at the start of the next log file if none is open (with the log on). */
    public static void note(String text) {
        if (on()) {
            line(text);
        } else if (Config.log2d && PENDING.size() < 50) {
            PENDING.add(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + text);
        }
    }

    private static void sample(int which, long nanos) {
        List<Long> samples = SAMPLES[which];
        if (samples.size() < MAX_SAMPLES) {
            samples.add(nanos);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- the scanner (render thread)

    /**
     * @param where distance from the player and whether it is at the edge of the loaded area
     */
    static void seen(int cx, int cz, String missing, String where, long signature) {
        if (on() && SEEN.putIfAbsent(key(cx, cz), System.nanoTime()) == null) {
            if (SEEN.size() > 50_000) {
                SEEN.clear();
                SETTLING.clear();
            }
            SETTLING.put(key(cx, cz), new long[] { signature, 0, 0 });
            Long loadedAt = LOADED.remove(key(cx, cz));
            String sinceLoad = "";
            if (loadedAt != null) {
                long delay = System.nanoTime() - loadedAt;
                sample(LOAD_TO_SEEN, delay);
                sinceLoad = " loadedMsAgo=" + ms(delay);
            }
            line(
                "SEEN " + cx
                    + ","
                    + cz
                    + " "
                    + where
                    + sinceLoad
                    + (missing.isEmpty() ? " neighboursReady=true" : " missingNeighbours=" + missing));
        }
    }

    /** The game loaded a chunk (its blocks come right after). */
    static void loaded(int cx, int cz) {
        if (on()) {
            chunksLoaded.incrementAndGet();
            LOADED.put(key(cx, cz), System.nanoTime());
            if (LOADED.size() > 50_000) {
                LOADED.clear();
            }
        }
    }

    /** The game let go of a chunk; one the scanner never saw is counted and logged. */
    static void unloaded(int cx, int cz) {
        if (!on()) {
            return;
        }
        chunksUnloaded.incrementAndGet();
        ARRIVED.remove(key(cx, cz));
        LAST_LOOK.remove(key(cx, cz));
        Long firstSeen = FIRST_SEEN.remove(key(cx, cz));
        if (firstSeen != null) {
            line("LET_GO " + cx + "," + cz + " +" + ms(System.nanoTime() - firstSeen));
        }
        Long loadedAt = LOADED.remove(key(cx, cz));
        if (loadedAt != null) {
            goneUnseen.incrementAndGet();
            line(
                "GONE_UNSEEN " + cx + "," + cz + " loaded " + ms(System.nanoTime() - loadedAt) + " ms ago, never seen");
        }
    }

    /** What the chunk looked like from above when the scanner first saw it. */
    static void arrived(int cx, int cz, ArrivalCheck.Snapshot snapshot) {
        if (on()) {
            ARRIVED.put(key(cx, cz), snapshot);
            if (ARRIVED.size() > 50_000) {
                ARRIVED.clear();
            }
        }
    }

    /** The snapshot taken when the chunk arrived, if any (once). */
    static ArrivalCheck.Snapshot takeArrival(int cx, int cz) {
        return on() ? ARRIVED.remove(key(cx, cz)) : null;
    }

    /**
     * A chunk was first mapped: how it changed from when it arrived.
     *
     * @param counts see {@link ArrivalCheck#compare}
     */
    static void arrival(int cx, int cz, int[] counts, long sinceArrived, String why) {
        if (!on()) {
            return;
        }
        if (counts[0] == 0) {
            arrivedComplete.incrementAndGet();
        } else {
            arrivedIncomplete.incrementAndGet();
        }
        columnsChanged.addAndGet(counts[0]);
        snowAdded.addAndGet(counts[1]);
        iceAdded.addAndGet(counts[2]);
        treeAdded.addAndGet(counts[3]);
        otherAdded.addAndGet(counts[4]);
        coldHit.addAndGet(counts[5]);
        coldMissed.addAndGet(counts[6]);
        coldWrong.addAndGet(counts[7]);
        line(
            "ARRIVAL " + cx
                + ","
                + cz
                + (counts[0] == 0 ? " complete" : " changedColumns=" + counts[0])
                + (counts[1] > 0 ? " snowAdded=" + counts[1] : "")
                + (counts[2] > 0 ? " iceAdded=" + counts[2] : "")
                + (counts[3] > 0 ? " treeAdded=" + counts[3] : "")
                + (counts[4] > 0 ? " otherChanged=" + counts[4] : "")
                + (counts[5] + counts[6] + counts[7] > 0
                    ? " snowIcePredicted[right=" + counts[5] + " missed=" + counts[6] + " wrong=" + counts[7] + "]"
                    : "")
                + " afterMs="
                + ms(sinceArrived)
                + " scan="
                + why);
    }

    private static final AtomicLong chunksLoaded = new AtomicLong(), chunksUnloaded = new AtomicLong(),
        goneUnseen = new AtomicLong(), arrivedComplete = new AtomicLong(), arrivedIncomplete = new AtomicLong(),
        columnsChanged = new AtomicLong(), snowAdded = new AtomicLong(), iceAdded = new AtomicLong(),
        treeAdded = new AtomicLong(), otherAdded = new AtomicLong(), coldHit = new AtomicLong(),
        coldMissed = new AtomicLong(), coldWrong = new AtomicLong();
    private static final AtomicLong[] ARRIVAL_COUNTERS = { chunksLoaded, chunksUnloaded, goneUnseen, arrivedComplete,
        arrivedIncomplete, columnsChanged, snowAdded, iceAdded, treeAdded, otherAdded, coldHit, coldMissed, coldWrong };

    /** The game marked a settling chunk changed; whether its surface really changed. */
    /**
     * The surface of a chunk looked at again (first seen, marked changed while waiting, scanned): a CHANGE line with
     * what changed since its last look, timed from when it was first seen. With NEIGHBOURS and LET_GO, a flight's log
     * tells how long new chunks keep changing, so the wait before mapping them can be tuned offline.
     *
     * @param when seen, waiting (marked changed while waiting to be mapped) or scan:why
     */
    static void look(net.minecraft.world.World world, net.minecraft.world.chunk.Chunk chunk, String when) {
        if (!on()) {
            return;
        }
        long key = key(chunk.xPosition, chunk.zPosition);
        long now = System.nanoTime();
        if (when.equals("seen")) {
            FIRST_SEEN.putIfAbsent(key, now);
        }
        Long firstSeen = FIRST_SEEN.get(key);
        ArrivalCheck.Snapshot before = LAST_LOOK.get(key);
        if (before != null && firstSeen != null) {
            int[] counts = new int[8];
            ArrivalCheck.compare(chunk, before, world.provider.hasNoSky, counts);
            if (counts[0] > 0) {
                line(
                    "CHANGE " + chunk.xPosition
                        + ","
                        + chunk.zPosition
                        + " +"
                        + ms(now - firstSeen)
                        + " at="
                        + when
                        + " columns="
                        + counts[0]
                        + " snow="
                        + counts[1]
                        + " ice="
                        + counts[2]
                        + " tree="
                        + counts[3]
                        + " other="
                        + counts[4]
                        + " sinceLastLookMs="
                        + ms(now - before.at));
            }
        }
        if (firstSeen != null) {
            LAST_LOOK.put(key, ArrivalCheck.take(world, chunk));
        }
        if (LAST_LOOK.size() > 20_000) {
            LAST_LOOK.clear();
            FIRST_SEEN.clear();
        }
    }

    /** All 8 neighbours of a chunk waiting to be mapped are loaded, for the first time since it was seen. */
    static void neighboursReady(int cx, int cz) {
        Long firstSeen = on() ? FIRST_SEEN.get(key(cx, cz)) : null;
        if (firstSeen != null) {
            line("NEIGHBOURS " + cx + "," + cz + " +" + ms(System.nanoTime() - firstSeen));
        }
    }

    static void marked(int cx, int cz, long signature) {
        if (!on()) {
            return;
        }
        long[] state = SETTLING.get(key(cx, cz));
        if (state == null) {
            return;
        }
        state[1]++;
        boolean real = state[0] != signature;
        if (real) {
            state[2]++;
            state[0] = signature;
            marksReal.incrementAndGet();
        } else {
            marksNoise.incrementAndGet();
        }
        Long firstSeen = FIRST_SEEN.get(key(cx, cz));
        if (firstSeen != null) {
            line(
                "MARK " + cx
                    + ","
                    + cz
                    + " +"
                    + ms(System.nanoTime() - firstSeen)
                    + " surface="
                    + (real ? "changed" : "same"));
        }
    }

    /**
     * @param missing neighbours not loaded (+x, -z...), empty if none
     * @param where   distance from the player and whether it is at the edge of the loaded area
     * @param edge    at the edge: a missing neighbour there comes only when the player gets closer
     */
    static void settled(int cx, int cz, boolean timeout, int quietTicks, String missing, String where, boolean edge,
        int ticks) {
        if (!on()) {
            return;
        }
        Long seen = SEEN.get(key(cx, cz));
        long[] marks = SETTLING.remove(key(cx, cz));
        long waited = seen == null ? -1 : System.nanoTime() - seen;
        if (waited >= 0) {
            sample(timeout ? SETTLE_TIMEOUT : SETTLE_QUIET, waited);
        }
        String why = "";
        if (timeout) {
            settleTimeouts.incrementAndGet();
            if (edge) {
                settleTimeoutsEdge.incrementAndGet();
            }
            why = missing.isEmpty() ? " because=kept being marked changed"
                : edge ? " because=neighbour beyond the loaded area" : " because=neighbour not sent";
        }
        line(
            "SETTLED " + cx
                + ","
                + cz
                + " how="
                + (timeout ? "timeout" : "quiet+neighbours")
                + why
                + " ticks="
                + ticks
                + " quietTicks="
                + quietTicks
                + (missing.isEmpty() ? "" : " missingNeighbours=" + missing)
                + " "
                + where
                + (marks == null ? "" : " marksChanged=" + marks[1] + " marksWithSurfaceChange=" + marks[2])
                + (waited < 0 ? "" : " waitedMs=" + ms(waited)));
    }

    /** Put off: a region it goes into is being read. */
    static void deferred(int cx, int cz, String map, boolean surfaceReady, boolean biomesReady) {
        if (on()) {
            scansDeferred.incrementAndGet();
            DEFERRED.putIfAbsent(key(cx, cz), System.nanoTime());
            line(
                "SCAN_DEFERRED " + cx
                    + ","
                    + cz
                    + " map="
                    + map
                    + " waiting for"
                    + (surfaceReady ? "" : " surface region")
                    + (biomesReady ? "" : " biome region"));
        }
    }

    /**
     * A chunk was scanned into its region.
     *
     * @param why new, changed, rescan (on schedule), unloading, chunkload
     */
    /**
     * @param signature  the chunk's surface signature now (0 if not worked out)
     * @param onMapSince when the map had the chunk before this scan (0 if it didn't)
     */
    static void scanned(int cx, int cz, int layer, String why, long nanos, int pixelsChanged, boolean regionNew,
        String map, long signature, long onMapSince) {
        if (!on()) {
            return;
        }
        scans.incrementAndGet();
        scanNanos.addAndGet(nanos);
        sample(SCAN, nanos);
        long now = System.nanoTime();
        Long seen = SEEN.remove(key(cx, cz));
        SETTLING.remove(key(cx, cz));
        if (layer < 0) {
            // Sent again by the server while it waited to be scanned: this scan maps the new one, which isn't lost.
            LOADED.remove(key(cx, cz));
        }
        String waited = "";
        if (seen != null && layer < 0) {
            long delay = now - seen;
            sample(SEEN_TO_SCANNED, delay);
            waited = " seenToScannedMs=" + ms(delay);
        }
        Long deferredAt = DEFERRED.remove(key(cx, cz));
        if (deferredAt != null) {
            sample(DEFER_TO_SCAN, now - deferredAt);
            waited += " putOffToScannedMs=" + ms(now - deferredAt);
        }
        if (pixelsChanged == 0) {
            scansNoPixels.incrementAndGet();
        }
        String surface = "";
        if (layer < 0 && signature != 0) {
            Long before = SCANNED_SIGNATURE.put(key(cx, cz), signature);
            if (SCANNED_SIGNATURE.size() > 100_000) {
                SCANNED_SIGNATURE.clear();
            }
            if (before != null) {
                boolean same = before == signature;
                if (same) {
                    scansSameSurface.incrementAndGet();
                }
                surface = same ? " surface=same" : " surface=changed";
            }
        }
        if (onMapSince > 0) {
            waited += " wasOnMapAgeS=" + (System.currentTimeMillis() - onMapSince) / 1000;
        }
        line(
            "SCAN " + cx
                + ","
                + cz
                + (layer < 0 ? " surface" : " caveLayer=" + layer)
                + " map="
                + map
                + " why="
                + why
                + " ms="
                + ms(nanos)
                + " pixelsChanged="
                + pixelsChanged
                + (regionNew ? " (region was not in memory before)" : "")
                + surface
                + waited);
    }

    /** The scan queue was built. */
    static void queueBuilt(int layer, int radius, int loaded, int fresh, int changed, int rescans, int settling,
        long nanos) {
        if (!on()) {
            return;
        }
        sample(QUEUE_BUILD, nanos);
        if (fresh + changed + rescans > 0) {
            line(
                "QUEUE " + (layer < 0 ? "surface" : "caveLayer=" + layer)
                    + " radius="
                    + radius
                    + " loadedChunks="
                    + loaded
                    + " new="
                    + fresh
                    + " changed="
                    + changed
                    + " rescans="
                    + rescans
                    + " stillSettling="
                    + settling
                    + " ms="
                    + ms(nanos));
        }
    }

    /** One tick of the scanner: what it did with its budget. */
    static void tick(int layer, int scannedCount, int deferredCount, int queueLeft, long nanos, int budget) {
        if (on() && (scannedCount > 0 || deferredCount > 0) && nanos > 5_000_000L) {
            line(
                "SCAN_TICK_SLOW " + (layer < 0 ? "surface" : "caveLayer=" + layer)
                    + " scanned="
                    + scannedCount
                    + "/"
                    + budget
                    + " deferred="
                    + deferredCount
                    + " queueLeft="
                    + queueLeft
                    + " ms="
                    + ms(nanos));
        }
    }

    static void unloadScan(int cx, int cz, String what) {
        if (on()) {
            unloadScans.incrementAndGet();
            Long seen = SEEN.get(key(cx, cz));
            line(
                "UNLOAD " + cx
                    + ","
                    + cz
                    + " "
                    + what
                    + (seen == null ? "" : " seenMsAgo=" + ms(System.nanoTime() - seen)));
        }
    }

    /**
     * The map's own time in one client tick.
     *
     * @param isoNanos    of it, the 3D map's (copying chunks' blocks, taking pictures)
     * @param unloadNanos of it, mapping chunks the game let go of (2D and 3D)
     */
    static void mapTick(long nanos, long isoNanos, long unloadNanos) {
        if (!on()) {
            return;
        }
        mapTickCount.incrementAndGet();
        mapTickNanos.addAndGet(nanos);
        mapTickMaxNanos.accumulateAndGet(nanos, Math::max);
        mapTickIsoNanos.addAndGet(isoNanos);
        mapTickIsoMaxNanos.accumulateAndGet(isoNanos, Math::max);
        mapTickUnloadNanos.addAndGet(unloadNanos);
        mapTickUnloadMaxNanos.accumulateAndGet(unloadNanos, Math::max);
        sample(MAP_TICK, nanos);
        if (nanos > 20_000_000L) {
            line(
                "MAP_TICK_SLOW ms=" + ms(nanos)
                    + " of which 3dMs="
                    + ms(isoNanos)
                    + " unloadsMs="
                    + ms(unloadNanos)
                    + " flatScanMs="
                    + ms(nanos - isoNanos - unloadNanos));
        }
    }

    private static final AtomicLong mapTickIsoNanos = new AtomicLong(), mapTickIsoMaxNanos = new AtomicLong(),
        mapTickUnloadNanos = new AtomicLong(), mapTickUnloadMaxNanos = new AtomicLong();

    /**
     * The game stood still between two ticks.
     *
     * @param lastMapTick the map's own time in the tick before
     * @param what        what was going on (world map open, 3D...)
     */
    static void hitch(long gapNanos, long lastMapTick, String what) {
        if (!on()) {
            return;
        }
        hitches.incrementAndGet();
        hitchNanos.addAndGet(gapNanos);
        sample(HITCH, gapNanos);
        line(
            "HITCH gapMs=" + ms(gapNanos)
                + " mapTickBeforeMs="
                + ms(lastMapTick)
                + " "
                + what
                + " heapUsedMB="
                + ((Runtime.getRuntime()
                    .totalMemory()
                    - Runtime.getRuntime()
                        .freeMemory())
                    >> 20));
    }

    // ---------------------------------------------------------------- regions

    private static String region(String map, int rx, int rz) {
        return map + " r." + rx + "." + rz;
    }

    /** A background read was started. */
    static void readAsked(String map, int rx, int rz, String why, int pending) {
        if (!on()) {
            return;
        }
        readsAsked.incrementAndGet();
        ASKED.put(region(map, rx, rz), System.nanoTime());
        line("READ_ASKED " + region(map, rx, rz) + " for=" + why + " readsPending=" + pending);
    }

    /** A region file was read (loader thread, or the game's own when blocking). */
    static void read(String map, int rx, int rz, long nanos, long bytes, String parts, String result) {
        if (!on()) {
            return;
        }
        readsDone.incrementAndGet();
        readNanos.addAndGet(nanos);
        readBytes.addAndGet(bytes);
        sample(READ, nanos);
        line("READ " + region(map, rx, rz) + " " + result + " ms=" + ms(nanos) + " bytes=" + bytes + " parts=" + parts);
    }

    /** A read region was taken into memory (render thread). */
    static void picked(String map, int rx, int rz, boolean found, String by) {
        if (!on()) {
            return;
        }
        Long asked = ASKED.remove(region(map, rx, rz));
        String waited = "";
        if (asked != null) {
            long delay = System.nanoTime() - asked;
            sample(ASKED_TO_PICKED, delay);
            waited = " askedToPickedMs=" + ms(delay);
        }
        line("PICKED " + region(map, rx, rz) + (found ? "" : " (no file)") + " by=" + by + waited);
    }

    /** Read by the game's thread, which waited for it. */
    static void blockingRead(String map, int rx, int rz, long nanos, boolean pending, String caller) {
        if (!on()) {
            return;
        }
        blockingReads.incrementAndGet();
        blockingNanos.addAndGet(nanos);
        sample(BLOCKING_READ, nanos);
        line(
            "BLOCKING_READ " + region(map, rx, rz)
                + " ms="
                + ms(nanos)
                + (pending ? " (waited for a background read)" : " (read here)")
                + " caller="
                + caller);
    }

    static void made(String map, int rx, int rz) {
        if (on()) {
            regionsMade.incrementAndGet();
            line("MADE " + region(map, rx, rz) + " (new, no file)");
        }
    }

    static void lod(String map, int rx, int rz, String from, long nanos) {
        if (!on()) {
            return;
        }
        lodBuilt.incrementAndGet();
        lodNanos.addAndGet(nanos);
        sample(LOD_BUILD, nanos);
        line("LOD " + region(map, rx, rz) + " from=" + from + " ms=" + ms(nanos));
    }

    static void saveCopy(String map, int rx, int rz, long nanos) {
        if (on()) {
            sample(SAVE_COPY, nanos);
        }
    }

    static void saved(String map, int rx, int rz, long nanos, long bytes, String parts, String result) {
        if (!on()) {
            return;
        }
        saves.incrementAndGet();
        saveNanos.addAndGet(nanos);
        saveBytes.addAndGet(bytes);
        sample(SAVE_WRITE, nanos);
        line("SAVE " + region(map, rx, rz) + " " + result + " ms=" + ms(nanos) + " bytes=" + bytes + " parts=" + parts);
    }

    static void saveRound(String map, int regions, long copyNanos) {
        if (on() && regions > 0) {
            line("SAVE_ROUND map=" + map + " regions=" + regions + " copyMs(render thread)=" + ms(copyNanos));
        }
    }

    /** PNG decode (read) or encode (save) of a region, for the summaries. */
    static void png(boolean encode, long nanos) {
        if (on() && nanos > 0) {
            sample(encode ? PNG_ENCODE : PNG_DECODE, nanos);
        }
    }

    /** A region read waited this long for a loader thread. */
    static void readQueued(long nanos) {
        if (on()) {
            sample(READ_QUEUE, nanos);
        }
    }

    /** A region save waited this long for the saver thread. */
    static void saveQueued(long nanos) {
        if (on()) {
            sample(SAVE_QUEUE, nanos);
        }
    }

    /** A region's changes waited this long before being copied for saving. */
    static void unsavedFor(long millis) {
        if (on() && millis > 0) {
            sample(UNSAVED, millis * 1_000_000L);
        }
    }

    /**
     * Something wrong with a region's files, on a line of its own so it is easy to find: READ_WARN (a part lost),
     * READ_FAILED, SAVE_WARN, SAVE_FAILED.
     */
    static void problem(String kind, String map, int rx, int rz, String text) {
        if (!on()) {
            return;
        }
        (kind.startsWith("READ") ? readProblems : saveProblems).incrementAndGet();
        PROBLEMS.computeIfAbsent(kind, k -> new AtomicLong())
            .incrementAndGet();
        line(kind + " " + region(map, rx, rz) + " " + text);
    }

    /**
     * A round of saves of every map (autosave) or saving all as the world is left.
     *
     * @param nanos      time on the render thread (autosave: copying the regions; leaving: waiting for them too)
     * @param regions    regions queued for saving
     * @param dirtyLeft  regions with changes not queued (being written already)
     */
    public static void saveAll(String why, int maps, int regions, int dirtyLeft, long nanos, int inMemory) {
        if (!on()) {
            return;
        }
        sample(why.equals("CLOSE_SAVE") ? CLOSE_SAVE : AUTOSAVE, nanos);
        line(
            why + " maps="
                + maps
                + " regionsQueued="
                + regions
                + " regionsInMemory="
                + inMemory
                + (dirtyLeft > 0 ? " stillChanged=" + dirtyLeft : "")
                + " ms="
                + ms(nanos)
                + " heapUsedMB="
                + ((Runtime.getRuntime()
                    .totalMemory()
                    - Runtime.getRuntime()
                        .freeMemory())
                    >> 20));
    }

    static void retained(String map, int freed, int keptUnsaved, int textures, int lods, int cancelled, int inMemory) {
        if (on() && freed + textures + lods + cancelled > 0) {
            line(
                "RETAIN map=" + map
                    + " regionsFreed="
                    + freed
                    + " keptUntilSaved="
                    + keptUnsaved
                    + " texturesFreed="
                    + textures
                    + " reducedFreed="
                    + lods
                    + " readsCancelled="
                    + cancelled
                    + " regionsLeft="
                    + inMemory);
        }
    }

    // ---------------------------------------------------------------- textures and drawing (render thread)

    static void textureMade(int rx, int rz, boolean reduced) {
        if (on()) {
            texturesMade.incrementAndGet();
        }
    }

    /**
     * Part of a region's texture was uploaded.
     *
     * @param sinceChangeNanos from the region's first change not shown yet to now
     */
    static void uploaded(int rx, int rz, int width, int height, long nanos, long sinceChangeNanos, boolean reduced) {
        if (!on()) {
            return;
        }
        uploads.incrementAndGet();
        uploadNanos.addAndGet(nanos);
        uploadPixels.addAndGet((long) width * height);
        sample(UPLOAD, nanos);
        if (nanos > 2_000_000L || width * height >= 512 * 512) {
            line(
                "UPLOAD " + (reduced ? "reduced tile" : "r." + rx + "." + rz)
                    + " area="
                    + width
                    + "x"
                    + height
                    + " ms="
                    + ms(nanos)
                    + (sinceChangeNanos > 0 ? " changeToScreenMs=" + ms(sinceChangeNanos) : ""));
        }
    }

    /**
     * A frame of the map was drawn.
     *
     * @param minimap the minimap's frame (not counted in the world map's)
     */
    public static void frame(boolean minimap, boolean reduced, int inView, int drawnCount, int loading, int missing,
        int textureLimited, long nanos) {
        if (!on() || minimap) {
            return;
        }
        frames.incrementAndGet();
        frameNanos.addAndGet(nanos);
        frameMaxNanos.accumulateAndGet(nanos, Math::max);
        drawn.addAndGet(drawnCount);
        notLoaded.addAndGet(loading);
        noFile.addAndGet(missing);
        textureWait.addAndGet(textureLimited);
        sample(FRAME, nanos);
        lastInView = inView;
        lastReduced = reduced;
    }

    private static volatile int lastInView;
    private static volatile boolean lastReduced;

    // ---------------------------------------------------------------- teammates

    static void shared(int cx, int cz, String what) {
        if (!on()) {
            return;
        }
        if (what.equals("written")) {
            shared.incrementAndGet();
        } else if (what.startsWith("ours")) {
            sharedOlder.incrementAndGet();
        } else {
            sharedWaiting.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- statistics (render thread, each tick)

    private static double lastPlayerX, lastPlayerZ;
    private static long lastPlayerAt;

    static void stats(int regions, int reduced, int textures, int pending, int queueLeft, int settling, double playerX,
        double playerZ, int viewDistance, int missingInView) {
        if (!on()) {
            return;
        }
        long now = System.currentTimeMillis();
        long f = frames.get();
        boolean busy = scans.get() + readsAsked.get() + saves.get() + uploads.get() + f > 0;
        if (now - lastStats >= (busy ? 1000 : 10_000)) {
            lastStats = now;
            if (f > 0) {
                line(
                    "DRAW frames=" + f
                        + " avgMs="
                        + ms(frameNanos.getAndSet(0) / f)
                        + " maxMs="
                        + ms(frameMaxNanos.getAndSet(0))
                        + " regionsInView="
                        + lastInView
                        + (lastReduced ? " (reduced copies)" : "")
                        + " drawnPerFrame="
                        + drawn.getAndSet(0) / f
                        + " loadingPerFrame="
                        + notLoaded.getAndSet(0) / f
                        + " noFilePerFrame="
                        + noFile.getAndSet(0) / f
                        + " waitingForTexturePerFrame="
                        + textureWait.getAndSet(0) / f);
                frames.set(0);
            }
            Runtime runtime = Runtime.getRuntime();
            String speed = "";
            if (lastPlayerAt > 0 && now > lastPlayerAt) {
                double dx = playerX - lastPlayerX, dz = playerZ - lastPlayerZ;
                double perSecond = Math.sqrt(dx * dx + dz * dz) * 1000.0 / (now - lastPlayerAt);
                speed = String.format(Locale.ROOT, " speedBlocksPerS=%.1f", perSecond);
            }
            lastPlayerX = playerX;
            lastPlayerZ = playerZ;
            lastPlayerAt = now;
            long ticks = Math.max(1, mapTickCount.getAndSet(0));
            line(
                "STATS player=" + (int) Math.floor(playerX)
                    + ","
                    + (int) Math.floor(playerZ)
                    + speed
                    + " viewDistance="
                    + viewDistance
                    + " chunksNotYetSentInView="
                    + missingInView
                    + " loaded="
                    + chunksLoaded.getAndSet(0)
                    + " unloaded="
                    + chunksUnloaded.getAndSet(0)
                    + " goneUnseen="
                    + goneUnseen.getAndSet(0)
                    + " arrivedComplete="
                    + arrivedComplete.getAndSet(0)
                    + " arrivedIncomplete="
                    + arrivedIncomplete.getAndSet(0)
                    + " [columns="
                    + columnsChanged.getAndSet(0)
                    + " snow="
                    + snowAdded.getAndSet(0)
                    + " ice="
                    + iceAdded.getAndSet(0)
                    + " tree="
                    + treeAdded.getAndSet(0)
                    + " other="
                    + otherAdded.getAndSet(0)
                    + "] snowIcePredicted[right="
                    + coldHit.getAndSet(0)
                    + " missed="
                    + coldMissed.getAndSet(0)
                    + " wrong="
                    + coldWrong.getAndSet(0)
                    + "]"
                    + " mapTickAvgMs="
                    + ms(mapTickNanos.getAndSet(0) / ticks)
                    + " mapTickMaxMs="
                    + ms(mapTickMaxNanos.getAndSet(0))
                    + " (3dAvgMs="
                    + ms(mapTickIsoNanos.getAndSet(0) / ticks)
                    + " 3dMaxMs="
                    + ms(mapTickIsoMaxNanos.getAndSet(0))
                    + " unloadsAvgMs="
                    + ms(mapTickUnloadNanos.getAndSet(0) / ticks)
                    + " unloadsMaxMs="
                    + ms(mapTickUnloadMaxNanos.getAndSet(0))
                    + ")"
                    + " hitches="
                    + hitches.getAndSet(0)
                    + " hitchMs="
                    + ms(hitchNanos.getAndSet(0))
                    + " scans="
                    + scans.getAndSet(0)
                    + " noPixelChange="
                    + scansNoPixels.getAndSet(0)
                    + " surfaceSame="
                    + scansSameSurface.getAndSet(0)
                    + " marksNoise="
                    + marksNoise.getAndSet(0)
                    + " marksReal="
                    + marksReal.getAndSet(0)
                    + " settleTimeouts="
                    + settleTimeouts.getAndSet(0)
                    + " ofThemAtEdge="
                    + settleTimeoutsEdge.getAndSet(0)
                    + " scanMs="
                    + ms(scanNanos.getAndSet(0))
                    + " deferred="
                    + scansDeferred.getAndSet(0)
                    + " unloadScans="
                    + unloadScans.getAndSet(0)
                    + " queueLeft="
                    + queueLeft
                    + " settling="
                    + settling
                    + " readsAsked="
                    + readsAsked.getAndSet(0)
                    + " readsDone="
                    + readsDone.getAndSet(0)
                    + " readMs="
                    + ms(readNanos.getAndSet(0))
                    + " readKB="
                    + (readBytes.getAndSet(0) >> 10)
                    + " blockingReads="
                    + blockingReads.getAndSet(0)
                    + " blockingMs="
                    + ms(blockingNanos.getAndSet(0))
                    + " regionsMade="
                    + regionsMade.getAndSet(0)
                    + " reducedBuilt="
                    + lodBuilt.getAndSet(0)
                    + " reducedMs="
                    + ms(lodNanos.getAndSet(0))
                    + " saves="
                    + saves.getAndSet(0)
                    + " saveMs="
                    + ms(saveNanos.getAndSet(0))
                    + " saveKB="
                    + (saveBytes.getAndSet(0) >> 10)
                    + " readProblems="
                    + readProblems.getAndSet(0)
                    + " saveProblems="
                    + saveProblems.getAndSet(0)
                    + " texturesMade="
                    + texturesMade.getAndSet(0)
                    + " uploads="
                    + uploads.getAndSet(0)
                    + " uploadMs="
                    + ms(uploadNanos.getAndSet(0))
                    + " uploadKpx="
                    + (uploadPixels.getAndSet(0) >> 10)
                    + " teamWritten="
                    + shared.getAndSet(0)
                    + " teamOlder="
                    + sharedOlder.getAndSet(0)
                    + " teamWaiting="
                    + sharedWaiting.getAndSet(0)
                    + " regionsInMemory="
                    + regions
                    + " reducedInMemory="
                    + reduced
                    + " textures="
                    + textures
                    + " readsPending="
                    + pending
                    + " heapUsedMB="
                    + ((runtime.totalMemory() - runtime.freeMemory()) >> 20));
        }
        if (now - lastSummary >= 60_000) {
            lastSummary = now;
            summary("SUMMARY");
        }
    }

    private static void summary(String title) {
        line(title + " (ms)");
        for (int i = 0; i < SAMPLES.length; i++) {
            List<Long> values;
            synchronized (SAMPLES[i]) {
                values = new ArrayList<>(SAMPLES[i]);
            }
            if (values.isEmpty()) {
                continue;
            }
            Collections.sort(values);
            long sum = 0;
            for (long v : values) {
                sum += v;
            }
            line(
                String.format(
                    Locale.ROOT,
                    "%s   %-42s n=%d avg=%s p50=%s p90=%s p99=%s max=%s total=%s",
                    title,
                    SAMPLE_NAMES[i],
                    values.size(),
                    ms(sum / values.size()),
                    ms(values.get(values.size() / 2)),
                    ms(values.get(values.size() * 9 / 10)),
                    ms(values.get(Math.min(values.size() - 1, values.size() * 99 / 100))),
                    ms(values.get(values.size() - 1)),
                    ms(sum)));
        }
        line(
            title + "   chunks seen and not scanned yet: "
                + SEEN.size()
                + ", reads asked and not picked up: "
                + ASKED.size()
                + ", chunks put off and not scanned yet: "
                + DEFERRED.size()
                + ", chunks loaded and not seen yet: "
                + LOADED.size());
        if (!PROBLEMS.isEmpty()) {
            StringBuilder problems = new StringBuilder(title + "   region file problems since the start:");
            for (Map.Entry<String, AtomicLong> entry : PROBLEMS.entrySet()) {
                problems.append(' ')
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue()
                        .get());
            }
            line(problems.toString());
        }
    }
}
