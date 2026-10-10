package com.tonikelope.coronapoker.ios;

/** Falsify stale stop/pause/dispose operations after native source-slot reuse. */
public final class SoundVoiceProbe {
    private static int checks;
    private static final class Source { int changes; }
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        SoundVoiceRegistry<Source> registry = new SoundVoiceRegistry<>();
        Object shuffle = new Object(), deal = new Object();
        Source old = new Source(), current = new Source(), second = new Source();
        long first = registry.claim(shuffle, 7, old);
        long replacement = registry.claim(deal, 7, current);
        check(first != replacement);
        check(!registry.apply(shuffle, first, s -> s.changes++));
        check(!registry.apply(shuffle, replacement, s -> s.changes++));
        registry.applyAll(shuffle, s -> s.changes++);
        registry.releaseAll(shuffle, s -> s.changes++);
        check(old.changes == 0 && current.changes == 0 && registry.size() == 1);
        check(registry.apply(deal, replacement, s -> s.changes++));
        check(current.changes == 1);
        long newer = registry.claim(deal, 7, second);
        check(!registry.apply(deal, replacement, s -> s.changes++));
        check(registry.apply(deal, newer, s -> s.changes++));
        check(second.changes == 1 && current.changes == 1);
        Source parallel = new Source();
        registry.claim(shuffle, 8, parallel);
        registry.releaseAll(deal, s -> s.changes++);
        check(second.changes == 2 && parallel.changes == 0 && registry.size() == 1);
        check(!registry.apply(deal, newer, s -> s.changes++));
        for (int i = 0; i < 100_000; i++) registry.claim(deal, 7, new Source());
        check(registry.size() == 2);
        Thread[] workers = new Thread[4];
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (int n = 0; n < workers.length; n++) {
            final int slot = n + 20;
            workers[n] = new Thread(() -> {
                try {
                Object owner = new Object();
                for (int i = 0; i < 10_000; i++) {
                    long token = registry.claim(owner, slot, new Source());
                    if (!registry.apply(owner, token, s -> s.changes++)) throw new AssertionError("lost token");
                }
                registry.releaseAll(owner, s -> s.changes++);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            workers[n].start();
        }
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError("concurrent registry operation", failure.get());
        check(registry.size() == 2);
        registry.releaseAll(deal, s -> s.changes++);
        registry.releaseAll(shuffle, s -> s.changes++);
        check(registry.size() == 0 && parallel.changes == 1);
        System.out.println("PASS: " + checks + " playback ownership checks; 140,000 slot reuses");
    }
}
