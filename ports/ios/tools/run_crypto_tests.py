#!/usr/bin/env python3
"""Run upstream crypto assertions, adapting their pre-modular RNG access only."""
import subprocess
import sys
import shutil
import argparse
from pathlib import Path
from toolchain import jdk_home, game_version

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--reuse-mobile-snapshot", action="store_true",
                    help="Use the preceding complete mobile snapshot while an AOT build recreates its classes")
parser.add_argument("tests", nargs="*", help="Upstream crypto test class names; default is all classes")
args = parser.parse_args()
classes = PORT / "build/crypto-tests"
classes.mkdir(parents=True, exist_ok=True)
repository = ROOT / ".m2/repository"
dependencies = [
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
version = game_version()
# A full AOT build recreates build/game/classes. Keep this independent verifier
# on a complete snapshot so a later build cannot replace classes mid-test.
mobile_classes = classes / "mobile-classes"
source_classes = PORT / "build/game/classes"
if not args.reuse_mobile_snapshot:
    if not (source_classes / "com/tonikelope/coronapoker/core/ApplicationMetadata.class").is_file():
        raise RuntimeError("Complete the mobile Java compilation before running crypto contracts")
    if mobile_classes.exists():
        shutil.rmtree(mobile_classes)
    shutil.copytree(source_classes, mobile_classes)
elif not (mobile_classes / "com/tonikelope/coronapoker/core/ApplicationMetadata.class").is_file():
    raise RuntimeError("No complete preceding mobile crypto snapshot is available")
print("Mobile crypto class snapshot ready: " + str(mobile_classes), flush=True)
paths = [mobile_classes, ROOT / "modules/coronapoker-assets/target/classes",
         PORT / ".cache/downloads/bcprov-jdk18on-1.86.jar", *[repository / name for name in dependencies]]
# Diagnostics are disabled on the host, but verification of their bytecode
# still needs the pinned bridge type definitions. No UIKit method is called.
runtime = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
paths += [runtime / name for name in ["robovm-rt.jar", "robovm-bro-bridge.jar",
                                    "robovm-objc.jar", "robovm-cocoatouch.jar"]]
missing = [str(path) for path in paths if not path.exists()]
if missing:
    raise RuntimeError("Missing crypto contract dependencies: " + ", ".join(missing))
cp = ":".join(map(str, paths))
source_dir = ROOT / "tools/qa/src/test/java/com/tonikelope/coronapoker/crypto"
sources = sorted(source_dir.glob("*Test.java"))
adapted = []
for source in sources:
    text = source.read_text()
    text = text.replace("import com.tonikelope.coronapoker.Helpers;\n", "")
    text = text.replace("com.tonikelope.coronapoker.Helpers.CSPRNG_GENERATOR", "CryptoRandom.generator()")
    text = text.replace("Helpers.CSPRNG_GENERATOR", "CryptoRandom.generator()")
    text = text.replace("CryptoRandom.generator() = new SecureRandom();", "CryptoRandom.configure(new SecureRandom());")
    target = classes / "generated" / source.name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    adapted.append(target)
java = jdk_home() / "bin"
subprocess.run([str(java / "javac"), "--release", "17", "-cp", cp, "-d", str(classes),
                str(PORT / "tools/BoundaryTests.java"), str(PORT / "tools/FieldPerformanceProbe.java"),
                *map(str, adapted)], check=True)
print("Upstream crypto assertions; legacy Helpers RNG access maps to the existing CryptoRandom API.", flush=True)
selected = args.tests or [source.stem for source in sources]
subprocess.run([str(java / "java"), "-Xmx1g", "-Djava.awt.headless=true",
               "-Dcoronapoker.validation.expectedVersion=" + version,
               "-Dcoronapoker.validation.mobileClasses=" + str(mobile_classes),
               "-Dcoronapoker.validation.mobileClassNames=com.tonikelope.coronapoker.crypto.Fe25519,com.tonikelope.coronapoker.crypto.EdwardsPoint,com.tonikelope.coronapoker.crypto.RistrettoSRA",
               "-cp", str(classes) + ":" + cp,
               "BoundaryTests", *["com.tonikelope.coronapoker.crypto." + name for name in selected]], check=True)
