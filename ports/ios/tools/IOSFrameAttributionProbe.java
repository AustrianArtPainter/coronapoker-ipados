package com.tonikelope.coronapoker.ios;

import java.lang.reflect.Field;

/** Tests the actual bounded recorder with deterministic clock points, never a reimplementation. */
public final class IOSFrameAttributionProbe {
    private static native long stubCalls();
    private static native void stubCpu(long value);
    private static native void stubResolution(long value);
    private static final long B = 1_000_000_000L, M = 1_000_000L;
    private static long field(String name) throws Exception {
        Field f = IOSFrameAttribution.class.getDeclaredField(name); f.setAccessible(true);
        return ((Number) f.get(null)).longValue();
    }
    private static long stageValue(String name, int stage) throws Exception {
        Field f = IOSFrameAttribution.class.getDeclaredField(name); f.setAccessible(true);
        return ((long[]) f.get(null))[stage];
    }
    private static void expectStage(int expected) {
        if (IOSFrameAttribution.currentStage() != expected) throw new AssertionError("Stage context: " + expected);
    }
    private static void frame(int index, long start, long cpu, long duration, long work, int flags, long interval) {
        IOSFrameAttribution.beginAt(start, cpu, 1);
        IOSFrameAttribution.endAt(start + duration, cpu < 0 ? cpu : cpu + work, 1);
        IOSFrameAttribution.retain(index, B, 3, flags, interval, interval < 0 ? -1 : 0, 0);
    }
    public static void main(String[] args) throws Exception {
        System.load(args[1]);
        String mode = args[0];
        if (mode.equals("disabled")) {
            expectStage(-1);
            IOSFrameAttribution.begin(B); IOSFrameAttribution.stage(IOSFrameAttribution.FONT);
            expectStage(-1);
            IOSFrameAttribution.end(B + M); IOSFrameAttribution.retain(0, B, 3, 1, -1, -1, 0);
            IOSFrameAttribution.suspend(); IOSFrameAttribution.freeze();
            expectStage(-1);
            for (String n : new String[]{"values", "walls", "cpus", "currentWall", "currentCpu", "priorWall", "priorCpu", "NAMES"}) {
                Field f = IOSFrameAttribution.class.getDeclaredField(n); f.setAccessible(true);
                if (f.get(null) != null) throw new AssertionError("Disabled storage: " + n);
            }
            if (stubCalls() != 0 || field("nativeCalls") != 0 || field("clockReads") != 0) throw new AssertionError("Disabled native getter");
            System.out.println("{\"enabled\":false,\"nativeClockCalls\":0,\"allStageArraysNull\":true}");
            return;
        }
        IOSFrameAttribution.resolutionAt(1);
        if (mode.startsWith("native-")) {
            stubResolution(mode.equals("native-resolution-failure") ? -1 : 1);
            stubCpu(mode.equals("native-owner-failure") ? -2 : mode.equals("native-clock-failure") ? -1 : 0);
            long now = System.nanoTime(); IOSFrameAttribution.begin(now);
            IOSFrameAttribution.stage(IOSFrameAttribution.FONT);
            IOSFrameAttribution.end(System.nanoTime());
            IOSFrameAttribution.retain(0, now, 3, 1, -1, -1, 0);
            if (stubCalls() != 4) throw new AssertionError("Expected one resolution and three CPU reads");
        } else if (mode.equals("split-stages")) {
            IOSFrameAttribution.beginAt(B, 0, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.BEGIN_COUNTERS, B+M, M/2, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.SHELL_PREFIX, B+3*M, 3*M/2, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.KEYBOARD_FIT, B+5*M, 5*M/2, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.SHELL_PREFIX, B+9*M, 9*M/2, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.TABLE_PRELUDE, B+12*M, 6*M, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.KEYBOARD_FIT, B+15*M, 15*M/2, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.TABLE_PRELUDE, B+20*M, 10*M, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.SHELL_TAIL, B+25*M, 25*M/2, 1);
            IOSFrameAttribution.endAt(B+30*M, 15*M, 1);
            expectStage(-1);
            int[] stages = {IOSFrameAttribution.UNMARKED, IOSFrameAttribution.BEGIN_COUNTERS,
                    IOSFrameAttribution.SHELL_PREFIX, IOSFrameAttribution.KEYBOARD_FIT,
                    IOSFrameAttribution.TABLE_PRELUDE, IOSFrameAttribution.SHELL_TAIL};
            long[] milliseconds = {1, 2, 5, 9, 8, 5};
            long wall = 0, cpu = 0; int mask = 0;
            for (int i=0; i<stages.length; i++) {
                long expected = milliseconds[i]*M;
                if (stageValue("currentWall", stages[i]) != expected
                        || stageValue("currentCpu", stages[i]) != expected/2)
                    throw new AssertionError("Repeated stage total: " + stages[i]);
                wall += stageValue("currentWall", stages[i]); cpu += stageValue("currentCpu", stages[i]);
                mask |= 1 << stages[i];
            }
            if (wall != 30*M || cpu != 15*M || field("seen") != mask
                    || field("invalid") != 0 || field("invalidWall") != 0)
                throw new AssertionError("Split stage conservation/masks");
            IOSFrameAttribution.retain(0, B, 3, 13, -1, -1, 0);
        } else if (mode.equals("stage-context")) {
            expectStage(-1);
            IOSFrameAttribution.stage(IOSFrameAttribution.SHELL_PREFIX);
            IOSFrameAttribution.beginAt(B, 0, 1); expectStage(IOSFrameAttribution.UNMARKED);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.SHELL_PREFIX, B+M, M/2, 1);
            expectStage(IOSFrameAttribution.SHELL_PREFIX);
            IOSFrameAttribution.discard(); expectStage(-1);
            IOSFrameAttribution.stage(IOSFrameAttribution.KEYBOARD_FIT);
            IOSFrameAttribution.beginAt(B+10*M, M, 1); expectStage(IOSFrameAttribution.UNMARKED);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.KEYBOARD_FIT, B+11*M, 3*M/2, 1);
            expectStage(IOSFrameAttribution.KEYBOARD_FIT);
            IOSFrameAttribution.suspend(); expectStage(-1);
            IOSFrameAttribution.stage(IOSFrameAttribution.SHELL_TAIL);
            IOSFrameAttribution.beginAt(B+20*M, 2*M, 1); expectStage(IOSFrameAttribution.UNMARKED);
            IOSFrameAttribution.endAt(B+21*M, 5*M/2, 1); expectStage(-1);
            IOSFrameAttribution.discard(); expectStage(-1);
            IOSFrameAttribution.beginAt(B+30*M, 3*M, 1);
            IOSFrameAttribution.freeze(); expectStage(-1);
            if (stubCalls() != 0 || field("nativeCalls") != 0 || field("clockReads") != 0)
                throw new AssertionError("Stage context getter read a native clock");
        } else if (mode.equals("pair")) {
            IOSFrameAttribution.beginAt(B, 100, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.FRONT_KEYBOARD, B+M, 500_100, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.FONT, B+3*M, 1_000_100, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.FRONT_UPDATE, B+5*M, 2_000_100, 1);
            IOSFrameAttribution.endAt(B+30*M, 10_000_100, 1);
            IOSFrameAttribution.retain(0, B, 3, 13, -1, -1, 0);
            frame(1, B+40*M, 15_000_100, 2*M, M, 12, 40*M);
            frame(2, B+80*M, 20_000_100, 2*M, M, 0, 40*M);
            frame(3, B+100*M, 25_000_100, 30*M, M, 0, 20*M);
        } else if (mode.equals("resume")) {
            frame(0, B, 10*M, 30*M, M, 13, -1);
            IOSFrameAttribution.suspend();
            frame(1, B+1000*M, 50*M, 30*M, M, 17, -1);
            frame(2, B+1050*M, 53*M, M, M, 0, 50*M);
        } else if (mode.equals("discard")) {
            IOSFrameAttribution.beginAt(B-10*M, 10, 1);
            IOSFrameAttribution.endAt(B-M, 1000, 1);
            IOSFrameAttribution.discard();
            frame(0, B, 10000, 30*M, M, 13, -1);
        } else if (mode.equals("failures")) {
            frame(0, B, -1, 30*M, 0, 13, -1);
            frame(1, B+40*M, 10*M, 30*M, -M, 0, 40*M);
            frame(2, B+80*M, 20*M, 30*M, 40*M, 0, 40*M);
            IOSFrameAttribution.resolutionAt(-1);
            frame(3, B+120*M, 70*M, 30*M, M, 0, 40*M);
        } else if (mode.equals("overflow")) {
            for (int i=0; i<260; i++) frame(i, B+i*40*M, i*2*M, 30*M, M, i==0?13:0, i==0?-1:40*M);
        } else if (mode.equals("stage-errors")) {
            IOSFrameAttribution.beginAt(B, 0, 1);
            IOSFrameAttribution.stageAt(99, B+M, M, 1);
            IOSFrameAttribution.stageAt(IOSFrameAttribution.FONT, B-M, 2*M, 1);
            IOSFrameAttribution.endAt(B+30*M, 3*M, 1);
            IOSFrameAttribution.retain(0, B, 3, 13, -1, -1, 0);
        } else throw new AssertionError(mode);
        IOSFrameAttribution.freeze();
        expectStage(-1);
        String before = IOSFrameAttribution.snapshotJson();
        IOSFrameAttribution.begin(B); IOSFrameAttribution.stage(999); IOSFrameAttribution.end(B+M);
        IOSFrameAttribution.retain(999, B, 3, 0, M, 0, 0); IOSFrameAttribution.suspend();
        if (!before.equals(IOSFrameAttribution.snapshotJson())) throw new AssertionError("Changed after freeze");
        if (!mode.startsWith("native-") && stubCalls() != 0) throw new AssertionError("Seam/frozen called native getter");
        if (mode.startsWith("native-") && stubCalls() != 4) throw new AssertionError("Frozen native getter");
        System.out.println(before);
    }
}
