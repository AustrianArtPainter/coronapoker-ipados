package com.tonikelope.coronapoker.gdx;

import com.tonikelope.coronapoker.table.TableSnapshot;
import java.util.List;
import java.util.Objects;

/** Render-thread-owned scene data. Rules, event ordering and barriers stay upstream. */
final class IOSSceneProjection {
    static final int CAPACITY = 10;
    private GdxTableViewState source;
    private TableSnapshot snapshot;
    private long sequence = Long.MIN_VALUE;
    private String language;
    private long revision;
    private long geometryRevision = 1;
    private int count;
    private final String[] roster = new String[CAPACITY];
    private final Readout[] readouts = new Readout[CAPACITY];
    private final Boolean[] communitySelected = new Boolean[5];

    IOSSceneProjection() {
        for (int i = 0; i < CAPACITY; i++) readouts[i] = new Readout();
    }

    void synchronize(GdxTableViewState next, String nextLanguage) {
        if (next == null) return;
        TableSnapshot current = next.snapshot();
        if (source == next && snapshot == current && sequence == next.lastSequence()
                && Objects.equals(language, nextLanguage)) return;
        source = next;
        snapshot = current;
        sequence = next.lastSequence();
        language = nextLanguage;
        revision++;
        IOSFrameEvidence.projectionBuilds++;
        // Use the original display rule at state cutover, never at paint time.
        List<TableSnapshot.PlayerSnapshot> visible = CoronaPokerGdxTable.visibleSeatPlayers(current);
        int nextCount = Math.min(CAPACITY, visible.size());
        boolean changed = count != nextCount;
        int local = -1;
        for (int i = 0; i < nextCount; i++)
            if (visible.get(i).nickname().equals(current.localNickname())) local = i;
        for (int i = 0; i < CAPACITY; i++) {
            String name = i >= nextCount ? null : visible.get(
                    local < 0 ? i : i == 0 ? local : i <= local ? i - 1 : i).nickname();
            changed |= !Objects.equals(roster[i], name);
            roster[i] = name;
            Readout readout = readouts[i];
            readout.player = name == null ? null : visible.get(
                    local < 0 ? i : i == 0 ? local : i <= local ? i - 1 : i);
            readout.holeCards = name == null ? List.of() : next.presentedHoleCards(name);
            for (int slot = 0; slot < 2; slot++) readout.holeSelected[slot] = name == null ? null
                    : next.showdownCardSelected(name, slot, false);
        }
        for (int slot = 0; slot < 5; slot++) communitySelected[slot] = next.showdownCardSelected(
                current.localNickname(), slot, true);
        count = nextCount;
        if (changed) geometryRevision++;
    }

    int count() { return count; }
    long revision() { return revision; }
    long geometryRevision() { return geometryRevision; }
    void invalidateGeometry() { geometryRevision++; }
    Readout readout(int index) { return readouts[index]; }
    TableSnapshot.PlayerSnapshot player(String nickname) {
        for (int i = 0; i < count; i++) if (Objects.equals(roster[i], nickname)) return readouts[i].player;
        return null;
    }
    List<TableSnapshot.CardSnapshot> holeCards(String nickname) {
        for (int i = 0; i < count; i++) if (Objects.equals(roster[i], nickname)) return readouts[i].holeCards;
        return null;
    }
    Boolean selected(String nickname, int slot, boolean community) {
        if (community) return communitySelected[slot];
        for (int i = 0; i < count; i++) if (Objects.equals(roster[i], nickname)) return readouts[i].holeSelected[slot];
        return null;
    }

    static final class Readout {
        final IOSSeatLabelKey labelKey = new IOSSeatLabelKey(), stackKey = new IOSSeatLabelKey();
        long revision = Long.MIN_VALUE;
        long phase = Long.MIN_VALUE;
        String action = "";
        TableSnapshot.PlayerSnapshot player;
        List<TableSnapshot.CardSnapshot> holeCards = List.of();
        final Boolean[] holeSelected = new Boolean[2];
    }
}
