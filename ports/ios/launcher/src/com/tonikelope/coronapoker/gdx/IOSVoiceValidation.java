package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSDiagnostics;
import com.tonikelope.coronapoker.ios.PortableWave;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in finite playback of synthetic audio through the actual voice-note path. */
final class IOSVoiceValidation {
    private static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_VOICE_PROBE"));
    private static final AtomicBoolean ONCE = new AtomicBoolean();

    static void startOnce() {
        if (!ENABLED || !ONCE.compareAndSet(false, true)) return;
        AtomicLong startedAt = new AtomicLong();
        try {
            byte[] pcm = new byte[16000 * 2 * 2];
            for (int frame = 0; frame < pcm.length / 2; frame++) {
                int sample = (int) (7000 * Math.sin(2 * Math.PI * 440 * frame / 16000));
                pcm[frame * 2] = (byte) sample;
                pcm[frame * 2 + 1] = (byte) (sample >>> 8);
            }
            byte[] wave = PortableWave.encodePcm(pcm);
            IOSDiagnostics.report("ios-voice-probe.json", "{\"phase\":\"queued\",\"syntheticOnly\":true}");
            GdxVoicePlayback.play(wave, 0.4f, () -> {
                startedAt.set(System.nanoTime());
                IOSDiagnostics.report("ios-voice-probe.json", "{\"phase\":\"playing\",\"syntheticOnly\":true}");
            }).whenComplete((ignored, failure) -> {
                long elapsed = startedAt.get() == 0 ? -1 : (System.nanoTime() - startedAt.get()) / 1000000;
                boolean passed = failure == null && elapsed >= 1500 && elapsed < 5000;
                IOSDiagnostics.report("ios-voice-probe.json", "{\"phase\":\"finished\",\"syntheticOnly\":true,\"started\":"
                        + (startedAt.get() != 0) + ",\"elapsedMillis\":" + elapsed + ",\"passed\":" + passed
                        + ",\"exception\":" + (failure != null) + "}");
                if (failure != null) IOSDiagnostics.voiceFailure(failure);
            });
        } catch (Throwable failure) {
            IOSDiagnostics.voiceFailure(failure);
            IOSDiagnostics.report("ios-voice-probe.json", "{\"phase\":\"failed\",\"syntheticOnly\":true,\"passed\":false}");
        }
    }

    private IOSVoiceValidation() {}
}
