package com.tonikelope.coronapoker.ios;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Ordered presentation projection. It never grants poker or protocol decisions. */
public final class BackgroundProjection<E, S> {
    /** Optional foreground-only timing sink; never installed by ordinary playback. */
    public interface DeliveryTrace<E> {
        boolean enabled();
        long nanoTime();
        long gcCount();
        void record(E event, long started, long lockRequested, long lockAcquired,
                long presentStarted, long ended, long gcStart, long gcLocked,
                long gcPresent, long gcEnd, int status);
        default void failed() { }
    }
    private final Consumer<E> apply;
    private final Supplier<S> copy;
    private final BiConsumer<E, CompletableFuture<Void>> present;
    private final Executor foreground;
    private final Executor completion;
    private final DeliveryTrace<E> deliveryTrace;
    private final Set<Pending> pending = new LinkedHashSet<>();
    private boolean suspended;
    private boolean closed;
    private long generation;

    public BackgroundProjection(Consumer<E> apply, Supplier<S> copy,
            BiConsumer<E, CompletableFuture<Void>> present, Executor foreground,
            Executor completion, boolean suspended) {
        this(apply, copy, present, foreground, completion, suspended, null);
    }

    public BackgroundProjection(Consumer<E> apply, Supplier<S> copy,
            BiConsumer<E, CompletableFuture<Void>> present, Executor foreground,
            Executor completion, boolean suspended, DeliveryTrace<E> deliveryTrace) {
        this.apply = apply; this.copy = copy; this.present = present;
        this.foreground = foreground; this.completion = completion;
        this.suspended = suspended;
        this.deliveryTrace = deliveryTrace;
    }

    public CompletableFuture<Void> publish(E event) {
        Pending delivery;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Presentation projection is closed");
            apply.accept(event);
            delivery = new Pending(event, generation);
            if (!suspended) pending.add(delivery);
            if (suspended) finish(delivery, null);
            else foreground.execute(() -> deliver(delivery));
        }
        return delivery.result;
    }

    private void deliver(Pending delivery) {
        DeliveryTrace<E> trace = deliveryTrace;
        if (trace != null) {
            boolean enabled = false;
            try { enabled = trace.enabled(); }
            catch (Throwable ignored) { traceFailed(trace); }
            if (enabled) { deliverMeasured(delivery, trace); return; }
        }
        deliverUnmeasured(delivery);
    }

    private void deliverUnmeasured(Pending delivery) {
        synchronized (this) {
            if (closed || suspended || delivery.generation != generation
                    || delivery.result.isDone()) return;
        }
        CompletableFuture<Void> visual = new CompletableFuture<>();
        visual.whenComplete((ignored, failure) -> finish(delivery, failure));
        try { present.accept(delivery.event, visual); }
        catch (Throwable failure) { visual.completeExceptionally(failure); }
    }

    private void deliverMeasured(Pending delivery, DeliveryTrace<E> trace) {
        long started, gcStart, lockRequested, lockAcquired, gcLocked;
        boolean skipped;
        try {
            started = trace.nanoTime(); gcStart = trace.gcCount();
            lockRequested = trace.nanoTime();
            synchronized (this) {
                lockAcquired = trace.nanoTime(); gcLocked = trace.gcCount();
                skipped = closed || suspended || delivery.generation != generation
                        || delivery.result.isDone();
            }
        } catch (Throwable ignored) {
            traceFailed(trace);
            deliverUnmeasured(delivery);
            return;
        }
        if (skipped) {
            recordDelivery(trace, delivery, started, lockRequested, lockAcquired,
                    -1, gcStart, gcLocked, -1, 1);
            return;
        }
        CompletableFuture<Void> visual = new CompletableFuture<>();
        visual.whenComplete((ignored, failure) -> finish(delivery, failure));
        long gcPresent = -1, presentStarted = -1;
        boolean measured = true;
        try { gcPresent = trace.gcCount(); presentStarted = trace.nanoTime(); }
        catch (Throwable ignored) { traceFailed(trace); measured = false; }
        int status = 0;
        try { present.accept(delivery.event, visual); }
        catch (Throwable failure) { status = 2; visual.completeExceptionally(failure); }
        finally {
            if (measured) recordDelivery(trace, delivery, started, lockRequested, lockAcquired,
                    presentStarted, gcStart, gcLocked, gcPresent, status);
        }
    }

    private void recordDelivery(DeliveryTrace<E> trace, Pending delivery, long started,
            long lockRequested, long lockAcquired, long presentStarted,
            long gcStart, long gcLocked, long gcPresent, int status) {
        try {
            long gcEnd = trace.gcCount(), ended = trace.nanoTime();
            trace.record(delivery.event, started, lockRequested, lockAcquired,
                    presentStarted, ended, gcStart, gcLocked, gcPresent, gcEnd, status);
        } catch (Throwable ignored) { traceFailed(trace); }
    }
    private static void traceFailed(DeliveryTrace<?> trace) {
        // A diagnostic observer must never change presentation/completion semantics.
        try { trace.failed(); } catch (Throwable ignored) { }
    }

    private void finish(Pending delivery, Throwable failure) {
        // Core callbacks can stop/join network workers. Keep them off UIKit/GL.
        completion.execute(() -> {
            synchronized (BackgroundProjection.this) { pending.remove(delivery); }
            if (failure == null) delivery.result.complete(null);
            else delivery.result.completeExceptionally(failure);
        });
    }

    /** Called on the foreground thread before rendering is suspended. */
    public void suspend() {
        ArrayList<Pending> release;
        synchronized (this) {
            if (closed || suspended) return;
            suspended = true; generation++;
            release = new ArrayList<>(pending); pending.clear();
        }
        for (Pending delivery : release) finish(delivery, null);
    }

    /** Called with a valid GL context before accepting new foreground events. */
    public synchronized void resume(Consumer<S> restore) {
        if (closed || !suspended) return;
        restore.accept(copy.get());
        generation++; suspended = false;
    }

    public synchronized S snapshot() { return copy.get(); }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized boolean isSuspended() { return suspended; }
    public void close() {
        ArrayList<Pending> release;
        synchronized (this) {
            closed = true; generation++;
            release = new ArrayList<>(pending); pending.clear();
        }
        for (Pending delivery : release)
            finish(delivery, new java.util.concurrent.CancellationException("Presentation closed"));
    }
    private final class Pending {
        final E event;
        final long generation;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        Pending(E event, long generation) { this.event = event; this.generation = generation; }
    }
}
