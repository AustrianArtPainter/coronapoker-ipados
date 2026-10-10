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
    private final AVAudioPlayer player;
    private final AVAudioPlayerDelegateAdapter delegate;
    private volatile OnCompletionListener completion;
    private boolean disposed;
    private boolean looping;
    private final boolean diagnosticMute;

    private IOSMusic(FileHandle file) throws Exception {
        boolean background = "background_music.mp3".equals(file.name());
        String audioTest = System.getenv("CP_IOS_AUDIO_TEST");
        diagnosticMute = "music-only".equals(audioTest) ? !background : "effects-only".equals(audioTest) && background;
        player = new AVAudioPlayer(new NSURL(file.file()));
        delegate = new AVAudioPlayerDelegateAdapter() {
            @Override public void didFinishPlaying(AVAudioPlayer source, boolean succeeded) {
                OnCompletionListener listener = completion;
                if (listener != null) listener.onCompletion(IOSMusic.this);
            }
            @Override public void decodeErrorDidOccur(AVAudioPlayer source, NSError error) {
                System.err.println("Native music decode failed: " + error);
                OnCompletionListener listener = completion;
                if (listener != null) listener.onCompletion(IOSMusic.this);
            }
        };
        player.setDelegate(delegate);
        player.prepareToPlay();
        synchronized (PLAYERS) { PLAYERS.add(this); }
    }

    public static Music create(FileHandle file) {
        if (SilentAudio.ENABLED) return SilentAudio.music(file);
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0L;
        try { return new IOSMusic(file); }
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
        return false;
    }
    @Override public synchronized void play() {
        if (disposed) throw new IllegalStateException("Music is disposed");
        if (player.isPlaying()) return;
        long start = IOSPerformance.ENABLED ? System.nanoTime() : 0L;
        try {
        activatePlayback();
        if (!player.play()) throw new IllegalStateException("Native music playback could not start");
        } finally { if (IOSPerformance.ENABLED) IOSPerformance.record("music.play", System.nanoTime() - start); }
    }
    @Override public synchronized void pause() { if (!disposed) player.pause(); }
    @Override public synchronized void stop() { if (!disposed) { player.stop(); player.setCurrentTime(0); } }
    @Override public synchronized boolean isPlaying() { return !disposed && player.isPlaying(); }
    @Override public synchronized void setLooping(boolean loop) { looping = loop; player.setNumberOfLoops(loop ? -1 : 0); }
    @Override public boolean isLooping() { return looping; }
    @Override public synchronized void setVolume(float volume) { player.setVolume(diagnosticMute ? 0f : Math.max(0, Math.min(1, volume))); }
    @Override public synchronized float getVolume() { return player.getVolume(); }
    @Override public synchronized void setPan(float pan, float volume) { player.setPan(Math.max(-1, Math.min(1, pan))); setVolume(volume); }
    @Override public synchronized void setPosition(float seconds) { player.setCurrentTime(Math.max(0, Math.min(player.getDuration(), seconds))); }
    @Override public synchronized float getPosition() { return (float) player.getCurrentTime(); }
    @Override public void setOnCompletionListener(OnCompletionListener listener) { completion = listener; }
    @Override public synchronized void dispose() {
        if (disposed) return;
        player.stop(); player.setDelegate(null); completion = null; disposed = true;
        synchronized (PLAYERS) { PLAYERS.remove(this); }
        player.dispose(); delegate.dispose();
    }
}
