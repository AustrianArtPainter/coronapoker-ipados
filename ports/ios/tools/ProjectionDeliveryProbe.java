package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.BackgroundProjection;
import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.robovm.rt.GC;

/** Production projection plus sampler, with controlled contention and counter changes. */
public final class ProjectionDeliveryProbe {
    private static int checks;
    private static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
    private static void drain(ArrayDeque<Runnable> work) {
        while (!work.isEmpty()) work.remove().run();
    }
    private static void waitNanos(long duration) {
        long end = System.nanoTime() + duration, remaining;
        while ((remaining = end - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
    }
    private static final class FaultTrace implements BackgroundProjection.DeliveryTrace<Integer> {
        final int failAt;
        int calls, failures;
        FaultTrace(int failAt) { this.failAt = failAt; }
        private void visit() { if (++calls == failAt) throw new IllegalStateException("observer fault"); }
        @Override public boolean enabled() { visit(); return true; }
        @Override public long nanoTime() { visit(); return calls * 100L; }
        @Override public long gcCount() { visit(); return 0; }
        @Override public void record(Integer event, long a, long b, long c, long d,
                long e, long f, long g, long h, long i, int status) { visit(); }
        @Override public void failed() { failures++; throw new IllegalStateException("failure sink fault"); }
    }
    public static void main(String[] args) throws Exception {
        check(IOSFrameEvidence.ENABLED, "Scene evidence enabled");
        var sink = IOSFrameEvidence.<Integer>projectionTrace(Integer::longValue);
        check(!sink.enabled(), "No pre-arm clock/counter recording");
        sink.record(-1, 1, 2, 3, 4, 5, 0, 0, 0, 0, 0);
        long origin = System.nanoTime();
        long started = IOSFrameEvidence.beginAt(origin, 1000, 0);
        IOSFrameEvidence.scene(1, 1);
        IOSFrameEvidence.endAt(started, started + 1_000_000L, 1100, 0);
        check(sink.enabled(), "Armed trace enabled");
        ArrayDeque<Runnable> foreground = new ArrayDeque<>(), completions = new ArrayDeque<>();
        AtomicInteger state = new AtomicInteger(), presentations = new AtomicInteger();
        CountDownLatch holderReady = new CountDownLatch(1), lockRequested = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        AtomicReference<long[]> firstTiming = new AtomicReference<>();
        var timed = new BackgroundProjection.DeliveryTrace<Integer>() {
            int clocks;
            @Override public boolean enabled() { return sink.enabled(); }
            @Override public long nanoTime() {
                long time = sink.nanoTime();
                if (++clocks == 2) lockRequested.countDown();
                return time;
            }
            @Override public long gcCount() { return sink.gcCount(); }
            @Override public void failed() { sink.failed(); }
            @Override public void record(Integer event, long a, long b, long c, long d,
                    long e, long f, long g, long h, long i, int status) {
                sink.record(event, a, b, c, d, e, f, g, h, i, status);
                if (event == 1) firstTiming.set(new long[]{a, b, c, d, e, f, g, h, i});
            }
        };
        var projection = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> {
                    presentations.incrementAndGet();
                    GC.collections.incrementAndGet();
                    waitNanos(10_000_000L);
                    visual.complete(null);
                }, foreground::add, completions::add, false, timed);
        CompletableFuture<Void> normal = projection.publish(1);
        Thread holder = new Thread(() -> {
            try {
                synchronized (projection) {
                    holderReady.countDown();
                    if (!lockRequested.await(5, TimeUnit.SECONDS)) throw new AssertionError("No foreground lock request");
                    waitNanos(20_000_000L);
                    GC.collections.incrementAndGet();
                }
            } catch (Throwable failure) { holderFailure.set(failure); }
        }, "projection-controlled-lock-holder");
        holder.start();
        check(holderReady.await(5, TimeUnit.SECONDS), "Controlled holder acquired actual projection monitor");
        foreground.remove().run();
        holder.join(5000);
        check(!holder.isAlive() && holderFailure.get() == null, "Controlled lock holder finished");
        long[] timing = firstTiming.get();
        check(timing != null && timing[2] - timing[1] >= 10_000_000L, "Controlled projection lock wait recorded");
        check(timing[4] - timing[3] >= 5_000_000L, "Synchronous present work recorded");
        check(timing[5] == 0 && timing[6] == 1 && timing[7] == 1 && timing[8] == 2,
                "Process GC observation boundaries");
        check(!normal.isDone(), "Core completion remains on supplied executor");
        drain(completions);
        check(normal.isDone() && !normal.isCompletedExceptionally(), "Normal delivery completes exactly as before");

        AtomicReference<CompletableFuture<Void>> barrier = new AtomicReference<>();
        var unfinished = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> { presentations.incrementAndGet(); barrier.set(visual); },
                foreground::add, completions::add, false, sink);
        var waiting = unfinished.publish(2);
        drain(foreground);
        check(!waiting.isDone() && completions.isEmpty(), "Trace ends at present return, not unfinished animation barrier");
        barrier.get().complete(null); drain(completions);
        check(waiting.isDone(), "Asynchronous visual completion preserved");

        var failing = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> { presentations.incrementAndGet(); throw new IllegalArgumentException("present fault"); },
                foreground::add, completions::add, false, sink);
        var failed = failing.publish(3);
        drain(foreground); drain(completions);
        check(failed.isCompletedExceptionally(), "Present failure preserves exceptional future");

        var stale = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> { throw new AssertionError("Stale event presented"); },
                foreground::add, completions::add, false, sink);
        var old = stale.publish(4);
        stale.suspend();
        AtomicInteger restored = new AtomicInteger();
        stale.resume(restored::set);
        drain(completions); drain(foreground);
        check(old.isDone() && restored.get() == state.get(), "Resume restores snapshot and rejects stale generation");
        stale.suspend();
        int queued = foreground.size();
        var background = stale.publish(40);
        check(foreground.size() == queued, "Suspended publish never queues presentation or trace");
        drain(completions);
        check(background.isDone(), "Background protocol delivery completion preserved");

        var closed = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> { throw new AssertionError("Closed event presented"); },
                foreground::add, completions::add, false, sink);
        var cancelled = closed.publish(5);
        closed.close(); drain(completions); drain(foreground);
        check(cancelled.isCompletedExceptionally(), "Close cancellation preserved");
        var done = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> { throw new AssertionError("Completed event presented"); },
                foreground::add, completions::add, false, sink);
        done.publish(6).complete(null); drain(foreground); done.close(); drain(completions);
        check(presentations.get() == 3, "Only normal, unfinished and throwing present calls executed");

        for (int failAt = 1; failAt <= 11; failAt++) {
            FaultTrace fault = new FaultTrace(failAt);
            AtomicInteger calls = new AtomicInteger();
            var faulted = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                    (event, visual) -> { calls.incrementAndGet(); visual.complete(null); },
                    foreground::add, completions::add, false, fault);
            var result = faulted.publish(7);
            drain(foreground); drain(completions);
            check(calls.get() == 1 && result.isDone() && !result.isCompletedExceptionally(),
                    "Observer fault cannot skip/duplicate present or alter future at boundary " + failAt);
            check(fault.failures == 1 && faulted.pendingCount() == 0,
                    "Observer and failure-sink faults contained at boundary " + failAt);
        }
        int counterCalls = GC.calls.get();
        var ordinary = new BackgroundProjection<Integer, Integer>(state::addAndGet, state::get,
                (event, visual) -> visual.complete(null), foreground::add, completions::add, false);
        ordinary.publish(8); drain(foreground); drain(completions);
        check(GC.calls.get() == counterCalls, "Original constructor performs no diagnostic GC access");

        long now = System.nanoTime();
        for (int i = 0; i < 8192; i++) sink.record(10000 + i, now + i * 5L,
                now + i * 5L + 1, now + i * 5L + 2, now + i * 5L + 3,
                now + i * 5L + 4, 2, 2, 2, 2, 0);
        long stop = origin + 300_000_000_000L;
        IOSFrameEvidence.beginAt(stop, 2000, 2); IOSFrameEvidence.scene(1, 9000);
        IOSFrameEvidence.endAt(stop, stop + 1_000_000L, 2100, 2);
        check(!sink.enabled(), "Trace disabled immediately when sampler finishes");
        for (int i = 0; i < 100; i++) sink.record(-2, now, now, now, now, now, 2, 2, 2, 2, 0);
        check(IOSDiagnostics.ready.await(5, TimeUnit.SECONDS), "Single final writer completed");
        check(IOSDiagnostics.reports.get() == 1, "No live or repeated writes");
        System.out.println(IOSDiagnostics.text);
        System.err.println("PASS " + checks + " production delivery timing, lock wait, lifecycle, observer fault and freeze checks");
    }
}
