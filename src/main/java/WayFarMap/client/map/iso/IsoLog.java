package WayFarMap.client.map.iso;

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
import WayFarMap.Perf;
import WayFarMap.WayFarMap;

/**
 * A detailed log of how chunks get onto the 3D map, for finding why some take long: every step of a chunk (seen by
 * the game, settled, scanned, queued, copied, stored, tiles marked) with its time, what the threads were doing, and
 * summaries of where the time went. Written to {@code .minecraft/wayfarmap/logs/3d-*.log} by a thread of its own, so
 * logging never waits for the disk. Only while {@link Config#log3d} is on.
 */
public final class IsoLog {

    /** Log files kept; older ones are deleted when a new one starts. */
    private static final int FILES_KEPT = 8;
    /** Past this the per-chunk lines stop (the summaries go on), so a long session doesn't fill the disk. */
    private static final long MAX_BYTES = 200L << 20;
    /** Chunks remembered for the summaries. */
    private static final int MAX_DONE = 200_000;

    private static final long START = System.nanoTime();
    private static final LinkedBlockingQueue<String> LINES = new LinkedBlockingQueue<>();
    private static volatile File file;
    private static volatile boolean detailsStopped;
    private static final AtomicLong written = new AtomicLong();
    private static Thread thread;

    /** A chunk on its way to the 3D map; all times {@link System#nanoTime()}, 0 if not reached. */
    static final class Trace {

        final int cx, cz;
        /** Why it is copied: fresh, changed, near, far, back, neighbour, unload-edge, unload-late. */
        String reason;
        /** Loaded by the game (its data arrived), or 0 if not known. */
        long loaded;
        /** First seen by the scanner (waiting for the chunk to settle), or 0 if not known. */
        long seen;
        long settled;
        String settleHow;
        long scanned;
        long queued;
        String queue;
        long firstCapture;
        long lastCapture;
        int captures;
        long captureNanos;
        long blockCaptureNanos;
        long facesNanos;
        int facesFound, facesToDraw, facesMissing;
        long submitted;
        int writerBacklog;
        long writerStart;
        long writerEnd;

        Trace(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** Chunks between being seen and being handed to the writer. */
    private static final Map<Long, Trace> TRACES = new ConcurrentHashMap<>();
    /** Chunks the scanner saw but didn't scan yet (settling): when they were first seen. */
    private static final Map<Long, Long> SEEN = new ConcurrentHashMap<>();
    private static final Map<Long, long[]> SETTLED = new ConcurrentHashMap<>();
    /** Chunks the game loaded and the scanner didn't see yet: when they were loaded. */
    private static final Map<Long, Long> LOADED = new ConcurrentHashMap<>();
    /** Phases of the chunks stored, for the summaries: see {@link #PHASES}. */
    private static final List<long[]> DONE = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> DONE_NAMES = Collections.synchronizedList(new ArrayList<>());
    private static final String[] PHASES = { "total", "settle", "settled->scan", "scan->queue", "queue->capture",
        "capturing", "captureCpu", "submit->writer", "writer", "load->seen", "load->stored" };

    // Counters since the last stats line.
    private static final AtomicLong capturesDone = new AtomicLong(), captureNanosSum = new AtomicLong(),
        incompleteCaptures = new AtomicLong(), stored = new AtomicLong(), storedSame = new AtomicLong(),
        writerNanos = new AtomicLong(), tilesDrawn = new AtomicLong(), tileNanos = new AtomicLong(),
        tilesFromDisk = new AtomicLong(), tilesRetry = new AtomicLong(), tilesSkipped = new AtomicLong(),
        tickNanos = new AtomicLong(), ticks = new AtomicLong(), ticksOutOfTime = new AtomicLong(),
        unloadSkipped = new AtomicLong(), unloadCopied = new AtomicLong(), scanDeferred = new AtomicLong();
    private static final AtomicLong writerBacklog = new AtomicLong();
    private static volatile long saverBusySince;
    private static volatile String saverTask = "";
    private static long lastStats, lastSummary;
    /** Tile renderer threads (for how busy they are). */
    static volatile int renderers;
    /** Frames of the 3D view since the last stats line: quads drawn (all, most in one frame), and the last state. */
    private static long viewFrames, viewQuads, viewQuadsMax;
    private static int viewTextured, viewBacklog, viewBacklogMax;
    /** Tiles put on the graphics card since the last stats line, and the time it took. */
    private static final AtomicLong uploads = new AtomicLong(), uploadNanos = new AtomicLong(),
        uploadMaxNanos = new AtomicLong();
    /**
     * For the summaries (ms): a chunk loaded until the scanner saw it, a chunk change until the tiles showing it were
     * on screen, a tile with nothing yet from being wanted until on screen, a finished tile waiting to be uploaded.
     */
    private static final List<Long> CHANGE_SHOWN = Collections.synchronizedList(new ArrayList<>()),
        NEW_SHOWN = Collections.synchronizedList(new ArrayList<>()),
        UPLOAD_WAITS = Collections.synchronizedList(new ArrayList<>()),
        LOAD_SEEN = Collections.synchronizedList(new ArrayList<>());

    private IsoLog() {}

    /** Folder next to the log where pictures of blocks are saved as PNG, null while the log is off. */
    static File pictureDirectory() {
        File log = file;
        return log == null ? null
            : new File(
                log.getParentFile(),
                log.getName()
                    .replace(".log", "") + "-pictures");
    }

    static boolean on() {
        return Config.log3d && file != null;
    }

    /** Public for the scanner in another package. */
    public static boolean enabled() {
        return on();
    }

    // ---------------------------------------------------------------- file

    /** A world was joined: starts a new log file in {@code dataDirectory/wayfarmap/logs}. */
    static synchronized void open(File dataDirectory, File worldDirectory) {
        close();
        if (!Config.log3d) {
            return;
        }
        File directory = new File(new File(dataDirectory, "wayfarmap"), "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            WayFarMap.LOG.warn("Could not create {}", directory);
            return;
        }
        File[] old = directory.listFiles((d, name) -> name.startsWith("3d-") && name.endsWith(".log"));
        if (old != null && old.length >= FILES_KEPT) {
            Arrays.sort(
                old,
                (a, b) -> a.getName()
                    .compareTo(b.getName()));
            for (int i = 0; i <= old.length - FILES_KEPT; i++) {
                old[i].delete();
            }
        }
        String name = "3d-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".log";
        File target = new File(directory, name);
        BufferedWriter out;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8));
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not create the 3D map log " + target, e);
            return;
        }
        file = target;
        detailsStopped = false;
        BlockDiag.clear();
        written.set(0);
        LINES.clear();
        Thread writer = new Thread(() -> writeLines(out), "WayFarMap 3D log");
        writer.setDaemon(true);
        writer.start();
        thread = writer;
        WayFarMap.LOG.info("3D map log: {}", target);
        Perf.reset();
        Perf.on = true;
        lastPerf = System.currentTimeMillis();
        lastPerfNanos = System.nanoTime();
        THREAD_CPU.clear();
        THREAD_TOTALS.clear();
        FRESH_TIMES.clear();
        LEVEL_COUNTS.clear();
        LEVEL_TIMES.clear();
        synchronized (FRESH_ATTEMPTS) {
            FRESH_ATTEMPTS.clear();
        }
        FaceRenderer.kindStats()
            .clear();
        LOADED.clear();
        HIDDEN_KINDS.clear();
        visChecked = visHidden = visToDraw = visToDrawHidden = visNanos = 0;
        visByReach = visByLines = visSeen = visLinesFollowed = visLinesSkipped = visReachNanos = visLinesNanos = 0;
        hiddenReachedCount.set(0);
        FaceRenderer.dataStatsClear();
        CHANGE_SHOWN.clear();
        NEW_SHOWN.clear();
        UPLOAD_WAITS.clear();
        LOAD_SEEN.clear();
        viewFrames = viewQuads = viewQuadsMax = 0;
        viewBacklogMax = 0;
        perfStart = System.nanoTime();
        Runtime runtime = Runtime.getRuntime();
        line(
            "START world=" + worldDirectory
                + " cpus="
                + runtime.availableProcessors()
                + " maxMemMB="
                + (runtime.maxMemory() >> 20)
                + " isoCaptureMs="
                + Config.isoCaptureMs
                + " chunksScannedPerTick="
                + Config.chunksScannedPerTick
                + " autosaveSec="
                + Config.autosaveIntervalSeconds
                + " isoQuality="
                + Config.isoQuality
                + " isoSmooth="
                + Config.isoSmooth
                + " java="
                + System.getProperty("java.version"));
        line(
            "LEGEND times in ms since the log started; [thread]. A chunk: SEEN (scanner found it loaded) -> SETTLED "
                + "(decorated, quiet) -> SCANNED (flat map) -> QUEUED (for copying) -> CAPTURE (render thread copies "
                + "blocks + pictures; INCOMPLETE = pictures still missing, copied again) -> SUBMIT (to the writer "
                + "thread) -> STORE (writer compressed and stored it) -> DONE (all phases) -> MARKED (tiles on screen "
                + "told to redraw). FACES after a CAPTURE: why blocks need pictures, what the caches gave, and the "
                + "time finding them, setting up GL, drawing (CPU only), reading back (waits for the graphics card to "
                + "finish drawing too), storing sprites. STALL: no client tick for a while. STATS every second while "
                + "busy, SUMMARY every 5 minutes and at the end (with the kinds of blocks whose pictures cost most).");
        line(
            "LEGEND PERF every 5 seconds: frames per second and how long frames took, client ticks per second and "
                + "their time, the integrated server's ticks per second and milliseconds per tick (single player), "
                + "the mod's parts per frame / tick / server tick (ms each on average, share of that time, slowest), "
                + "cpu% of the mod's threads and the game's own (100 = one core busy), chunks put on the 3D map per "
                + "second and how long new ones took, pictures[reused/drawn] blocks given pictures from a cache or "
                + "drawn. BOTTLENECK in each SUMMARY: what costs most and what to change.");
        line(
            "LEGEND pictures learned by kind (a block open on the same sides next to the same blocks): after "
                + "3 times alike, the next ones are not drawn (every 16th is, to check); KIND_UNRELIABLE when a "
                + "kind gave other pictures (drawn every time from then on). Also by block alone (whatever is next "
                + "to it): after 6 alike in 3 kinds of places, for blocks inside their cell (detached: plants) or "
                + "all empty (ores); BLOCK_UNRELIABLE when not. Alike = the same, or for a detached block about the "
                + "same coverage and color (plants moved a little from place to place). In each SUMMARY: learned "
                + "pictures (per kind reused/drawn/drawnEmpty/confirmed/conflicts/alike/reusedByBlock/"
                + "blockConflicts), tiles per level of the 3D view (queued with nothing yet / only out of date, "
                + "drawn, drawing times), copies per new chunk (more than one = pictures not all taken at once).");
        line(
            "LEGEND tiles of the 3D view: VIEW_START (the view opened, zoomed, turned or moved to another dimension) "
                + "-> TILE_QUEUED -> TILE_DONE (a renderer made it: disk=what reading its saved file gave, "
                + "chunks=where the rays found blocks: store (3D blocks) / none, "
                + "reads=region files read for it, waits=time waiting for block looks from the game, px=pixels: "
                + "clear (nothing, the dark background shows) / black / dark, saved=what was written to its file) -> "
                + "VIEW every second while the screen is not complete (onScreen, ready, empty, fromCoarser = a "
                + "blurry parent shown, holes = nothing shown at all) -> VIEW_COMPLETE (how long the screen took). "
                + "TILE_WARN marks what looks wrong (empty or black tiles saved, files that couldn't be read, slow "
                + "tiles); LOOKS_WAIT are waits for block looks.");
        line(
            "LEGEND the whole way of a chunk: the game loads it (not a line of its own: SEEN sinceLoadMs) -> SEEN "
                + "... -> STORE -> MARKED (tiles told) -> TILE_QUEUED -> TILE_DONE (trace[ms rays usPerRay] = ray "
                + "tracing alone, 4 rays a pixel zoomed far out with isoSmooth; checkMs = finding whether its file is "
                + "out of date) -> TILE_SHOWN (on the graphics card: changeMs = from the chunk change stored to on "
                + "screen, newMs = a tile with nothing yet from first wanted to on screen, uploadWaitMs = finished, "
                + "waiting for its turn to be uploaded, uploadMs = putting it on the card). DONE load->seen and "
                + "load->stored: from the game loading the chunk. STATS renderers[busy] = share of the time the tile "
                + "renderers were drawing; view[...] = frames of the 3D view, quads drawn a frame, tiles with "
                + "pictures, results waiting for upload, uploads and their time. SUMMARY latency: those times as "
                + "percentiles. PERF frame: 3dDraw [3dUpload 3dLooks 3dTiles 3dEvict] splits the 3D view's frame "
                + "time; tick: capture3d [3dLooksAhead].");
        line(
            "LEGEND speed-ups: QUEUED queue=fresh-early = a new chunk copied as soon as the 8 around it are loaded, "
                + "without waiting for it to settle; it is QUEUED reason=settled again once settled (UNCHANGED if "
                + "nothing came). NOISE = copied again but only fluids flowing, leaves or light changed: no pictures "
                + "taken, not stored (by=render thread, before the pictures; by=writer, after), the chunk is copied "
                + "again at most every 30 s, and noise is stored at least every 60 s. MARKED changed=x,y,z..x,y,z = "
                + "the blocks that changed (chunk coordinates): only tiles over them are drawn again (whole = all). "
                + "TILE_DONE src=composed = a coarse tile made from the 4 finer tiles' files instead of traced. "
                + "IN_FLIGHT = a chunk's pictures drawn and read back from the graphics card the next tick (no wait "
                + "for it): the chunk's CAPTURE follows then; PERF tick: 3dPicturesRead, STATS picturesReadLaterMs = "
                + "that reading and storing. PICTURES_READBACK says which way pictures are read. FACES "
                + "hiddenFromMap[blocksNotDrawn=h/n picturesLeftOut=v ms] and SUMMARY 'hidden from the map': "
                + "pictures the map can't show (every line of sight toward the viewer from that side meets a solid "
                + "cube with solid icons first; glass, leaves, water, bars let it through) are not drawn: HIDDEN "
                + "ids, drawn from icons should a ray get there. QUEUED reason=visibility: a block changed nearby, "
                + "the chunk's hidden pictures are looked at again. -Dwayfarmap.drawHidden=true draws them all.");
        line(
            "LEGEND hiddenFromMap details: only the chunks the map has hide blocks (a chunk loaded but never stored "
                + "is empty for the tiles), and only the blocks to draw are looked at. hiddenByReach = pictures hidden "
                + "because open space from the sky reaches no cell around (no line followed), hiddenByLines = hidden "
                + "after following the lines of sight, seen = can be seen; lines/linesSkipped = lines followed / left "
                + "out as starting where open space doesn't reach; reachMs/linesMs = time of each step. A ray of a "
                + "tile reaching a hidden picture is a mistake: HIDDEN_REACHED (where), SUMMARY "
                + "raysReachingHiddenPictures and fallback hiddenReached should stay 0.");
        line(
            "LEGEND byData: pictures of blocks with a tile entity (Carpenter's Blocks, ArchitectureCraft, GregTech) by "
                + "the block, its surroundings, open sides, what its tile entity keeps (NBT without x/y/z) and what "
                + "the 6 tile entities next to it keep. hit = given without drawing, sharedInChunk = drawn once for "
                + "all the blocks with the same key in the chunk (classes that proved reliable), learning = drawn as "
                + "the key isn't confirmed yet, verify = drawn anyway to check (every 16th), notFitting = kept "
                + "pictures hidden where this block can be seen, unreliable = key gave other pictures once (drawn "
                + "every time), classOff = class turned off, noKey = its data couldn't be written. DATA_UNRELIABLE = a "
                + "key gave other pictures (should be rare), DATA_CLASS_TRUSTED / DATA_CLASS_OFF = a class proved "
                + "reliable / not. SUMMARY data#: per class of tile entity. -Dwayfarmap.noDataCache=true turns it "
                + "off. PICTURE_CACHE: these caches (by surroundings, kind, block, data, and how classes fared) kept "
                + "from game to game in wayfarmap/cache/<world>/3d-pictures.dat: loaded when the world is joined "
                + "(entries, droppedForMissingPictures), saved with the pictures (copied ms on the render thread), "
                + "ignored if the resource packs, the mods or their versions changed. With it, a base taken before "
                + "shows hits instead of learning in FACES.");
    }

    /** The world was left: writes the summary and closes the file. */
    static synchronized void close() {
        Thread writer = thread;
        if (writer == null) {
            return;
        }
        summary("END");
        Perf.on = false;
        LINES.add("\u0000");
        try {
            writer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        thread = null;
        file = null;
        TRACES.clear();
        SEEN.clear();
        SETTLED.clear();
        DONE.clear();
        DONE_NAMES.clear();
        BLOCKS.clear();
        CHANGED_BY.clear();
        TILE_COUNTS.clear();
        VIEW_TIMES.clear();
        LOADED.clear();
        viewKey = null;
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
            WayFarMap.LOG.warn("3D map log stopped", e);
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {}
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /** Milliseconds from one time to the other, "-" if either is missing. */
    private static String span(long from, long to) {
        return from == 0 || to == 0 ? "-" : ms(to - from);
    }

    private static void line(String text) {
        long now = System.nanoTime();
        String line = String.format(Locale.ROOT, "%10.1f", (now - START) / 1e6) + " ["
            + Thread.currentThread()
                .getName()
            + "] "
            + text;
        if (written.addAndGet(line.length() + 1) > MAX_BYTES && !text.startsWith("STATS")
            && !text.startsWith("SUMMARY")
            && !text.startsWith("END")) {
            if (!detailsStopped) {
                detailsStopped = true;
                LINES.add("LOG over " + (MAX_BYTES >> 20) + " MB: per-chunk lines stop here");
            }
            return;
        }
        LINES.add(line);
    }

    /** Any line (public for the scanner). */
    public static void log(String text) {
        if (on()) {
            line(text);
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static String at(long key) {
        return (int) (key >> 32) + "," + (int) key;
    }

    // ---------------------------------------------------------------- scanner (render thread)

    /** The game loaded a chunk (its data arrived from the server). */
    public static void loaded(int cx, int cz) {
        if (!on()) {
            return;
        }
        if (LOADED.size() > 50_000) {
            // Chunks loaded and let go before the scanner came to them: start over rather than grow without end.
            LOADED.clear();
        }
        LOADED.put(key(cx, cz), System.nanoTime());
    }

    /** The scanner found a new chunk loaded; it waits until the chunk is decorated. */
    public static void seen(int cx, int cz, boolean neighboursReady) {
        if (!on()) {
            return;
        }
        long key = key(cx, cz);
        if (SEEN.size() > 50_000) {
            // Chunks let go while settling are never scanned: start over rather than grow without end.
            SEEN.clear();
            SETTLED.clear();
        }
        long now = System.nanoTime();
        if (SEEN.putIfAbsent(key, now) == null) {
            Long loaded = LOADED.get(key);
            if (loaded != null && LOAD_SEEN.size() < 100_000) {
                LOAD_SEEN.add((now - loaded) / 1_000_000L);
            }
            line(
                "SEEN " + cx
                    + ","
                    + cz
                    + " neighboursReady="
                    + neighboursReady
                    + " sinceLoadMs="
                    + (loaded == null ? "-" : ms(now - loaded)));
        }
    }

    /** It is considered finished and scanned soon. */
    public static void settled(int cx, int cz, String how, int ticksWaited) {
        if (!on()) {
            return;
        }
        long key = key(cx, cz);
        Long seen = SEEN.get(key);
        long now = System.nanoTime();
        SETTLED.put(key, new long[] { seen == null ? 0 : seen, now });
        line(
            "SETTLED " + cx
                + ","
                + cz
                + " how="
                + how
                + " ticks="
                + ticksWaited
                + " ms="
                + span(seen == null ? 0 : seen, now));
    }

    /** The scanner skipped it for now: the flat map's region is still being read. */
    public static void scanDeferred(int cx, int cz) {
        if (on()) {
            scanDeferred.incrementAndGet();
            line("SCAN_DEFERRED " + cx + "," + cz + " flat region still loading");
        }
    }

    /** Scanned for the flat map (surface). */
    public static void scanned(int cx, int cz, boolean again, boolean changed, boolean modified, long nanos) {
        if (!on()) {
            return;
        }
        line(
            "SCANNED " + cx
                + ","
                + cz
                + " again="
                + again
                + " changed="
                + changed
                + " modified="
                + modified
                + " ms="
                + ms(nanos));
    }

    // ---------------------------------------------------------------- IsoMap (render thread)

    /** What {@link IsoMap#onChunkScanned} did with it. */
    static void scanDecision(int cx, int cz, String what, long sinceLastMs) {
        if (!on()) {
            return;
        }
        line("DECIDE " + cx + "," + cz + " " + what + (sinceLastMs >= 0 ? " sinceLastCopyMs=" + sinceLastMs : ""));
    }

    /** It went into a queue to be copied. */
    static void queued(int cx, int cz, String reason, String queue, int fresh, int again) {
        if (!on()) {
            return;
        }
        long key = key(cx, cz);
        long now = System.nanoTime();
        Trace trace = TRACES.get(key);
        boolean created = trace == null;
        if (created) {
            trace = new Trace(cx, cz);
            trace.reason = reason;
            Long loaded = LOADED.remove(key);
            trace.loaded = loaded == null ? 0 : loaded;
            Long seen = SEEN.remove(key);
            long[] settled = SETTLED.remove(key);
            if (settled != null) {
                trace.seen = settled[0];
                trace.settled = settled[1];
            } else if (seen != null) {
                trace.seen = seen;
            }
            trace.scanned = now;
            TRACES.put(key, trace);
        }
        trace.queued = now;
        trace.queue = queue;
        line(
            "QUEUED " + cx
                + ","
                + cz
                + " reason="
                + reason
                + " queue="
                + queue
                + " freshQueue="
                + fresh
                + " againQueue="
                + again
                + (created ? "" : " (already traced, first reason=" + trace.reason + ")"));
    }

    /** It left its queue without being copied. */
    static void dropped(int cx, int cz, String why) {
        if (!on()) {
            return;
        }
        Trace trace = TRACES.remove(key(cx, cz));
        line(
            "DROPPED " + cx
                + ","
                + cz
                + " "
                + why
                + (trace == null ? ""
                    : " reason=" + trace.reason
                        + " waitedMs="
                        + span(trace.queued, System.nanoTime())
                        + " captures="
                        + trace.captures));
    }

    /** A copy of its blocks was made (it may lack pictures: then it is copied again). */
    static void captured(int cx, int cz, String reason, boolean whole, boolean unloading, long blockNanos,
        long faceNanos, boolean complete, boolean storedAnyway, int copies, long budgetLeftNanos) {
        if (!on()) {
            return;
        }
        long key = key(cx, cz);
        long now = System.nanoTime();
        picturesReused.addAndGet(
            FaceRenderer.kindHit + FaceRenderer.surroundingsHit
                + FaceRenderer.placeHit
                + FaceRenderer.dataHit
                + FaceRenderer.dataShared);
        picturesDrawn.addAndGet(FaceRenderer.lastDrawn);
        Trace trace = TRACES.get(key);
        if (trace == null) {
            trace = new Trace(cx, cz);
            trace.reason = reason;
            trace.queued = now - blockNanos - faceNanos;
            TRACES.put(key, trace);
        }
        if (trace.firstCapture == 0) {
            trace.firstCapture = now - blockNanos - faceNanos;
        }
        // How long it waited for this turn since the one before (round and round the queue).
        String sinceLast = trace.lastCapture == 0 ? "-" : ms(now - blockNanos - faceNanos - trace.lastCapture);
        trace.lastCapture = now;
        trace.captures++;
        trace.blockCaptureNanos += blockNanos;
        trace.facesNanos += faceNanos;
        trace.captureNanos += blockNanos + faceNanos;
        trace.facesFound = FaceRenderer.lastFound;
        trace.facesToDraw = FaceRenderer.lastToDraw;
        trace.facesMissing = FaceRenderer.lastMissing;
        capturesDone.incrementAndGet();
        captureNanosSum.addAndGet(blockNanos + faceNanos);
        if (!complete) {
            incompleteCaptures.incrementAndGet();
        }
        line(
            (complete ? "CAPTURE " : "CAPTURE_INCOMPLETE ") + cx
                + ","
                + cz
                + " reason="
                + trace.reason
                + " attempt="
                + trace.captures
                + " sinceLastAttemptMs="
                + sinceLast
                + " whole="
                + whole
                + " unloading="
                + unloading
                + " blocksMs="
                + ms(blockNanos)
                + " picturesMs="
                + ms(faceNanos)
                + " facesFound="
                + FaceRenderer.lastFound
                + " toDraw="
                + FaceRenderer.lastToDraw
                + " drawn="
                + FaceRenderer.lastDrawn
                + " missing="
                + FaceRenderer.lastMissing
                + " paletteFull="
                + FaceRenderer.lastPaletteFull
                + (storedAnyway ? " STORED_WITH_MISSING_PICTURES copies=" + copies : "")
                + " tickBudgetLeftMs="
                + ms(budgetLeftNanos));
        if (FaceRenderer.lastFound > 0) {
            // Inside the pictures: why blocks need them, what the caches gave, where the time went.
            line(
                "FACES " + cx
                    + ","
                    + cz
                    + " attempt="
                    + trace.captures
                    + (FaceRenderer.sessionReused != 0 ? " resumed" : " foundAnew")
                    + " need[complex="
                    + FaceRenderer.whyComplex
                    + " ownRenderer="
                    + FaceRenderer.whyOwnRenderer
                    + " glass="
                    + FaceRenderer.whyGlass
                    + " sidesDependOnWorld="
                    + FaceRenderer.whySides
                    + " tileEntities="
                    + FaceRenderer.tileEntities
                    + "] cache[placeHit="
                    + FaceRenderer.placeHit
                    + " placeExpired="
                    + FaceRenderer.placeExpired
                    + " placeChanged="
                    + FaceRenderer.placeChanged
                    + " surroundingsHit="
                    + FaceRenderer.surroundingsHit
                    + " sharedInChunk="
                    + FaceRenderer.surroundingsShared
                    + " kindHit="
                    + FaceRenderer.kindHit
                    + "] byData[hit="
                    + FaceRenderer.dataHit
                    + " sharedInChunk="
                    + FaceRenderer.dataShared
                    + " learning="
                    + FaceRenderer.dataLearning
                    + " verify="
                    + FaceRenderer.dataVerify
                    + " notFitting="
                    + FaceRenderer.dataNotFitting
                    + " unreliable="
                    + FaceRenderer.dataUnreliableKey
                    + " classOff="
                    + FaceRenderer.dataClassOff
                    + " noKey="
                    + FaceRenderer.dataNoKey
                    + " keyMs="
                    + ms(FaceRenderer.dataKeyNanos)
                    + "] batches="
                    + FaceRenderer.batches
                    + " slots="
                    + FaceRenderer.slotsUsed
                    + " ms[find="
                    + ms(FaceRenderer.findNanos)
                    + " glSetup="
                    + ms(FaceRenderer.setupNanos)
                    + " draw="
                    + ms(FaceRenderer.drawNanos)
                    + " readBack="
                    + ms(FaceRenderer.readNanos)
                    + " storeSprites="
                    + ms(FaceRenderer.storeNanos)
                    + "] find[blocks="
                    + FaceRenderer.blocksLooked
                    + " openSidesMs="
                    + ms(FaceRenderer.exposedNanos)
                    + " tileEntityMs="
                    + ms(FaceRenderer.tileEntityNanos)
                    + " surroundingsMs="
                    + ms(FaceRenderer.surroundingsNanos)
                    + "] store[unshadeMs="
                    + ms(FaceRenderer.unshadeNanos)
                    + " idMs="
                    + ms(FaceRenderer.idNanos)
                    + " learnMs="
                    + ms(FaceRenderer.learnNanos)
                    + "] hiddenFromMap[blocksNotDrawn="
                    + FaceRenderer.hiddenFound
                    + "/"
                    + FaceRenderer.visibilityChecked
                    + " picturesLeftOut="
                    + FaceRenderer.hiddenToDraw
                    + " ms="
                    + ms(FaceRenderer.visibilityNanos)
                    + " hiddenByReach="
                    + FaceRenderer.visHiddenByReach
                    + " hiddenByLines="
                    + FaceRenderer.visHiddenByLines
                    + " seen="
                    + FaceRenderer.visSeen
                    + " lines="
                    + FaceRenderer.visLinesFollowed
                    + " linesSkipped="
                    + FaceRenderer.visLinesSkipped
                    + " reachMs="
                    + ms(FaceRenderer.visReachNanos)
                    + " linesMs="
                    + ms(FaceRenderer.visLinesNanos)
                    + "] skippable[expiredSame="
                    + FaceRenderer.expiredSame
                    + " expiredDiffer="
                    + FaceRenderer.expiredDiffer
                    + " sameAsTwin="
                    + FaceRenderer.sameAsTwin
                    + " differFromTwin="
                    + FaceRenderer.differFromTwin
                    + " sameAsTwinWithData="
                    + FaceRenderer.sameAsTwinWithData
                    + " differFromTwinWithData="
                    + FaceRenderer.differFromTwinWithData
                    + " onlyBottomOpen="
                    + FaceRenderer.onlyBottomOpen
                    + " allEmpty="
                    + FaceRenderer.allEmpty
                    + " allEmptyOnlyBottom="
                    + FaceRenderer.allEmptyOnlyBottom
                    + "]");
        }
    }

    /** Per kind of block: pictures taken, views, time drawing (render thread). */
    private static final Map<String, long[]> BLOCKS = new ConcurrentHashMap<>();

    /** A block was drawn for its pictures. */
    static void blockDrawn(net.minecraft.block.Block block, Object tileEntity, int views, long nanos) {
        if (!on()) {
            return;
        }
        String name;
        try {
            name = String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(block));
        } catch (RuntimeException e) {
            name = block.getClass()
                .getName();
        }
        if (tileEntity != null) {
            name += " [" + tileEntity.getClass()
                .getSimpleName() + "]";
        }
        long[] stats = BLOCKS.get(name);
        if (stats == null) {
            if (BLOCKS.size() > 5000) {
                return;
            }
            stats = new long[3];
            BLOCKS.put(name, stats);
        }
        stats[0]++;
        stats[1] += views;
        stats[2] += nanos;
    }

    private static final AtomicLong unchangedSkipped = new AtomicLong();

    /** Copied again, but its blocks are the same as the copy stored: left as it is, no pictures taken. */
    static void unchanged(int cx, int cz, String reason, long nanos) {
        if (!on()) {
            return;
        }
        unchangedSkipped.incrementAndGet();
        Trace trace = TRACES.remove(key(cx, cz));
        line(
            "UNCHANGED " + cx
                + ","
                + cz
                + " reason="
                + (trace != null ? trace.reason : reason)
                + " blocksMs="
                + ms(nanos)
                + " (same blocks, pictures kept)");
    }

    private static final AtomicLong noiseSkipped = new AtomicLong();

    /**
     * Copied again, but only noise changed (fluids flowing, leaves, light: see {@link BlockNoise}): not stored, no
     * pictures taken (render thread) or not stored (writer); copied again seldom from now on.
     */
    static void noise(int cx, int cz, String where, long nanos) {
        if (!on()) {
            return;
        }
        noiseSkipped.incrementAndGet();
        // From the writer the trace is gone already (it ends with the STORE line).
        Trace trace = TRACES.remove(key(cx, cz));
        line(
            "NOISE " + cx
                + ","
                + cz
                + " reason="
                + (trace != null ? trace.reason : "?")
                + " by="
                + where
                + " ms="
                + ms(nanos)
                + " (only fluids, leaves or light changed: not stored, copied again in "
                + IsoMap.NOISY_RECAPTURE_MS / 1000
                + "s at the earliest)");
    }

    /**
     * Its pictures were drawn and are read back from the graphics card next tick; the chunk is finished then (its
     * time so far counts in its copying).
     */
    static void inFlight(int cx, int cz, long blockNanos, long faceNanos, int flights) {
        if (!on()) {
            return;
        }
        Trace trace = TRACES.get(key(cx, cz));
        if (trace != null) {
            if (trace.firstCapture == 0) {
                trace.firstCapture = System.nanoTime() - blockNanos - faceNanos;
            }
            trace.blockCaptureNanos += blockNanos;
            trace.facesNanos += faceNanos;
            trace.captureNanos += blockNanos + faceNanos;
        }
        captureNanosSum.addAndGet(blockNanos + faceNanos);
        picturesDrawn.addAndGet(FaceRenderer.lastDrawn);
        line(
            "IN_FLIGHT " + cx
                + ","
                + cz
                + " blocksMs="
                + ms(blockNanos)
                + " picturesMs="
                + ms(faceNanos)
                + " drawn="
                + FaceRenderer.lastDrawn
                + " batchesReadBack="
                + flights
                + " (pictures read back next tick, then stored)");
    }

    /**
     * Blocks needing pictures since the log started ({@link MapVisibility}): looked at, hidden from every view side,
     * to draw, to draw and hidden, nanos looking; and per kind of block to draw: {to draw, hidden}.
     */
    private static long visChecked, visHidden, visToDraw, visToDrawHidden, visNanos;
    private static final Map<Integer, long[]> HIDDEN_KINDS = new ConcurrentHashMap<>();

    /** A block needing pictures: not drawn at all (hidden from every side) or drawn (render thread). */
    static void visibility(int lookKey, boolean hidden) {
        long[] counts = HIDDEN_KINDS.get(lookKey);
        if (counts == null) {
            if (HIDDEN_KINDS.size() > 5000) {
                return;
            }
            counts = new long[2];
            HIDDEN_KINDS.put(lookKey, counts);
        }
        counts[0]++;
        if (hidden) {
            counts[1]++;
        }
    }

    /**
     * Pictures looked at by {@link MapVisibility} since the log started: hidden by its first step alone (open space
     * reaches no cell around), hidden after following lines, seen; lines followed and skipped; time of each step.
     */
    private static long visByReach, visByLines, visSeen, visLinesFollowed, visLinesSkipped, visReachNanos,
        visLinesNanos;

    /** A chunk's blocks needing pictures were looked at (render thread). */
    static void visibilityTotals(int checked, int hidden, int toDraw, int toDrawHidden, long nanos) {
        visChecked += checked;
        visHidden += hidden;
        visToDraw += toDraw;
        visToDrawHidden += toDrawHidden;
        visNanos += nanos;
        visByReach += FaceRenderer.visHiddenByReach;
        visByLines += FaceRenderer.visHiddenByLines;
        visSeen += FaceRenderer.visSeen;
        visLinesFollowed += FaceRenderer.visLinesFollowed;
        visLinesSkipped += FaceRenderer.visLinesSkipped;
        visReachNanos += FaceRenderer.visReachNanos;
        visLinesNanos += FaceRenderer.visLinesNanos;
    }

    /** Rays that reached a picture left out as hidden from the map (renderer threads): should be none. */
    private static final AtomicLong hiddenReachedCount = new AtomicLong();
    /** Lines logged for them at most per log (the count goes on). */
    private static final int HIDDEN_REACHED_LINES = 40;

    /**
     * A ray of a tile reached a picture left out as hidden ({@link MapVisibility} said the map can't show it from
     * there): the block is drawn from its icons. Counted; the first few logged with where it is (renderer threads).
     *
     * @param side the solid cube's side, -1 for a block that isn't one (then the view side counts)
     */
    static void hiddenReached(int x, int y, int z, int lookKey, int rotation, int side) {
        if (!on()) {
            return;
        }
        long n = hiddenReachedCount.incrementAndGet();
        if (n <= HIDDEN_REACHED_LINES) {
            line(
                "HIDDEN_REACHED " + x
                    + ","
                    + y
                    + ","
                    + z
                    + " "
                    + BlockDiag.name(lookKey)
                    + " view="
                    + rotation
                    + (side >= 0 ? " side=" + side : "")
                    + " (left out as hidden, yet a ray got there: drawn from icons; logged "
                    + HIDDEN_REACHED_LINES
                    + " times at most)");
        }
    }

    /** How many pictures were left out as hidden from the map, and of which kinds of blocks. */
    private static void visibilitySummary(String title) {
        if (visChecked == 0 && hiddenReachedCount.get() == 0) {
            return;
        }
        line(
            title + " hidden from the map (pictures left out, drawn from icons should a ray get there): blocks not "
                + "drawn at all "
                + visHidden
                + "/"
                + visChecked
                + " ("
                + share(visHidden, visChecked)
                + "), of the "
                + visToDraw
                + " blocks drawn pictures left out "
                + visToDrawHidden
                + ", looking took "
                + ms(visNanos)
                + "ms (pictures hidden by reach alone "
                + visByReach
                + ", after following lines "
                + visByLines
                + ", seen "
                + visSeen
                + "; lines followed "
                + visLinesFollowed
                + ", skipped "
                + visLinesSkipped
                + "; reachMs="
                + ms(visReachNanos)
                + " linesMs="
                + ms(visLinesNanos)
                + ") raysReachingHiddenPictures="
                + hiddenReachedCount.get()
                + " (should be 0)");
        List<Map.Entry<Integer, long[]>> kinds = new ArrayList<>(HIDDEN_KINDS.entrySet());
        kinds.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        for (int n = 0; n < Math.min(20, kinds.size()); n++) {
            long[] c = kinds.get(n)
                .getValue();
            if (c[1] == 0) {
                break;
            }
            line(
                title + "   hidden#"
                    + (n + 1)
                    + " "
                    + BlockDiag.name(
                        kinds.get(n)
                            .getKey())
                    + " needingPictures="
                    + c[0]
                    + " notDrawnHidden="
                    + c[1]
                    + " ("
                    + share(c[1], c[0])
                    + ")");
        }
    }

    /** Copying it failed or gave nothing. */
    static void captureFailed(int cx, int cz, String why) {
        if (on()) {
            TRACES.remove(key(cx, cz));
            line("CAPTURE_FAILED " + cx + "," + cz + " " + why);
        }
    }

    /** Handed to the writer; returns the trace the writer finishes. */
    static Trace submitted(int cx, int cz) {
        if (!on()) {
            return null;
        }
        Trace trace = TRACES.remove(key(cx, cz));
        if (trace == null) {
            return null;
        }
        trace.submitted = System.nanoTime();
        trace.writerBacklog = (int) writerBacklog.incrementAndGet();
        line("SUBMIT " + cx + "," + cz + " writerBacklog=" + trace.writerBacklog);
        return trace;
    }

    // ---------------------------------------------------------------- writer thread

    static void writerStart(Trace trace) {
        if (trace != null) {
            trace.writerStart = System.nanoTime();
        }
    }

    /**
     * The writer stored it.
     *
     * @param timing see {@link BlockStore#put}: region header, blob read, encode, trim nanos, blob bytes, changed
     */
    static void stored(Trace trace, int cx, int cz, long keepNanos, long[] timing) {
        if (trace != null) {
            writerBacklog.decrementAndGet();
        }
        if (!on()) {
            return;
        }
        long now = System.nanoTime();
        boolean changed = timing[5] != 0;
        (changed ? stored : storedSame).incrementAndGet();
        StringBuilder b = new StringBuilder();
        b.append("STORE ")
            .append(cx)
            .append(',')
            .append(cz)
            .append(" changed=")
            .append(changed)
            .append(" keepPicturesMs=")
            .append(ms(keepNanos))
            .append(" encodeMs=")
            .append(ms(timing[2]))
            .append(" regionHeaderMs=")
            .append(ms(timing[0]))
            .append(" regionBlobsReadMs=")
            .append(ms(timing[1]))
            .append(" lockWaitMs=")
            .append(ms(timing[6]))
            .append(" trimMs=")
            .append(ms(timing[3]))
            .append(" bytes=")
            .append(timing[4]);
        if (trace != null) {
            trace.writerEnd = now;
            writerNanos.addAndGet(now - trace.writerStart);
            b.append(" waitedForWriterMs=")
                .append(span(trace.submitted, trace.writerStart))
                .append(" writerMs=")
                .append(span(trace.writerStart, now));
        }
        line(b.toString());
        if (trace != null) {
            done(trace);
        }
    }

    private static void done(Trace t) {
        long start = t.seen != 0 ? t.seen : t.settled != 0 ? t.settled : t.scanned != 0 ? t.scanned : t.queued;
        long[] phases = { t.writerEnd - start, t.seen != 0 && t.settled != 0 ? t.settled - t.seen : -1,
            t.settled != 0 && t.scanned != 0 ? t.scanned - t.settled : -1,
            t.scanned != 0 && t.queued != 0 ? t.queued - t.scanned : -1,
            t.queued != 0 && t.firstCapture != 0 ? t.firstCapture - t.queued : -1,
            t.firstCapture != 0 ? t.lastCapture - t.firstCapture : -1, t.captureNanos, t.writerStart - t.submitted,
            t.writerEnd - t.writerStart, t.loaded != 0 && t.seen != 0 ? t.seen - t.loaded : -1,
            t.loaded != 0 ? t.writerEnd - t.loaded : -1 };
        StringBuilder b = new StringBuilder("DONE ").append(t.cx)
            .append(',')
            .append(t.cz)
            .append(" reason=")
            .append(t.reason)
            .append(" queue=")
            .append(t.queue);
        for (int i = 0; i < PHASES.length; i++) {
            b.append(' ')
                .append(PHASES[i])
                .append('=')
                .append(phases[i] < 0 ? "-" : ms(phases[i]));
        }
        b.append(" captures=")
            .append(t.captures)
            .append(" blocksCpu=")
            .append(ms(t.blockCaptureNanos))
            .append(" picturesCpu=")
            .append(ms(t.facesNanos))
            .append(" faces=")
            .append(t.facesFound)
            .append(" writerBacklogAtSubmit=")
            .append(t.writerBacklog);
        line(b.toString());
        if (DONE.size() < MAX_DONE) {
            DONE.add(phases);
            DONE_NAMES.add(t.cx + "," + t.cz + " " + t.reason);
        }
        if ("fresh".equals(t.reason)) {
            freshStored.incrementAndGet();
            synchronized (FRESH_ATTEMPTS) {
                if (FRESH_ATTEMPTS.size() < 200_000) {
                    FRESH_ATTEMPTS.add(t.captures);
                }
            }
            synchronized (FRESH_TIMES) {
                if (FRESH_TIMES.size() < 100_000) {
                    FRESH_TIMES.add(phases[0]);
                }
            }
        } else {
            againStored.incrementAndGet();
        }
    }

    /** Per kind of block: cells changed (block, light), pictures changed, in copies stored as changed. */
    private static final Map<String, long[]> CHANGED_BY = new ConcurrentHashMap<>();
    /** Copies stored as changed, by what changed: blocks, only light, only colors, only pictures, only the height. */
    private static final AtomicLong diffBlocks = new AtomicLong(), diffLightOnly = new AtomicLong(),
        diffColorsOnly = new AtomicLong(), diffPicturesOnly = new AtomicLong(), diffOther = new AtomicLong();

    private static String blockName(int id) {
        try {
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockById(id);
            return String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(block));
        } catch (RuntimeException e) {
            return "#" + id;
        }
    }

    private static void countChange(Map<String, Integer> local, String name, int column) {
        local.merge(name, 1, Integer::sum);
        long[] stats = CHANGED_BY.get(name);
        if (stats == null) {
            if (CHANGED_BY.size() > 5000) {
                return;
            }
            stats = new long[3];
            CHANGED_BY.put(name, stats);
        }
        synchronized (stats) {
            stats[column]++;
        }
    }

    /**
     * A copy was stored as changed (writer thread): what changed against the copy before. Blocks (id or metadata),
     * only the light in cells, biome colors, the pictures of cells (and of which blocks), the height range.
     */
    static void chunkDiff(int cx, int cz, Trace trace, ChunkBlocks before, ChunkBlocks now) {
        if (!on()) {
            return;
        }
        int blocks = 0, light = 0;
        Map<String, Integer> blockNames = new java.util.HashMap<>(), pictureNames = new java.util.HashMap<>();
        int from = Math.max(before.yMin, now.yMin), to = Math.min(before.yMax, now.yMax);
        for (int y = from; y <= to; y++) {
            for (int i = 0; i < 256; i++) {
                int a = before.cells[((y - before.yMin) << 8) | i], b = now.cells[((y - now.yMin) << 8) | i];
                if (a == b) {
                    continue;
                }
                if (ChunkBlocks.lookKey(a) != ChunkBlocks.lookKey(b)) {
                    blocks++;
                    countChange(blockNames, blockName(ChunkBlocks.blockId(b)), 0);
                } else {
                    light++;
                    if (light <= 64) {
                        countChange(new java.util.HashMap<>(), blockName(ChunkBlocks.blockId(b)), 1);
                    }
                }
            }
        }
        boolean range = before.yMin != now.yMin || before.yMax != now.yMax;
        boolean colors = !Arrays.equals(before.grass, now.grass) || !Arrays.equals(before.foliage, now.foliage)
            || !Arrays.equals(before.water, now.water);
        // Pictures by cell: the same cell in both copies (cells are indices from yMin, so compared by place).
        Map<Long, Integer> old = new java.util.HashMap<>();
        for (int n = 0; n < before.faceCells.length; n++) {
            old.put(place(before, before.faceCells[n]), n);
        }
        int pictures = 0, picturesNew = 0, picturesGone = 0, generation = 0;
        if (before.faceGeneration != now.faceGeneration) {
            generation = 1;
        }
        for (int n = 0; n < now.faceCells.length; n++) {
            int cell = now.faceCells[n];
            Integer m = old.remove(place(now, cell));
            if (m == null) {
                picturesNew++;
                continue;
            }
            for (int slot = 0; slot < ChunkBlocks.PER_CELL; slot++) {
                if (before.faceIds[m * ChunkBlocks.PER_CELL + slot] != now.faceIds[n * ChunkBlocks.PER_CELL + slot]) {
                    pictures++;
                    countChange(pictureNames, blockName(ChunkBlocks.blockId(now.cells[cell])), 2);
                    break;
                }
            }
        }
        picturesGone = old.size();
        boolean picturesChanged = pictures + picturesNew + picturesGone > 0;
        // What changed, joined: BLOCKS, LIGHT, COLORS, PICTURES, HEIGHT; NOTHING_SEEN if only other stored data.
        StringBuilder parts = new StringBuilder();
        if (blocks > 0) {
            parts.append("+BLOCKS");
        }
        if (light > 0) {
            parts.append("+LIGHT");
        }
        if (colors) {
            parts.append("+COLORS");
        }
        if (picturesChanged) {
            parts.append("+PICTURES");
        }
        if (range) {
            parts.append("+HEIGHT");
        }
        String what = parts.length() == 0 ? "NOTHING_SEEN" : parts.substring(1);
        if (blocks > 0) {
            diffBlocks.incrementAndGet();
        } else if (what.equals("PICTURES")) {
            diffPicturesOnly.incrementAndGet();
        } else if (what.equals("LIGHT")) {
            diffLightOnly.incrementAndGet();
        } else if (what.equals("COLORS")) {
            diffColorsOnly.incrementAndGet();
        } else {
            diffOther.incrementAndGet();
        }
        line(
            "CHUNK_DIFF " + cx
                + ","
                + cz
                + " "
                + what
                + " reason="
                + (trace == null ? "?" : trace.reason)
                + " blocks="
                + blocks
                + " lightOnlyCells="
                + light
                + " pictures="
                + pictures
                + " picturesNew="
                + picturesNew
                + " picturesGone="
                + picturesGone
                + " colors="
                + colors
                + " heightRange="
                + (range ? before.yMin + ".." + before.yMax + "->" + now.yMin + ".." + now.yMax : "same")
                + (generation != 0 ? " otherPalette" : "")
                + (blockNames.isEmpty() ? "" : " changedBlocks=" + top(blockNames))
                + (pictureNames.isEmpty() ? "" : " changedPicturesOf=" + top(pictureNames)));
    }

    private static long place(ChunkBlocks blocks, int cell) {
        return (long) (blocks.yMin + (cell >> 8)) << 8 | (cell & 255);
    }

    /** The five most frequent names, with counts. */
    private static String top(Map<String, Integer> names) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(names.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder b = new StringBuilder();
        for (int n = 0; n < Math.min(5, list.size()); n++) {
            b.append(n == 0 ? "" : ",")
                .append(
                    list.get(n)
                        .getKey())
                .append('x')
                .append(
                    list.get(n)
                        .getValue());
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- saver thread

    static void saverStart(String what) {
        saverBusySince = System.nanoTime();
        saverTask = what;
        log("SAVER_START " + what);
    }

    static void saverEnd(String what) {
        long since = saverBusySince;
        saverBusySince = 0;
        log("SAVER_END " + what + " ms=" + ms(System.nanoTime() - since));
    }

    static void regionSaved(int dimension, int rx, int rz, long bytes, long copyNanos, long writeNanos, boolean ok) {
        log(
            "REGION_SAVED dim=" + dimension
                + " r="
                + rx
                + ","
                + rz
                + " bytes="
                + bytes
                + " copyUnderLockMs="
                + ms(copyNanos)
                + " writeMs="
                + ms(writeNanos)
                + (ok ? "" : " FAILED"));
    }

    static void regionRead(int dimension, int rx, int rz, String what, long nanos, long bytes) {
        TileWork w = TILE_WORK.get();
        if (w != null) {
            if (what.startsWith("header")) {
                w.headerReads++;
            } else {
                w.blobReads++;
            }
            w.readNanos += nanos;
            w.readBytes += bytes;
        }
        log(
            "REGION_READ dim=" + dimension
                + " r="
                + rx
                + ","
                + rz
                + " "
                + what
                + " ms="
                + ms(nanos)
                + " bytes="
                + bytes
                + " ["
                + Thread.currentThread()
                    .getName()
                + "]");
    }

    static void regionTrimmed(int dimension, int dropped, long bytesBefore, long bytesAfter, long nanos) {
        log(
            "BLOBS_TRIMMED dim=" + dimension
                + " regionsDropped="
                + dropped
                + " mbBefore="
                + (bytesBefore >> 20)
                + " mbAfter="
                + (bytesAfter >> 20)
                + " ms="
                + ms(nanos));
    }

    // ---------------------------------------------------------------- tiles

    /** Chunk changes reached the tiles in memory. */
    static void marked(int dimension, int cx, int cz, long changeTimeMs, int tiles, int tilesInMemory, int[] box) {
        if (on()) {
            line(
                "MARKED " + cx
                    + ","
                    + cz
                    + " dim="
                    + dimension
                    + " delayMs="
                    + (System.currentTimeMillis() - changeTimeMs)
                    + " changed="
                    + (box == null ? "whole"
                        : box[0] + "," + box[1]
                            + ","
                            + box[2]
                            + ".."
                            + box[3]
                            + ","
                            + box[4]
                            + ","
                            + box[5])
                    + " tilesDirtied="
                    + tiles
                    + " tilesInMemory="
                    + tilesInMemory);
        }
    }

    /**
     * @param kind new (nothing to show yet), stale (only out of date: behind the new ones), hole (a chunk was stored
     *             where it shows nothing: as soon as the new ones)
     */
    static void tileQueued(IsoTiles.Key key, String kind, int queueSize) {
        if (on()) {
            line("TILE_QUEUED " + tile(key) + " " + kind + " queue=" + queueSize);
            levelStats(key.level)["new".equals(kind) ? 0 : 1]++;
        }
    }

    /**
     * Per level of the 3D view: tiles queued with nothing to show yet, queued only out of date, drawn, and their
     * drawing times (ms) for percentiles.
     */
    private static final Map<Integer, long[]> LEVEL_COUNTS = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Long>> LEVEL_TIMES = new ConcurrentHashMap<>();

    private static long[] levelStats(int level) {
        return LEVEL_COUNTS.computeIfAbsent(level, k -> new long[3]);
    }

    /** What one tile renderer met while making a tile (its own thread). */
    static final class TileWork {

        /** What reading the tile's file gave: none, ok, ok-empty, stale (why), bad-magic, io-error. */
        String disk = "-";
        long diskNanos;
        long diskBytes;
        /** Chunks the rays entered: with 3D blocks, with nothing. */
        int storeChunks, noChunks;
        /** Region files read for it: 3D block headers and blobs. */
        int headerReads, blobReads;
        long readNanos, readBytes;
        int decoded;
        long decodeNanos;
        /** Waits for block looks from the render thread, their time, and how many gave up. */
        int looksWaits, looksTimeouts;
        long looksNanos;
        boolean noPalette;
        /** What was written to the tile's file. */
        String saved = "-";
        long savedNanos;
        /** Ray tracing alone, and the rays cast. */
        long traceNanos;
        int rays;
        /** Finding whether the tile's file is out of date (the chunks under it). */
        long checkNanos;
    }

    private static final ThreadLocal<TileWork> TILE_WORK = new ThreadLocal<>();
    /** Tiles by what happened to them (source, reading the file, saving, warnings), for the summaries. */
    private static final Map<String, AtomicLong> TILE_COUNTS = new ConcurrentHashMap<>();
    /** How long the screen took to be complete, each time (ms). */
    private static final List<Long> VIEW_TIMES = Collections.synchronizedList(new ArrayList<>());

    private static void countTile(String what) {
        TILE_COUNTS.computeIfAbsent(what, k -> new AtomicLong())
            .incrementAndGet();
    }

    /** First word of a description: "stale (...)" -> "stale". */
    private static String firstWord(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    /** A warning about a tile: counted for the summaries and written. */
    private static void warn(String kind, String text) {
        countTile("warn " + kind);
        line("TILE_WARN " + kind + " " + text);
    }

    /** A renderer starts a tile: what happens on this thread until {@link #tileDone} is counted for it. */
    static void tileStart() {
        TILE_WORK.set(on() ? new TileWork() : null);
    }

    /** What the tile renderer on this thread is counting, null if none (or the log is off). */
    static TileWork work() {
        return TILE_WORK.get();
    }

    /** The rays of a tile entered a chunk: 0 = 3D blocks, 2 = nothing there. */
    static void tileChunk(int kind) {
        TileWork w = TILE_WORK.get();
        if (w != null) {
            if (kind == 0) {
                w.storeChunks++;
            } else {
                w.noChunks++;
            }
        }
    }

    /** A chunk's blocks were unpacked for a tile. */
    static void tileDecoded(long nanos) {
        TileWork w = TILE_WORK.get();
        if (w != null) {
            w.decoded++;
            w.decodeNanos += nanos;
        }
    }

    /** A renderer waited for the looks of a chunk's blocks; ok=false when it gave up after 5 s. */
    static void looksWait(int asked, long nanos, boolean ok) {
        TileWork w = TILE_WORK.get();
        if (w != null) {
            w.looksWaits++;
            w.looksNanos += nanos;
            if (!ok) {
                w.looksTimeouts++;
            }
        }
        if (on() && (!ok || nanos > 200_000_000L)) {
            countTile(ok ? "looks slow wait" : "warn LOOKS_WAIT gave up");
            line(
                (ok ? "" : "TILE_WARN ") + "LOOKS_WAIT blocks="
                    + asked
                    + " ms="
                    + ms(nanos)
                    + (ok ? " (slow: the render thread works them out only while it draws frames)"
                        : " GAVE UP: the tile is drawn with blocks missing and again later"));
        }
    }

    static void tileDone(IsoTiles.Key key, long waitedNanos, long nanos, String source, String retry, int[] pixels,
        int[] nightPixels) {
        TileWork w = TILE_WORK.get();
        TILE_WORK.remove();
        if (!on()) {
            return;
        }
        boolean empty = pixels == null;
        tilesDrawn.incrementAndGet();
        tileNanos.addAndGet(nanos);
        levelStats(key.level)[2]++;
        List<Long> times = LEVEL_TIMES.computeIfAbsent(key.level, k -> Collections.synchronizedList(new ArrayList<>()));
        if (times.size() < 50_000) {
            times.add(nanos / 1_000_000);
        }
        if ("disk".equals(source)) {
            tilesFromDisk.incrementAndGet();
        }
        if (retry != null) {
            tilesRetry.incrementAndGet();
        }
        int[] day = pixelStats(pixels), night = pixelStats(nightPixels);
        int total = IsoProjection.TILE_PIXELS * IsoProjection.TILE_PIXELS;
        StringBuilder b = new StringBuilder("TILE_DONE ").append(tile(key))
            .append(" src=")
            .append(source)
            .append(" waitedMs=")
            .append(ms(waitedNanos))
            .append(" ms=")
            .append(ms(nanos));
        if (w != null) {
            b.append(" disk=")
                .append(w.disk);
            if (w.diskNanos > 0) {
                b.append(" diskMs=")
                    .append(ms(w.diskNanos))
                    .append(" diskBytes=")
                    .append(w.diskBytes)
                    .append(" checkMs=")
                    .append(ms(w.checkNanos));
            }
            if (w.rays > 0) {
                b.append(" trace[ms=")
                    .append(ms(w.traceNanos))
                    .append(" rays=")
                    .append(w.rays)
                    .append(" usPerRay=")
                    .append(String.format(Locale.ROOT, "%.2f", w.traceNanos / 1e3 / w.rays))
                    .append(']');
            }
            if ("trace".equals(source)) {
                b.append(" chunks[store=")
                    .append(w.storeChunks)
                    .append(" none=")
                    .append(w.noChunks)
                    .append("] reads[headers=")
                    .append(w.headerReads)
                    .append(" blobs=")
                    .append(w.blobReads)
                    .append(" ms=")
                    .append(ms(w.readNanos))
                    .append(" kb=")
                    .append(w.readBytes >> 10)
                    .append("] decoded=")
                    .append(w.decoded)
                    .append(" decodeMs=")
                    .append(ms(w.decodeNanos))
                    .append(" waits[looks=")
                    .append(w.looksWaits)
                    .append(" ms=")
                    .append(ms(w.looksNanos))
                    .append(w.looksTimeouts > 0 ? " gaveUp=" + w.looksTimeouts : "")
                    .append("]")
                    .append(w.noPalette ? " NO_PALETTE (block pictures not loaded: drawn from icons)" : "");
            }
            b.append(" saved=")
                .append(w.saved);
            if (w.savedNanos > 0) {
                b.append(" saveMs=")
                    .append(ms(w.savedNanos));
            }
        }
        if (empty) {
            b.append(" EMPTY");
        } else {
            b.append(" px[clear=")
                .append(percent(day[0], total))
                .append(" black=")
                .append(percent(day[1], total))
                .append(" dark=")
                .append(percent(day[2], total))
                .append(" avgLum=")
                .append(day[3])
                .append("] night[black=")
                .append(percent(night[1], total))
                .append(" avgLum=")
                .append(night[3])
                .append("]");
        }
        if (retry != null) {
            b.append(" RETRY(")
                .append(retry)
                .append(", drawn again)");
        }
        line(b.toString());
        countTile("src " + source + (empty ? " empty" : ""));
        if (w != null) {
            countTile("disk " + firstWord(w.disk));
            countTile("saved " + firstWord(w.saved));
            if (w.looksTimeouts > 0) {
                countTile("looks gave up");
            }
        }
        if (retry != null) {
            countTile("retry");
        }

        // What looks wrong, on lines of their own so they are easy to find.
        String where = tile(key) + " src=" + source;
        if (!empty && day[1] + day[2] > total / 2) {
            warn(
                "DARK",
                where + " black="
                    + percent(day[1], total)
                    + " dark="
                    + percent(day[2], total)
                    + " avgLum="
                    + day[3]
                    + (w != null && w.noPalette ? " (no block pictures)" : ""));
        }
        if (!empty && day[0] > total * 3 / 4 && w != null && w.storeChunks > 0) {
            warn(
                "MOSTLY_CLEAR",
                where + " clear="
                    + percent(day[0], total)
                    + " though the rays met "
                    + w.storeChunks
                    + " chunks with blocks");
        }
        if (w != null && (w.disk.startsWith("io-error") || w.disk.startsWith("bad-magic"))) {
            warn("BAD_FILE", where + " disk=" + w.disk + " (drawn anew)");
        }
        if (nanos > 2_000_000_000L) {
            warn(
                "SLOW",
                where + " ms="
                    + ms(nanos)
                    + (w == null ? ""
                        : " readMs=" + ms(
                            w.readNanos) + " looksWaitMs=" + ms(w.looksNanos) + " decodeMs=" + ms(w.decodeNanos)));
        }
    }

    /** {clear, black, dark, average brightness 0-255 of the not clear ones} of a tile's pixels. */
    private static int[] pixelStats(int[] pixels) {
        if (pixels == null) {
            return new int[4];
        }
        int clear = 0, black = 0, dark = 0;
        long lum = 0;
        for (int c : pixels) {
            if ((c >>> 24) == 0) {
                clear++;
                continue;
            }
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            int l = (r * 299 + g * 587 + bl * 114) / 1000;
            lum += l;
            if (Math.max(r, Math.max(g, bl)) < 12) {
                black++;
            } else if (l < 40) {
                dark++;
            }
        }
        int shown = pixels.length - clear;
        return new int[] { clear, black, dark, shown == 0 ? 0 : (int) (lum / shown) };
    }

    private static String percent(int n, int total) {
        return n * 100 / Math.max(1, total) + "%";
    }

    /** What the 3D view looked like this frame (render thread); written once a second while it isn't complete. */
    private static String viewKey;
    private static long viewSince, viewLastLine, viewLastDraw;
    private static int viewUploads;

    static void view(int dimension, int rotation, int level, int onScreen, int ready, int empty, int fromCoarser,
        int holes, int queued, int results, int uploaded, int tilesInMemory) {
        if (!on()) {
            return;
        }
        long now = System.nanoTime();
        String key = dimension + "/" + rotation + "/" + level;
        viewUploads += uploaded;
        if (!key.equals(viewKey) || now - viewLastDraw > 2_000_000_000L) {
            // Opened again, zoomed to another level, turned or another dimension.
            viewKey = key;
            viewSince = now;
            viewLastLine = 0;
            viewUploads = uploaded;
            line(
                "VIEW_START dim=" + dimension
                    + " rot="
                    + rotation
                    + " L"
                    + level
                    + " onScreen="
                    + onScreen
                    + " readyAlready="
                    + ready
                    + " tilesInMemory="
                    + tilesInMemory);
        }
        viewLastDraw = now;
        boolean complete = ready == onScreen;
        if (complete) {
            if (viewSince != 0) {
                line(
                    "VIEW_COMPLETE dim=" + dimension
                        + " rot="
                        + rotation
                        + " L"
                        + level
                        + " after="
                        + ms(now - viewSince)
                        + "ms onScreen="
                        + onScreen
                        + " empty="
                        + empty
                        + (empty * 2 > onScreen ? " (more than half empty: dark background shows)" : ""));
                VIEW_TIMES.add((now - viewSince) / 1_000_000L);
                viewSince = 0;
            }
            return;
        }
        if (viewSince == 0) {
            // Was complete, now some aren't (moved, or chunks changed): a new wait starts.
            viewSince = now;
        }
        if (now - viewLastLine >= 1_000_000_000L) {
            viewLastLine = now;
            line(
                "VIEW dim=" + dimension
                    + " rot="
                    + rotation
                    + " L"
                    + level
                    + " waitingMs="
                    + ms(now - viewSince)
                    + " onScreen="
                    + onScreen
                    + " ready="
                    + ready
                    + " empty="
                    + empty
                    + " fromCoarser="
                    + fromCoarser
                    + " holes="
                    + holes
                    + " queued="
                    + queued
                    + " resultsWaitingUpload="
                    + results
                    + " uploaded="
                    + viewUploads
                    + " tilesInMemory="
                    + tilesInMemory);
            viewUploads = 0;
        }
    }

    static void tileSkipped(IsoTiles.Key key) {
        if (on()) {
            tilesSkipped.incrementAndGet();
            line("TILE_SKIPPED " + tile(key) + " no longer on screen");
        }
    }

    /** A frame of the 3D view was drawn (render thread). */
    static void frameDrawn(int quads, int textured, int backlog) {
        viewFrames++;
        viewQuads += quads;
        viewQuadsMax = Math.max(viewQuadsMax, quads);
        viewTextured = textured;
        viewBacklog = backlog;
        viewBacklogMax = Math.max(viewBacklogMax, backlog);
    }

    /**
     * A tile was put on the graphics card and is on screen now (render thread).
     *
     * @param changeMs    from the oldest chunk change it shows to now, -1 if it shows none
     * @param newNanos    from when it was first wanted with nothing to show, -1 if it had a picture
     * @param waitNanos   from the renderer finishing it to its upload, -1 if not known
     * @param queuedNanos from its job being queued to its upload, -1 if not known
     * @param nanos       putting it on the card
     */
    static void tileShown(IsoTiles.Key key, String source, boolean empty, long changeMs, long newNanos,
        long waitNanos, long queuedNanos, long nanos) {
        if (!on()) {
            return;
        }
        uploads.incrementAndGet();
        uploadNanos.addAndGet(nanos);
        uploadMaxNanos.accumulateAndGet(nanos, Math::max);
        if (changeMs >= 0 && CHANGE_SHOWN.size() < 100_000) {
            CHANGE_SHOWN.add(changeMs);
        }
        if (newNanos >= 0 && NEW_SHOWN.size() < 100_000) {
            NEW_SHOWN.add(newNanos / 1_000_000L);
        }
        if (waitNanos >= 0 && UPLOAD_WAITS.size() < 100_000) {
            UPLOAD_WAITS.add(waitNanos / 1_000_000L);
        }
        line(
            "TILE_SHOWN " + tile(key)
                + " src="
                + source
                + (empty ? " empty" : "")
                + " changeMs="
                + (changeMs < 0 ? "-" : String.valueOf(changeMs))
                + " newMs="
                + (newNanos < 0 ? "-" : ms(newNanos))
                + " queuedToUploadMs="
                + (queuedNanos < 0 ? "-" : ms(queuedNanos))
                + " uploadWaitMs="
                + (waitNanos < 0 ? "-" : ms(waitNanos))
                + " uploadMs="
                + ms(nanos));
    }

    private static String tile(IsoTiles.Key key) {
        return "rot" + key.rotation + " L" + key.level + " " + key.tu + "," + key.tv;
    }

    // ---------------------------------------------------------------- per tick (render thread)

    /** The game let a chunk go. */
    static void unload(int cx, int cz, String what) {
        if (!on()) {
            return;
        }
        if (what.startsWith("skipped")) {
            unloadSkipped.incrementAndGet();
        } else if (what.startsWith("copy")) {
            unloadCopied.incrementAndGet();
        }
        line("UNLOAD " + cx + "," + cz + " " + what);
    }

    /** End of {@link IsoMap#tick}: time spent and the state of the queues. */
    private static long lastTickAt;
    private static long lastGcCount, lastGcMillis;

    /** Chunks unpacked by the tile renderers: from the cache, decoded (and the time), regions read for them. */
    static final AtomicLong decodedHits = new AtomicLong(), decodedMisses = new AtomicLong(),
        decodeNanos = new AtomicLong();

    static void tick(long nanos, boolean outOfTime, int fresh, int again, int unfinished, int tilesQueued,
        long paletteUnsaved, FacePalette palette) {
        if (!on()) {
            return;
        }
        long at = System.nanoTime();
        if (lastTickAt != 0 && at - lastTickAt > 250_000_000L) {
            // The game didn't tick for a while: a freeze (the 3D map's own share is in tickMs).
            line("STALL no client tick for " + ms(at - lastTickAt) + "ms (3D map took " + ms(nanos) + "ms of it)");
        }
        lastTickAt = at;
        ticks.incrementAndGet();
        tickNanos.addAndGet(nanos);
        if (outOfTime) {
            ticksOutOfTime.incrementAndGet();
        }
        if (nanos > 50_000_000L) {
            line("SLOW_TICK ms=" + ms(nanos) + " freshQueue=" + fresh + " againQueue=" + again);
        }
        long now = System.currentTimeMillis();
        boolean busy = fresh + again + unfinished + tilesQueued > 0 || writerBacklog.get() > 0
            || saverBusySince != 0
            || capturesDone.get() > 0;
        if (now - lastStats >= (busy ? 1000 : 10_000)) {
            long statsMs = lastStats == 0 ? 1000 : now - lastStats;
            lastStats = now;
            long tileSum = tileNanos.getAndSet(0);
            Runtime runtime = Runtime.getRuntime();
            long since = saverBusySince;
            line(
                "STATS freshQueue=" + fresh
                    + " againQueue="
                    + again
                    + " unfinished="
                    + unfinished
                    + " traced="
                    + TRACES.size()
                    + " settling="
                    + SEEN.size()
                    + " writerBacklog="
                    + writerBacklog.get()
                    + " saver="
                    + (since == 0 ? "idle" : saverTask + " for " + ms(System.nanoTime() - since) + "ms")
                    + " captures="
                    + capturesDone.getAndSet(0)
                    + " incomplete="
                    + incompleteCaptures.getAndSet(0)
                    + " unchanged="
                    + unchangedSkipped.getAndSet(0)
                    + " noise="
                    + noiseSkipped.getAndSet(0)
                    + " captureMs="
                    + ms(captureNanosSum.getAndSet(0))
                    + " picturesReadLaterMs="
                    + takeFinishNanos()
                    + " stored="
                    + stored.getAndSet(0)
                    + " storedUnchanged="
                    + storedSame.getAndSet(0)
                    + " writerMs="
                    + ms(writerNanos.getAndSet(0))
                    + " ticks="
                    + ticks.getAndSet(0)
                    + " tickMs="
                    + ms(tickNanos.getAndSet(0))
                    + " ticksOutOfTime="
                    + ticksOutOfTime.getAndSet(0)
                    + " unloadCopied="
                    + unloadCopied.getAndSet(0)
                    + " unloadSkipped="
                    + unloadSkipped.getAndSet(0)
                    + " scanDeferred="
                    + scanDeferred.getAndSet(0)
                    + " tilesQueued="
                    + tilesQueued
                    + " tilesDrawn="
                    + tilesDrawn.getAndSet(0)
                    + " tileMs="
                    + ms(tileSum)
                    + " renderers["
                    + renderers
                    + " busy="
                    + share(tileSum, renderers * statsMs * 1_000_000L)
                    + "]"
                    + viewStats()
                    + " tilesFromDisk="
                    + tilesFromDisk.getAndSet(0)
                    + " tilesRetry="
                    + tilesRetry.getAndSet(0)
                    + " tilesSkipped="
                    + tilesSkipped.getAndSet(0)
                    + " looksPending="
                    + BlockLooks.pending()
                    + " picturesUnsavedMB="
                    + (paletteUnsaved >> 20)
                    + gc()
                    + (palette == null ? ""
                        : " sprites[new=" + palette.spritesNew
                            + " known="
                            + palette.spritesKnown
                            + " empty="
                            + palette.spritesEmpty
                            + " refused="
                            + palette.spritesRefused
                            + " total="
                            + palette.size()
                            + "]")
                    + " decoded[hit="
                    + decodedHits.getAndSet(0)
                    + " miss="
                    + decodedMisses.getAndSet(0)
                    + " ms="
                    + ms(decodeNanos.getAndSet(0))
                    + "]"
                    + " heapUsedMB="
                    + ((runtime.totalMemory() - runtime.freeMemory()) >> 20));
        }
        if (now - lastPerf >= PERF_MS) {
            lastPerf = now;
            perf();
        }
        if (lastSummary == 0) {
            lastSummary = now;
        } else if (now - lastSummary >= 300_000) {
            lastSummary = now;
            summary("SUMMARY");
        }
    }

    /** The 3D view's frames since the last stats line, and the tiles put on the graphics card; then starts again. */
    private static String viewStats() {
        long n = uploads.getAndSet(0), nanos = uploadNanos.getAndSet(0), max = uploadMaxNanos.getAndSet(0);
        if (viewFrames == 0 && n == 0) {
            return "";
        }
        String text = " view[frames=" + viewFrames
            + " quadsPerFrame="
            + (viewFrames == 0 ? 0 : viewQuads / viewFrames)
            + " quadsMax="
            + viewQuadsMax
            + " textured="
            + viewTextured
            + " uploadBacklog="
            + viewBacklog
            + " uploadBacklogMax="
            + viewBacklogMax
            + " uploads="
            + n
            + " uploadMs="
            + ms(nanos)
            + " uploadMaxMs="
            + ms(max)
            + "]";
        viewFrames = viewQuads = viewQuadsMax = 0;
        viewBacklogMax = 0;
        return text;
    }

    /** "ms[batches=n readMs=r]" of the pictures read back later since the last stats line; then starts again. */
    private static String takeFinishNanos() {
        String text = ms(FaceRenderer.finishNanos) + "[batches="
            + FaceRenderer.finishBatches
            + " readMs="
            + ms(FaceRenderer.finishReadNanos)
            + "]";
        FaceRenderer.finishNanos = FaceRenderer.finishReadNanos = FaceRenderer.finishBatches = 0;
        return text;
    }

    /** Garbage collections since the last stats line. */
    private static String gc() {
        long count = 0, millis = 0;
        for (java.lang.management.GarbageCollectorMXBean bean : java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            millis += Math.max(0, bean.getCollectionTime());
        }
        String text = " gc=" + (count - lastGcCount) + " gcMs=" + (millis - lastGcMillis);
        lastGcCount = count;
        lastGcMillis = millis;
        return text;
    }

    // ---------------------------------------------------------------- performance

    /** How often a PERF line is written. */
    private static final long PERF_MS = 5000;
    private static long lastPerf, lastPerfNanos, perfStart;
    /** Each thread's CPU time when last looked at, and each group of threads' CPU time since the log started. */
    private static final Map<Long, Long> THREAD_CPU = new ConcurrentHashMap<>();
    private static final Map<String, Long> THREAD_TOTALS = new ConcurrentHashMap<>();
    /** Chunks put on the 3D map since the last PERF line: new ones and ones copied again; how long new ones took. */
    private static final AtomicLong freshStored = new AtomicLong(), againStored = new AtomicLong();
    /** Blocks given pictures from a cache (by kind, surroundings or place) and blocks drawn, since the last PERF. */
    private static final AtomicLong picturesReused = new AtomicLong(), picturesDrawn = new AtomicLong();
    private static final List<Long> FRESH_TIMES = new ArrayList<>();

    /** One decimal. */
    private static String f1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Percent of {@code part} in {@code whole}, no decimals. */
    private static String share(long part, long whole) {
        return whole <= 0 ? "-" : Math.round(part * 100.0 / whole) + "%";
    }

    /**
     * PERF: frames and ticks and what the mod took of them, the threads' CPU, and how fast chunks get onto the 3D
     * map, since the last one.
     */
    private static void perf() {
        long nanos = System.nanoTime();
        double seconds = Math.max(0.001, (nanos - lastPerfNanos) / 1e9);
        lastPerfNanos = nanos;
        Perf.Sample sample = Perf.take();
        StringBuilder b = new StringBuilder("PERF");
        long[] frames = sample.frames, ticks = sample.ticks, server = sample.serverTicks;
        b.append(" fps=")
            .append(f1(frames[0] / seconds));
        if (frames[0] > 0) {
            b.append(" frameMs[avg=")
                .append(ms(frames[1] / frames[0]))
                .append(" p50=")
                .append(ms(frames[3]))
                .append(" p95=")
                .append(ms(frames[4]))
                .append(" p99=")
                .append(ms(frames[5]))
                .append(" max=")
                .append(ms(frames[2]))
                .append(']');
        }
        b.append(" clientTps=")
            .append(f1(ticks[0] / seconds));
        if (ticks[0] > 0) {
            b.append(" tickMs[avg=")
                .append(ms(ticks[1] / ticks[0]))
                .append(" p95=")
                .append(ms(ticks[4]))
                .append(" max=")
                .append(ms(ticks[2]))
                .append(']');
        }
        if (server[0] > 0) {
            // Single player: the game's own server, in the same process.
            b.append(" serverTps=")
                .append(f1(Math.min(20, server[0] / seconds)))
                .append(" mspt[avg=")
                .append(ms(server[1] / server[0]))
                .append(" p95=")
                .append(ms(server[4]))
                .append(" max=")
                .append(ms(server[2]))
                .append(']');
        }
        parts(b, sample, Perf.Where.FRAME, " frame:", frames);
        parts(b, sample, Perf.Where.TICK, " tick:", ticks);
        parts(b, sample, Perf.Where.SERVER, " server:", server);
        b.append(threadCpu(nanos - perfStart, seconds));
        long reused = picturesReused.getAndSet(0), drawnBlocks = picturesDrawn.getAndSet(0);
        if (reused + drawnBlocks > 0) {
            b.append(" pictures[reused=")
                .append(reused)
                .append(" drawn=")
                .append(drawnBlocks)
                .append(" saved=")
                .append(share(reused, reused + drawnBlocks))
                .append(']');
        }
        long fresh = freshStored.getAndSet(0), again = againStored.getAndSet(0);
        b.append(" chunks/s[new=")
            .append(f1(fresh / seconds))
            .append(" again=")
            .append(f1(again / seconds))
            .append(']');
        List<Long> times;
        synchronized (FRESH_TIMES) {
            times = new ArrayList<>(FRESH_TIMES);
            FRESH_TIMES.clear();
        }
        if (!times.isEmpty()) {
            Collections.sort(times);
            b.append(" newChunkMs[p50=")
                .append(ms(times.get(times.size() / 2)))
                .append(" p90=")
                .append(ms(times.get(times.size() * 9 / 10)))
                .append(" max=")
                .append(ms(times.get(times.size() - 1)))
                .append(']');
        }
        line(b.toString());
    }

    /**
     * The mod's parts that ran in frames, ticks or server ticks: ms per frame (or tick) on average, their share of
     * the time those took, and the slowest single run; parts inside another in brackets after it.
     */
    private static void parts(StringBuilder b, Perf.Sample sample, Perf.Where where, String title, long[] span) {
        StringBuilder parts = new StringBuilder();
        for (Perf.Part part : Perf.Part.values()) {
            if (part.where != where || part.inside != null) {
                continue;
            }
            appendPart(parts, sample, part, span);
            StringBuilder inner = new StringBuilder();
            for (Perf.Part child : Perf.Part.values()) {
                if (child.inside == part || (child.inside != null && child.inside.inside == part)) {
                    appendPart(inner, sample, child, span);
                }
            }
            if (inner.length() > 0) {
                parts.append(" [")
                    .append(
                        inner.toString()
                            .trim())
                    .append(']');
            }
        }
        if (parts.length() > 0) {
            b.append(title)
                .append(parts);
        }
    }

    private static void appendPart(StringBuilder b, Perf.Sample sample, Perf.Part part, long[] span) {
        long[] measured = sample.parts[part.ordinal()];
        if (measured[1] == 0) {
            return;
        }
        // Per frame or tick of the game (not per run): what it costs each one on average.
        long per = span[0] > 0 ? measured[0] / span[0] : measured[0] / measured[1];
        b.append(' ')
            .append(part.label)
            .append('=')
            .append(ms(per))
            .append("ms(")
            .append(share(measured[0], span[1]))
            .append(" max=")
            .append(ms(measured[2]))
            .append(')');
    }

    /** cpu%[...]: the mod's threads (renderers counted together) and the game's own, of one core each. */
    private static String threadCpu(long sinceStart, double seconds) {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
            return "";
        }
        Map<String, Long> groups = new java.util.TreeMap<>();
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (long id : bean.getAllThreadIds()) {
            java.lang.management.ThreadInfo info = bean.getThreadInfo(id);
            if (info == null) {
                continue;
            }
            String group = threadGroup(info.getThreadName());
            long cpu = bean.getThreadCpuTime(id);
            if (group == null || cpu < 0) {
                continue;
            }
            alive.add(id);
            Long before = THREAD_CPU.put(id, cpu);
            long used = before == null ? 0 : cpu - before;
            groups.merge(group, used, Long::sum);
            THREAD_TOTALS.merge(group, used, Long::sum);
        }
        THREAD_CPU.keySet()
            .retainAll(alive);
        StringBuilder b = new StringBuilder(" cpu%[");
        boolean first = true;
        for (Map.Entry<String, Long> group : groups.entrySet()) {
            if (!first) {
                b.append(' ');
            }
            first = false;
            b.append(
                group.getKey()
                    .replace(' ', '_'))
                .append('=')
                .append(Math.round(group.getValue() / 1e9 / seconds * 100));
        }
        return b.append(" cores=")
            .append(
                Runtime.getRuntime()
                    .availableProcessors())
            .append(']')
            .toString();
    }

    /** The group a thread is counted in: the mod's by name (its numbered ones together), the game's main ones. */
    private static String threadGroup(String name) {
        if (name.startsWith("WayFarMap ")) {
            return name.substring("WayFarMap ".length())
                .replaceAll(" \\d+$", "");
        }
        if (name.equals("Client thread")) {
            return "game";
        }
        if (name.equals("Server thread")) {
            return "game server";
        }
        return null;
    }

    /**
     * BOTTLENECK: since the log started, what of the mod costs frames, ticks and the server most, its threads'
     * CPU, which wait new chunks spend longest in, and what to change for each.
     */
    private static void bottleneck(String title, List<long[]> all, List<String> names) {
        long[][] totals = Perf.totals();
        long[] frames = totals[0], ticks = totals[1], server = totals[2], parts = totals[3];
        double seconds = Math.max(0.001, (System.nanoTime() - perfStart) / 1e9);
        String t = title + " BOTTLENECK";
        line(
            t + " over "
                + Math.round(seconds)
                + "s: fps="
                + f1(frames[0] / seconds)
                + " avgFrameMs="
                + (frames[0] == 0 ? "-" : ms(frames[1] / frames[0]))
                + " clientTps="
                + f1(ticks[0] / seconds)
                + " avgTickMs="
                + (ticks[0] == 0 ? "-" : ms(ticks[1] / ticks[0]))
                + (server[0] == 0 ? ""
                    : " serverTps=" + f1(Math.min(20, server[0] / seconds)) + " avgMspt=" + ms(server[1] / server[0])));
        // The mod's share of each, from its top parts (the ones inside others are in them).
        long[] spans = { frames[1], ticks[1], server[1] };
        long[] counts = { frames[0], ticks[0], server[0] };
        String[] whats = { "frame time", "client tick time", "server tick time" };
        List<Perf.Part> ranked = new ArrayList<>(Arrays.asList(Perf.Part.values()));
        ranked.sort((a, b) -> Long.compare(parts[b.ordinal()], parts[a.ordinal()]));
        for (Perf.Where where : Perf.Where.values()) {
            long mod = 0;
            StringBuilder top = new StringBuilder();
            for (Perf.Part part : ranked) {
                if (part.where != where || parts[part.ordinal()] == 0) {
                    continue;
                }
                if (part.inside == null) {
                    mod += parts[part.ordinal()];
                }
                long count = counts[where.ordinal()];
                top.append(' ')
                    .append(part.label)
                    .append('=')
                    .append(count == 0 ? "-" : ms(parts[part.ordinal()] / count))
                    .append("ms(")
                    .append(share(parts[part.ordinal()], spans[where.ordinal()]))
                    .append(')');
            }
            if (mod > 0) {
                line(
                    t + "   mod's share of "
                        + whats[where.ordinal()]
                        + ": "
                        + share(mod, spans[where.ordinal()])
                        + " - by part:"
                        + top);
            }
        }
        if (!THREAD_TOTALS.isEmpty()) {
            StringBuilder cpu = new StringBuilder(
                t + "   threads' cpu (100% = one core) of "
                    + Runtime.getRuntime()
                        .availableProcessors()
                    + " cores:");
            List<Map.Entry<String, Long>> groups = new ArrayList<>(THREAD_TOTALS.entrySet());
            groups.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> group : groups) {
                cpu.append(' ')
                    .append(
                        group.getKey()
                            .replace(' ', '_'))
                    .append('=')
                    .append(Math.round(group.getValue() / 1e9 / seconds * 100))
                    .append('%');
            }
            line(cpu.toString());
        }
        String wait = slowestWait(all, names);
        if (wait != null) {
            line(t + "   new chunks wait longest in: " + wait);
        }
        for (String advice : advice(frames, ticks, server, parts, seconds, wait)) {
            line(t + "   ADVICE " + advice);
        }
    }

    /** The phase new chunks spend longest in (by its median), with its share of their whole way, or null. */
    private static String slowestWait(List<long[]> all, List<String> names) {
        // The waits between steps and the steps themselves; not "total" (0) or the copying's CPU (6, inside 5).
        int[] phases = { 1, 2, 3, 4, 5, 7, 8, 9 };
        String best = null;
        long bestMedian = -1, totalMedian = 0;
        List<Long> totals = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (names.get(i)
                .endsWith(" fresh")) {
                totals.add(all.get(i)[0]);
            }
        }
        for (int p : phases) {
            List<Long> values = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                long[] row = all.get(i);
                if (names.get(i)
                    .endsWith(" fresh") && row[p] >= 0) {
                    values.add(row[p]);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            Collections.sort(values);
            long median = values.get(values.size() / 2);
            if (median > bestMedian) {
                bestMedian = median;
                best = PHASES[p];
            }
        }
        if (best == null) {
            return null;
        }
        if (!totals.isEmpty()) {
            Collections.sort(totals);
            totalMedian = totals.get(totals.size() / 2);
        }
        return best + " (median " + ms(bestMedian) + "ms of " + ms(totalMedian) + "ms from seen to stored)";
    }

    /** What to change, from what costs most. */
    private static List<String> advice(long[] frames, long[] ticks, long[] server, long[] parts, double seconds,
        String wait) {
        List<String> advice = new ArrayList<>();
        long frameCount = Math.max(1, frames[0]), tickCount = Math.max(1, ticks[0]);
        double capture = parts[Perf.Part.CAPTURE_3D.ordinal()] / 1e6 / tickCount;
        double scan = parts[Perf.Part.SCAN_2D.ordinal()] / 1e6 / tickCount;
        double minimap = parts[Perf.Part.MINIMAP.ordinal()] / 1e6 / frameCount;
        double markers = parts[Perf.Part.MARKERS.ordinal()] / 1e6 / frameCount;
        double upload = parts[Perf.Part.ISO_UPLOAD.ordinal()] / 1e6 / frameCount;
        double isoDraw = parts[Perf.Part.ISO_DRAW.ordinal()] / 1e6 / frameCount;
        double items = parts[Perf.Part.ITEM_PICTURES.ordinal()] / 1e6 / frameCount;
        if (capture > 4) {
            advice.add(
                "copying blocks for the 3D map takes " + f1(capture)
                    + "ms a tick: lower isoCaptureMs (now "
                    + Config.isoCaptureMs
                    + ") for more FPS/TPS, new chunks come slower");
        }
        if (scan > 3) {
            advice.add(
                "scanning chunks for the 2D map takes " + f1(scan)
                    + "ms a tick: lower chunksScannedPerTick (now "
                    + Config.chunksScannedPerTick
                    + ")");
        }
        if (isoDraw > 4) {
            advice.add(
                "the 3D view takes " + f1(isoDraw)
                    + "ms a frame (uploads "
                    + f1(upload)
                    + "ms): lower isoQuality (now "
                    + Config.isoQuality
                    + ") or turn isoSmooth off (now "
                    + Config.isoSmooth
                    + ")");
        }
        if (minimap > 2) {
            advice.add("the minimap takes " + f1(minimap) + "ms a frame: a smaller minimap or zoom, fewer mob icons");
        }
        if (markers > 2) {
            advice.add(
                "markers in the world take " + f1(markers)
                    + "ms a frame: fewer waypoints shown (groups hidden, max distance) or beams off");
        }
        if (items > 1) {
            advice.add("waypoint icon pictures take " + f1(items) + "ms a frame (only while new icons are taken)");
        }
        long chunkload = parts[Perf.Part.CHUNKLOAD.ordinal()];
        if (server[0] > 0 && chunkload / 1e6 / server[0] > 10) {
            advice.add(
                "chunk loading (/wf chunkload, regionload) takes " + f1(chunkload / 1e6 / server[0])
                    + "ms of each server tick: it is what costs TPS while it runs");
        }
        long renderers = THREAD_TOTALS.getOrDefault("3D renderer", 0L);
        int cores = Runtime.getRuntime()
            .availableProcessors();
        if (renderers / 1e9 / seconds > Math.max(1, cores - 2)) {
            advice.add(
                "the 3D renderers use " + Math.round(renderers / 1e9 / seconds * 100)
                    + "% CPU of "
                    + cores
                    + " cores: they may slow the game; lower isoQuality or isoSmooth off");
        }
        if (wait != null) {
            // "settled->scan" before "settle": it starts with that too.
            if (wait.startsWith("settled->scan") || wait.startsWith("scan->queue")) {
                advice.add("new chunks wait most for the scanner: raise chunksScannedPerTick (costs a little TPS)");
            } else if (wait.startsWith("load->seen")) {
                advice.add(
                    "new chunks wait most for the scanner to find them after the game loaded them: it scans "
                        + Config.chunksScannedPerTick
                        + " a tick (chunksScannedPerTick) from a queue around the player built again only now and "
                        + "then");
            } else if (wait.startsWith("settle")) {
                advice.add(
                    "new chunks wait most for the game to finish them (neighbours loaded, decorated): that is the "
                        + "game's chunk loading, not the mod");
            } else if (wait.startsWith("queue->capture") || wait.startsWith("capturing")) {
                advice.add(
                    "new chunks wait most to be copied: raise isoCaptureMs (now " + Config.isoCaptureMs
                        + ", costs FPS/TPS); 'capturing' long = copied again for pictures still missing");
            } else if (wait.startsWith("submit->writer") || wait.startsWith("writer")) {
                advice.add("new chunks wait most for the writer thread: the disk or the CPU is the limit");
            }
        }
        double looks = parts[Perf.Part.ISO_LOOKS.ordinal()] / 1e6 / frameCount;
        if (looks > 3) {
            advice.add(
                "working out block looks for the tile renderers takes " + f1(looks)
                    + "ms a frame while the 3D view is open (up to 10 ms a frame): fewer looks to work out, or a "
                    + "smaller budget, gives FPS back");
        }
        double evict = parts[Perf.Part.ISO_EVICT.ordinal()] / 1e6 / frameCount;
        if (evict > 0.5) {
            advice.add(
                "looking for tiles to free takes " + f1(evict)
                    + "ms a frame: it goes through every tile in memory each frame");
        }
        long uploadWait = median(UPLOAD_WAITS);
        if (uploadWait > 100) {
            advice.add(
                "finished tiles wait " + uploadWait
                    + "ms (median) to be put on the graphics card: 12 a frame at most, fewer tiles a second "
                    + "while the FPS is low");
        }
        if (advice.isEmpty()) {
            advice.add("nothing of the mod stands out");
        }
        return advice;
    }

    // ---------------------------------------------------------------- summaries

    private static void summary(String title) {
        List<long[]> all;
        List<String> names;
        synchronized (DONE) {
            all = new ArrayList<>(DONE);
            names = new ArrayList<>(DONE_NAMES);
        }
        line(title + " chunksStored=" + all.size() + " stillTraced=" + TRACES.size() + " stillSettling=" + SEEN.size());
        bottleneck(title, all, names);
        tileSummary(title);
        visibilitySummary(title);
        latencySummary(title);
        levelSummary(title);
        attemptSummary(title);
        kindSummary(title);
        FaceRenderer.dataSummary(title);
        blockSummary(title);
        changeSummary(title);
        BlockDiag.fallbackSummary(title);
        if (all.isEmpty()) {
            return;
        }
        // New chunks apart from chunks copied again: the new ones are what the player waits for.
        phaseStats(title + " fresh", all, names, true);
        phaseStats(title + " again", all, names, false);
        // Chunks still on their way: the ones stuck longest.
        List<Trace> waiting = new ArrayList<>(TRACES.values());
        long now = System.nanoTime();
        waiting.sort((a, b) -> Long.compare(a.queued, b.queued));
        for (int n = 0; n < Math.min(20, waiting.size()); n++) {
            Trace t = waiting.get(n);
            line(
                title + "   stuck "
                    + t.cx
                    + ","
                    + t.cz
                    + " reason="
                    + t.reason
                    + " queue="
                    + t.queue
                    + " queuedMsAgo="
                    + span(t.queued, now)
                    + " captures="
                    + t.captures
                    + " facesMissing="
                    + t.facesMissing);
        }
        writeSlowest(title, all, names);
    }

    /** Median of times kept for the summaries, 0 if none. */
    private static long median(List<Long> times) {
        List<Long> sorted;
        synchronized (times) {
            sorted = new ArrayList<>(times);
        }
        if (sorted.isEmpty()) {
            return 0;
        }
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Percentiles of times kept for the summaries, "-" if none. */
    private static String percentiles(List<Long> times) {
        List<Long> sorted;
        synchronized (times) {
            sorted = new ArrayList<>(times);
        }
        if (sorted.isEmpty()) {
            return "-";
        }
        Collections.sort(sorted);
        int n = sorted.size();
        return "n=" + n
            + " p50="
            + sorted.get(n / 2)
            + " p90="
            + sorted.get(n * 9 / 10)
            + " p99="
            + sorted.get(Math.min(n - 1, n * 99 / 100))
            + " max="
            + sorted.get(n - 1);
    }

    /** The parts of the way from the game loading a chunk to it on screen that aren't in the chunks' phases. */
    private static void latencySummary(String title) {
        line(title + " latency (ms) chunk loaded by the game -> seen by the scanner: " + percentiles(LOAD_SEEN));
        line(title + " latency (ms) chunk change stored -> tiles showing it on screen: " + percentiles(CHANGE_SHOWN));
        line(title + " latency (ms) tile wanted with nothing to show -> on screen: " + percentiles(NEW_SHOWN));
        line(title + " latency (ms) tile finished by a renderer -> uploaded: " + percentiles(UPLOAD_WAITS));
    }

    /** Per level of the 3D view: tiles with nothing yet / only out of date queued, drawn, and drawing times. */
    private static void levelSummary(String title) {
        List<Integer> levels = new ArrayList<>(LEVEL_COUNTS.keySet());
        Collections.sort(levels);
        for (int level : levels) {
            long[] c = LEVEL_COUNTS.get(level);
            StringBuilder b = new StringBuilder(title).append(" tiles L")
                .append(level)
                .append(": queuedMissing=")
                .append(c[0])
                .append(" queuedRefresh=")
                .append(c[1])
                .append(" drawn=")
                .append(c[2]);
            List<Long> times = LEVEL_TIMES.get(level);
            if (times != null && !times.isEmpty()) {
                List<Long> sorted;
                synchronized (times) {
                    sorted = new ArrayList<>(times);
                }
                Collections.sort(sorted);
                long sum = 0;
                for (long t : sorted) {
                    sum += t;
                }
                b.append(" drawMs[p50=")
                    .append(sorted.get(sorted.size() / 2))
                    .append(" p90=")
                    .append(sorted.get(sorted.size() * 9 / 10))
                    .append(" max=")
                    .append(sorted.get(sorted.size() - 1))
                    .append(" totalS=")
                    .append(sum / 1000)
                    .append(']');
            }
            line(b.toString());
        }
    }

    /** How many copies new chunks needed (more than one: pictures not all taken in one go). */
    private static void attemptSummary(String title) {
        long[] buckets = new long[5];
        String[] names = { "1", "2-3", "4-6", "7-10", ">10" };
        synchronized (FRESH_ATTEMPTS) {
            for (int attempts : FRESH_ATTEMPTS) {
                buckets[attempts <= 1 ? 0 : attempts <= 3 ? 1 : attempts <= 6 ? 2 : attempts <= 10 ? 3 : 4]++;
            }
        }
        long total = 0;
        for (long n : buckets) {
            total += n;
        }
        if (total == 0) {
            return;
        }
        StringBuilder b = new StringBuilder(title).append(" copies per new chunk:");
        for (int i = 0; i < buckets.length; i++) {
            b.append(' ')
                .append(names[i])
                .append('=')
                .append(buckets[i])
                .append('(')
                .append(share(buckets[i], total))
                .append(')');
        }
        line(b.toString());
    }

    /** How many copies each new chunk stored needed. */
    private static final List<Integer> FRESH_ATTEMPTS = new ArrayList<>();

    /**
     * Pictures learned by kind: per block, how many were reused without drawing, drawn, drawn and all empty, drawn and
     * the same as learned, and drawn different (the kind is then drawn every time); the share of drawing saved.
     */
    private static void kindSummary(String title) {
        Map<Integer, long[]> stats = FaceRenderer.kindStats();
        if (stats.isEmpty()) {
            return;
        }
        long reused = 0, drawn = 0, empty = 0;
        List<Map.Entry<Integer, long[]>> kinds = new ArrayList<>(stats.entrySet());
        for (Map.Entry<Integer, long[]> kind : kinds) {
            reused += kind.getValue()[0];
            drawn += kind.getValue()[1];
            empty += kind.getValue()[2];
        }
        int[] learned = FaceRenderer.learnedKinds();
        line(
            title + " learned pictures: reused without drawing="
                + reused
                + " drawn="
                + drawn
                + " (all empty "
                + empty
                + ") drawingSaved="
                + share(reused, reused + drawn)
                + " kinds[learned="
                + learned[0]
                + " unreliable="
                + learned[1]
                + " seen="
                + learned[2]
                + "] byBlock[learned="
                + learned[3]
                + " unreliable="
                + learned[4]
                + " seen="
                + learned[5]
                + "]");
        kinds.sort((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        for (int n = 0; n < Math.min(25, kinds.size()); n++) {
            long[] v = kinds.get(n)
                .getValue();
            StringBuilder b = new StringBuilder(title).append("   kind#")
                .append(n + 1)
                .append(' ')
                .append(
                    BlockDiag.name(
                        kinds.get(n)
                            .getKey()));
            for (int f = 0; f < FaceRenderer.KIND_FIELDS.length; f++) {
                b.append(' ')
                    .append(FaceRenderer.KIND_FIELDS[f])
                    .append('=')
                    .append(v[f]);
            }
            b.append(" saved=")
                .append(share(v[0], v[0] + v[1]));
            line(b.toString());
        }
    }

    /** Tiles of the 3D view: what reading and saving them gave, warnings, and how long the screen took. */
    private static void tileSummary(String title) {
        List<Map.Entry<String, AtomicLong>> counts = new ArrayList<>(TILE_COUNTS.entrySet());
        counts.sort(Map.Entry.comparingByKey());
        StringBuilder b = new StringBuilder(title).append(" tiles:");
        for (Map.Entry<String, AtomicLong> count : counts) {
            b.append(" [")
                .append(count.getKey())
                .append("]=")
                .append(
                    count.getValue()
                        .get());
        }
        line(b.toString());
        List<Long> times;
        synchronized (VIEW_TIMES) {
            times = new ArrayList<>(VIEW_TIMES);
        }
        if (!times.isEmpty()) {
            Collections.sort(times);
            line(
                title + " screen complete after (ms): n="
                    + times.size()
                    + " p50="
                    + times.get(times.size() / 2)
                    + " p90="
                    + times.get(times.size() * 9 / 10)
                    + " max="
                    + times.get(times.size() - 1));
        }
    }

    /** What made copies count as changed, and which kinds of blocks. */
    private static void changeSummary(String title) {
        line(
            title + " changed copies: blocks="
                + diffBlocks.get()
                + " picturesOnly="
                + diffPicturesOnly.get()
                + " lightOnly="
                + diffLightOnly.get()
                + " colorsOnly="
                + diffColorsOnly.get()
                + " otherMixes="
                + diffOther.get());
        List<Map.Entry<String, long[]>> kinds = new ArrayList<>(CHANGED_BY.entrySet());
        kinds.sort(
            (a, b) -> Long.compare(
                b.getValue()[0] + b.getValue()[1] + b.getValue()[2],
                a.getValue()[0] + a.getValue()[1] + a.getValue()[2]));
        for (int n = 0; n < Math.min(30, kinds.size()); n++) {
            long[] stats = kinds.get(n)
                .getValue();
            line(
                title + "   changed#"
                    + (n + 1)
                    + " "
                    + kinds.get(n)
                        .getKey()
                    + " blockChanged="
                    + stats[0]
                    + " lightChanged="
                    + stats[1]
                    + " pictureChanged="
                    + stats[2]);
        }
    }

    /** The kinds of blocks whose pictures took the most time. */
    private static void blockSummary(String title) {
        List<Map.Entry<String, long[]>> kinds = new ArrayList<>(BLOCKS.entrySet());
        if (kinds.isEmpty()) {
            return;
        }
        kinds.sort((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]));
        long total = 0;
        for (Map.Entry<String, long[]> kind : kinds) {
            total += kind.getValue()[2];
        }
        line(
            title + " pictures by block: "
                + kinds.size()
                + " kinds, drawing "
                + ms(total)
                + "ms in all (without "
                + "reading back)");
        for (int n = 0; n < Math.min(40, kinds.size()); n++) {
            long[] stats = kinds.get(n)
                .getValue();
            line(
                String.format(
                    Locale.ROOT,
                    "%s   block#%d %s drawn=%d views=%d ms=%s msPerBlock=%s",
                    title,
                    n + 1,
                    kinds.get(n)
                        .getKey(),
                    stats[0],
                    stats[1],
                    ms(stats[2]),
                    String.format(Locale.ROOT, "%.3f", stats[2] / 1e6 / Math.max(1, stats[0]))));
        }
    }

    private static void phaseStats(String title, List<long[]> all, List<String> names, boolean fresh) {
        for (int p = 0; p < PHASES.length; p++) {
            List<Long> values = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                long[] phases = all.get(i);
                if (phases[p] >= 0 && names.get(i)
                    .endsWith(" fresh") == fresh) {
                    values.add(phases[p]);
                }
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
                    "%s   %-15s n=%d avg=%s p50=%s p90=%s p99=%s max=%s",
                    title,
                    PHASES[p],
                    values.size(),
                    ms(sum / values.size()),
                    ms(values.get(values.size() / 2)),
                    ms(values.get(values.size() * 9 / 10)),
                    ms(values.get(Math.min(values.size() - 1, values.size() * 99 / 100))),
                    ms(values.get(values.size() - 1))));
        }
    }

    private static void writeSlowest(String title, List<long[]> all, List<String> names) {
        // Where the time of the slowest chunks went.
        Integer[] order = new Integer[all.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(all.get(b)[0], all.get(a)[0]));
        for (int n = 0; n < Math.min(30, order.length); n++) {
            long[] phases = all.get(order[n]);
            StringBuilder b = new StringBuilder(title).append("   slow#")
                .append(n + 1)
                .append(' ')
                .append(names.get(order[n]));
            for (int p = 0; p < PHASES.length; p++) {
                b.append(' ')
                    .append(PHASES[p])
                    .append('=')
                    .append(phases[p] < 0 ? "-" : ms(phases[p]));
            }
            line(b.toString());
        }
    }
}
