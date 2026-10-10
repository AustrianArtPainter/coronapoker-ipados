#!/usr/bin/env python3
"""Exercise the generated touch handlers; physical iPad validation remains separate."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
classes = PORT / "build/scroll-tests"
classes.mkdir(parents=True, exist_ok=True)
dependencies = [PORT / "build/game/classes", ROOT / ".m2/repository/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
                ROOT / ".m2/repository/com/badlogicgames/gdx/gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar"]
cp = ":".join(map(str, dependencies))
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-cp", cp, "-d", str(classes), str(PORT / "tools/MobileScrollProbe.java")], check=True)
subprocess.run([str(java / "java"), "-cp", str(classes) + ":" + cp, "com.tonikelope.coronapoker.gdx.MobileScrollProbe"], check=True)
