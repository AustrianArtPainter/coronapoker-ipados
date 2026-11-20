package com.tonikelope.coronapoker.gdx;

import com.badlogic.gdx.math.Rectangle;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Table-owned readouts and read-only geometry; never stores hits or callbacks. */
final class IOSTableReadoutCache {
    private CoronaPokerGdxTable.LocalActionLayout actions;
    private CoronaPokerGdxTable.PreActionHudLayout preActions;
    private int actionsWidth, preActionsWidth;
    private CoronaPokerGdxTable.FinalSummaryHeaderLayout summaryHeader;
    private int summaryWidth, summaryHeight;
    private final Rectangle[] summaryNav = new Rectangle[4];

    private long clockSeconds;
    private Locale clockLocale;
    private String clock;
    private GdxGameText blindsSource, handSource;
    private String blindsLanguage, handLanguage, blinds, hand;
    private long smallBlindBits, bigBlindBits;
    private boolean ante, lastHand;
    private int increaseInterval, increaseType, increaseCount, handNumber, maximumHands;

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

    String clock(long seconds) {
        // The original String.format uses the FORMAT default, independently
        // of the selected game language. Preserve runtime locale changes.
        Locale locale = Locale.getDefault(Locale.Category.FORMAT);
        if (clock == null || clockSeconds != seconds || !Objects.equals(clockLocale, locale)) {
            clock = CoronaPokerGdxTable.formatPlayTime(seconds);
            clockSeconds = seconds;
            clockLocale = locale;
        }
        return clock;
    }

    String blinds(GdxGameText text, double smallBlind, double bigBlind, boolean ante,
            int interval, int type, int count) {
        String language = text.language();
        long sb = Double.doubleToLongBits(smallBlind), bb = Double.doubleToLongBits(bigBlind);
        if (blinds == null || blindsSource != text || !Objects.equals(blindsLanguage, language)
                || smallBlindBits != sb || bigBlindBits != bb || this.ante != ante
                || increaseInterval != interval || increaseType != type || increaseCount != count) {
            blinds = CoronaPokerGdxTable.communityBlindsText(text, smallBlind, bigBlind,
                    ante, interval, type, count);
            blindsSource = text;
            blindsLanguage = language;
            smallBlindBits = sb;
            bigBlindBits = bb;
            this.ante = ante;
            increaseInterval = interval;
            increaseType = type;
            increaseCount = count;
        }
        return blinds;
    }

    String hand(GdxGameText text, int number, int maximum, boolean last) {
        String language = text.language();
        if (hand == null || handSource != text || !Objects.equals(handLanguage, language)
                || handNumber != number || maximumHands != maximum || lastHand != last) {
            hand = CoronaPokerGdxTable.communityHandText(text, number, maximum, last);
            handSource = text;
            handLanguage = language;
            handNumber = number;
            maximumHands = maximum;
            lastHand = last;
        }
        return hand;
    }

    void clear() {
        actions = null;
        preActions = null;
        summaryHeader = null;
        Arrays.fill(summaryNav, null);
        clock = null;
        clockLocale = null;
        blindsSource = handSource = null;
        blindsLanguage = handLanguage = blinds = hand = null;
    }
}
