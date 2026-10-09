#!/usr/bin/env python3
"""Compare real original versus generated mobile handlers for UIKit input events."""
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
classes = PORT / "build/text-input-tests"
classes.mkdir(parents=True, exist_ok=True)
mobile = PORT / "build/game/classes"
cp = ":".join(map(str, [mobile, *dependencies]))
subprocess.run([str(JAVA / "javac"), "--release", "17", "-cp", cp, "-d", str(classes),
                str(PORT / "tools/MobileTextInputProbe.java")], check=True)
report = {"recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "scope": "real input handlers on the JVM; physical UIKit input must be rechecked on iPad"}
for name, implementations in [
    ("original", [ROOT / "modules/coronapoker-gdx/target/classes", ROOT / "modules/coronapoker-core/target/classes", mobile]),
    ("mobile", [mobile])
]:
    result = subprocess.run([str(JAVA / "java"), "-cp", ":".join(map(str, [classes, *implementations, *dependencies])),
                             "com.tonikelope.coronapoker.gdx.MobileTextInputProbe"], text=True, capture_output=True)
    log = PORT / "validation" / ("text-input-" + name + ".log")
    log.write_text(result.stdout + result.stderr)
    report[name] = {"exitCode": result.returncode, "log": log.name}
    print(name + ": " + result.stdout, flush=True)
report["passed"] = report["original"]["exitCode"] != 0 and report["mobile"]["exitCode"] == 0
(PORT / "validation/text-input-regression.json").write_text(json.dumps(report, indent=2) + "\n")
if not report["passed"]: raise SystemExit("Input regression validation failed; inspect saved logs")
