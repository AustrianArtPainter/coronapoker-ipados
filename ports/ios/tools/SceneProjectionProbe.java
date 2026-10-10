package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.table.TableSnapshot;
import com.tonikelope.coronapoker.table.TableVisualEvent;
import java.util.List;

/** Exercises state cutovers and shared hit/draw geometry without an iOS runtime. */
public final class SceneProjectionProbe {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static TableSnapshot.PlayerSnapshot player(String name, boolean exited) {
        return new TableSnapshot.PlayerSnapshot(name, 20, 0, 0, true, false, exited,
                false, 0, 0, 0, 0, false, TableSnapshot.Position.NONE, "", "", List.of());
    }
    public static void main(String[] args) {
        var roster = List.of(player("remote", false), player("local", true), player("departed", true));
        var snapshot = new TableSnapshot(1, "local", TableSnapshot.Street.WAITING,
                0, "", false, roster, List.of());
        var state = new GdxTableViewState(snapshot);
        var scene = new IOSSceneProjection();
        scene.synchronize(state, "es");
        check(scene.count() == CoronaPokerGdxTable.visibleSeatCount(snapshot), "Original visibility policy");
        check(scene.count() == 2, "Exited local seat retained; departed remote excluded");
        check(scene.player("local") == roster.get(1), "Local reference follows local-first seat ownership");
        check(scene.player("remote") == roster.get(0), "Remote reference follows retained roster");
        long revision = scene.revision(), geometry = scene.geometryRevision();
        for (int i = 0; i < 10_000; i++) scene.synchronize(state, "es");
        check(scene.revision() == revision, "Static paint cannot rebuild projection");
        check(scene.geometryRevision() == geometry, "Static paint cannot rebuild geometry");
        state.apply(new TableVisualEvent.GameClock(1, 100));
        scene.synchronize(state, "es");
        check(scene.revision() > revision, "Clock event advances display state");
        check(scene.geometryRevision() == geometry, "Clock cannot invalidate seat geometry");
        revision = scene.revision();
        scene.synchronize(state, "en");
        check(scene.revision() > revision, "Language cutover refreshes presentation");
        check(scene.geometryRevision() == geometry, "Language cannot move seats");
        state.apply(new TableVisualEvent.SeatRoster(2, List.of(player("local", false), player("new", false), player("remote", false))));
        scene.synchronize(state, "en");
        check(scene.count() == 3, "Arrival updates visible scene");
        check(scene.geometryRevision() > geometry, "Arrival invalidates seat geometry");
        var restored = state.mobileCopy();
        revision = scene.revision();
        scene.synchronize(restored, "en");
        check(scene.revision() > revision, "Background restored projection is a fresh cutover");
        check(scene.count() == 3, "Restored scene keeps authoritative roster");
        var left = new TableSnapshot.CardSnapshot("A_H", true, false, true);
        var right = new TableSnapshot.CardSnapshot("K_H", true, false, true);
        restored.apply(new TableVisualEvent.RevealHoleCards(3, "local", left, right, "pair"));
        scene.synchronize(restored, "en");
        var cards = scene.holeCards("local");
        check(cards.equals(restored.presentedHoleCards("local")), "Ordered reveal remains authoritative");
        boolean retained = true;
        for (int i = 0; i < 10_000; i++) retained &= scene.holeCards("local") == cards;
        check(retained, "Resting paint reuses the event-owned cards");
        restored.apply(new TableVisualEvent.HandResult(4, "local", "pair", true, TableSnapshot.Street.SHOWDOWN));
        restored.apply(new TableVisualEvent.ShowdownHighlight(5, "local", true, List.of(0), List.of(1, 2, 3)));
        scene.synchronize(restored, "en");
        check(Boolean.TRUE.equals(scene.selected("local", 0, false)), "Winning pocket retained");
        check(Boolean.FALSE.equals(scene.selected("local", 1, false)), "Losing pocket dimmed");
        check(Boolean.TRUE.equals(scene.selected("", 2, true)), "Winning community retained");
        check(Boolean.FALSE.equals(scene.selected("", 0, true)), "Other community dimmed");
        restored.apply(new TableVisualEvent.FoldHoleCards(6, "remote"));
        scene.synchronize(restored, "en");
        check(scene.holeCards("remote").equals(restored.presentedHoleCards("remote")), "Fold cannot leave stale visible cards");
        check(!scene.player("remote").active(), "Fold refreshes player state on the same cutover");
        for (float width : new float[]{1668, 1920, 2420}) {
            var hud = CoronaPokerGdxTable.localHudLayout(width);
            check(hud.stackBounds() == hud.stackBounds(), "Stack bounds are persistent");
            check(hud.investedBounds() == hud.investedBounds(), "Invested bounds are persistent");
            check(hud.stackValueBounds() == hud.stackValueBounds(), "Value glyph area is persistent");
            check(hud.bounds() == hud.bounds(), "HUD hit/draw bounds are shared");
            check(hud.stackChipCenterX() == hud.stackBounds().x + 43, "Stack anchor unchanged");
            check(hud.investedChipCenterX() == hud.investedBounds().x + hud.investedBounds().width - 43, "Invested anchor unchanged");
        }
        hotJoinCutover();
        System.out.println("PASS " + checks + " scene projection/lifecycle/geometry checks");
    }

    private static void hotJoinCutover() {
        var warm = new TableSnapshot.PlayerSnapshot("local",20,0,0,false,true,false,false,
                0,0,0,0,false,false,TableSnapshot.Position.NONE,
                TableSnapshot.Decision.NONE,TableSnapshot.ActionKind.NONE,"","",List.of(),20,0,true);
        var presentation = new TableSnapshot.PlayerPresentation(true,true,42f,true,"pair",
                List.of(1),true,true,List.of(0),List.of(0,1),TableSnapshot.RebuyPhase.REBOUGHT,10,"ALL IN");
        var cards = List.of(new TableSnapshot.CardSnapshot("A_H",true,false),
                new TableSnapshot.CardSnapshot("K_H",true,false));
        var allIn = new TableSnapshot.PlayerSnapshot("remote",0,20,20,true,false,false,false,
                1,1,0,0,true,false,TableSnapshot.Position.DEALER,
                TableSnapshot.Decision.ALL_IN,TableSnapshot.ActionKind.ALL_IN,"ALL IN","pair",cards,20,0,false,presentation);
        var folded = new TableSnapshot.PlayerSnapshot("folded",19,1,1,true,false,false,false,
                1,1,0,0,false,false,TableSnapshot.Position.NONE,
                TableSnapshot.Decision.FOLD,TableSnapshot.ActionKind.FOLD,"FOLD","",List.of(),20,0,false);
        var initial = new TableSnapshot(1,"local",TableSnapshot.Street.WAITING,0,"",false,List.of(warm),List.of());
        var incoming = new TableSnapshot(30,"local",TableSnapshot.Street.SHOWDOWN,41,"remote",true,
                List.of(warm,allIn,folded),List.of(new TableSnapshot.CardSnapshot("2_H",true,false),
                new TableSnapshot.CardSnapshot("3_H",true,false)),new TableSnapshot.TablePresentation("1/2",3,List.of(1)));
        var state = new GdxTableViewState(initial, () -> 1_000_000_000L);
        var bootstrap = new TableVisualEvent.HotJoinState(30,incoming);
        state.apply(bootstrap);
        var copied = state.mobileCopy();
        var scene = new IOSSceneProjection();
        scene.synchronize(copied,"en");
        check(copied.snapshot().equals(incoming),"Background copy preserves the complete 25.86 snapshot");
        check(scene.player("local").warming(),"WARMING UP remains spectator-owned after resume");
        check(copied.foldedThisHand("folded"),"Fold semantics are independent of active-seat membership");
        check(scene.holeCards("remote").equals(cards),"Public revealed pockets survive hot-join cutover");
        check(copied.actionKind("remote")==TableVisualEvent.PlayerAction.ActionKind.ALL_IN,"ALL IN action survives copy");
        check(copied.actionLabel("remote").equals("ALL IN"),"Canonical public label survives copy");
        check(copied.hasHandResult("remote") && copied.resolvedHandName("remote").equals("pair"),"Showdown result survives copy");
        check(copied.resolvedWonPotIndexes("remote").equals(List.of(1)) && copied.returnedSidePot("remote"),"Payout ownership survives copy");
        check(Boolean.TRUE.equals(scene.selected("remote",0,false)),"Pocket highlight survives copy");
        check(Boolean.TRUE.equals(scene.selected("",1,true)),"Board highlight survives copy");
        check(copied.sharedProgressVisible() && !copied.sharedProgressIndeterminate(),"Paused countdown replaces provisional progress");
        check(copied.runItTwicePotPrefix().equals("1/2") && copied.isRabbitCard(1),"RIT and rabbit presentation survive copy");
        check(copied.immediateRebuyAmount("remote")==10,"Rebuy state survives copy");
        copied.apply(new TableVisualEvent.HotJoinState(31,initial));
        check(copied.snapshot().equals(incoming),"Copied bootstrap guard rejects a second state reset");
        var ready=TableVisualEvent.PreparationStatus.Phase.READY;
        var starting=TableVisualEvent.PreparationStatus.Phase.STARTING_DEALER;
        check(GdxTableRenderer.preparationAfter(starting,bootstrap)==ready,"Background bootstrap dismisses preparation shield");
        check(GdxTableRenderer.preparationAfter(ready,new TableVisualEvent.PreparationStatus(32,starting))==ready,"Delayed preparation cannot block resumed table");
    }
}
