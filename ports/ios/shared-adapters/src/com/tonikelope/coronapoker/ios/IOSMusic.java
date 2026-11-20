package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.robovm.apple.avfoundation.AVAudioPlayer;
import org.robovm.apple.avfoundation.AVAudioPlayerDelegateAdapter;
import org.robovm.apple.avfoundation.AVAudioSession;
import org.robovm.apple.avfoundation.AVAudioSessionCategory;
import org.robovm.apple.foundation.NSError;
import org.robovm.apple.foundation.NSURL;

/** Real AVAudioPlayer music; independent of the paused OpenGL render loop. */
public final class IOSMusic implements Music {
    private static final Set<IOSMusic> PLAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile IOSMusic clockOwner;
    private final AVAudioPlayer player;
    private final AVAudioPlayerDelegateAdapter delegate;
    private volatile OnCompletionListener completion;
    private boolean disposed;
    private boolean looping;
    private final boolean diagnosticMute;
    private final boolean reportBackground;
    // Two primitive counters only in the explicit diagnostic; no per-frame JNI.
    private final long[] clockVolumeCounters;
    private final long clockPlayerIdentity;
    private float clockLastVolume;
    private boolean clockVolumeSeen;

    private IOSMusic(FileHandle file) throws Exception {
        this(file, true);
    }
    private IOSMusic(FileHandle file, boolean reportBackground) throws Exception {
        this.reportBackground = reportBackground && background(file);
        diagnosticMute = diagnosticMute(file);
        clockVolumeCounters = IOSMusicClock.ENABLED && this.reportBackground ? new long[2] : null;
        player = new AVAudioPlayer(new NSURL(file.file()));
        clockPlayerIdentity = clockVolumeCounters == null ? 0 : player.getHandle();
        delegate = new AVAudioPlayerDelegateAdapter() {
            @Override public void didFinishPlaying(AVAudioPlayer source, boolean succeeded) {
                if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.FINISH, succeeded ? 1 : 0);
                OnCompletionListener listener = completion;
                if (listener != null) listener.onCompletion(IOSMusic.this);
            }
            @Override public void decodeErrorDidOccur(AVAudioPlayer source, NSError error) {
                if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.DECODE_ERROR, error == null ? 0 : error.getCode());
                System.err.println("Native music decode failed: " + error);
                OnCompletionListener listener = completion;
                if (listener != null) listener.onCompletion(IOSMusic.this);
            }
        };
        player.setDelegate(delegate);
        player.prepareToPlay();
        synchronized (PLAYERS) { PLAYERS.add(this); }
        if (clockVolumeCounters != null) {
            synchronized (IOSMusic.class) {
                if (clockOwner != null) {
                    IOSMusicClock.event(player.getHandle(), 30, 0);
                    throw new IllegalStateException("Audio clock requires one background player; duplicate/recreated player rejected");
                }
                IOSMusicClock.register(player.getHandle()); clockOwner = this;
            }
        }
    }

    static boolean background(FileHandle file) { return "background_music.mp3".equals(file.name()); }
    static boolean diagnosticMute(FileHandle file) {
        boolean background = background(file);
        String audioTest = System.getenv("CP_IOS_AUDIO_TEST");
        return "music-only".equals(audioTest) ? !background : "effects-only".equals(audioTest) && background;
    }
    /** Explicit capability fallback, bypassing the opt-in factory selection. */
    static Music createLegacyFallback(FileHandle file) throws Exception { return new IOSMusic(file, false); }

    /** Probe-only readout into caller-owned storage; never called by normal playback. */
    static void sampleProbeState(Music music, long[] state, int offset) {
        if (music instanceof IOSSystemMusic) {
            ((IOSSystemMusic) music).sampleProbeState(state, offset); return;
        }
        for (int i = 0; i < 6; i++) state[offset + i] = -1;
        if (music instanceof IOSMusic) {
            IOSMusic nativeMusic = (IOSMusic) music;
            state[offset] = 0; // legacy; queue uses 1, queue pan fallback uses 2.
            state[offset + 2] = nativeMusic.disposed ? -1 : nativeMusic.player.isPlaying() ? 2 : 0;
            state[offset + 4] = nativeMusic.disposed ? 0 : nativeMusic.player.getHandle();
        }
    }

    public static Music create(FileHandle file) {
        if (SilentAudio.ENABLED) return SilentAudio.music(file);
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0L;
        try {
            return background(file) && "queue".equals(System.getenv("CP_IOS_MUSIC_BACKEND"))
                    ? new IOSSystemMusic(file) : new IOSMusic(file);
        }
        catch (Exception failure) { throw new IllegalStateException("Unable to open native music: " + file, failure); }
        finally { if (IOSPerformance.ENABLED) IOSPerformance.record("music.load", System.nanoTime() - start); }
    }
    public static synchronized void activatePlayback() {
        if (SilentAudio.ENABLED) return;
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0L;
        try {
            AVAudioSession session = AVAudioSession.getSharedInstance();
            AVAudioSessionCategory category = session.getCategory();
            if (category != AVAudioSessionCategory.PlayAndRecord && category != AVAudioSessionCategory.Playback)
                session.setCategory(AVAudioSessionCategory.Playback);
            session.setActive(true);
            if (IOSMusicClock.ENABLED) IOSMusicClock.event(0, IOSMusicClock.SESSION_ACTIVE, 1);
        } catch (Exception failure) { throw new IllegalStateException("Unable to activate game audio", failure); }
        finally { if (IOSPerformance.ENABLED) IOSPerformance.record("music.session", System.nanoTime() - start); }
    }
    public static boolean hasAudiblePlayback() {
        if (SilentAudio.ENABLED) return false;
        synchronized (PLAYERS) {
            for (IOSMusic music : PLAYERS)
                if (!music.disposed && music.player.isPlaying() && music.player.getVolume() > 0)
                    return AVAudioSession.getSharedInstance().getOutputVolume() > 0;
        }
        return IOSSystemMusic.hasAudiblePlayback();
    }
    /** Actual background backend, including explicit pan fallback; sampled on demand. */
    public static String backgroundBackendName() {
        if (SilentAudio.ENABLED) return "silent";
        boolean legacy = false;
        synchronized (PLAYERS) {
            for (IOSMusic music : PLAYERS) if (!music.disposed && music.reportBackground) legacy = true;
        }
        String candidate = IOSSystemMusic.registeredBackend();
        return candidate == null ? (legacy ? "legacy" : "no-bgm") : legacy ? "mixed" : candidate;
    }
    /** Diagnostic-only render hook; absent flag returns before any JNI or player access. */
    public static void sampleClockEvidence() {
        if (!IOSMusicClock.ENABLED) return;
        IOSMusic owner = clockOwner;
        if (owner == null) return;
        synchronized (owner) {
            if (!IOSMusicClock.sample(owner.clockPlayerIdentity, owner.clockVolumeCounters)) clockOwner = null;
        }
    }
    @Override public synchronized void play() {
        if (disposed) throw new IllegalStateException("Music is disposed");
        if (player.isPlaying()) return;
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0L;
        try {
        activatePlayback();
        if (!player.play()) throw new IllegalStateException("Native music playback could not start");
        if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.PLAY, 1);
        } finally { if (IOSPerformance.ENABLED) IOSPerformance.record("music.play", System.nanoTime() - start); }
    }
    @Override public synchronized void pause() { if (!disposed) { player.pause(); if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.PAUSE, 0); } }
    @Override public synchronized void stop() { if (!disposed) { player.stop(); player.setCurrentTime(0); if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.STOP, 0); } }
    @Override public synchronized boolean isPlaying() { return !disposed && player.isPlaying(); }
    @Override public synchronized void setLooping(boolean loop) { looping = loop; player.setNumberOfLoops(loop ? -1 : 0); if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.LOOP, loop ? 1 : 0); }
    @Override public boolean isLooping() { return looping; }
    @Override public synchronized void setVolume(float volume) {
        float actual = diagnosticMute ? 0f : Math.max(0, Math.min(1, volume));
        player.setVolume(actual);
        if (clockVolumeCounters != null) {
            clockVolumeCounters[0]++;
            if (!clockVolumeSeen || Float.floatToIntBits(clockLastVolume) != Float.floatToIntBits(actual)) {
                clockVolumeSeen = true; clockLastVolume = actual; clockVolumeCounters[1]++;
                IOSMusicClock.event(player.getHandle(), IOSMusicClock.VOLUME, actual);
            }
        }
    }
    @Override public synchronized float getVolume() { return player.getVolume(); }
    @Override public synchronized void setPan(float pan, float volume) { player.setPan(Math.max(-1, Math.min(1, pan))); setVolume(volume); if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.PAN, pan); }
    @Override public synchronized void setPosition(float seconds) { player.setCurrentTime(Math.max(0, Math.min(player.getDuration(), seconds))); if (clockVolumeCounters != null) IOSMusicClock.event(player.getHandle(), IOSMusicClock.SEEK, seconds); }
    @Override public synchronized float getPosition() { return (float) player.getCurrentTime(); }
    @Override public void setOnCompletionListener(OnCompletionListener listener) { completion = listener; }
    @Override public synchronized void dispose() {
        if (disposed) return;
        player.stop(); player.setDelegate(null); completion = null; disposed = true;
        if (clockVolumeCounters != null) IOSMusicClock.detach(player.getHandle(), clockVolumeCounters);
        synchronized (PLAYERS) { PLAYERS.remove(this); }
        player.dispose(); delegate.dispose();
    }
}
