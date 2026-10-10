package com.tonikelope.coronapoker.ios;

import org.robovm.rt.GC;

/** Same-state, synchronous public-renderer measurements; no render optimizations. */
public final class TableDrawProbe {
    public static final boolean ENABLED = "1".equals(System.getenv("CP_IOS_TABLE_DRAW_PROBE"));
    private static final int CALLS = 32;
    private static final StringBuilder REPORT = new StringBuilder("{\"callsPerSample\":32,\"samples\":[");
    private static int scenes;
    private static boolean first = true;
    static {
        if (ENABLED && (!MockCardCrypto.ENABLED || IOSPerformance.ENABLED || TablePaintProbe.ENABLED))
            throw new IllegalStateException("Draw measurement requires isolated mock cards, normal paint, and the continuous profiler disabled");
    }
    public static boolean capture(boolean stable) {
        return ENABLED && stable && scenes++ < 2;
    }
    public static void measure(String label, Runnable draw) {
        for (int warm = 0; warm < 4; warm++) draw.run();
        for (int pass = 0; pass < 3; pass++) {
            long allocated = GC.getTotalBytes(), started = System.nanoTime();
            for (int call = 0; call < CALLS; call++) draw.run();
            long elapsed = System.nanoTime() - started;
            long delta = GC.getTotalBytes() - allocated;
            if (!first) REPORT.append(','); first = false;
            REPORT.append("{\"scene\":").append(scenes).append(",\"label\":\"").append(label)
                    .append("\",\"pass\":").append(pass).append(",\"allocatedBytes\":").append(delta)
                    .append(",\"elapsedNanos\":").append(elapsed).append('}');
        }
    }
    public static void save() { IOSDiagnostics.report("ios-table-draw-probe.json", REPORT.toString() + "]}\n"); }
    private TableDrawProbe() {}
}
