#!/usr/bin/env python3
"""Fetch hash-pinned build inputs, extract local toolchains, and prepare Maven."""
import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path
from toolchain import PORT, digest, jdk_home, verify_downloads

ROOT = PORT.parents[1]
DOWNLOADS = PORT / ".cache/downloads"
TOOLCHAINS = PORT / ".cache/toolchains"


def fetch(item, offline):
    target = DOWNLOADS / item["name"]
    if target.is_file() and digest(target) == item["sha256"]:
        print("Verified: " + item["name"], flush=True)
        return target
    if offline:
        raise RuntimeError("Offline cache missing or checksum mismatch: " + item["name"])
    if not item["url"].startswith("https://"):
        raise RuntimeError("Build inputs must use HTTPS")
    print("Downloading: " + item["name"], flush=True)
    partial = target.with_name(target.name + ".part")
    try:
        request = urllib.request.Request(item["url"], headers={"User-Agent": "CoronaPoker-iPadOS-build"})
        with urllib.request.urlopen(request, timeout=60) as source, partial.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        if digest(partial) != item["sha256"]:
            raise RuntimeError("Checksum mismatch: " + item["name"] + "; refusing to use this download")
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def extract(item, archive):
    target = TOOLCHAINS / item["extract"]
    marker = target / ".cp-archive-sha256"
    if marker.is_file() and marker.read_text().strip() == item["sha256"]:
        return
    print("Extracting: " + item["name"], flush=True)
    # Python's data filter rejects paths/links outside the staging directory,
    # devices and other unsafe archive members. No elevated installation.
    with tempfile.TemporaryDirectory(prefix="extract-", dir=TOOLCHAINS) as directory:
        with tarfile.open(archive, "r:gz") as source:
            source.extractall(directory, filter="data")
        unpacked = Path(directory) / item["extract"]
        if not unpacked.is_dir():
            raise RuntimeError("Unexpected toolchain archive layout: " + item["name"])
        if target.exists():
            shutil.rmtree(target)
        shutil.move(unpacked, target)
    marker.write_text(item["sha256"] + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Verify cached inputs; forbid network downloads")
    parser.add_argument("--downloads-only", action="store_true", help="Prepare toolchains only; skip Maven packaging")
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        parser.error("Python 3.12 or newer is required for safe archive extraction")
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("This pinned toolchain targets an Apple Silicon Mac")
    if not args.downloads_only and not shutil.which("mvn"):
        parser.error("Install Maven 3.9+ first, or use --downloads-only")
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    TOOLCHAINS.mkdir(parents=True, exist_ok=True)
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    manifest = json.loads((PORT / "config/downloads.json").read_text())
    for item in manifest["files"]:
        archive = fetch(item, args.offline)
        if item.get("extract"):
            extract(item, archive)
    verify_downloads()
    if not args.downloads_only:
        env = os.environ.copy()
        env["JAVA_HOME"] = str(jdk_home())
        env["PATH"] = str(jdk_home() / "bin") + ":" + env["PATH"]
        command = ["mvn", "-s", str(PORT / "config/maven-settings.xml"),
                   "-Dmaven.repo.local=" + str(ROOT / ".m2/repository"), "-DskipTests", "package"]
        if args.offline:
            command.insert(1, "--offline")
        subprocess.run(command, cwd=ROOT, env=env, check=True)
    print("Ready. Next: python3 ports/ios/tools/build_game.py", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
