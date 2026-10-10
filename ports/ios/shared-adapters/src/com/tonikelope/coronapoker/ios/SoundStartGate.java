package com.tonikelope.coronapoker.ios;

/** Per shared sample, caller-synchronized; loops and critical cues bypass it. */
public final class SoundStartGate {
    public static final long INTERVAL_NANOS = 40_000_000L;
    private long lastStart;
    private boolean started;
    public boolean allow(long now) {
        if (started && now-lastStart < INTERVAL_NANOS) return false;
        started=true; lastStart=now; return true;
    }
    static boolean highFrequency(String path) {
        return path.endsWith("/misc/deal.wav") || path.endsWith("/misc/bet.wav");
    }
}
