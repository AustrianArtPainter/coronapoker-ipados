#!/usr/bin/env python3
"""Build/run the host-only exact-PID audio helper; default operation only builds.

Examples (no audio capture or TCC request):
  python3 ports/ios/tools/run_app_audio_tap.py --build-only
  python3 ports/ios/tools/run_app_audio_tap.py --self-test
  python3 ports/ios/tools/run_app_audio_tap.py --inspect --pid PID --app-bundle INSTALLED_APP

Capture is a separate, explicit operation, requiring the inspected binary hash:
  ... --capture --pid PID --app-bundle INSTALLED_APP --expected-sha256 HASH \
      --seconds 120 --output NEW_PRIVATE_DIRECTORY [--tap-drift-compensation on|off]

macOS may ask for System Audio Recording access for CoronaPoker App Audio Tap
at the first --capture. This tool never accepts that permission on the user's
behalf. It never selects a microphone or a global/all-process audio tap.
Output: output.f32le (interleaved Float32 PCM), chunks.tsv (mach timestamps and
frame/byte offsets), metadata.json (format, target hash and transport validity).
Tap drift compensation defaults to on. The optional on/off control changes only
the private capture aggregate; actual drift/clock/composition are read back.
--allow-composition-only-drift-readback explicitly permits weaker configuration
readback when the private native drift getter is unavailable. It still verifies
the exact private tap-only composition and never verifies the resampler itself.
The target is restricted to the installed CoronaPoker simulator App. A missing,
shared, ambiguous or mismatched audio process is an error with no fallback.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import subprocess

PORT = Path(__file__).resolve().parents[1]
SOURCE = PORT / "tools/app_audio_tap.swift"
BUILD = PORT / "validation/app-audio-tap-helper"
BUNDLE = BUILD / "CoronaPoker App Audio Tap.app"
CONTENTS = BUNDLE / "Contents"
EXECUTABLE = CONTENTS / "MacOS/AppAudioTap"


def build():
    compiler = subprocess.check_output(["xcrun", "--find", "swiftc"], text=True).strip()
    sdk = subprocess.check_output(["xcrun", "--sdk", "macosx", "--show-sdk-path"], text=True).strip()
    version = subprocess.check_output([compiler, "--version"], text=True)
    fingerprint = hashlib.sha256(SOURCE.read_bytes() + Path(__file__).read_bytes() + sdk.encode() + version.encode()).hexdigest()
    manifest = BUILD / "build.json"
    previous = json.loads(manifest.read_text()) if manifest.exists() else {}
    if not EXECUTABLE.exists() or previous.get("fingerprint") != fingerprint:
        EXECUTABLE.parent.mkdir(parents=True, exist_ok=True)
        cache = BUILD / "module-cache"
        cache.mkdir(exist_ok=True)
        subprocess.run([compiler, "-O", "-swift-version", "5", "-target", "arm64-apple-macos15.0",
                        "-sdk", sdk, "-module-cache-path", str(cache), "-framework", "CoreAudio",
                        "-framework", "AudioToolbox", "-framework", "Foundation",
                        str(SOURCE), "-o", str(EXECUTABLE)], check=True)
        (CONTENTS / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": "com.tonikelope.coronapoker.validation.app-audio-tap",
            "CFBundleExecutable": "AppAudioTap", "CFBundleName": "CoronaPoker App Audio Tap",
            "CFBundleDisplayName": "CoronaPoker App Audio Tap", "CFBundlePackageType": "APPL",
            "CFBundleVersion": "1", "LSMinimumSystemVersion": "15.0",
            "NSAudioCaptureUsageDescription": "仅采集指定 CoronaPoker 模拟器 App 的输出音频，用于定位爆音与卡顿；不采集麦克风或其他应用。",
        }))
        subprocess.run(["codesign", "--force", "--sign", "-", "--identifier",
                        "com.tonikelope.coronapoker.validation.app-audio-tap", str(BUNDLE)], check=True)
        manifest.write_text(json.dumps({"fingerprint": fingerprint, "sourceSHA256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
                                      "swiftVersion": version.strip(), "target": "arm64-apple-macos15.0"}, indent=2) + "\n")
    return EXECUTABLE


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--build-only", action="store_true")
    modes.add_argument("--self-test", action="store_true")
    modes.add_argument("--inspect", action="store_true")
    modes.add_argument("--capture", action="store_true")
    parser.add_argument("--pid", type=int)
    parser.add_argument("--app-bundle", type=Path)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--seconds", type=float)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--tap-drift-compensation", choices=["on", "off"])
    parser.add_argument("--allow-composition-only-drift-readback", action="store_true")
    args = parser.parse_args()
    target_options = args.pid is not None or args.app_bundle is not None or args.expected_sha256 is not None
    capture_options = args.seconds is not None or args.output is not None or args.tap_drift_compensation is not None or args.allow_composition_only_drift_readback
    if args.inspect or args.capture:
        if args.pid is None or args.app_bundle is None:
            parser.error("--pid and --app-bundle are required")
        if args.capture and (args.expected_sha256 is None or args.seconds is None or args.output is None):
            parser.error("capture requires --expected-sha256, --seconds and --output")
        if args.inspect and capture_options:
            parser.error("inspection does not accept capture options")
    elif target_options or capture_options:
        parser.error("select --inspect or --capture explicitly when passing a target")
    executable = build()
    command = [str(executable)]
    if args.self_test:
        command.append("--self-test")
    elif args.inspect or args.capture:
        command.extend(["--capture" if args.capture else "--inspect", "--pid", str(args.pid),
                        "--app-bundle", str(args.app_bundle.resolve())])
        if args.expected_sha256:
            command.extend(["--expected-sha256", args.expected_sha256])
        if args.capture:
            command.extend(["--seconds", str(args.seconds), "--output", str(args.output.resolve())])
            if args.tap_drift_compensation is not None:
                command.extend(["--tap-drift-compensation", args.tap_drift_compensation])
            if args.allow_composition_only_drift_readback:
                command.append("--allow-composition-only-drift-readback")
    else:
        print(executable)
        return
    os.execv(executable, command)


if __name__ == "__main__":
    main()
