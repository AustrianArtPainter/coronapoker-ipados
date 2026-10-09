#!/usr/bin/env python3
"""Observe the native host through fresh TCP connections, without a debugger."""
import argparse
import json
import socket
import time
from datetime import datetime, timezone
from pathlib import Path


def read_status(host, port):
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(5)
        connection.sendall(b"STATUS\n")
        response = bytearray()
        while b"\n" not in response:
            chunk = connection.recv(4096)
            if not chunk:
                raise RuntimeError("Connection closed before status response")
            response.extend(chunk)
            if len(response) > 65536:
                raise RuntimeError("Response exceeds validation limit")
        return json.loads(response.split(b"\n", 1)[0])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=7235)
    parser.add_argument("--seconds", type=int, default=300)
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--require-background", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    samples = []
    errors = []
    consecutive_errors = 0
    with args.output.open("w") as stream:
        while True:
            record = {"observedUTC": datetime.now(timezone.utc).isoformat(),
                      "elapsedSeconds": time.monotonic() - start}
            try:
                status = read_status(args.host, args.port)
                record["status"] = status
                samples.append(status)
                consecutive_errors = 0
            except (OSError, ValueError, RuntimeError) as error:
                record["error"] = str(error)
                errors.append(record)
                consecutive_errors += 1
            stream.write(json.dumps(record) + "\n")
            stream.flush()
            if consecutive_errors >= 3 or time.monotonic() - start >= args.seconds:
                break
            time.sleep(min(args.interval, max(0, args.seconds - (time.monotonic() - start))))
    problems = []
    if len(samples) < 2:
        problems.append("Insufficient status samples")
    else:
        first, last = samples[0], samples[-1]
        if any(sample["runID"] != first["runID"] for sample in samples):
            problems.append("Host process restarted")
        for key in ["ticks", "databaseCommits", "signatureChecks", "connections"]:
            if last[key] <= first[key]:
                problems.append(key + " did not advance")
        if args.require_background and any(not sample["background"] for sample in samples):
            problems.append("At least one sample was foreground")
        if args.require_background and last["backgroundTicks"] <= first["backgroundTicks"]:
            problems.append("Background ticks did not advance")
        if any(not sample["musicPlaying"] or sample["error"] for sample in samples):
            problems.append("Audio stopped or host reported an error")
        if last["maximumTickGapSeconds"] > 5:
            problems.append("A timer gap exceeded five seconds")
    if errors:
        problems.append("TCP observations failed: " + str(len(errors)))
    duration = time.monotonic() - start
    if duration < args.seconds:
        problems.append("Observation ended early")
    report = {"scope": "native platform only; poker game not validated",
              "requestedSeconds": args.seconds, "observedSeconds": duration,
              "samples": len(samples), "errors": errors, "problems": problems,
              "passed": not problems, "first": samples[0] if samples else None,
              "last": samples[-1] if samples else None}
    args.output.with_suffix(".summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ["scope", "observedSeconds", "samples", "passed", "problems"]}))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
