package com.tonikelope.coronapoker.ios;

/** Positions the inline YouTube player over its rectangle in the GDX viewport. */
public final class IOSVideo {
    private IOSVideo() {}
    public static native void show(long owner, String id, float x, float y, float width, float height, float opacity);
    public static native void hide(long owner);
}
