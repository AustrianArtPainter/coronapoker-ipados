package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.table.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Drives the real mobile table's terminal UI without a GPU or native audio. */
public final class MobileSummaryRestoreProbe {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static TableSnapshot snapshot() {
        var local = new TableSnapshot.PlayerSnapshot("local", 10, 0, 0,
                true, false, false, false, -1, -1, 0, 0, false,
                TableSnapshot.Position.NONE, "", "", List.of());
        return new TableSnapshot(1, "local", TableSnapshot.Street.PREFLOP,
                0, "", false, List.of(local), List.of());
    }
    private static TableVisualEvent.CloseTable closing(long sequence) {
        var summary = new TableSessionSummary("local", 24, 900, 1,
                TableSessionSummary.CloseReason.COMPLETED,
                List.of(new TableSessionSummary.PlayerBalance("local", 10, 10, 0)));
        return new TableVisualEvent.CloseTable(sequence, summary, TableSnapshot.Street.FINISHED);
    }
    private static CoronaPokerGdxTable table(GdxTableViewState state, List<TableCommand> commands) {
        return new CoronaPokerGdxTable(60, state, commands::add, () -> {},
                new GdxGameLogSink(), null);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    private static void present(GdxTableRenderer renderer) throws Exception {
        var method = GdxTableRenderer.class.getDeclaredMethod("presentClosingSummary");
        method.setAccessible(true);
        method.invoke(renderer);
    }
    private static void restore(boolean backgroundCompletion, int navigation) throws Exception {
        var commands = new ArrayList<TableCommand>();
        var state = new GdxTableViewState(snapshot());
        var close = closing(10);
        if (backgroundCompletion) state.apply(close);
        var table = table(new GdxTableViewState(snapshot()), commands);
        table.mobileRestore(state.mobileCopy(), TableVisualEvent.PreparationStatus.Phase.READY, null);
        var barrier = new CompletableFuture<Void>();
        var renderer = new GdxTableRenderer(commands::add);
        set(renderer, "table", table);
        set(renderer, "closingEvent", close);
        set(renderer, "closingResult", barrier);
        present(renderer); // Exercise the actual renderer entry and completion relay.
        check(field(table, "finalSummary") == close.summary(), "Display must retain the original canonical summary");
        check(table.mobileVisibleScene().equals("summary"), "Restored scene must be the summary");
        var restored = (GdxTableViewState) field(table, "liveState");
        check(restored.lastSequence() == 10 && restored.snapshot().street() == TableSnapshot.Street.FINISHED,
                "Terminal state must be applied exactly once");
        check(!barrier.isDone(), "The summary must remain visible until explicit navigation");
        present(renderer);
        check(field(table, "finalSummary") == close.summary() && !barrier.isDone(),
                "A second resume must not replay or acknowledge the summary");
        try {
            restored.apply(close);
            throw new AssertionError("Ordinary duplicate events must still be rejected");
        } catch (IllegalArgumentException expected) { checks++; }
        check(table.handleFinalSummaryNavigationAction(navigation), "Restored summary must accept explicit navigation");
        barrier.get(2, TimeUnit.SECONDS);
        check(barrier.isDone() && !barrier.isCompletedExceptionally(), "Navigation must release the terminal barrier");
        check(commands.isEmpty(), "Restoring/navigating a finished game must not submit a poker action");
    }
    public static void main(String[] args) throws Exception {
        restore(false, 0); // Ordinary foreground completion.
        restore(true, 0);  // Locked completion, then main menu.
        restore(true, 2);  // Locked completion, then statistics.
        restore(true, 3);  // Locked completion, then continue.
        var state = new GdxTableViewState(snapshot());
        state.apply(closing(10));
        var table = table(state.mobileCopy(), new ArrayList<>());
        try {
            table.mobilePresentClosingSummary(closing(9), new CompletableFuture<>());
            throw new AssertionError("A stale terminal summary must not replace a newer projection");
        } catch (IllegalStateException expected) { checks++; }
        var empty = new TableVisualEvent.CloseTable(10, TableSessionSummary.empty(), TableSnapshot.Street.FINISHED);
        var emptyState = new GdxTableViewState(snapshot());
        emptyState.apply(empty);
        var emptyTable = table(emptyState.mobileCopy(), new ArrayList<>());
        var emptyBarrier = new CompletableFuture<Void>();
        emptyTable.mobilePresentClosingSummary(empty, emptyBarrier);
        check(emptyBarrier.isDone(), "An empty original summary must still close immediately");
        System.out.println("PASS " + checks + " real mobile summary restore checks");
    }
}
