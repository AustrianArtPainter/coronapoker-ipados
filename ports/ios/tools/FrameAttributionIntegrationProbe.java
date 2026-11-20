package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import com.tonikelope.coronapoker.ios.IOSFrameAttribution;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Drive actual sampler/recorder seams through deferred arm, resume and finish. */
public final class FrameAttributionIntegrationProbe {
    static final long S = 1_000_000_000L, M = 1_000_000L;
    static void call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = IOSFrameAttribution.class.getDeclaredMethod(name, types);
        method.setAccessible(true); method.invoke(null, values);
    }
    static void frame(long time, int phase, long duration, long cpu) throws Exception {
        call("beginAt", new Class<?>[]{long.class,long.class,long.class},time,cpu,1L);
        long start = IOSFrameEvidence.beginAt(time,0,0); IOSFrameEvidence.scene(phase,10);
        call("endAt", new Class<?>[]{long.class,long.class,long.class},time+duration,cpu+M,1L);
        IOSFrameEvidence.endAt(start,time+duration,0,0);
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("public-begin")) {
            System.load(args[1]);
            long before = System.nanoTime();
            long start = IOSFrameEvidence.begin();
            long after = System.nanoTime();
            if (start < before || start > after) throw new AssertionError("Public begin changed raw wall origin");
            if (org.robovm.rt.GC.calls.get() != 2
                    || org.robovm.rt.GC.bytesStage != IOSFrameAttribution.BEGIN_COUNTERS
                    || org.robovm.rt.GC.countStage != IOSFrameAttribution.BEGIN_COUNTERS)
                throw new AssertionError("Public begin counters outside BEGIN_COUNTERS");
            if (IOSFrameAttribution.currentStage() != IOSFrameAttribution.SHELL_PREFIX)
                throw new AssertionError("Public begin did not restore shell prefix");
            IOSFrameEvidence.scene(3, 1);
            IOSFrameEvidence.end(start);
            if (IOSFrameAttribution.currentStage() != -1)
                throw new AssertionError("Deferred unarmed frame retained an open stage");
            IOSFrameAttribution.freeze();
            System.out.println(IOSFrameAttribution.snapshotJson());
            return;
        }
        call("resolutionAt",new Class<?>[]{long.class},1L);
        frame(S,5,M,0);
        frame(2*S,3,M,2*M);
        frame(7*S,3,30*M,4*M); // first retained render after five stable seconds
        frame(7*S+40*M,3,2*M,6*M);
        IOSFrameEvidence.suspend();
        frame(8*S,3,30*M,8*M);
        frame(8*S+40*M,3,2*M,10*M);
        frame(17*S,3,30*M,12*M);
        if (!IOSDiagnostics.ready.await(3,TimeUnit.SECONDS)) throw new AssertionError("No finished report");
        if (IOSDiagnostics.reports.get()!=1) throw new AssertionError("Duplicate report");
        String before=IOSDiagnostics.text;
        IOSFrameEvidence.begin(); IOSFrameAttribution.stage(IOSFrameAttribution.FONT);
        if (!before.equals(IOSDiagnostics.text)) throw new AssertionError("Changed after finish");
        System.out.println(before);
    }
}
