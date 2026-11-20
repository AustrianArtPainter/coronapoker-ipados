package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.robovm.apple.avfoundation.AVAudioSession;
import org.robovm.apple.avfoundation.AVPlayerActionAtItemEnd;
import org.robovm.apple.avfoundation.AVPlayerItem;
import org.robovm.apple.avfoundation.AVPlayerItemStatus;
import org.robovm.apple.avfoundation.AVPlayerStatus;
import org.robovm.apple.avfoundation.AVPlayerTimeControlStatus;
import org.robovm.apple.avfoundation.AVQueuePlayer;
import org.robovm.apple.coremedia.CMTime;
import org.robovm.apple.foundation.NSNotificationCenter;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.foundation.NSOperationQueue;
import org.robovm.apple.foundation.NSURL;
import org.robovm.apple.foundation.NSString;
import org.robovm.objc.ObjCRuntime;
import org.robovm.objc.Selector;

/**
 * Opt-in background-music experiment. Two independent items are queued before
 * looping playback; an end notification only replenishes the spare item. There
 * is no Java sample feeder, audio processing tap, periodic observer or looper.
 * This API choice is not a guarantee against GC-related audio interruption.
 */
final class IOSSystemMusic implements Music {
    private static final Set<IOSSystemMusic> PLAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private final FileHandle file;
    private final NSURL url;
    private final boolean diagnosticMute;
    private final ArrayDeque<Track> tracks = new ArrayDeque<>();
    private AVQueuePlayer player;
    private NSObject.NSKeyValueObserver playerStatusObserver;
    private Music legacy;
    private boolean fallback, disposed, looping, requested, ended, failed, completionSent;
    private boolean seekPending, seekInFlight;
    private float volume = 1f, pan, position;
    private double duration;
    private long generation;
    private String failure;
    private volatile OnCompletionListener completion;

    private static final class Track {
        final AVPlayerItem item;
        final long generation;
        NSObject endObserver, failureObserver;
        NSObject.NSKeyValueObserver statusObserver;
        boolean active = true, observingStatus;
        Track(AVPlayerItem item, long generation) { this.item = item; this.generation = generation; }
    }

    // Lazy, probe-only selectors/constants. Raw object identities are compared
    // synchronously while this player's monitor is held; no object is exposed,
    // retained from a sample, or dereferenced later from a stored identity.
    private static final class ProbeSelectors {
        static final Selector STATUS = Selector.register("status");
        static final Selector TIME_CONTROL = Selector.register("timeControlStatus");
        static final Selector WAITING = Selector.register("reasonForWaitingToPlay");
        static final Selector CURRENT_ITEM = Selector.register("currentItem");
        static final NSString MINIMIZE = org.robovm.apple.avfoundation.AVPlayer.WaitingToMinimizeStallsReason();
        static final NSString EVALUATE = org.robovm.apple.avfoundation.AVPlayer.WaitingWhileEvaluatingBufferingRateReason();
        static final NSString NO_ITEM = org.robovm.apple.avfoundation.AVPlayer.WaitingWithNoItemToPlayReason();
    }
    /** Probe-only primitive readout; normal playback never initializes ProbeSelectors. */
    synchronized void sampleProbeState(long[] state, int offset) {
        if (fallback) {
            IOSMusic.sampleProbeState(legacy, state, offset); state[offset] = 2;
            state[offset + 5] = generation; return;
        }
        for (int i = 0; i < 6; i++) state[offset + i] = -1;
        state[offset] = 1; state[offset + 5] = generation;
        if (disposed || player == null) { state[offset + 4] = 0; return; }
        long handle = player.getHandle();
        state[offset + 1] = ObjCRuntime.int_objc_msgSend(handle, ProbeSelectors.STATUS.getHandle());
        state[offset + 2] = ObjCRuntime.int_objc_msgSend(handle, ProbeSelectors.TIME_CONTROL.getHandle());
        long reason = ObjCRuntime.ptr_objc_msgSend(handle, ProbeSelectors.WAITING.getHandle());
        state[offset + 3] = reason == 0 ? 0 : reason == ProbeSelectors.MINIMIZE.getHandle() ? 1
                : reason == ProbeSelectors.EVALUATE.getHandle() ? 2 : reason == ProbeSelectors.NO_ITEM.getHandle() ? 3 : 4;
        state[offset + 4] = ObjCRuntime.ptr_objc_msgSend(handle, ProbeSelectors.CURRENT_ITEM.getHandle());
    }

    IOSSystemMusic(FileHandle file) {
        if (!file.file().isFile()) throw new IllegalArgumentException("Missing queued music: " + file);
        this.file = file;
        diagnosticMute = IOSMusic.diagnosticMute(file);
        url = new NSURL(file.file());
        try { rebuild(0f); }
        catch (RuntimeException error) { releaseQueue(); url.dispose(); throw error; }
        synchronized (PLAYERS) { PLAYERS.add(this); }
        System.out.println("IOS music background backend=queue");
    }

    private static IOSSystemMusic[] snapshot() {
        synchronized (PLAYERS) { return PLAYERS.toArray(new IOSSystemMusic[0]); }
    }
    static String registeredBackend() {
        boolean queue = false, legacy = false;
        for (IOSSystemMusic music : snapshot()) synchronized (music) {
            if (!music.disposed) { if (music.fallback) legacy = true; else queue = true; }
        }
        return queue && legacy ? "mixed" : queue ? "queue" : legacy ? "queue-fell-back" : null;
    }
    static boolean hasAudiblePlayback() {
        for (IOSSystemMusic music : snapshot()) synchronized (music) {
            if (music.disposed || !music.requested || music.effectiveVolume() <= 0) continue;
            boolean playing = music.fallback ? music.legacy != null && music.legacy.isPlaying()
                    : music.player != null && music.player.getTimeControlStatus() == AVPlayerTimeControlStatus.Playing;
            if (playing && AVAudioSession.getSharedInstance().getOutputVolume() > 0) return true;
        }
        return false;
    }

    private float effectiveVolume() { return diagnosticMute ? 0f : volume; }
    private void checkOpen() { if (disposed) throw new IllegalStateException("Music is disposed"); }
    private float currentPosition() {
        if (fallback) return legacy == null ? position : legacy.getPosition();
        if (player == null || seekPending || ended) return position;
        double seconds = player.getCurrentTime().getSeconds();
        return Double.isFinite(seconds) && seconds >= 0 ? (float) seconds : position;
    }
    private float boundedPosition(float seconds) {
        if (Float.isNaN(seconds) || seconds < 0) return 0f;
        return duration > 0 ? (float) Math.min(duration, seconds) : seconds;
    }

    /** Called with this monitor held, or before publication in the constructor. */
    private void rebuild(float seconds) {
        releaseQueue();
        position = boundedPosition(seconds);
        ended = false; failed = false; failure = null; completionSent = false;
        seekPending = position > 0; seekInFlight = false;
        player = new AVQueuePlayer();
        final AVQueuePlayer current = player;
        final long epoch = generation;
        playerStatusObserver = (key, object, change) -> NSOperationQueue.getMainQueue().addOperation(
                () -> playerStatusChanged(current, epoch));
        player.addKeyValueObserver("status", playerStatusObserver);
        player.setActionAtItemEnd(AVPlayerActionAtItemEnd.Advance);
        player.setVolume(effectiveVolume());
        appendTrack();
        if (looping) appendTrack();
        seekWhenReady();
        if (requested && !seekPending) player.play();
    }
    private void appendTrack() {
        final Track track = new Track(new AVPlayerItem(url), generation);
        NSNotificationCenter center = NSNotificationCenter.getDefaultCenter();
        try {
            track.endObserver = center.addObserver(AVPlayerItem.DidPlayToEndTimeNotification(), track.item,
                    NSOperationQueue.getMainQueue(), ignored -> itemEnded(track));
            track.failureObserver = center.addObserver(AVPlayerItem.FailedToPlayToEndTimeNotification(), track.item,
                    NSOperationQueue.getMainQueue(), ignored -> itemStatusChanged(track, true));
            track.statusObserver = (key, object, change) -> NSOperationQueue.getMainQueue().addOperation(
                    () -> itemStatusChanged(track, false));
            track.item.addKeyValueObserver("status", track.statusObserver);
            track.observingStatus = true;
            if (!player.canInsertItem(track.item, null)) throw new IllegalStateException("Native music item cannot be queued");
            player.insertItem(track.item, null);
            tracks.addLast(track);
        } catch (RuntimeException error) { detach(track); track.item.dispose(); throw error; }
    }
    private void detach(Track track) {
        track.active = false;
        if (track.observingStatus) {
            track.item.removeKeyValueObserver("status", track.statusObserver); track.observingStatus = false;
        }
        NSNotificationCenter center = NSNotificationCenter.getDefaultCenter();
        if (track.endObserver != null) { center.removeObserver(track.endObserver); track.endObserver.dispose(); track.endObserver = null; }
        if (track.failureObserver != null) { center.removeObserver(track.failureObserver); track.failureObserver.dispose(); track.failureObserver = null; }
        track.statusObserver = null;
    }
    private void releaseQueue() {
        generation++;
        seekPending = false; seekInFlight = false;
        AVQueuePlayer previous = player;
        player = null;
        if (previous != null && playerStatusObserver != null) previous.removeKeyValueObserver("status", playerStatusObserver);
        playerStatusObserver = null;
        if (previous != null) previous.pause();
        for (Track track : tracks) detach(track);
        if (previous != null) previous.removeAllItems();
        while (!tracks.isEmpty()) tracks.removeFirst().item.dispose();
        if (previous != null) previous.dispose();
    }
    private boolean valid(Track track) {
        return !disposed && !fallback && player != null && track.active && track.generation == generation;
    }
    private void updateDuration(Track track) {
        double seconds = track.item.getDuration().getSeconds();
        if (Double.isFinite(seconds) && seconds > 0) duration = seconds;
    }
    private OnCompletionListener fail(String message) {
        if (failed) return null;
        failure = message; failed = true; requested = false; seekPending = false; seekInFlight = false;
        if (player != null) player.pause();
        System.err.println(message);
        if (completionSent) return null;
        completionSent = true;
        return completion;
    }
    private void deliver(OnCompletionListener listener, long epoch) {
        if (listener == null) return;
        synchronized (this) { if (disposed || epoch != generation) return; }
        listener.onCompletion(this);
    }
    private void itemStatusChanged(Track track, boolean notificationFailure) {
        OnCompletionListener listener = null;
        long epoch;
        synchronized (this) {
            if (!valid(track)) return;
            epoch = generation;
            try {
                if (notificationFailure || track.item.getStatus() == AVPlayerItemStatus.Failed || player.getStatus() == AVPlayerStatus.Failed) {
                    listener = fail("Native queued music failed: " + track.item.getError());
                } else if (track.item.getStatus() == AVPlayerItemStatus.ReadyToPlay) {
                    updateDuration(track); seekWhenReady();
                }
            } catch (RuntimeException error) { listener = fail("Native queued music status failed: " + error); }
        }
        deliver(listener, epoch);
    }
    private void playerStatusChanged(AVQueuePlayer source, long epoch) {
        OnCompletionListener listener = null;
        synchronized (this) {
            if (disposed || fallback || epoch != generation || source != player) return;
            try {
                if (source.getStatus() == AVPlayerStatus.Failed) listener = fail("Native music queue failed: " + source.getError());
                else seekWhenReady();
            } catch (RuntimeException error) { listener = fail("Native music queue status failed: " + error); }
        }
        deliver(listener, epoch);
    }
    private void itemEnded(Track track) {
        OnCompletionListener listener = null;
        long epoch;
        synchronized (this) {
            if (!valid(track)) return;
            epoch = generation;
            try {
                updateDuration(track);
                detach(track); tracks.remove(track); player.removeItem(track.item); track.item.dispose();
                if (looping) {
                    while (tracks.size() < 2) appendTrack();
                    if (requested && !seekPending) player.play();
                } else {
                    requested = false; ended = true; position = duration > 0 ? (float) duration : position;
                    if (!completionSent) { completionSent = true; listener = completion; }
                }
            } catch (RuntimeException error) { listener = fail("Native queued music refill failed: " + error); }
        }
        deliver(listener, epoch);
    }

    private void seekWhenReady() {
        if (!seekPending || seekInFlight || player == null || tracks.isEmpty()) return;
        Track first = tracks.getFirst();
        if (first.item.getStatus() != AVPlayerItemStatus.ReadyToPlay) return;
        updateDuration(first); position = boundedPosition(position);
        if (!Float.isFinite(position)) throw new IllegalArgumentException("Music position requires a known finite duration");
        final long epoch = generation;
        seekInFlight = true;
        player.seekToTime(CMTime.create((double) position, 1000000), CMTime.Zero(), CMTime.Zero(),
                finished -> NSOperationQueue.getMainQueue().addOperation(() -> seekCompleted(epoch, finished)));
    }
    private void seekCompleted(long epoch, boolean finished) {
        OnCompletionListener listener = null;
        synchronized (this) {
            if (disposed || fallback || epoch != generation || player == null || !seekInFlight) return;
            seekInFlight = false; seekPending = false;
            if (!finished) listener = fail("Native queued music seek did not complete");
            else if (requested) player.play();
        }
        deliver(listener, epoch);
    }

    private void releaseLegacy() {
        generation++;
        Music previous = legacy; legacy = null;
        if (previous != null) { previous.setOnCompletionListener(null); previous.stop(); previous.dispose(); }
    }
    private void configureLegacy(Music replacement, float seconds) {
        legacy = replacement;
        final long epoch = generation;
        replacement.setLooping(looping); replacement.setPan(pan, effectiveVolume()); replacement.setPosition(seconds);
        replacement.setOnCompletionListener(ignored -> legacyCompleted(replacement, epoch));
    }
    private void legacyCompleted(Music source, long epoch) {
        OnCompletionListener listener;
        synchronized (this) {
            if (disposed || source != legacy || epoch != generation || !requested || completionSent) return;
            requested = false; ended = true; completionSent = true;
            position = duration > 0 ? (float) duration : source.getPosition();
            listener = completion;
        }
        deliver(listener, epoch);
    }
    private Music newLegacy() {
        try { return IOSMusic.createLegacyFallback(file); }
        catch (Exception error) { throw new IllegalStateException("Unable to preserve native music pan", error); }
    }
    private void migrateForPan(float value) {
        // Prepare first, then sample the old position immediately before pause.
        Music replacement = newLegacy();
        float seconds = currentPosition();
        releaseQueue(); fallback = true; pan = value;
        try {
            configureLegacy(replacement, seconds);
            if (requested) replacement.play();
        } catch (RuntimeException error) { releaseLegacy(); throw error; }
        System.out.println("IOS music background backend=legacy-pan reason=nonzero-pan");
    }

    @Override public synchronized void play() {
        checkOpen();
        if (fallback) {
            if (legacy == null || ended) {
                float seconds = ended ? 0f : position;
                releaseLegacy(); configureLegacy(newLegacy(), seconds);
                ended = false; completionSent = false;
            }
            requested = true; legacy.play(); return;
        }
        if (failed) throw new IllegalStateException(failure);
        if (player == null || ended) rebuild(ended ? 0f : position);
        if (player.getStatus() == AVPlayerStatus.Failed) throw new IllegalStateException("Native music queue failed: " + player.getError());
        if (requested && (seekPending || player.getTimeControlStatus() != AVPlayerTimeControlStatus.Paused)) return;
        IOSMusic.activatePlayback(); requested = true;
        seekWhenReady();
        if (!seekPending) player.play();
    }
    @Override public synchronized void pause() {
        if (disposed) return;
        requested = false;
        if (fallback) { if (legacy != null) legacy.pause(); }
        else if (player != null) player.pause();
    }
    @Override public synchronized void stop() {
        if (disposed) return;
        requested = false; ended = false; completionSent = false; failed = false; failure = null; position = 0;
        if (fallback) releaseLegacy(); else releaseQueue();
    }
    @Override public synchronized boolean isPlaying() {
        if (disposed || !requested || ended || failed) return false;
        if (fallback) return legacy != null && legacy.isPlaying();
        return player != null && player.getStatus() != AVPlayerStatus.Failed
                && (seekPending || player.getTimeControlStatus() != AVPlayerTimeControlStatus.Paused);
    }
    @Override public synchronized void setLooping(boolean loop) {
        checkOpen();
        if (looping == loop) return;
        float seconds = currentPosition(); looping = loop;
        if (fallback) { if (legacy != null) legacy.setLooping(loop); }
        else if (player != null) rebuild(seconds);
    }
    @Override public synchronized boolean isLooping() { return looping; }
    @Override public synchronized void setVolume(float value) {
        checkOpen(); volume = Math.max(0, Math.min(1, value));
        if (fallback) { if (legacy != null) legacy.setVolume(effectiveVolume()); }
        else if (player != null) player.setVolume(effectiveVolume());
    }
    @Override public synchronized float getVolume() { return effectiveVolume(); }
    @Override public synchronized void setPan(float value, float volume) {
        checkOpen(); setVolume(volume);
        float clipped = Math.max(-1, Math.min(1, value));
        if (!fallback && clipped != 0) migrateForPan(clipped);
        else if (fallback) { pan = clipped; if (legacy != null) legacy.setPan(clipped, effectiveVolume()); }
    }
    @Override public synchronized void setPosition(float seconds) {
        checkOpen(); position = boundedPosition(seconds); ended = false; completionSent = false;
        if (fallback) {
            float target = position; releaseLegacy(); configureLegacy(newLegacy(), target);
            if (requested) legacy.play();
        } else rebuild(position);
    }
    @Override public synchronized float getPosition() { return currentPosition(); }
    @Override public void setOnCompletionListener(OnCompletionListener listener) { completion = listener; }
    @Override public synchronized void dispose() {
        if (disposed) return;
        disposed = true; requested = false; completion = null;
        releaseQueue(); releaseLegacy();
        synchronized (PLAYERS) { PLAYERS.remove(this); }
        url.dispose();
    }
}
