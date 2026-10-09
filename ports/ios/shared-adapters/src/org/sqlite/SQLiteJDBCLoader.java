package org.sqlite;

import com.tonikelope.coronapoker.ios.NativeSQLite;

/** iOS override: native functions are linked into the app; no library extraction. */
public class SQLiteJDBCLoader {
    private static boolean initialized;
    public static synchronized boolean initialize() throws Exception {
        if (!initialized) {
            if (!NativeSQLite.initialize()) throw new IllegalStateException("Static SQLite JNI initialization failed");
            if (!"3.51.1".equals(NativeSQLite.version())) throw new IllegalStateException("Unexpected SQLite engine version");
            initialized = true;
        }
        return true;
    }
    public static boolean isNativeMode() throws Exception { return initialize(); }
    public static int getMajorVersion() { return 3; }
    public static int getMinorVersion() { return 51; }
    public static String getVersion() { return "3.51.1.0"; }
}
