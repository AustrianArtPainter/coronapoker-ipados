#!/usr/bin/env python3
"""Compare original startup RNG draws and inspect their final lowered callers."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from toolchain import jdk_home


PORT = Path(__file__).resolve().parents[1]
HELPER_NAME = "com.tonikelope.coronapoker.ios.IOSIntroRandom"
HELPER_CLASS = HELPER_NAME.replace(".", "/") + ".class"
TABLE_NAME = "com.tonikelope.coronapoker.gdx.CoronaPokerGdxTable"


def inspect_helper(java, artifact, bytecode):
    if struct.unpack_from(">H", bytecode, 6)[0] != 52:
        raise AssertionError("Startup random helper must be compiled as Java 8")
    text = subprocess.check_output([str(java / "javap"), "-c", "-p", "-classpath",
                                    str(artifact), HELPER_NAME], text=True)
    if any(value in text for value in ["java/util/random", "RandomGenerator", "jvmdg", "wagyourtail"]):
        raise AssertionError("Startup random helper acquired a post-Java-8 bridge dependency")
    for method in ["java/util/SplittableRandom.nextInt:()I", "java/util/SplittableRandom.nextLong:()J"]:
        if method not in text:
            raise AssertionError("Startup random helper must retain original scalar mixing: " + method)
    if "java/util/SplittableRandom.nextDouble:" in text or "java/util/SplittableRandom.nextBoolean:" in text:
        raise AssertionError("Startup must not invoke inherited Java 17 scalar defaults")


def inspect_callers(java, artifact):
    text = subprocess.check_output([str(java / "javap"), "-c", "-p", "-classpath",
                                    str(artifact), TABLE_NAME], text=True)
    for method in ["introCardLayout", "randomRange"]:
        match = re.search(r"^  [^\n]*\b" + method + r"\([^\n]*;\n.*?(?=^  \S|\Z)", text, re.M | re.S)
        if not match:
            raise AssertionError("Missing original startup method in lowered artifact: " + method)
        body = match.group(0)
        if "com/tonikelope/coronapoker/ios/IOSIntroRandom" not in body:
            raise AssertionError("Startup method does not use the reviewed helper: " + method)
        # The original immutable layout uses the reviewed Java 10 List.copyOf
        # stub. Reject the unsupported random bridge, retaining that unrelated
        # collection adaptation in the original method.
        if any(value in body for value in ["java/util/SplittableRandom", "java/util/random",
                "RandomGenerator", "jvmdg/util/Utils", "jvmdg/j17/impl/random"]):
            raise AssertionError("Lowered startup caller retained an unsafe random bridge: " + method)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-jar", type=Path,
                        help="Inspect and execute the helper in the final lowered game JAR; never builds AOT")
    args = parser.parse_args()
    java = jdk_home() / "bin"
    build = PORT / "build/intro-random-probe"
    build.mkdir(parents=True, exist_ok=True)
    helper = PORT / "shared-adapters/src" / (HELPER_NAME.replace(".", "/") + ".java")
    subprocess.run([str(java / "javac"), "--release", "8", "-d", str(build), str(helper)], check=True)
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", str(build), "-d", str(build),
                    str(PORT / "tools/IntroRandomCompatibilityProbe.java")], check=True)
    inspect_helper(java, build, (build / HELPER_CLASS).read_bytes())
    if args.native_jar:
        artifact = args.native_jar.resolve()
        inspect_callers(java, artifact)
    else:
        original = build / "helper-original.jar"
        artifact = build / "helper-lowered.jar"
        subprocess.run([str(java / "jar"), "--create", "--file", str(original),
                        "-C", str(build), HELPER_CLASS], check=True)
        subprocess.run([str(java / "java"), "-jar", str(PORT / ".cache/downloads/jvmdowngrader-2.0.1-all.jar"),
                        "--noColor", "-c", "52", "downgrade", "--target", str(original), "-",
                        "shade", "--prefix", "com/tonikelope/coronapoker/ios/compat", "--target", "-", str(artifact)], check=True)
    with zipfile.ZipFile(artifact) as archive:
        inspect_helper(java, artifact, archive.read(HELPER_CLASS))
    subprocess.run([str(java / "java"), "-cp", str(artifact) + ":" + str(build),
                    "com.tonikelope.coronapoker.ios.IntroRandomCompatibilityProbe", str(artifact)], check=True)
    print("PASS Java 8 helper ownership and lowered bytecode; physical startup remains a separate device check")


if __name__ == "__main__":
    main()
