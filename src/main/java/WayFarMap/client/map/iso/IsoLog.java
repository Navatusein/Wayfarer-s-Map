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
            Arrays.sort(old, (a, b) -> a.getName()
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
                + "told to redraw). STATS every second while busy, SUMMARY every 5 minutes and at the end.");
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
            "SETTLED " + cx + "," + cz + " how=" + how + " ticks=" + ticksWaited + " ms="
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
        line("SCANNED " + cx + "," + cz + " again=" + again + " changed=" + changed + " modified=" + modified + " ms="
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
            "QUEUED " + cx + "," + cz + " reason=" + reason + " queue=" + queue + " freshQueue=" + fresh
                + " againQueue=" + again + (created ? "" : " (already traced, first reason=" + trace.reason + ")"));
    }

    /** It left its queue without being copied. */
    static void dropped(int cx, int cz, String why) {
        if (!on()) {
            return;
        }
        Trace trace = TRACES.remove(key(cx, cz));
        line("DROPPED " + cx + "," + cz + " " + why + (trace == null ? ""
            : " reason=" + trace.reason + " waitedMs=" + span(trace.queued, System.nanoTime()) + " captures="
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
        line((complete ? "CAPTURE " : "CAPTURE_INCOMPLETE ") + cx
            + ","
            + cz
            + " reason="
            + trace.reason
            + " attempt="
            + trace.captures
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
        log("REGION_SAVED dim=" + dimension + " r=" + rx + "," + rz + " bytes=" + bytes + " copyUnderLockMs="
            + ms(copyNanos) + " writeMs=" + ms(writeNanos) + (ok ? "" : " FAILED"));
    }

    static void regionRead(int dimension, int rx, int rz, String what, long nanos, long bytes) {
        log("REGION_READ dim=" + dimension + " r=" + rx + "," + rz + " " + what + " ms=" + ms(nanos) + " bytes=" + bytes
            + " [" + Thread.currentThread()
                .getName()
            + "]");
    }

    static void regionTrimmed(int dimension, int dropped, long bytesBefore, long bytesAfter, long nanos) {
        log("BLOBS_TRIMMED dim=" + dimension + " regionsDropped=" + dropped + " mbBefore=" + (bytesBefore >> 20)
            + " mbAfter=" + (bytesAfter >> 20) + " ms=" + ms(nanos));
    }

    // ---------------------------------------------------------------- tiles

    /** Chunk changes reached the tiles in memory. */
    static void marked(int dimension, int cx, int cz, long changeTimeMs, int tiles, int tilesInMemory) {
        if (on()) {
            line(
                "MARKED " + cx + "," + cz + " dim=" + dimension + " delayMs="
                    + (System.currentTimeMillis() - changeTimeMs) + " tilesDirtied=" + tiles + " tilesInMemory="
                    + tilesInMemory);
        }
    }

    static void tileQueued(IsoTiles.Key key, boolean stale, int queueSize) {
        if (on()) {
            line("TILE_QUEUED " + tile(key) + (stale ? " stale" : " new") + " queue=" + queueSize);
        }
    }

    static void tileDone(IsoTiles.Key key, long waitedNanos, long nanos, String source, boolean retry, boolean empty) {
        if (!on()) {
            return;
        }
        tilesDrawn.incrementAndGet();
        tileNanos.addAndGet(nanos);
        if ("disk".equals(source)) {
            tilesFromDisk.incrementAndGet();
        }
        if (retry) {
            tilesRetry.incrementAndGet();
        }
        line("TILE_DONE " + tile(key) + " src=" + source + " waitedMs=" + ms(waitedNanos) + " ms=" + ms(nanos)
            + (retry ? " RETRY(looks/pictures missing, drawn again)" : "") + (empty ? " empty" : ""));
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
    static void tick(long nanos, boolean outOfTime, int fresh, int again, int unfinished, int tilesQueued,
        long paletteUnsaved) {
        if (!on()) {
            return;
        }
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

    // ---------------------------------------------------------------- summaries

    private static void summary(String title) {
        List<long[]> all;
        List<String> names;
        synchronized (DONE) {
            all = new ArrayList<>(DONE);
            names = new ArrayList<>(DONE_NAMES);
        }
        line(title + " chunksStored=" + all.size() + " stillTraced=" + TRACES.size() + " stillSettling=" + SEEN.size());
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
            line(title + "   stuck " + t.cx + "," + t.cz + " reason=" + t.reason + " queue=" + t.queue + " queuedMsAgo="
                + span(t.queued, now) + " captures=" + t.captures + " facesMissing=" + t.facesMissing);
        }
        writeSlowest(title, all, names);
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
