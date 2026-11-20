#!/usr/bin/env python3
"""Pure-Python capture integrity/freshness tests; no app or device access."""
import copy
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_scene_evidence as collector

UTC = 1_800_000_000_000


def fixture():
    manifest = {"bundleIdentifier": collector.BUNDLE, "gameVersion": "26.16", "build": "9",
                "executableSHA256": "a" * 64}
    metadata = {"runID": "fresh-fixture", "launchSucceeded": True,
                "launchRequestedUtcMillis": UTC, "launchCompletedUtcMillis": UTC + 100,
                "evidenceSeconds": 10, "sourceCommit": "b" * 40, "executableSHA256": "a" * 64,
                "environment": {"CP_IOS_RUN_ID": "fresh-fixture", "CP_IOS_SCENE_EVIDENCE": "1",
                                "CP_IOS_EVIDENCE_SECONDS": "10"}}
    samples = []
    for index in range(601):
        elapsed = index * collector.NANOSECOND // 60
        previous_elapsed = (index - 1) * collector.NANOSECOND // 60
        samples.append({"intervalEndElapsedNanos": elapsed,
                        "intervalNs": elapsed - previous_elapsed if index else None,
                        "previousRenderNs": 1_000_000 if index else None,
                        "intervalProcessAllocatedBytes": 100 if index else None,
                        "previousRenderProcessAllocatedBytes": 10 if index else None,
                        "intervalGCCount": 0 if index else None,
                        "previousPhase": 3 if index else -1, "currentPhase": 3,
                        "previousSequence": 0 if index else -1,
                        "flags": (12 if elapsed <= 5 * collector.NANOSECOND else 0)
                                 | (1 if index == 0 else 0),
                        "currentRenderNs": 1_000_000, "currentRenderProcessAllocatedBytes": 10,
                        "currentRenderGCCount": 0})
    report = {"kind": "retained-scene-v5", "runID": "fresh-fixture", "timedEvidence": True,
              "evidenceSeconds": 10, "originUtcMillis": UTC + 500, "originMonotonicNanos": 100,
              "samplingEndedElapsedNanos": 10_001_000_000, "completionReason": "duration",
              "sampledFrames": len(samples), "liveReporter": False, "allocationScopesEnabled": False,
              "sampleCapacity": 1800, "sampleStoragePrimitiveBytes": 1800 * 83,
              "diagnosticConfig": {key: None for key in collector.CONFIG_ENV},
              "frameAttribution": {"enabled": False},
              "audio": None, "crypto": None, "animation": None,
              "flagsMap": collector.FLAGS.copy(),
              "phaseNames": list(collector.PHASE_NAMES.values()),
              "samples": samples}
    return report, metadata, manifest


def enable_quote_wrap_control(report, metadata, manifest):
    metadata["control"] = "quote-wrap-cache"
    metadata["environment"]["CP_IOS_QUOTE_WRAP_CACHE"] = "1"
    report["diagnosticConfig"]["quoteWrapCache"] = "1"


def enable_frame_attribution(report, metadata, manifest):
    metadata["control"] = collector.FRAME_ATTRIBUTION_CONTROL
    metadata["environment"]["CP_IOS_FRAME_ATTRIBUTION"] = "1"
    report["diagnosticConfig"]["frameAttribution"] = "1"
    report["frameAttribution"] = {"enabled": True}


def enable_deferred_phase(report, metadata, manifest, phase="menu"):
    metadata["environment"][collector.START_PHASE_ENV] = phase
    metadata["captureReadyUtcMillis"] = UTC + 21_000
    report.update(evidenceStartPhase=phase, originUtcMillis=UTC + 20_000,
                  firstForegroundUtcMillis=UTC + 500,
                  firstForegroundMonotonicNanos=100,
                  originMonotonicNanos=19_500_000_100,
                  startPhaseStableNanos=5_000_000_000)
    index = {"menu": 3, "table": 1, "summary": 2}[phase]
    for row in report["samples"]:
        row["currentPhase"] = index
        if row["previousPhase"] != -1:
            row["previousPhase"] = index


class SceneEvidenceTests(unittest.TestCase):
    def audit(self, mutation=None, **options):
        report, metadata, manifest = copy.deepcopy(fixture())
        if mutation:
            mutation(report, metadata, manifest)
        return collector.audit_evidence(report, metadata, "fresh-fixture", manifest,
                                        collected_utc_millis=UTC + 50_000, **options)

    def assert_rejected(self, mutation):
        result = self.audit(mutation)
        self.assertFalse(result["evidenceAccepted"], result)
        self.assertTrue(result["problems"])

    def test_complete_capture_accepts_integrity_without_accepting_audio(self):
        result = self.audit()
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertIsNone(result["symptomResolved"])
        self.assertIsNone(result["performanceWithinTarget"])
        self.assertIn("unmeasured", result["audioQuality"])
        self.assertEqual(result["nullIntervalFrames"], 1)

    def test_gc_runtime_is_bound_to_binary_policy(self):
        for policy, runtime_policy, discovery in (("registered", "registered", 0),
                                                  ("original", "sdk-original", -1)):
            def selected(report, metadata, manifest):
                manifest["nativeGCSuspendPolicy"] = policy
                manifest["expectedGCVersion"] = 460032
                manifest["gcBuildManifestSHA256"] = "c" * 64 if policy == "registered" else "sdk-original"
                report["gcRuntime"] = {"policy": runtime_policy, "taskThreadDiscovery": discovery,
                                       "markerHelpers": 9, "version": 460032, "bundlePolicy": policy,
                                       "bundleManifestSHA256": manifest["gcBuildManifestSHA256"],
                                       "collectorEnvironmentOverridesPresent": False}
            with self.subTest(policy=policy):
                result = self.audit(selected)
                self.assertTrue(result["evidenceAccepted"], result["problems"])
                self.assertIsNone(result["symptomResolved"])
                for mutation in (lambda r, m, b: r.pop("gcRuntime"),
                                 lambda r, m, b: b.pop("nativeGCSuspendPolicy"),
                                 lambda r, m, b: r["gcRuntime"].update(taskThreadDiscovery=1),
                                 lambda r, m, b: r["gcRuntime"].update(taskThreadDiscovery=False),
                                 lambda r, m, b: r["gcRuntime"].update(policy="task-threads"),
                                 lambda r, m, b: r["gcRuntime"].update(markerHelpers=True),
                                 lambda r, m, b: r["gcRuntime"].update(bundleManifestSHA256="d" * 64),
                                 lambda r, m, b: r["gcRuntime"].update(bundlePolicy="other"),
                                 lambda r, m, b: b.pop("expectedGCVersion"),
                                 lambda r, m, b: r["gcRuntime"].update(version=460033),
                                 lambda r, m, b: r["gcRuntime"].update(collectorEnvironmentOverridesPresent=True),
                                 lambda r, m, b: m["environment"].update(GC_MARKERS="2"),
                                 lambda r, m, b: r["gcRuntime"].update(version=0)):
                    def invalid(report, metadata, manifest):
                        selected(report, metadata, manifest)
                        mutation(report, metadata, manifest)
                    self.assert_rejected(invalid)

    def test_gc_contract_probe_is_never_normal_scene_evidence(self):
        def active(report, metadata, manifest):
            metadata["environment"]["CP_IOS_GC_PROBE"] = "1"
            report["diagnosticConfig"]["gcProbe"] = "1"
        self.assert_rejected(active)
        result = self.audit(lambda r, m, b: r["diagnosticConfig"].pop("gcProbe"))
        self.assertTrue(result["evidenceAccepted"], result["problems"])

    def test_audio_clock_diagnostic_is_never_normal_scene_evidence(self):
        for value in ("180", "0", "", "bad"):
            for scope in ("both", "launch-only", "report-only"):
                with self.subTest(value=value, scope=scope):
                    def active(report, metadata, manifest):
                        if scope != "report-only":
                            metadata["environment"]["CP_IOS_AUDIO_CLOCK_SECONDS"] = value
                        if scope != "launch-only":
                            report["diagnosticConfig"]["audioClockSeconds"] = value
                    self.assert_rejected(active)
        result = self.audit(lambda r, m, b: r["diagnosticConfig"].pop("audioClockSeconds"))
        self.assertTrue(result["evidenceAccepted"], result["problems"])

    def test_frame_attribution_requires_explicit_control_and_reports_diagnostic_scope(self):
        self.assert_rejected(enable_frame_attribution)
        result = self.audit(enable_frame_attribution, control=collector.FRAME_ATTRIBUTION_CONTROL)
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertEqual(result["kind"], "frame-attribution-capture-audit-v1")
        self.assertTrue(result["frameAttributionEnabled"])
        self.assertTrue(result["diagnosticInstrumentation"])
        self.assertFalse(result["normalAcceptance"])
        self.assertFalse(result["normalScenePerformance"])
        self.assertIsNone(result["symptomResolved"])
        self.assertIsNone(result["performanceWithinTarget"])
        self.assertIn("unmeasured", result["audioQuality"])
        self.assertIn("does not establish normal performance", result["scope"])
        normal = self.audit()
        for key in ("sampledFrames", "sameSceneCoverage", "minimumPhaseCoverage", "phaseStats"):
            self.assertEqual(result[key], normal[key])
        self.assertFalse(self.audit(enable_frame_attribution, control=collector.FRAME_ATTRIBUTION_CONTROL,
                                    minimum_phase_seconds=180)["evidenceAccepted"])

    def test_frame_attribution_control_requires_exact_flag_and_actual_enabled_recorder(self):
        mutations = [lambda r, m, b: m.pop("control"),
                     lambda r, m, b: m["environment"].pop("CP_IOS_FRAME_ATTRIBUTION"),
                     lambda r, m, b: r["diagnosticConfig"].pop("frameAttribution"),
                     lambda r, m, b: r.pop("frameAttribution"),
                     lambda r, m, b: r.update(frameAttribution={"enabled": False})]
        for value in (None, "", "0", "true", "01", "bad"):
            mutations.extend((lambda r, m, b, value=value: m["environment"].update(CP_IOS_FRAME_ATTRIBUTION=value),
                              lambda r, m, b, value=value: r["diagnosticConfig"].update(frameAttribution=value)))
        for index, mutation in enumerate(mutations):
            with self.subTest(mismatch=index):
                def invalid(report, metadata, manifest):
                    enable_frame_attribution(report, metadata, manifest)
                    mutation(report, metadata, manifest)
                result = self.audit(invalid, control=collector.FRAME_ATTRIBUTION_CONTROL)
                self.assertFalse(result["evidenceAccepted"], result)

    def test_actual_frame_attribution_cannot_hide_behind_disabled_flags_in_other_controls(self):
        for control in ("normal", collector.QUOTE_WRAP_CONTROL, *collector.MUSIC_CONTROLS):
            for value in (None, "", "0"):
                with self.subTest(control=control, value=value):
                    def hidden(report, metadata, manifest):
                        if control == collector.QUOTE_WRAP_CONTROL:
                            enable_quote_wrap_control(report, metadata, manifest)
                        elif control in collector.MUSIC_CONTROLS:
                            backend = collector.MUSIC_CONTROLS[control]
                            metadata["control"] = control
                            metadata["environment"]["CP_IOS_MUSIC_BACKEND"] = backend
                            report["diagnosticConfig"]["musicBackend"] = backend
                            report["backgroundMusicBackend"] = backend
                        if value is not None:
                            metadata["environment"]["CP_IOS_FRAME_ATTRIBUTION"] = value
                        report["diagnosticConfig"]["frameAttribution"] = value
                        report["frameAttribution"] = {"enabled": True}
                    result = self.audit(hidden, control=control)
                    self.assertFalse(result["evidenceAccepted"], result)
                    self.assertIn("Actual frame attribution recorder does not match the explicit capture control",
                                  result["problems"])

    def test_frame_attribution_declaration_is_strict_and_older_disabled_reports_remain_valid(self):
        for declaration in (None, False, [], {}, {"enabled": None}, {"enabled": 0},
                            {"enabled": 1}, {"enabled": "false"}):
            with self.subTest(declaration=declaration):
                self.assert_rejected(lambda r, m, b: r.update(frameAttribution=declaration))
        def old_report(report, metadata, manifest):
            report.pop("frameAttribution")
            report["diagnosticConfig"].pop("frameAttribution")
        result = self.audit(old_report)
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertFalse(result["frameAttributionEnabled"])
        self.assertTrue(self.audit()["evidenceAccepted"])
        self.assert_rejected(lambda r, m, b: m["environment"].update(CP_IOS_FRAME_ATTRIBUTION_UNKNOWN="0"))

    def test_normal_capture_requires_attribution_flag_unset_even_when_recorder_is_disabled(self):
        for value in ("1", "0", "", "true"):
            for scope in ("both", "launch-only", "report-only"):
                with self.subTest(value=value, scope=scope):
                    def configured(report, metadata, manifest):
                        if scope != "report-only":
                            metadata["environment"]["CP_IOS_FRAME_ATTRIBUTION"] = value
                        if scope != "launch-only":
                            report["diagnosticConfig"]["frameAttribution"] = value
                    self.assert_rejected(configured)

    def test_frame_attribution_does_not_exempt_other_probes_or_capture_integrity(self):
        for mutation in (lambda r, m, b: (m["environment"].update(CP_IOS_PROFILE="1"),
                                          r["diagnosticConfig"].update(profile="1")),
                         lambda r, m, b: m["environment"].update(CP_IOS_UNKNOWN_PROBE="0"),
                         lambda r, m, b: r["samples"][400].update(flags=12),
                         lambda r, m, b: r.update(completionReason="capacity")):
            def invalid(report, metadata, manifest):
                enable_frame_attribution(report, metadata, manifest)
                mutation(report, metadata, manifest)
            result = self.audit(invalid, control=collector.FRAME_ATTRIBUTION_CONTROL)
            self.assertFalse(result["evidenceAccepted"], result)

    def test_deferred_scene_retains_full_window_and_original_coverage_thresholds(self):
        for phase in collector.START_PHASES:
            with self.subTest(phase=phase):
                result = self.audit(lambda r, m, b: enable_deferred_phase(r, m, b, phase),
                                    expected_phase=phase, minimum_phase_seconds=10)
                self.assertTrue(result["evidenceAccepted"], result["problems"])
                self.assertEqual(result["minimumPhaseCoverage"], .8)
                self.assertEqual(result["sameSceneCoverage"][phase], 10 / 10.001)
                self.assertIsNone(result["symptomResolved"])

    def test_deferred_scene_rejects_forged_clocks_readiness_and_wrong_first_phase(self):
        mutations = [lambda r, m, b: m.pop("captureReadyUtcMillis"),
                     lambda r, m, b: m.update(captureReadyUtcMillis=UTC - 1),
                     lambda r, m, b: m.update(captureReadyUtcMillis=UTC + 200),
                     lambda r, m, b: m["environment"].update(CP_IOS_EVIDENCE_START_PHASE="frontend"),
                     lambda r, m, b: r.update(evidenceStartPhase="summary"),
                     lambda r, m, b: r.update(firstForegroundUtcMillis=UTC - 10_000),
                     lambda r, m, b: r.update(firstForegroundMonotonicNanos=99_000_000_000),
                     lambda r, m, b: r.update(startPhaseStableNanos=4_999_999_999),
                     lambda r, m, b: r.update(originMonotonicNanos=15_000_000_100),
                     lambda r, m, b: r["samples"][0].update(currentPhase=1)]
        for index, mutation in enumerate(mutations):
            with self.subTest(mismatch=index):
                def invalid(report, metadata, manifest):
                    enable_deferred_phase(report, metadata, manifest)
                    mutation(report, metadata, manifest)
                self.assert_rejected(invalid)

    def test_deferred_start_phase_cannot_be_forged_by_an_otherwise_valid_scene(self):
        def wrong_start(report, metadata, manifest):
            enable_deferred_phase(report, metadata, manifest)
            for row in report["samples"]:
                row["currentPhase"] = 1
                if row["previousPhase"] != -1:
                    row["previousPhase"] = 1
        result = self.audit(wrong_start, expected_phase="any")
        self.assertFalse(result["evidenceAccepted"])
        self.assertEqual(result["problems"], ["First sample does not belong to the requested deferred phase"])

    def test_default_normal_accepts_older_build9_without_control_snapshot(self):
        result = self.audit(lambda r, m, b: r["diagnosticConfig"].pop("quoteWrapCache"))
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertEqual(result["kind"], "normal-scene-capture-audit-v1")
        self.assertEqual(result["control"], "normal")

    def test_quote_wrap_control_requires_explicit_opt_in(self):
        self.assert_rejected(enable_quote_wrap_control)
        self.assert_rejected(lambda r, m, b: m["environment"].update(CP_IOS_QUOTE_WRAP_CACHE="1"))
        self.assert_rejected(lambda r, m, b: r["diagnosticConfig"].update(quoteWrapCache="1"))

    def test_music_backend_controls_are_explicit_and_bound_to_launch_and_report(self):
        for control, backend in collector.MUSIC_CONTROLS.items():
            def selected(report, metadata, manifest):
                metadata["control"] = control
                metadata["environment"]["CP_IOS_MUSIC_BACKEND"] = backend
                report["diagnosticConfig"]["musicBackend"] = backend
                report["backgroundMusicBackend"] = backend
            with self.subTest(control=control):
                result = self.audit(selected, control=control)
                self.assertTrue(result["evidenceAccepted"], result["problems"])
                self.assertIsNone(result["symptomResolved"])
                self.assert_rejected(selected)
                for mutation in (lambda r, m, b: m["environment"].pop("CP_IOS_MUSIC_BACKEND"),
                                 lambda r, m, b: r["diagnosticConfig"].pop("musicBackend"),
                                 lambda r, m, b: r["diagnosticConfig"].update(musicBackend="other"),
                                 lambda r, m, b: r.update(backgroundMusicBackend="queue-fell-back"),
                                 lambda r, m, b: r["diagnosticConfig"].update(profile="1")):
                    def invalid(report, metadata, manifest):
                        selected(report, metadata, manifest)
                        mutation(report, metadata, manifest)
                    self.assertFalse(self.audit(invalid, control=control)["evidenceAccepted"])

    def test_existing_captures_without_music_backend_snapshot_still_audit(self):
        result = self.audit(lambda r, m, b: r["diagnosticConfig"].pop("musicBackend"))
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        def old_control(report, metadata, manifest):
            enable_quote_wrap_control(report, metadata, manifest)
            report["diagnosticConfig"].pop("musicBackend")
        result = self.audit(old_control, control="quote-wrap-cache")
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        result = self.audit(lambda r, m, b: r["diagnosticConfig"].pop("musicProbe"))
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        def music_probe(report, metadata, manifest):
            metadata["environment"]["CP_IOS_MUSIC_PROBE"] = "gc-audio"
            report["diagnosticConfig"]["musicProbe"] = "gc-audio"
        self.assert_rejected(music_probe)

    def test_quote_wrap_control_accepts_matching_capture_without_claiming_audio_fix(self):
        result = self.audit(enable_quote_wrap_control, control="quote-wrap-cache")
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertEqual(result["kind"], "controlled-scene-capture-audit-v1")
        self.assertEqual(result["control"], "quote-wrap-cache")
        self.assertIsNone(result["symptomResolved"])
        self.assertIsNone(result["performanceWithinTarget"])
        self.assertIn("unmeasured", result["audioQuality"])
        self.assertIn("does not establish an audio fix", result["scope"])

    def test_control_requires_metadata_launch_and_report_to_agree(self):
        mutations = [lambda r, m, b: m.pop("control"),
                     lambda r, m, b: m.update(control="normal"),
                     lambda r, m, b: m["environment"].pop("CP_IOS_QUOTE_WRAP_CACHE"),
                     lambda r, m, b: m["environment"].update(CP_IOS_QUOTE_WRAP_CACHE="0"),
                     lambda r, m, b: r["diagnosticConfig"].pop("quoteWrapCache"),
                     lambda r, m, b: r["diagnosticConfig"].update(quoteWrapCache="true")]
        for index, mutation in enumerate(mutations):
            with self.subTest(mismatch=index):
                def mismatch(report, metadata, manifest):
                    enable_quote_wrap_control(report, metadata, manifest)
                    mutation(report, metadata, manifest)
                result = self.audit(mismatch, control="quote-wrap-cache")
                self.assertFalse(result["evidenceAccepted"], result)
        result = self.audit(enable_quote_wrap_control, control="unknown-control")
        self.assertFalse(result["evidenceAccepted"], result)

    def test_control_does_not_exempt_other_probes_or_capture_integrity(self):
        mutations = [lambda r, m, b: (m["environment"].update(CP_IOS_PROFILE="1"),
                                      r["diagnosticConfig"].update(profile="1")),
                     lambda r, m, b: (m["environment"].update(CP_IOS_VOICE_PROBE="1"),
                                      r["diagnosticConfig"].update(voiceProbe="1")),
                     lambda r, m, b: m["environment"].update(CP_IOS_UNKNOWN_PROBE="0"),
                     lambda r, m, b: r.update(runID="old-fixture"),
                     lambda r, m, b: b.update(executableSHA256="c" * 64),
                     lambda r, m, b: r["samples"][400].update(flags=12),
                     lambda r, m, b: r.update(completionReason="capacity")]
        for index, mutation in enumerate(mutations):
            with self.subTest(rejected=index):
                def invalid_controlled_capture(report, metadata, manifest):
                    enable_quote_wrap_control(report, metadata, manifest)
                    mutation(report, metadata, manifest)
                result = self.audit(invalid_controlled_capture, control="quote-wrap-cache")
                self.assertFalse(result["evidenceAccepted"], result)

    def test_control_audit_only_cli_is_explicit_and_contacts_no_device(self):
        for control, configure in ((collector.QUOTE_WRAP_CONTROL, enable_quote_wrap_control),
                                   (collector.FRAME_ATTRIBUTION_CONTROL, enable_frame_attribution)):
            with self.subTest(control=control), tempfile.TemporaryDirectory() as directory:
                report, metadata, manifest = fixture()
                configure(report, metadata, manifest)
                output = Path(directory)
                (output / "manifest.json").write_text(json.dumps(manifest))
                metadata["binaryManifest"] = "manifest.json"
                (output / "launch-metadata.json").write_text(json.dumps(metadata))
                (output / "ios-scene-evidence.json").write_text(json.dumps(report))
                args = ["collector", "--audit-only", "--run-id", metadata["runID"],
                        "--output", str(output)]
                with patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected device call")):
                    with patch.object(collector.sys, "argv", args), contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(collector.main(), 1)
                    with patch.object(collector.sys, "argv", args + ["--control", control]), \
                            contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(collector.main(), 0)
                result = collector.load_json(output / "scene-audit.json")
                self.assertTrue(result["evidenceAccepted"])
                self.assertEqual(result["control"], control)
                if control == collector.FRAME_ATTRIBUTION_CONTROL:
                    self.assertFalse(result["normalAcceptance"])

    def test_old_run_is_rejected(self):
        self.assert_rejected(lambda r, m, b: r.update(runID="old-fixture"))

    def test_old_version_summary_is_rejected(self):
        self.assert_rejected(lambda r, m, b: r.update(kind="retained-scene-v4"))

    def test_origin_before_launch_is_rejected(self):
        self.assert_rejected(lambda r, m, b: r.update(originUtcMillis=UTC - 2001))

    def test_clock_tolerance_is_explicitly_reported(self):
        result = self.audit(lambda r, m, b: r.update(originUtcMillis=UTC - 1500))
        self.assertTrue(result["evidenceAccepted"])
        self.assertEqual(result["clock"]["toleranceSeconds"], 2)
        self.assertEqual(len(result["warnings"]), 1)

    def test_incomplete_duration_and_capacity_are_rejected(self):
        for change in ({"samplingEndedElapsedNanos": 9_999_000_000}, {"timedEvidence": False},
                       {"completionReason": "capacity"}, {"completionReason": "summary"}):
            with self.subTest(change=change):
                self.assert_rejected(lambda r, m, b: r.update(change))

    def test_future_sampling_completion_is_rejected(self):
        self.assert_rejected(lambda r, m, b: r.update(originUtcMillis=UTC + 50_000))

    def test_missing_configuration_and_isolation_are_rejected(self):
        self.assert_rejected(lambda r, m, b: r["diagnosticConfig"].pop("audioTest"))
        self.assert_rejected(lambda r, m, b: r.pop("crypto"))
        self.assert_rejected(lambda r, m, b: r["diagnosticConfig"].update(diagnostics="1"))
        self.assert_rejected(lambda r, m, b: m["environment"].update(CP_IOS_SCENARIO="offline"))

    def test_launch_failure_and_wrong_binary_are_rejected(self):
        self.assert_rejected(lambda r, m, b: m.update(launchSucceeded=False))
        self.assert_rejected(lambda r, m, b: b.update(executableSHA256="c" * 64))
        self.assert_rejected(lambda r, m, b: m.update(sourceCommit="short"))

    def test_voice_scroll_and_unknown_diagnostic_settings_are_rejected(self):
        for setting in ("CP_IOS_VOICE_PROBE", "CP_IOS_SCROLL_TEST", "CP_IOS_UNKNOWN_PROBE"):
            with self.subTest(setting=setting):
                self.assert_rejected(lambda r, m, b: m["environment"].update({setting: "1"}))
        # An unknown setting cannot be declared safe merely by setting it to zero.
        self.assert_rejected(lambda r, m, b: m["environment"].update(CP_IOS_UNKNOWN_PROBE="0"))
        self.assert_rejected(lambda r, m, b: r["diagnosticConfig"].pop("voiceProbe"))
        self.assert_rejected(lambda r, m, b: r["diagnosticConfig"].pop("scrollTest"))

    def test_declared_disabled_voice_and_scroll_are_accepted(self):
        def disabled(report, metadata, manifest):
            for short, full in (("voiceProbe", "CP_IOS_VOICE_PROBE"), ("scrollTest", "CP_IOS_SCROLL_TEST")):
                report["diagnosticConfig"][short] = metadata["environment"][full] = "0"
        result = self.audit(disabled)
        self.assertTrue(result["evidenceAccepted"], result["problems"])

    def test_sample_count_and_missing_frame_are_rejected(self):
        self.assert_rejected(lambda r, m, b: r.update(sampledFrames=1))
        def remove_frame(report, metadata, manifest):
            report["samples"].pop(10)
            report["sampledFrames"] -= 1
        self.assert_rejected(remove_frame)

    def test_nonmonotonic_time_and_boolean_metrics_are_rejected(self):
        self.assert_rejected(lambda r, m, b: r["samples"][10].update(intervalEndElapsedNanos=0))
        self.assert_rejected(lambda r, m, b: r["samples"][10].update(intervalGCCount=True))

    def test_forged_warmup_and_cold_flags_are_rejected(self):
        def hide_steady_frames(report, metadata, manifest):
            for row in report["samples"][1:]:
                row["flags"] = 12
        self.assert_rejected(hide_steady_frames)
        self.assert_rejected(lambda r, m, b: r["samples"][400].update(flags=4))
        self.assert_rejected(lambda r, m, b: r["samples"][400].update(flags=8))
        self.assert_rejected(lambda r, m, b: r["samples"][100].update(flags=0))

    def test_warmup_boundary_uses_start_time_and_includes_exactly_five_seconds(self):
        result = self.audit()
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        menu = next(phase for phase in result["phaseStats"] if phase["name"] == "menu")
        self.assertEqual(menu["steadySameSceneIntervals"]["intervals"], 300)
        self.assert_rejected(lambda r, m, b: r["samples"][300].update(flags=0))

    def test_scene_change_restarts_warmup_without_restarting_cold_window(self):
        def change_scene(report, metadata, manifest):
            entry = report["samples"][400]["intervalEndElapsedNanos"]
            for index, row in enumerate(report["samples"][400:], 400):
                row["currentPhase"] = 1
                row["previousPhase"] = 3 if index == 400 else 1
                row["flags"] = (2 if index == 400 else 0) | \
                    (4 if row["intervalEndElapsedNanos"] - entry <= 5 * collector.NANOSECOND else 0)
        result = self.audit(change_scene, expected_phase="any")
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        def forge_changed_cold(report, metadata, manifest):
            change_scene(report, metadata, manifest)
            report["samples"][400]["flags"] |= 8
        self.assert_rejected(forge_changed_cold)

    def test_duration_bounds_and_primitive_capacity_are_enforced(self):
        for duration in (9, 901, 3600):
            with self.subTest(duration=duration):
                def outside_range(report, metadata, manifest):
                    report["evidenceSeconds"] = metadata["evidenceSeconds"] = duration
                    metadata["environment"]["CP_IOS_EVIDENCE_SECONDS"] = str(duration)
                self.assert_rejected(outside_range)
        self.assert_rejected(lambda r, m, b: r.update(sampleCapacity=120_000))
        self.assert_rejected(lambda r, m, b: r.update(sampleStoragePrimitiveBytes=1))
        def longest_window(report, metadata, manifest):
            report["evidenceSeconds"] = metadata["evidenceSeconds"] = 900
            metadata["environment"]["CP_IOS_EVIDENCE_SECONDS"] = "900"
            report["sampleCapacity"] = 108_600
            report["sampleStoragePrimitiveBytes"] = 108_600 * 83
            last, preceding = report["samples"][-1], report["samples"][-2]
            last["intervalEndElapsedNanos"] = 900 * collector.NANOSECOND
            last["intervalNs"] = last["intervalEndElapsedNanos"] - preceding["intervalEndElapsedNanos"]
            report["samplingEndedElapsedNanos"] = last["intervalEndElapsedNanos"] + last["currentRenderNs"]
            report["originUtcMillis"] -= 900_000
            metadata["launchRequestedUtcMillis"] -= 900_000
            metadata["launchCompletedUtcMillis"] -= 900_000
        result = self.audit(longest_window)
        self.assertTrue(result["evidenceAccepted"], result["problems"])

    def test_missing_last_frame_metrics_and_wrong_phase_are_rejected(self):
        self.assert_rejected(lambda r, m, b: r["samples"][-1].pop("currentRenderNs"))
        self.assert_rejected(lambda r, m, b: r["samples"][10].update(currentPhase=6))
        self.assert_rejected(lambda r, m, b: r["samples"][10].update(previousRenderNs=2))

    def test_missing_resume_marker_is_rejected(self):
        def reset(report, metadata, manifest):
            row = report["samples"][60]
            row.update({key: None for key in collector.INTERVAL_KEYS})
            row.update(previousPhase=-1, previousSequence=-1, flags=13)
        self.assert_rejected(reset)

    def test_lock_gap_is_unobserved_and_never_counted_as_a_hitch(self):
        def resume(report, metadata, manifest):
            report["evidenceSeconds"] = metadata["evidenceSeconds"] = 40
            metadata["environment"]["CP_IOS_EVIDENCE_SECONDS"] = "40"
            report["samplingEndedElapsedNanos"] += 30 * collector.NANOSECOND
            report["sampleCapacity"] = 40 * 120 + 600
            report["sampleStoragePrimitiveBytes"] = report["sampleCapacity"] * 83
            for row in report["samples"][60:]:
                row["intervalEndElapsedNanos"] += 30 * collector.NANOSECOND
                row["flags"] = 4 if row["intervalEndElapsedNanos"] - 31 * collector.NANOSECOND <= 5 * collector.NANOSECOND else 0
            row = report["samples"][60]
            row.update({key: None for key in collector.INTERVAL_KEYS})
            row.update(previousPhase=-1, previousSequence=-1, flags=21)
        result = self.audit(resume, expected_phase="any")
        self.assertTrue(result["evidenceAccepted"], result["problems"])
        self.assertEqual(result["longIntervals"], [])
        self.assertEqual(len(result["unobservedPauseResumeGaps"]), 1)
        self.assertGreater(result["unobservedPauseResumeGaps"][0]["seconds"], 30)
        self.assertLess(result["sameSceneCoverage"]["menu"], .3)

    def test_menu_coverage_rejects_a_different_scene(self):
        def table(report, metadata, manifest):
            for row in report["samples"]:
                row["currentPhase"] = 1
                if row["previousPhase"] != -1:
                    row["previousPhase"] = 1
        self.assert_rejected(table)
        result = self.audit(table, expected_phase="table")
        self.assertTrue(result["evidenceAccepted"])

    def test_last_render_is_included(self):
        result = self.audit(lambda r, m, b: (r["samples"][-1].update(currentRenderNs=50_000_000),
                                            r.update(samplingEndedElapsedNanos=10_050_000_000)))
        self.assertTrue(result["evidenceAccepted"])
        menu = next(phase for phase in result["phaseStats"] if phase["name"] == "menu")
        self.assertEqual(menu["renderMaxMs"], 50)

    def test_declared_completion_cannot_extend_a_short_array(self):
        self.assert_rejected(lambda r, m, b: r.update(samplingEndedElapsedNanos=20_000_000_000))

    def test_audit_is_read_only(self):
        report, metadata, manifest = fixture()
        initial = copy.deepcopy((report, metadata, manifest))
        with patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected process/device access")):
            collector.audit_evidence(report, metadata, "fresh-fixture", manifest)
        self.assertEqual((report, metadata, manifest), initial)

    def test_duplicate_json_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"runID":"old","runID":"new"}')
            with self.assertRaises(ValueError):
                collector.load_json(path)

    def test_nonfinite_json_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nonfinite.json"
            path.write_text('{"intervalNs":NaN}')
            with self.assertRaises(ValueError):
                collector.load_json(path)

    def test_simulator_collects_once_after_local_wait_and_replays_without_contact(self):
        report, metadata, manifest = fixture()
        # Build11's default-enabled production cache has no explicit control flag.
        self.assertIsNone(report["diagnosticConfig"]["quoteWrapCache"])
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, output, container = base / "source", base / "collected", base / "app-data"
            source.mkdir()
            (container / "Documents").mkdir(parents=True)
            (container / "Documents/ios-scene-evidence.json").write_text(json.dumps(report))
            (source / "manifest.json").write_text(json.dumps(manifest))
            metadata["binaryManifest"] = "manifest.json"
            (source / "launch.json").write_text(json.dumps(metadata))
            events = []

            def query_once(arguments, **kwargs):
                self.assertEqual(events, ["wait"])
                self.assertEqual(arguments, ["xcrun", "simctl", "get_app_container", "synthetic-simulator",
                                             collector.BUNDLE, "data"])
                return collector.subprocess.CompletedProcess(arguments, 0, stdout=str(container) + "\n")

            arguments = ["collector", "--simulator", "synthetic-simulator", "--run-id", metadata["runID"],
                         "--launch-metadata", str(source / "launch.json"), "--output", str(output)]
            with patch.object(collector.sys, "argv", arguments), \
                    patch.object(collector.subprocess, "run", side_effect=query_once) as command, \
                    patch.object(collector.shutil, "copyfile", wraps=collector.shutil.copyfile) as copy_file, \
                    patch.object(collector.time, "time", side_effect=[UTC / 1000, UTC / 1000 + 50]), \
                    patch.object(collector.time, "sleep", side_effect=lambda seconds: events.append("wait")), \
                    patch.object(collector.time, "time_ns", return_value=(UTC + 50_000) * 1_000_000), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(collector.main(), 0)
                self.assertEqual(command.call_count, 1)
                self.assertEqual(copy_file.call_count, 1)
            provenance = collector.load_json(output / "collection-metadata.json")
            self.assertEqual(provenance["platform"], "simulator")
            self.assertEqual(provenance["deviceCopyCount"], 0)
            self.assertEqual(provenance["simulatorContainerQueryCount"], 1)
            self.assertEqual(provenance["localFileCopyCount"], 1)
            arguments = ["collector", "--audit-only", "--run-id", metadata["runID"], "--output", str(output)]
            with patch.object(collector.sys, "argv", arguments), \
                    patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected target access")), \
                    patch.object(collector.shutil, "copyfile", side_effect=AssertionError("Unexpected report copy")), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(collector.main(), 0)
            result = collector.load_json(output / "scene-audit.json")
            self.assertTrue(result["evidenceAccepted"])
            self.assertEqual(result["control"], "normal")
            self.assertEqual(result["collectionPlatform"], "simulator")
            self.assertIn("not equivalent to physical iPad", result["platformLimit"])
            self.assertNotIn("synthetic-simulator", json.dumps(result))

    def test_live_target_selection_is_required_and_mutually_exclusive(self):
        common = ["collector", "--run-id", "fresh-fixture", "--output", "/unused-output",
                  "--launch-metadata", "/unused-launch.json"]
        for targets in ([], ["--device", "synthetic-device", "--simulator", "synthetic-simulator"]):
            with self.subTest(targets=targets), patch.object(collector.sys, "argv", common + targets), \
                    patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected target access")), \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    collector.main()
                self.assertEqual(failure.exception.code, 2)

    def test_simulator_container_failure_has_no_retry_or_device_fallback(self):
        report, metadata, manifest = fixture()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, output = base / "source", base / "collected"
            source.mkdir()
            (source / "manifest.json").write_text(json.dumps(manifest))
            metadata["binaryManifest"] = "manifest.json"
            (source / "launch.json").write_text(json.dumps(metadata))
            arguments = ["collector", "--simulator", "synthetic-simulator", "--run-id", metadata["runID"],
                         "--launch-metadata", str(source / "launch.json"), "--output", str(output)]
            errors = io.StringIO()
            failure = collector.subprocess.CalledProcessError(1, ["xcrun", "simctl", "synthetic-simulator"])
            with patch.object(collector.sys, "argv", arguments), \
                    patch.object(collector.subprocess, "run", side_effect=failure) as command, \
                    patch.object(collector.shutil, "copyfile", side_effect=AssertionError("Unexpected report copy")), \
                    patch.object(collector.time, "time", return_value=UTC / 1000 + 100), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                self.assertEqual(collector.main(), 1)
                self.assertEqual(command.call_count, 1)
            self.assertNotIn("synthetic-simulator", errors.getvalue())
            self.assertFalse((output / "collection-metadata.json").exists())

    def test_collector_only_copies_once_and_offline_replay_contacts_no_device(self):
        report, metadata, manifest = fixture()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source"
            source.mkdir()
            output = base / "collected"
            (source / "manifest.json").write_text(json.dumps(manifest))
            metadata["binaryManifest"] = "manifest.json"
            (source / "launch.json").write_text(json.dumps(metadata))

            def copy_once(arguments, **kwargs):
                self.assertEqual(arguments[:7], ["xcrun", "devicectl", "--timeout", "25", "device", "copy", "from"])
                destination = arguments[arguments.index("--destination") + 1]
                Path(destination).write_text(json.dumps(report))

            live_args = ["collector", "--device", "synthetic-only", "--run-id", metadata["runID"],
                         "--launch-metadata", str(source / "launch.json"), "--output", str(output)]
            with patch.object(collector.sys, "argv", live_args), \
                    patch.object(collector.subprocess, "run", side_effect=copy_once) as command, \
                    patch.object(collector.time, "time", return_value=UTC / 1000 + 100), \
                    patch.object(collector.time, "time_ns", return_value=(UTC + 50_000) * 1_000_000), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(collector.main(), 0)
                self.assertEqual(command.call_count, 1)
            audit_args = ["collector", "--audit-only", "--run-id", metadata["runID"], "--output", str(output)]
            with patch.object(collector.sys, "argv", audit_args), \
                    patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected device call")), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(collector.main(), 0)
            snapshot = collector.load_json(output / "scene-audit.json")
            self.assertTrue(snapshot["evidenceAccepted"])
            self.assertEqual(collector.load_json(output / "collection-metadata.json")["deviceCopyCount"], 1)
            report["runID"] = "altered"
            (output / "ios-scene-evidence.json").write_text(json.dumps(report))
            with patch.object(collector.sys, "argv", audit_args), \
                    patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected device call")), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(collector.main(), 1)

    def test_live_metadata_destination_collision_is_rejected_without_mutation(self):
        report, metadata, manifest = fixture()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "manifest.json").write_text(json.dumps(manifest))
            metadata["binaryManifest"] = "manifest.json"
            metadata_path = output / "launch-metadata.json"
            metadata_path.write_text(json.dumps(metadata))
            original = metadata_path.read_bytes()
            arguments = ["collector", "--device", "synthetic-only", "--run-id", metadata["runID"],
                         "--launch-metadata", str(metadata_path), "--output", str(output)]
            errors = io.StringIO()
            with patch.object(collector.sys, "argv", arguments), \
                    patch.object(collector.subprocess, "run", side_effect=AssertionError("Unexpected device call")), \
                    contextlib.redirect_stderr(errors):
                self.assertEqual(collector.main(), 1)
            self.assertEqual(metadata_path.read_bytes(), original)
            self.assertIn("input will not be overwritten", errors.getvalue())
            self.assertFalse((output / "ios-scene-evidence.json").exists())


if __name__ == "__main__":
    unittest.main()
