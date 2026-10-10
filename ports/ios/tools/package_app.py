#!/usr/bin/env python3
"""Archive an already signed physical-device app without changing its contents."""
import argparse
import datetime
import hashlib
import json
import plistlib
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from toolchain import game_version

PORT = Path(__file__).resolve().parents[1]
DEFAULT_APP = PORT / "build/game/CoronaPoker.app"
DEFAULT_OUTPUT = PORT / ("dist/CoronaPoker-" + game_version() + "-iPad-development.ipa")


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def package(app, output):
    app, output = app.resolve(), output.resolve()
    info = plistlib.loads((app / "Info.plist").read_bytes())
    if info.get("DTPlatformName") == "iphonesimulator" or "embedded.mobileprovision" not in {
            path.name for path in app.iterdir()}:
        raise RuntimeError("Packaging requires a signed physical-device app")
    subprocess.run(["codesign", "--verify", "--strict", "--deep", str(app)], check=True)
    profile = plistlib.loads(subprocess.check_output(
        ["security", "cms", "-D", "-i", str(app / "embedded.mobileprovision")], stderr=subprocess.DEVNULL))
    bundle = info["CFBundleIdentifier"]
    if profile["Entitlements"]["application-identifier"].split(".", 1)[1] != bundle:
        raise RuntimeError("The embedded profile belongs to another bundle")
    now = datetime.datetime.now(datetime.timezone.utc)
    expiry = profile["ExpirationDate"].replace(tzinfo=datetime.timezone.utc)
    if expiry <= now:
        raise RuntimeError("The signing profile has expired; renew it before packaging")
    output.parent.mkdir(parents=True, exist_ok=True)
    executable = app / info["CFBundleExecutable"]
    executable_hash = digest(executable)
    with tempfile.TemporaryDirectory(prefix="ios-package-", dir=PORT / "build") as directory:
        payload = Path(directory) / "Payload"
        payload.mkdir()
        shutil.copytree(app, payload / app.name, symlinks=True)
        archive = Path(directory) / "application.ipa"
        subprocess.run(["ditto", "-c", "-k", "--keepParent", "--norsrc", str(payload), str(archive)], check=True)
        # Verify the bytes that will be delivered, including the actual executable.
        with zipfile.ZipFile(archive) as zipped:
            corrupt = zipped.testzip()
            if corrupt:
                raise RuntimeError("Archive integrity check failed: " + corrupt)
            packaged = hashlib.sha256()
            with zipped.open("Payload/" + app.name + "/" + info["CFBundleExecutable"]) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    packaged.update(block)
            if packaged.hexdigest() != executable_hash:
                raise RuntimeError("Packaged executable differs from the signed application")
        shutil.move(archive, output)
    result = {
        "bundleIdentifier": bundle,
        "gameVersion": info["CFBundleShortVersionString"],
        "minimumOSVersion": info["MinimumOSVersion"],
        "profileExpiresAt": expiry.isoformat(),
        "createdAt": now.isoformat(),
        "archive": output.name,
        "archiveBytes": output.stat().st_size,
        "archiveSHA256": digest(output),
        "executableSHA256": executable_hash,
        "signatureVerified": True,
        "archiveIntegrityVerified": True,
        "distribution": "personal development; only devices authorized by the embedded profile",
    }
    output.with_suffix(".manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, default=DEFAULT_APP)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    arguments = parser.parse_args()
    package(arguments.app, arguments.output)
