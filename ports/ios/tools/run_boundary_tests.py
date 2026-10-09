#!/usr/bin/env python3
"""Execute original security/state contracts with mobile implementations first."""
from pathlib import Path
from toolchain import jdk_home
import subprocess

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
JAVA = jdk_home() / "bin"
REPOSITORY = ROOT / ".m2/repository"
DEPENDENCIES = [
    "org/junit/platform/junit-platform-launcher/1.10.2/junit-platform-launcher-1.10.2.jar",
    "org/junit/platform/junit-platform-engine/1.10.2/junit-platform-engine-1.10.2.jar",
    "org/junit/platform/junit-platform-commons/1.10.2/junit-platform-commons-1.10.2.jar",
    "org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar",
    "org/junit/jupiter/junit-jupiter-engine/5.10.2/junit-jupiter-engine-5.10.2.jar",
    "org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar",
    "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar",
    "com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2.jar",
    "com/badlogicgames/gdx/gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
]
paths = [PORT / "build/game/classes", ROOT / "modules/coronapoker-core/target/test-classes",
         ROOT / "modules/coronapoker-gdx/target/test-classes", ROOT / "modules/coronapoker-gdx/target/classes",
         ROOT / "modules/coronapoker-assets/target/classes", *[REPOSITORY / path for path in DEPENDENCIES]]
if any(not path.exists() for path in paths): raise RuntimeError("Missing baseline test dependencies")
classes = PORT / "build/boundary-tests"
classes.mkdir(parents=True, exist_ok=True)
cp = ":".join(map(str, paths))
original = ROOT / "modules/coronapoker-gdx/src/test/java/com/tonikelope/coronapoker/gdx/GdxTableViewStateTest.java"
text = original.read_text()
desktop_assertion = "        assertFalse(GdxImageClipboard.copy((java.awt.Image) null));\n"
if text.count(desktop_assertion) != 1: raise RuntimeError("Desktop clipboard assertion changed; re-audit")
mobile_test = classes / "generated/com/tonikelope/coronapoker/gdx/GdxTableViewStateTest.java"
mobile_test.parent.mkdir(parents=True, exist_ok=True)
mobile_test.write_text(text.replace(desktop_assertion, ""))
print("Original state/security tests; only the AWT-null clipboard assertion is omitted. UIKit clipboard requires device validation.", flush=True)
subprocess.run([str(JAVA / "javac"), "--release", "17", "-cp", cp, "-d", str(classes), str(PORT / "tools/BoundaryTests.java"), str(mobile_test)], check=True)
subprocess.run([str(JAVA / "java"), "-cp", str(classes) + ":" + cp, "BoundaryTests",
               "com.tonikelope.coronapoker.core.AvatarImageValidatorTest",
               "com.tonikelope.coronapoker.gdx.GdxTableViewStateTest"], check=True)
