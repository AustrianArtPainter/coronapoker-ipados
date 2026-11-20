#!/usr/bin/env python3
"""Check native sampler integrity with deterministic clocks and minimal runtime stubs."""
import json
import os
import subprocess
from pathlib import Path
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
OUTPUT = PORT / "build/frame-evidence-tests"
SOURCE = PORT / "shared-adapters/src/com/tonikelope/coronapoker/gdx/IOSFrameEvidence.java"
STUBS = {
    "org/robovm/rt/GC.java": '''package org.robovm.rt;
public final class GC {
 public static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
 public static final java.util.concurrent.atomic.AtomicLong collections=new java.util.concurrent.atomic.AtomicLong();
 public static long getCount(){calls.incrementAndGet();return collections.get();}
 public static long getTotalBytes(){calls.incrementAndGet();return 0;}
 public static long getHeapSize(){calls.incrementAndGet();return 4096;}
 public static long getFreeBytes(){calls.incrementAndGet();return 2048;}
}''',
    "com/tonikelope/coronapoker/ios/IOSDiagnostics.java": '''package com.tonikelope.coronapoker.ios;
public final class IOSDiagnostics {
 public static final java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
 public static final java.util.concurrent.atomic.AtomicInteger reports=new java.util.concurrent.atomic.AtomicInteger();
 public static volatile String text,writerThread;
 public static void report(String name,String value){text=value;writerThread=Thread.currentThread().getName();reports.incrementAndGet();ready.countDown();}
}''',
    "com/tonikelope/coronapoker/ios/IOSPerformance.java": '''package com.tonikelope.coronapoker.ios;
public final class IOSPerformance {public static final boolean ENABLED="1".equals(System.getenv("CP_IOS_PROFILE"));}''',
    "com/tonikelope/coronapoker/ios/IOSMusic.java": '''package com.tonikelope.coronapoker.ios;
public final class IOSMusic {public static String backgroundBackendName(){return "legacy";}}''',
    "com/tonikelope/coronapoker/ios/IOSGCRuntime.java": '''package com.tonikelope.coronapoker.ios;
public final class IOSGCRuntime {public static String snapshotJson(){return "{\\\"policy\\\":\\\"sdk-original\\\",\\\"taskThreadDiscovery\\\":-1,\\\"markerHelpers\\\":9,\\\"version\\\":460032}";}}''',
    "com/tonikelope/coronapoker/ios/ProfiledSound.java": '''package com.tonikelope.coronapoker.ios;
public final class ProfiledSound {public static long throttledStarts(){return 0;}public static long failedStarts(){return 0;}}''',
    "com/tonikelope/coronapoker/gdx/GifFramePreparation.java": '''package com.tonikelope.coronapoker.gdx;
public final class GifFramePreparation {public static final java.util.concurrent.atomic.AtomicLong LIVE_BYTES=new java.util.concurrent.atomic.AtomicLong(),PEAK_BYTES=new java.util.concurrent.atomic.AtomicLong();}''',
    "com/tonikelope/coronapoker/gdx/IOSFittedTextCache.java": '''package com.tonikelope.coronapoker.gdx;
public final class IOSFittedTextCache {public static long builds,hits;}''',
}


def run(mode, scopes=False, audio_clock=False):
    environment = {k: v for k, v in os.environ.items() if not k.startswith("CP_IOS_")}
    environment.update(CP_IOS_RUN_ID="frame-integrity-" + mode, CP_IOS_PROFILE="0", CP_IOS_DIAGNOSTICS="0")
    if mode != "disabled":
        environment["CP_IOS_SCENE_EVIDENCE"] = "1"
    if mode != "legacy":
        # The disabled case proves an invalid unused setting cannot awaken native work.
        environment["CP_IOS_EVIDENCE_SECONDS"] = "bad-unused-value" if mode == "disabled" else "300"
    if mode == "disabled":
        environment["CP_IOS_EVIDENCE_START_PHASE"] = "bad-unused-phase"
    if scopes:
        environment["CP_IOS_SCENE_SCOPES"] = "1"
    if audio_clock:
        environment["CP_IOS_AUDIO_CLOCK_SECONDS"] = "12"
    if mode.startswith("deferred-"):
        environment["CP_IOS_EVIDENCE_START_PHASE"] = "menu" if mode == "deferred-resume" else mode.removeprefix("deferred-")
    result = subprocess.run([str(JAVA / "java"), "-cp", str(OUTPUT / "classes"),
                             "com.tonikelope.coronapoker.gdx." + ("ProjectionDeliveryProbe" if mode == "projection" else "FrameEvidenceProbe"), mode],
                            env=environment, text=True, capture_output=True, check=True)
    if result.stderr:
        print(result.stderr.strip())
    report = json.loads(result.stdout)
    (OUTPUT / (mode + ("-scopes" if scopes else "") + ("-clock" if audio_clock else "") + ".json")).write_text(json.dumps(report, indent=2) + "\n")
    return report


def raw_checks(report, timed, expected_origin=1_000_000_000, expected_clock=None):
    assert report["kind"] == "retained-scene-v5"
    assert report["timedEvidence"] is timed
    assert report["liveReporter"] is False and report["screenshotDiagnostics"] is False
    assert report["diagnosticConfig"]["voiceProbe"] is None
    assert report["diagnosticConfig"]["scrollTest"] is None
    assert report["diagnosticConfig"]["audioClockSeconds"] == expected_clock
    delivery = report["projectionDelivery"]
    assert delivery["installed"] is False and delivery["stored"] == 0
    assert delivery["primitiveStorageBytes"] == 0 and delivery["storageAllocatedBeforeArm"] is None
    assert delivery["dropped"] == delivery["observerFailures"] == 0
    assert report["originUtcMillis"] > 0 and report["originMonotonicNanos"] == expected_origin
    expected_capacity = report["evidenceSeconds"] * 120 + 600 if timed else 120_000
    assert report["sampleCapacity"] == expected_capacity
    assert report["sampleStoragePrimitiveBytes"] == expected_capacity * 83
    assert report["samplingEndedElapsedNanos"] >= report["evidenceSeconds"] * 1_000_000_000
    samples = report["samples"]
    assert report["sampledFrames"] == len(samples)
    for index, row in enumerate(samples):
        assert row["currentRenderNs"] == 2_000_000
        assert row["currentRenderProcessAllocatedBytes"] == 120
        assert row["currentRenderGCCount"] == 0
        if index:
            assert row["intervalEndElapsedNanos"] > samples[index - 1]["intervalEndElapsedNanos"]
        if row["flags"] & 1:
            for field in ["intervalNs", "previousRenderNs", "intervalProcessAllocatedBytes",
                          "previousRenderProcessAllocatedBytes", "intervalGCCount"]:
                assert row[field] is None
            assert row["previousPhase"] == -1 and row["previousSequence"] == -1
        else:
            assert row["intervalNs"] > 0 and row["previousRenderNs"] == 2_000_000
            assert row["previousRenderProcessAllocatedBytes"] == 120
            if timed and index:
                assert row["intervalNs"] == row["intervalEndElapsedNanos"] - samples[index - 1]["intervalEndElapsedNanos"]
                assert row["previousPhase"] == samples[index - 1]["currentPhase"]
        assert bool(row["flags"] & 2) == (row["previousPhase"] != -1 and row["previousPhase"] != row["currentPhase"])
    for phase in report["phases"]:
        expected = [r for r in samples if r["currentPhase"] == phase["phase"]
                    and r["previousPhase"] == phase["phase"] and not r["flags"] & (1 | 4)]
        assert phase["frames"] == len(expected)
        assert phase["sampledFrames"] == sum(r["currentPhase"] == phase["phase"] for r in samples)
        assert phase["allocatedBytes"] == sum(r["intervalProcessAllocatedBytes"] for r in expected)


OUTPUT.mkdir(parents=True, exist_ok=True)
sources = []
for relative, content in STUBS.items():
    path = OUTPUT / "stubs" / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    sources.append(path)
JAVA = jdk_home() / "bin"
classes = OUTPUT / "classes"
classes.mkdir(exist_ok=True)
subprocess.run([str(JAVA / "javac"), "--release", "17", "-d", str(classes),
                *map(str, sources), str(SOURCE),
                str(PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/IOSFrameAttribution.java"),
                str(PORT / "shared-adapters/src/com/tonikelope/coronapoker/ios/BackgroundProjection.java"),
                str(PORT / "tools/FrameEvidenceProbe.java"), str(PORT / "tools/ProjectionDeliveryProbe.java")], check=True)
menu = run("menu")
raw_checks(menu, True)
assert menu["completionReason"] == "duration" and len(menu["samples"]) == 4
assert menu["samples"][0]["intervalEndElapsedNanos"] == 0 and menu["samples"][0]["flags"] == 13
assert all(r["currentPhase"] == 3 for r in menu["samples"])
assert menu["samples"][1]["intervalProcessAllocatedBytes"] == 200
assert menu["samples"][2]["intervalGCCount"] == 1
for target in ("menu", "table", "summary"):
    deferred = run("deferred-" + target)
    raw_checks(deferred, True, expected_origin=8_000_000_000)
    assert deferred["evidenceStartPhase"] == target
    assert deferred["firstForegroundMonotonicNanos"] == 1_000_000_000
    assert deferred["startPhaseStableNanos"] == 5_000_000_000
    assert len(deferred["samples"]) == 3
    assert deferred["samples"][0]["intervalEndElapsedNanos"] == 0
    assert all(row["currentPhase"] == {"menu": 3, "table": 1, "summary": 2}[target] for row in deferred["samples"])
    assert deferred["samples"][1]["intervalProcessAllocatedBytes"] == 200
    assert deferred["samples"][1]["intervalGCCount"] == 0
deferred_resume = run("deferred-resume")
raw_checks(deferred_resume, True, expected_origin=11_000_000_000)
assert len(deferred_resume["samples"]) == 3
assert deferred_resume["startPhaseStableNanos"] == 5_000_000_000
assert deferred_resume["samples"][0]["flags"] == 13
assert deferred_resume["suspendCount"] == 0  # Pre-capture gaps are not sampled gaps.
flow = run("flow")
raw_checks(flow, True)
assert flow["suspendCount"] == 1 and len(flow["samples"]) == 9
assert flow["samples"][1]["previousPhase"] == 4 and flow["samples"][1]["currentPhase"] == 3
assert flow["samples"][3]["flags"] & (1 | 16) == (1 | 16)
assert flow["samples"][4]["intervalNs"] == 16_000_000
assert flow["samples"][6]["previousSequence"] == 101
assert flow["completionReason"] == "duration"
scopes = run("flow", scopes=True)
raw_checks(scopes, True)
assert scopes["allocationScopesEnabled"] is True
menu_scope = next(r for r in scopes["allocationScopes"] if r["phase"] == 3 and r["name"] == "alloc.applicationFrame")
assert menu_scope["calls"] == 4 and menu_scope["bytes"] == 148
legacy = run("legacy")
raw_checks(legacy, False)
assert legacy["completionReason"] == "summary" and len(legacy["samples"]) == 3
assert [p["name"] for p in legacy["phases"]] == ["table", "summary"]
assert all(r["currentPhase"] in (1, 2) and not r["flags"] & (1 | 2 | 4) for r in legacy["samples"])
assert run("disabled")["disabledChecks"] >= 16
clock = run("menu", audio_clock=True)
raw_checks(clock, True, expected_clock="12")
projection = run("projection")
delivery = projection["projectionDelivery"]
assert delivery["kind"] == "foreground-projection-delivery-v1" and delivery["installed"] is True
assert delivery["storageAllocatedBeforeArm"] is True
assert delivery["capacity"] == delivery["stored"] == 8192
assert delivery["primitiveStorageBytes"] == 8192 * 82
assert delivery["dropped"] == 6 and delivery["observerFailures"] == 0
assert delivery["statusMap"] == {"presented": 0, "skipped": 1, "presentThrew": 2}
assert delivery["columns"] == ["sequence", "taskStartElapsedNanos", "lockRequestElapsedNanos",
    "lockAcquiredElapsedNanos", "presentStartElapsedNanos", "endElapsedNanos", "gcAtTaskStart",
    "gcAtLockAcquired", "gcBeforePresent", "gcAtEnd", "status", "lastMarkedPhase"]
rows = delivery["samples"]
assert len(rows) == 8192 and [r[0] for r in rows[:6]] == [1, 2, 3, 4, 5, 6]
assert [r[10] for r in rows[:6]] == [0, 0, 2, 1, 1, 1]
assert rows[0][3] - rows[0][2] >= 10_000_000 and rows[0][5] - rows[0][4] >= 5_000_000
assert rows[0][6:10] == [0, 1, 1, 2]
assert rows[-1][0] == 10000 + 8192 - 7  # Keep earliest rows, never overwrite on overflow.
for row in rows:
    assert len(row) == 12 and row[11] == 1
    assert 0 <= row[1] <= row[2] <= row[3] <= row[5] <= projection["samplingEndedElapsedNanos"]
    assert row[6] <= row[7] <= row[9]
    if row[10] == 1:
        assert row[4] is None and row[8] is None
    else:
        assert row[3] <= row[4] <= row[5] and row[7] <= row[8] <= row[9]
generator = (PORT / "tools/prepare_game_sources.py").read_text()
assert generator.count("IOSFrameEvidence.scene(startupIntro != null ? 4 : table == null ? 5 : 0, -1);") == 1
assert generator.count("IOSFrameEvidence.scene(surface == Surface.MENU && !hasVisibleFrontendModal() ? 3 : 5, -1);") == 1
assert "@Override public void pause() { GdxTableRenderer.suspendAll(); }" in generator
assert "Gdx.app.postRunnable(GdxTableRenderer::resumeAll);" in generator
print("PASS raw timeline/phase/GC/allocation integrity; menu timed stop; transition warmup; pause/resume; scopes; legacy window; disabled native fast path")
print("PASS bounded production projection delivery timeline; lock/present GC observations; delayed visual/error/lifecycle semantics; observer fault isolation; audio clock metadata")
