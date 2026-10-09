#!/usr/bin/env python3
"""Compare original and mobile clipping in logical-versus-physical pixel fixtures."""
import datetime
import json
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
JAVA = jdk_home() / "bin"
GDX = ROOT / ".m2/repository/com/badlogicgames/gdx"
dependencies = [GDX / "gdx/1.14.2/gdx-1.14.2.jar", GDX / "gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar"]
classes = PORT / "build/scissor-tests"
classes.mkdir(parents=True, exist_ok=True)
mobile = PORT / "build/game/classes"
cp = ":".join(map(str, [mobile, *dependencies]))
subprocess.run([str(JAVA / "javac"), "--release", "17", "-cp", cp, "-d", str(classes),
                str(PORT / "tools/MobileScissorProbe.java")], check=True)
report = {"recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "scope": "real frontend/table clip commands under fake GL, including modal FBO targets; physical chat/voice rendering requires iPad validation"}
for name, implementations in [
    ("original", [ROOT / "modules/coronapoker-gdx/target/classes", ROOT / "modules/coronapoker-core/target/classes", mobile]),
    ("mobile", [mobile])
]:
    result = subprocess.run([str(JAVA / "java"), "-cp", ":".join(map(str, [classes, *implementations, *dependencies])),
                             "com.tonikelope.coronapoker.gdx.MobileScissorProbe"], text=True, capture_output=True)
    log = PORT / "validation" / ("scissor-" + name + ".log")
    log.write_text(result.stdout + result.stderr)
    report[name] = {"exitCode": result.returncode, "log": log.name}
    print(name + ": " + result.stdout, flush=True)
report["passed"] = report["original"]["exitCode"] != 0 and report["mobile"]["exitCode"] == 0
(PORT / "validation/scissor-regression.json").write_text(json.dumps(report, indent=2) + "\n")
if not report["passed"]: raise SystemExit("Clip coordinate validation failed; inspect saved logs")
