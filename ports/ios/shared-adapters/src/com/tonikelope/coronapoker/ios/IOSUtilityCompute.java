package com.tonikelope.coronapoker.ios;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.robovm.apple.foundation.NSOperationQueue;
import org.robovm.apple.foundation.NSQualityOfService;

/** Foundation owns thread creation, so Utility QoS is set before any Java callback. */
final class IOSUtilityCompute {
    private static final NSOperationQueue QUEUE = createQueue();
    private static final Semaphore ADMISSION = new Semaphore(IOSCompute.LIMIT + 128);
    private static final AtomicInteger QUEUED = new AtomicInteger();
    private static NSOperationQueue createQueue() {
        NSOperationQueue queue = new NSOperationQueue();
        queue.setName("coronapoker-ios-compute");
        queue.setMaxConcurrentOperationCount(IOSCompute.LIMIT);
        queue.setQualityOfService(NSQualityOfService.Utility);
        return queue;
    }
    static <T> Future<T> submit(Callable<T> action) {
        if (!ADMISSION.tryAcquire()) throw new RejectedExecutionException("iOS compute queue is full");
        FutureTask<T> future = new FutureTask<>(action);
        QUEUED.incrementAndGet();
        try {
            // The queue retains this block through execution. It also owns its
            // autorelease pool and releases the block after the operation ends.
            QUEUE.addOperation(() -> {
                QUEUED.decrementAndGet();
                Thread.interrupted();
                try {
                    IOSCompute.beginNativeJob();
                    future.run();
                } finally {
                    IOSCompute.endNativeJob();
                    Thread.interrupted();
                    ADMISSION.release();
                }
            });
        } catch (Throwable failure) {
            QUEUED.decrementAndGet();
            ADMISSION.release();
            future.cancel(false);
            throw failure;
        }
        return future;
    }
    static int queued() { return QUEUED.get(); }
    private IOSUtilityCompute() {}
}
