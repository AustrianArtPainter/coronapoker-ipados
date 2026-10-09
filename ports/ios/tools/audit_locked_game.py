#!/usr/bin/env python3
"""Combine physical lock, game progress, timeout policy and original-peer evidence."""
import argparse
from collections import Counter
import json
import re
from pathlib import Path


def read(path):
    return json.loads(path.read_text())


def audit(args):
    samples = [json.loads(line) for line in args.samples.read_text().splitlines()]
    observed = read(args.samples.with_suffix(".summary.json"))
    confirmation, build = read(args.confirmation), read(args.build)
    native, desktop = read(args.native_audit), read(args.desktop_audit)
    problems = []
    if not observed.get("passed") or observed.get("requestedSeconds", 0) < 900 or observed.get("observedSeconds", 0) < 900:
        problems.append("The independent full 15-minute observation did not pass")
    if not samples or any("error" in row for row in samples):
        problems.append("Missing samples or a TCP observation failure")
        raise ValueError("Cannot audit missing/failed samples")
    statuses = [row["status"] for row in samples]
    first, last = statuses[0], statuses[-1]
    run = first["runID"]
    if confirmation.get("runID") != run or not confirmation.get("userStatement"):
        problems.append("Physical lock confirmation does not identify this run")
    if build.get("scenario") != run or build.get("launchHasConsoleOrDebugger") is not False:
        problems.append("The build/launch record does not identify a detached game")
    if any(s.get("kind") != "native-java-game" or s.get("runID") != run or s.get("mode") != "host"
           or not s.get("background") or not s.get("musicPlaying") or s.get("error") for s in statuses):
        problems.append("A sample was not the same background native host with audible music")
    maximum_tick_gap = max(s["maximumTickGapSeconds"] for s in statuses)
    if maximum_tick_gap > 5:
        problems.append("Native timer paused for over five seconds")
    maximum_observation_gap = max(b["elapsedSeconds"] - a["elapsedSeconds"] for a, b in zip(samples, samples[1:]))
    if maximum_observation_gap > 10:
        problems.append("The TCP observation has a gap over ten seconds")
    keys = ["ticks", "backgroundTicks", "events", "finishedShuffles", "endedHands", "payouts",
            "persistedFinishedHands", "persistedSignedActions"]
    progress = {key: last[key] - first[key] for key in keys}
    if any(value <= 0 for value in progress.values()) or any(
            b[key] < a[key] for key in keys for a, b in zip(statuses, statuses[1:])):
        problems.append("Game progress stopped or counters went backwards")
    events = [json.loads(line) for line in args.events.read_text().splitlines()]
    window = [event for event in events if first["sequence"] < event["sequence"] <= last["sequence"]]
    if not window or any(not event.get("background") for event in window):
        problems.append("The visual event evidence is missing or contains foreground activity")
    own = Counter(event["action"] for event in window if event.get("type") == "PlayerAction"
                  and event.get("player") == args.nickname)
    allowed = {"WAITING", "CHECK", "FOLD", "SMALL_BLIND", "BIG_BLIND"}
    if any(action not in allowed for action in own) or not own.get("CHECK") or not own.get("FOLD"):
        problems.append("Local background actions violated or did not exercise both CHECK/FOLD branches")
    if not native.get("passed") or not desktop.get("passed"):
        problems.append("A database cryptographic audit did not pass")
    for key in ["completedHands", "verifiedActionSignatures", "actionRecordsSHA256"]:
        if native.get(key) != desktop.get(key):
            problems.append("Native/desktop database mismatch: " + key)
    if native.get("completedHands", 0) < last["persistedFinishedHands"]:
        problems.append("The database does not include the observed completed hands")
    if any(abs(record.get("finalStacks", 0) - record.get("totalBuyins", 0)) > 0.001
           or record.get("tamperedAmountsRejected") != record.get("verifiedActionSignatures")
           for record in [native, desktop]):
        problems.append("Funds or signature tamper rejection did not pass")
    log = args.desktop_log.read_text()
    decks = {int(n) for n in re.findall(r"SHUFFLE-VERIFY: deck verified OK \(hand (\d+)\)", log)}
    consensus = {int(n) for n in re.findall(r"Hand (\d+) verified: 2 receipts unanimous", log)}
    completed = set(range(first["persistedFinishedHands"] + 1, last["persistedFinishedHands"] + 1))
    if not completed.issubset(decks & consensus):
        problems.append("Original desktop deck proofs or unanimous receipts are missing")
    result = {
        "scope": "real native iPad full game hosting an unmodified original desktop GUI during confirmed physical lock",
        "runID": run, "passed": not problems, "problems": problems,
        "observedSeconds": observed["observedSeconds"], "samples": len(samples),
        "maximumNativeTickGapSeconds": maximum_tick_gap,
        "maximumObservationGapSeconds": maximum_observation_gap,
        "progress": progress, "localBackgroundActions": dict(sorted(own.items())),
        "completedHandsWithOriginalDesktopProofs": len(completed),
        "databaseCompletedHands": native["completedHands"],
        "verifiedActionSignatures": native["verifiedActionSignatures"],
        "tamperedAmountsRejected": native["tamperedAmountsRejected"],
        "bothSidesActionRecordsSHA256": native["actionRecordsSHA256"],
        "finalStacks": native["finalStacks"], "totalBuyins": native["totalBuyins"],
        "nativeExecutableSHA256": build["executableSHA256"],
        "originalDesktopGUIJarSHA256": build["desktopGUIJarSHA256"],
        "limitations": "audible music required; measured device/OS only; system interruption and suspension conditions remain",
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))
    return result["passed"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["samples", "confirmation", "build", "events", "native-audit", "desktop-audit", "desktop-log", "output"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--nickname", default="IPAD-QA")
    raise SystemExit(0 if audit(parser.parse_args()) else 1)
