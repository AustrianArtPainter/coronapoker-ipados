#!/usr/bin/env python3
"""Run the actual diagnostics render method with the real recorder and host JNI clock.

Only platform collaborators are stubbed. The render body is mechanically extracted
from IOSDiagnostics.java, including its nested finally and original catch/rethrow.
No App, AOT, simulator, audio or device work is performed.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess

from prepare_game_sources import replace_method
from toolchain import jdk_home


PORT = Path(__file__).resolve().parents[1]
OUT = PORT / "build/frame-attribution-shell-tests"
PACKAGE = "com/tonikelope/coronapoker/ios"
SOURCE = PORT / "shared-adapters/src" / PACKAGE / "IOSDiagnostics.java"
HELPER = PORT / "shared-adapters/src" / PACKAGE / "IOSFrameAttribution.java"

SHELL = r'''package com.tonikelope.coronapoker.ios;
import java.nio.file.Path;
interface ApplicationListener { void render(); }
public final class IOSDiagnostics implements ApplicationListener {
    private final ApplicationListener delegate;
    private int frames, vertexProbeFrames;
    static int failures, failureStage = -99;
    static Throwable failureValue;
    IOSDiagnostics(ApplicationListener value) { delegate = value; }
    static void failure(Throwable value) {
        failures++; failureValue = value;
        failureStage = IOSFrameAttribution.currentStage();
    }
    static Path documents() { throw new AssertionError("Unexpected screenshot path"); }
    static void report(String name, String value) { throw new AssertionError("Unexpected screenshot report"); }
__ACTUAL_RENDER__
}
class IOSPerformance {
    static long beginFrame() { return 0; }
    static long beginAllocation() { return 0; }
    static void endAllocation(String name, long value) {}
    static void endFrame(long value) {}
}
class VertexUploadProbe {
    static final boolean ENABLED = false;
    static void run() { throw new AssertionError("Unexpected upload probe"); }
}
class IOSMusic {
    static int samples;
    static void sampleClockEvidence() {
        samples++;
        if (IOSFrameAttribution.currentStage() != -1) throw new AssertionError("Clock sample inside render");
    }
}
class Pixmap { void dispose() {} }
class ScreenUtils {
    static Pixmap getFrameBufferPixmap(int x, int y, int width, int height) {
        throw new AssertionError("Unexpected screenshot");
    }
}
class PixmapIO { static void writePNG(FileHandle file, Pixmap image, int compression, boolean flip) {} }
class FileHandle {}
class Graphics { int getBackBufferWidth() { return 1; } int getBackBufferHeight() { return 1; } }
class Files { FileHandle absolute(String path) { return new FileHandle(); } }
class Gdx { static final Graphics graphics = new Graphics(); static final Files files = new Files(); }
'''

EVIDENCE = r'''package com.tonikelope.coronapoker.gdx;
import com.tonikelope.coronapoker.ios.IOSFrameAttribution;
public final class IOSFrameEvidence {
    public static final boolean ATTRIBUTION_ENABLED = IOSFrameAttribution.ENABLED;
    public static int ends;
    public static long begin() {
        long wall = System.nanoTime();
        if (ATTRIBUTION_ENABLED) {
            IOSFrameAttribution.begin(wall);
            IOSFrameAttribution.stage(IOSFrameAttribution.SHELL_PREFIX);
        }
        return wall;
    }
    public static void end(long start) {
        ends++;
        if (ATTRIBUTION_ENABLED) IOSFrameAttribution.end(System.nanoTime());
    }
}
'''

PROBE = r'''package com.tonikelope.coronapoker.ios;
import java.lang.reflect.Method;
import com.tonikelope.coronapoker.gdx.IOSFrameEvidence;
public final class FrameAttributionShellProbe {
    public static void main(String[] args) throws Exception {
        System.load(args[1]);
        final String mode = args[0];
        final boolean disabled = mode.equals("disabled");
        final boolean child = mode.equals("child") || mode.equals("throw-child");
        final boolean throwing = mode.startsWith("throw-");
        final RuntimeException expected = new RuntimeException("delegate sentinel");
        IOSDiagnostics shell = new IOSDiagnostics(new ApplicationListener() {
            @Override public void render() {
                int prefix = disabled ? -1 : IOSFrameAttribution.SHELL_PREFIX;
                if (IOSFrameAttribution.currentStage() != prefix) throw new AssertionError("Missing shell prefix");
                if (child) IOSFrameAttribution.stage(IOSFrameAttribution.TABLE_PRELUDE);
                if (throwing) throw expected;
            }
        });
        RuntimeException observed = null;
        try { shell.render(); } catch (RuntimeException value) { observed = value; }
        if (observed != (throwing ? expected : null)) throw new AssertionError("Exception identity changed");
        if (IOSDiagnostics.failures != (throwing ? 1 : 0)) throw new AssertionError("Failure handler count");
        if (throwing && (IOSDiagnostics.failureValue != expected
                || IOSDiagnostics.failureStage != IOSFrameAttribution.SHELL_TAIL))
            throw new AssertionError("Delegate finally did not set tail before failure reporting");
        if (IOSFrameEvidence.ends != 1 || IOSMusic.samples != 1)
            throw new AssertionError("Render finally did not execute exactly once");
        if (IOSFrameAttribution.currentStage() != -1) throw new AssertionError("Stage left open");
        Method calls = IOSFrameAttributionProbe.class.getDeclaredMethod("stubCalls");
        calls.setAccessible(true);
        long nativeCalls = ((Number) calls.invoke(null)).longValue();
        long expectedCalls = disabled ? 0 : child ? 6 : 5;
        if (nativeCalls != expectedCalls) throw new AssertionError("Native clock reads: " + nativeCalls);
        IOSFrameAttribution.freeze();
        System.out.println("{\"mode\":\"" + mode + "\",\"passed\":true,\"nativeClockCalls\":" + nativeCalls
                + ",\"endCalls\":" + IOSFrameEvidence.ends + ",\"failureStage\":" + IOSDiagnostics.failureStage + "}");
    }
}
'''


def main():
    source = SOURCE.read_text()
    captured = []
    replace_method(source, "    @Override public void render()", lambda value: captured.append(value) or value)
    if len(captured) != 1:
        raise AssertionError("Actual diagnostics render method missing or duplicated")
    actual = captured[0]
    src = OUT / "src"
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, content in {
        PACKAGE + "/IOSDiagnostics.java": SHELL.replace("__ACTUAL_RENDER__", actual),
        PACKAGE + "/FrameAttributionShellProbe.java": PROBE,
        "com/tonikelope/coronapoker/gdx/IOSFrameEvidence.java": EVIDENCE,
    }.items():
        path = src / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        paths.append(path)
    java = jdk_home()
    subprocess.run([str(java / "bin/javac"), "--release", "17", "-d", str(classes),
                    str(HELPER), str(PORT / "tools/IOSFrameAttributionProbe.java"),
                    *map(str, paths)], check=True)
    library = OUT / "libframe-attribution-shell-stub.dylib"
    subprocess.run(["xcrun", "clang", "-O2", "-dynamiclib", "-I", str(java / "include"),
                    "-I", str(java / "include/darwin"), str(PORT / "tools/FrameAttributionNativeStub.c"),
                    "-o", str(library)], check=True)
    reports = []
    for mode in ("normal", "child", "throw-prefix", "throw-child", "disabled"):
        env = {key: value for key, value in os.environ.items() if not key.startswith("CP_IOS_")}
        if mode != "disabled":
            env.update(CP_IOS_SCENE_EVIDENCE="1", CP_IOS_FRAME_ATTRIBUTION="1")
        run = subprocess.run([str(java / "bin/java"), "-cp", str(classes),
                              "com.tonikelope.coronapoker.ios.FrameAttributionShellProbe", mode, str(library)],
                             env=env, text=True, capture_output=True, check=True)
        reports.append(json.loads(run.stdout))
    result = {
        "passed": True,
        "modes": reports,
        "actualRenderSHA256": hashlib.sha256(actual.encode()).hexdigest(),
        "actualSourceSHA256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "recorderSHA256": hashlib.sha256(HELPER.read_bytes()).hexdigest(),
        "scope": "Mechanically extracted full IOSDiagnostics.render; actual recorder and controlled host JNI clock; platform collaborators stubbed.",
    }
    (OUT / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
