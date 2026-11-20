package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.tonikelope.coronapoker.core.game.GameCancellationException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** App-level CPU admission. Waiting for the protocol/clock never occupies these two workers. */
public final class IOSCompute {
    public static final int LIMIT = 2;
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger ACTIVE = new AtomicInteger(), MAX_ACTIVE = new AtomicInteger();
    private static final AtomicInteger QOS_FAILURES = new AtomicInteger(), QOS = new AtomicInteger(-1);
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static volatile boolean nativeQueueUsed;
    private static final ThreadPoolExecutor CPU = new ThreadPoolExecutor(LIMIT, LIMIT, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(() -> {
                    WORKER.set(true);
                    try { task.run(); } finally { WORKER.remove(); }
                }, "coronapoker-ios-compute-" + SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public static void applyUtilityPolicy() {
        // RoboVM-created orchestration threads use legacy pthread priorities,
        // which opt out of pthread QoS. Pure CPU work uses Foundation below.
        if (onIOS()) Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
    }
    private static boolean onIOS() {
        return Gdx.app != null && Gdx.app.getType() == Application.ApplicationType.iOS;
    }
    public static <T> T call(Callable<T> action) {
        if (WORKER.get()) return invoke(action); // Nested proof/curve calls cannot queue behind themselves.
        Future<T> future = submit(action);
        try { return future.get(); }
        catch (InterruptedException interrupted) {
            future.cancel(true);
            purge();
            Thread.currentThread().interrupt();
            throw new GameCancellationException(interrupted);
        } catch (ExecutionException failed) { return rethrow(failed.getCause()); }
    }
    public static <T> Future<T> submit(Callable<T> action) {
        Callable<T> admitted = () -> {
            int active = ACTIVE.incrementAndGet();
            MAX_ACTIVE.accumulateAndGet(active, Math::max);
            try { return invoke(action); }
            finally { ACTIVE.decrementAndGet(); COMPLETED.incrementAndGet(); }
        };
        if (onIOS()) {
            nativeQueueUsed = true;
            return IOSUtilityCompute.submit(admitted);
        }
        return CPU.submit(admitted);
    }
    public static <T> CompletableFuture<T> future(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> owner = submit(() -> {
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            return null;
        });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) { owner.cancel(true); purge(); }
        });
        return result;
    }
    private static <T> T invoke(Callable<T> action) {
        try { return action.call(); }
        catch (Throwable failure) { return rethrow(failure); }
    }
    private static <T> T rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new CompletionException(failure);
    }
    public static int maxActive() { return MAX_ACTIVE.get(); }
    public static int active() { return ACTIVE.get(); }
    public static int queued() { return nativeQueueUsed ? IOSUtilityCompute.queued() : CPU.getQueue().size(); }
    public static long completed() { return COMPLETED.get(); }
    public static int workerQoS() { return QOS.get(); }
    public static int qosFailures() { return QOS_FAILURES.get(); }
    private static void purge() { if (!nativeQueueUsed) CPU.purge(); }
    static void beginNativeJob() {
        WORKER.set(true);
        int qos = IOSPlatform.currentThreadQoS();
        QOS.set(qos);
        if (qos != 17) QOS_FAILURES.incrementAndGet();
    }
    static void endNativeJob() { WORKER.remove(); }
    private IOSCompute() {}
}
