package com.tonikelope.coronapoker.ios;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Opt-in timings without screenshots, GL readback, debugger, or work on the audio thread. */
public final class IOSPerformance {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_PROFILE"));
    private static final Map<String, Samples> METRICS = new LinkedHashMap<>();
    private static long previousFrame;
    private static long previousGC, frameGC;
    private static boolean started;
    public static long beginFrame() {
        if (!ENABLED) return 0L;
        long now = System.nanoTime();
        long gc = org.robovm.rt.GC.getCount();
        if (previousFrame != 0L && now - previousFrame < TimeUnit.SECONDS.toNanos(2)) {
            record("frame.interval", now - previousFrame);
            record(gc != previousGC ? "frame.interval.withGC" : "frame.interval.withoutGC", now - previousFrame);
        }
        previousGC = frameGC = gc;
        previousFrame = now;
        return now;
    }
    public static void endFrame(long start) {
        if (!ENABLED) return;
        long duration = System.nanoTime() - start;
        record("frame.render", duration);
        if (org.robovm.rt.GC.getCount() != frameGC) record("frame.render.withGC", duration);
    }
    public static long beginAllocation() { return ENABLED
            || com.tonikelope.coronapoker.gdx.IOSFrameEvidence.allocationScopesEnabled()
            ? org.robovm.rt.GC.getTotalBytes() : 0L; }
    public static void endAllocation(String name, long start) {
        if (!ENABLED) {
            if (start != 0L && com.tonikelope.coronapoker.gdx.IOSFrameEvidence.allocationScopesEnabled())
                com.tonikelope.coronapoker.gdx.IOSFrameEvidence.allocationScope(name, org.robovm.rt.GC.getTotalBytes() - start);
            return;
        }
        // Process-wide counter: use quiet/static scenes to isolate UI work.
        synchronized (IOSPerformance.class) {
            METRICS.computeIfAbsent(name, ignored -> new Samples()).add(org.robovm.rt.GC.getTotalBytes() - start);
        }
    }
    public static synchronized void record(String name, long nanos) {
        if (!ENABLED) return;
        METRICS.computeIfAbsent(name, ignored -> new Samples()).add(nanos / 1_000_000.0);
        if (!started) {
            started = true;
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "coronapoker-ios-perf-report"); worker.setDaemon(true); return worker;
            }).scheduleAtFixedRate(IOSPerformance::report, 10, 10, TimeUnit.SECONDS);
        }
    }
    private static void report() {
        StringBuilder json = new StringBuilder("{\"units\":\"milliseconds\",\"metrics\":{");
        synchronized (IOSPerformance.class) {
        boolean first = true;
        for (var entry : METRICS.entrySet()) {
            if (!first) json.append(','); first = false;
            Samples s = entry.getValue();
            double[] sorted = Arrays.copyOf(s.recent, (int) Math.min(s.count, s.recent.length)); Arrays.sort(sorted);
            boolean bytes = entry.getKey().startsWith("alloc.");
            json.append('"').append(entry.getKey()).append("\":{\"units\":\"")
                    .append(bytes ? "bytes" : "milliseconds").append("\",\"count\":").append(s.count)
                    .append(",\"mean\":").append(s.total / s.count).append(",\"max\":").append(s.maximum)
                    .append(",\"recentP95\":").append(sorted[(int) ((sorted.length - 1) * 0.95)]);
            if (!bytes) json.append(",\"over34ms\":").append(s.over34).append(",\"over100ms\":").append(s.over100);
            json.append('}');
        }
        }
        IOSDiagnostics.report("ios-performance.json", json.append("},\"fps\":")
                .append(com.badlogic.gdx.Gdx.graphics.getFramesPerSecond())
                .append(",\"audioIOBufferSeconds\":").append((SilentAudio.ENABLED ? 0 : org.robovm.apple.avfoundation.AVAudioSession.getSharedInstance().getIOBufferDuration()))
                .append(",\"audioPreferredIOBufferSeconds\":").append((SilentAudio.ENABLED ? 0 : org.robovm.apple.avfoundation.AVAudioSession.getSharedInstance().getPreferredIOBufferDuration()))
                .append(",\"audioMixing\":").append((!SilentAudio.ENABLED && org.robovm.apple.avfoundation.AVAudioSession.getSharedInstance().getCategoryOptions().contains(org.robovm.apple.avfoundation.AVAudioSessionCategoryOptions.MixWithOthers)))
                .append(",\"audioSampleRate\":").append((SilentAudio.ENABLED ? 0 : org.robovm.apple.avfoundation.AVAudioSession.getSharedInstance().getSampleRate()))
                .append(",\"audioIsolation\":\"").append(SilentAudio.ENABLED ? "silent" : "normal").append("\"")
                .append(",\"silentBackendInstalled\":").append(SilentAudio.backendInstalled)
                .append(",\"suppressedSoundStarts\":").append(SilentAudio.SOUND_STARTS.get())
                .append(",\"suppressedMusicStarts\":").append(SilentAudio.MUSIC_STARTS.get())
                .append(",\"cardCryptoIsolation\":\"").append(MockCardCrypto.ENABLED ? "mock" : "real").append("\"")
                .append(",\"mockDeals\":").append(MockCardCrypto.DEALS.get())
                .append(",\"mockReveals\":").append(MockCardCrypto.REVEALS.get())
                .append(",\"realCardCryptoCallsInMock\":").append(MockCardCrypto.REAL_CALLS.get())
                .append(",\"mockSignatures\":").append(MockCardCrypto.SIGNATURES.get())
                .append(",\"realCardCryptoCalls\":").append(MockCardCrypto.NORMAL_CALLS.get())
                .append(",\"cardAnimationIsolation\":\"").append(InstantCardProbe.ENABLED ? "instant-cards" : "normal").append("\"")
                .append(",\"instantCardEvents\":").append(InstantCardProbe.EVENTS.get())
                .append(",\"instantCardSounds\":").append(InstantCardProbe.SOUNDS.get())
                .append(",\"tablePaintIsolation\":\"").append(TablePaintProbe.ENABLED ? "off" : "normal").append("\"")
                .append(",\"tablePaintSkippedFrames\":").append(TablePaintProbe.skippedFrames)
                .append(",\"gcCount\":").append(org.robovm.rt.GC.getCount())
                .append(",\"heapBytes\":").append(org.robovm.rt.GC.getHeapSize())
                .append(",\"allocatedBytes\":").append(org.robovm.rt.GC.getTotalBytes())
                .append(",\"computeLimit\":").append(IOSCompute.LIMIT)
                .append(",\"computeMaxActive\":").append(IOSCompute.maxActive())
                .append(",\"computeActive\":").append(IOSCompute.active())
                .append(",\"computeQueued\":").append(IOSCompute.queued())
                .append(",\"computeCompleted\":").append(IOSCompute.completed())
                .append(",\"computeQoS\":").append(IOSCompute.workerQoS())
                .append(",\"computeQoSFailures\":").append(IOSCompute.qosFailures())
                .append("}\n").toString());
    }
    private static final class Samples {
        final double[] recent = new double[1024];
        long count, over34, over100;
        double total, maximum;
        void add(double millis) {
            recent[(int) (count++ % recent.length)] = millis;
            total += millis; maximum = Math.max(maximum, millis);
            if (millis > 34) over34++; if (millis > 100) over100++;
        }
    }
    private IOSPerformance() {}
}
