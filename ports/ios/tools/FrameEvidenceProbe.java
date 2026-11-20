package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import java.util.concurrent.TimeUnit;
import org.robovm.rt.GC;

/** Deterministic foreground timeline checks against the production sampler. */
public final class FrameEvidenceProbe {
    private static final long ORIGIN = 1_000_000_000L, MS = 1_000_000L, SECOND = 1_000_000_000L;
    private static int checks;
    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
    private static void frame(long offset, int phase, long sequence, long bytes, long collections) {
        long started = IOSFrameEvidence.beginAt(ORIGIN + offset, bytes, collections);
        IOSFrameEvidence.scene(phase, sequence);
        IOSFrameEvidence.allocationScope("alloc.applicationFrame", 37);
        IOSFrameEvidence.endAt(started, started + 2 * MS, bytes + 120, collections);
    }
    private static void awaitReport() throws Exception {
        check(IOSDiagnostics.ready.await(5, TimeUnit.SECONDS), "Final writer completed");
        check(IOSDiagnostics.reports.get() == 1, "Exactly one report");
        check("coronapoker-ios-scene-report".equals(IOSDiagnostics.writerThread), "Write occurs on dedicated final writer");
        Thread late = new Thread(() -> {
            if (IOSFrameEvidence.projectionTrace(null) != null)
                throw new AssertionError("Finished sampler accepted late projection sink");
        }, "late-table-factory");
        java.util.concurrent.atomic.AtomicReference<Throwable> lateFailure = new java.util.concurrent.atomic.AtomicReference<>();
        late.setUncaughtExceptionHandler((thread, failure) -> lateFailure.set(failure));
        late.start(); late.join(5000);
        check(!late.isAlive() && lateFailure.get() == null, "Finished sampler rejects sink installation from another thread");
        IOSFrameEvidence.endAt(ORIGIN, ORIGIN + 400 * SECOND, 9000, 20);
        check(IOSFrameEvidence.begin() == 0, "Finished sampler stays closed");
        check(!IOSFrameEvidence.allocationScopesEnabled(), "Scopes stop when samples freeze");
        IOSFrameEvidence.suspend();
        IOSFrameEvidence.scene(3, 123);
        check(IOSDiagnostics.reports.get() == 1, "Repeated finish/lifecycle calls do not write again");
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        check(IOSFrameEvidence.durationSeconds(null) == 0, "Unset duration keeps legacy mode");
        check(IOSFrameEvidence.durationSeconds("10") == 10, "Minimum duration accepted");
        check(IOSFrameEvidence.durationSeconds("300") == 300, "Five-minute duration accepted");
        check(IOSFrameEvidence.durationSeconds("900") == 900, "Maximum duration accepted");
        for (String invalid : new String[]{"", "9", "901", "3600", "0", "-1", "0.5", "not-a-number", "999999999999"}) {
            boolean rejected = false;
            try { IOSFrameEvidence.durationSeconds(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid duration rejected: " + invalid);
        }
        if ("disabled".equals(mode)) {
            check(!IOSFrameEvidence.ENABLED, "Disabled environment honored");
            check(IOSFrameEvidence.begin() == 0, "Disabled begin returns immediately");
            IOSFrameEvidence.end(1); IOSFrameEvidence.scene(-1, 1); IOSFrameEvidence.suspend();
            IOSFrameEvidence.allocationScope("alloc.applicationFrame", 50);
            check(IOSFrameEvidence.projectionTrace(null) == null, "Disabled projection trace allocates no observer");
            IOSFrameEvidence.deliveryAt(1, 1, 2, 3, 4, 5, 0, 0, 0, 0, 0);
            check(GC.calls.get() == 0, "Disabled sampler performs no native counter access");
            check(IOSDiagnostics.reports.get() == 0, "Disabled sampler writes nothing");
            System.out.println("{\"disabledChecks\":" + checks + "}");
            return;
        }
        check(IOSFrameEvidence.ENABLED, "Enabled environment honored");
        if (!"legacy".equals(mode)) for (String invalid : new String[]{"", "startup", "frontend", "0", "Menu"}) {
            boolean rejected = false;
            try { IOSFrameEvidence.startPhase(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid deferred phase rejected: " + invalid);
        }
        if ("deferred-resume".equals(mode)) {
            frame(0, 3, 1, 1000, 5);
            frame(4 * SECOND, 3, 2, 1200, 5);
            IOSFrameEvidence.suspend();
            frame(5 * SECOND, 3, 3, 1400, 6);
            frame(9 * SECOND, 3, 4, 1600, 6);
            frame(10 * SECOND, 3, 5, 1800, 7);
            frame(10 * SECOND + 16 * MS, 3, 6, 2000, 7);
            frame(310 * SECOND, 3, 7, 2200, 8);
        } else if (mode.startsWith("deferred-")) {
            int target = switch (mode) {
                case "deferred-menu" -> 3;
                case "deferred-table" -> 1;
                case "deferred-summary" -> 2;
                default -> throw new IllegalArgumentException(mode);
            };
            frame(0, 4, -1, 1000, 5);
            frame(16 * MS, target, -1, 1200, 5);
            frame(SECOND, 5, -1, 1400, 5); // A modal resets readiness.
            frame(2 * SECOND, target, 1, 1600, 6);
            frame(6 * SECOND, target, 2, 1800, 7);
            check(IOSDiagnostics.reports.get() == 0, "Unstable pages cannot consume a capture window");
            frame(7 * SECOND, target, 3, 2000, 8);
            frame(7 * SECOND + 16 * MS, target, 4, 2200, 8);
            frame(307 * SECOND, target, 5, 2400, 9);
        } else if ("menu".equals(mode)) {
            frame(0, 3, 7, 1000, 5);
            frame(16 * MS, 3, 8, 1200, 5);
            frame(6 * SECOND, 3, 9, 1600, 6);
            check(IOSDiagnostics.reports.get() == 0, "Menu waits for duration");
            frame(300 * SECOND, 3, 10, 2100, 7);
        } else if ("flow".equals(mode)) {
            frame(0, 4, -1, 1000, 5);
            frame(16 * MS, 3, -1, 1200, 5);
            frame(6 * SECOND, 3, -1, 1600, 6);
            IOSFrameEvidence.suspend(); IOSFrameEvidence.suspend();
            frame(20 * SECOND, 3, -1, 3000, 8);
            frame(20 * SECOND + 16 * MS, 3, -1, 3200, 8);
            frame(26 * SECOND, 1, 101, 3600, 9);
            frame(27 * SECOND, 2, 102, 3800, 9);
            frame(93 * SECOND, 2, 103, 4000, 10);
            check(IOSDiagnostics.reports.get() == 0, "Timed mode does not stop after legacy summary window");
            frame(300 * SECOND, 2, 104, 4300, 11);
        } else if ("legacy".equals(mode)) {
            frame(0, 4, -1, 1000, 5);
            frame(SECOND, 3, -1, 1200, 5);
            frame(2 * SECOND, 1, 101, 1600, 6);
            frame(8 * SECOND, 1, 102, 1800, 6);
            frame(9 * SECOND, 2, 103, 2000, 7);
            frame(14 * SECOND, 2, 104, 2200, 7);
            frame(14 * SECOND + 100 * MS, 2, 105, 2400, 7);
            check(IOSDiagnostics.reports.get() == 0, "Legacy mode waits for full summary window");
            frame(74 * SECOND, 2, 106, 2600, 8);
        } else throw new IllegalArgumentException(mode);
        awaitReport();
        check(IOSDiagnostics.text != null, "Report contains output");
        System.out.println(IOSDiagnostics.text);
        System.err.println("PASS " + checks + " " + mode + " sampler lifecycle checks");
    }
}
