package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import com.tonikelope.coronapoker.ios.IOSPerformance;
import com.tonikelope.coronapoker.ios.BackgroundProjection;
import java.util.Arrays;
import java.util.function.ToLongFunction;
import org.robovm.rt.GC;

/** Bounded foreground samples. No live JSON, screenshots or periodic reporter. */
public final class IOSFrameEvidence {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_SCENE_EVIDENCE"));
    public static final boolean ATTRIBUTION_ENABLED = ENABLED && "1".equals(System.getenv("CP_IOS_FRAME_ATTRIBUTION"));
    private static final boolean SCOPES = ENABLED && "1".equals(System.getenv("CP_IOS_SCENE_SCOPES"));
    private static final int EVIDENCE_SECONDS = ENABLED ? durationSeconds(System.getenv("CP_IOS_EVIDENCE_SECONDS")) : 0;
    private static final int START_PHASE = ENABLED ? startPhase(System.getenv("CP_IOS_EVIDENCE_START_PHASE")) : -1;
    static {
        if (ATTRIBUTION_ENABLED && EVIDENCE_SECONDS == 0)
            throw new IllegalArgumentException("CP_IOS_FRAME_ATTRIBUTION requires timed scene evidence");
    }
    private static final long SECOND = 1_000_000_000L;
    private static final String[] PHASE_NAMES = {"preparing", "table", "summary", "menu", "startup", "frontend"};
    private static final int NO_PREVIOUS_INTERVAL = 1, SCENE_CHANGE = 2, WITHIN_FIVE_SECONDS = 4,
            COLD_START_WINDOW = 8, FIRST_AFTER_RESUME = 16;
    private static final String[] scopeNames = {"alloc.formatAmount", "alloc.formatPlayTime", "alloc.summaryDate",
            "alloc.drawText", "alloc.drawLeftText", "alloc.finalSummary", "alloc.tableFrame", "alloc.tableScene",
            "alloc.seats", "alloc.localHud", "alloc.board", "alloc.chatPoll", "alloc.uiLayer", "alloc.background",
            "alloc.applicationFrame", "alloc.fullTableFrame"};
    private static final long[][] scopeBytes = SCOPES ? new long[PHASE_NAMES.length][scopeNames.length] : null;
    private static final long[][] scopeCalls = SCOPES ? new long[PHASE_NAMES.length][scopeNames.length] : null;
    // Two times the current 60 FPS target, with a ten-second margin. The legacy
    // window keeps its original bound; timed runs need not retain twenty minutes.
    private static final int CAPACITY = EVIDENCE_SECONDS > 0
            ? EVIDENCE_SECONDS * 120 + 600 : 120_000;
    private static final long[] elapsed = ENABLED ? new long[CAPACITY] : null;
    private static final long[] intervals = ENABLED ? new long[CAPACITY] : null;
    private static final long[] renders = ENABLED ? new long[CAPACITY] : null;
    private static final long[] allocations = ENABLED ? new long[CAPACITY] : null;
    private static final long[] renderAllocations = ENABLED ? new long[CAPACITY] : null;
    private static final long[] events = ENABLED ? new long[CAPACITY] : null;
    private static final long[] collections = ENABLED ? new long[CAPACITY] : null;
    private static final long[] currentRenders = ENABLED ? new long[CAPACITY] : null;
    private static final long[] currentRenderAllocations = ENABLED ? new long[CAPACITY] : null;
    private static final long[] currentRenderCollections = ENABLED ? new long[CAPACITY] : null;
    private static final byte[] phases = ENABLED ? new byte[CAPACITY] : null;
    private static final byte[] priorPhases = ENABLED ? new byte[CAPACITY] : null;
    private static final byte[] flags = ENABLED ? new byte[CAPACITY] : null;
    private static long previous, previousBytes, previousGC, previousRender, previousRenderBytes, previousEvent;
    private static long startBytes, startGC, phaseStarted, summaryStarted;
    private static volatile long origin;
    private static long originUtcMillis, samplingEndedElapsedNanos;
    private static long foregroundOrigin, foregroundOriginUtcMillis, startCandidate, startPhaseStableNanos;
    private static long event = -1;
    private static int count, phase, previousPhase = -1, suspends;
    private static volatile boolean finished;
    private static boolean resumePending;
    private static String completionReason;
    private static final int DELIVERY_CAPACITY = 8192, DELIVERY_COLUMNS = 10;
    // Allocated once when a table installs the optional sink. A deferred table
    // run installs it before arming. Delivery and render share one foreground owner.
    private static long[][] deliveryValues;
    private static byte[] deliveryStatus, deliveryPhase;
    private static int deliveryCount, deliveryDropped, deliveryObserverFailures;
    private static boolean deliveryStorageAllocatedBeforeArm;
    public static long projectionBuilds, layoutBuilds, labelBuilds;
    public static long tableDraws, hiddenTableDraws, summaryDraws, summaryCompiles, summaryReplays;
    public static long allInPreparedClips, allInPreparedPlaybacks, allInFallbackPlaybacks, allInHotUploads;
    public static long preloadPixmapCreates, preloadPixmapDisposals;
    private static long heapBeforePreload, heapAfterPreload, freeAfterPreload;
    private static final String[] COUNTER_NAMES = {"projectionBuilds", "layoutBuilds", "labelBuilds", "tableDraws",
            "hiddenTableDraws", "summaryDraws", "summaryCompiles", "summaryReplays", "allInPreparedClips",
            "allInPreparedPlaybacks", "allInFallbackPlaybacks", "allInHotUploads", "preloadPixmapCreates",
            "preloadPixmapDisposals", "cpuPreloadPixelBytes", "peakCpuPreloadPixelBytes", "seatTextLayoutBuilds",
            "seatTextLayoutHits", "throttledSoundStarts", "failedSoundStarts"};
    private static long[] finalCounters;
    static void preloadBegin() { if(ENABLED && heapBeforePreload==0) heapBeforePreload=GC.getHeapSize(); }
    static void preloadReady() { if(ENABLED && heapAfterPreload==0) { heapAfterPreload=GC.getHeapSize();freeAfterPreload=GC.getFreeBytes(); } }

    static int durationSeconds(String configured) {
        if (configured == null) return 0;
        try {
            int seconds = Integer.parseInt(configured);
            if (seconds >= 10 && seconds <= 900) return seconds;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("CP_IOS_EVIDENCE_SECONDS must be an integer from 10 to 900");
    }

    static int startPhase(String configured) {
        if (configured == null) return -1;
        if (EVIDENCE_SECONDS == 0) throw new IllegalArgumentException("Deferred phase capture requires a timed window");
        return switch (configured) {
            case "table" -> 1;
            case "summary" -> 2;
            case "menu" -> 3;
            default -> throw new IllegalArgumentException("CP_IOS_EVIDENCE_START_PHASE must be menu, table or summary");
        };
    }

    public static long begin() {
        if (!ENABLED || finished) return 0;
        long now = System.nanoTime();
        if (ATTRIBUTION_ENABLED) {
            com.tonikelope.coronapoker.ios.IOSFrameAttribution.begin(now);
            com.tonikelope.coronapoker.ios.IOSFrameAttribution.stage(com.tonikelope.coronapoker.ios.IOSFrameAttribution.BEGIN_COUNTERS);
        }
        long bytes = GC.getTotalBytes(), gc = GC.getCount();
        if (ATTRIBUTION_ENABLED)
            com.tonikelope.coronapoker.ios.IOSFrameAttribution.stage(com.tonikelope.coronapoker.ios.IOSFrameAttribution.SHELL_PREFIX);
        return beginAt(now, bytes, gc);
    }
    // Primitive clock/counter seam for integrity tests; production reads native counters above.
    static long beginAt(long now, long bytes, long gc) {
        if (!ENABLED || finished) return 0;
        phase = 0; event = -1;
        if (foregroundOrigin == 0) {
            foregroundOrigin = now; foregroundOriginUtcMillis = System.currentTimeMillis();
            if (START_PHASE == -1) { origin = now; originUtcMillis = foregroundOriginUtcMillis; }
        }
        startBytes = bytes; startGC = gc;
        return now;
    }
    public static void scene(int next, long sequence) {
        if (!ENABLED || finished) return;
        if (next < 0 || next >= PHASE_NAMES.length) throw new IllegalArgumentException("Unknown evidence phase");
        phase = next; event = sequence;
    }
    public static boolean allocationScopesEnabled() { return SCOPES && origin != 0 && !finished; }
    public static void allocationScope(String name, long bytes) {
        if (!allocationScopesEnabled()) return;
        for (int i = 0; i < scopeNames.length; i++) if (scopeNames[i].equals(name)) {
            scopeBytes[phase][i] += bytes; scopeCalls[phase][i]++; return;
        }
    }

    public static <E> BackgroundProjection.DeliveryTrace<E> projectionTrace(ToLongFunction<E> sequence) {
        if (!ENABLED || !prepareDeliveryStorage()) return null;
        return new BackgroundProjection.DeliveryTrace<E>() {
            @Override public boolean enabled() { return origin != 0 && !finished; }
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public long gcCount() { return GC.getCount(); }
            @Override public void failed() { if (origin != 0 && !finished) deliveryObserverFailures++; }
            @Override public void record(E value, long started, long lockRequested, long lockAcquired,
                    long presentStarted, long ended, long gcStart, long gcLocked,
                    long gcPresent, long gcEnd, int status) {
                deliveryAt(sequence.applyAsLong(value), started, lockRequested, lockAcquired,
                        presentStarted, ended, gcStart, gcLocked, gcPresent, gcEnd, status);
            }
        };
    }
    private static synchronized boolean prepareDeliveryStorage() {
        if (finished) return false;
        if (deliveryValues != null) return true;
        deliveryStorageAllocatedBeforeArm = origin == 0;
        deliveryValues = new long[DELIVERY_COLUMNS][DELIVERY_CAPACITY];
        deliveryStatus = new byte[DELIVERY_CAPACITY]; deliveryPhase = new byte[DELIVERY_CAPACITY];
        return true;
    }
    static void deliveryAt(long sequence, long started, long lockRequested, long lockAcquired,
            long presentStarted, long ended, long gcStart, long gcLocked,
            long gcPresent, long gcEnd, int status) {
        if (!ENABLED || origin == 0 || finished || deliveryValues == null) return;
        if (deliveryCount == DELIVERY_CAPACITY) { deliveryDropped++; return; }
        int i = deliveryCount++;
        deliveryValues[0][i] = sequence;
        deliveryValues[1][i] = started - origin;
        deliveryValues[2][i] = lockRequested - origin;
        deliveryValues[3][i] = lockAcquired - origin;
        deliveryValues[4][i] = presentStarted == -1 ? -1 : presentStarted - origin;
        deliveryValues[5][i] = ended - origin;
        deliveryValues[6][i] = gcStart; deliveryValues[7][i] = gcLocked;
        deliveryValues[8][i] = gcPresent; deliveryValues[9][i] = gcEnd;
        deliveryStatus[i] = (byte) status; deliveryPhase[i] = (byte) phase;
    }

    public static void end(long started) {
        if (!ENABLED || started == 0 || finished) return;
        long now = System.nanoTime();
        if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.end(now);
        endAt(started, now, GC.getTotalBytes(), GC.getCount());
    }
    static void endAt(long started, long now, long bytes, long gc) {
        if (!ENABLED || started == 0 || finished) return;
        if (origin == 0) {
            // Startup overlays and page transitions cannot consume the requested
            // scene window. Only five continuous foreground seconds arm it.
            if (phase != START_PHASE) {
                startCandidate = 0;
                if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.discard();
                return;
            }
            if (startCandidate == 0) startCandidate = started;
            if (started - startCandidate < 5 * SECOND) {
                if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.discard();
                return;
            }
            startPhaseStableNanos = started - startCandidate;
            origin = started;
            originUtcMillis = System.currentTimeMillis() - (now - started) / 1_000_000;
        }
        boolean hasPrevious = previous != 0;
        boolean changed = hasPrevious && phase != previousPhase;
        if (!hasPrevious || changed) {
            phaseStarted = EVIDENCE_SECONDS > 0 ? started : now;
            if (phase == 2) summaryStarted = now;
        }
        int sampleFlags = (hasPrevious ? 0 : NO_PREVIOUS_INTERVAL) | (changed ? SCENE_CHANGE : 0)
                | ((EVIDENCE_SECONDS > 0 ? started : now) - phaseStarted <= 5 * SECOND ? WITHIN_FIVE_SECONDS : 0)
                | (started - origin <= 5 * SECOND ? COLD_START_WINDOW : 0)
                | (resumePending ? FIRST_AFTER_RESUME : 0);
        boolean legacySteady = (phase == 1 || phase == 2) && hasPrevious && !changed
                && now - phaseStarted > 5 * SECOND;
        if ((EVIDENCE_SECONDS > 0 || legacySteady) && count < CAPACITY) {
            elapsed[count] = started - origin;
            intervals[count] = hasPrevious ? started - previous : 0;
            renders[count] = hasPrevious ? previousRender : 0;
            allocations[count] = hasPrevious ? startBytes - previousBytes : 0;
            renderAllocations[count] = hasPrevious ? previousRenderBytes : 0;
            events[count] = hasPrevious ? previousEvent : -1;
            collections[count] = hasPrevious ? startGC - previousGC : 0;
            currentRenders[count] = now - started;
            currentRenderAllocations[count] = bytes - startBytes;
            currentRenderCollections[count] = gc - startGC;
            phases[count] = (byte) phase;
            priorPhases[count] = (byte) (hasPrevious ? previousPhase : -1);
            flags[count] = (byte) sampleFlags;
            if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.retain(
                    count, origin, phase, sampleFlags, hasPrevious ? started - previous : -1,
                    hasPrevious ? startGC - previousGC : -1, gc - startGC);
            count++;
        }
        previous = started; previousBytes = startBytes; previousGC = startGC; previousPhase = phase;
        previousRender = now - started;
        previousRenderBytes = bytes - startBytes;
        previousEvent = event; resumePending = false;
        if (count == CAPACITY) finish(now, "capacity");
        else if (EVIDENCE_SECONDS > 0 && now - origin >= EVIDENCE_SECONDS * SECOND) finish(now, "duration");
        else if (EVIDENCE_SECONDS == 0 && summaryStarted != 0 && phase == 2
                && now - summaryStarted >= 65 * SECOND) finish(now, "summary");
    }
    public static void suspend() {
        if (!ENABLED || finished) return;
        if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.suspend();
        if (previous != 0) suspends++;
        previous = 0; previousPhase = -1; phase = 0;
        startCandidate = 0;
        if (origin != 0) resumePending = true;
    }

    private static synchronized void finish(long now, String reason) {
        // Foreground writes stay single-owner. This cold-path monitor excludes
        // late sink installation; Thread.start publishes fully initialized storage.
        if (finished) return;
        finished = true; completionReason = reason; samplingEndedElapsedNanos = now - origin;
        if (ATTRIBUTION_ENABLED) com.tonikelope.coronapoker.ios.IOSFrameAttribution.freeze();
        finalCounters = new long[]{projectionBuilds, layoutBuilds, labelBuilds, tableDraws,
                hiddenTableDraws, summaryDraws, summaryCompiles, summaryReplays, allInPreparedClips,
                allInPreparedPlaybacks, allInFallbackPlaybacks, allInHotUploads, preloadPixmapCreates,
                preloadPixmapDisposals, GifFramePreparation.LIVE_BYTES.get(), GifFramePreparation.PEAK_BYTES.get(),
                IOSFittedTextCache.builds, IOSFittedTextCache.hits,
                com.tonikelope.coronapoker.ios.ProfiledSound.throttledStarts(),
                com.tonikelope.coronapoker.ios.ProfiledSound.failedStarts()};
        Thread writer = new Thread(IOSFrameEvidence::report, "coronapoker-ios-scene-report");
        writer.setDaemon(true); writer.start();
    }

    private static void report() {
        StringBuilder out = new StringBuilder("{\"kind\":\"retained-scene-v5\",\"preferredFPS\":60,\"liveReporter\":")
                .append(IOSPerformance.ENABLED);
        out.append(",\"runID\":").append(quote(System.getenv("CP_IOS_RUN_ID")))
                .append(",\"reportedUtcMillis\":").append(System.currentTimeMillis())
                .append(",\"timedEvidence\":").append(EVIDENCE_SECONDS > 0)
                .append(",\"evidenceSeconds\":").append(EVIDENCE_SECONDS)
                .append(",\"originUtcMillis\":").append(originUtcMillis)
                .append(",\"originMonotonicNanos\":").append(origin)
                .append(",\"evidenceStartPhase\":").append(START_PHASE == -1 ? "null" : quote(PHASE_NAMES[START_PHASE]))
                .append(",\"firstForegroundUtcMillis\":").append(foregroundOriginUtcMillis)
                .append(",\"firstForegroundMonotonicNanos\":").append(foregroundOrigin)
                .append(",\"startPhaseStableNanos\":").append(startPhaseStableNanos)
                .append(",\"backgroundMusicBackend\":").append(quote(com.tonikelope.coronapoker.ios.IOSMusic.backgroundBackendName()))
                .append(",\"gcRuntime\":").append(com.tonikelope.coronapoker.ios.IOSGCRuntime.snapshotJson())
                .append(",\"samplingEndedElapsedNanos\":").append(samplingEndedElapsedNanos)
                .append(",\"completionReason\":").append(quote(completionReason))
                .append(",\"sampledFrames\":").append(count)
                .append(",\"sampleCapacity\":").append(CAPACITY)
                .append(",\"sampleStoragePrimitiveBytes\":").append(CAPACITY * 83L)
                .append(",\"suspendCount\":").append(suspends)
                .append(",\"crypto\":").append(quote(System.getenv("CP_IOS_CRYPTO_TEST")))
                .append(",\"audio\":").append(quote(System.getenv("CP_IOS_AUDIO_TEST")))
                .append(",\"animation\":").append(quote(System.getenv("CP_IOS_ANIMATION_TEST")))
                .append(",\"screenshotDiagnostics\":").append("1".equals(System.getenv("CP_IOS_DIAGNOSTICS")))
                .append(",\"pathStress\":").append("1".equals(System.getenv("CP_IOS_PATH_STRESS")))
                .append(",\"intervalSemantics\":\"interval ends at current render start; previousRender belongs to previousPhase; currentRender belongs to currentPhase\"")
                .append(",\"allocationCounterScope\":\"process-wide including concurrent workers; not render-thread attribution\"")
                .append(",\"timerSemantics\":\"monotonic elapsed since capture origin; optional selected phase starts after five stable foreground seconds; while suspended completion waits for the next foreground render\"")
                .append(",\"coldStartWindowSemantics\":\"first five seconds of foreground render sampling; excludes process and listener create initialization\"")
                .append(",\"phaseNames\":[\"preparing\",\"table\",\"summary\",\"menu\",\"startup\",\"frontend\"]")
                .append(",\"flagsMap\":{\"noPreviousInterval\":1,\"sceneChange\":2,\"within5SecondsOfSceneEntry\":4,\"coldStartWindow\":8,\"firstAfterResume\":16}")
                .append(",\"diagnosticConfig\":{");
        String[] configNames = {"profile", "diagnostics", "pathStress", "scenario", "audioTest", "cryptoTest",
                "animationTest", "tablePaintTest", "tableDrawTest", "allInIsolation", "backendProbe", "sceneScopes", "vertexProbe",
                "voiceProbe", "scrollTest", "quoteWrapCache", "musicBackend", "musicProbe", "gcProbe", "audioClockSeconds", "frameAttribution"};
        String[] configEnvs = {"CP_IOS_PROFILE", "CP_IOS_DIAGNOSTICS", "CP_IOS_PATH_STRESS", "CP_IOS_SCENARIO",
                "CP_IOS_AUDIO_TEST", "CP_IOS_CRYPTO_TEST", "CP_IOS_ANIMATION_TEST", "CP_IOS_TABLE_PAINT_TEST",
                "CP_IOS_TABLE_DRAW_PROBE", "CP_IOS_ALLIN_ISOLATION", "CP_IOS_BACKEND_PROBE", "CP_IOS_SCENE_SCOPES", "CP_IOS_VERTEX_PROBE",
                "CP_IOS_VOICE_PROBE", "CP_IOS_SCROLL_TEST", "CP_IOS_QUOTE_WRAP_CACHE", "CP_IOS_MUSIC_BACKEND", "CP_IOS_MUSIC_PROBE", "CP_IOS_GC_PROBE", "CP_IOS_AUDIO_CLOCK_SECONDS", "CP_IOS_FRAME_ATTRIBUTION"};
        for (int i = 0; i < configNames.length; i++) {
            if (i > 0) out.append(',');
            out.append(quote(configNames[i])).append(':').append(quote(System.getenv(configEnvs[i])));
        }
        out.append('}');
        for (int i = 0; i < COUNTER_NAMES.length; i++)
            out.append(',').append(quote(COUNTER_NAMES[i])).append(':').append(finalCounters[i]);
        out.append(",\"heapBytesAtReport\":").append(GC.getHeapSize())
                .append(",\"heapBytesBeforePreload\":").append(heapBeforePreload)
                .append(",\"heapBytesAfterPreload\":").append(heapAfterPreload)
                .append(",\"freeHeapBytesAfterPreload\":").append(freeAfterPreload)
                .append(",\"allInIsolation\":").append(quote(System.getenv("CP_IOS_ALLIN_ISOLATION")))
                .append(",\"phases\":[");
        int firstPhase = EVIDENCE_SECONDS > 0 ? 0 : 1, lastPhase = EVIDENCE_SECONDS > 0 ? PHASE_NAMES.length - 1 : 2;
        for (int p = firstPhase; p <= lastPhase; p++) {
            if (p > firstPhase) out.append(',');
            appendPhase(out, p);
        }
        out.append("],\"allocationScopesEnabled\":").append(SCOPES)
                .append(",\"allocationScopeUnits\":\"process bytes; nested scopes overlap\",\"allocationScopes\":[");
        boolean firstScope = true;
        if (SCOPES) for (int p = firstPhase; p <= lastPhase; p++) for (int i = 0; i < scopeNames.length; i++) {
            if (!firstScope) out.append(','); firstScope = false;
            out.append("{\"phase\":").append(p).append(",\"name\":\"").append(scopeNames[i])
                    .append("\",\"calls\":").append(scopeCalls[p][i]).append(",\"bytes\":").append(scopeBytes[p][i])
                    .append(",\"meanBytesPerCall\":").append(scopeCalls[p][i] == 0 ? 0
                            : scopeBytes[p][i] / (double) scopeCalls[p][i]).append('}');
        }
        out.append("],\"slowFrames\":[");
        int slow = 0;
        for (int i = 0; i < count && slow < 100; i++) if (validInterval(i) && intervals[i] > 33_333_333L) {
            if (slow++ > 0) out.append(',');
            out.append("{\"phase\":").append(priorPhases[i]).append(",\"sequence\":").append(events[i])
                    .append(",\"intervalMs\":").append(intervals[i] / 1_000_000.0)
                    .append(",\"renderMs\":").append(renders[i] / 1_000_000.0)
                    .append(",\"gcCount\":").append(collections[i]).append('}');
        }
        out.append(']');
        appendDeliveryEvidence(out);
        out.append(",\"frameAttribution\":").append(ATTRIBUTION_ENABLED
                ? com.tonikelope.coronapoker.ios.IOSFrameAttribution.snapshotJson() : "{\"enabled\":false}");
        out.append(",\"samples\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            boolean valid = validInterval(i);
            out.append("{\"intervalEndElapsedNanos\":").append(elapsed[i])
                    .append(",\"intervalNs\":"); appendNullable(out, valid, intervals[i]);
            out.append(",\"previousRenderNs\":"); appendNullable(out, valid, renders[i]);
            out.append(",\"intervalProcessAllocatedBytes\":"); appendNullable(out, valid, allocations[i]);
            out.append(",\"previousRenderProcessAllocatedBytes\":"); appendNullable(out, valid, renderAllocations[i]);
            out.append(",\"intervalGCCount\":"); appendNullable(out, valid, collections[i]);
            out.append(",\"previousPhase\":").append(priorPhases[i])
                    .append(",\"currentPhase\":").append(phases[i])
                    .append(",\"previousSequence\":").append(events[i])
                    .append(",\"flags\":").append(flags[i])
                    .append(",\"currentRenderNs\":").append(currentRenders[i])
                    .append(",\"currentRenderProcessAllocatedBytes\":").append(currentRenderAllocations[i])
                    .append(",\"currentRenderGCCount\":").append(currentRenderCollections[i]).append('}');
        }
        IOSDiagnostics.report("ios-scene-evidence.json", out.append("]}\n").toString());
    }
    private static void appendDeliveryEvidence(StringBuilder out) {
        out.append(",\"projectionDelivery\":{\"kind\":\"foreground-projection-delivery-v1\",\"installed\":")
                .append(deliveryValues != null).append(",\"capacity\":").append(DELIVERY_CAPACITY)
                .append(",\"stored\":").append(deliveryCount).append(",\"dropped\":").append(deliveryDropped)
                .append(",\"observerFailures\":").append(deliveryObserverFailures)
                .append(",\"primitiveStorageBytes\":").append(deliveryValues == null ? 0 : DELIVERY_CAPACITY * 82L)
                .append(",\"storageAllocatedBeforeArm\":").append(deliveryValues == null ? "null" : deliveryStorageAllocatedBeforeArm)
                .append(",\"scope\":\"Foreground projection delivery only; excludes queue wait and asynchronous visual completion. Origin and end use the frame clock. Counter reads add overhead and are process-wide; GC deltas are observations, not collection durations. Other outside-render work is uninstrumented. Phase is the most recent render marker.\"")
                .append(",\"statusMap\":{\"presented\":0,\"skipped\":1,\"presentThrew\":2}")
                .append(",\"timeSemantics\":\"taskStart before first counter read; lockRequest immediately before monitor entry; lockAcquired inside monitor; presentStart after setup/counter read before the synchronous present callback (usually acceptEvent); end after that callback returns/throws and the final counter read\"")
                .append(",\"columns\":[\"sequence\",\"taskStartElapsedNanos\",\"lockRequestElapsedNanos\",\"lockAcquiredElapsedNanos\",\"presentStartElapsedNanos\",\"endElapsedNanos\",\"gcAtTaskStart\",\"gcAtLockAcquired\",\"gcBeforePresent\",\"gcAtEnd\",\"status\",\"lastMarkedPhase\"],\"samples\":[");
        for (int i = 0; i < deliveryCount; i++) {
            if (i > 0) out.append(',');
            out.append('[');
            for (int column = 0; column < DELIVERY_COLUMNS; column++) {
                if (column > 0) out.append(',');
                long value = deliveryValues[column][i];
                if ((column == 4 || column == 8) && value == -1) out.append("null");
                else out.append(value);
            }
            out.append(',').append(deliveryStatus[i]).append(',').append(deliveryPhase[i]).append(']');
        }
        out.append("]}");
    }
    private static boolean validInterval(int sample) { return (flags[sample] & NO_PREVIOUS_INTERVAL) == 0; }
    private static boolean steadyInterval(int sample, int p) {
        return validInterval(sample) && phases[sample] == p && priorPhases[sample] == p
                && (flags[sample] & WITHIN_FIVE_SECONDS) == 0;
    }
    private static void appendPhase(StringBuilder out, int p) {
        int size = 0, sampled = 0;
        for (int i = 0; i < count; i++) { if (phases[i] == p) sampled++; if (steadyInterval(i, p)) size++; }
        long[] sorted = new long[size];
        long total = 0, allocated = 0, renderAllocated = 0, gc = 0, render = 0;
        int offset = 0, over25 = 0, over34 = 0, over100 = 0, slowGC = 0;
        for (int i = 0; i < count; i++) if (steadyInterval(i, p)) {
            long value = intervals[i]; sorted[offset++] = value;
            total += value; allocated += allocations[i]; renderAllocated += renderAllocations[i];
            gc += collections[i]; render += renders[i];
            if (value > 25_000_000L) over25++;
            if (value > 33_333_333L) { over34++; if (collections[i] != 0) slowGC++; }
            if (value > 100_000_000L) over100++;
        }
        Arrays.sort(sorted);
        out.append("{\"name\":").append(quote(PHASE_NAMES[p])).append(",\"phase\":").append(p)
                .append(",\"frames\":").append(size).append(",\"sampledFrames\":").append(sampled)
                .append(",\"filter\":\"valid interval; same scene; after entry warmup\"")
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
    private static void appendNullable(StringBuilder out, boolean valid, long value) {
        if (valid) out.append(value); else out.append("null");
    }
    private static double percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[(int) ((sorted.length - 1) * fraction)] / 1_000_000.0;
    }
    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    private IOSFrameEvidence() {}
}
