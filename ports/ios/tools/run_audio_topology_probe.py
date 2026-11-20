#!/usr/bin/env python3
"""Verify native audio-route hotplug contracts without invoking UIKit on the host."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
output = PORT / "build/audio-topology-tests"
output.mkdir(parents=True, exist_ok=True)
runtime = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
classpath = ":".join(map(str, [PORT / "build/game/classes",
    ROOT / ".m2/repository/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
    *(runtime / name for name in ["robovm-rt.jar", "robovm-bro-bridge.jar",
                                "robovm-objc.jar", "robovm-cocoatouch.jar"])]))
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-cp", classpath,
    "-d", str(output), str(PORT / "tools/AudioTopologyProbe.java")], check=True)
subprocess.run([str(java / "java"), "-cp", str(output) + ":" + classpath,
    "com.tonikelope.coronapoker.gdx.AudioTopologyProbe"], check=True)
