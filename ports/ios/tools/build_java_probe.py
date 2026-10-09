#!/usr/bin/env python3
"""Compile shared algorithms unchanged; downgrade bytecode only at build time."""
import argparse
import hashlib
import subprocess
from pathlib import Path
from prepare_runtime import prepare
from prepare_compiler import prepare as prepare_compiler
from build_sqlite import build as build_sqlite
import xml.etree.ElementTree as ET
import plistlib
import os
from toolchain import jdk_home, verify_downloads

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
BUILD = PORT / "build" / "java-probe"
SDK = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT"
DOWNGRADER = PORT / ".cache/downloads/jvmdowngrader-2.0.1-all.jar"
BC = PORT / ".cache/downloads/bcprov-jdk18on-1.86.jar"
JDBC = ROOT / ".m2/repository/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar"
SLF4J = ROOT / ".m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"


def run(*args):
    arguments = [str(arg) for arg in args]
    if arguments[0] == str(SDK / "bin/robovm"):
        compiler = PORT / ".cache/derived-compiler/compiler-fix.jar"
        arguments = ["java", "-Xss1024k", "-Xmx2g", "-cp", str(compiler) + ":" + str(SDK / "lib/robovm-dist-compiler.jar"),
                     "org.robovm.compiler.AppCompiler", "-home", str(SDK), *arguments[1:]]
    environment = os.environ.copy()
    environment["PATH"] = str(jdk_home() / "bin") + ":" + environment["PATH"]
    subprocess.run(arguments, cwd=PORT, env=environment, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--aot-console", action="store_true")
    parser.add_argument("--database", action="store_true")
    parser.add_argument("--ios", action="store_true")
    args = parser.parse_args()
    verify_downloads()
    if hashlib.sha256(DOWNGRADER.read_bytes()).hexdigest() != "60a5bd57846fb4be1756fb93e6f63b3d9a63c2759a63e763c44553b086326107":
        raise SystemExit("Unexpected JvmDowngrader contents")
    source_root = ROOT / "modules/coronapoker-core/src/main/java/com/tonikelope/coronapoker"
    originals = [source_root / "crypto" / (name + ".java") for name in
                 ["CryptoRandom", "Fe25519", "EdwardsPoint", "Ristretto255", "RistrettoSRA", "Dleq"]]
    originals.append(source_root / "core/network/SecureChannelCodec.java")
    sources = originals + list((PORT / "java-probe/src").rglob("*.java"))
    sources.append(PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/Ed25519Provider.java")
    sources += [source_root / "core/DatabaseService.java", source_root / "core/ApplicationService.java",
                PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/NativeSQLite.java",
                PORT / "shared-adapters/src/org/sqlite/SQLiteJDBCLoader.java"]
    classes = BUILD / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    bindings = [SDK / "lib" / name for name in ["robovm-rt.jar", "robovm-objc.jar", "robovm-cocoatouch.jar", "robovm-bro-bridge.jar"]]
    compile_cp = ":".join(str(path) for path in [BC, JDBC, *bindings])
    run("javac", "--release", "17", "-encoding", "UTF-8", "-cp", compile_cp, "-d", classes, *sources)
    raw = BUILD / "original.jar"
    native = BUILD / "native.jar"
    run("jar", "--create", "--file", raw, "-C", classes, ".")
    run("java", "-cp", str(raw) + ":" + str(BC), "com.tonikelope.coronapoker.ios.ModernRuntimeProbe")
    run("java", "-jar", DOWNGRADER, "--noColor", "-c", "52", "downgrade", "--target", raw, "-",
        "--classpath", compile_cp,
        "shade", "--prefix", "com/tonikelope/coronapoker/ios/compat", "--target", "-", native)
    run("java", "-cp", str(native) + ":" + str(BC), "com.tonikelope.coronapoker.ios.ModernRuntimeProbe")
    if args.aot_console or args.ios:
        runtime = prepare()
        prepare_compiler()
        cache = PORT / ".cache/aot-boot-bridge"
        bootclasspath = str(runtime) + ":" + str(SDK / "lib/robovm-bro-bridge.jar")
        temp = BUILD / "tmp-console"
        temp.mkdir(parents=True, exist_ok=True)
    if args.aot_console:
        run(SDK / "bin/robovm", "-cache", cache, "-tmp", temp, "-d", BUILD / "console",
            "-os", "macosx", "-arch", "arm64", "-target", "console", "-threads", "4",
            "-bootclasspath", bootclasspath, "-skiprt",
            "-forcelinkclasses", "com.tonikelope.coronapoker.ios.Ed25519Provider$*",
            "-cp", str(native) + ":" + str(BC), "com.tonikelope.coronapoker.ios.ModernRuntimeProbe")
        run(BUILD / "console/com.tonikelope.coronapoker.ios.ModernRuntimeProbe")
        if args.database:
            (BUILD / "tmp-database").mkdir(parents=True, exist_ok=True)
            sqlite = build_sqlite("macosx")
            config = ET.Element("config")
            libs = ET.SubElement(config, "libs")
            ET.SubElement(libs, "lib", force="true").text = str(sqlite)
            exports = ET.SubElement(config, "exportedSymbols")
            ET.SubElement(exports, "symbol").text = "Java_*"
            config_file = BUILD / "sqlite-console.xml"
            ET.ElementTree(config).write(config_file, encoding="utf-8", xml_declaration=True)
            run(SDK / "bin/robovm", "-cache", cache, "-tmp", BUILD / "tmp-database", "-d", BUILD / "database-console",
                "-config", config_file, "-os", "macosx", "-arch", "arm64", "-target", "console", "-threads", "4",
                "-bootclasspath", bootclasspath, "-skiprt", "-forcelinkclasses", "org.sqlite.**",
                "-cp", ":".join(map(str, [native, JDBC, SLF4J])), "com.tonikelope.coronapoker.ios.DatabaseRuntimeProbe")
            run(BUILD / "database-console/com.tonikelope.coronapoker.ios.DatabaseRuntimeProbe", BUILD / "jdbc-proof.sqlite")
    if args.ios:
        sdk_version = subprocess.check_output(["xcrun", "--sdk", "iphoneos", "--show-sdk-version"], text=True).strip()
        (BUILD / "tmp-ios").mkdir(parents=True, exist_ok=True)
        sqlite = build_sqlite("ios")
        config = ET.Element("config")
        libs = ET.SubElement(config, "libs")
        ET.SubElement(libs, "lib", force="true").text = str(sqlite)
        exports = ET.SubElement(config, "exportedSymbols")
        ET.SubElement(exports, "symbol").text = "Java_*"
        for tag, values in [("frameworks", ["UIKit", "Foundation"]),
                            ("forceLinkClasses", ["com.tonikelope.coronapoker.ios.**", "org.sqlite.**"] )]:
            parent = ET.SubElement(config, tag)
            for value in values: ET.SubElement(parent, "framework" if tag == "frameworks" else "pattern").text = value
        plist = BUILD / "Info.plist"
        plist.write_bytes(plistlib.dumps({"CFBundleIdentifier": "com.tonikelope.coronapoker.ios", "CFBundleExecutable": "CoronaPoker",
            "CFBundleName": "CoronaPoker", "CFBundleDisplayName": "CP Java 验证", "CFBundleVersion": "1", "CFBundleShortVersionString": "0.1",
            "CFBundlePackageType": "APPL", "LSRequiresIPhoneOS": True, "MinimumOSVersion": "18.0", "UIDeviceFamily": [1, 2], "UILaunchScreen": {},
            "NSLocalNetworkUsageDescription": "与原版 CoronaPoker 25.79 的电脑端在局域网联机。",
            "UIApplicationSceneManifest": {"UIApplicationSupportsMultipleScenes": False, "UISceneConfigurations": {"UIWindowSceneSessionRoleApplication": [
                {"UISceneConfigurationName": "Core validation", "UISceneDelegateClassName": "CoronaPokerCoreProbeScene"}]}},
            "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"]}))
        config_file = BUILD / "ios.xml"
        ET.ElementTree(config).write(config_file, encoding="utf-8", xml_declaration=True)
        run(SDK / "bin/robovm", "-cache", cache, "-tmp", BUILD / "tmp-ios", "-d", BUILD / "ios/CoronaPoker.app", "-o", "CoronaPoker",
            "-config", config_file, "-os", "ios", "-arch", "arm64", "-target", "ios", "-threads", "4", "-skipsign", "-sdk", sdk_version, "-plist", plist,
            "-bootclasspath", bootclasspath, "-skiprt", "-cp", ":".join(str(path) for path in [native, BC, JDBC, SLF4J, *bindings[1:3]]),
            "com.tonikelope.coronapoker.ios.IOSCoreProbe")


if __name__ == "__main__":
    main()
