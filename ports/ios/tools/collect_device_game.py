#!/usr/bin/env python3
"""Read a diagnostic game's status/performance over USB; keep device data local."""
import argparse
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

BUNDLE = "com.tonikelope.coronapoker.ios"


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
    parser.add_argument("--device", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=480)
    parser.add_argument("--interval", type=float, default=10)
    args = parser.parse_args()
    if args.interval < 2 or args.seconds < 1:
        parser.error("Use interval >= 2 seconds and duration >= 1 second")
    args.output.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    statuses = []
    performances = []
    failures = []
    with (args.output / "samples.jsonl").open("w") as stream:
        sample = 0
        while time.monotonic() - start < args.seconds:
            sample += 1
            record = {"observedUTC": datetime.now(timezone.utc).isoformat(),
                      "elapsedSeconds": time.monotonic() - start}
            try:
                status = copy(args.device, "ios-game-status.json",
                              args.output / "status.json", args.output / "copy-status.log")
                if status["runID"] != args.run_id:
                    raise RuntimeError("Status belongs to another diagnostic run")
                record["status"] = status
                statuses.append(status)
                # The performance writer flushes every ten seconds; a file
                # from the preceding process may still exist during startup.
                if status["ticks"] >= 12 and (sample % 3 == 1 or status["phase"] == "closed"):
                    performance = copy(args.device, "ios-performance.json",
                                       args.output / f"performance-{sample:03d}.json",
                                       args.output / "copy-performance.log")
                    record["performance"] = performance
                    performances.append(performance)
                print(json.dumps({"sample": sample, "phase": status["phase"],
                                  "hand": status["hand"], "error": status["error"]}), flush=True)
            except (RuntimeError, ValueError, KeyError, OSError) as failure:
                record["readError"] = str(failure)
                failures.append(str(failure))
            stream.write(json.dumps(record) + "\n")
            stream.flush()
            if statuses and (statuses[-1]["phase"] == "closed" or statuses[-1]["error"]):
                break
            time.sleep(min(args.interval, max(0, args.seconds - (time.monotonic() - start))))
    last = statuses[-1] if statuses else {}
    problems = []
    if last.get("phase") != "closed" or last.get("closeReason") != "COMPLETED":
        problems.append("Game did not finish normally")
    if any(s["error"] for s in statuses):
        problems.append("Game reported an error")
    if abs(last.get("finalStacks", 0) - last.get("totalBuyins", 0)) > 0.001:
        problems.append("Final funds are not conserved")
    if not performances:
        problems.append("No performance samples")
    elif any(p["computeMaxActive"] > 2 or p["computeQoS"] != 17
             or p["computeQoSFailures"] != 0 for p in performances):
        problems.append("Compute limit or actual Utility QoS check failed")
    result = {"passed": not problems, "problems": problems, "readErrors": failures,
              "samples": len(statuses), "last": last,
              "scope": "Native game, funds and compute admission; audio needs physical listening"}
    (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ["passed", "problems", "samples", "scope"]}), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
