package com.tonikelope.coronapoker.ios;

import java.util.Arrays;

/** Opt-in foreground timing. Primitive storage only; no worker, live report or GC hook. */
public final class IOSFrameAttribution {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_SCENE_EVIDENCE"))
            && "1".equals(System.getenv("CP_IOS_FRAME_ATTRIBUTION"));
    public static final int UNMARKED = 0, FRONT_KEYBOARD = 1, FONT = 2, FRONT_UPDATE = 3,
            FRONT_PREPARE = 4, FRONT_SHAPES = 5, FRONT_TEXT = 6, FRONT_TAIL = 7,
            TABLE_PRELUDE = 8, TABLE_UPDATE = 9, TABLE_PREPARE = 10, TABLE_PAINT = 11,
            TABLE_TAIL = 12, SUMMARY_PREPARE = 13, SUMMARY_PAINT = 14, SUMMARY_REPLAY = 15,
            BEGIN_COUNTERS = 16, SHELL_PREFIX = 17, SHELL_TAIL = 18, KEYBOARD_FIT = 19;
    private static final String[] NAMES = ENABLED ? new String[]{"unmarkedRecorder", "frontendKeyboard", "fontRefresh",
            "frontendUpdateMusic", "frontendPrepareClear", "frontendLayoutAndShapes",
            "frontendTextAndBatchEnd", "frontendTail", "tablePrelude", "tableUpdateAudio",
            "tablePrepareClear", "tablePaint", "tableTail", "summaryPrepareCache",
            "summaryLiveOrCompile", "summaryReplayAndBatchEnd", "beginGcCounters", "shellPrefix",
            "shellTail", "keyboardFit"} : null;
    private static final int STAGES = 20, CAPACITY = 256, COLUMNS = 15;
    private static final long THRESHOLD_NS = 25_000_000L;
    private static final long[][] values = ENABLED ? new long[COLUMNS][CAPACITY] : null;
    private static final long[][] walls = ENABLED ? new long[STAGES][CAPACITY] : null;
    private static final long[][] cpus = ENABLED ? new long[STAGES][CAPACITY] : null;
    private static final long[] currentWall = ENABLED ? new long[STAGES] : null;
    private static final long[] currentCpu = ENABLED ? new long[STAGES] : null;
    private static final long[] priorWall = ENABLED ? new long[STAGES] : null;
    private static final long[] priorCpu = ENABLED ? new long[STAGES] : null;
    private static long started, startCpu, ended, renderCpu, lastWall, lastCpu, maxBracket;
    private static long priorStarted, priorEnded, priorStartCpu, priorRenderCpu, priorBracket;
    private static long priorIndex, priorGc;
    private static int stage, seen, invalid, invalidWall, priorSeen, priorInvalid, priorInvalidWall, priorPhase, priorFlags;
    private static int count, dropped, priorSlot = -1;
    private static long frames, validCpuFrames, wallTotal, cpuTotal, clockReads, clockFailures,
            clockAnomalies, stageErrors, resets, maxRender, nativeCalls, threadOwnerFailures;
    private static long resolution = -1, readBracket;
    private static boolean initialized, open, ready, priorReady, frozen;

    private IOSFrameAttribution() {}
    private static native long cpuNanosNative();
    private static native long cpuResolutionNanosNative();

    private static long readCpu(long wall) {
        clockReads++;
        nativeCalls++;
        long cpu = cpuNanosNative();
        readBracket = System.nanoTime() - wall;
        if (cpu == -2) threadOwnerFailures++;
        else if (cpu < 0) clockFailures++;
        return cpu;
    }
    public static void begin(long wall) {
        if (!ENABLED || frozen) return;
        if (!initialized) {
            nativeCalls++;
            resolution = cpuResolutionNanosNative();
            initialized = true;
            if (resolution == -2) threadOwnerFailures++;
            else if (resolution < 0) clockFailures++;
        }
        long cpu = readCpu(wall);
        beginAt(wall, cpu, readBracket);
    }
    public static void stage(int next) {
        if (!ENABLED || frozen || !open || next == stage) return;
        long wall = System.nanoTime(), cpu = readCpu(wall);
        stageAt(next, wall, cpu, readBracket);
    }
    /** No clock read: callers restore their own stage after a nested operation. */
    public static int currentStage() {
        return ENABLED && !frozen && open ? stage : -1;
    }
    public static void end(long wall) {
        if (!ENABLED || frozen || !open) return;
        long cpu = readCpu(wall);
        endAt(wall, cpu, readBracket);
    }
    // Actual recorder seams: deterministic tests do not mirror timing/storage logic.
    static void beginAt(long wall, long cpu, long bracket) {
        if (!ENABLED || frozen) return;
        if (open || ready) stageErrors++;
        Arrays.fill(currentWall, 0); Arrays.fill(currentCpu, 0);
        started = lastWall = wall; startCpu = lastCpu = cpu;
        maxBracket = Math.max(0, bracket); seen = invalid = invalidWall = 0; stage = UNMARKED;
        open = true; ready = false;
    }
    static void resolutionAt(long value) { if (ENABLED && !frozen) resolution = value; }
    private static long deltaCpu(long from, long to, long wall, long bracket) {
        if (resolution < 0 || from < 0 || to < 0) return -1;
        long delta = to - from;
        long tolerance = Math.max(0, resolution) * 2 + Math.max(0, bracket);
        if (delta < 0 || wall < 0 || delta > wall + tolerance) { clockAnomalies++; return -1; }
        return delta;
    }
    private static void closeStage(long wall, long cpu, long bracket) {
        maxBracket = Math.max(maxBracket, Math.max(0, bracket));
        seen |= 1 << stage;
        long duration = wall - lastWall;
        if (duration < 0) { stageErrors++; invalid |= 1 << stage; invalidWall |= 1 << stage; }
        else currentWall[stage] += duration;
        long work = deltaCpu(lastCpu, cpu, duration, maxBracket * 2);
        if (work < 0) invalid |= 1 << stage;
        else currentCpu[stage] += work;
        lastWall = wall; lastCpu = cpu;
    }
    static void stageAt(int next, long wall, long cpu, long bracket) {
        if (!ENABLED || frozen || !open) return;
        if (next < 0 || next >= STAGES) { stageErrors++; invalid |= 1 << stage; return; }
        closeStage(wall, cpu, bracket); stage = next;
    }
    static void endAt(long wall, long cpu, long bracket) {
        if (!ENABLED || frozen || !open) return;
        closeStage(wall, cpu, bracket);
        ended = wall; renderCpu = deltaCpu(startCpu, cpu, wall - started, maxBracket * 2);
        open = false; ready = true;
    }
    private static int append(long index, long origin, long start, long end, long cpu,
            long gc, int phase, int sampleFlags, int mask, int bad, int badWall, long bracket,
            long[] stageWall, long[] stageCpu) {
        if (count == CAPACITY) { dropped++; return -2; }
        int at = count++;
        values[0][at] = index; values[1][at] = -1;
        values[2][at] = start - origin; values[3][at] = end - origin;
        values[4][at] = cpu; values[5][at] = values[6][at] = -1;
        values[7][at] = phase; values[8][at] = sampleFlags; values[9][at] = gc;
        values[10][at] = -1; values[11][at] = mask; values[12][at] = bad;
        values[13][at] = bracket; values[14][at] = badWall;
        for (int j = 0; j < STAGES; j++) { walls[j][at] = stageWall[j]; cpus[j][at] = stageCpu[j]; }
        return at;
    }
    /** Called only for an actual retained raw sample; current render belongs to this sample index. */
    public static void retain(int index, long origin, int phase, int sampleFlags,
            long interval, long intervalGc, long renderGc) {
        if (!ENABLED || frozen) return;
        if (!ready) { stageErrors++; return; }
        boolean pair = (sampleFlags & 1) == 0 && priorReady && priorIndex + 1 == index;
        if (pair && interval > THRESHOLD_NS) {
            int at = priorSlot;
            if (at == -1) at = append(priorIndex, origin, priorStarted, priorEnded, priorRenderCpu,
                    priorGc, priorPhase, priorFlags, priorSeen, priorInvalid, priorInvalidWall, priorBracket, priorWall, priorCpu);
            if (at >= 0) {
                values[1][at] = index; values[5][at] = interval;
                values[6][at] = deltaCpu(priorStartCpu, startCpu, interval, priorBracket + maxBracket);
                values[10][at] = intervalGc;
            }
        }
        int slot = -1;
        if (ended - started > THRESHOLD_NS)
            slot = append(index, origin, started, ended, renderCpu, renderGc, phase,
                    sampleFlags, seen, invalid, invalidWall, maxBracket, currentWall, currentCpu);
        frames++; wallTotal += ended - started; maxRender = Math.max(maxRender, ended - started);
        if (renderCpu >= 0) { validCpuFrames++; cpuTotal += renderCpu; }
        System.arraycopy(currentWall, 0, priorWall, 0, STAGES);
        System.arraycopy(currentCpu, 0, priorCpu, 0, STAGES);
        priorStarted = started; priorEnded = ended; priorStartCpu = startCpu;
        priorRenderCpu = renderCpu; priorBracket = maxBracket; priorSeen = seen; priorInvalid = invalid; priorInvalidWall = invalidWall;
        priorIndex = index; priorGc = renderGc; priorPhase = phase; priorFlags = sampleFlags;
        priorReady = true; priorSlot = slot; ready = false;
    }
    public static void discard() {
        if (!ENABLED || frozen) return;
        open = ready = priorReady = false; priorSlot = -1;
    }
    public static void suspend() {
        if (!ENABLED || frozen) return;
        open = ready = priorReady = false; priorSlot = -1; resets++;
    }
    public static void freeze() {
        if (!ENABLED || frozen) return;
        open = ready = false; frozen = true;
    }
    private static void nullable(StringBuilder out, long value) {
        if (value < 0) out.append("null"); else out.append(value);
    }
    /** Writer-only serialization after IOSFrameEvidence freezes and starts its report thread. */
    public static String snapshotJson() {
        if (!ENABLED) return "{\"enabled\":false}";
        StringBuilder out = new StringBuilder("{\"enabled\":true,\"kind\":\"bounded-main-thread-attribution-v2\",\"normalAcceptance\":false");
        out.append(",\"clock\":\"clock_gettime(CLOCK_THREAD_CPUTIME_ID)\",\"resolutionNs\":"); nullable(out, resolution);
        out.append(",\"frozen\":").append(frozen).append(",\"capacity\":").append(CAPACITY)
                .append(",\"stored\":").append(count).append(",\"dropped\":").append(dropped)
                .append(",\"storagePrimitiveBytes\":").append((COLUMNS + STAGES * 2L) * CAPACITY * 8 + STAGES * 4L * 8)
                .append(",\"retainedFrames\":").append(frames).append(",\"validCpuFrames\":").append(validCpuFrames)
                .append(",\"renderWallTotalNs\":").append(wallTotal).append(",\"validRenderCpuTotalNs\":").append(cpuTotal)
                .append(",\"maxRenderNs\":").append(maxRender).append(",\"clockReads\":").append(clockReads)
                .append(",\"nativeClockCalls\":").append(nativeCalls).append(",\"threadOwnerFailures\":").append(threadOwnerFailures)
                .append(",\"clockFailures\":").append(clockFailures).append(",\"clockAnomalies\":").append(clockAnomalies)
                .append(",\"stageErrors\":").append(stageErrors).append(",\"suspendResets\":").append(resets)
                .append(",\"thresholdNs\":").append(THRESHOLD_NS)
                .append(",\"stageCount\":").append(STAGES)
                .append(",\"clockCounterScope\":\"Since diagnostic initialization, including deferred pre-arm renders; frame totals cover retained samples only\"")
                .append(",\"counterReadTiming\":\"beginGcCounters brackets the two begin GC reads plus boundary observation overhead; end GC reads occur after render wall/CPU endpoints and remain unmeasured\"")
                .append(",\"selection\":\"render>25ms or previous render of interval>25ms; one event per render; unpaired final current render retained\"")
                .append(",\"semantics\":\"Exclusive marked stages, missing=null; current render sample index pairs only with next valid interval sample; wall minus thread CPU is offCPU, not a blocked/GC/GPU duration. Clock-read brackets are observer uncertainty; CPU includes this main thread only, not GC helpers.\",\"events\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append("{\"renderSampleIndex\":").append(values[0][i]).append(",\"intervalSampleIndex\":"); nullable(out, values[1][i]);
            out.append(",\"renderStartElapsedNs\":").append(values[2][i]).append(",\"renderEndElapsedNs\":").append(values[3][i])
                    .append(",\"renderCpuNs\":"); nullable(out, values[4][i]);
            out.append(",\"intervalNs\":"); nullable(out, values[5][i]);
            out.append(",\"intervalCpuNs\":"); nullable(out, values[6][i]);
            out.append(",\"phase\":").append(values[7][i]).append(",\"flags\":").append(values[8][i])
                    .append(",\"renderGCCount\":").append(values[9][i]).append(",\"intervalGCCount\":"); nullable(out, values[10][i]);
            out.append(",\"seenMask\":").append(values[11][i]).append(",\"invalidCpuMask\":").append(values[12][i])
                    .append(",\"invalidWallMask\":").append(values[14][i])
                    .append(",\"maxClockReadBracketNs\":").append(values[13][i]).append(",\"stages\":[");
            for (int j = 0; j < STAGES; j++) {
                if (j > 0) out.append(',');
                out.append("{\"name\":\"").append(NAMES[j]).append("\",\"wallNs\":");
                nullable(out, (values[11][i] & 1 << j) == 0 || (values[14][i] & 1 << j) != 0 ? -1 : walls[j][i]);
                out.append(",\"cpuNs\":");
                nullable(out, (values[11][i] & 1 << j) == 0 || (values[12][i] & 1 << j) != 0 ? -1 : cpus[j][i]);
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }
}
