package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;
import com.tonikelope.coronapoker.ios.IOSPerformance;
import com.tonikelope.coronapoker.ios.PortableGif;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** One table-owned shuffle sequence. Decoding/uploading is preparation only. */
final class PreloadedShuffleAnimation implements Disposable {
    private static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;
    private final TextureRegion[] frames;
    private final long[] ends;
    private final int width, height;
    private final long duration;
    private Prepared pending;
    private GifFramePreparation source;
    private int uploaded;
    private boolean disposed;

    PreloadedShuffleAnimation(Prepared prepared) {
        pending = prepared;
        width = prepared.width;
        height = prepared.height;
        ends = prepared.ends;
        duration = ends[ends.length - 1];
        frames = new TextureRegion[ends.length];
    }

    PreloadedShuffleAnimation(GifFramePreparation source) {
        this.source = source;
        width = source.width; height = source.height; ends = source.ends;
        duration = ends[ends.length-1]; frames = new TextureRegion[ends.length];
    }
    void preparationWorker(java.util.concurrent.Future<?> worker) { source.setWorker(worker); }
    long gpuBytes() { return (long)width * height * 4 * frames.length; }

    static Prepared prepareLooping(String resource, int maxWidth) throws IOException {
        return prepare(Gdx.files.internal(resource).readBytes(), maxWidth);
    }
    static Prepared prepareLooping(Path file, int maxWidth) throws IOException {
        return prepare(Files.readAllBytes(file), maxWidth);
    }
    static Prepared prepare(byte[] data, int maxWidth) throws IOException {
        PortableGif.Info info = PortableGif.inspect(data);
        float scale = Math.min(1f, maxWidth / (float) info.width());
        int width = Math.max(1, Math.round(info.width() * scale));
        int height = Math.max(1, Math.round(info.height() * scale));
        long bytes = (long) width * height * 4 * info.frames().size();
        if (bytes > MAX_DECODED_BYTES) throw new IOException("Shuffle exceeds decoded image budget");
        ArrayList<byte[]> pixels = new ArrayList<>(info.frames().size());
        long[] ends = new long[info.frames().size()];
        PortableGif.decode(data, maxWidth, frame -> {
            ends[pixels.size()] = frame.endMillis();
            pixels.add(frame.rgba());
        }, () -> Thread.currentThread().isInterrupted());
        if (pixels.size() != ends.length) throw new IOException("Shuffle preparation cancelled");
        return new Prepared(width, height, ends, pixels.toArray(new byte[0][]));
    }

    /** GL thread, before the dealer START barrier is acknowledged. One frame per call. */
    boolean uploadNext() {
        if (disposed) throw new IllegalStateException("Shuffle was disposed");
        if (ready()) return true;
        if (uploaded == frames.length) return completePreparation();
        PortableGif.Pixels next = source == null ? null : source.poll();
        if (source != null && next == null) return false;
        byte[] pixels = next == null ? pending.pixels[uploaded] : next.rgba();
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0;
        Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        IOSFrameEvidence.preloadPixmapCreates++;
        Texture texture = null;
        try {
            image.getPixels().clear();
            image.getPixels().put(pixels);
            image.getPixels().flip();
            texture = new Texture(new UploadPixels(image));
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            frames[uploaded] = new TextureRegion(texture);
            if (pending != null) pending.pixels[uploaded] = null;
            uploaded++;
        } catch (Throwable failure) {
            if (texture != null) texture.dispose();
            throw failure;
        } finally {
            image.dispose();
            IOSFrameEvidence.preloadPixmapDisposals++;
            if (source != null) source.recycle(pixels);
            if (IOSPerformance.ENABLED) IOSPerformance.record("shuffle.preload.upload", System.nanoTime() - start);
        }
        return completePreparation();
    }

    private boolean completePreparation() {
        if (uploaded != frames.length || source != null && !source.finished()) return false;
        if (source != null) { source.releasePixels(); source = null; }
        pending = null;
        return true;
    }
    boolean ready() { return uploaded == frames.length && source == null; }
    TextureRegion frameAt(float seconds) {
        return frameAt(seconds, true);
    }
    TextureRegion frameAt(float seconds, boolean loop) {
        if (disposed || !ready()) return null;
        long time = Math.max(0L, Math.round(seconds * 1000.0));
        time = loop ? time % duration : Math.min(time, duration - 1);
        int low = 0, high = ends.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (time < ends[middle]) high = middle;
            else low = middle + 1;
        }
        return frames[low];
    }
    float durationSeconds() { return duration / 1000f; }
    long durationMillis() { return duration; }
    float frameStartSeconds(int oneBased) {
        return oneBased <= 1 ? 0 : ends[Math.min(oneBased - 2, ends.length - 1)] / 1000f;
    }
    int width() { return width; }
    int height() { return height; }
    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        pending = null;
        if (source != null) { source.cancel(); source = null; }
        for (TextureRegion frame : frames) if (frame != null) frame.getTexture().dispose();
    }

    /** Unmanaged GPU data must not retain even the disposed Pixmap wrapper. */
    private static final class UploadPixels implements TextureData {
        private Pixmap image;
        private final int width, height;
        UploadPixels(Pixmap image) { this.image = image; width=image.getWidth(); height=image.getHeight(); }
        public TextureDataType getType() { return TextureDataType.Pixmap; }
        public boolean isPrepared() { return image != null; }
        public void prepare() { if(image == null) throw new IllegalStateException("GPU-only image already uploaded"); }
        public Pixmap consumePixmap() { Pixmap pixels=image; image=null; return pixels; }
        public boolean disposePixmap() { return false; } // uploadNext owns its finally-dispose.
        public void consumeCustomData(int target) { throw new UnsupportedOperationException(); }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
        public boolean useMipMaps() { return false; }
        public boolean isManaged() { return false; }
    }
    static final class Prepared {
        final int width, height;
        final long[] ends;
        final byte[][] pixels;
        Prepared(int width, int height, long[] ends, byte[][] pixels) {
            this.width = width; this.height = height; this.ends = ends; this.pixels = pixels;
        }
    }
}
