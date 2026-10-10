#!/usr/bin/env python3
"""Validate actual ALL IN assets and playback ownership before device build."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
target = PORT / "build/cinematic-tests"
target.mkdir(parents=True, exist_ok=True)
jdk = jdk_home()/"bin"
runtime = PORT/".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
cp = ":".join(map(str, [PORT/"build/game/classes",
        ROOT/".m2/repository/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
        *(runtime/name for name in ("robovm-rt.jar", "robovm-bro-bridge.jar",
                                  "robovm-objc.jar", "robovm-cocoatouch.jar"))]))
subprocess.run([str(jdk/"javac"), "--release", "17", "-cp", cp, "-d", str(target),
                str(PORT/"tools/CinematicPreloadProbe.java")], check=True)
subprocess.run([str(jdk/"java"), "-Xmx512m", "-cp", str(target)+":"+cp,
                "com.tonikelope.coronapoker.gdx.CinematicPreloadProbe", str(ROOT/"src/main/resources")], check=True)
