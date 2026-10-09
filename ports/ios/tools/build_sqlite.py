#!/usr/bin/env python3
"""Statically build the original JDBC JNI layer and identical SQLite version."""
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
DOWNLOADS = PORT / ".cache/downloads"
JDBC = ROOT / ".m2/repository/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar"
BUILD = PORT / "build/sqlite"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def build(platform):
    source = BUILD / "src"
    source.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(DOWNLOADS / "sqlite-amalgamation-3510100.zip") as archive:
        for name in ["sqlite3.c", "sqlite3.h"]:
            match = next(entry for entry in archive.namelist() if entry.endswith("/" + name))
            (source / name).write_bytes(archive.read(match))
    original_jni = (DOWNLOADS / "NativeDB.c").read_text()
    extension_call = "    return sqlite3_enable_load_extension(db, enable ? 1 : 0);"
    if original_jni.count(extension_call) != 1:
        raise RuntimeError("SQLite JNI extension entry point changed; re-audit the static build")
    (source / "NativeDB.c").write_text(original_jni.replace(extension_call,
        "#ifdef SQLITE_OMIT_LOAD_EXTENSION\n"
        "    return enable ? SQLITE_ERROR : SQLITE_OK;\n"
        "#else\n" + extension_call + "\n#endif"))
    classes = BUILD / "header-classes"
    classes.mkdir(parents=True, exist_ok=True)
    run(jdk_home() / "bin/javac", "--release", "17", "-cp", JDBC, "-h", source, "-d", classes, DOWNLOADS / "NativeDB.java")
    shutil.copy2(source / "org_sqlite_core_NativeDB.h", source / "NativeDB.h")
    include = jdk_home() / "include"
    sdk = {"ios":"iphoneos","ios-simulator":"iphonesimulator","macosx":"macosx"}[platform]
    sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
    clang = subprocess.check_output(["xcrun", "--sdk", sdk, "--find", "clang"], text=True).strip()
    triple = {"ios":"arm64-apple-ios18.0","ios-simulator":"arm64-apple-ios18.0-simulator",
              "macosx":"arm64-apple-macos13.0"}[platform]
    objects = BUILD / platform
    objects.mkdir(parents=True, exist_ok=True)
    common = [clang, "-target", triple, "-isysroot", sdk_path, "-O2", "-fPIC", "-I", source,
              "-I", include, "-I", include / "darwin", "-DSQLITE_THREADSAFE=1",
              "-DSQLITE_ENABLE_COLUMN_METADATA", "-DSQLITE_ENABLE_FTS5", "-DSQLITE_OMIT_LOAD_EXTENSION",
              "-DSQLITE_DEFAULT_MEMSTATUS=0"]
    for c_file, extra in [(source / "sqlite3.c", []),
                          (source / "NativeDB.c", ["-DJNI_OnLoad=CP_SQLiteJNI_OnLoad", "-DJNI_OnUnload=CP_SQLiteJNI_OnUnload"]),
                          (PORT / "native/sqlite_bridge.c", [])]:
        run(*common, *extra, "-c", c_file, "-o", objects / (c_file.stem + ".o"))
    archive = objects / "libcoronapoker-sqlite.a"
    run("xcrun", "ar", "rcs", archive, *(objects / (name + ".o") for name in ["sqlite3", "NativeDB", "sqlite_bridge"]))
    report = {"sqlite": "3.51.1", "jdbc": "3.51.1.0", "platform": platform,
              "sha256": {name: hashlib.sha256((DOWNLOADS / name).read_bytes()).hexdigest()
                         for name in ["sqlite-amalgamation-3510100.zip", "NativeDB.c", "NativeDB.java"]}}
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    (PORT / "validation" / ("sqlite-build-" + platform + ".json")).write_text(json.dumps(report, indent=2) + "\n")
    return archive


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=["ios", "ios-simulator", "macosx"])
    print(build(parser.parse_args().platform))
