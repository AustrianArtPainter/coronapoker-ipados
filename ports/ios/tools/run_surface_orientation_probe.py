#!/usr/bin/env python3
"""Check the retained surface's real SpriteBatch geometry without a GPU."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
output = PORT / "build/surface-orientation-tests"
output.mkdir(parents=True, exist_ok=True)
cp = str(ROOT / ".m2/repository/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar")
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-cp", cp, "-d", str(output),
    str(PORT / "shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSOpaqueSceneLayer.java"),
    str(PORT / "tools/RetainedSurfaceOrientationProbe.java")], check=True)
subprocess.run([str(java / "java"), "-cp", str(output) + ":" + cp,
    "com.tonikelope.coronapoker.gdx.RetainedSurfaceOrientationProbe"], check=True)
