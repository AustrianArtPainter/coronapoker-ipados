#!/usr/bin/env python3
"""Build the shared Java game and its mobile boundaries as an ARM64 iOS app."""
import argparse
import os
import plistlib
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from prepare_game_sources import prepare as prepare_sources
from prepare_runtime import prepare as prepare_runtime
from prepare_compiler import prepare as prepare_compiler
from build_sqlite import build as build_sqlite
from add_app_icon import add_icon
from toolchain import jdk_home, verify_downloads, game_version

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
BUILD = PORT / "build/game"
SDK = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT"
DOWNLOADS = PORT / ".cache/downloads"
JDK = jdk_home()
M2 = ROOT / ".m2/repository"

def run(*args):
    env = os.environ.copy()
    env["PATH"] = str(JDK / "bin") + ":" + env["PATH"]
    subprocess.run([str(arg) for arg in args], cwd=PORT, env=env, check=True)

def main():
    global BUILD
    parser = argparse.ArgumentParser()
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--simulator", action="store_true", help="ARM64 iOS Simulator in build/game-simulator")
    parser.add_argument("--sdk-version", help="Override the SDK version; default is the selected Xcode SDK")
    args = parser.parse_args()
    version = game_version()
    verify_downloads()
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    if args.simulator: BUILD = PORT / "build/game-simulator"
    generated = prepare_sources()
    run("java", PORT / "tools/GenerateLegacyKeys.java", ROOT / "modules/coronapoker-gdx/src/main/java/com/tonikelope/coronapoker/gdx/GdxShortcutBindings.java",
        generated / "com/tonikelope/coronapoker/ios")
    dependencies = [
        M2 / "com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
        M2 / "com/badlogicgames/gdx/gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
        M2 / "com/badlogicgames/gdx/gdx-jnigen-loader/2.5.2/gdx-jnigen-loader-2.5.2.jar",
        M2 / "org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar",
        M2 / "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
        DOWNLOADS / "bcprov-jdk18on-1.86.jar",
        DOWNLOADS / "gdx-backend-robovm-metalangle-1.14.2.jar",
        *[SDK / "lib" / name for name in ["robovm-objc.jar", "robovm-cocoatouch.jar", "robovm-bro-bridge.jar"]]]
    cp = ":".join(map(str, [*dependencies, SDK / "lib/robovm-rt.jar"]))
    adapters = PORT / "shared-adapters/src"
    overrides = {str(path.relative_to(base)) for base in [adapters, generated]
                 for path in base.rglob("*.java")}
    excluded = {"com/tonikelope/coronapoker/gdx/GdxLauncher.java", "com/tonikelope/coronapoker/gdx/GdxMacFirstThread.java"}
    sources = []
    for module in ["coronapoker-core", "coronapoker-gdx"]:
        base = ROOT / "modules" / module / "src/main/java"
        sources += [path for path in base.rglob("*.java") if str(path.relative_to(base)) not in overrides | excluded]
    sources += [path for base in [adapters, generated, PORT / "launcher/src"] for path in base.rglob("*.java")]
    classes = BUILD / "classes"
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    source_list = BUILD / "sources.args"
    source_list.write_text("\n".join('"' + str(path) + '"' for path in sources) + "\n")
    run("javac", "--release", "17", "-encoding", "UTF-8", "-cp", cp, "-d", classes, "@" + str(source_list))
    for module in ["coronapoker-core", "coronapoker-gdx"]:
        resources = ROOT / "modules" / module / "src/main/resources"
        if resources.exists(): shutil.copytree(resources, classes, dirs_exist_ok=True)
    raw, native = BUILD / "original.jar", BUILD / "native.jar"
    run("jar", "--create", "--file", raw, "-C", classes, ".")
    run("java", "-jar", DOWNLOADS / "jvmdowngrader-2.0.1-all.jar", "--noColor", "-c", "52", "downgrade", "--target", raw, "-",
        "--classpath", cp, "shade", "--prefix", "com/tonikelope/coronapoker/ios/compat", "--target", "-", native)
    if args.compile_only: return
    runtime = prepare_runtime()
    prepare_compiler()
    sqlite = build_sqlite("ios-simulator" if args.simulator else "ios")
    include = JDK / "include"
    sdk_name = "iphonesimulator" if args.simulator else "iphoneos"
    triple = "arm64-apple-ios18.0-simulator" if args.simulator else "arm64-apple-ios18.0"
    sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk_name, "--show-sdk-path"], text=True).strip()
    sdk_version = args.sdk_version or subprocess.check_output(
        ["xcrun", "--sdk", sdk_name, "--show-sdk-version"], text=True).strip()
    platform_object = BUILD / "ios_platform.o"
    run("xcrun", "--sdk", sdk_name, "clang", "-target", triple, "-isysroot", sdk_path,
        "-O2", "-fobjc-arc", "-I", include, "-I", include / "darwin", "-c", PORT / "native/ios_platform.m", "-o", platform_object)
    platform_archive = BUILD / "libcoronapoker-platform.a"
    run("xcrun", "ar", "rcs", platform_archive, platform_object)
    config = ET.Element("config")
    libs = ET.SubElement(config, "libs")
    for path in [sqlite, platform_archive]: ET.SubElement(libs, "lib", force="true").text = str(path)
    for tag, child, values in [
        # Export only our static JNI. Exporting generated GDX weak fallbacks
        # shadows the real framework JNI and recursively calls IOSGLES20.init.
        ("exportedSymbols", "symbol", ["Java_org_sqlite_core_NativeDB_*", "Java_com_tonikelope_coronapoker_ios_*"]),
        ("frameworks", "framework", ["UIKit", "Foundation", "CoreGraphics", "AVFoundation", "Security", "UniformTypeIdentifiers"]),
        ("forceLinkClasses", "pattern", ["com.tonikelope.coronapoker.ios.**", "com.tonikelope.coronapoker.gdx.IOSGdxLauncher", "org.sqlite.**", "com.badlogic.gdx.graphics.g3d.particles.**"] )]:
        parent = ET.SubElement(config, tag)
        for value in values: ET.SubElement(parent, child).text = value
    config_file = BUILD / "robovm.xml"
    ET.ElementTree(config).write(config_file, encoding="utf-8", xml_declaration=True)
    plist = BUILD / "Info.plist"
    plist.write_bytes(plistlib.dumps({"CFBundleIdentifier":"com.tonikelope.coronapoker.ios", "CFBundleExecutable":"CoronaPoker",
        "CFBundleName":"CoronaPoker", "CFBundleDisplayName":"CoronaPoker", "CFBundleVersion":"5", "CFBundleShortVersionString":version,
        "CFBundlePackageType":"APPL", "LSRequiresIPhoneOS":True, "MinimumOSVersion":"18.0", "UIDeviceFamily":[1,2], "UILaunchScreen":{},
        "UIFileSharingEnabled":True, "LSSupportsOpeningDocumentsInPlace":True,
        "UIStatusBarHidden":True, "UIViewControllerBasedStatusBarAppearance":False,
        "UIBackgroundModes":["audio"], "NSLocalNetworkUsageDescription":f"与原版 CoronaPoker {version} 电脑端联机，由本机主持牌局。",
        "NSMicrophoneUsageDescription":"录制游戏内语音消息，发送给同桌玩家。",
        "UISupportedInterfaceOrientations":["UIInterfaceOrientationLandscapeLeft","UIInterfaceOrientationLandscapeRight"]}))
    temp = BUILD / "tmp-ios"
    temp.mkdir(parents=True, exist_ok=True)
    assets = ROOT / f"modules/coronapoker-assets/target/coronapoker-assets-{version}.jar"
    bridge = SDK / "lib/robovm-bro-bridge.jar"
    native_dependencies = [native, assets, *[path for path in dependencies if path != bridge], *[DOWNLOADS / name for name in
        ["gdx-platform-1.14.2-natives-ios.jar","gdx-freetype-platform-1.14.2-natives-ios.jar"]]]
    compiler = ":".join(map(str, [PORT / ".cache/derived-compiler/compiler-fix.jar", SDK / "lib/robovm-dist-compiler.jar"]))
    app = BUILD / "CoronaPoker.app"
    run("java", "-Xss1024k", "-Xmx3g", "-cp", compiler, "org.robovm.compiler.AppCompiler", "-home", SDK,
        "-cache", PORT / ".cache/aot-boot-bridge", "-tmp", temp, "-d", app, "-o", "CoronaPoker", "-config", config_file,
        "-os", "ios", "-arch", "arm64-simulator" if args.simulator else "arm64", "-target", "ios", "-threads", "4", "-skipsign", "-sdk", sdk_version, "-plist", plist,
        # Struct/NativeObject must be visible to native bootstrap-loader lookup
        # so GC keeps the memory owned by Objective-C superclass structures.
        "-bootclasspath", str(runtime) + ":" + str(bridge), "-skiprt", "-cp", ":".join(map(str, native_dependencies)), "com.tonikelope.coronapoker.gdx.IOSGdxLauncher")
    for resources in [ROOT / "src/main/resources", ROOT / "modules/coronapoker-core/src/main/resources",
                      ROOT / "modules/coronapoker-gdx/src/main/resources"]:
        for path in resources.iterdir():
            destination = app / path.name
            if path.is_dir(): shutil.copytree(path, destination, dirs_exist_ok=True)
            else: shutil.copy2(path, destination)
    add_icon(app, sdk_name)
    print(app)

if __name__ == "__main__": main()
