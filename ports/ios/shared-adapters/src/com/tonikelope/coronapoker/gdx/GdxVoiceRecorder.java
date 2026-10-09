package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.IOSPlatform;
import com.tonikelope.coronapoker.ios.PortableWave;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/** Native microphone, original capture limits/gain, unchanged mu-law wire. */
final class GdxVoiceRecorder {
    enum Outcome { RECORDING, OK, ABORTED, NO_LINE, EMPTY, SILENT, LOST, ENCODE_ERROR }
    static final int MAX_SECONDS = 15, MIN_MILLIS = 100, TAIL_MILLIS = 250;
    private final boolean automaticGain;
    private final String input;
    private volatile boolean recording;
    private volatile Outcome outcome = Outcome.ABORTED;
    private volatile long capturedMillis;
    private volatile Thread watcher;
    GdxVoiceRecorder() { this(null); }
    GdxVoiceRecorder(Properties properties) {
        automaticGain = automaticGainEnabled(properties);
        input = properties == null ? "" : properties.getProperty(GdxAudioDevices.CAPTURE_KEY, "");
    }
    Outcome start(Runnable onLive, Runnable onEnded) {
        try {
            if (!IOSPlatform.startMicrophone(input)) return outcome = Outcome.NO_LINE;
            recording = true; outcome = Outcome.RECORDING;
            if (onLive != null) onLive.run();
            watcher = new Thread(() -> {
                long deadline = System.nanoTime() + MAX_SECONDS * 1_000_000_000L;
                while (recording && System.nanoTime() < deadline) {
                    capturedMillis = Math.max(capturedMillis, Math.round(IOSPlatform.microphoneSeconds() * 1000));
                    try { Thread.sleep(50); } catch (InterruptedException interrupted) { return; }
                }
                if (recording && onEnded != null) onEnded.run();
            }, "coronapoker-ios-voice-capture");
            watcher.setDaemon(true); watcher.start();
            return Outcome.RECORDING;
        } catch (Exception failure) { return outcome = Outcome.NO_LINE; }
    }
    byte[] stopAndEncode() {
        try {
            if (!recording) return null;
            Thread.sleep(TAIL_MILLIS);
            capturedMillis = Math.max(capturedMillis, Math.round(IOSPlatform.microphoneSeconds() * 1000));
            recording = false;
            if (watcher != null) watcher.interrupt();
            byte[] wave = IOSPlatform.stopMicrophone();
            if (wave == null) { outcome = Outcome.LOST; return null; }
            byte[] pcm = PortableWave.pcmFromWave(wave);
            if (pcm.length < 3200) { outcome = Outcome.EMPTY; return null; }
            if (peakAmplitude(pcm) <= 8) { outcome = Outcome.SILENT; return null; }
            byte[] encoded = encodePcm(automaticGain ? applySafeCaptureGain(pcm) : pcm);
            outcome = Outcome.OK; return encoded;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); abort(); return null;
        } catch (Exception failure) { outcome = Outcome.ENCODE_ERROR; return null; }
    }
    static byte[] encodePcm(byte[] pcm) throws Exception { return PortableWave.encodePcm(pcm); }
    long capturedMillis() {
        if (recording) capturedMillis = Math.max(capturedMillis, Math.round(IOSPlatform.microphoneSeconds() * 1000));
        return capturedMillis;
    }
    Outcome outcome() { return outcome; }
    boolean isRecording() { return recording; }
    void abort() {
        recording = false; outcome = Outcome.ABORTED;
        if (watcher != null) watcher.interrupt();
        IOSPlatform.abortMicrophone();
    }
    private static int peakAmplitude(byte[] bytes) {
        int peak = 0;
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            int sample = (short) ((bytes[i] & 255) | bytes[i + 1] << 8);
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }
    static byte[] applySafeCaptureGain(byte[] pcm) {
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("Unaligned PCM voice payload");
        int peak = peakAmplitude(pcm);
        if (peak <= 0 || peak >= 29490) return Arrays.copyOf(pcm, pcm.length);
        float gain = Math.min(4, 29490 / (float) peak);
        byte[] boosted = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short source = (short) ((pcm[i] & 255) | pcm[i + 1] << 8);
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(source * gain)));
            boosted[i] = (byte) sample; boosted[i + 1] = (byte) (sample >>> 8);
        }
        return boosted;
    }
    static boolean automaticGainEnabled(Properties properties) {
        return properties == null || Boolean.parseBoolean(properties.getProperty(GdxSettingsContract.AUDIO_MIC_AUTO_GAIN_KEY, "true"));
    }
}
