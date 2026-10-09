package com.tonikelope.coronapoker.ios;

/** JNI initialization for the statically linked, pinned SQLite JDBC native code. */
public final class NativeSQLite {
    private NativeSQLite() {}
    public static native boolean initialize();
    public static native String version();
}
