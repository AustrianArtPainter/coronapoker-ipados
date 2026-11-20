package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSCompute;
import com.tonikelope.coronapoker.ios.PortableGif;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Three CPU pixel buffers per clip, exclusively during pre-START preparation. */
final class GifFramePreparation {
    static final int BUFFERS = 3;
    static final AtomicLong LIVE_BYTES = new AtomicLong(), PEAK_BYTES = new AtomicLong();
    final int width, height;
    final long[] ends;
    private final ArrayBlockingQueue<byte[]> free = new ArrayBlockingQueue<>(BUFFERS);
    private final ArrayBlockingQueue<PortableGif.Pixels> decoded = new ArrayBlockingQueue<>(BUFFERS);
    private final long reservedBytes;
    private volatile boolean finished, cancelled;
    private volatile Throwable failure;
    private volatile Future<?> worker;
    private volatile boolean released;

    private GifFramePreparation(PortableGif.Info info, int maxWidth, long budget) throws IOException {
        float scale = Math.min(1f, maxWidth / (float)info.width());
        width = Math.max(1, Math.round(info.width()*scale));
        height = Math.max(1, Math.round(info.height()*scale));
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if ((long)bytes * info.frames().size() > budget) throw new BudgetExceeded();
        ends = new long[info.frames().size()];
        long time = 0;
        for (int i=0;i<ends.length;i++) ends[i] = time = Math.addExact(time, info.frames().get(i).delayMillis());
        for (int i=0;i<BUFFERS;i++) free.add(new byte[bytes]);
        reservedBytes = (long)bytes * BUFFERS;
        PEAK_BYTES.accumulateAndGet(LIVE_BYTES.addAndGet(reservedBytes), Math::max);
    }

    static CompletableFuture<PreloadedShuffleAnimation> start(Callable<byte[]> input, int width, long budget) {
        CompletableFuture<PreloadedShuffleAnimation> metadata = new CompletableFuture<>();
        Future<?> job = IOSCompute.submit(() -> {
            GifFramePreparation source = null;
            try {
                byte[] data = input.call();
                source = new GifFramePreparation(PortableGif.inspect(data), width, budget);
                PreloadedShuffleAnimation animation = new PreloadedShuffleAnimation(source);
                if (!metadata.complete(animation)) { source.cancelled = true; return null; }
                GifFramePreparation producer = source;
                PortableGif.decode(data, width, producer::publish,
                        () -> producer.cancelled || Thread.currentThread().isInterrupted(), producer::acquire);
            } catch (Throwable error) {
                if (source != null) source.failure = error;
                metadata.completeExceptionally(error);
            } finally {
                if (source != null) {
                    source.finished = true;
                    if (source.cancelled || source.failure != null) source.releasePixels();
                }
            }
            return null;
        });
        metadata.whenComplete((animation, error) -> {
            if (animation != null) animation.preparationWorker(job);
            if (metadata.isCancelled()) job.cancel(true);
        });
        return metadata;
    }
    static void cancel(CompletableFuture<PreloadedShuffleAnimation> preparation) {
        if (preparation == null) return;
        preparation.whenComplete((animation, failure) -> { if (animation != null) animation.dispose(); });
        preparation.cancel(true);
    }

    private byte[] acquire(int bytes) {
        try {
            if (cancelled) throw new CancellationException("GIF preparation cancelled");
            byte[] buffer = free.take();
            if (buffer.length != bytes) throw new IllegalStateException("GIF buffer geometry changed");
            return buffer;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new CancellationException("GIF preparation interrupted");
        }
    }
    private void publish(PortableGif.Pixels frame) {
        try {
            if (cancelled) throw new CancellationException("GIF preparation cancelled");
            decoded.put(frame);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new CancellationException("GIF preparation interrupted");
        }
    }
    PortableGif.Pixels poll() {
        checkFailure(); return decoded.poll();
    }
    void recycle(byte[] pixels) { if (!cancelled && !released) free.add(pixels); }
    boolean finished() { checkFailure(); return finished; }
    void setWorker(Future<?> job) { worker = job; if (cancelled) job.cancel(true); }
    void cancel() { cancelled = true; Future<?> job = worker; if (job != null) job.cancel(true); if (finished) releasePixels(); }
    synchronized void releasePixels() {
        if (released) return;
        released = true; free.clear(); decoded.clear(); LIVE_BYTES.addAndGet(-reservedBytes);
    }
    private void checkFailure() {
        if (failure != null) throw new CompletionException(failure);
        if (cancelled) throw new CancellationException("GIF preparation cancelled");
    }
    static final class BudgetExceeded extends IOException {
        BudgetExceeded() { super("GIF exceeds prepared GPU image budget"); }
    }
}
