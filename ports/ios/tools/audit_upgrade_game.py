#!/usr/bin/env python3
"""Check a real device's hot-join or spectator/rebuy lifecycle from local evidence."""
import argparse
import json
import math
from pathlib import Path
from status_freshness import assess_status_freshness


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def integer(value):
    return type(value) is int and value >= 0


def amount(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def native_shape(record):
    return (all(type(record.get(key)) is bool for key in
                ["localWarming", "localSpectator", "localActive", "localExited"])
            and all(integer(record.get(key)) for key in
                    ["utcMillis", "sequence", "hand", "localBuyin", "localRebuyCount", "localImmediateRebuyAmount"])
            and record["utcMillis"] > 0 and amount(record.get("localStack")))


def admitted(record):
    return (record["localActive"] and not record["localWarming"]
            and not record["localSpectator"] and not record["localExited"])


def spectator(record):
    return (record["localSpectator"] and not record["localWarming"]
            and not record["localExited"] and record["localStack"] == 0)


def audit(args):
    observations, all_events = rows(args.statuses), rows(args.events)
    statuses = [row["status"] for row in observations if "status" in row]
    problems, evidence = [], {}

    def finish():
        result = {"passed": not problems, "problems": problems, "scenario": args.scenario,
                  "runID": args.run_id, "gameVersion": args.version, "evidence": evidence,
                  "desktopCompared": bool(args.desktop_states),
                  "scope": "real native lifecycle observations; peer JAR identity and cryptographic/funds audits are separate"}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result))
        return result["passed"]

    if not statuses or not all_events:
        problems.append("Status samples and event evidence are both required")
        return finish()
    if any("error" in row or "readError" in row for row in observations):
        problems.append("An independent status read failed")
    freshness = assess_status_freshness(observations, run_id=args.run_id, expected_version=args.version)
    problems.extend(freshness["problems"])
    if any(not native_shape(record) for record in [*statuses, *all_events]):
        problems.append("Native lifecycle booleans, amounts, sequence, hand or timestamps are missing or invalid")
    if any(not integer(s.get("localRebuyTransfers")) for s in statuses):
        problems.append("Native local canonical-rebuy counters are missing or invalid")
    if any(e.get("runID") != args.run_id for e in all_events):
        problems.append("Event evidence belongs to another run")
    if problems:
        return finish()
    if any(b["sequence"] <= a["sequence"] or b["utcMillis"] < a["utcMillis"]
           for a, b in zip(all_events, all_events[1:])):
        problems.append("Visual events are not strictly ordered or their clock went backwards")
    if any(b["localRebuyTransfers"] < a["localRebuyTransfers"] for a, b in zip(statuses, statuses[1:])):
        problems.append("The native local rebuy counter went backwards")
    window_start, window_end = statuses[0]["utcMillis"], statuses[-1]["utcMillis"]
    events = [e for e in all_events if window_start <= e["utcMillis"] <= window_end]
    if not events or max(e["sequence"] for e in events) < statuses[-1]["sequence"]:
        problems.append("Copied event evidence does not cover the final observed native sequence; collect it again")
    if any(e["localWarming"] and e.get("player") == args.nickname
           and (e.get("type") == "DealHoleCard" or e.get("type") == "PlayerAction"
                and e.get("action") != "WAITING") for e in events):
        problems.append("The warming observer received a local card or poker action")
    previous = {event["sequence"]: all_events[i - 1] for i, event in enumerate(all_events) if i > 0}
    start = pending = rebought = deal = None
    if args.scenario == "hotjoin":
        warming = [s for s in statuses if s["localWarming"] and not s["localExited"] and s.get("street") == "FLOP"
                   and integer(s.get("publicFaceUpCards")) and s["publicFaceUpCards"] == args.public_cards]
        if not warming or not any(integer(s.get("hotJoinSnapshots")) and s["hotJoinSnapshots"] > 0 for s in statuses):
            problems.append("No authoritative warming snapshot with the expected public board was observed")
        start = next((e for e in events if e.get("type") == "HotJoinState" and e["localWarming"]), None)
        observed_hand = max((s["hand"] for s in warming), default=0)
        deal = next((e for e in events if start and e["sequence"] > start["sequence"]
                     and e.get("type") == "DealHoleCard" and e.get("player") == args.nickname and admitted(e)), None)
        if start is None or deal is None or observed_hand <= 0 or deal["hand"] != observed_hand + 1:
            problems.append("The warming observer did not enter the immediately following hand with active local cards")
        evidence = {"warmingSamples": len(warming), "observedHand": observed_hand,
                    "snapshotSequence": None if start is None else start["sequence"],
                    "firstAdmittedDealSequence": None if deal is None else deal["sequence"],
                    "firstAdmittedHand": None if deal is None else deal["hand"]}
    else:
        start = next((e for e in events if spectator(e)), None)
        pending = next((e for e in events if start and e["sequence"] > start["sequence"]
                        and e.get("type") == "ImmediateRebuyStatus" and e.get("player") == args.nickname
                        and integer(e.get("rebuyAmount")) and e["rebuyAmount"] > 0 and spectator(e)), None)
        # A Rebuy for another participant must leave the local buy-in/count
        # unchanged. Require the local state transition on this exact event,
        # rather than accepting balances that were already changed earlier.
        if pending:
            for event in events:
                prior = previous.get(event["sequence"])
                if (event["sequence"] > pending["sequence"] and event.get("type") == "Rebuy" and prior
                        and not event["localWarming"] and not event["localExited"]
                        and event["localBuyin"] == prior["localBuyin"] + pending["rebuyAmount"]
                        and event["localRebuyCount"] == prior["localRebuyCount"] + 1
                        and event["localBuyin"] == start["localBuyin"] + pending["rebuyAmount"]
                        and event["localRebuyCount"] == start["localRebuyCount"] + 1
                        and abs(event["localStack"] - prior["localStack"] - pending["rebuyAmount"]) < 0.001):
                    rebought = event
                    break
        deal = next((e for e in events if rebought and e["sequence"] > rebought["sequence"]
                     and e.get("type") == "DealHoleCard" and e.get("player") == args.nickname
                     and e["hand"] == pending["hand"] + 1 and admitted(e)), None)
        before = next((s for s in reversed(statuses) if rebought and s["sequence"] < rebought["sequence"]
                       and s["utcMillis"] <= rebought["utcMillis"]), None)
        after = next((s for s in statuses if deal and s["sequence"] >= deal["sequence"]
                      and s["utcMillis"] >= deal["utcMillis"]), None)
        if any(step is None for step in [start, pending, rebought, deal, before, after]):
            problems.append("Missing zero-stack spectator, local rebuy request/transfer, next-hand active deal or bracketing status samples")
        elif after["localRebuyTransfers"] - before["localRebuyTransfers"] != 1:
            problems.append("The native canonical local-rebuy counter did not advance exactly once across the accepted lifecycle")
        else:
            local_changes = [e for e in events if before["sequence"] < e["sequence"] <= after["sequence"]
                             and e.get("type") == "Rebuy" and e["sequence"] in previous
                             and e["localRebuyCount"] == previous[e["sequence"]]["localRebuyCount"] + 1]
            if [e["sequence"] for e in local_changes] != [rebought["sequence"]]:
                problems.append("The native counter interval does not identify exactly this local Rebuy event")
        evidence = {"spectatorSequence": None if start is None else start["sequence"],
                    "requestSequence": None if pending is None else pending["sequence"],
                    "rebuySequence": None if rebought is None else rebought["sequence"],
                    "returnedHand": None if deal is None else deal["hand"],
                    "localTransferCounterBefore": None if before is None else before["localRebuyTransfers"],
                    "localTransferCounterAfter": None if after is None else after["localRebuyTransfers"]}

    if args.desktop_states:
        desktop = rows(args.desktop_states)
        desktop_run = getattr(args, "desktop_run_id", None) or args.run_id
        desktop_shape = lambda s: (s.get("runID") == desktop_run and s.get("gameVersion") == args.version
            and all(type(s.get(k)) is bool for k in ["warming", "spectator", "exited", "active"])
            and all(integer(s.get(k)) for k in ["utcMillis", "sequence", "hand", "buyin", "rebuyCount", "immediateRebuyAmount"])
            and s["utcMillis"] > 0 and isinstance(s.get("street"), str) and amount(s.get("stack")))
        if not desktop or any(not desktop_shape(s) for s in desktop):
            problems.append("Desktop evidence has a different run/version or lacks lifecycle fields")
        elif any(b["utcMillis"] < a["utcMillis"] for a, b in zip(desktop, desktop[1:])):
            problems.append("Desktop evidence is not in chronological order")
        else:
            # Device and desktop clocks may differ slightly; a two-second
            # allowance cannot make an older appended run usable as proof.
            peer = [s for s in desktop if s.get("player") == args.nickname
                    and window_start - 2_000 <= s["utcMillis"] <= window_end + 2_000]
            if args.scenario == "hotjoin":
                warm = next((s for s in peer if start and s["warming"] and not s["exited"] and s["street"] == "FLOP"
                             and s["hand"] == observed_hand and s["utcMillis"] >= start["utcMillis"] - 2_000), None)
                active = next((s for s in peer if warm and deal and s["utcMillis"] > warm["utcMillis"]
                               and s["utcMillis"] >= deal["utcMillis"] - 2_000 and s["hand"] == deal["hand"]
                               and not s["warming"] and not s["spectator"] and not s["exited"]), None)
                if warm is None or active is None:
                    problems.append("Same-run desktop evidence does not show warming followed by admission inside the observed window")
            else:
                broke = next((s for s in peer if start and s["spectator"] and not s["warming"] and not s["exited"]
                              and s["stack"] == 0 and s["utcMillis"] >= start["utcMillis"] - 2_000), None)
                requested = next((s for s in peer if broke and pending and s["utcMillis"] > broke["utcMillis"]
                                  and s["hand"] == pending["hand"] and s["spectator"] and not s["warming"] and not s["exited"]
                                  and s["immediateRebuyAmount"] == pending["rebuyAmount"]), None)
                returned = next((s for s in peer if requested and deal and s["utcMillis"] > requested["utcMillis"]
                                 and s["utcMillis"] >= deal["utcMillis"] - 2_000 and s["hand"] == deal["hand"]
                                 and not s["warming"] and not s["spectator"] and not s["exited"]
                                 and s["buyin"] == deal["localBuyin"] and s["rebuyCount"] == deal["localRebuyCount"]), None)
                if any(s is None for s in [broke, requested, returned]):
                    problems.append("Same-run desktop evidence does not show spectator, requested rebuy and next-hand return in order")
    evidence["observationUTCWindow"] = [window_start, window_end]
    return finish()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=["hotjoin", "spectator-rebuy"])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--version", default="26.16")
    parser.add_argument("--nickname", default="IPAD-QA")
    parser.add_argument("--public-cards", type=int, default=3)
    for name in ["statuses", "events", "output"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--desktop-states", type=Path)
    parser.add_argument("--desktop-run-id", help="Explicit desktop run ID if its diagnostic run uses a different name")
    raise SystemExit(0 if audit(parser.parse_args()) else 1)
