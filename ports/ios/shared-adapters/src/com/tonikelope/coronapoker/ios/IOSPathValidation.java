package com.tonikelope.coronapoker.ios;

import com.tonikelope.coronapoker.core.ApplicationMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Opt-in real-thread path/GC stress, completed before the validation room opens. */
public final class IOSPathValidation {
    private static final int THREADS = 3;
    private static final int ROUNDS = 384;
    private static final String[] VALID = {"ascii-0-_", "\u6d4b\u8bd5-\u4e2d\u6587",
            "emoji-\ud83d\ude00-\ud83c\udf89", "combining-e\u0301-\u00e9"};
    private static final String[] MALFORMED = {"high-\ud800", "low-\udc00", "middle-\ud800-end"};

    public static void runIfEnabled() throws Exception {
        if (!"1".equals(System.getenv("CP_IOS_PATH_STRESS"))) return;
        Path documents = IOSDiagnostics.documents();
        Path report = documents.resolve("ios-path-stress.json");
        Files.deleteIfExists(report);
        String run = System.getenv("CP_IOS_RUN_ID");
        Result result = exercise(documents, run == null ? "" : run,
                ApplicationMetadata.VERSION, org.robovm.rt.GC::getCount, "RoboVM");
        Files.write(report, result.json().getBytes(StandardCharsets.UTF_8));
        if (!result.error.isEmpty()) {
            throw new IllegalStateException("Native path stress failed: " + result.error);
        }
    }

    /** Injected counters allow host business-logic checks, never native GC proof. */
    public static Result exercise(Path base, String run, String version,
                                   LongSupplier gcCount, String gcSource) {
        AtomicLong roundTrips = new AtomicLong(), rejected = new AtomicLong();
        AtomicInteger gcRequests = new AtomicInteger(), completed = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>("");
        CountDownLatch ready = new CountDownLatch(THREADS), start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        Thread[] workers = new Thread[THREADS];
        long before = gcCount.getAsLong();
        for (int index = 0; index < workers.length; index++) {
            final int worker = index;
            workers[index] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    Path parent = base.resolve("path-stress").resolve("worker-" + worker);
                    String parentText = parent.toString();
                    for (int round = 0; round < ROUNDS && error.get().isEmpty(); round++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        String suffix = "-" + worker + "-" + round;
                        for (String valid : VALID) {
                            String name = valid + suffix;
                            Path child = parent.resolve(name);
                            String rendered = child.toString();
                            Path restored = Paths.get(rendered);
                            if (!rendered.equals(parentText + "/" + name)
                                    || !child.getFileName().toString().equals(name)
                                    || !restored.equals(child) || !restored.toString().equals(rendered)
                                    || !restored.getParent().equals(parent)) {
                                throw new AssertionError("Valid path roundtrip changed characters");
                            }
                            roundTrips.incrementAndGet();
                        }
                        for (String malformed : MALFORMED) {
                            String name = malformed + suffix;
                            expectInvalid(() -> parent.resolve(name));
                            rejected.incrementAndGet();
                            expectInvalid(() -> Paths.get(name));
                            rejected.incrementAndGet();
                        }
                        // Different lanes collect at different rounds while
                        // the other Java threads allocate/resolve UTF-8 paths.
                        if ((round + worker * 7) % 24 == 0) {
                            gcRequests.incrementAndGet();
                            System.gc();
                            Thread.yield();
                        }
                    }
                    completed.incrementAndGet();
                } catch (Throwable failure) {
                    // Exception messages can include the device container path.
                    // Keep the report limited to the failing lane/type.
                    error.compareAndSet("", "worker " + worker + " " + failure.getClass().getSimpleName());
                } finally {
                    finished.countDown();
                }
            }, "coronapoker-ios-path-stress-" + worker);
        }
        int started = 0, joined = 0;
        boolean interrupted = false;
        try {
            for (Thread worker : workers) { worker.start(); started++; }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                error.compareAndSet("", "Path workers did not reach the start barrier");
            }
            start.countDown();
            if (!finished.await(120, TimeUnit.SECONDS)) {
                error.compareAndSet("", "Path workers did not finish before timeout");
            }
        } catch (InterruptedException stopped) {
            interrupted = true;
            error.compareAndSet("", "Path validation was interrupted");
        } catch (Throwable failure) {
            error.compareAndSet("", "Path worker launch " + failure.getClass().getSimpleName());
        } finally {
            start.countDown();
            for (int index = 0; index < started; index++) {
                if (workers[index].isAlive() && !error.get().isEmpty()) workers[index].interrupt();
            }
            for (int index = 0; index < started; index++) {
                try { workers[index].join(10_000); }
                catch (InterruptedException stopped) {
                    interrupted = true;
                    workers[index].interrupt();
                }
                if (!workers[index].isAlive()) joined++;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        long after = gcCount.getAsLong();
        if (started != THREADS || joined != THREADS || completed.get() != THREADS) {
            error.compareAndSet("", "Not all path threads completed and joined");
        }
        if (roundTrips.get() != (long) THREADS * ROUNDS * VALID.length
                || rejected.get() != (long) THREADS * ROUNDS * MALFORMED.length * 2) {
            error.compareAndSet("", "Path checks did not complete the requested matrix");
        }
        if (gcRequests.get() <= 0 || before < 0 || after <= before) {
            error.compareAndSet("", "The measured GC counter did not advance under collection pressure");
        }
        return new Result(run, version, gcSource, roundTrips.get(), rejected.get(),
                gcRequests.get(), before, after, started, joined, completed.get(), error.get());
    }

    private static void expectInvalid(Runnable path) {
        try { path.run(); }
        catch (InvalidPathException expected) { return; }
        throw new AssertionError("Unpaired surrogate was accepted instead of REPORT");
    }

    public static final class Result {
        public final String runID, gameVersion, gcCounterSource, error;
        public final long roundTrips, invalidPathsRejected, gcBefore, gcAfter;
        public final int gcRequests, threadsStarted, threadsJoined, threadsCompleted;
        Result(String run, String version, String source, long paths, long invalid,
               int requests, long before, long after, int started, int joined,
               int completed, String error) {
            runID = run; gameVersion = version; gcCounterSource = source;
            roundTrips = paths; invalidPathsRejected = invalid; gcRequests = requests;
            gcBefore = before; gcAfter = after; threadsStarted = started;
            threadsJoined = joined; threadsCompleted = completed; this.error = error;
        }
        public String json() {
            return "{\"kind\":\"path-thread-gc-stress\",\"runID\":" + quote(runID)
                    + ",\"gameVersion\":" + quote(gameVersion) + ",\"gcCounterSource\":" + quote(gcCounterSource)
                    + ",\"roundTrips\":" + roundTrips + ",\"invalidPathsRejected\":" + invalidPathsRejected
                    + ",\"gcRequests\":" + gcRequests + ",\"gcBefore\":" + gcBefore + ",\"gcAfter\":" + gcAfter
                    + ",\"threadsStarted\":" + threadsStarted + ",\"threadsJoined\":" + threadsJoined
                    + ",\"threadsCompleted\":" + threadsCompleted + ",\"error\":" + quote(error) + "}\n";
        }
        private static String quote(String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }
    private IOSPathValidation() {}
}
