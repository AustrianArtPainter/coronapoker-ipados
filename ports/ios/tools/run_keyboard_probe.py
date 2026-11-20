#!/usr/bin/env python3
"""Compare actual Keyboard + production JNI array paths with a fixed old oracle.

Host-only Java/JNI. UIKit querying alone is replaced by controllable fractions;
the two production JNI methods are copied mechanically, not reimplemented.
No App, simulator, GPU, audio, permissions or shared generated sources are used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from prepare_game_sources import replace_method
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
ORACLE_COMMIT = "95b9ae16cde36d973ef8c2c5464451cd9936042c"
KEYBOARD = "ports/ios/shared-adapters/src/com/tonikelope/coronapoker/ios/IOSKeyboard.java"
PREFIX = "Java_com_tonikelope_coronapoker_ios_IOSPlatform_"


def extract(source, signature):
    found = []
    replace_method(source, signature, lambda value: found.append(value) or value)
    return found[0]


def prepare_fixture_sources(output):
    """Return explicit host source paths, reusable by additional diagnostic seams."""
    original = subprocess.check_output(["git", "show", ORACLE_COMMIT + ":" + KEYBOARD], cwd=ROOT, text=True)
    if original.count("public final class IOSKeyboard") != 1 or original.count("private IOSKeyboard()") != 1:
        raise RuntimeError("Fixed keyboard oracle header changed")
    oracle = original.replace("public final class IOSKeyboard", "public final class OriginalIOSKeyboard").replace(
        "private IOSKeyboard()", "private OriginalIOSKeyboard()")
    native_source = (PORT / "native/ios_platform.m").read_text()
    old = extract(native_source, "JNIEXPORT jfloatArray JNICALL " + PREFIX + "safeAreaInsets(")
    new = extract(native_source, "JNIEXPORT void JNICALL " + PREFIX + "readSafeAreaInsets(")
    native = r'''#include <jni.h>
#include <stdint.h>
static jfloat CPValues[4];
static jlong CPOldCalls, CPOutCalls, CPQueries;
static jobject CPDestination;
static void CPReadSafeAreaFractions(jfloat values[4]) {
    CPQueries++;
    for (int i=0;i<4;i++) values[i]=CPValues[i];
}
''' + old.replace(PREFIX + "safeAreaInsets", "CPProductionSafeAreaInsets", 1) + "\n" + new.replace(
        PREFIX + "readSafeAreaInsets", "CPProductionReadSafeAreaInsets", 1) + r'''
JNIEXPORT jfloatArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_safeAreaInsets(JNIEnv *env,jclass type) {
    CPOldCalls++; return CPProductionSafeAreaInsets(env,type);
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSPlatform_readSafeAreaInsets(JNIEnv *env,jclass type,jfloatArray destination) {
    CPOutCalls++;
    if(CPDestination) (*env)->DeleteGlobalRef(env,CPDestination);
    CPDestination=destination?(*env)->NewGlobalRef(env,destination):NULL;
    CPProductionReadSafeAreaInsets(env,type,destination);
}
JNIEXPORT void JNICALL Java_com_tonikelope_coronapoker_ios_IOSKeyboardProbe_fractionsNative(JNIEnv *env,jclass type,jfloat a,jfloat b,jfloat c,jfloat d) {
    CPValues[0]=a;CPValues[1]=b;CPValues[2]=c;CPValues[3]=d;
}
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSKeyboardProbe_countNative(JNIEnv *env,jclass type,jint kind) {
    return kind==0?CPOldCalls:kind==1?CPOutCalls:CPQueries;
}
JNIEXPORT jfloatArray JNICALL Java_com_tonikelope_coronapoker_ios_IOSKeyboardProbe_destinationNative(JNIEnv *env,jclass type) {
    return (jfloatArray)(*env)->NewLocalRef(env,CPDestination);
}
'''
    # Keep the actual recorder's existing controlled JNI implementation intact.
    native += "\n" + (PORT / "tools/FrameAttributionNativeStub.c").read_text() + r'''
JNIEXPORT jlong JNICALL Java_com_tonikelope_coronapoker_ios_IOSKeyboardProbe_cpuCallsNative(JNIEnv *env,jclass type) {
    return reads;
}
'''
    stubs = {
        "com/badlogic/gdx/Gdx.java": '''package com.badlogic.gdx;
public final class Gdx {
 public static Graphics graphics; public static Input input;
 public static final class Graphics {public int width,height;public int getWidth(){return width;}public int getHeight(){return height;}}
 public interface Input {void setOnscreenKeyboardVisible(boolean value);}
}''',
        "com/badlogic/gdx/utils/viewport/Viewport.java": '''package com.badlogic.gdx.utils.viewport;
public final class Viewport {
 public int width,height,updates;public boolean center;private int x,y;
 public Runnable duringUpdate;public RuntimeException failure;
 public void update(int w,int h,boolean c){width=w;height=h;center=c;updates++;x=w%7-3;y=h%11-5;
  if(duringUpdate!=null)duringUpdate.run();if(failure!=null)throw failure;}
 public int getScreenX(){return x;}public int getScreenY(){return y;}
 public void setScreenX(int value){x=value;}public void setScreenY(int value){y=value;}
}''',
        "com/tonikelope/coronapoker/gdx/IOSFrameEvidence.java": '''package com.tonikelope.coronapoker.gdx;
public final class IOSFrameEvidence {
 public static final boolean ATTRIBUTION_ENABLED="1".equals(System.getenv("CP_IOS_SCENE_EVIDENCE"))
   && "1".equals(System.getenv("CP_IOS_FRAME_ATTRIBUTION"));
}''',
        "com/tonikelope/coronapoker/ios/IOSPlatform.java": '''package com.tonikelope.coronapoker.ios;
public final class IOSPlatform {
 public static float occlusion;
 public static native float[] safeAreaInsets();public static native void readSafeAreaInsets(float[] destination);
 public static float keyboardOcclusion(){return occlusion;}
}''',
        "com/tonikelope/coronapoker/ios/OriginalIOSKeyboard.java": oracle,
    }
    files = []
    for name, content in stubs.items():
        path = output / "src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        files.append(path)
    native_path = output / "keyboard-native.c"
    native_path.write_text(native)
    return files, native_path, {"oracleCommit": ORACLE_COMMIT,
        "oracleSourceSHA256": hashlib.sha256(original.encode()).hexdigest(),
        "nativeMethodsSHA256": hashlib.sha256((old + "\n" + new).encode()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, help="New private directory; defaults to a unique ignored validation directory")
    args = parser.parse_args()
    if args.output:
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        output = Path(tempfile.mkdtemp(prefix="keyboard-outparam-", dir=PORT / "validation"))
    sources, native, provenance = prepare_fixture_sources(output)
    java = jdk_home()
    library = output / "libkeyboard-probe.dylib"
    subprocess.run(["xcrun", "clang", "-dynamiclib", "-O2", "-I", str(java / "include"),
                    "-I", str(java / "include/darwin"), str(native), "-o", str(library)], check=True)
    classes = output / "classes"
    classes.mkdir()
    helper = PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/IOSFrameAttribution.java"
    sources += [ROOT / KEYBOARD, helper, PORT / "tools/IOSKeyboardProbe.java"]
    subprocess.run([str(java / "bin/javac"), "--release", "17", "-d", str(classes), *map(str, sources)], check=True)
    reports = {}
    for mode, flags in (("normal", {}), ("scene-only", {"CP_IOS_SCENE_EVIDENCE": "1"}),
                        ("attribution-only", {"CP_IOS_FRAME_ATTRIBUTION": "1"}),
                        ("enabled", {"CP_IOS_SCENE_EVIDENCE": "1", "CP_IOS_FRAME_ATTRIBUTION": "1"})):
        env = {key: value for key, value in os.environ.items() if not key.startswith("CP_IOS_")}
        env.update(flags)
        result = subprocess.run([str(java / "bin/java"), "-cp", str(classes),
                                 "com.tonikelope.coronapoker.ios.IOSKeyboardProbe", str(library), mode],
                                env=env, capture_output=True, text=True)
        (output / (mode + ".log")).write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError("Keyboard differential/JNI checks failed (" + mode + "):\n" + result.stdout + result.stderr)
        reports[mode] = json.loads(result.stdout)
        (output / (mode + ".json")).write_text(json.dumps(reports[mode], indent=2) + "\n")
    report = dict(reports["normal"], modes=reports)
    report.update(provenance, keyboardSHA256=hashlib.sha256((ROOT / KEYBOARD).read_bytes()).hexdigest(),
                  attributionSHA256=hashlib.sha256(helper.read_bytes()).hexdigest(),
                  nativeCpuStubSHA256=hashlib.sha256((PORT / "tools/FrameAttributionNativeStub.c").read_bytes()).hexdigest(),
                  scope="Actual current/old Keyboard, production JNI array methods and real attribution helper; controlled host query/CPU JNI, not UIKit geometry, CPU timing or physical-device validation")
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(dict(report, output=str(output))))


if __name__ == "__main__":
    main()
