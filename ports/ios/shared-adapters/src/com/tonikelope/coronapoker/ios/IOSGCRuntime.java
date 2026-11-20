package com.tonikelope.coronapoker.ios;

import org.robovm.rt.GC;

/** Read-only runtime identity. No policy setters, workers or collection hooks. */
public final class IOSGCRuntime {
    private IOSGCRuntime() {}
    /** 0=registered-only, 1=task discovery, -1=unmodified SDK without a query hook. */
    public static native int taskThreadDiscovery();
    public static native int markerHelpers();
    private static native String bundleManifestSHA256();
    private static native String bundlePolicy();

    /** Only report writers call this; never a render or audio callback. */
    public static String snapshotJson() {
        int discovery = taskThreadDiscovery();
        boolean tuning = false;
        for (String key : System.getenv().keySet()) if (key.startsWith("GC_")) tuning = true;
        return "{\"policy\":\"" + (discovery == 0 ? "registered" : discovery == 1 ? "task-threads" : "sdk-original")
                + "\",\"taskThreadDiscovery\":" + discovery + ",\"markerHelpers\":" + markerHelpers()
                + ",\"bundlePolicy\":\"" + bundlePolicy() + "\",\"bundleManifestSHA256\":\"" + bundleManifestSHA256()
                + "\",\"collectorEnvironmentOverridesPresent\":" + tuning
                + ",\"version\":" + GC.getVersion() + "}";
    }
}
