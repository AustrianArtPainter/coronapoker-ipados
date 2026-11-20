#!/usr/bin/env python3
"""Capture/check desktop sources so the independent iOS port stays reviewable."""
import argparse
import hashlib
import json
from pathlib import Path
from toolchain import game_version

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
BASELINE = PORT / "validation" / ("desktop-source-baseline-" + game_version() + ".json")


def source_files():
    roots = [ROOT / "src", ROOT / "modules", ROOT / "tools", ROOT / "coronaupdater", ROOT / ".mvn"]
    files = [ROOT / "pom.xml"]
    for root in roots:
        if root.exists():
            files.extend(p for p in root.rglob("*") if p.is_file()
                         and not any(part in {"target", ".m2", ".git", "build"}
                                     for part in p.relative_to(root).parts))
    return sorted(set(files))


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def snapshot():
    return {str(path.relative_to(ROOT)): digest(path) for path in source_files()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("capture", "check"))
    args = parser.parse_args()
    current = snapshot()
    if args.mode == "capture":
        if BASELINE.exists():
            raise SystemExit("Baseline already exists; it must not be silently replaced")
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps({"protocol": game_version(), "sha256": current}, indent=2) + "\n")
        print(json.dumps({"capturedFiles": len(current), "baseline": str(BASELINE)}))
    else:
        expected = json.loads(BASELINE.read_text())["sha256"]
        changed = sorted(p for p in expected.keys() | current.keys() if expected.get(p) != current.get(p))
        print(json.dumps({"checkedFiles": len(expected), "changed": changed}))
        if changed:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
