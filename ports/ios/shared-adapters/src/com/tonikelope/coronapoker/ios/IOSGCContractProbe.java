package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.utils.ScreenUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSOperationQueue;
import org.robovm.apple.foundation.NSThread;
import org.robovm.rt.GC;

/** Isolated correctness probe; never ordinary game, frame, or audio evidence. */
public final class IOSGCContractProbe extends ApplicationAdapter {
    private static final long SECOND = 1_000_000_000L;
    private static final int COLLECTIONS = 5;
    private static native void startNative();
    private static native long heartbeat();
    private static native void collectFullGC();
    private static native boolean validateNativeSentinel();
    private static native boolean stopNative();
    private static native void nativeCounters(long[] output);
    private static volatile IOSGCContractProbe active;
    private final String runID = UUID.randomUUID().toString();
    private final Worker[] workers = new Worker[4];
    private final AtomicReference<Throwable> workerError = new AtomicReference<>();
    private final AtomicInteger callbacksStarted = new AtomicInteger(), callbacksFinished = new AtomicInteger();
    private final AtomicInteger foreignCallbacks = new AtomicInteger(), callbacksDuringInvocation = new AtomicInteger();
    private final long[] gcBefore = new long[COLLECTIONS], gcAfter = new long[COLLECTIONS];
    private final long[] gcStart = new long[COLLECTIONS], gcEnd = new long[COLLECTIONS], gcUTC = new long[COLLECTIONS];
    private final long[] heartbeatBefore = new long[COLLECTIONS], heartbeatAfter = new long[COLLECTIONS];
    private final Throwable[] gcErrors = new Throwable[COLLECTIONS];
    private final boolean[] sentinelChecks = new boolean[COLLECTIONS];
    private final long[] nativeCounts = new long[8];
    private final AtomicInteger rawJavaCallbacks = new AtomicInteger(), childThreadsStarted = new AtomicInteger(), childThreadsFinished = new AtomicInteger();
    private NSOperationQueue queue;
    private volatile boolean stop, collecting;
    private volatile int collectionEpoch;
    private volatile Thread rawChild;
    private long started, startedUTC, nextCallbackBatch, cleanupDeadline, initialGC, initialHeartbeat, finalHeartbeat;
    private int gcRuns, callbacksEnqueued, pauses, discovery, markers;
    private boolean written, stopping, nativeStarted, nativeStopped, finalSentinel, graphsValid;
    private String runtimeBefore;
    private Throwable failure;

    public IOSGCContractProbe() {}
    @Override public void create() {
        started = System.nanoTime(); startedUTC = System.currentTimeMillis();
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            require("1".equals(System.getenv("CP_IOS_GC_PROBE")), "GC contract requires exact CP_IOS_GC_PROBE=1");
            require(NSThread.isCurrentThreadMainThread(), "GC contract requires the native main thread");
            IOSDiagnostics.beginRun(); Files.deleteIfExists(IOSDiagnostics.documents().resolve("ios-gc-contract.json"));
            runtimeBefore = IOSGCRuntime.snapshotJson();
            discovery = IOSGCRuntime.taskThreadDiscovery(); markers = IOSGCRuntime.markerHelpers();
            initialGC = GC.getCount(); active = this; startNative(); nativeStarted = true; initialHeartbeat = heartbeat();
            require(validateNativeSentinel(), "Initial native JNI global-reference sentinel failed");
            queue = new NSOperationQueue(); queue.setMaxConcurrentOperationCount(2);
            for (int i = 0; i < workers.length; i++) {
                Worker worker = workers[i] = new Worker(i);
                worker.thread = new Thread(worker, "CoronaPoker-GC-contract-" + i);
                worker.thread.setDaemon(true); worker.thread.start();
            }
        } catch (Throwable error) { beginStop(error); }
    }
    @Override public void render() {
        ScreenUtils.clear(0.025f, 0.035f, 0.055f, 1f);
        if (written) return;
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            long now = System.nanoTime();
            if (stopping) { if (allStopped() || now >= cleanupDeadline) finish(); return; }
            require(now - started < 29 * SECOND, "GC contract exceeded its bounded execution time");
            if (workerError.get() != null) { beginStop(workerError.get()); return; }
            if (now >= nextCallbackBatch && queue.getOperationCount() < 16) {
                nextCallbackBatch = now + 100_000_000L;
                for (int i = 0; i < 4; i++) { final int id = callbacksEnqueued++; queue.addOperation(() -> foreignCallback(id)); }
            }
            if (gcRuns < COLLECTIONS && stackGraphsReadyAndChecked(gcRuns)
                    && now - started >= (2L + 4L * gcRuns) * SECOND) {
                int index = gcRuns++;
                gcBefore[index] = GC.getCount(); heartbeatBefore[index] = heartbeat();
                gcUTC[index] = System.currentTimeMillis(); gcStart[index] = System.nanoTime(); collecting = true;
                try { collectFullGC(); } catch (Throwable error) { gcErrors[index] = error; }
                finally { collecting = false; gcEnd[index] = System.nanoTime(); heartbeatAfter[index] = heartbeat(); gcAfter[index] = GC.getCount(); }
                sentinelChecks[index] = validateNativeSentinel();
                require(gcErrors[index] == null && gcAfter[index] > gcBefore[index], "Diagnostic full collection did not advance native count");
                require(sentinelChecks[index], "JNI global-reference graph was damaged after collection");
                collectionEpoch = index + 1;
            }
            if (now - started >= 24 * SECOND) beginStop(null);
        } catch (Throwable error) { beginStop(error); }
    }
    private void foreignCallback(int id) {
        callbacksStarted.incrementAndGet();
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            require(!NSThread.isCurrentThreadMainThread(), "NSOperationQueue callback unexpectedly used the main thread");
            foreignCallbacks.incrementAndGet();
            if (collecting) callbacksDuringInvocation.incrementAndGet();
            if (stop) return;
            Node[] graph = graph(32, 0x4000L + id);
            long until = System.nanoTime() + 20_000_000L;
            do { verify(graph, 0x4000L + id); Thread.sleep(1); } while (!stop && System.nanoTime() < until);
            verify(graph, 0x4000L + id);
        } catch (InterruptedException interrupted) { if (!stop) workerError.compareAndSet(null, interrupted); }
        catch (Throwable error) { workerError.compareAndSet(null, error); }
        finally { callbacksFinished.incrementAndGet(); }
    }
    /** Called via JNI only after a fresh raw pthread's successful attachment. */
    public static void foreignThreadCallback(int id) {
        IOSGCContractProbe probe = active;
        if (probe == null) throw new IllegalStateException("Native callback has no active GC contract");
        probe.rawJavaCallbacks.incrementAndGet();
        try (NSAutoreleasePool pool = new NSAutoreleasePool()) {
            require(!NSThread.isCurrentThreadMainThread(), "Raw native callback unexpectedly used the main thread");
            if (probe.stop) return;
            Node[] graph = graph(64, 0x8000L + id);
            Thread child = new Thread(() -> {
                try { verify(graph, 0x8000L + id); probe.childThreadsFinished.incrementAndGet(); }
                catch (Throwable error) { probe.workerError.compareAndSet(null, error); }
            }, "CoronaPoker-GC-contract-child");
            child.setDaemon(true); probe.rawChild = child; probe.childThreadsStarted.incrementAndGet(); child.start();
            child.join(2000);
            require(!child.isAlive(), "Child Java thread from an attached native callback did not finish in time");
            verify(graph, 0x8000L + id);
            long until = System.nanoTime() + 20_000_000L;
            do { verify(graph, 0x8000L + id); Thread.sleep(1); } while (!probe.stop && System.nanoTime() < until);
        } catch (Throwable error) { probe.workerError.compareAndSet(null, error); }
    }
    private final class Worker implements Runnable {
        final int id;
        final Node[] roots;
        final Object[] retained = new Object[32];
        final long[] localGCCounts = new long[COLLECTIONS];
        Thread thread;
        volatile long iterations, checks;
        volatile boolean ended;
        volatile boolean localReady;
        volatile int localEpochVerified;
        volatile long localChecks;
        Worker(int id) { this.id = id; roots = graph(256, 0x1000L + id); }
        @Override public void run() {
            // Intentionally never stored in this Worker, the Application, a
            // retained churn slot, or any other heap field outside this graph.
            // Only this Java thread's live local/register roots retain it.
            Node[] localRoots = null;
            try {
                localRoots = graph(256, 0x2000L + id);
                verify(localRoots, 0x2000L + id); localChecks++; localReady = true;
                while (!stop && System.nanoTime() - started < 28 * SECOND) {
                    int epoch = collectionEpoch;
                    if (epoch > localEpochVerified) {
                        verify(localRoots, 0x2000L + id); localChecks++;
                        localGCCounts[epoch - 1] = GC.getCount(); localEpochVerified = epoch;
                    }
                    long turn = iterations;
                    Object[] pointers = new Object[12];
                    for (int i = 0; i < 10; i++) pointers[i] = roots[(int) ((turn + i) & 255)];
                    byte[] bytes = new byte[1024]; bytes[0] = (byte) turn; bytes[1023] = (byte) ~turn;
                    long[] words = new long[16]; words[0] = turn; words[15] = ~turn;
                    pointers[10] = bytes; pointers[11] = words; retained[(int) (turn & 31)] = pointers;
                    require(bytes[0] == (byte) turn && bytes[1023] == (byte) ~turn && words[15] == ~turn, "Worker fresh allocation was damaged");
                    if ((turn & 15) == 0) { verify(roots, 0x1000L + id); checks++; }
                    iterations = turn + 1; Thread.sleep(2);
                }
                verify(roots, 0x1000L + id); checks++;
                verify(localRoots, 0x2000L + id); localChecks++;
            } catch (InterruptedException interrupted) {
                try { verify(roots, 0x1000L + id); checks++; verify(localRoots, 0x2000L + id); localChecks++; }
                catch (Throwable error) { workerError.compareAndSet(null, error); }
                if (!stop) workerError.compareAndSet(null, interrupted);
            } catch (Throwable error) { workerError.compareAndSet(null, error); }
            finally { ended = true; }
        }
    }
    private boolean stackGraphsReadyAndChecked(int epoch) {
        for (Worker worker : workers) if (worker == null || !worker.localReady || worker.localEpochVerified != epoch) return false;
        return true;
    }
    private static final class Node {
        final long tag;
        final long[] values = new long[64];
        final Node[] links = new Node[4];
        Node(long tag) { this.tag = tag; for (int i = 0; i < values.length; i++) values[i] = tag ^ (i * 0x100000001b3L); }
    }
    private static Node[] graph(int size, long seed) {
        Node[] roots = new Node[size];
        for (int i = 0; i < size; i++) roots[i] = new Node(seed + i);
        for (int i = 0; i < size; i++) for (int j = 0; j < 4; j++) roots[i].links[j] = roots[(i + j * 17) % size];
        return roots;
    }
    private static void verify(Node[] roots, long seed) {
        for (int i = 0; i < roots.length; i++) {
            Node node = roots[i]; require(node != null && node.tag == seed + i, "Strong graph node identity changed");
            for (int j = 0; j < 4; j++) require(node.links[j] == roots[(i + j * 17) % roots.length], "Strong graph link changed");
            for (int j = 0; j < 64; j++) require(node.values[j] == (node.tag ^ (j * 0x100000001b3L)), "Strong graph primitive payload changed");
        }
    }
    private void beginStop(Throwable error) {
        if (failure == null && error != null) failure = error;
        if (stopping || written) return;
        stopping = true; stop = true; cleanupDeadline = System.nanoTime() + 3 * SECOND;
        for (Worker worker : workers) if (worker != null && worker.thread != null) worker.thread.interrupt();
        try { if (queue != null) queue.cancelAllOperations(); } catch (Throwable problem) { if (failure == null) failure = problem; }
        if (nativeStarted) {
            try { finalSentinel = validateNativeSentinel(); } catch (Throwable problem) { if (failure == null) failure = problem; }
            try { finalHeartbeat = heartbeat(); } catch (Throwable problem) { if (failure == null) failure = problem; }
            try { nativeStopped = stopNative(); } catch (Throwable problem) { if (failure == null) failure = problem; }
        }
    }
    private boolean allStopped() {
        for (Worker worker : workers) if (worker != null && worker.thread != null && worker.thread.isAlive()) return false;
        Thread child = rawChild; if (child != null && child.isAlive()) return false;
        try { return queue == null || queue.getOperationCount() == 0; }
        catch (Throwable error) { if (failure == null) failure = error; return false; }
    }
    private void finish() {
        if (written) return;
        boolean stopped = allStopped();
        graphsValid = true;
        try {
            for (Worker worker : workers) {
                require(worker != null && worker.ended && worker.iterations >= 16 && worker.checks > 0, "A Java worker did not complete its live graph checks");
                require(worker.localReady && worker.localEpochVerified == COLLECTIONS && worker.localChecks >= COLLECTIONS + 1,
                        "A stack-only worker graph was not validated after every explicit GC epoch");
                for (int i = 0; i < COLLECTIONS; i++) require(worker.localGCCounts[i] >= gcAfter[i], "Stack-only graph check preceded its GC epoch");
                verify(worker.roots, 0x1000L + worker.id);
            }
        } catch (Throwable error) { graphsValid = false; if (failure == null) failure = error; }
        if (workerError.get() != null && failure == null) failure = workerError.get();
        try { if (nativeStarted) nativeCounters(nativeCounts); } catch (Throwable error) { if (failure == null) failure = error; }
        boolean passed = failure == null && stopped && nativeStopped && graphsValid && finalSentinel
                && gcRuns == COLLECTIONS && GC.getCount() > initialGC && foreignCallbacks.get() >= 16
                && callbacksStarted.get() == callbacksFinished.get() && finalHeartbeat > initialHeartbeat
                && nativeCounts[0] >= 128 && nativeCounts[0] == nativeCounts[1] && nativeCounts[1] == nativeCounts[2]
                && nativeCounts[2] == nativeCounts[3] && nativeCounts[3] == nativeCounts[4] && nativeCounts[5] == 0
                && rawJavaCallbacks.get() == nativeCounts[2] && childThreadsStarted.get() >= 128
                && childThreadsStarted.get() == childThreadsFinished.get();
        written = true;
        String snapshot = null;
        try { snapshot = IOSGCRuntime.snapshotJson(); } catch (Throwable error) { passed = false; if (failure == null) failure = error; }
        StringBuilder out = new StringBuilder(16384);
        out.append("{\"probe\":\"ios-gc-runtime-contract-v1\",\"runID\":").append(quote(runID))
                .append(",\"completed\":true,\"passed\":").append(passed)
                .append(",\"gameInitialized\":false,\"audioInitialized\":false,\"normalScenePerformance\":false")
                .append(",\"scope\":\"Isolated GC correctness and native-thread observation; forced GC invocation duration and heartbeat progress are not exact STW timing, audio acceptance, or normal scene performance. JNI sentinel has no intentional Java-side root; conservative stale machine roots cannot be excluded.\"")
                .append(",\"collectionAPI\":\"BDW GC_gcollect via diagnostic JNI\",\"taskThreadDiscovery\":").append(discovery)
                .append(",\"markerHelpers\":").append(markers).append(",\"runtimeBefore\":").append(runtimeBefore == null ? "null" : runtimeBefore)
                .append(",\"runtimeAfter\":").append(snapshot == null ? "null" : snapshot)
                .append(",\"startedNano\":").append(started).append(",\"startedUTCMillis\":").append(startedUTC)
                .append(",\"finishedNano\":").append(System.nanoTime()).append(",\"pauses\":").append(pauses)
                .append(",\"initialGCCount\":").append(initialGC).append(",\"finalGCCount\":").append(GC.getCount())
                .append(",\"allWorkersStopped\":").append(stopped).append(",\"nativeHeartbeatStopped\":").append(nativeStopped)
                .append(",\"strongGraphsValid\":").append(graphsValid).append(",\"finalJNISentinelValid\":").append(finalSentinel)
                .append(",\"stackOnlyGraphOracle\":\"Each worker retains a separate graph only in run() locals; primitive readiness/GC-epoch acknowledgements gate the next forced collection.\"")
                .append(",\"heartbeatInitial\":").append(initialHeartbeat).append(",\"heartbeatFinal\":").append(finalHeartbeat)
                .append(",\"heartbeatProgressDuringGCRequiredForPass\":false,\"callbacksEnqueued\":").append(callbacksEnqueued)
                .append(",\"callbacksStarted\":").append(callbacksStarted.get()).append(",\"callbacksFinished\":").append(callbacksFinished.get())
                .append(",\"foreignCallbacks\":").append(foreignCallbacks.get()).append(",\"callbackEntriesWhileInvocationFlagSet\":").append(callbacksDuringInvocation.get())
                .append(",\"rawThreadTarget\":256,\"rawThreadMinimumForPass\":128,\"rawThreadsCreated\":").append(nativeCounts[0])
                .append(",\"rawThreadsAttached\":").append(nativeCounts[1]).append(",\"rawNativeCallbacksCompleted\":").append(nativeCounts[2])
                .append(",\"rawThreadsDetached\":").append(nativeCounts[3]).append(",\"sentinelChecksOnFreshAttachedThreads\":").append(nativeCounts[4])
                .append(",\"crossDetachSentinelChecks\":").append(Math.max(0, nativeCounts[4] - 1)).append(",\"rawNativeErrors\":").append(nativeCounts[5])
                .append(",\"rawCoordinatorExited\":").append(nativeCounts[6] != 0).append(",\"rawJavaCallbacks\":").append(rawJavaCallbacks.get())
                .append(",\"childJavaThreadsStarted\":").append(childThreadsStarted.get()).append(",\"childJavaThreadsFinished\":").append(childThreadsFinished.get())
                .append(",\"workerError\":").append(quote(stack(workerError.get()))).append(",\"error\":").append(quote(stack(failure)))
                .append(",\"workers\":[");
        for (int i = 0; i < workers.length; i++) {
            if (i != 0) out.append(','); Worker worker = workers[i];
            if (worker == null) out.append("null");
            else out.append("{\"id\":").append(i).append(",\"iterations\":").append(worker.iterations)
                    .append(",\"checks\":").append(worker.checks).append(",\"ended\":").append(worker.ended)
                    .append(",\"stackGraphReady\":").append(worker.localReady).append(",\"stackGraphEpochVerified\":").append(worker.localEpochVerified)
                    .append(",\"stackGraphChecks\":").append(worker.localChecks).append(",\"stackGraphGCCounts\":")
                    .append(java.util.Arrays.toString(worker.localGCCounts)).append('}');
        }
        out.append("],\"collections\":[");
        for (int i = 0; i < gcRuns; i++) {
            if (i != 0) out.append(',');
            out.append("{\"index\":").append(i).append(",\"startNano\":").append(gcStart[i]).append(",\"endNano\":").append(gcEnd[i])
                    .append(",\"startUTCMillis\":").append(gcUTC[i]).append(",\"countBefore\":").append(gcBefore[i]).append(",\"countAfter\":").append(gcAfter[i])
                    .append(",\"heartbeatBefore\":").append(heartbeatBefore[i]).append(",\"heartbeatAfter\":").append(heartbeatAfter[i])
                    .append(",\"jniSentinelValid\":").append(sentinelChecks[i]).append(",\"error\":").append(quote(stack(gcErrors[i]))).append('}');
        }
        IOSDiagnostics.report("ios-gc-contract.json", out.append("]}").toString());
        if (stopped && nativeStopped) active = null;
        if (queue != null && stopped) { queue.dispose(); queue = null; }
    }
    private void finishOnLifecycle(Throwable error) {
        beginStop(error);
        while (!written && !allStopped() && System.nanoTime() < cleanupDeadline) {
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        finish();
    }
    @Override public void pause() { pauses++; if (!written) finishOnLifecycle(new IllegalStateException("GC contract interrupted by pause")); }
    @Override public void dispose() { if (!written) finishOnLifecycle(new IllegalStateException("GC contract disposed before completion")); }
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
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
