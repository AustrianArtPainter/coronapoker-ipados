package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.ios.BackgroundProjection;
import com.tonikelope.coronapoker.table.*;
import com.tonikelope.coronapoker.core.game.GameDialogSink;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

/** Race, projection-isolation and original-default checks without a GPU. */
public final class MobileLifecycleProbe {
    public static void main(String[] args) throws Exception {
        projection(); stateCopy(); dialogDeadlines();
        System.out.println("PASS mobile lifecycle: 10000 background events, stale queued frames, unfinished visual barrier, isolated projection copy, original dialog defaults, explicit trust gates");
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void projection() throws Exception {
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        ArrayList<CompletableFuture<Void>> visual = new ArrayList<>();
        ArrayList<Integer> presented = new ArrayList<>();
        int[] model = {0}, screen = {0};
        ExecutorService completion = Executors.newCachedThreadPool();
        try {
            BackgroundProjection<Integer, Integer> gate = new BackgroundProjection<>(event -> {
                require(event == model[0] + 1, "Out-of-order model event"); model[0] = event;
            }, () -> model[0], (event, barrier) -> { presented.add(event); visual.add(barrier); }, ui::add, completion, false);
            var first = gate.publish(1); ui.remove().run();
            require(!first.isDone(), "Animation prematurely released");
            var queued = gate.publish(2);
            gate.suspend(); first.get(2, TimeUnit.SECONDS); queued.get(2, TimeUnit.SECONDS);
            ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int event = 3; event <= 10002; event++) futures.add(gate.publish(event));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            require(gate.pendingCount() == 0 && ui.size() == 1, "Background accumulated render work");
            gate.resume(latest -> screen[0] = latest);
            require(screen[0] == 10002, "Resume restored stale projection");
            var next = gate.publish(10003);
            while (!ui.isEmpty()) ui.remove().run();
            require(presented.equals(List.of(1,10003)), "Old queued frame was replayed");
            visual.get(0).completeExceptionally(new IllegalStateException("Retired GPU animation"));
            require(!next.isDone(), "Retired animation completed the current barrier");
            visual.get(1).complete(null); next.get(2, TimeUnit.SECONDS);
            gate.close();
            try { gate.publish(10004); throw new AssertionError("Closed presentation accepted event"); }
            catch (IllegalStateException expected) { }
        } finally { completion.shutdownNow(); }
    }
    static void stateCopy() throws Exception {
        var player = new TableSnapshot.PlayerSnapshot("local",1000,0,0,true,false,false,false,-2,-2,0,0,
                false, TableSnapshot.Position.DEALER,"","",List.of());
        var snapshot = new TableSnapshot(1,"local",TableSnapshot.Street.SHOWDOWN,0,"",false,List.of(player),List.of());
        GdxTableViewState original = new GdxTableViewState(snapshot);
        original.apply(new TableVisualEvent.HandResult(1,"local","WIN",true,TableSnapshot.Street.SHOWDOWN,true));
        var copy = original.mobileCopy();
        int inspected = 0;
        for (var field : GdxTableViewState.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); Object left=field.get(original),right=field.get(copy);
            require(Objects.equals(left,right), "Copy lost " + field.getName());
            if (left instanceof Map || left instanceof Set) require(left != right, "Copy shared mutable " + field.getName());
            inspected++;
        }
        require(inspected == 52, "Projection fields changed");
        copy.apply(new TableVisualEvent.HandResult(2,"local","LOSE",false,TableSnapshot.Street.SHOWDOWN,false));
        require(Boolean.TRUE.equals(original.resolvedHandWinner("local")), "Background copy changed foreground result");
        require(Boolean.FALSE.equals(copy.resolvedHandWinner("local")), "Copy failed to advance");
        original.apply(new TableVisualEvent.GameClock(2,3600));
        require(copy.playTimeSeconds() != original.playTimeSeconds(), "Clock state shared between projections");
    }
    static void dialogDeadlines() throws Exception {
        var warning = new GdxTableDialog(GdxTableDialog.Kind.TIMED_WARNING,"warn",GameDialogSink.Icon.NONE,0,1);
        var optional = new GdxTableDialog(GdxTableDialog.Kind.CONFIRM,"optional",GameDialogSink.Icon.NONE,0,1);
        var mandatory = new GdxTableDialog("rebuy","",0,1,true,"",100,1000,100);
        var trust = new GdxTableDialog(GdxTableDialog.Kind.ZERO_TRUST,"trust",GameDialogSink.Icon.NONE,0,0);
        var recovery = GdxTableDialog.recovery();
        var automaticWager = GdxTableDialog.autoAction("BET");
        for(var dialog:List.of(warning,optional,mandatory,trust,recovery)) dialog.opened(0);
        GdxMobileDialogs.background(true);
        GdxMobileDialogs.presentInBackground(recovery);
        GdxMobileDialogs.presentInBackground(automaticWager);
        try {
            require(warning.result().toCompletableFuture().get(3,TimeUnit.SECONDS), "Warning timeout default changed");
            require(!optional.result().toCompletableFuture().get(3,TimeUnit.SECONDS), "Optional wager was approved in background");
            require(mandatory.result().toCompletableFuture().get(3,TimeUnit.SECONDS), "Mandatory rebuy original timeout changed");
            require(!automaticWager.result().toCompletableFuture().get(3,TimeUnit.SECONDS), "Queued AUTO wager fired in background");
            require(!trust.complete() && !recovery.complete(), "Explicit trust/core recovery gate bypassed");
        } finally { GdxMobileDialogs.background(false); trust.dismiss(); recovery.dismiss(); }
    }
}
