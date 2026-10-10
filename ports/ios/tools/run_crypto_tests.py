#!/usr/bin/env python3
"""Run upstream crypto assertions, adapting their pre-modular RNG access only."""
import subprocess
import sys
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
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
paths = [PORT / "build/game/classes", ROOT / "modules/coronapoker-assets/target/classes",
         PORT / ".cache/downloads/bcprov-jdk18on-1.86.jar", *[repository / name for name in dependencies]]
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
selected = sys.argv[1:] or [source.stem for source in sources]
subprocess.run([str(java / "java"), "-Xmx1g", "-Djava.awt.headless=true", "-cp", str(classes) + ":" + cp,
               "BoundaryTests", *["com.tonikelope.coronapoker.crypto." + name for name in selected]], check=True)
