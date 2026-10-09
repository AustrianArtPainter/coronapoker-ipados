#!/usr/bin/env python3
"""Compare the actual desktop/mobile camera actions and mobile completion guards."""
import datetime
import json
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
JAVA = jdk_home() / "bin"
GDX = ROOT / ".m2/repository/com/badlogicgames/gdx"
deps = [GDX / "gdx/1.14.2/gdx-1.14.2.jar", GDX / "gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
        GDX / "gdx-platform/1.14.2/gdx-platform-1.14.2-natives-desktop.jar",
        GDX / "gdx-jnigen-loader/2.5.2/gdx-jnigen-loader-2.5.2.jar"]
mobile = PORT / "build/game/classes"
classes = PORT / "build/screenshot-tests"
classes.mkdir(parents=True, exist_ok=True)
subprocess.run([str(JAVA / "javac"), "--release", "17", "-cp", ":".join(map(str, [mobile, *deps])),
                "-d", str(classes), str(PORT / "tools/MobileScreenshotProbe.java"),
                str(PORT / "tools/MobileScreenshotPngProbe.java")], check=True)
report = {"recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "scope": "actual camera action handlers and asynchronous completion guards; native PNG and pasteboard need physical validation"}
for name, implementation in [
    ("original", [ROOT / "modules/coronapoker-gdx/target/classes", ROOT / "modules/coronapoker-core/target/classes", mobile]),
    ("mobile", [mobile]),
]:
    result = subprocess.run([str(JAVA / "java"), "-cp", ":".join(map(str, [classes, *implementation, *deps])),
                             "com.tonikelope.coronapoker.gdx.MobileScreenshotProbe"], text=True, capture_output=True)
    log = PORT / "validation" / ("screenshot-" + name + ".log")
    log.write_text(result.stdout + result.stderr)
    report[name] = {"exitCode": result.returncode, "log": log.name}
    print(name + ": " + result.stdout + result.stderr, flush=True)
    png = subprocess.run([str(JAVA / "java"), "-cp", ":".join(map(str, [classes, *implementation, *deps])),
                          "com.tonikelope.coronapoker.gdx.MobileScreenshotPngProbe"], text=True, capture_output=True)
    png_log = PORT / "validation" / ("screenshot-png-" + name + ".log")
    png_log.write_text(png.stdout + png.stderr)
    report[name]["pngExitCode"] = png.returncode
    report[name]["pngLog"] = png_log.name
    print(name + " PNG: " + png.stdout + png.stderr, flush=True)
report["passed"] = (report["original"]["exitCode"] != 0 and report["mobile"]["exitCode"] == 0
                    and report["original"]["pngExitCode"] == 0 and report["mobile"]["pngExitCode"] == 0)
(PORT / "validation/screenshot-regression.json").write_text(json.dumps(report, indent=2) + "\n")
if not report["passed"]: raise SystemExit("Screenshot regression failed; inspect saved logs")
