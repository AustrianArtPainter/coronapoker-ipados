package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;

/** Records the real effect backend's load/start cost only during an explicit device probe. */
public final class ProfiledSound implements Sound {
    private static final boolean MUSIC_ONLY = "music-only".equals(System.getenv("CP_IOS_AUDIO_TEST"));
    private final Sound sound;
    private ProfiledSound(Sound sound) { this.sound = sound; }
    public static long throttledStarts() { return IOSOwnedSound.throttledStarts; }
    public static long failedStarts() { return IOSOwnedSound.failedStarts; }
    public static Sound create(FileHandle file) {
        if (SilentAudio.ENABLED) return SilentAudio.sound();
        if (!IOSPerformance.ENABLED && !MUSIC_ONLY) return new IOSOwnedSound(file);
        long start = System.nanoTime();
        try { return new ProfiledSound(new IOSOwnedSound(file)); }
        finally { IOSPerformance.record("sound.load", System.nanoTime() - start); }
    }
    @Override public long play() { return play(1f, 1f, 0f); }
    @Override public long play(float volume) { return play(volume, 1f, 0f); }
    @Override public long play(float volume, float pitch, float pan) {
        if (MUSIC_ONLY) return -1L;
        long start = System.nanoTime();
        try { return sound.play(volume, pitch, pan); }
        finally { IOSPerformance.record("sound.play", System.nanoTime() - start); }
    }
    @Override public long loop() { return loop(1f, 1f, 0f); }
    @Override public long loop(float volume) { return loop(volume, 1f, 0f); }
    @Override public long loop(float volume, float pitch, float pan) {
        if (MUSIC_ONLY) return -1L;
        long start = System.nanoTime();
        try { return sound.loop(volume, pitch, pan); }
        finally { IOSPerformance.record("sound.loop", System.nanoTime() - start); }
    }
    @Override public void stop() { sound.stop(); }
    @Override public void pause() { sound.pause(); }
    @Override public void resume() { sound.resume(); }
    @Override public void dispose() { sound.dispose(); }
    @Override public void stop(long id) { sound.stop(id); }
    @Override public void pause(long id) { sound.pause(id); }
    @Override public void resume(long id) { sound.resume(id); }
    @Override public void setLooping(long id, boolean loop) { sound.setLooping(id, loop); }
    @Override public void setPitch(long id, float pitch) { sound.setPitch(id, pitch); }
    @Override public void setVolume(long id, float volume) { sound.setVolume(id, volume); }
    @Override public void setPan(long id, float pan, float volume) { sound.setPan(id, pan, volume); }
}
