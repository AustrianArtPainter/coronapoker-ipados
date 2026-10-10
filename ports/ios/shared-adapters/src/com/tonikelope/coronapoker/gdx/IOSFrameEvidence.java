package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import java.util.Arrays;
import org.robovm.rt.GC;

/** Bounded primitive frame samples. No live JSON, screenshots or periodic reporter. */
public final class IOSFrameEvidence {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_SCENE_EVIDENCE"));
    private static final boolean SCOPES = ENABLED && "1".equals(System.getenv("CP_IOS_SCENE_SCOPES"));
    private static final String[] scopeNames = {"alloc.formatAmount", "alloc.formatPlayTime", "alloc.summaryDate",
            "alloc.drawText", "alloc.drawLeftText", "alloc.finalSummary", "alloc.tableFrame", "alloc.tableScene",
            "alloc.seats", "alloc.localHud", "alloc.board", "alloc.chatPoll", "alloc.uiLayer", "alloc.background",
            "alloc.applicationFrame", "alloc.fullTableFrame"};
    private static final long[][] scopeBytes = SCOPES ? new long[3][scopeNames.length] : null;
    private static final long[][] scopeCalls = SCOPES ? new long[3][scopeNames.length] : null;
    private static final int CAPACITY = 120_000;
    private static final long[] intervals = ENABLED ? new long[CAPACITY] : null;
    private static final long[] renders = ENABLED ? new long[CAPACITY] : null;
    private static final long[] allocations = ENABLED ? new long[CAPACITY] : null;
    private static final long[] renderAllocations = ENABLED ? new long[CAPACITY] : null;
    private static final long[] events = ENABLED ? new long[CAPACITY] : null;
    private static final byte[] phases = ENABLED ? new byte[CAPACITY] : null;
    private static final byte[] collections = ENABLED ? new byte[CAPACITY] : null;
    private static long previous, previousBytes, previousGC, previousRender, previousRenderBytes, previousEvent;
    private static long startBytes, startGC, phaseStarted, summaryStarted;
    private static long event;
    private static int count, phase, previousPhase;
    private static boolean finished;
    public static long projectionBuilds, layoutBuilds, labelBuilds;
    public static long tableDraws, hiddenTableDraws, summaryDraws, summaryCompiles, summaryReplays;
    public static long allInPreparedClips, allInPreparedPlaybacks, allInFallbackPlaybacks, allInHotUploads;
    public static long preloadPixmapCreates, preloadPixmapDisposals;
    private static long heapBeforePreload, heapAfterPreload, freeAfterPreload;
    static void preloadBegin() { if(ENABLED && heapBeforePreload==0) heapBeforePreload=GC.getHeapSize(); }
    static void preloadReady() { if(ENABLED && heapAfterPreload==0) { heapAfterPreload=GC.getHeapSize();freeAfterPreload=GC.getFreeBytes(); } }

    public static long begin() {
        phase = 0;
        if (!ENABLED || finished) return 0;
        long started = System.nanoTime();
        startBytes = GC.getTotalBytes(); startGC = GC.getCount();
        return started;
    }
    public static void scene(int next, long sequence) { phase = next; event = sequence; }
    public static boolean allocationScopesEnabled() { return SCOPES && !finished; }
    public static void allocationScope(String name, long bytes) {
        if (!allocationScopesEnabled()) return;
        for (int i = 0; i < scopeNames.length; i++) if (scopeNames[i].equals(name)) {
            scopeBytes[phase][i] += bytes; scopeCalls[phase][i]++; return;
        }
    }

    public static void end(long started) {
        if (started == 0 || finished) return;
        long now = System.nanoTime();
        if (phase != previousPhase) {
            phaseStarted = now;
            if (phase == 2) summaryStarted = now;
        }
        if (phase != 0 && phase == previousPhase && now - phaseStarted > 5_000_000_000L
                && previous != 0 && count < CAPACITY) {
            intervals[count] = started - previous;
            renders[count] = previousRender;
            allocations[count] = startBytes - previousBytes;
            renderAllocations[count] = previousRenderBytes;
            events[count] = previousEvent;
            phases[count] = (byte) phase;
            collections[count] = (byte) Math.min(127, startGC - previousGC);
            count++;
        }
        previous = started; previousBytes = startBytes; previousGC = startGC; previousPhase = phase;
        previousRender = now - started;
        previousRenderBytes = GC.getTotalBytes() - startBytes;
        previousEvent = event;
        if (count == CAPACITY || summaryStarted != 0 && phase == 2
                && now - summaryStarted >= 65_000_000_000L) {
            finished = true;
            Thread writer = new Thread(IOSFrameEvidence::report, "coronapoker-ios-scene-report");
            writer.setDaemon(true); writer.start();
        }
    }
    public static void suspend() { previous = 0; previousPhase = 0; phase = 0; }

    private static void report() {
        StringBuilder out = new StringBuilder("{\"kind\":\"retained-scene-v4\",\"preferredFPS\":60,\"liveReporter\":false,");
        out.append("\"runID\":").append(quote(System.getenv("CP_IOS_RUN_ID")))
                .append(",\"reportedUtcMillis\":").append(System.currentTimeMillis())
                .append(",\"crypto\":").append(quote(System.getenv("CP_IOS_CRYPTO_TEST")))
                .append(",\"audio\":").append(quote(System.getenv("CP_IOS_AUDIO_TEST")))
                .append(",\"animation\":").append(quote(System.getenv("CP_IOS_ANIMATION_TEST")))
                .append(',');
        out.append("\"projectionBuilds\":").append(projectionBuilds)
                .append(",\"layoutBuilds\":").append(layoutBuilds)
                .append(",\"labelBuilds\":").append(labelBuilds)
                .append(",\"tableDraws\":").append(tableDraws)
                .append(",\"hiddenTableDraws\":").append(hiddenTableDraws)
                .append(",\"summaryDraws\":").append(summaryDraws)
                .append(",\"summaryCompiles\":").append(summaryCompiles)
                .append(",\"summaryReplays\":").append(summaryReplays)
                .append(",\"allInPreparedClips\":").append(allInPreparedClips)
                .append(",\"allInPreparedPlaybacks\":").append(allInPreparedPlaybacks)
                .append(",\"allInFallbackPlaybacks\":").append(allInFallbackPlaybacks)
                .append(",\"allInHotUploads\":").append(allInHotUploads)
                .append(",\"preloadPixmapCreates\":").append(preloadPixmapCreates)
                .append(",\"preloadPixmapDisposals\":").append(preloadPixmapDisposals)
                .append(",\"cpuPreloadPixelBytes\":").append(GifFramePreparation.LIVE_BYTES.get())
                .append(",\"peakCpuPreloadPixelBytes\":").append(GifFramePreparation.PEAK_BYTES.get())
                .append(",\"seatTextLayoutBuilds\":").append(IOSFittedTextCache.builds)
                .append(",\"seatTextLayoutHits\":").append(IOSFittedTextCache.hits)
                .append(",\"throttledSoundStarts\":").append(com.tonikelope.coronapoker.ios.ProfiledSound.throttledStarts())
                .append(",\"failedSoundStarts\":").append(com.tonikelope.coronapoker.ios.ProfiledSound.failedStarts())
                .append(",\"heapBytesAtReport\":").append(GC.getHeapSize())
                .append(",\"heapBytesBeforePreload\":").append(heapBeforePreload)
                .append(",\"heapBytesAfterPreload\":").append(heapAfterPreload)
                .append(",\"freeHeapBytesAfterPreload\":").append(freeAfterPreload)
                .append(",\"allInIsolation\":").append(quote(System.getenv("CP_IOS_ALLIN_ISOLATION")))
                .append(",\"phases\":[");
        for (int p = 1; p <= 2; p++) {
            if (p > 1) out.append(',');
            int size = 0;
            for (int i = 0; i < count; i++) if (phases[i] == p) size++;
            long[] sorted = new long[size];
            long total = 0, allocated = 0, renderAllocated = 0, gc = 0, render = 0;
            int offset = 0, over25 = 0, over34 = 0, over100 = 0, slowGC = 0;
            for (int i = 0; i < count; i++) if (phases[i] == p) {
                long value = intervals[i]; sorted[offset++] = value;
                total += value; allocated += allocations[i]; renderAllocated += renderAllocations[i];
                gc += collections[i]; render += renders[i];
                if (value > 25_000_000L) over25++;
                if (value > 33_333_333L) { over34++; if (collections[i] != 0) slowGC++; }
                if (value > 100_000_000L) over100++;
            }
            Arrays.sort(sorted);
            out.append("{\"name\":\"").append(p == 1 ? "table" : "summary").append("\",\"frames\":").append(size)
                    .append(",\"meanIntervalMs\":").append(size == 0 ? 0 : total / (size * 1_000_000.0))
                    .append(",\"p95Ms\":").append(percentile(sorted, .95))
                    .append(",\"p99Ms\":").append(percentile(sorted, .99))
                    .append(",\"maxMs\":").append(percentile(sorted, 1))
                    .append(",\"meanRenderMs\":").append(size == 0 ? 0 : render / (size * 1_000_000.0))
                    .append(",\"allocatedBytes\":").append(allocated)
                    .append(",\"meanProcessBytesPerFrame\":").append(size == 0 ? 0 : allocated / (double) size)
                    .append(",\"meanBytesDuringRender\":").append(size == 0 ? 0 : renderAllocated / (double) size)
                    .append(",\"meanBytesBetweenRenders\":").append(size == 0 ? 0 : (allocated - renderAllocated) / (double) size)
                    .append(",\"gcCount\":").append(gc)
                    .append(",\"over25ms\":").append(over25)
                    .append(",\"over33_3ms\":").append(over34)
                    .append(",\"over100ms\":").append(over100)
                    .append(",\"slowWithGC\":").append(slowGC).append('}');
        }
        out.append("],\"allocationScopesEnabled\":").append(SCOPES)
                .append(",\"allocationScopeUnits\":\"process bytes; nested scopes overlap\",\"allocationScopes\":[");
        boolean firstScope = true;
        if (SCOPES) for (int p = 1; p <= 2; p++) for (int i = 0; i < scopeNames.length; i++) {
            if (!firstScope) out.append(','); firstScope = false;
            out.append("{\"phase\":").append(p).append(",\"name\":\"").append(scopeNames[i])
                    .append("\",\"calls\":").append(scopeCalls[p][i]).append(",\"bytes\":").append(scopeBytes[p][i])
                    .append(",\"meanBytesPerCall\":").append(scopeCalls[p][i] == 0 ? 0
                            : scopeBytes[p][i] / (double) scopeCalls[p][i]).append('}');
        }
        out.append("],\"slowFrames\":[");
        int slow = 0;
        for (int i = 0; i < count && slow < 100; i++) if (intervals[i] > 33_333_333L) {
            if (slow++ > 0) out.append(',');
            out.append("{\"phase\":").append(phases[i]).append(",\"sequence\":").append(events[i])
                    .append(",\"intervalMs\":").append(intervals[i] / 1_000_000.0)
                    .append(",\"renderMs\":").append(renders[i] / 1_000_000.0)
                    .append(",\"gcCount\":").append(collections[i]).append('}');
        }
        IOSDiagnostics.report("ios-scene-evidence.json", out.append("]}\n").toString());
    }
    private static double percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[(int) ((sorted.length - 1) * fraction)] / 1_000_000.0;
    }
    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private IOSFrameEvidence() {}
}
