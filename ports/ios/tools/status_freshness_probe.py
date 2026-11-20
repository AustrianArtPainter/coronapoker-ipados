#!/usr/bin/env python3
"""Synthetic acceptance/rejection proofs; no device data or fixture files."""
import contextlib
import copy
import io
import json
from types import SimpleNamespace
import unittest

from audit_locked_game import audit
from status_freshness import assess_status_freshness


RUN = "synthetic-freshness-proof"
VERSION = "26.16"


def observations(offset_millis=0):
    values = []
    for index in range(181):
        elapsed = index * 5
        values.append({"elapsedSeconds": elapsed, "status": {
            "kind": "native-java-game", "runID": RUN,
            "gameVersion": VERSION, "error": "", "mode": "host",
            "background": True, "musicPlaying": True,
            "utcMillis": 1_800_000_000_000 + offset_millis + elapsed * 1000,
            "ticks": 100 + elapsed, "backgroundTicks": 100 + elapsed,
            "maximumTickGapSeconds": 1.05, "sequence": 10 + index,
            "events": 10 + index, "finishedShuffles": 1 + index // 10,
            "endedHands": 1 + index // 10, "payouts": 1 + index // 10,
            "persistedFinishedHands": 1 + index // 10,
            "persistedSignedActions": 1 + index * 2}})
    return values


class MemoryFile:
    def __init__(self, text="", summary=None):
        self.text = text
        self.summary = summary

    def read_text(self):
        return self.text

    def write_text(self, value):
        self.text = value

    def with_suffix(self, suffix):
        return self.summary


def memory_json(value):
    return MemoryFile(json.dumps(value))


def audit_arguments(samples):
    last = samples[-1]["status"]
    completed = last["persistedFinishedHands"]
    signed = last["persistedSignedActions"]
    database = {"passed": True, "completedHands": completed,
                "verifiedActionSignatures": signed,
                "actionRecordsSHA256": "synthetic-identical-records",
                "finalStacks": 20, "totalBuyins": 20,
                "tamperedAmountsRejected": signed}
    events = [{"runID": RUN, "sequence": 11, "background": True,
               "type": "PlayerAction", "player": "IPAD-QA", "action": "CHECK"},
              {"runID": RUN, "sequence": 12, "background": True,
               "type": "PlayerAction", "player": "IPAD-QA", "action": "FOLD"}]
    return SimpleNamespace(
        samples=MemoryFile("\n".join(json.dumps(s) for s in samples),
                           memory_json({"passed": True, "requestedSeconds": 900,
                                        "observedSeconds": 900})),
        confirmation=memory_json({"runID": RUN, "userStatement": "Synthetic locked fixture"}),
        build=memory_json({"scenario": RUN, "launchHasConsoleOrDebugger": False,
                           "executableSHA256": "synthetic-native",
                           "desktopGUIJarSHA256": "synthetic-desktop"}),
        native_audit=memory_json(database), desktop_audit=memory_json(database),
        events=MemoryFile("\n".join(json.dumps(e) for e in events)),
        desktop_log=MemoryFile("\n".join(
            f"SHUFFLE-VERIFY: deck verified OK (hand {hand})\n"
            f"Hand {hand} verified: 2 receipts unanimous"
            for hand in range(2, completed + 1))),
        run_id=RUN, version=VERSION, nickname="IPAD-QA", output=MemoryFile())


class StatusFreshnessProof(unittest.TestCase):
    def assess(self, values):
        return assess_status_freshness(values, run_id=RUN, expected_version=VERSION)

    def locked_audit(self, values):
        args = audit_arguments(values)
        with contextlib.redirect_stdout(io.StringIO()):
            accepted = audit(args)
        return accepted, json.loads(args.output.text)

    def test_live_ticker_and_arbitrary_fixed_clock_offset_pass(self):
        for offset in [0, 86400 * 1000 * 13, -86400 * 1000 * 13]:
            with self.subTest(offset=offset):
                result = self.assess(observations(offset))
                self.assertTrue(result["passed"], result)
                self.assertEqual(result["nativeTickAdvance"], 900)
                self.assertEqual(result["nativeClockAdvanceSeconds"], 900)
                self.assertTrue(self.locked_audit(observations(offset))[0])

    def test_normal_subsecond_snapshot_jitter_passes(self):
        values = observations()
        for index, sample in enumerate(values):
            sample["status"]["utcMillis"] -= (index % 3) * 300
        self.assertTrue(self.assess(values)["passed"])

    def test_one_boundary_cached_reply_within_tolerance_passes(self):
        values = observations()
        values[90]["status"] = copy.deepcopy(values[89]["status"])
        result = self.assess(values)
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["maximumUnchangedStatusSeconds"], 5)

    def test_clock_step_during_observation_is_rejected(self):
        values = observations()
        for sample in values[90:]:
            sample["status"]["utcMillis"] += 60_000
        self.assertFalse(self.assess(values)["passed"])

    def test_cached_status_for_final_895_seconds_is_rejected(self):
        values = observations()
        for sample in values[2:]:
            sample["status"] = copy.deepcopy(values[1]["status"])
        result = self.assess(values)
        self.assertFalse(result["passed"])
        self.assertEqual(result["maximumUnchangedStatusSeconds"], 895)
        self.assertFalse(self.locked_audit(values)[0])

    def test_wrong_run_and_version_are_rejected_by_both_layers(self):
        for key in ["runID", "gameVersion"]:
            with self.subTest(key=key):
                values = observations()
                values[90]["status"][key] = "another-run-or-version"
                self.assertFalse(self.assess(values)["passed"])
                self.assertFalse(self.locked_audit(values)[0])

    def test_utc_only_or_ticks_only_updates_are_rejected(self):
        for key in ["ticks", "utcMillis"]:
            with self.subTest(key=key):
                values = observations()
                for sample in values:
                    sample["status"][key] = values[0]["status"][key]
                self.assertFalse(self.assess(values)["passed"])

    def test_backwards_counters_and_observation_times_are_rejected(self):
        for key in ["ticks", "utcMillis"]:
            with self.subTest(key=key):
                values = observations()
                values[90]["status"][key] = values[89]["status"][key] - 1
                self.assertFalse(self.assess(values)["passed"])
        values = observations()
        values[90]["elapsedSeconds"] = values[89]["elapsedSeconds"]
        self.assertFalse(self.assess(values)["passed"])

    def test_claimed_900_seconds_cannot_replace_a_short_sample_window(self):
        values = observations()[:-1]
        accepted, result = self.locked_audit(values)
        self.assertFalse(accepted)
        self.assertIn("The actual fresh-status samples do not span the full 15-minute window",
                      result["problems"])

    def test_sparse_ticks_cannot_cover_the_observation_window(self):
        values = observations()
        for index, sample in enumerate(values):
            sample["status"]["ticks"] = 100 + index
        self.assertFalse(self.assess(values)["passed"])

    def test_missing_invalid_or_failed_samples_are_rejected(self):
        for value in [None, True, float("nan"), -1]:
            with self.subTest(value=value):
                values = observations()
                values[90]["status"]["ticks"] = value
                self.assertFalse(self.assess(values)["passed"])
        values = observations()
        values[90] = {"elapsedSeconds": 450, "readError": "synthetic failed observation"}
        self.assertFalse(self.assess(values)["passed"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
