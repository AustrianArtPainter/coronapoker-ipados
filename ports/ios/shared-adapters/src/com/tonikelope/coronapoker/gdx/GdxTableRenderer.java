package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.Gdx;
import com.tonikelope.coronapoker.ios.BackgroundProjection;
import com.tonikelope.coronapoker.table.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original table UI with a pure ordered projection while UIKit is suspended. */
public final class GdxTableRenderer implements TableRenderer {
    private static final Executor COMPLETIONS = Executors.newCachedThreadPool(task -> {
        Thread worker = new Thread(task, "coronapoker-ios-visual-completion");
        worker.setDaemon(true); return worker;
    });
    private static final Set<GdxTableRenderer> ACTIVE = new HashSet<>();
    private static volatile boolean background;
    private final TableCommandSink commands;
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CoronaPokerGdxTable table;
    private volatile BackgroundProjection<TableVisualEvent, GdxTableViewState> projection;
    private volatile TableVisualEvent.PreparationStatus.Phase phase = TableVisualEvent.PreparationStatus.Phase.STARTING_DEALER;
    private volatile String deck;
    private volatile TableVisualEvent.CloseTable closingEvent;
    private volatile CompletableFuture<Void> closingResult;
    private boolean closingPresented;

    public GdxTableRenderer(TableCommandSink commands) { this.commands = Objects.requireNonNull(commands); }
    @Override public CompletionStage<Void> open(TableSnapshot initialState) {
        if (closed.get() || !opened.compareAndSet(false, true)) throw new IllegalStateException("Table renderer already open/closed");
        boolean openRace = IOSGameValidation.injectOpenRace();
        if (openRace) suspendAll();
        GdxTableViewState state = new GdxTableViewState(initialState);
        projection = new BackgroundProjection<>(event -> {
            state.apply(event);
            IOSGameValidation.event(event, state);
            if (event instanceof TableVisualEvent.PreparationStatus status) phase = status.phase();
            if (event instanceof TableVisualEvent.DeckChanged change) deck = change.deck();
        }, state::mobileCopy, (event, barrier) -> {
            if (event instanceof TableVisualEvent.CloseTable) {
                presentClosingSummary(); barrier.complete(null);
            } else table.acceptEvent(event, barrier);
        },
                runnable -> Gdx.app.postRunnable(runnable), COMPLETIONS, background);
        synchronized (ACTIVE) { ACTIVE.add(this); }
        if (openRace) Gdx.app.postRunnable(GdxTableRenderer::resumeAll);
        CompletableFuture<Void> ready = new CompletableFuture<>();
        try {
            GdxApplicationShell.requireActive().openTable(initialState, commands,
                created -> {
                    table = created;
                    // UIKit can resume while the table is still being created.
                    // Reconcile the projection when the GL scene becomes ready.
                    if (background) { created.mobileSuspendInput(); projection.suspend(); }
                    else projection.resume(latest -> created.mobileRestore(latest, phase, deck));
                    ready.complete(null);
                }, ready);
        } catch (Throwable failure) { ready.completeExceptionally(failure); }
        return ready;
    }
    @Override public CompletionStage<Void> render(TableVisualEvent event) {
        if (!opened.get() || closed.get() || table == null) throw new IllegalStateException("Table renderer is not open");
        Objects.requireNonNull(event);
        if (event instanceof TableVisualEvent.CloseTable close) {
            closingEvent = close; closingResult = new CompletableFuture<>();
            projection.publish(event);
            return closingResult;
        }
        return projection.publish(event);
    }
    static boolean background() { return background; }
    static boolean suspendedProjection() {
        synchronized (ACTIVE) {
            for (GdxTableRenderer renderer : ACTIVE)
                if (renderer.projection != null && renderer.projection.isSuspended()) return true;
        }
        return false;
    }
    static boolean tablePaused() {
        synchronized (ACTIVE) {
            for (GdxTableRenderer renderer : ACTIVE)
                if (renderer.projection != null) return renderer.projection.snapshot().snapshot().paused();
        }
        return false;
    }
    static void suspendAll() {
        background = true;
        GdxTableRenderer[] renderers;
        synchronized (ACTIVE) { renderers = ACTIVE.toArray(new GdxTableRenderer[0]); }
        for (GdxTableRenderer renderer : renderers) {
            if (renderer.table != null) renderer.table.mobileSuspendInput();
            if (renderer.projection != null) renderer.projection.suspend();
        }
        GdxMobileDialogs.background(true);
    }
    static void resumeAll() {
        background = false;
        GdxTableRenderer[] renderers;
        synchronized (ACTIVE) { renderers = ACTIVE.toArray(new GdxTableRenderer[0]); }
        for (GdxTableRenderer renderer : renderers) {
            if (renderer.projection != null && renderer.table != null) {
                renderer.projection.resume(state -> renderer.table.mobileRestore(state, renderer.phase, renderer.deck));
                renderer.presentClosingSummary();
            }
        }
        GdxMobileDialogs.background(false);
    }
    private void presentClosingSummary() {
        if (background || table == null || closingEvent == null || closingPresented) return;
        closingPresented = true;
        CompletableFuture<Void> visual = new CompletableFuture<>();
        transferCompletionAsync(visual, closingResult, COMPLETIONS);
        try { table.acceptEvent(closingEvent, visual); }
        catch (Throwable failure) { visual.completeExceptionally(failure); }
    }
    static void transferCompletionAsync(CompletionStage<Void> source, CompletableFuture<Void> target, Executor executor) {
        source.whenCompleteAsync((ignored, failure) -> {
            if (failure == null) target.complete(null); else target.completeExceptionally(failure);
        }, executor);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (ACTIVE) { ACTIVE.remove(this); }
        if (projection != null) projection.close();
        CompletableFuture<Void> closing = closingResult;
        if (closing != null && !closing.isDone())
            COMPLETIONS.execute(() -> closing.completeExceptionally(new CancellationException("Table closed")));
        GdxApplicationShell shell = GdxApplicationShell.active();
        if (shell != null) shell.closeTable(table);
        table = null; opened.set(false);
    }
}
