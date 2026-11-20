#!/usr/bin/env python3
"""Build optionally, renew personal signing optionally, and update the connected iPad."""
import argparse
import datetime
import json
import os
import subprocess
import sys
from pathlib import Path
from package_app import package, DEFAULT_OUTPUT
from sign_app import sign

PORT = Path(__file__).resolve().parents[1]
APP = PORT / "build/game/CoronaPoker.app"
TEMPLATE = PORT / "build/java-signing/Build/Products/Release-iphoneos/CoronaPokerHostValidation.app"
BUNDLE = "com.tonikelope.coronapoker.ios"


def run(arguments, log):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w") as stream:
        subprocess.run([str(argument) for argument in arguments], cwd=PORT.parents[1],
                       stdout=stream, stderr=subprocess.STDOUT, check=True)
    print("Completed: " + log.name, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default=os.environ.get("CP_IOS_DEVICE"),
                        help="Your connected device UDID or CoreDevice ID (or CP_IOS_DEVICE)")
    parser.add_argument("--team", default=os.environ.get("CP_IOS_TEAM"),
                        help="Your Xcode development team (or CP_IOS_TEAM)")
    parser.add_argument("--build", action="store_true", help="Recompile the full native game before signing")
    parser.add_argument("--refresh-profile", action="store_true", help="Ask logged-in Xcode to create a fresh device profile")
    parser.add_argument("--no-install", action="store_true", help="Sign/package only; do not modify the running iPad")
    parser.add_argument("--package", action="store_true", help="Also create the development IPA and manifest")
    parser.add_argument("--launch", action="store_true", help="Launch the normal game, ending any existing game process")
    args = parser.parse_args()
    if args.no_install and args.launch:
        parser.error("--launch requires installation")
    if (args.refresh_profile or not args.no_install) and not args.device:
        parser.error("--device is required to refresh a device profile or install")
    if args.refresh_profile and not args.team:
        parser.error("--team is required for --refresh-profile")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    logs = PORT / "validation" / ("install-ipad-" + stamp)
    if args.build:
        run([sys.executable, PORT / "tools/build_game.py"], logs / "build.log")
    if args.refresh_profile:
        run([sys.executable, PORT / "tools/generate_validation_project.py"], logs / "project.log")
        run(["xcodebuild", "-project", PORT / "validation-host/CoronaPokerHostValidation.xcodeproj",
             "-scheme", "CoronaPokerHostValidation", "-configuration", "Release",
             "-destination", "id=" + args.device, "-derivedDataPath", PORT / "build/java-signing",
             "-allowProvisioningUpdates", "-allowProvisioningDeviceRegistration",
             "PRODUCT_BUNDLE_IDENTIFIER=" + BUNDLE, "DEVELOPMENT_TEAM=" + args.team, "build"],
            logs / "profile.log")
    sign(APP, TEMPLATE, None)
    if args.package:
        package(APP, DEFAULT_OUTPUT)
    if not args.no_install:
        run(["xcrun", "devicectl", "device", "install", "app", "--device", args.device,
             APP, "--json-output", logs / "device-install.json"], logs / "device-install.log")
        if args.launch:
            # No QA environment variables: this is the user's normal saved game.
            run(["xcrun", "devicectl", "device", "process", "launch", "--device", args.device,
                 "--terminate-existing", BUNDLE, "--json-output", logs / "device-launch.json"], logs / "device-launch.log")
    print(json.dumps({"bundleIdentifier": BUNDLE, "installed": not args.no_install,
                      "normalGameLaunched": args.launch, "logs": str(logs)}))


if __name__ == "__main__":
    main()
