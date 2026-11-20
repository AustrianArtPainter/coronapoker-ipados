#!/usr/bin/env python3
"""Check native source reuse without requiring a host OpenAL device."""
from pathlib import Path
import subprocess
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
base = PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios"
output = PORT / "build/sound-tests"
output.mkdir(parents=True, exist_ok=True)
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-d", str(output),
                str(base / "SoundVoiceRegistry.java"), str(PORT / "tools/SoundVoiceProbe.java")], check=True)
subprocess.run([str(java / "java"), "-cp", str(output), "com.tonikelope.coronapoker.ios.SoundVoiceProbe"], check=True)
