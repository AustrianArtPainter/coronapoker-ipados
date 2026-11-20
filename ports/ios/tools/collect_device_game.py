#!/usr/bin/env python3
"""Read a diagnostic game's status/performance over USB; keep device data local."""
import argparse
import json
import math
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from status_freshness import assess_status_freshness
from toolchain import game_version

BUNDLE = "com.tonikelope.coronapoker.ios"


def audit_records(observations, run_id, version, profile_enabled):
    statuses = [row["status"] for row in observations if isinstance(row.get("status"), dict)]
    performances = [row["performance"] for row in observations if isinstance(row.get("performance"), dict)]
    failures = [str(row["readError"]) for row in observations if "readError" in row]
    freshness = assess_status_freshness(observations, run_id=run_id, expected_version=version)
    problems = list(freshness["problems"])
    if failures:
        problems.append("An independent device status/performance read failed")
    last = statuses[-1] if statuses else {}
    if last.get("phase") != "closed" or last.get("closeReason") != "COMPLETED":
        problems.append("Game did not finish normally")
    funds = [last.get("finalStacks"), last.get("totalBuyins")]
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in funds):
        problems.append("Final funds are missing or invalid")
    elif abs(funds[0] - funds[1]) > 0.001:
        problems.append("Final funds are not conserved")
    if not profile_enabled:
        problems.append("Performance acceptance requires this launch to use CP_IOS_PROFILE=1")
    if len(performances) < 2:
        problems.append("At least two advancing performance reports from this launch are required")
    expected_isolation = {"audioIsolation": "normal", "silentBackendInstalled": False,
            "suppressedSoundStarts": 0, "suppressedMusicStarts": 0,
            "cardCryptoIsolation": "real", "mockDeals": 0, "mockReveals": 0,
            "mockSignatures": 0, "realCardCryptoCallsInMock": 0,
            "cardAnimationIsolation": "normal", "instantCardEvents": 0,
            "instantCardSounds": 0, "tablePaintIsolation": "normal",
            "tablePaintSkippedFrames": 0, "computeLimit": 2,
            "computeQoS": 17, "computeQoSFailures": 0}
    counts = []
    for performance in performances:
        if any(type(performance.get(key)) is not type(value) or performance.get(key) != value
               for key, value in expected_isolation.items()):
            problems.append("Performance evidence is missing or used audio/crypto/animation/render isolation")
        maximum = performance.get("computeMaxActive")
        if type(maximum) is not int or not 0 <= maximum <= 2:
            problems.append("Compute admission limit failed or is missing")
        metrics = performance.get("metrics")
        interval = metrics.get("frame.interval") if isinstance(metrics, dict) else None
        frame = interval.get("count") if isinstance(interval, dict) else None
        allocated = performance.get("allocatedBytes")
        completed = performance.get("computeCompleted")
        real_crypto = performance.get("realCardCryptoCalls")
        if any(type(value) is not int or value < 0 for value in [frame, allocated, completed, real_crypto]):
            problems.append("Performance freshness counters are missing or invalid")
        else:
            counts.append((frame, allocated, completed, real_crypto))
    if counts:
        if any(any(later < earlier for earlier, later in zip(a, b)) for a, b in zip(counts, counts[1:])):
            problems.append("Performance counters went backwards or retained a previous process report")
        if len(counts) < 2 or counts[-1][0] <= counts[0][0] or counts[-1][1] <= counts[0][1]:
            problems.append("Performance reports did not advance; a cached report cannot prove CP_IOS_PROFILE=1")
        if counts[-1][2] <= 0 or counts[-1][3] <= 0:
            problems.append("No actual compute or real card-crypto work was observed")
    problems = list(dict.fromkeys(problems))
    return {"passed": not problems, "problems": problems, "readErrors": failures,
            "runID": run_id, "gameVersion": version, "profileLaunchConfirmed": profile_enabled,
            "samples": len(statuses), "performanceSamples": len(performances),
            "last": last, "freshness": freshness,
            "scope": "Native game, fresh status, funds and real compute/performance; audio needs physical listening"}


def copy(device, source, destination, log):
    with log.open("w") as stream:
        result = subprocess.run([
            "xcrun", "devicectl", "--timeout", "25", "device", "copy", "from",
            "--device", device, "--domain-type", "appDataContainer",
            "--domain-identifier", BUNDLE, "--source", "Documents/" + source,
            "--destination", str(destination)], stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError("Device copy failed; inspect the private local log")
    return json.loads(destination.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=480)
    parser.add_argument("--interval", type=float, default=10)
    parser.add_argument("--profile-enabled", action="store_true",
                        help="Confirm this exact run was launched with CP_IOS_PROFILE=1; advancing reports are still required")
    parser.add_argument("--audit-only", action="store_true",
                        help="Reassess the existing output/samples.jsonl without accessing the device")
    args = parser.parse_args()
    version = game_version()
    if not args.audit_only and not args.device:
        parser.error("--device is required unless --audit-only is used")
    if args.interval < 2 or args.seconds < 1:
        parser.error("Use interval >= 2 seconds and duration >= 1 second")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.audit_only:
        observations = [json.loads(line) for line in (args.output / "samples.jsonl").read_text().splitlines()
                        if line.strip()]
        result = audit_records(observations, args.run_id, version, args.profile_enabled)
        (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({k: result[k] for k in ["passed", "problems", "samples", "scope"]}), flush=True)
        raise SystemExit(0 if result["passed"] else 1)
    start = time.monotonic()
    statuses = []
    observations = []
    with (args.output / "samples.jsonl").open("w") as stream:
        sample = 0
        while time.monotonic() - start < args.seconds:
            sample += 1
            record = {"observedUTC": datetime.now(timezone.utc).isoformat(),
                      "elapsedSeconds": time.monotonic() - start}
            try:
                status = copy(args.device, "ios-game-status.json",
                              args.output / "status.json", args.output / "copy-status.log")
                if status["runID"] != args.run_id or status.get("gameVersion") != version:
                    raise RuntimeError("Status belongs to another diagnostic run/version")
                record["status"] = status
                statuses.append(status)
                # The performance writer flushes every ten seconds; a file
                # from the preceding process may still exist during startup.
                if args.profile_enabled and status["ticks"] >= 12 and (sample % 3 == 1 or status["phase"] == "closed"):
                    performance = copy(args.device, "ios-performance.json",
                                       args.output / f"performance-{sample:03d}.json",
                                       args.output / "copy-performance.log")
                    record["performance"] = performance
                print(json.dumps({"sample": sample, "phase": status["phase"],
                                  "hand": status["hand"], "error": status["error"]}), flush=True)
            except (RuntimeError, ValueError, KeyError, OSError) as failure:
                record["readError"] = str(failure)
            stream.write(json.dumps(record) + "\n")
            stream.flush()
            observations.append(record)
            if statuses and (statuses[-1]["phase"] == "closed" or statuses[-1]["error"]):
                break
            time.sleep(min(args.interval, max(0, args.seconds - (time.monotonic() - start))))
    result = audit_records(observations, args.run_id, version, args.profile_enabled)
    (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ["passed", "problems", "samples", "scope"]}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
