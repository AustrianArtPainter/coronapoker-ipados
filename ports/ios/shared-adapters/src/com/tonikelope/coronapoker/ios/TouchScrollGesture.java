package com.tonikelope.coronapoker.ios;

/** One finger owns a pan until release/cancellation. Coordinates are logical UI units. */
public final class TouchScrollGesture {
    private int owner = -1;
    private int startX, startY;
    private float lastX, lastY;
    private boolean dragging;
    private boolean horizontal;
    private float dx, dy;

    public boolean begin(int pointer, int screenX, int screenY, float x, float y) {
        return begin(pointer, screenX, screenY, x, y, false);
    }
    public boolean begin(int pointer, int screenX, int screenY, float x, float y, boolean horizontalPan) {
        if (pointer != 0 || active()) return false;
        owner = pointer;
        startX = screenX; startY = screenY;
        lastX = x; lastY = y; dragging = false;
        horizontal = horizontalPan;
        dx = dy = 0f;
        return true;
    }
    public boolean move(int pointer, int screenX, int screenY, float x, float y) {
        dx = dy = 0f;
        if (!owns(pointer)) return false;
        long sx = (long) screenX - startX, sy = (long) screenY - startY;
        if (!dragging && (horizontal ? sx * sx + sy * sy < 12L * 12L : sy * sy < 12L * 12L)) return false;
        dragging = true;
        dx = x - lastX; dy = y - lastY;
        lastX = x; lastY = y;
        return true;
    }
    public boolean owns(int pointer) { return owner == pointer && active(); }
    public boolean active() { return owner >= 0; }
    public boolean dragging() { return dragging; }
    public float deltaX() { return dx; }
    public float deltaY() { return dy; }
    public void cancel() { owner = -1; dragging = false; dx = dy = 0f; }
    public static float offset(float current, float delta, float maximum) {
        return Math.max(0f, Math.min(Math.max(0f, maximum), current + delta));
    }
}
