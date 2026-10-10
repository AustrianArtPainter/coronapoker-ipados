package com.tonikelope.coronapoker.ios;

import java.util.concurrent.atomic.AtomicLong;

/** Foreground diagnostic: remove card motion only, retaining real crypto and audio. */
public final class InstantCardProbe {
    public static final boolean ENABLED = "instant-cards".equals(System.getenv("CP_IOS_ANIMATION_TEST"));
    public static final AtomicLong EVENTS = new AtomicLong(), SOUNDS = new AtomicLong();
    static {
        if (ENABLED && (!"offline".equals(System.getenv("CP_IOS_SCENARIO"))
                || SilentAudio.ENABLED || MockCardCrypto.ENABLED))
            throw new IllegalStateException("Card animation probe requires offline real crypto and audio");
    }
    private InstantCardProbe() {}
}
