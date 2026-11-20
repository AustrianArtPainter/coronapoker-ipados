package com.tonikelope.coronapoker.ios;

/** Diagnostic paint gate; game/event/animation-clock updates remain in the caller. */
public final class TablePaintProbe {
    public static final boolean ENABLED = "off".equals(System.getenv("CP_IOS_TABLE_PAINT_TEST"));
    public static long skippedFrames;
    private static int warmFrames;
    static {
        if (ENABLED && (!"offline".equals(System.getenv("CP_IOS_SCENARIO"))
                || !String.valueOf(System.getenv("CP_IOS_RUN_ID")).startsWith("table-paint-")
                || SilentAudio.ENABLED || MockCardCrypto.ENABLED || InstantCardProbe.ENABLED))
            throw new IllegalStateException("Paint isolation requires the offline probe with real crypto, audio and animation clocks");
    }
    public static boolean skip(boolean ready) {
        if (!ENABLED || !ready || ++warmFrames <= 180) return false;
        skippedFrames++;
        return true;
    }
    private TablePaintProbe() {}
}
