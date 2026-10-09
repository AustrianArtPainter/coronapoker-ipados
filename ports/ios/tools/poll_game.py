#!/usr/bin/env python3
"""Collect fresh, read-only TCP evidence from the real native Java poker game."""
import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from poll_host import read_status

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=7235)
    parser.add_argument("--seconds", type=int, default=900)
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--require-background", action="store_true")
    parser.add_argument("--until-closed", action="store_true")
    parser.add_argument("--wait-ready-seconds", type=int, default=0,
                        help="Wait for this run's listener before starting the measured observation")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.wait_ready_seconds > 0:
        deadline = time.monotonic() + args.wait_ready_seconds
        while True:
            try:
                ready = read_status(args.host,args.port)
                if ready.get("kind") == "native-java-game" and ready.get("runID") == args.run_id:
                    break
            except (OSError,ValueError,RuntimeError):
                pass
            if time.monotonic() >= deadline:
                raise SystemExit("The requested game's status listener was not ready before observation")
            time.sleep(0.5)
    start = time.monotonic()
    samples, errors = [], []
    consecutive_errors = 0
    with args.output.open("w") as stream:
        while True:
            record = {"observedUTC": datetime.now(timezone.utc).isoformat(), "elapsedSeconds": time.monotonic()-start}
            try:
                status = read_status(args.host,args.port)
                record["status"] = status
                samples.append(status)
                consecutive_errors = 0
            except (OSError,ValueError,RuntimeError) as error:
                record["error"] = str(error)
                errors.append(record)
                consecutive_errors += 1
            stream.write(json.dumps(record)+"\n");stream.flush()
            if consecutive_errors >= 3 or time.monotonic()-start >= args.seconds:
                break
            if samples and (samples[-1]["error"] or (args.until_closed and samples[-1]["phase"] == "closed")):
                break
            time.sleep(min(args.interval,max(0,args.seconds-(time.monotonic()-start))))
    problems = []
    if len(samples) < 2: problems.append("Insufficient status samples")
    else:
        first,last = samples[0],samples[-1]
        if any(sample["kind"] != "native-java-game" or sample["runID"] != args.run_id for sample in samples):
            problems.append("Unexpected game process or run ID")
        for key in ["ticks","events","finishedShuffles","persistedFinishedHands","persistedSignedActions"]:
            if last[key] <= first[key]: problems.append(key+" did not advance")
        if any(sample["error"] for sample in samples): problems.append("The game reported an error")
        if any(not sample["background"] and sample.get("projectionSuspended", False) for sample in samples):
            problems.append("A foreground table retained a suspended projection")
        if args.require_background:
            if any(not sample["background"] or not sample["musicPlaying"] for sample in samples):
                problems.append("Not continuously in background with audible music")
            if last["backgroundTicks"] <= first["backgroundTicks"]: problems.append("Background ticks did not advance")
        if last["maximumTickGapSeconds"] > 5: problems.append("A timer gap exceeded five seconds")
        if args.until_closed and last["phase"] != "closed": problems.append("Game did not close within validation duration")
        if args.until_closed and (last.get("closeReason") != "COMPLETED" or abs(last.get("finalStacks",0)-last.get("totalBuyins",0)) > 0.001):
            problems.append("Game did not finish normally with conserved funds")
    if errors: problems.append("TCP observations failed: "+str(len(errors)))
    duration = time.monotonic()-start
    if not args.until_closed and duration < args.seconds: problems.append("Observation ended early")
    report = {"scope":"real native Java game; signed row presence requires separate cryptographic audit",
              "requestedSeconds":args.seconds,"observedSeconds":duration,"samples":len(samples),
              "errors":errors,"problems":problems,"passed":not problems,
              "first":samples[0] if samples else None,"last":samples[-1] if samples else None}
    args.output.with_suffix(".summary.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({key:report[key] for key in ["scope","observedSeconds","samples","passed","problems","last"]}))
    raise SystemExit(0 if report["passed"] else 1)

if __name__ == "__main__": main()
