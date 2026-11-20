package com.tonikelope.coronapoker.ios;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.Viewport;
import java.lang.reflect.Field;

/** Host-only differential checks against the mechanically extracted old helper. */
public final class IOSKeyboardProbe {
    private static native void fractionsNative(float left, float top, float right, float bottom);
    private static native long countNative(int kind);
    private static native float[] destinationNative();
    private static native long cpuCallsNative();
    private static int cases, nativeContracts, attributionChecks;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void fractions(float[] values) {
        fractionsNative(values[0], values[1], values[2], values[3]);
    }

    private static void same(float expected, float actual, String message) {
        require(Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual), message);
    }

    private static void prefix(float[] expected, float[] actual) {
        for (int i = 0; i < 4; i++) same(expected[i], actual[i], "Native fraction " + i + " changed");
    }

    private static void nativeContract() {
        float[] first = {.1f, .2f, .3f, .4f};
        fractions(first);
        float[] original = IOSPlatform.safeAreaInsets();
        require(original.length == 4, "Original API stopped returning four values");
        prefix(first, original);
        float[] next = {-.0f, .25f, .5f, .75f};
        fractions(next);
        float[] newer = IOSPlatform.safeAreaInsets();
        require(original != newer, "Original API lost independent snapshot ownership");
        prefix(first, original);
        prefix(next, newer);
        nativeContracts += 2;

        float[] destination = {9, 9, 9, 9, -0f, 17f, Float.POSITIVE_INFINITY};
        IOSPlatform.readSafeAreaInsets(destination);
        require(destinationNative() == destination, "Native API replaced its destination");
        prefix(next, destination);
        same(-0f, destination[4], "Extra destination slot was overwritten");
        same(17f, destination[5], "Extra destination slot was overwritten");
        same(Float.POSITIVE_INFINITY, destination[6], "Extra destination slot was overwritten");
        float[] special = {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -0f};
        fractions(special);
        IOSPlatform.readSafeAreaInsets(destination);
        prefix(special, destination);
        prefix(special, IOSPlatform.safeAreaInsets());
        nativeContracts += 2;

        for (float[] bad : new float[][]{null, new float[0], new float[1], new float[3]}) {
            long queries = countNative(2);
            boolean rejected = false;
            try { IOSPlatform.readSafeAreaInsets(bad); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "Invalid destination was accepted");
            require(countNative(2) == queries, "Invalid destination performed a UIKit query");
            nativeContracts++;
        }
    }

    /** Reusable entry point for a later fit-stage attribution harness. */
    public static void runGeometryChecks() throws Exception {
        cases = 0;
        Gdx.graphics = new Gdx.Graphics();
        Viewport original = new Viewport(), current = new Viewport();
        Field bufferField = IOSKeyboard.class.getDeclaredField("SAFE_AREA_INSETS");
        bufferField.setAccessible(true);
        Object buffer = bufferField.get(null);
        require(buffer instanceof float[] && ((float[]) buffer).length == 4, "Missing four-value owned buffer");
        int[][] sizes = {{1920, 1080}, {1366, 768}, {2732, 2048}, {1, 1}, {0, 0}};
        float[][] insets = {{0, 0, 0, 0}, {.0125f, .05f, .0375f, .08f}, {.1f, 0, .2f, .15f},
                            {-.01f, .03f, .02f, 0}, {.8f, .9f, .7f, .8f}};
        float[] keyboard = {-1f, 0f, .2f, .7499f, .75f, 1.5f, Float.NaN};
        for (int[] size : sizes) for (float[] safe : insets) for (float overlap : keyboard) {
            Gdx.graphics.width = size[0]; Gdx.graphics.height = size[1];
            IOSPlatform.occlusion = overlap;
            fractions(safe);
            long old = countNative(0), out = countNative(1), queries = countNative(2);
            OriginalIOSKeyboard.fit(original);
            require(countNative(0) == old + 1 && countNative(1) == out && countNative(2) == queries + 1,
                    "Old oracle did not query the original native API once");
            IOSKeyboard.fit(current);
            require(countNative(0) == old + 1 && countNative(1) == out + 1 && countNative(2) == queries + 2,
                    "Current helper called the allocating API or skipped its live query");
            require(destinationNative() == buffer && bufferField.get(null) == buffer, "Transfer buffer identity changed");
            require(original.width == current.width && original.height == current.height
                    && original.getScreenX() == current.getScreenX() && original.getScreenY() == current.getScreenY(),
                    "Viewport geometry differs from the fixed source oracle");
            require(current.center && original.center && current.width >= 1 && current.height >= 1,
                    "Viewport center/clamp contract changed");
            require(current.updates == ++cases && original.updates == cases, "A frame skipped viewport.update");
        }
        // Identical geometry still updates the viewport; safe-area and keyboard
        // queries remain live, with no additive-offset accumulation across frames.
        int updates = current.updates;
        OriginalIOSKeyboard.fit(original); IOSKeyboard.fit(current);
        require(current.updates == updates + 1 && original.updates == current.updates, "Stable geometry was cached");
        require(original.getScreenX() == current.getScreenX() && original.getScreenY() == current.getScreenY(),
                "Repeated viewport offsets accumulated");
        cases++;
    }

    private static void inactiveFit(String state) {
        require(IOSFrameAttribution.currentStage() == -1, state + " retained an active stage");
        long calls = cpuCallsNative();
        IOSKeyboard.fit(new Viewport());
        require(cpuCallsNative() == calls, state + " fit read a CPU clock");
        attributionChecks++;
    }

    private static Object field(String name) throws Exception {
        Field field = IOSFrameAttribution.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void attribution(boolean enabled) throws Exception {
        require(IOSFrameAttribution.ENABLED == enabled, "Actual helper did not honor both explicit flags");
        inactiveFit("not-open");
        if (!enabled) {
            for (Field field : IOSFrameAttribution.class.getDeclaredFields()) {
                if (field.getType().isArray()) {
                    field.setAccessible(true);
                    require(field.get(null) == null, "Disabled helper allocated " + field.getName());
                    attributionChecks++;
                }
            }
            require(IOSFrameAttribution.snapshotJson().equals("{\"enabled\":false}"), "Disabled recorder snapshot changed");
            require(cpuCallsNative() == 0, "Normal geometry/native contracts awakened attribution getters");
            return;
        }
        require(cpuCallsNative() == 0, "Unopened enabled helper read a CPU clock");
        for (int parent : new int[]{IOSFrameAttribution.TABLE_PRELUDE, IOSFrameAttribution.FRONT_KEYBOARD}) {
            IOSFrameAttribution.begin(System.nanoTime());
            IOSFrameAttribution.stage(parent);
            Viewport viewport = new Viewport();
            viewport.duringUpdate = () -> require(IOSFrameAttribution.currentStage() == IOSFrameAttribution.KEYBOARD_FIT,
                    "Viewport.update was outside the actual fit stage");
            long before = cpuCallsNative();
            IOSKeyboard.fit(viewport);
            require(cpuCallsNative() == before + 2, "Active fit did not enter/restore with exactly two getters");
            require(IOSFrameAttribution.currentStage() == parent, "Fit lost its caller's stage");
            attributionChecks++;

            RuntimeException original = new IllegalStateException("controlled viewport failure");
            viewport.failure = original;
            before = cpuCallsNative();
            RuntimeException caught = null;
            try { IOSKeyboard.fit(viewport); }
            catch (RuntimeException failure) { caught = failure; }
            require(caught == original, "Fit swallowed or replaced the viewport exception");
            require(cpuCallsNative() == before + 2 && IOSFrameAttribution.currentStage() == parent,
                    "Exceptional fit did not restore its stage with exactly two getters");
            attributionChecks++;
            IOSFrameAttribution.end(System.nanoTime());
            IOSFrameAttribution.discard();
        }
        inactiveFit("discarded");
        IOSFrameAttribution.begin(System.nanoTime());
        IOSFrameAttribution.stage(IOSFrameAttribution.TABLE_PRELUDE);
        IOSFrameAttribution.suspend();
        inactiveFit("suspended");
        IOSFrameAttribution.begin(System.nanoTime());
        IOSFrameAttribution.stage(IOSFrameAttribution.FRONT_KEYBOARD);
        IOSFrameAttribution.freeze();
        inactiveFit("frozen");
        require((Boolean) field("frozen") && ((Long) field("stageErrors")) == 0,
                "Actual recorder lifecycle acquired stage errors");
        require(IOSFrameAttribution.snapshotJson().contains("\"frozen\":true"), "Recorder snapshot did not freeze");
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        boolean enabled = "enabled".equals(args[1]);
        require(com.tonikelope.coronapoker.gdx.IOSFrameEvidence.ATTRIBUTION_ENABLED == enabled,
                "Frame evidence guard differs from the requested fresh-JVM mode");
        nativeContract();
        runGeometryChecks();
        require(cpuCallsNative() == 0, "Closed geometry differential read an attribution clock");
        attribution(enabled);
        System.out.println("{\"passed\":true,\"geometryFrames\":" + cases + ",\"nativeContractCases\":"
                + nativeContracts + ",\"legacyCalls\":" + countNative(0) + ",\"outparamCalls\":" + countNative(1)
                + ",\"liveQueryCalls\":" + countNative(2) + ",\"attributionChecks\":" + attributionChecks
                + ",\"attributionEnabled\":" + enabled + ",\"nativeCpuCalls\":" + cpuCallsNative() + "}");
    }
}
