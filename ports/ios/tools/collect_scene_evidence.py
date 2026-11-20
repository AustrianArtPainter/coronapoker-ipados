#!/usr/bin/env python3
"""Copy one completed game frame report, then audit it locally.

This tool never launches, installs, terminates, or changes the app. It waits
locally for the declared window to end, then performs exactly one file copy.
The simulator path queries its app data container once after that local wait.
No status listener, automatic game scenario, screenshot, or live polling is used.

Launch metadata is a local JSON object containing runID, launchSucceeded,
launchRequestedUtcMillis, launchCompletedUtcMillis, evidenceSeconds (10..900), environment,
sourceCommit (full Git SHA), binaryManifest (path relative to that JSON), and
executableSHA256. Optional deviceLaunchResult preserves a reference to the
devicectl launch JSON. Use a fresh run ID and an ignored validation directory.
Normal is the default. The explicit --control quote-wrap-cache diagnostic requires
metadata control="quote-wrap-cache" and the matching launch/report setting "1".
The frame-attribution control likewise requires its exact flag and an enabled
recorder declaration; its output is instrumented diagnostic evidence only.

An accepted audit means capture integrity, freshness, and scene coverage only.
It does not establish audible playback, absence of crackle, or a resolved issue.
"""
import argparse
import hashlib
import json
import math
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
BUNDLE = "com.tonikelope.coronapoker.ios"
NANOSECOND = 1_000_000_000
MIN_EVIDENCE_SECONDS = 10
MAX_EVIDENCE_SECONDS = 900
SAMPLE_PRIMITIVE_BYTES = 10 * 8 + 3  # Producer owns ten long arrays and three byte arrays.
PHASE_NAMES = {0: "preparing", 1: "table", 2: "summary", 3: "menu", 4: "startup", 5: "frontend"}
FLAGS = {"noPreviousInterval": 1, "sceneChange": 2, "within5SecondsOfSceneEntry": 4,
         "coldStartWindow": 8, "firstAfterResume": 16}
CONFIG_ENV = {
    "profile": "CP_IOS_PROFILE", "diagnostics": "CP_IOS_DIAGNOSTICS",
    "pathStress": "CP_IOS_PATH_STRESS", "scenario": "CP_IOS_SCENARIO",
    "audioTest": "CP_IOS_AUDIO_TEST", "cryptoTest": "CP_IOS_CRYPTO_TEST",
    "animationTest": "CP_IOS_ANIMATION_TEST", "tablePaintTest": "CP_IOS_TABLE_PAINT_TEST",
    "tableDrawTest": "CP_IOS_TABLE_DRAW_PROBE", "allInIsolation": "CP_IOS_ALLIN_ISOLATION",
    "backendProbe": "CP_IOS_BACKEND_PROBE", "sceneScopes": "CP_IOS_SCENE_SCOPES",
    "vertexProbe": "CP_IOS_VERTEX_PROBE", "voiceProbe": "CP_IOS_VOICE_PROBE",
    "scrollTest": "CP_IOS_SCROLL_TEST", "quoteWrapCache": "CP_IOS_QUOTE_WRAP_CACHE",
    "musicBackend": "CP_IOS_MUSIC_BACKEND",
    "musicProbe": "CP_IOS_MUSIC_PROBE",
    "gcProbe": "CP_IOS_GC_PROBE",
    "audioClockSeconds": "CP_IOS_AUDIO_CLOCK_SECONDS",
    "frameAttribution": "CP_IOS_FRAME_ATTRIBUTION",
}
START_PHASE_ENV = "CP_IOS_EVIDENCE_START_PHASE"
START_PHASES = ("menu", "table", "summary")
START_PHASE_WARMUP_SECONDS = 5
ALLOWED_CP_ENV = {"CP_IOS_RUN_ID", "CP_IOS_SCENE_EVIDENCE", "CP_IOS_EVIDENCE_SECONDS", START_PHASE_ENV, *CONFIG_ENV.values()}
INTERVAL_KEYS = ("intervalNs", "previousRenderNs", "intervalProcessAllocatedBytes",
                 "previousRenderProcessAllocatedBytes", "intervalGCCount")
CURRENT_KEYS = ("currentRenderNs", "currentRenderProcessAllocatedBytes", "currentRenderGCCount")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
GIT_SHA = re.compile(r"[0-9a-f]{40}\Z")
RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
QUOTE_WRAP_CONTROL = "quote-wrap-cache"
FRAME_ATTRIBUTION_CONTROL = "frame-attribution"
MUSIC_CONTROLS = {"legacy-music": "legacy", "queue-music": "queue"}
CONTROLS = ("normal", QUOTE_WRAP_CONTROL, *MUSIC_CONTROLS, FRAME_ATTRIBUTION_CONTROL)


def allowed_config(control, short):
    if short == "frameAttribution":
        return ("1",) if control == FRAME_ATTRIBUTION_CONTROL else (None,)
    if short == "audioClockSeconds":
        return (None,)
    if short == "musicBackend":
        return (MUSIC_CONTROLS[control],) if control in MUSIC_CONTROLS else (None,)
    return ("1",) if control == QUOTE_WRAP_CONTROL and short == "quoteWrapCache" else (None, "", "0")


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def digest(path):
    checksum = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def load_json(path):
    # Duplicate JSON keys could hide a conflicting run/configuration declaration.
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result
    with Path(path).open() as stream:
        return json.load(stream, object_pairs_hook=unique_pairs,
                         parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON number")))


def percentile(values, fraction):
    ordered = sorted(values)
    return None if not ordered else ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] / 1_000_000


def timing_stats(rows):
    values = [row["intervalNs"] for row in rows]
    nanoseconds = sum(values)
    slow = [row for row in rows if row["intervalNs"] > NANOSECOND / 30]
    return {"intervals": len(values), "observedIntervalSeconds": nanoseconds / NANOSECOND,
            "p95Ms": percentile(values, .95), "p99Ms": percentile(values, .99),
            "maxMs": percentile(values, 1),
            "over25ms": sum(value > 25_000_000 for value in values),
            "over33_3ms": len(slow), "over100ms": sum(value > 100_000_000 for value in values),
            "over33_3msPerMinute": len(slow) * 60 * NANOSECOND / nanoseconds if nanoseconds else None,
            "intervalGCCount": sum(row["intervalGCCount"] for row in rows),
            "slowIntervalsWithGC": sum(row["intervalGCCount"] > 0 for row in slow),
            "intervalProcessAllocatedBytes": sum(row["intervalProcessAllocatedBytes"] for row in rows),
            "meanProcessAllocatedBytesPerInterval": (sum(row["intervalProcessAllocatedBytes"] for row in rows)
                                                      / len(rows) if rows else None)}


def launch_problems(metadata, run_id, manifest, *, control="normal"):
    problems = []
    if control not in CONTROLS:
        problems.append("Unsupported capture control")
    if not isinstance(metadata, dict):
        return ["Launch metadata must be an object"]
    if metadata.get("control", "normal") != control:
        problems.append("Launch metadata control does not match the explicit audit control")
    if metadata.get("runID") != run_id:
        problems.append("Launch metadata runID does not match this request")
    if metadata.get("launchSucceeded") is not True:
        problems.append("Successful launch was not recorded")
    requested, completed = metadata.get("launchRequestedUtcMillis"), metadata.get("launchCompletedUtcMillis")
    if not integer(requested, 1) or not integer(completed, 1) or completed < requested:
        problems.append("Launch UTC boundaries are missing or invalid")
    if not integer(metadata.get("evidenceSeconds"), MIN_EVIDENCE_SECONDS) or metadata["evidenceSeconds"] > MAX_EVIDENCE_SECONDS:
        problems.append("Launch evidenceSeconds must be an integer from 10 to 900")
    if not isinstance(metadata.get("sourceCommit"), str) or not GIT_SHA.fullmatch(metadata["sourceCommit"]):
        problems.append("Full source Git commit is missing or invalid")
    binary_hash = metadata.get("executableSHA256")
    if not isinstance(binary_hash, str) or not SHA256.fullmatch(binary_hash):
        problems.append("Launch executable SHA256 is missing or invalid")
    if not isinstance(manifest, dict) or manifest.get("bundleIdentifier") != BUNDLE:
        problems.append("A matching binary manifest is required")
    else:
        if manifest.get("executableSHA256") != binary_hash:
            problems.append("Launch executable hash differs from the binary manifest")
        if not isinstance(manifest.get("gameVersion"), str) or not manifest["gameVersion"]:
            problems.append("Binary manifest game version is missing")
        if type(manifest.get("build")) not in (int, str) or not str(manifest["build"]).isdigit():
            problems.append("Binary manifest build is missing or invalid")
    environment = metadata.get("environment")
    if not isinstance(environment, dict) or any(type(k) is not str or type(v) is not str
                                                 for k, v in environment.items()):
        problems.append("Actual launch environment must be a string dictionary")
    else:
        if environment.get("CP_IOS_RUN_ID") != run_id or environment.get("CP_IOS_SCENE_EVIDENCE") != "1":
            problems.append("Launch did not declare this runID and frame evidence enabled")
        if environment.get("CP_IOS_EVIDENCE_SECONDS") != str(metadata.get("evidenceSeconds")):
            problems.append("Launch duration environment does not match metadata")
        start_phase = environment.get(START_PHASE_ENV)
        if start_phase is not None:
            if start_phase not in START_PHASES:
                problems.append("Deferred capture phase must be menu, table or summary")
            ready = metadata.get("captureReadyUtcMillis")
            if not integer(ready, 1) or (integer(completed, 1) and ready < completed):
                problems.append("Deferred capture requires a post-launch target-page observation UTC")
        for short, key in CONFIG_ENV.items():
            allowed = allowed_config(control, short)
            if environment.get(key) not in allowed:
                problems.append("Launch configuration is not permitted by this capture control: " + key)
        for key in environment:
            if key.startswith("CP_IOS_") and key not in ALLOWED_CP_ENV:
                problems.append("Launch contains an unreviewed CP_IOS setting: " + key)
            if key.startswith("GC_"):
                problems.append("Normal scene capture cannot use collector environment tuning: " + key)
    return problems


def audit_evidence(report, metadata, run_id, manifest, *, control="normal", expected_phase="menu",
                   minimum_phase_coverage=.8, minimum_phase_seconds=0,
                   clock_tolerance_seconds=2, collected_utc_millis=None):
    """Pure audit: no file writes, device reads, or mutations of its inputs."""
    problems = launch_problems(metadata, run_id, manifest, control=control)
    metadata = metadata if isinstance(metadata, dict) else {}
    manifest = manifest if isinstance(manifest, dict) else {}
    warnings = []
    if not isinstance(report, dict):
        report = {}
        problems.append("Frame evidence must be a JSON object")
    if report.get("kind") != "retained-scene-v5":
        problems.append("Complete retained-scene-v5 evidence is required; old summaries are rejected")
    if report.get("runID") != run_id:
        problems.append("Evidence belongs to another run")
    if report.get("timedEvidence") is not True:
        problems.append("Evidence is not a complete timed capture")
    seconds = report.get("evidenceSeconds")
    if not integer(seconds, MIN_EVIDENCE_SECONDS) or seconds > MAX_EVIDENCE_SECONDS or seconds != metadata.get("evidenceSeconds"):
        problems.append("Evidence duration is missing or differs from this launch")
    reason = report.get("completionReason")
    if reason not in ("duration", "capacity", "summary"):
        problems.append("Evidence completion reason is missing or invalid")
    elif reason != "duration":
        problems.append("Timed window was interrupted by " + reason + " completion")
    origin, monotonic = report.get("originUtcMillis"), report.get("originMonotonicNanos")
    ended = report.get("samplingEndedElapsedNanos")
    if not integer(origin, 1) or type(monotonic) is not int:
        problems.append("Evidence UTC/monotonic origin is missing or invalid")
    if not integer(ended, 1):
        problems.append("Sampling end timestamp is missing or invalid")
    elif integer(seconds, 1) and ended < seconds * NANOSECOND:
        problems.append("Evidence stopped before the requested duration")
    requested = metadata.get("launchRequestedUtcMillis")
    delta = origin - requested if integer(origin, 1) and integer(requested, 1) else None
    tolerance_ms = clock_tolerance_seconds * 1000
    if delta is not None:
        if delta < -tolerance_ms:
            problems.append("Evidence origin predates this launch beyond the declared clock tolerance")
        elif delta < 0:
            warnings.append("Device origin predates Mac launch by %.3fs, accepted only within the explicit clock tolerance"
                            % (-delta / 1000))
    if integer(collected_utc_millis, 1) and integer(origin, 1) and integer(ended, 1):
        if origin + ended / 1_000_000 > collected_utc_millis + tolerance_ms:
            problems.append("Device sampling end is later than collection beyond the clock tolerance")
    config = report.get("diagnosticConfig")
    environment = metadata.get("environment", {})
    environment = environment if isinstance(environment, dict) else {}
    # Old saved v5 reports predate the recorder. An explicit declaration must
    # still be typed, and actual instrumentation cannot hide behind unset flags.
    attribution = report.get("frameAttribution", {"enabled": False})
    attribution_enabled = attribution.get("enabled") if isinstance(attribution, dict) else None
    if type(attribution_enabled) is not bool:
        problems.append("Frame attribution recorder declaration is invalid")
    elif attribution_enabled != (control == FRAME_ATTRIBUTION_CONTROL):
        problems.append("Actual frame attribution recorder does not match the explicit capture control")
    runtime = report.get("gcRuntime")
    gc_policy = manifest.get("nativeGCSuspendPolicy")
    if gc_policy is not None or runtime is not None:
        if gc_policy not in ("registered", "original"):
            problems.append("GC runtime policy is not bound to a supported binary manifest policy")
        elif not isinstance(runtime, dict) or not integer(runtime.get("markerHelpers")) \
                or not integer(runtime.get("version"), 1):
            problems.append("Actual GC runtime snapshot is missing or invalid")
        elif type(manifest.get("expectedGCVersion")) is not int or runtime["version"] != manifest["expectedGCVersion"]:
            problems.append("Actual GC version differs from the binary manifest")
        elif runtime.get("collectorEnvironmentOverridesPresent") is not False:
            problems.append("Actual process has collector environment tuning or no tuning snapshot")
        elif runtime.get("bundlePolicy") != gc_policy \
                or runtime.get("bundleManifestSHA256") != manifest.get("gcBuildManifestSHA256") \
                or (gc_policy == "registered" and not isinstance(manifest.get("gcBuildManifestSHA256"), str)) \
                or (gc_policy == "registered" and not SHA256.fullmatch(manifest["gcBuildManifestSHA256"])) \
                or (gc_policy == "original" and manifest.get("gcBuildManifestSHA256") != "sdk-original"):
            problems.append("GC build identity differs from the bundle or binary manifest")
        elif (gc_policy == "registered" and
              (runtime.get("policy") != "registered" or type(runtime.get("taskThreadDiscovery")) is not int
               or runtime["taskThreadDiscovery"] != 0)) \
                or (gc_policy == "original" and
                    (runtime.get("policy") != "sdk-original" or type(runtime.get("taskThreadDiscovery")) is not int
                     or runtime["taskThreadDiscovery"] != -1)):
            problems.append("Actual GC suspension policy differs from the binary manifest")
    start_phase = environment.get(START_PHASE_ENV)
    if control in MUSIC_CONTROLS and report.get("backgroundMusicBackend") != MUSIC_CONTROLS[control]:
        problems.append("Actual background music backend does not match the explicit comparison control")
    if report.get("evidenceStartPhase") != start_phase:
        problems.append("Evidence start phase differs from launch environment")
    if start_phase in START_PHASES:
        ready = metadata.get("captureReadyUtcMillis")
        foreground, foreground_monotonic = report.get("firstForegroundUtcMillis"), report.get("firstForegroundMonotonicNanos")
        stable = report.get("startPhaseStableNanos")
        if not integer(foreground, 1) or not integer(foreground_monotonic, 1) \
                or not integer(monotonic, 1) or foreground_monotonic > monotonic:
            problems.append("Deferred capture lacks a valid first-foreground clock pair")
        elif integer(origin, 1):
            waited_ms = (monotonic - foreground_monotonic) / 1_000_000
            if abs(origin - foreground - waited_ms) > tolerance_ms:
                problems.append("Deferred UTC and monotonic wait disagree beyond clock tolerance")
        if not integer(stable, START_PHASE_WARMUP_SECONDS * NANOSECOND):
            problems.append("Deferred target phase did not declare five stable foreground seconds")
        if integer(ready, 1) and integer(origin, 1) \
                and origin > ready + (START_PHASE_WARMUP_SECONDS + 1) * 1000 + tolerance_ms:
            problems.append("Deferred capture began after the observed target-page readiness bound")
        if integer(foreground, 1) and integer(requested, 1) and foreground < requested - tolerance_ms:
            problems.append("First foreground render predates this launch beyond clock tolerance")
    if not isinstance(config, dict):
        problems.append("Full diagnostic configuration declaration is missing")
    else:
        for short, full in CONFIG_ENV.items():
            allowed = allowed_config(control, short)
            # Baselines from build9 predate this one optional control snapshot key.
            legacy_missing = short not in config and ((control == "normal" and short == "quoteWrapCache")
                                or (control not in MUSIC_CONTROLS and short == "musicBackend")
                                or (short in ("musicProbe", "gcProbe", "audioClockSeconds", "frameAttribution")
                                    and environment.get(full) is None))
            if (short not in config and not legacy_missing) or config.get(short) not in allowed:
                problems.append("Evidence configuration is omitted or not permitted by this capture control: " + short)
            if config.get(short) != environment.get(full):
                problems.append("Evidence configuration differs from launch environment: " + short)
    for key, env_key in (("audio", "CP_IOS_AUDIO_TEST"), ("crypto", "CP_IOS_CRYPTO_TEST"),
                         ("animation", "CP_IOS_ANIMATION_TEST")):
        if key not in report or report.get(key) != environment.get(env_key):
            problems.append("Normal isolation declaration is missing or mismatched: " + key)
    if report.get("liveReporter") is not False or report.get("allocationScopesEnabled") is not False:
        problems.append("Baseline contains live reporting or allocation-scope instrumentation")
    if report.get("flagsMap") != FLAGS:
        problems.append("Sample flag definition is missing or unsupported")
    names = report.get("phaseNames")
    if names != list(PHASE_NAMES.values()):
        problems.append("Scene phase definition is missing or unsupported")
    samples = report.get("samples")
    capacity = seconds * 120 + 600 if integer(seconds, MIN_EVIDENCE_SECONDS) and seconds <= MAX_EVIDENCE_SECONDS else 108_600
    if report.get("sampleCapacity") != capacity or type(report.get("sampleCapacity")) is not int:
        problems.append("Sample capacity does not match the declared timed window")
    if report.get("sampleStoragePrimitiveBytes") != capacity * SAMPLE_PRIMITIVE_BYTES or type(report.get("sampleStoragePrimitiveBytes")) is not int:
        problems.append("Primitive sample storage declaration does not match the sample capacity")
    if not isinstance(samples, list) or not 2 <= len(samples) <= capacity:
        problems.append("A complete bounded sample array with at least two frames is required")
        samples = samples if isinstance(samples, list) else []
    if type(report.get("sampledFrames")) is not int or report.get("sampledFrames") != len(samples):
        problems.append("sampledFrames differs from the complete sample array")
    if start_phase in START_PHASES and samples and isinstance(samples[0], dict) \
            and PHASE_NAMES.get(samples[0].get("currentPhase")) != start_phase:
        problems.append("First sample does not belong to the requested deferred phase")
    valid_intervals, valid_current, gaps = [], [], []
    previous = None
    phase_entry_elapsed = None
    for index, row in enumerate(samples):
        prefix = "Sample %d: " % index
        if not isinstance(row, dict):
            problems.append(prefix + "must be an object")
            previous = None
            continue
        elapsed, flags = row.get("intervalEndElapsedNanos"), row.get("flags")
        phase, previous_phase = row.get("currentPhase"), row.get("previousPhase")
        if not integer(elapsed) or (previous is not None and elapsed <= previous["intervalEndElapsedNanos"]):
            problems.append(prefix + "elapsed sample timestamps are missing or not strictly increasing")
            previous = None
            continue
        if index == 0 and elapsed != 0:
            problems.append(prefix + "first frame must start at the declared monotonic origin")
        if not integer(flags) or flags > 31 or type(phase) is not int or phase not in PHASE_NAMES:
            problems.append(prefix + "flags or currentPhase are invalid")
            previous = None
            continue
        if any(key not in row or not integer(row[key]) for key in CURRENT_KEYS):
            problems.append(prefix + "current render metrics are missing or invalid")
            previous = None
            continue
        if not integer(row.get("previousSequence"), -1) or type(previous_phase) is not int:
            problems.append(prefix + "previous scene/sequence metadata is missing or invalid")
            previous = None
            continue
        # Reconstruct scene entry from actual sample phases and baseline resets.
        # A forged warmup/cold flag must not hide a long frame from steady stats.
        if previous is None or flags & FLAGS["noPreviousInterval"] or phase != previous["currentPhase"]:
            phase_entry_elapsed = elapsed
        if bool(flags & FLAGS["coldStartWindow"]) != (elapsed <= 5 * NANOSECOND):
            problems.append(prefix + "cold-start flag disagrees with the monotonic capture origin")
        if bool(flags & FLAGS["within5SecondsOfSceneEntry"]) != (elapsed - phase_entry_elapsed <= 5 * NANOSECOND):
            problems.append(prefix + "scene warmup flag disagrees with reconstructed scene entry")
        if integer(ended, 1) and elapsed + row["currentRenderNs"] > ended:
            problems.append(prefix + "render ends after declared sampling completion")
        if previous is not None and integer(seconds, 1) and \
                previous["intervalEndElapsedNanos"] + previous["currentRenderNs"] >= seconds * NANOSECOND:
            problems.append(prefix + "capture continued after the timed window had already completed")
        if flags & FLAGS["noPreviousInterval"]:
            if any(key not in row or row[key] is not None for key in INTERVAL_KEYS):
                problems.append(prefix + "disconnected first/resume interval must contain explicit null metrics")
            if previous_phase != -1 or row["previousSequence"] != -1 or flags & FLAGS["sceneChange"]:
                problems.append(prefix + "disconnected first/resume interval retains previous scene metadata")
            if index > 0 and not flags & FLAGS["firstAfterResume"]:
                problems.append(prefix + "a later disconnected interval lacks a resume marker")
            if previous is not None:
                gaps.append({"fromElapsedNanos": previous["intervalEndElapsedNanos"],
                             "toElapsedNanos": elapsed, "seconds": (elapsed - previous["intervalEndElapsedNanos"]) / NANOSECOND,
                             "reason": "pause/resume baseline reset; excluded from hitch statistics"})
        else:
            if index == 0 or previous is None:
                problems.append(prefix + "interval has no preceding complete sample")
            if flags & FLAGS["firstAfterResume"]:
                problems.append(prefix + "resume sample did not disconnect its prior interval")
            if any(key not in row or not integer(row[key]) for key in INTERVAL_KEYS) or row.get("intervalNs") == 0:
                problems.append(prefix + "interval metrics are missing or invalid")
            elif previous is not None:
                if row["intervalNs"] != elapsed - previous["intervalEndElapsedNanos"]:
                    problems.append(prefix + "interval does not span consecutive samples (missing/altered frame)")
                if previous_phase != previous["currentPhase"] or previous_phase not in PHASE_NAMES:
                    problems.append(prefix + "previousPhase does not match the preceding sample")
                if bool(flags & FLAGS["sceneChange"]) != (phase != previous_phase):
                    problems.append(prefix + "scene-change flag disagrees with the interval phases")
                if row["previousRenderNs"] != previous["currentRenderNs"] or \
                        row["previousRenderProcessAllocatedBytes"] != previous["currentRenderProcessAllocatedBytes"]:
                    problems.append(prefix + "prior render does not match the preceding complete sample")
                if row["previousRenderNs"] > row["intervalNs"] or \
                        row["previousRenderProcessAllocatedBytes"] > row["intervalProcessAllocatedBytes"] or \
                        previous["currentRenderGCCount"] > row["intervalGCCount"]:
                    problems.append(prefix + "render metrics exceed their enclosing interval")
                if previous_phase in PHASE_NAMES:
                    valid_intervals.append(row)
        valid_current.append(row)
        previous = row
    if samples and isinstance(samples[-1], dict) and integer(samples[-1].get("intervalEndElapsedNanos")) \
            and integer(samples[-1].get("currentRenderNs")) and integer(ended, 1):
        last_end = samples[-1]["intervalEndElapsedNanos"] + samples[-1]["currentRenderNs"]
        if last_end != ended:
            problems.append("Sampling completion does not match the final recorded render end")
    # Limit corrupted-report diagnostics without hiding that additional errors occurred.
    problems = list(dict.fromkeys(problems))
    error_count = len(problems)
    if error_count > 100:
        problems = problems[:100] + ["Additional integrity problems omitted: %d" % (error_count - 100)]
    phases = []
    for phase, name in PHASE_NAMES.items():
        rows = [row for row in valid_intervals if row["previousPhase"] == phase]
        stable = [row for row in rows if row["currentPhase"] == phase
                  and not row["flags"] & (FLAGS["within5SecondsOfSceneEntry"] | FLAGS["coldStartWindow"])]
        current = [row for row in valid_current if row["currentPhase"] == phase]
        phases.append({"phase": phase, "name": name, "renderSamples": len(current),
                       "allObservedIntervals": timing_stats(rows), "steadySameSceneIntervals": timing_stats(stable),
                       "renderProcessAllocatedBytes": sum(row["currentRenderProcessAllocatedBytes"] for row in current),
                       "renderGCCount": sum(row["currentRenderGCCount"] for row in current),
                       "renderP95Ms": percentile([row["currentRenderNs"] for row in current], .95),
                       "renderP99Ms": percentile([row["currentRenderNs"] for row in current], .99),
                       "renderMaxMs": percentile([row["currentRenderNs"] for row in current], 1)})
    observed_same_phase = {name: sum(row["intervalNs"] for row in valid_intervals
                                   if row["previousPhase"] == phase and row["currentPhase"] == phase) / NANOSECOND
                           for phase, name in PHASE_NAMES.items()}
    window_seconds = ended / NANOSECOND if integer(ended, 1) else None
    coverage = {name: duration / window_seconds if window_seconds else None
                for name, duration in observed_same_phase.items()}
    if expected_phase != "any":
        measured = observed_same_phase[expected_phase]
        ratio = coverage[expected_phase]
        if ratio is None or ratio < minimum_phase_coverage or measured < minimum_phase_seconds:
            problems.append("Requested %s scene coverage/duration is insufficient" % expected_phase)
    slow = []
    for row in valid_intervals:
        if row["intervalNs"] > NANOSECOND / 30:
            elapsed = row["intervalEndElapsedNanos"]
            slow.append({"intervalEndElapsedNanos": elapsed,
                         "estimatedIntervalEndUtcMillis": origin + elapsed / 1_000_000 if integer(origin, 1) else None,
                         "intervalMs": row["intervalNs"] / 1_000_000,
                         "previousRenderMs": row["previousRenderNs"] / 1_000_000,
                         "intervalGCCount": row["intervalGCCount"],
                         "intervalProcessAllocatedBytes": row["intervalProcessAllocatedBytes"],
                         "previousPhase": row["previousPhase"], "currentPhase": row["currentPhase"],
                         "previousSequence": row["previousSequence"], "flags": row["flags"]})
    return {"kind": ("frame-attribution-capture-audit-v1" if control == FRAME_ATTRIBUTION_CONTROL
                     else "normal-scene-capture-audit-v1" if control == "normal"
                     else "controlled-scene-capture-audit-v1"),
            "control": control, "evidenceAccepted": not problems,
            **({"normalAcceptance": False, "normalScenePerformance": False,
                "diagnosticInstrumentation": True} if control == FRAME_ATTRIBUTION_CONTROL else {}),
            "scope": ("Instrumented frame-attribution diagnostic; capture integrity and scene coverage only; "
                      "does not establish normal performance or an audio fix" if control == FRAME_ATTRIBUTION_CONTROL
                      else "Capture integrity, freshness, explicit control configuration and scene coverage only; does not establish an audio fix"),
            "audioQuality": "unmeasured; requires physical listening or independent audio evidence",
            "symptomResolved": None, "performanceWithinTarget": None,
            "runID": run_id, "problems": problems, "warnings": warnings,
            "sourceCommit": metadata.get("sourceCommit"),
            "binary": {key: manifest.get(key) for key in ("bundleIdentifier", "gameVersion", "build", "executableSHA256")},
            "backgroundMusicBackend": report.get("backgroundMusicBackend"),
            "frameAttributionEnabled": attribution_enabled,
            "gcRuntime": runtime, "nativeGCSuspendPolicy": gc_policy,
            "provenanceLimit": "Source/binary linkage is declared by launch metadata; device report has no binary attestation",
            "clock": {"toleranceSeconds": clock_tolerance_seconds, "originMinusLaunchRequestedMs": delta,
                      "deviceOriginUtcMillis": origin, "collectedUtcMillis": collected_utc_millis},
            "timedEvidence": report.get("timedEvidence"), "completionReason": reason,
            "requestedSeconds": seconds, "sampledWindowSeconds": window_seconds,
            "sampledFrames": len(samples), "nullIntervalFrames": len(valid_current) - len(valid_intervals),
            "unobservedPauseResumeGaps": gaps, "sameSceneSeconds": observed_same_phase,
            "sameSceneCoverage": coverage, "expectedPhase": expected_phase,
            "minimumPhaseCoverage": minimum_phase_coverage, "minimumPhaseSeconds": minimum_phase_seconds,
            "phaseStats": phases, "longIntervals": slow,
            "allocationUnits": "Process-wide allocation counters, not thread-local; render and interval totals overlap",
            "gcInterpretation": "GC overlap is correlation only; it does not establish audio callback starvation"}


def private_output(path):
    path = path.resolve()
    if path == ROOT or ROOT in path.parents:
        result = subprocess.run(["git", "check-ignore", "--quiet", "--", str(path)], cwd=ROOT,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise ValueError("Repository evidence output must be Git-ignored (use ports/ios/validation/<run-id>)")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--device", help="Device ID used only for one final file copy; never written to the audit")
    target.add_argument("--simulator", help="Simulator ID used only for one final app-container query; never written to the audit")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--launch-metadata", type=Path, help="Required for live copy; defaults to saved launch metadata in audit-only mode")
    parser.add_argument("--output", required=True, type=Path, help="Local private evidence directory")
    parser.add_argument("--audit-only", action="store_true", help="Audit a saved report without contacting a device or waiting")
    parser.add_argument("--control", choices=CONTROLS, default="normal",
                        help="Explicit diagnostic control; normal remains the default and rejects enabled control flags")
    parser.add_argument("--evidence", type=Path, help="Alternate saved report (audit-only mode)")
    parser.add_argument("--expected-phase", choices=[*PHASE_NAMES.values(), "any"], default="menu")
    parser.add_argument("--minimum-phase-coverage", type=float, default=.8)
    parser.add_argument("--minimum-phase-seconds", type=float, default=0)
    parser.add_argument("--clock-tolerance-seconds", type=float, default=2,
                        help="Explicit Mac/device UTC skew tolerance; always reported in the audit")
    parser.add_argument("--grace-seconds", type=float, default=30,
                        help="One local wait through launch completion + duration + this startup/write grace")
    args = parser.parse_args()
    if not RUN_ID.fullmatch(args.run_id):
        parser.error("Use a fresh run ID with 1..64 ASCII letters/digits/underscores/hyphens")
    if not args.audit_only and (not (args.device or args.simulator) or not args.launch_metadata):
        parser.error("Live collection requires exactly one of --device/--simulator and --launch-metadata")
    if args.evidence and not args.audit_only:
        parser.error("--evidence is only valid with --audit-only")
    if any(not math.isfinite(value) or value < 0 for value in
           (args.minimum_phase_coverage, args.minimum_phase_seconds, args.clock_tolerance_seconds, args.grace_seconds)) \
            or args.minimum_phase_coverage > 1 or args.clock_tolerance_seconds > 60:
        parser.error("Use finite nonnegative thresholds, coverage <= 1 and clock tolerance <= 60 seconds")
    try:
        output = private_output(args.output)
        metadata_path = (args.launch_metadata or output / "launch-metadata.json").resolve()
        if not args.audit_only and metadata_path == (output / "launch-metadata.json").resolve():
            raise ValueError("Live launch metadata must be outside the saved launch-metadata.json destination; input will not be overwritten")
        metadata = load_json(metadata_path)
        manifest_reference = metadata.get("binaryManifest")
        if not isinstance(manifest_reference, str) or not manifest_reference:
            raise ValueError("Launch metadata requires a binaryManifest path")
        manifest_path = (metadata_path.parent / manifest_reference).resolve()
        manifest = load_json(manifest_path)
        initial_problems = launch_problems(metadata, args.run_id, manifest, control=args.control)
        if initial_problems:
            raise ValueError("; ".join(initial_problems))
        manifest_hash = digest(manifest_path)
        if metadata.get("binaryManifestSHA256") not in (None, manifest_hash):
            raise ValueError("Binary manifest bytes differ from launch metadata")
        evidence_path = (args.evidence or output / "ios-scene-evidence.json").resolve()
        output.mkdir(parents=True, exist_ok=True)
        collected = None
        collection_platform = "unknown"
        if not args.audit_only:
            collection_platform = "simulator" if args.simulator else "device"
            if evidence_path.exists() or (output / "collection-metadata.json").exists():
                raise ValueError("Refusing to overwrite a previous capture; use a fresh output directory")
            ready = metadata["launchCompletedUtcMillis"] / 1000
            if metadata["environment"].get(START_PHASE_ENV) is not None:
                ready = metadata["captureReadyUtcMillis"] / 1000 + START_PHASE_WARMUP_SECONDS + 1
            wait_until = ready + metadata["evidenceSeconds"] + args.grace_seconds
            print("Waiting locally for the declared frame window; no device polling", flush=True)
            while True:
                remaining = wait_until - time.time()
                if remaining <= 0:
                    break
                time.sleep(min(30, remaining))
            original_metadata_hash = digest(metadata_path)
            local_metadata = dict(metadata)
            local_metadata["binaryManifest"] = "binary-manifest.json"
            local_metadata["binaryManifestSHA256"] = manifest_hash
            if isinstance(metadata.get("deviceLaunchResult"), str):
                local_metadata["deviceLaunchResult"] = str((metadata_path.parent / metadata["deviceLaunchResult"]).resolve())
            (output / "launch-metadata.json").write_text(json.dumps(local_metadata, indent=2) + "\n")
            (output / "binary-manifest.json").write_bytes(manifest_path.read_bytes())
            with (output / "copy-scene-evidence.log").open("w") as log:
                if args.simulator:
                    query = subprocess.run(["xcrun", "simctl", "get_app_container", args.simulator, BUNDLE, "data"],
                                           stdout=subprocess.PIPE, stderr=log, text=True, check=True, timeout=25)
                    container = Path(query.stdout.strip())
                    if not container.is_absolute() or not container.is_dir():
                        raise ValueError("Simulator app data container path is missing or invalid")
                    shutil.copyfile(container / "Documents/ios-scene-evidence.json", evidence_path)
                    log.write("Copied the completed simulator frame report once after the local wait\n")
                else:
                    subprocess.run(["xcrun", "devicectl", "--timeout", "25", "device", "copy", "from",
                                    "--device", args.device, "--domain-type", "appDataContainer",
                                    "--domain-identifier", BUNDLE, "--source", "Documents/ios-scene-evidence.json",
                                    "--destination", str(evidence_path)], stdout=log, stderr=subprocess.STDOUT, check=True)
            collected = time.time_ns() // 1_000_000
            provenance = {"collectedUtcMillis": collected,
                          "collectedUTC": datetime.fromtimestamp(collected / 1000, timezone.utc).isoformat(),
                          "launchMetadataSource": str(metadata_path), "originalLaunchMetadataSHA256": original_metadata_hash,
                          "savedLaunchMetadataSHA256": digest(output / "launch-metadata.json"),
                          "binaryManifestSource": str(manifest_path), "binaryManifestSHA256": manifest_hash,
                          "evidenceSHA256": digest(evidence_path), "platform": collection_platform,
                          "deviceCopyCount": 0 if args.simulator else 1,
                          "simulatorContainerQueryCount": 1 if args.simulator else 0,
                          "localFileCopyCount": 1 if args.simulator else 0,
                          "appLaunchCount": 0, "appInstallCount": 0}
            (output / "collection-metadata.json").write_text(json.dumps(provenance, indent=2) + "\n")
        elif (output / "collection-metadata.json").exists():
            provenance = load_json(output / "collection-metadata.json")
            collected = provenance.get("collectedUtcMillis")
            collection_platform = provenance.get("platform", "device" if provenance.get("deviceCopyCount") == 1 else "unknown")
            if provenance.get("evidenceSHA256") != digest(evidence_path):
                raise ValueError("Saved evidence bytes differ from their collection checksum")
            if provenance.get("binaryManifestSHA256") != manifest_hash:
                raise ValueError("Saved manifest bytes differ from their collection checksum")
            if digest(metadata_path) not in (provenance.get("originalLaunchMetadataSHA256"),
                                             provenance.get("savedLaunchMetadataSHA256")):
                raise ValueError("Saved launch metadata bytes differ from their collection checksum")
        report = load_json(evidence_path)
        result = audit_evidence(report, metadata, args.run_id, manifest,
                                control=args.control,
                                expected_phase=args.expected_phase,
                                minimum_phase_coverage=args.minimum_phase_coverage,
                                minimum_phase_seconds=args.minimum_phase_seconds,
                                clock_tolerance_seconds=args.clock_tolerance_seconds,
                                collected_utc_millis=collected)
        result["evidencePath"] = str(evidence_path)
        result["evidenceSHA256"] = digest(evidence_path)
        result["launchMetadataPath"] = str(metadata_path)
        result["binaryManifestPath"] = str(manifest_path)
        result["collectionPlatform"] = collection_platform
        result["platformLimit"] = ("Simulator evidence is not equivalent to physical iPad performance or audio validation"
                                   if collection_platform == "simulator"
                                   else "Collection platform does not establish audible quality or runtime binary attestation")
        if collected is None:
            result["warnings"].append("No independent collection UTC/checksum was provided; freshness is bound to launch metadata only")
        (output / "scene-audit.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
        print(json.dumps({key: result[key] for key in
                          ("kind", "control", "collectionPlatform", "platformLimit", "evidenceAccepted", "problems", "warnings", "sampledFrames", "sameSceneCoverage", "audioQuality")}), flush=True)
        return 0 if result["evidenceAccepted"] else 1
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        # Process failures include their command line, which contains a target ID.
        print("Scene evidence copy failed; inspect the private local copy log", file=sys.stderr)
        return 1
    except (ValueError, TypeError, AttributeError, OSError) as failure:
        print("Scene evidence collection/audit failed: " + str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
