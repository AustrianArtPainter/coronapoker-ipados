#!/usr/bin/env python3
"""Fix MobiVM 3's subprocess-output race without altering its distributed jar."""
import hashlib
import json
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
SDK = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT"
DERIVED = PORT / ".cache/derived-compiler"


def prepare():
    upstream = PORT / ".cache/downloads/Executor.java"
    source = upstream.read_text()
    start = source.index("    public int exec() throws IOException {")
    end = source.index("    public Process execAsync()", start)
    method = source[start:end]
    anchor = "        Process process = pb.start();"
    if method.count(anchor) != 1:
        raise RuntimeError("Unexpected Executor source: process creation changed")
    method = method.replace(anchor, anchor + "\n        List<Thread> ioPumps = new ArrayList<>();")
    if method.count("                t.start();") != 1:
        raise RuntimeError("Unexpected Executor source: pump creation changed")
    method = method.replace("                t.start();", "                ioPumps.add(t);\n                t.start();")
    wait = "            int code = process.waitFor();"
    if method.count(wait) != 1:
        raise RuntimeError("Unexpected Executor source: wait changed")
    method = method.replace(wait, wait + "\n            // Drain captured output before execCapture reads its buffer.\n            for (Thread pump : ioPumps) pump.join();")
    modified = source[:start] + method + source[end:]
    target = DERIVED / "src/org/robovm/compiler/util/Executor.java"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(modified)
    classes = DERIVED / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk_home() / "bin/javac"), "--release", "17", "-cp", str(SDK / "lib/robovm-dist-compiler.jar"), "-d", str(classes), str(target)], check=True)
    jar = DERIVED / "compiler-fix.jar"
    subprocess.run([str(jdk_home() / "bin/jar"), "--create", "--file", str(jar), "-C", str(classes), "."], check=True)
    report = {"upstreamSourceCommit": "6d97fcec3112d4da6e1087fd0f0bc670a24e41d8",
              "sourceSHA256": hashlib.sha256(source.encode()).hexdigest(),
              "patchedSourceSHA256": hashlib.sha256(modified.encode()).hexdigest(),
              "originalCompilerSHA256": hashlib.sha256((SDK / "lib/robovm-dist-compiler.jar").read_bytes()).hexdigest(),
              "change": "Wait for synchronous subprocess IO pump completion before returning; async execution unchanged"}
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    (PORT / "validation/compiler-derivation.json").write_text(json.dumps(report, indent=2) + "\n")
    return jar


if __name__ == "__main__":
    print(prepare())
