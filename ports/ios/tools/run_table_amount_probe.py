#!/usr/bin/env python3
"""Compare actual upstream amount formatting and iOS cache without App/AOT/UI."""
import hashlib
import json
from pathlib import Path
import subprocess

import prepare_game_sources as generator
from prepare_table_amounts import ORIGINAL, UNCACHED, WRAPPER, prepare
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
OUTPUT = PORT / "build/table-amount-tests"
UPSTREAM = ROOT / "modules/coronapoker-gdx/src/main/java/com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"
HELPER = PORT / "shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSTableAmountCache.java"
PREPARED = PORT / "build/game/generated-src/com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method(text, signature):
    methods = []
    generator.replace_method(text, signature, lambda body: methods.append(body) or body)
    assert len(methods) == 1
    return methods[0]


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    upstream_hash, prepared_hash = digest(UPSTREAM), digest(PREPARED)
    original = UPSTREAM.read_text()
    converted = prepare(original)
    actual_original = method(original, "    static String formatAmount(")
    assert actual_original == ORIGINAL
    assert converted.replace(WRAPPER + "\n\n" + UNCACHED, actual_original, 1) == original
    assert method(converted, "    static String mobileUncachedFormatAmount(") == actual_original.replace(
        "formatAmount(", "mobileUncachedFormatAmount(", 1)
    # The sole transform leaves all call sites, guards and evaluation order intact.
    invalid = [converted, original.replace(actual_original, "", 1),
        original.replace(actual_original, actual_original * 2, 1),
        original.replace(actual_original, actual_original.replace(".HALF_UP)", ".HALF_EVEN)"), 1),
        original.replace(actual_original, actual_original.replace(".toPlainString()", ".toString()"), 1),
        original + "\n// mobileUncachedFormatAmount( ownership conflict\n"]
    for changed in invalid:
        try:
            prepare(changed)
        except RuntimeError:
            pass
        else:
            raise AssertionError("Converter accepted changed/missing/repeated ownership")
    sources = []
    for name, methods in (("OriginalTableAmounts", actual_original), ("CoronaPokerGdxTable",
            method(converted, "    static String formatAmount(") + "\n\n"
            + method(converted, "    static String mobileUncachedFormatAmount("))):
        path = OUTPUT / (name + ".java")
        path.write_text("package com.tonikelope.coronapoker.gdx;\nfinal class " + name + " {\n" + methods + "\n}\n")
        sources.append(path)
    java = jdk_home() / "bin"
    subprocess.run([str(java / "javac"), "--release", "8", "-d", str(classes), str(HELPER),
                    *map(str, sources)], check=True)
    for name in ("IOSTableAmountCache", "CoronaPokerGdxTable", "OriginalTableAmounts"):
        path = classes / ("com/tonikelope/coronapoker/gdx/" + name + ".class")
        assert int.from_bytes(path.read_bytes()[6:8], "big") == 52
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", str(classes), "-d", str(classes),
                    str(PORT / "tools/TableAmountCacheProbe.java")], check=True)
    # Synchronous host JIT keeps a one-time tier transition out of the measured
    # steady loop; disabling escape analysis keeps the uncached baseline honest.
    result = subprocess.run([str(java / "java"), "-Xmx256m", "-Xbatch", "-XX:-DoEscapeAnalysis", "-cp", str(classes),
                             "com.tonikelope.coronapoker.gdx.TableAmountCacheProbe"], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("Production amount comparison failed:\n" + result.stdout + result.stderr)
    report = json.loads(result.stdout)
    # Exercise the actual complete generator privately without changing shared
    # prepared sources, App classes or the current build16 evidence bindings.
    private_port = OUTPUT / "generator-port"
    private_port.mkdir(exist_ok=True)
    link = private_port / "tools"
    if not link.exists():
        link.symlink_to((PORT / "tools").resolve(), target_is_directory=True)
    assert link.resolve() == (PORT / "tools").resolve()
    generator_text = (PORT / "tools/prepare_game_sources.py").read_text()
    assert generator_text.count("    text = prepare_table_amounts(text)") == 1
    generator.PORT, generator.GENERATED = private_port, private_port / "generated-src"
    generator.prepare()
    private = generator.GENERATED / "com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"
    final = private.read_text()
    assert method(final, "    static String mobileUncachedFormatAmount(") == UNCACHED
    entry = method(final, "    static String formatAmount(")
    assert entry.count("return IOSTableAmountCache.formatAmount(amount);") == 1
    assert entry.count('endAllocation("alloc.formatAmount"') == 1
    report.update(oracle="Method body extracted from actual current upstream, mechanically renamed for production misses",
        oracleAndHelperJavaClassVersion=52, failClosedOwnershipCases=len(invalid),
        sourceTransformOnlyChangesExactAmountMethod=True, actualFullGeneratorRunPrivately=True,
        existingAllocationDiagnosticsPreserved=True, productionSourceSHA256=digest(HELPER),
        hostAllocationJVMFlags=["-Xbatch", "-XX:-DoEscapeAnalysis"],
        upstreamSHA256=upstream_hash, privateGeneratedTableSHA256=digest(private),
        desktopSourceUnchanged=digest(UPSTREAM) == upstream_hash,
        sharedPreparedSourceUnchanged=digest(PREPARED) == prepared_hash, appBuildOrAOTRun=False)
    assert report["desktopSourceUnchanged"] and report["sharedPreparedSourceUnchanged"]
    (OUTPUT / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
