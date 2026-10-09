package com.tonikelope.coronapoker.gdx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;

/** Wall-clock dialog deadlines retain the original timeout result while GL sleeps. */
final class GdxMobileDialogs {
    private static final Map<GdxTableDialog, Clock> CLOCKS = new IdentityHashMap<>();
    private static final Executor COMPLETIONS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "coronapoker-ios-dialog-result"); thread.setDaemon(true); return thread;
    });
    private static volatile boolean background;
    static {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "coronapoker-ios-dialog-deadline"); thread.setDaemon(true); return thread;
        });
        timer.scheduleWithFixedDelay(GdxMobileDialogs::tick, 50, 50, TimeUnit.MILLISECONDS);
    }
    private GdxMobileDialogs() { }
    static void opened(GdxTableDialog dialog) {
        synchronized (CLOCKS) { CLOCKS.computeIfAbsent(dialog, ignored -> new Clock()); }
        dialog.result().whenComplete((ignored, failure) -> { synchronized (CLOCKS) { CLOCKS.remove(dialog); } });
    }
    static float elapsed(GdxTableDialog dialog) {
        synchronized (CLOCKS) {
            Clock clock = CLOCKS.get(dialog);
            return clock == null ? 0 : clock.elapsed();
        }
    }
    static void paused(GdxTableDialog dialog, boolean value) {
        synchronized (CLOCKS) {
            Clock clock = CLOCKS.get(dialog);
            if (clock != null) clock.pause(value);
        }
    }
    static void background(boolean value) {
        background = value;
        if (value) tick();
    }
    static boolean presentInBackground(GdxTableDialog request) {
        if (!background) return false;
        request.opened(0);
        if (request.kind() == GdxTableDialog.Kind.INFO && !request.isExternallyControlled()) COMPLETIONS.execute(request::dismiss);
        else if (request.seconds() == 0 && (request.kind() == GdxTableDialog.Kind.CONFIRM || request.isAutoAction()))
            COMPLETIONS.execute(request::dismiss);
        // ERROR and ZERO_TRUST remain explicit user gates. Recovery decisions
        // owned by the core are not guessed by a renderer timer.
        return false; // Retain the normal UI request for foreground acknowledgement.
    }
    private static void tick() {
        if (!background) return;
        boolean paused = GdxTableRenderer.tablePaused();
        ArrayList<GdxTableDialog> expired = new ArrayList<>();
        synchronized (CLOCKS) {
            for (Map.Entry<GdxTableDialog, Clock> entry : CLOCKS.entrySet()) {
                GdxTableDialog dialog = entry.getKey();
                entry.getValue().pause(paused);
                if (!dialog.complete() && !dialog.isExternallyControlled()
                        && (dialog.expired(0) || dialog.isAutoAction())) expired.add(dialog);
            }
        }
        for (GdxTableDialog dialog : expired)
            COMPLETIONS.execute(dialog.isAutoAction() ? dialog::dismiss : dialog::timeout);
    }
    private static final class Clock {
        long previous = System.nanoTime();
        long elapsed;
        boolean paused;
        float elapsed() {
            long now = System.nanoTime();
            if (!paused) elapsed += Math.max(0, now - previous);
            previous = now;
            return elapsed / 1_000_000_000f;
        }
        void pause(boolean value) { elapsed(); paused = value; }
    }
}
