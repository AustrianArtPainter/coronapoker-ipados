#!/usr/bin/env python3
"""Original frontend wrapping / real FreeType metrics, without a GPU/audio device."""
import os
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
output = PORT / "build/menu-quote-wrap-tests"
output.mkdir(parents=True, exist_ok=True)
runtime = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
m2 = ROOT / ".m2/repository/com/badlogicgames/gdx"
dependencies = [PORT / "build/game/classes",
    m2 / "gdx/1.14.2/gdx-1.14.2.jar",
    m2 / "gdx-platform/1.14.2/gdx-platform-1.14.2-natives-desktop.jar",
    m2 / "gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
    m2 / "gdx-freetype-platform/1.14.2/gdx-freetype-platform-1.14.2-natives-desktop.jar",
    m2 / "gdx-jnigen-loader/2.5.2/gdx-jnigen-loader-2.5.2.jar",
    *(runtime / name for name in ["robovm-rt.jar", "robovm-bro-bridge.jar", "robovm-objc.jar", "robovm-cocoatouch.jar"])]
for dependency in dependencies:
    if not dependency.exists():
        raise RuntimeError("Missing existing probe dependency: " + str(dependency))
cp = ":".join(map(str, dependencies))
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-cp", cp, "-d", str(output),
    str(PORT / "tools/MenuQuoteWrapCacheProbe.java")], check=True)
for flag in [None, "0", "true", "1"]:
    env = {key: value for key, value in os.environ.items() if not key.startswith("CP_IOS_")}
    if flag is not None:
        env["CP_IOS_QUOTE_WRAP_CACHE"] = flag
    subprocess.run([str(java / "java"), "-Xmx512m", "-cp", str(output) + ":" + cp,
        "com.tonikelope.coronapoker.gdx.MenuQuoteWrapCacheProbe", str(ROOT / "src/main/resources"),
        "false" if flag == "0" else "true"], env=env, check=True)
