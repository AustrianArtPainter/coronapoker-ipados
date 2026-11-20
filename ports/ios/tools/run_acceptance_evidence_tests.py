#!/usr/bin/env python3
"""Reject incomplete/stale acceptance evidence and failed JUnit containers."""
import argparse
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from types import SimpleNamespace
from audit_upgrade_game import audit
from toolchain import game_version, jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
UTC = 1_800_000_000_000


def native(sequence, time, **fields):
    return {"runID": "fixture", "utcMillis": UTC + time, "sequence": sequence, "hand": 3,
            "localWarming": False, "localSpectator": False, "localActive": False, "localExited": False,
            "localStack": 10, "localBuyin": 10, "localRebuyCount": 0, "localImmediateRebuyAmount": 0, **fields}


def observed(time, sequence, **fields):
    status = native(sequence, time, kind="native-java-game", gameVersion="26.16", error="",
                    ticks=100 + time // 1000, maximumTickGapSeconds=1.0,
                    localRebuyTransfers=0, hotJoinSnapshots=0, publicFaceUpCards=0, street="FLOP")
    status.update(fields)
    return {"elapsedSeconds": time / 1000, "status": status}


def peer(time, **fields):
    return {"runID": "fixture", "gameVersion": "26.16", "utcMillis": UTC + time, "sequence": 1,
            "player": "IPAD-QA", "hand": 3, "street": "FLOP", "warming": False, "spectator": False,
            "active": False, "exited": False, "stack": 10, "buyin": 10, "rebuyCount": 0,
            "immediateRebuyAmount": 0, **fields}


def fixtures(scenario):
    if scenario == "hotjoin":
        return ([observed(0, 0), observed(5000, 1, localWarming=True, hotJoinSnapshots=1, publicFaceUpCards=3),
                 observed(10000, 3, hand=4, localActive=True, hotJoinSnapshots=1)],
                [native(1, 1000, type="HotJoinState", localWarming=True),
                 native(2, 7000, type="SeatRoster", hand=4, localActive=True),
                 native(3, 8000, type="DealHoleCard", player="IPAD-QA", hand=4, localActive=True)],
                [peer(2000, warming=True), peer(9000, hand=4, warming=False, active=True)])
    return ([observed(0, 0, localSpectator=True, localStack=0),
             observed(5000, 2, localSpectator=True, localStack=0, localImmediateRebuyAmount=10),
             observed(10000, 5, hand=4, localActive=True, localBuyin=20, localRebuyCount=1, localRebuyTransfers=1)],
            [native(1, 1000, type="SeatRoster", localSpectator=True, localStack=0),
             native(2, 2000, type="ImmediateRebuyStatus", player="IPAD-QA", rebuyAmount=10,
                    localSpectator=True, localStack=0, localImmediateRebuyAmount=10),
             native(3, 6000, type="Rebuy", localSpectator=True, localBuyin=20, localRebuyCount=1),
             native(4, 7000, type="SeatRoster", hand=4, localActive=True, localBuyin=20, localRebuyCount=1),
             native(5, 8000, type="DealHoleCard", player="IPAD-QA", hand=4, localActive=True,
                    localBuyin=20, localRebuyCount=1)],
            [peer(1500, spectator=True, stack=0), peer(3500, spectator=True, stack=0, immediateRebuyAmount=10),
             peer(9000, hand=4, active=True, buyin=20, rebuyCount=1)])


def evidence_checks(directory):
    checks = 0

    def check(scenario, label, expected, change=lambda data: None):
        nonlocal checks
        data = copy.deepcopy(fixtures(scenario))
        change(data)
        paths = [directory / name for name in ["samples.jsonl", "events.jsonl", "desktop.jsonl"]]
        for path, records in zip(paths, data):
            path.write_text("\n".join(map(json.dumps, records)) + "\n")
        args = SimpleNamespace(scenario=scenario, run_id="fixture", version="26.16", nickname="IPAD-QA",
                               public_cards=3, statuses=paths[0], events=paths[1], desktop_states=paths[2],
                               desktop_run_id=None, output=directory / "audit.json")
        with contextlib.redirect_stdout(io.StringIO()):
            result = audit(args)
        if result != expected:
            raise AssertionError(label + ": " + args.output.read_text())
        checks += 1

    check("hotjoin", "complete same-run hot join", True)
    check("spectator-rebuy", "complete local spectator rebuy", True)
    def reversed_phases(data):
        data[2].reverse()
        for index, row in enumerate(data[2]):
            row["utcMillis"] = UTC + (index + 1) * 1000
    for scenario in ["hotjoin", "spectator-rebuy"]:
        check(scenario, "old desktop run", False, lambda d: d[2][0].update(runID="old-run"))
        check(scenario, "same-version old desktop timestamps", False,
              lambda d: [row.update(utcMillis=row["utcMillis"] - 60_000) for row in d[2]])
        check(scenario, "missing native spectator flag", False, lambda d: d[1][-1].pop("localSpectator"))
        check(scenario, "missing native exited flag", False, lambda d: d[1][-1].pop("localExited"))
        check(scenario, "missing peer spectator flag", False, lambda d: d[2][-1].pop("spectator"))
        check(scenario, "inactive local deal", False, lambda d: d[1][-1].update(localActive=False))
        check(scenario, "incomplete event copy", False, lambda d: d[1].pop())
        check(scenario, "wrong next hand", False, lambda d: d[1][-1].update(hand=5))
        check(scenario, "wrong native version", False, lambda d: d[0][-1]["status"].update(gameVersion="25.86"))
        check(scenario, "fresh reads of cached native status", False,
              lambda d: [row["status"].update(ticks=100, utcMillis=UTC) for row in d[0]])
        check(scenario, "desktop phases reversed inside chronological log", False,
              reversed_phases)
    check("hotjoin", "wrong public board", False, lambda d: d[0][1]["status"].update(publicFaceUpCards=4))
    check("hotjoin", "warming local card leak", False,
          lambda d: d[1][1].update(type="DealHoleCard", player="IPAD-QA", localWarming=True))
    check("spectator-rebuy", "no local canonical transfer", False,
          lambda d: d[0][-1]["status"].update(localRebuyTransfers=0))
    check("spectator-rebuy", "ambiguous multiple local canonical transfers", False,
          lambda d: d[0][-1]["status"].update(localRebuyTransfers=2))
    check("spectator-rebuy", "missing local transfer counter", False,
          lambda d: d[0][-1]["status"].pop("localRebuyTransfers"))
    check("spectator-rebuy", "no original positive rebuy request", False, lambda d: d[1][1].update(rebuyAmount=0))
    check("spectator-rebuy", "desktop returned without matching buyin", False, lambda d: d[2][-1].update(buyin=10))

    def unrelated_rebuy(data):
        # The local balance was already changed by a different event. A later
        # Rebuy belonging to someone else must not be treated as that transfer.
        earlier = {**data[1][2], "sequence": 3, "utcMillis": UTC + 5500, "type": "SeatRoster"}
        for event in data[1][2:]:
            event["sequence"] += 1
        data[1].insert(2, earlier)
        data[0][-1]["status"]["sequence"] += 1
    check("spectator-rebuy", "another player's Rebuy with unchanged local balances", False, unrelated_rebuy)
    return checks


def launcher_checks(directory, mobile):
    repository = ROOT / ".m2/repository"
    junit = [repository / path for path in [
        "org/junit/platform/junit-platform-launcher/1.10.2/junit-platform-launcher-1.10.2.jar",
        "org/junit/platform/junit-platform-engine/1.10.2/junit-platform-engine-1.10.2.jar",
        "org/junit/platform/junit-platform-commons/1.10.2/junit-platform-commons-1.10.2.jar",
        "org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar",
        "org/junit/jupiter/junit-jupiter-engine/5.10.2/junit-jupiter-engine-5.10.2.jar",
        "org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar",
        "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"]]
    java = jdk_home() / "bin"
    fixture = directory / "ContainerFailureFixture.java"
    fixture.write_text('''package acceptance;
import org.junit.jupiter.api.*;
class PassingContainer { @Test void passes() { Assertions.assertTrue(true); } }
class CrashedContainer {
    @BeforeAll static void setup() { throw new IllegalStateException("before-all fixture"); }
    @Test void unreachable() { Assertions.fail("should not run"); }
}
''')
    cp = ":".join(map(str, [mobile, *junit]))
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", cp, "-d", str(directory),
                    str(PORT / "tools/BoundaryTests.java"), str(fixture)], check=True)
    result = subprocess.run([str(java / "java"), "-Dcoronapoker.validation.expectedVersion=" + game_version(),
                             "-Dcoronapoker.validation.mobileClasses=" + str(mobile),
                             "-Dcoronapoker.validation.mobileClassNames=com.tonikelope.coronapoker.core.ApplicationMetadata",
                             "-cp", str(directory) + ":" + cp, "BoundaryTests",
                             "acceptance.PassingContainer", "acceptance.CrashedContainer"], capture_output=True, text=True)
    if result.returncode == 0 or not all(re.search(pattern, result.stdout) for pattern in
            [r"1 containers failed", r"1 tests successful", r"0 tests failed"]):
        raise AssertionError("A crashed BeforeAll container plus a passing test was accepted: " + result.stdout + result.stderr)

    desktop = directory / "DesktopReadinessFixture.java"
    desktop.write_text('''package com.tonikelope.coronapoker.gdx;
import com.tonikelope.coronapoker.table.*;
import java.nio.file.*;
import java.util.List;
public class DesktopReadinessFixture {
    public static void main(String[] args) throws Exception {
        if (!DesktopGuiPeerProbe.hotJoinReady(TableSnapshot.Street.FLOP, true, true)
                || DesktopGuiPeerProbe.hotJoinReady(TableSnapshot.Street.TURN, true, true)
                || DesktopGuiPeerProbe.hotJoinReady(TableSnapshot.Street.FLOP, false, true)
                || DesktopGuiPeerProbe.hotJoinReady(TableSnapshot.Street.FLOP, true, false))
            throw new AssertionError("Hot-join readiness accepted the wrong phase, pause or board");
        Path home = Path.of(args[0]);
        Path evidence = home.resolve("player-state.jsonl");
        Files.writeString(evidence, "old-run-evidence");
        DesktopGuiPeerProbe.prepareEvidence(home);
        if (!Files.readString(evidence).isEmpty()) throw new AssertionError("Old peer evidence survived a new run");
        var local = new TableSnapshot.PlayerSnapshot("IPAD-QA", 10, 0, 0, true, false, false, false,
                -1, -1, 0, 0, false, TableSnapshot.Position.NONE, "", "", List.of());
        var view = new GdxTableViewState(new TableSnapshot(0, "IPAD-QA", TableSnapshot.Street.WAITING,
                0, "", false, List.of(local), List.of()));
        var report = DesktopGuiPeerProbe.class.getDeclaredMethod("playerReport", GdxTableViewState.class);
        report.setAccessible(true);
        String text = (String) report.invoke(null, view);
        if (!text.contains("\\\"runID\\\":\\\"current-evidence\\\"")
                || !text.contains("\\\"active\\\":true") || !text.contains("\\\"street\\\":\\\"WAITING\\\""))
            throw new AssertionError("Peer telemetry lacks its current run and phase");
    }
}
''')
    jar = ROOT / ("target/CoronaPoker_" + game_version() + ".jar")
    subprocess.run([str(java / "javac"), "--release", "17", "-cp", str(jar), "-d", str(directory),
                    str(PORT / "tools/DesktopGuiPeerProbe.java"), str(desktop)], check=True)
    environment = os.environ.copy()
    environment["CP_DESKTOP_RUN_ID"] = "current-evidence"
    subprocess.run([str(java / "java"), "-cp", str(directory) + ":" + str(jar),
                    "com.tonikelope.coronapoker.gdx.DesktopReadinessFixture", str(directory)], env=environment, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobile-classes", type=Path, default=PORT / "build/crypto-tests/mobile-classes")
    args = parser.parse_args()
    output = PORT / "build/acceptance-evidence-tests"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as temporary:
        directory = Path(temporary)
        checks = evidence_checks(directory)
        launcher_checks(directory, args.mobile_classes.resolve())
    print(f"PASS {checks} lifecycle evidence cases; JUnit container-failure rejection; desktop readiness/run isolation")
