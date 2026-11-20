package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.math.Rectangle;
import java.util.Arrays;

/** Table-owned readouts and read-only geometry; never stores hits or callbacks. */
final class IOSTableReadoutCache {
    private CoronaPokerGdxTable.LocalActionLayout actions;
    private CoronaPokerGdxTable.PreActionHudLayout preActions;
    private int actionsWidth, preActionsWidth;
    private CoronaPokerGdxTable.FinalSummaryHeaderLayout summaryHeader;
    private int summaryWidth, summaryHeight;
    private final Rectangle[] summaryNav = new Rectangle[4];

    CoronaPokerGdxTable.LocalActionLayout actions(float width) {
        int bits = Float.floatToIntBits(width);
        if (actions == null || actionsWidth != bits) {
            actions = CoronaPokerGdxTable.localActionLayout(width);
            actionsWidth = bits;
        }
        return actions;
    }

    CoronaPokerGdxTable.PreActionHudLayout preActions(float width) {
        int bits = Float.floatToIntBits(width);
        if (preActions == null || preActionsWidth != bits) {
            preActions = CoronaPokerGdxTable.preActionHudLayout(width);
            preActionsWidth = bits;
        }
        return preActions;
    }

    CoronaPokerGdxTable.FinalSummaryHeaderLayout summaryHeader(float width, float height) {
        int wb = Float.floatToIntBits(width), hb = Float.floatToIntBits(height);
        if (summaryHeader == null || summaryWidth != wb || summaryHeight != hb) {
            summaryHeader = CoronaPokerGdxTable.finalSummaryHeaderLayout(width, height);
            for (int i = 0; i < summaryNav.length; i++) summaryNav[i] = summaryHeader.navButton(i);
            summaryWidth = wb;
            summaryHeight = hb;
        }
        return summaryHeader;
    }

    Rectangle summaryNav(float width, float height, int index) {
        summaryHeader(width, height);
        return summaryNav[index];
    }

    void clear() {
        actions = null;
        preActions = null;
        summaryHeader = null;
        Arrays.fill(summaryNav, null);
    }
}
