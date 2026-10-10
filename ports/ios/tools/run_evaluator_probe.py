#!/usr/bin/env python3
"""Compare the mobile workspace with a renamed, unmodified desktop oracle."""
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
output = PORT / "build/evaluator-tests"
output.mkdir(parents=True, exist_ok=True)
source = ROOT / "modules/coronapoker-core/src/main/java/org/alberta/poker/HandEvaluator.java"
oracle = output / "DesktopRankOracle.java"
oracle.write_text(source.read_text().replace("HandEvaluator", "DesktopRankOracle"))
java = jdk_home() / "bin"
cp = str(PORT / "build/game/classes")
subprocess.run([str(java/"javac"), "--release", "17", "-cp", cp, "-d", str(output),
                str(oracle), str(PORT/"tools/EvaluatorWorkspaceProbe.java")], check=True)
subprocess.run([str(java/"java"), "-Xmx256m", "-cp", str(output)+":"+cp,
                "org.alberta.poker.EvaluatorWorkspaceProbe"], check=True)
