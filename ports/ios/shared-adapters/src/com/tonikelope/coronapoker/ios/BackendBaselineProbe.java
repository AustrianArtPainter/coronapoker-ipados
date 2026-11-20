package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.ScreenUtils;
import java.util.Arrays;
import org.robovm.apple.avfoundation.AVAudioPlayer;
import org.robovm.apple.avfoundation.AVAudioSession;
import org.robovm.apple.avfoundation.AVAudioSessionCategory;
import org.robovm.apple.foundation.NSURL;
import org.robovm.rt.GC;

/** Opt-in, allocation-free clear loop on the unchanged production graphics backend.
 * No game services, textures, fonts, ObjectAL, executors or diagnostic polling.
 * All measurement storage is allocated before warmup; disk writes happen only at the end.
 */
public final class BackendBaselineProbe extends ApplicationAdapter {
    private static final long SECOND = 1_000_000_000L;
    private static final int LIMIT = 16_000;
    private final long[] interval = new long[LIMIT], body = new long[LIMIT];
    private final long[] gcInInterval = new long[LIMIT], gcInBody = new long[LIMIT];
    private final long[] allocatedInInterval = new long[LIMIT], allocatedInBody = new long[LIMIT];
    private final byte[] phaseOf = new byte[LIMIT];
    private int samples, stage, pauses;
    private long phaseStarted, previousStart, previousGC, previousAllocation;
    private long initialHeap, initialTotalAllocation;
    private boolean written, musicStarted;
    private String musicFailure;
    private AVAudioPlayer player;

    @Override public void create() {
        initialHeap = GC.getHeapSize();
        initialTotalAllocation = GC.getTotalBytes();
        phaseStarted = System.nanoTime();
    }

    @Override public void render() {
        long start = System.nanoTime();
        if (stage == 0 && start - phaseStarted >= 10 * SECOND) beginStage(1, start);
        else if (stage == 1 && start - phaseStarted >= 45 * SECOND) {
            beginStage(2, start);
            startMusic(); // Once, during the excluded warmup, never during a measured frame.
            phaseStarted = System.nanoTime();
        } else if (stage == 2 && start - phaseStarted >= 5 * SECOND) beginStage(3, start);
        else if (stage == 3 && start - phaseStarted >= 45 * SECOND) {
            beginStage(4, start);
            finish(); // Measurement has ended before any report allocation or file I/O.
        }

        boolean measuring = (stage == 1 || stage == 3) && samples < LIMIT;
        long currentGC = measuring ? GC.getCount() : 0;
        long currentAllocation = measuring ? GC.getTotalBytes() : 0;
        long measureStart = System.nanoTime();
        if (stage == 0 || stage == 1) ScreenUtils.clear(0.04f, 0.08f, 0.20f, 1f);
        else if (stage == 2 || stage == 3) ScreenUtils.clear(0.04f, 0.20f, 0.08f, 1f);
        else ScreenUtils.clear(0.20f, 0.16f, 0.04f, 1f);
        long clearElapsed = System.nanoTime() - measureStart;

        if (measuring) {
            if (previousStart != 0) {
                int i = samples++;
                phaseOf[i] = (byte) stage;
                interval[i] = start - previousStart;
                gcInInterval[i] = currentGC - previousGC;
                allocatedInInterval[i] = currentAllocation - previousAllocation;
                body[i] = clearElapsed;
                gcInBody[i] = GC.getCount() - currentGC;
                allocatedInBody[i] = GC.getTotalBytes() - currentAllocation;
            }
            previousStart = start; previousGC = currentGC; previousAllocation = currentAllocation;
        }
    }

    private void beginStage(int next, long now) {
        stage = next; phaseStarted = now; previousStart = 0;
    }
    private void startMusic() {
        try {
            AVAudioSession session = AVAudioSession.getSharedInstance();
            session.setCategory(AVAudioSessionCategory.Playback); session.setActive(true);
            player = new AVAudioPlayer(new NSURL(Gdx.files.internal("sounds/misc/background_music.mp3").file()));
            player.setNumberOfLoops(-1); player.setVolume(0.7f); player.prepareToPlay();
            musicStarted = player.play();
        } catch (Exception failure) { musicFailure = failure.toString(); }
    }
    private void finish() {
        if (written) return;
        written = true;
        long finalHeap = GC.getHeapSize(), finalTotalAllocation = GC.getTotalBytes();
        StringBuilder report = new StringBuilder(32_768);
        report.append("{\"probe\":\"clean-backend-v1\",\"gameInitialized\":false,\"preferredFPS\":60,")
                .append("\"width\":").append(Gdx.graphics.getBackBufferWidth())
                .append(",\"height\":").append(Gdx.graphics.getBackBufferHeight())
                .append(",\"pauses\":").append(pauses)
                .append(",\"musicStarted\":").append(musicStarted)
                .append(",\"musicFailure\":").append(musicFailure == null ? "null" : "\"" + musicFailure.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
                .append(",\"initialHeap\":").append(initialHeap)
                .append(",\"finalHeap\":").append(finalHeap)
                .append(",\"totalAllocatedIncludingStartup\":").append(finalTotalAllocation - initialTotalAllocation)
                .append(",\"phases\":[");
        appendPhase(report, 1, "silent"); report.append(','); appendPhase(report, 3, "native-bgm");
        report.append("]}");
        IOSDiagnostics.report("ios-backend-baseline.json", report.toString());
    }
    private void appendPhase(StringBuilder out, int phase, String name) {
        int count = 0, over25 = 0, over34 = 0, over100 = 0, slowWithGC = 0;
        long intervalSum = 0, max = 0, bodySum = 0, bodyMax = 0, gcSum = 0, allocationSum = 0, bodyAllocationSum = 0;
        long[] ordered = new long[samples];
        for (int i = 0; i < samples; i++) if (phaseOf[i] == phase) {
            long value = interval[i]; ordered[count++] = value; intervalSum += value; max = Math.max(max, value);
            bodySum += body[i]; bodyMax = Math.max(bodyMax, body[i]); gcSum += gcInInterval[i];
            allocationSum += allocatedInInterval[i]; bodyAllocationSum += allocatedInBody[i];
            if (value > 25_000_000) { over25++; if (gcInInterval[i] != 0) slowWithGC++; }
            if (value > 34_000_000) over34++;
            if (value > 100_000_000) over100++;
        }
        Arrays.sort(ordered, 0, count);
        out.append("{\"name\":\"").append(name).append("\",\"frames\":").append(count)
                .append(",\"meanIntervalMs\":").append(count == 0 ? 0 : intervalSum / (count * 1e6))
                .append(",\"p50IntervalMs\":").append(percentile(ordered, count, .50))
                .append(",\"p95IntervalMs\":").append(percentile(ordered, count, .95))
                .append(",\"p99IntervalMs\":").append(percentile(ordered, count, .99))
                .append(",\"maxIntervalMs\":").append(max / 1e6)
                .append(",\"over25ms\":").append(over25).append(",\"over34ms\":").append(over34)
                .append(",\"over100ms\":").append(over100).append(",\"slowWithGC\":").append(slowWithGC)
                .append(",\"gcCount\":").append(gcSum).append(",\"allocatedInIntervals\":").append(allocationSum)
                .append(",\"allocatedInClear\":").append(bodyAllocationSum)
                .append(",\"meanClearMs\":").append(count == 0 ? 0 : bodySum / (count * 1e6))
                .append(",\"maxClearMs\":").append(bodyMax / 1e6).append(",\"slowFrames\":[");
        boolean comma = false;
        for (int i = 0; i < samples; i++) if (phaseOf[i] == phase && interval[i] > 25_000_000) {
            if (comma) out.append(','); comma = true;
            out.append("{\"sample\":").append(i).append(",\"intervalMs\":").append(interval[i] / 1e6)
                    .append(",\"clearMs\":").append(body[i] / 1e6)
                    .append(",\"gc\":").append(gcInInterval[i]).append(",\"clearGC\":").append(gcInBody[i])
                    .append(",\"allocated\":").append(allocatedInInterval[i]).append('}');
        }
        out.append("]}");
    }
    private static double percentile(long[] ordered, int count, double fraction) {
        return count == 0 ? 0 : ordered[(int) Math.ceil((count - 1) * fraction)] / 1e6;
    }
    @Override public void pause() { pauses++; previousStart = 0; }
    @Override public void dispose() { if (player != null) { player.stop(); player.dispose(); player = null; } }
}
