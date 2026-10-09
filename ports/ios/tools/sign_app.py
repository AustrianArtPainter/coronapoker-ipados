#!/usr/bin/env python3
"""Sign an AOT application with an Xcode-created personal-team device profile."""
import argparse
import datetime
import hashlib
import json
import plistlib
import re
import shutil
import subprocess
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]

def sign(app, template, certificate):
    app = app.resolve()
    template = template.resolve()
    info = plistlib.loads((app / "Info.plist").read_bytes())
    profile_file = template / "embedded.mobileprovision"
    profile = plistlib.loads(subprocess.check_output(["security", "cms", "-D", "-i", str(profile_file)], stderr=subprocess.DEVNULL))
    entitlements = profile["Entitlements"]
    expected = entitlements["application-identifier"].split(".", 1)[1]
    if info["CFBundleIdentifier"] != expected:
        raise RuntimeError("Signing profile does not match the AOT app bundle identifier")
    now = datetime.datetime.now(datetime.timezone.utc)
    expiry = profile["ExpirationDate"].replace(tzinfo=datetime.timezone.utc)
    if expiry <= now:
        raise RuntimeError("Xcode profile has expired; rebuild the signing target")
    if certificate is None:
        available = set(re.findall(r"\b[0-9A-F]{40}\b", subprocess.check_output(
            ["security", "find-identity", "-v", "-p", "codesigning"], text=True)))
        matches = [hashlib.sha1(data).hexdigest().upper() for data in profile["DeveloperCertificates"]
                   if hashlib.sha1(data).hexdigest().upper() in available]
        if not matches:
            raise RuntimeError("No available signing identity matches this Xcode profile")
        certificate = matches[0]
    build = PORT / "build/signing"
    build.mkdir(parents=True, exist_ok=True)
    entitlement_file = build / "entitlements.plist"
    entitlement_file.write_bytes(plistlib.dumps(entitlements))
    shutil.copy2(profile_file, app / "embedded.mobileprovision")
    # Sign embedded native frameworks before the enclosing application.
    for framework in sorted((app / "Frameworks").glob("*.framework")):
        subprocess.run(["codesign", "--force", "--sign", certificate, str(framework)], check=True)
    subprocess.run(["codesign", "--force", "--sign", certificate,
                    "--entitlements", str(entitlement_file), str(app)], check=True)
    subprocess.run(["codesign", "--verify", "--strict", "--deep", str(app)], check=True)
    executable = app / info["CFBundleExecutable"]
    result = {"bundleIdentifier": expected, "profileExpiresAt": expiry.isoformat(),
              "executableSHA256": hashlib.sha256(executable.read_bytes()).hexdigest(), "signatureVerified": True}
    (PORT / "validation").mkdir(parents=True, exist_ok=True)
    (PORT / "validation/app-signature.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("app", type=Path)
    parser.add_argument("template", type=Path)
    parser.add_argument("--certificate", help="Optional identity override; otherwise match the Xcode profile")
    args = parser.parse_args()
    sign(args.app, args.template, args.certificate)
