package com.tonikelope.coronapoker.ios;

/** Optional offline visual isolation; normal launches retain both effects. */
public final class AllInIsolation {
    public static final String MODE = System.getenv("CP_IOS_ALLIN_ISOLATION");
    public static final boolean GIF_OFF = "gif-off".equals(MODE);
    public static final boolean FIRE_OFF = "fire-off".equals(MODE);
    static {
        if ((GIF_OFF || FIRE_OFF) && (!"offline".equals(System.getenv("CP_IOS_SCENARIO"))
                || !String.valueOf(System.getenv("CP_IOS_RUN_ID")).startsWith("allin-")))
            throw new IllegalStateException("ALL IN isolation is limited to the offline validation room");
    }
    private AllInIsolation() {}
}
