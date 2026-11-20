package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.audio.*;
import com.badlogic.gdx.backends.iosrobovm.IOSAudio;
import com.badlogic.gdx.files.FileHandle;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit foreground isolation probe. No ObjectAL, AVAudioPlayer or audio session. */
public final class SilentAudio implements IOSAudio {
    public static final boolean ENABLED = "silent".equals(System.getenv("CP_IOS_AUDIO_TEST"));
    public static final AtomicLong SOUND_STARTS = new AtomicLong(), MUSIC_STARTS = new AtomicLong();
    public static volatile boolean backendInstalled;
    private static final String[] OUTPUT = {"diagnostic\tSilent diagnostic output"};
    private static final String[] INPUT = {};
    public SilentAudio() { backendInstalled = true; }
    public static String[] outputs() { return ENABLED ? OUTPUT : IOSPlatform.audioOutputs(); }
    public static String[] inputs() { return ENABLED ? INPUT : IOSPlatform.microphoneInputs(); }
    public static Sound sound() { return new QuietSound(); }
    public static Music music(FileHandle file) {
        float duration = 3600f;
        if ("wav".equalsIgnoreCase(file.extension()))
            duration = PortableWave.durationSeconds(file.readBytes(), 1f);
        return new QuietMusic(duration);
    }
    @Override public Sound newSound(FileHandle file) { return sound(); }
    @Override public Music newMusic(FileHandle file) { return music(file); }
    @Override public AudioDevice newAudioDevice(int sampleRate, boolean mono) {
        throw new UnsupportedOperationException("Audio device disabled for silence probe");
    }
    @Override public AudioRecorder newAudioRecorder(int sampleRate, boolean mono) {
        throw new UnsupportedOperationException("Audio recording disabled for silence probe");
    }
    @Override public boolean switchOutputDevice(String name) { return true; }
    @Override public String[] getAvailableOutputDevices() { return OUTPUT; }
    @Override public void didBecomeActive() {}
    @Override public void willEnterForeground() {}
    @Override public void willResignActive() {}
    @Override public void dispose() {}

    private static final class QuietSound implements Sound {
        @Override public long play() { return SOUND_STARTS.incrementAndGet(); }
        @Override public long play(float volume) { return play(); }
        @Override public long play(float volume, float pitch, float pan) { return play(); }
        @Override public long loop() { return play(); }
        @Override public long loop(float volume) { return play(); }
        @Override public long loop(float volume, float pitch, float pan) { return play(); }
        @Override public void stop() {}
        @Override public void pause() {}
        @Override public void resume() {}
        @Override public void dispose() {}
        @Override public void stop(long id) {}
        @Override public void pause(long id) {}
        @Override public void resume(long id) {}
        @Override public void setLooping(long id, boolean loop) {}
        @Override public void setPitch(long id, float pitch) {}
        @Override public void setVolume(long id, float volume) {}
        @Override public void setPan(long id, float pan, float volume) {}
    }

    /** Retain playback clocks for existing cue barriers without creating native players. */
    private static final class QuietMusic implements Music {
        final float duration;
        float position, volume = 1f;
        long started;
        boolean playing, looping, disposed;
        OnCompletionListener completion;
        QuietMusic(float duration) { this.duration = duration; }
        private float clock() { return position + (playing ? (System.nanoTime() - started) / 1_000_000_000f : 0f); }
        @Override public synchronized void play() {
            if (disposed) throw new IllegalStateException("Music disposed");
            if (playing) return;
            playing = true; started = System.nanoTime(); MUSIC_STARTS.incrementAndGet();
        }
        @Override public synchronized void pause() { position = getPosition(); playing = false; }
        @Override public synchronized void stop() { playing = false; position = 0; }
        @Override public synchronized boolean isPlaying() {
            if (playing && !looping && clock() >= duration) {
                playing = false; position = duration;
                if (completion != null) completion.onCompletion(this);
            }
            return playing && !disposed;
        }
        @Override public synchronized void setLooping(boolean loop) { looping = loop; }
        @Override public boolean isLooping() { return looping; }
        @Override public void setVolume(float value) { volume = Math.max(0, Math.min(1, value)); }
        @Override public float getVolume() { return volume; }
        @Override public void setPan(float pan, float value) { setVolume(value); }
        @Override public synchronized void setPosition(float value) { position = Math.max(0, Math.min(duration, value)); started = System.nanoTime(); }
        @Override public synchronized float getPosition() { float value = clock(); return looping ? value % duration : Math.min(duration, value); }
        @Override public void setOnCompletionListener(OnCompletionListener listener) { completion = listener; }
        @Override public synchronized void dispose() { stop(); disposed = true; completion = null; }
    }
}
