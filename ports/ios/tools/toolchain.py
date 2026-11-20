"""Locations and integrity checks for the explicitly pinned public toolchain."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]


def game_version():
    """One reviewed version for the wire handshake, assets and app packaging."""
    version = json.loads((PORT / "config/runtime-lock.json").read_text())["desktopProtocolVersion"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+", version):
        raise RuntimeError("Invalid pinned game version")
    pom = ET.parse(PORT.parents[1] / "pom.xml").getroot()
    if pom.findtext("{http://maven.apache.org/POM/4.0.0}version") != version:
        raise RuntimeError("Pinned iOS version and upstream Maven version differ")
    return version


def jdk_home():
    lock = json.loads((PORT / "config/runtime-lock.json").read_text())
    return PORT / lock["hostJDK"]["home"]


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def verify_downloads():
    manifest = json.loads((PORT / "config/downloads.json").read_text())
    for item in manifest["files"]:
        path = PORT / ".cache/downloads" / item["name"]
        if not path.is_file() or digest(path) != item["sha256"]:
            raise RuntimeError("Missing or changed dependency: " + item["name"]
                               + "; run python3 ports/ios/tools/bootstrap.py")
    if not (jdk_home() / "bin/javac").is_file():
        raise RuntimeError("Pinned JDK is missing; run ports/ios/tools/bootstrap.py")
