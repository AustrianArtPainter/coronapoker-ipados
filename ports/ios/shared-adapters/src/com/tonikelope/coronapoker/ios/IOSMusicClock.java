package com.tonikelope.coronapoker.ios;

/** Explicit native-clock diagnostic; absent flag creates no native state. */
final class IOSMusicClock {
    static final int SECONDS = seconds(System.getenv("CP_IOS_AUDIO_CLOCK_SECONDS"));
    static final boolean ENABLED = SECONDS != 0;
    static final int PLAY = 1, PAUSE = 2, STOP = 3, SEEK = 4, VOLUME = 5,
            LOOP = 6, PAN = 7, FINISH = 8, DECODE_ERROR = 9, DISPOSE = 10, SESSION_ACTIVE = 11;
    private IOSMusicClock() {}
    private static int seconds(String value) {
        if (value == null) return 0;
        if (!value.matches("[0-9]{1,3}")) throw new IllegalArgumentException("CP_IOS_AUDIO_CLOCK_SECONDS requires an integer from 10 to 240");
        int result = Integer.parseInt(value);
        if (result < 10 || result > 240) throw new IllegalArgumentException("CP_IOS_AUDIO_CLOCK_SECONDS requires an integer from 10 to 240");
        return result;
    }
    static void register(long player) {
        String runID = System.getenv("CP_IOS_RUN_ID");
        if (runID == null || !runID.matches("[A-Za-z0-9._-]{1,96}"))
            throw new IllegalArgumentException("Audio clock requires an explicit safe CP_IOS_RUN_ID");
        registerNative(player, IOSDiagnostics.documents().resolve("ios-music-clock-" + runID).toString(), runID, SECONDS);
    }
    static void event(long player, int kind, double value) { eventNative(player, kind, value); }
    static boolean sample(long player, long[] counters) { return sampleNative(player, counters); }
    static void detach(long player, long[] counters) { detachNative(player, counters); }
    private static native void registerNative(long player, String directory, String runID, int seconds);
    private static native void eventNative(long player, int kind, double value);
    private static native boolean sampleNative(long player, long[] counters);
    private static native void detachNative(long player, long[] counters);
}
