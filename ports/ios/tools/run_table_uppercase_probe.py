#!/usr/bin/env python3
"""Check the production iOS uppercase cache without building or running the App."""
import hashlib
import json
from pathlib import Path
import subprocess
from prepare_game_sources import prepare_table_uppercase, replace_method
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
OUTPUT = PORT / "build/table-uppercase-tests"
GDX = ROOT / "modules/coronapoker-gdx/src/main/java/com/tonikelope/coronapoker/gdx"
HELPER = PORT / "shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSTableUppercaseCache.java"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    original_path = GDX / "CoronaPokerGdxTable.java"
    original = original_path.read_text()
    before = hashlib.sha256(original_path.read_bytes()).hexdigest()
    generated = prepare_table_uppercase(original)
    bodies = []
    for signature in ("    private String uppercase(", "    private static String uppercase("):
        def capture(method):
            bodies.append(method)
            return method
        replace_method(generated, signature, capture)
    assert len(bodies) == 2 and sum(body.count("IOSTableUppercaseCache.uppercase(") for body in bodies) == 2
    assert all('return value == null ? "" :' in body for body in bodies)
    seam = OUTPUT / "TableUppercaseGeneratedSeam.java"
    seam.write_text("""package com.tonikelope.coronapoker.gdx;
final class TableUppercaseGeneratedSeam {
 private final GdxGameText gameText;
 TableUppercaseGeneratedSeam(GdxGameText text){gameText=text;}
 String instance(String value){return uppercase(value);}
 static String withText(String value,GdxGameText text){return uppercase(value,text);}
""" + "\n".join(bodies) + "\n}\n")
    java = jdk_home() / "bin"
    subprocess.run([str(java / "javac"), "--release", "8", "-d", str(classes), str(HELPER)], check=True)
    helper_class = classes / "com/tonikelope/coronapoker/gdx/IOSTableUppercaseCache.class"
    assert int.from_bytes(helper_class.read_bytes()[6:8], "big") == 52
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", str(classes), "-d", str(classes),
                    str(ROOT / "modules/coronapoker-core/src/main/java/com/tonikelope/coronapoker/core/game/GameText.java"),
                    str(GDX / "GdxGameText.java"), str(seam), str(PORT / "tools/TableUppercaseCacheProbe.java")], check=True)
    result = subprocess.run([str(java / "java"), "-cp", str(classes),
                             "com.tonikelope.coronapoker.gdx.TableUppercaseCacheProbe"],
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("Production uppercase probe failed:\n" + result.stdout + result.stderr)
    report = json.loads(result.stdout)
    report.update(helperJavaClassVersion=52, generatedOverloadsChecked=2,
                  desktopSourceUnchanged=before == hashlib.sha256(original_path.read_bytes()).hexdigest(),
                  productionSourceSHA256=hashlib.sha256(HELPER.read_bytes()).hexdigest())
    assert report["desktopSourceUnchanged"]
    (OUTPUT / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
