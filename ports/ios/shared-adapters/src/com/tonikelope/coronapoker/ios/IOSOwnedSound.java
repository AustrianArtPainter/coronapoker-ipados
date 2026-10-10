package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.backends.iosrobovm.objectal.ALBuffer;
import com.badlogic.gdx.backends.iosrobovm.objectal.ALSource;
import com.badlogic.gdx.backends.iosrobovm.objectal.OALSimpleAudio;
import com.badlogic.gdx.utils.GdxRuntimeException;
import java.util.HashMap;
import java.util.Map;

/** ObjectAL effects with per-playback ownership instead of reusable source IDs. */
final class IOSOwnedSound implements Sound {
    private static final SoundVoiceRegistry<ALSource> VOICES = new SoundVoiceRegistry<>();
    private static final Map<String, Buffer> BUFFERS = new HashMap<>();
    private final String path;
    private final ALBuffer buffer;
    private final SoundStartGate startGate;
    public static long throttledStarts, failedStarts;
    private boolean disposed;

    IOSOwnedSound(FileHandle file) {
        path = file.file().getPath().replace('\\', '/');
        synchronized (VOICES) {
            Buffer shared = BUFFERS.get(path);
            if (shared == null) {
                ALBuffer nativeBuffer = OALSimpleAudio.sharedInstance().preloadEffect(path);
                if (nativeBuffer == null) throw new GdxRuntimeException("Unable to load sound: " + file);
                shared = new Buffer(nativeBuffer, SoundStartGate.highFrequency(path));
                BUFFERS.put(path, shared);
            }
            shared.owners++;
            buffer = shared.value;
            startGate = shared.gate;
        }
    }

    private long start(float volume, float pitch, float pan, boolean loop) {
        synchronized (VOICES) {
            if (disposed) return -1L;
            if (!loop && startGate != null && !startGate.allow(System.nanoTime())) {
                throttledStarts++; return -1L;
            }
            ALSource source = OALSimpleAudio.sharedInstance().playBuffer(buffer, volume, pitch, pan, loop);
            if (source == null) failedStarts++;
            return source == null ? -1L : VOICES.claim(this, source.getSourceId(), source);
        }
    }
    @Override public long play() { return play(1f); }
    @Override public long play(float volume) { return play(volume, 1f, 0f); }
    @Override public long play(float volume, float pitch, float pan) { return start(volume, pitch, pan, false); }
    @Override public long loop() { return loop(1f); }
    @Override public long loop(float volume) { return loop(volume, 1f, 0f); }
    @Override public long loop(float volume, float pitch, float pan) { return start(volume, pitch, pan, true); }
    @Override public void stop() { VOICES.releaseAll(this, ALSource::stop); }
    @Override public void pause() { VOICES.applyAll(this, source -> { if (source.playing()) source.setPaused(true); }); }
    @Override public void resume() { VOICES.applyAll(this, source -> { if (source.isPaused()) source.setPaused(false); }); }
    @Override public void stop(long token) { VOICES.apply(this, token, ALSource::stop); }
    @Override public void pause(long token) { VOICES.apply(this, token, source -> { if (source.playing()) source.setPaused(true); }); }
    @Override public void resume(long token) { VOICES.apply(this, token, source -> { if (source.isPaused()) source.setPaused(false); }); }
    @Override public void setLooping(long token, boolean loop) { VOICES.apply(this, token, source -> source.setLooping(loop)); }
    @Override public void setPitch(long token, float pitch) { VOICES.apply(this, token, source -> source.setPitch(pitch)); }
    @Override public void setVolume(long token, float volume) { VOICES.apply(this, token, source -> source.setVolume(volume)); }
    @Override public void setPan(long token, float pan, float volume) {
        VOICES.apply(this, token, source -> { source.setPan(pan); source.setVolume(volume); });
    }
    @Override public void dispose() {
        synchronized (VOICES) {
            if (disposed) return;
            VOICES.releaseAll(this, ALSource::stop);
            disposed = true;
            Buffer shared = BUFFERS.get(path);
            if (--shared.owners == 0) {
                BUFFERS.remove(path);
                OALSimpleAudio.sharedInstance().unloadEffect(path);
            }
        }
    }
    private static final class Buffer {
        final ALBuffer value;
        final SoundStartGate gate;
        int owners;
        Buffer(ALBuffer value, boolean throttle) { this.value = value; gate=throttle ? new SoundStartGate() : null; }
    }
}
