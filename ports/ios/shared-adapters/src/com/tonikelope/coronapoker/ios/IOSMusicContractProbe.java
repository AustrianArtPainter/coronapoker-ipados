package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.utils.ScreenUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.robovm.apple.avfoundation.AVAudioSession;
import org.robovm.apple.avfoundation.AVAudioSessionPortDescription;
import org.robovm.apple.avfoundation.AVAudioSessionRouteDescription;
import org.robovm.apple.foundation.NSArray;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.apple.foundation.NSThread;
import org.robovm.rt.GC;

/** Independent opt-in native Music checks; never ordinary scene/performance evidence. */
public final class IOSMusicContractProbe extends ApplicationAdapter {
    private static final long SECOND = 1_000_000_000L;
    private static final String REPORT = "ios-music-probe.json", READY = "ios-music-probe-ready.json";
    private static final String COLLECTION_API = "BDW GC_gcollect via diagnostic JNI";
    private static native void collectFullGC();
    private static final int LOOP_SAMPLES = 1000, LOOP_STATE_SIZE = 6;
    private final boolean gcAudio, loopAudio;
    private final double durationSeconds;
    private final String runID = UUID.randomUUID().toString();
    private final AtomicInteger completions = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private volatile boolean callbackIdentity = true;
    private Music music;
    private Object[][] fixture;
    private final long[] gcStartNano = new long[3], gcEndNano = new long[3];
    private final long[] gcStartUTC = new long[3], gcEndUTC = new long[3];
    private final long[] gcBefore = new long[3], gcAfter = new long[3];
    private final Throwable[] gcErrors = new Throwable[3];
    private long started, startedUTC, phaseStarted, triggerStarted, nextTriggerPoll, fixtureBytes;
    private long warmupGCBefore, warmupGCAfter;
    private long[] loopNano, loopEndNano, loopUTC, loopGCBefore, loopGCAfter, loopNative;
    private float[] loopPositions;
    private int loopSamples;
    private long nextLoopSample, loopCrossedNano;
    private boolean loopNearEndSeen;
    private int stage, gcRuns, pauses;
    private float pausedPosition, beforePanPosition;
    private float actualVolume = Float.NaN;
    private boolean written, readyWritten, nearEndSeen;
    private String initialBackend, expectedBackend, phase = "create";
    private String configuredProbeBuffer;
    private double requestedProbeBufferSeconds;
    private boolean bufferSetterAttempted, bufferSetterSucceeded;
    private Throwable bufferSetterError;
    private Path trigger;

    /** durationSeconds comes from the bundled source MP3's offline metadata. */
    public IOSMusicContractProbe(String mode, double durationSeconds) {
        gcAudio = "gc-audio".equals(mode);
        loopAudio = "loop-audio".equals(mode);
        this.durationSeconds = durationSeconds;
    }
    @Override public void create() {
        started = phaseStarted = System.nanoTime(); startedUTC = System.currentTimeMillis();
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            require(NSThread.isCurrentThreadMainThread(), "Probe actions require the native main thread");
            require(Double.isFinite(durationSeconds) && durationSeconds > 20, "Invalid source duration metadata");
            require(!SilentAudio.ENABLED, "Native probe cannot run with SilentAudio enabled");
            IOSDiagnostics.beginRun();
            Files.deleteIfExists(IOSDiagnostics.documents().resolve(REPORT));
            Files.deleteIfExists(IOSDiagnostics.documents().resolve(READY));
            expectedBackend = "queue".equals(System.getenv("CP_IOS_MUSIC_BACKEND")) ? "queue" : "legacy";
            configureProbeBuffer();
            music = IOSMusic.create(Gdx.files.internal("sounds/misc/background_music.mp3"));
            initialBackend = IOSMusic.backgroundBackendName();
            require(expectedBackend.equals(initialBackend), "Configured/actual initial backend mismatch: " + initialBackend);
            music.setVolume(gcAudio || loopAudio ? 0.5f : 0f);
            actualVolume = music.getVolume();
            require(actualVolume == (gcAudio || loopAudio ? 0.5f : 0f), "Diagnostic audio controls changed the requested native volume");
            music.setLooping(true);
            final Music expectedSource = music;
            music.setOnCompletionListener(source -> {
                if (source != expectedSource) callbackIdentity = false;
                completions.incrementAndGet();
            });
            if (gcAudio) {
                long before = GC.getTotalBytes();
                fixture = new Object[65536][];
                for (int row = 0; row < fixture.length; row++) fixture[row] = new Object[16];
                for (int row = 0; row < fixture.length; row++) for (int slot = 0; slot < 16; slot++)
                    fixture[row][slot] = fixture[(row + slot * 4093) & 65535];
                fixtureBytes = GC.getTotalBytes() - before;
                phase = "gc-audio-warmup";
            } else if (loopAudio) {
                loopNano = new long[LOOP_SAMPLES]; loopEndNano = new long[LOOP_SAMPLES];
                loopUTC = new long[LOOP_SAMPLES]; loopGCBefore = new long[LOOP_SAMPLES]; loopGCAfter = new long[LOOP_SAMPLES];
                loopNative = new long[LOOP_SAMPLES * LOOP_STATE_SIZE]; loopPositions = new float[LOOP_SAMPLES];
                // Initialize the optional native selectors/constants before
                // capture, not for the first time at the measured loop point.
                IOSMusic.sampleProbeState(music, loopNative, 0);
                phase = "loop-audio-warmup";
            } else phase = "initial-play";
            music.play(); phaseStarted = System.nanoTime();
        } catch (Throwable error) { finish(false, error); }
    }
    @Override public void render() {
        if (written) { ScreenUtils.clear(0.05f, 0.12f, 0.08f, 1f); return; }
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            require(NSThread.isCurrentThreadMainThread(), "Probe render left the native main thread");
            ScreenUtils.clear(0.04f, 0.08f, 0.15f, 1f);
            long now = System.nanoTime();
            require(now - started < (gcAudio || loopAudio ? 180 : 90) * SECOND, "Overall probe timeout: " + phase);
            if (gcAudio) gcAudio(now); else if (loopAudio) loopAudio(now); else contract(now);
        } catch (Throwable error) { finish(false, error); }
    }
    private double elapsed(long now) { return (now - phaseStarted) / 1e9; }
    private void enter(int next, String name) { stage = next; phase = name; phaseStarted = System.nanoTime(); }
    private void record(String step) {
        events.add("{\"step\":" + quote(step) + ",\"elapsedMs\":" + (System.nanoTime() - started) / 1e6
                + ",\"backend\":" + quote(IOSMusic.backgroundBackendName()) + ",\"position\":" + music.getPosition()
                + ",\"playing\":" + music.isPlaying() + ",\"looping\":" + music.isLooping()
                + ",\"volume\":" + music.getVolume() + ",\"completions\":" + completions.get() + "}");
    }
    private void contract(long now) {
        require(elapsed(now) < 12, "Music contract phase timeout: " + phase);
        float position = music == null ? 0 : music.getPosition();
        require(Float.isFinite(position), "Native music returned a non-finite position");
        switch (stage) {
            case 0:
                if (!music.isPlaying() || position < 0.1f) return;
                record("play"); music.pause(); pausedPosition = music.getPosition(); enter(1, "pause"); break;
            case 1:
                if (elapsed(now) < 0.35) return;
                require(!music.isPlaying() && Math.abs(position - pausedPosition) < 0.10, "Pause did not preserve position");
                record("pause-position-stable"); music.setPosition(5f); enter(2, "paused-seek"); break;
            case 2:
                if (elapsed(now) < 0.5) return;
                require(!music.isPlaying() && Math.abs(position - 5f) < 0.15, "Paused seek resumed or lost its target");
                record("paused-seek"); music.play(); enter(3, "resume"); break;
            case 3:
                if (!music.isPlaying() || position < 5.1f) return;
                record("resume-after-seek"); music.stop(); enter(4, "stop"); break;
            case 4:
                if (elapsed(now) < 0.25) return;
                require(!music.isPlaying() && position < 0.05f, "Stop did not reset position");
                record("stop-reset"); music.play(); enter(5, "replay"); break;
            case 5:
                if (!music.isPlaying() || position < 0.1f) return;
                require(position < 2f, "Replay did not start near zero"); record("replay");
                music.setPosition((float) durationSeconds - 0.25f); music.stop();
                music.setPosition(3f); music.play(); enter(6, "superseded-seek-generation"); break;
            case 6:
                if (elapsed(now) < 0.8) return;
                require(music.isPlaying() && position >= 3f && position < 6f && completions.get() == 0,
                        "Superseded seek/stop callback altered replacement playback");
                record("superseded-seek-stop-generation"); music.pause(); music.setLooping(false);
                music.setPosition((float) durationSeconds - 0.4f); music.play(); enter(7, "nonloop-end"); break;
            case 7:
                require(completions.get() <= 1, "Duplicate nonloop completion");
                if (completions.get() != 1 || music.isPlaying()) return;
                require(callbackIdentity, "Completion callback Music identity changed"); enter(8, "completion-settle"); break;
            case 8:
                if (elapsed(now) < 0.4) return;
                require(completions.get() == 1 && !music.isPlaying(), "Nonloop completion was not exactly once");
                record("nonloop-completion-once"); music.play(); enter(9, "replay-after-end"); break;
            case 9:
                if (!music.isPlaying() || position < 0.1f) return;
                require(position < 2f && completions.get() == 1, "Replay after natural end failed");
                record("replay-after-natural-end"); music.stop(); music.setLooping(true);
                nearEndSeen = false;
                music.setPosition((float) durationSeconds - 0.8f); music.play(); enter(10, "loop-boundary"); break;
            case 10:
                require(completions.get() == 1, "Looping produced a completion callback");
                // Require an advancing near-end position, not just the pending
                // seek target. A player that ignores seek and starts at zero
                // must not pass the later wrap-to-zero assertion.
                if (!nearEndSeen && music.isPlaying() && position > durationSeconds - 0.75 && position < durationSeconds - 0.05) {
                    nearEndSeen = true; record("near-end-playhead-advanced");
                }
                if (!nearEndSeen || elapsed(now) < 0.8 || !music.isPlaying() || position >= 2f) return;
                record("loop-boundary-crossed"); enter(11, "loop-continues"); break;
            case 11:
                if (elapsed(now) < 1) return;
                require(music.isPlaying() && position >= 0.8f && position < 5f && music.isLooping() && completions.get() == 1,
                        "Playback did not continue past the loop boundary");
                record("loop-continues"); beforePanPosition = position; music.setPan(0.25f, 0f); enter(12, "pan-fallback"); break;
            case 12:
                if (elapsed(now) < 0.45) return;
                String expected = "queue".equals(expectedBackend) ? "queue-fell-back" : "legacy";
                require(expected.equals(IOSMusic.backgroundBackendName()) && music.isPlaying() && music.isLooping()
                        && music.getVolume() == 0 && position >= beforePanPosition - 0.10 && position < beforePanPosition + 2f,
                        "Nonzero pan did not preserve backend/position/volume/loop/play state");
                record("pan-state-preserved"); music.setPan(0f, 0f); enter(13, "pan-reset-single-way"); break;
            case 13:
                if (elapsed(now) < 0.35) return;
                require(("queue".equals(expectedBackend) ? "queue-fell-back" : "legacy").equals(IOSMusic.backgroundBackendName()),
                        "Pan reset switched backends again");
                record("pan-reset-remains-legacy"); music.pause(); pausedPosition = music.getPosition(); enter(14, "fallback-pause"); break;
            case 14:
                if (elapsed(now) < 0.35) return;
                require(!music.isPlaying() && Math.abs(position - pausedPosition) < 0.10, "Pan backend pause lost position");
                record("fallback-pause"); music.stop(); music.play(); enter(15, "fallback-replay"); break;
            case 15:
                if (!music.isPlaying() || position < 0.1f) return;
                require(position < 2f, "Pan backend stop/replay did not reset"); record("fallback-stop-replay");
                music.setLooping(false); music.setPosition((float) durationSeconds - 0.3f); music.play(); enter(16, "fallback-end"); break;
            case 16:
                require(completions.get() <= 2, "Duplicate pan-backend completion");
                if (completions.get() != 2 || music.isPlaying()) return;
                require(callbackIdentity, "Pan-backend callback Music identity changed"); enter(17, "fallback-completion-settle"); break;
            case 17:
                if (elapsed(now) < 0.4) return;
                require(completions.get() == 2, "Pan-backend completion was not exactly once");
                record("fallback-completion-once"); music.setPosition((float) durationSeconds - 0.1f); music.play();
                music.dispose(); music = null; enter(18, "disposed-callback-generation"); break;
            case 18:
                if (elapsed(now) < 1) return;
                require(completions.get() == 2 && "no-bgm".equals(IOSMusic.backgroundBackendName()),
                        "Disposed music retained playback or delivered a stale completion");
                finish(true, null); break;
            default: throw new IllegalStateException("Unknown contract phase");
        }
    }

    private void gcAudio(long now) throws Exception {
        if (stage == 0) {
            require(elapsed(now) < 15, "GC audio warmup timeout");
            if (elapsed(now) < 5 || !music.isPlaying() || music.getPosition() < 1) return;
            warmupGCBefore = GC.getCount();
            collectFullGC(); // Explicitly outside the externally triggered measurement.
            warmupGCAfter = GC.getCount();
            require(warmupGCAfter > warmupGCBefore, "Native full warmup collection did not increment the GC count");
            require(fixture[0][0] == fixture[0], "Pointer fixture was lost");
            require(music.getVolume() == 0.5f && expectedBackend.equals(IOSMusic.backgroundBackendName()), "GC audio warmup lost its configured volume/backend");
            trigger = IOSDiagnostics.documents().resolve("ios-music-gc-trigger-" + runID + ".txt");
            String ready = base("native-music-gc-audio-ready-v2") + ",\"ready\":true,\"triggerFile\":"
                    + quote(trigger.getFileName().toString()) + ",\"triggerContents\":" + quote(runID)
                    + ",\"fixtureRows\":65536,\"fixtureSlotsPerRow\":16,\"fixtureAllocatedBytes\":" + fixtureBytes
                    + ",\"warmupGCBeforeReady\":true,\"nativeGCCountAtReady\":" + GC.getCount()
                    + ",\"readyNano\":" + System.nanoTime() + ",\"readyUTCMillis\":" + System.currentTimeMillis() + "}";
            write(READY, ready); readyWritten = true; enter(1, "waiting-for-run-trigger");
        } else if (stage == 1) {
            require(elapsed(now) < 120, "Timed out waiting for this run's trigger file");
            if (now < nextTriggerPoll) return;
            nextTriggerPoll = now + 100_000_000L;
            if (!Files.isRegularFile(trigger)) return;
            require(runID.equals(new String(Files.readAllBytes(trigger), StandardCharsets.UTF_8).trim()), "Trigger contents do not match this run");
            Files.delete(trigger); triggerStarted = System.nanoTime(); enter(2, "explicit-gc-audio-pressure");
        } else if (stage == 2) {
            // No volume/pause/stop/seek, filesystem polling or per-event report
            // allocation in the bounded pressure interval.
            if (gcRuns < 3 && now - triggerStarted >= (2L + 2L * gcRuns) * SECOND) {
                int index = gcRuns++;
                gcBefore[index] = GC.getCount(); gcStartUTC[index] = System.currentTimeMillis(); gcStartNano[index] = System.nanoTime();
                try { collectFullGC(); } catch (Throwable error) { gcErrors[index] = error; }
                finally { gcEndNano[index] = System.nanoTime(); gcEndUTC[index] = System.currentTimeMillis(); gcAfter[index] = GC.getCount(); }
            }
            if (gcRuns == 3 && now - triggerStarted >= 8 * SECOND) {
                require(fixture[65535][15] != null, "Pointer graph is no longer strongly retained");
                require(music.isPlaying() && music.getVolume() == 0.5f && completions.get() == 0
                        && expectedBackend.equals(IOSMusic.backgroundBackendName()), "GC audio playback/volume/backend changed unexpectedly");
                for (int i = 0; i < 3; i++) require(gcErrors[i] == null && gcAfter[i] > gcBefore[i], "Native full collection did not complete or increment the GC count");
                finish(true, null);
            }
        }
    }
    private void loopAudio(long now) throws Exception {
        if (stage == 0) {
            require(elapsed(now) < 15, "Loop audio warmup timeout");
            if (elapsed(now) < 5 || !music.isPlaying() || music.getPosition() < 1) return;
            require(music.getVolume() == 0.5f && expectedBackend.equals(IOSMusic.backgroundBackendName()), "Loop audio warmup lost its configured volume/backend");
            trigger = IOSDiagnostics.documents().resolve("ios-music-loop-trigger-" + runID + ".txt");
            String ready = base("native-music-loop-audio-ready-v1") + ",\"ready\":true,\"triggerFile\":"
                    + quote(trigger.getFileName().toString()) + ",\"triggerContents\":" + quote(runID)
                    + ",\"forcedGC\":false,\"fixtureRows\":0,\"requestedCaptureSeconds\":35,\"tailSeekSeconds\":15"
                    + ",\"nativeGCCountAtReady\":" + GC.getCount() + ",\"readyNano\":" + System.nanoTime()
                    + ",\"readyUTCMillis\":" + System.currentTimeMillis() + "}";
            write(READY, ready); readyWritten = true; enter(1, "waiting-for-loop-run-trigger");
        } else if (stage == 1) {
            require(elapsed(now) < 120, "Timed out waiting for this loop run's trigger file");
            if (now < nextTriggerPoll) return;
            nextTriggerPoll = now + 100_000_000L;
            if (!Files.isRegularFile(trigger)) return;
            require(runID.equals(new String(Files.readAllBytes(trigger), StandardCharsets.UTF_8).trim()), "Loop trigger contents do not match this run");
            Files.delete(trigger); triggerStarted = System.nanoTime();
            music.setPosition((float) durationSeconds - 15f);
            enter(2, "prepared-tail-loop-audio");
        } else if (stage == 2) {
            require(now - triggerStarted < 45 * SECOND, "Loop audio boundary/continuation timeout");
            if (now < nextLoopSample) return;
            require(loopSamples < LOOP_SAMPLES, "Loop audio sample capacity exhausted");
            nextLoopSample = now + 50_000_000L;
            int sample = loopSamples++;
            loopNano[sample] = System.nanoTime(); loopUTC[sample] = System.currentTimeMillis();
            loopGCBefore[sample] = GC.getCount();
            IOSMusic.sampleProbeState(music, loopNative, sample * LOOP_STATE_SIZE);
            float position = loopPositions[sample] = music.getPosition();
            loopGCAfter[sample] = GC.getCount(); loopEndNano[sample] = System.nanoTime();
            require(Float.isFinite(position), "Loop audio returned a non-finite playhead");
            require(completions.get() == 0 && music.getVolume() == 0.5f && music.isLooping(), "Loop audio changed volume/loop/completion state");
            if (position > durationSeconds - 14.9 && position < durationSeconds - 0.05) loopNearEndSeen = true;
            if (loopCrossedNano == 0 && loopNearEndSeen && position < 2 && music.isPlaying()) loopCrossedNano = loopNano[sample];
            if (loopCrossedNano != 0 && now - loopCrossedNano >= 8 * SECOND) {
                require(position >= 7.5f && position < 20 && music.isPlaying()
                        && expectedBackend.equals(IOSMusic.backgroundBackendName()), "Loop audio did not continue on its configured backend");
                finish(true, null);
            }
        }
    }
    private void configureProbeBuffer() throws Throwable {
        // This class is constructed only by an explicit Music diagnostic. Do
        // not read this flag or change audio session policy in normal startup.
        configuredProbeBuffer = System.getenv("CP_IOS_PROBE_BUFFER_MS");
        if (configuredProbeBuffer == null || "default".equals(configuredProbeBuffer)) return;
        if ("46".equals(configuredProbeBuffer)) requestedProbeBufferSeconds = 0.046;
        else if ("93".equals(configuredProbeBuffer)) requestedProbeBufferSeconds = 0.093;
        else throw new IllegalArgumentException("CP_IOS_PROBE_BUFFER_MS requires default, 46 or 93 exactly");
        bufferSetterAttempted = true;
        try {
            // Advisory even if this session was already active. Do not set a
            // category, deactivate/reactivate, or alter sample rate for a test.
            bufferSetterSucceeded = AVAudioSession.getSharedInstance().setPreferredIOBufferDuration(requestedProbeBufferSeconds);
            require(bufferSetterSucceeded, "The probe audio-buffer preference request returned false");
        } catch (Throwable error) { bufferSetterError = error; throw error; }
    }
    private static String finite(double value) { return Double.isFinite(value) ? Double.toString(value) : "null"; }
    private static String routePorts(NSArray<AVAudioSessionPortDescription> ports) {
        if (ports == null) return "null";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < ports.size(); i++) {
            if (i != 0) out.append(',');
            AVAudioSessionPortDescription port = ports.get(i);
            out.append("{\"type\":").append(quote(String.valueOf(port.getPortType())))
                    .append(",\"name\":").append(quote(port.getPortName())).append('}');
        }
        return out.append(']').toString();
    }
    private String bufferControlReport() {
        // Called only while preparing ready/final JSON, outside all measured
        // pressure samples. Never equate preference acceptance with actual I/O.
        String actual;
        try {
            long observedUTC = System.currentTimeMillis(), observedNano = System.nanoTime();
            AVAudioSession session = AVAudioSession.getSharedInstance();
            double preferred = session.getPreferredIOBufferDuration(), io = session.getIOBufferDuration();
            double sampleRate = session.getSampleRate(), inputLatency = session.getInputLatency(), outputLatency = session.getOutputLatency();
            String category = String.valueOf(session.getCategory()), mode = String.valueOf(session.getMode());
            AVAudioSessionRouteDescription route = session.getCurrentRoute();
            String inputs = route == null ? "null" : routePorts(route.getInputs());
            String outputs = route == null ? "null" : routePorts(route.getOutputs());
            actual = "{\"observedUTCMillis\":" + observedUTC + ",\"observedNano\":" + observedNano
                    + ",\"preferredIOBufferSeconds\":" + finite(preferred) + ",\"ioBufferSeconds\":" + finite(io)
                    + ",\"sampleRate\":" + finite(sampleRate) + ",\"inputLatencySeconds\":" + finite(inputLatency)
                    + ",\"outputLatencySeconds\":" + finite(outputLatency) + ",\"category\":" + quote(category)
                    + ",\"mode\":" + quote(mode) + ",\"route\":{\"inputs\":" + inputs + ",\"outputs\":" + outputs + "},\"readError\":null}";
        } catch (Throwable error) { actual = "{\"readError\":" + quote(stack(error)) + "}"; }
        return "{\"configured\":" + quote(configuredProbeBuffer) + ",\"requestedSeconds\":"
                + (requestedProbeBufferSeconds > 0 ? finite(requestedProbeBufferSeconds) : "null")
                + ",\"setterAttempted\":" + bufferSetterAttempted + ",\"setterSucceeded\":"
                + (bufferSetterAttempted ? Boolean.toString(bufferSetterSucceeded) : "null")
                + ",\"setterError\":" + quote(stack(bufferSetterError)) + ",\"actual\":" + actual
                + ",\"scope\":\"Probe-only advisory preference, requested before Music construction; session may already be active. This control does not change category, activation or sample rate. Read actual values at ready/final; it does not promise production audio continuity.\"}";
    }
    private String base(String probe) {
        return "{\"probe\":" + quote(probe) + ",\"runID\":" + quote(runID)
                + ",\"pid\":" + NSProcessInfo.getSharedProcessInfo().getProcessIdentifier()
                + ",\"mode\":" + quote(gcAudio ? "gc-audio" : loopAudio ? "loop-audio" : "contract")
                + ",\"gameInitialized\":false,\"normalScenePerformance\":false,\"mainThreadActions\":true"
                + ",\"configuredBackend\":" + quote(System.getenv("CP_IOS_MUSIC_BACKEND"))
                + ",\"initialBackend\":" + quote(initialBackend) + ",\"actualBackend\":" + quote(IOSMusic.backgroundBackendName())
                + ",\"requestedMusicVolume\":" + (gcAudio || loopAudio ? "0.5" : "0")
                + ",\"actualMusicVolume\":" + (Float.isFinite(actualVolume) ? Float.toString(actualVolume) : "null")
                + ",\"collectionAPI\":" + quote(gcAudio ? COLLECTION_API : null)
                + ",\"warmupGCCountBefore\":" + warmupGCBefore + ",\"warmupGCCountAfter\":" + warmupGCAfter
                + ",\"audioBufferControl\":" + bufferControlReport()
                + ",\"gcRuntime\":" + IOSGCRuntime.snapshotJson()
                + ",\"sourceDurationSeconds\":" + durationSeconds;
    }
    private void finish(boolean passed, Throwable error) {
        if (written) return;
        written = true;
        String actualBeforeCleanup = IOSMusic.backgroundBackendName();
        if (music != null) {
            try { actualVolume = music.getVolume(); }
            catch (Throwable readError) { if (error == null) error = readError; else error.addSuppressed(readError); passed = false; }
        }
        if ((!(gcAudio || loopAudio) || !passed) && music != null) {
            try { music.dispose(); } catch (Throwable cleanup) { if (error == null) error = cleanup; else error.addSuppressed(cleanup); passed = false; }
            music = null;
        }
        try {
            StringBuilder out = new StringBuilder(base(gcAudio ? "native-music-gc-audio-v2" : loopAudio ? "native-music-loop-audio-v1" : "native-music-contract-v1"));
            out.append(",\"completed\":true,\"passed\":").append(passed).append(",\"phase\":").append(quote(phase))
                    .append(",\"actualBackendBeforeCleanup\":").append(quote(actualBeforeCleanup))
                    .append(",\"startedUTCMillis\":").append(startedUTC).append(",\"finishedUTCMillis\":").append(System.currentTimeMillis())
                    .append(",\"startedNano\":").append(started).append(",\"finishedNano\":").append(System.nanoTime())
                    .append(",\"completions\":").append(completions.get()).append(",\"callbackIdentity\":").append(callbackIdentity)
                    .append(",\"pauses\":").append(pauses).append(",\"readyWritten\":").append(readyWritten)
                    .append(",\"warmupGCBeforeReady\":").append(gcAudio && readyWritten)
                    .append(",\"triggerStartedNano\":").append(triggerStarted).append(",\"fixtureRows\":").append(fixture == null ? 0 : fixture.length)
                    .append(",\"fixtureSlotsPerRow\":").append(fixture == null ? 0 : 16).append(",\"fixtureAllocatedBytes\":").append(fixtureBytes)
                    .append(",\"scope\":").append(quote(gcAudio ? "Explicit native BDW GC_gcollect pressure with a retained pointer graph; full collection invocation duration is not exact stop-the-world duration; not normal homepage performance."
                            : loopAudio ? "Prepared-tail native Music loop probe; no forced GC or pointer fixture; playhead/state observations require external App-only PCM analysis for audio continuity, and do not verify an unseeked full-song loop or normal scene performance."
                            : "Independent muted Music contract probe; not normal gameplay, audio-quality or performance evidence."))
                    .append(",\"forcedGC\":").append(gcAudio).append(",\"audioContinuityEvaluated\":false")
                    .append(",\"events\":[").append(String.join(",", events)).append("],\"gcInvocations\":[");
            for (int i = 0; i < gcRuns; i++) {
                if (i != 0) out.append(',');
                out.append("{\"index\":").append(i).append(",\"startUTCMillis\":").append(gcStartUTC[i])
                        .append(",\"endUTCMillis\":").append(gcEndUTC[i]).append(",\"startNano\":").append(gcStartNano[i])
                        .append(",\"endNano\":").append(gcEndNano[i]).append(",\"countBefore\":").append(gcBefore[i])
                        .append(",\"countAfter\":").append(gcAfter[i]).append(",\"error\":").append(quote(stack(gcErrors[i]))).append('}');
            }
            out.append("],\"loopCrossedNano\":").append(loopCrossedNano)
                    .append(",\"loopNearEndAdvanced\":").append(loopNearEndSeen)
                    .append(",\"loopSampleIntervalMillis\":50,\"loopSampleCapacity\":").append(loopAudio ? LOOP_SAMPLES : 0)
                    .append(",\"loopNativeStateColumns\":[\"backend\",\"playerStatus\",\"timeControlStatus\",\"waitingReason\",\"opaqueNativeItemOrPlayerIdentity\",\"generation\"]")
                    .append(",\"loopNativeStateCodes\":{\"backend\":\"0=legacy,1=queue,2=queue-fell-back\",\"playerStatus\":\"-1=unavailable,0=unknown,1=ready,2=failed\",\"timeControlStatus\":\"-1=unavailable,0=paused,1=waiting,2=playing; legacy maps isPlaying to 0/2\",\"waitingReason\":\"-1=unavailable,0=none,1=minimizeStalls,2=evaluatingBufferingRate,3=noItemToPlay,4=otherOrNonidenticalConstant\",\"identity\":\"queue=currentItem,legacy=AVAudioPlayer; opaque nonretained identity only, never dereference from report\"}")
                    .append(",\"loopSamples\":[");
            for (int i = 0; i < loopSamples; i++) {
                if (i != 0) out.append(',');
                out.append("{\"nano\":").append(loopNano[i]).append(",\"endNano\":").append(loopEndNano[i])
                        .append(",\"utcMillis\":").append(loopUTC[i]).append(",\"position\":")
                        .append(Float.isFinite(loopPositions[i]) ? Float.toString(loopPositions[i]) : "null")
                        .append(",\"gcBefore\":").append(loopGCBefore[i]).append(",\"gcAfter\":").append(loopGCAfter[i]).append(",\"native\":[");
                for (int column = 0; column < LOOP_STATE_SIZE; column++) {
                    if (column != 0) out.append(','); out.append(loopNative[i * LOOP_STATE_SIZE + column]);
                }
                out.append("]}");
            }
            out.append("],\"error\":").append(quote(stack(error))).append('}');
            write(REPORT, out.toString());
            System.out.println("Native music probe finished: mode=" + (gcAudio ? "gc-audio" : loopAudio ? "loop-audio" : "contract") + " passed=" + passed + " runID=" + runID);
        } catch (Throwable reporting) { System.err.println("Native music probe report failed: " + reporting); }
    }
    private static void write(String name, String text) throws Exception {
        Path directory = IOSDiagnostics.documents(); Files.createDirectories(directory);
        Files.write(directory.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String stack(Throwable error) {
        if (error == null) return null;
        StringWriter text = new StringWriter(); error.printStackTrace(new PrintWriter(text)); return text.toString();
    }
    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    @Override public void pause() { pauses++; if (!written) finish(false, new IllegalStateException("Probe interrupted by application pause")); }
    @Override public void dispose() {
        if (!written) finish(false, new IllegalStateException("Probe disposed before completion"));
        if (music != null) { music.dispose(); music = null; }
        fixture = null;
    }
}
