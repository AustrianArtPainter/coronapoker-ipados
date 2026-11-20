package com.tonikelope.coronapoker.ios;

/** A one-shot text context gesture; swipes, release, extra fingers and long pauses cancel it. */
public final class TouchTextHold {
    private boolean armed;
    private int startX, startY;
    private long started;
    public void begin(int pointer, int x, int y, long now) {
        cancel();
        if (pointer != 0) return;
        armed = true; startX = x; startY = y; started = now;
    }
    public void move(int pointer, int x, int y) {
        long dx = (long) x - startX, dy = (long) y - startY;
        if (pointer != 0 || dx * dx + dy * dy > 28L * 28L) cancel();
    }
    public boolean ready(int x, int y, boolean pressed, long now) {
        if (!armed) return false;
        if (!pressed) { cancel(); return false; }
        move(0, x, y);
        if (!armed) return false;
        long elapsed = now - started;
        if (elapsed < 650_000_000L) return false;
        cancel();
        return elapsed <= 3_000_000_000L;
    }
    public void cancel() { armed = false; }
}
