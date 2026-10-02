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
    /** Phases of the chunks stored, for the summaries: see {@link #PHASES}. */
    private static final List<long[]> DONE = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> DONE_NAMES = Collections.synchronizedList(new ArrayList<>());
    private static final String[] PHASES = { "total", "settle", "settled->scan", "scan->queue", "queue->capture",
        "capturing", "captureCpu", "submit->writer", "writer" };

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
            "LEGEND tiles of the 3D view: VIEW_START (the view opened, zoomed, turned or moved to another dimension) "
                + "-> TILE_QUEUED -> TILE_DONE (a renderer made it: disk=what reading its saved file gave, "
                + "chunks=where the rays found blocks: store (3D blocks) / none, "
                + "reads=region files read for it, waits=time waiting for block looks from the game, px=pixels: "
                + "clear (nothing, the dark background shows) / black / dark, saved=what was written to its file) -> "
                + "VIEW every second while the screen is not complete (onScreen, ready, empty, fromCoarser = a "
                + "blurry parent shown, holes = nothing shown at all) -> VIEW_COMPLETE (how long the screen took). "
                + "TILE_WARN marks what looks wrong (empty or black tiles saved, files that couldn't be read, slow "
                + "tiles); LOOKS_WAIT are waits for block looks.");
    }

    /** The world was left: writes the summary and closes the file. */
    static synchronized void close() {
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
        TRACES.clear();
        SEEN.clear();
        SETTLED.clear();
        DONE.clear();
        DONE_NAMES.clear();
        BLOCKS.clear();
        CHANGED_BY.clear();
        TILE_COUNTS.clear();
        VIEW_TIMES.clear();
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
        if (SEEN.putIfAbsent(key, System.nanoTime()) == null) {
            line("SEEN " + cx + "," + cz + " neighboursReady=" + neighboursReady);
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
            t.writerEnd - t.writerStart };
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
    static void marked(int dimension, int cx, int cz, long changeTimeMs, int tiles, int tilesInMemory) {
        if (on()) {
            line(
                "MARKED " + cx
                    + ","
                    + cz
                    + " dim="
                    + dimension
                    + " delayMs="
                    + (System.currentTimeMillis() - changeTimeMs)
                    + " tilesDirtied="
                    + tiles
                    + " tilesInMemory="
                    + tilesInMemory);
        }
    }

    static void tileQueued(IsoTiles.Key key, boolean stale, int queueSize) {
        if (on()) {
            line("TILE_QUEUED " + tile(key) + (stale ? " stale" : " new") + " queue=" + queueSize);
        }
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
                    .append(w.diskBytes);
            }
            if (!"disk".equals(source)) {
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
            lastStats = now;
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
                    + " captureMs="
                    + ms(captureNanosSum.getAndSet(0))
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
                    + ms(tileNanos.getAndSet(0))
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
        if (lastSummary == 0) {
            lastSummary = now;
        } else if (now - lastSummary >= 300_000) {
            lastSummary = now;
            summary("SUMMARY");
        }
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

    // ---------------------------------------------------------------- summaries

    private static void summary(String title) {
        List<long[]> all;
        List<String> names;
        synchronized (DONE) {
            all = new ArrayList<>(DONE);
            names = new ArrayList<>(DONE_NAMES);
        }
        line(title + " chunksStored=" + all.size() + " stillTraced=" + TRACES.size() + " stillSettling=" + SEEN.size());
        tileSummary(title);
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
