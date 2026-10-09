package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.tonikelope.coronapoker.ios.PortableGif;
import java.io.IOException;
import java.util.ArrayList;

/** Exact random-access small-GIF behavior without desktop ImageIO or AWT. */
final class GifTextureAnimation implements Disposable {
    private final Texture[] frames;
    private final long[] frameEndMillis;
    private final long duration;
    private final int width, height;
    private GifTextureAnimation(byte[] data, int maxWidth) throws IOException {
        var info = PortableGif.inspect(data);
        float scale = Math.min(1f, maxWidth / (float) info.width());
        width = Math.max(1, Math.round(info.width() * scale));
        height = Math.max(1, Math.round(info.height() * scale));
        frameEndMillis = new long[info.frames().size()];
        long end = 0;
        for (int i=0; i<frameEndMillis.length; i++) { end += info.frames().get(i).delayMillis(); frameEndMillis[i] = end; }
        duration = end;
        ArrayList<Texture> textures = new ArrayList<>();
        try {
            PortableGif.decode(data, maxWidth, frame -> {
                Pixmap image = new Pixmap(frame.width(), frame.height(), Pixmap.Format.RGBA8888);
                try {
                    image.getPixels().clear(); image.getPixels().put(frame.rgba()); image.getPixels().flip();
                    Texture texture = new Texture(image, false);
                    texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                    textures.add(texture);
                } finally { image.dispose(); }
            }, () -> false);
            frames = textures.toArray(new Texture[0]);
            if (frames.length != frameEndMillis.length) throw new IOException("GIF frame count changed while decoding");
        } catch (Throwable failure) {
            for (Texture texture : textures) texture.dispose();
            if (failure instanceof IOException error) throw error;
            throw failure;
        }
    }
    static GifTextureAnimation load(String path, int maxWidth) throws IOException {
        return load(Gdx.files.internal(path).readBytes(), path, maxWidth);
    }
    static GifTextureAnimation load(byte[] data, String label, int maxWidth) throws IOException {
        return new GifTextureAnimation(data, maxWidth);
    }
    Texture frameAt(float elapsedSeconds, boolean loop) {
        long time = Math.max(0, Math.round(elapsedSeconds * 1000));
        time = loop && duration > 0 ? time % duration : Math.min(time, duration - 1);
        for (int i=0; i<frames.length; i++) if (time < frameEndMillis[i]) return frames[i];
        return frames[frames.length-1];
    }
    float durationSeconds() { return duration / 1000f; }
    float frameStartSeconds(int oneBasedFrame) {
        return oneBasedFrame <= 1 ? 0 : frameEndMillis[Math.min(oneBasedFrame-2, frames.length-1)] / 1000f;
    }
    int width() { return width; }
    int height() { return height; }
    int frameCount() { return frames.length; }
    @Override public void dispose() { for (Texture texture : frames) texture.dispose(); }
}
