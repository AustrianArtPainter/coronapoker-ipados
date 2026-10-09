package com.tonikelope.coronapoker.gdx;
import com.tonikelope.coronapoker.ios.IOSPlatform;

/** Native idle policy; pressing the power button still locks the device. */
final class GdxScreenWakeLock implements AutoCloseable {
    private boolean held;
    static GdxScreenWakeLock forCurrentPlatform() { return new GdxScreenWakeLock(); }
    void acquire() { if (!held) { IOSPlatform.idleTimerDisabled(true); held = true; } }
    void release() { if (held) { IOSPlatform.idleTimerDisabled(false); held = false; } }
    @Override public void close() { release(); }
}
