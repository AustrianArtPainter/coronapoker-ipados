"""Assess fresh native status updates against an independent monotonic observer."""
import math


def _number(value, *, integer=False):
    return (isinstance(value, int) if integer else isinstance(value, (int, float))) \
            and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def assess_status_freshness(observations, *, run_id, expected_version,
                            max_stale_seconds=6.0,
                            max_clock_drift_seconds=6.0,
                            maximum_tick_gap_seconds=5.0):
    """Allow a fixed host/device clock offset, never a cached status window.

    The normal native writer sleeps for one second; observations are typically
    five seconds apart. Six seconds allows a boundary sample, ordinary ticker
    jitter and a five-second TCP response, without accepting sustained stale
    replies. UTC advancement and tick advancement must describe the same
    observation window. No absolute host/device clock comparison is made.
    """
    problems = []
    samples = []
    for observation in observations:
        status = observation.get("status")
        elapsed = observation.get("elapsedSeconds")
        if ("error" in observation or "readError" in observation
                or not isinstance(status, dict) or not _number(elapsed)):
            problems.append("Missing or failed independent status observation")
            continue
        if (status.get("kind") != "native-java-game"
                or status.get("runID") != run_id
                or status.get("gameVersion") != expected_version
                or status.get("error") != ""):
            problems.append("Native status belongs to another run/version or reports an error")
        if (not _number(status.get("ticks"), integer=True)
                or not _number(status.get("utcMillis"), integer=True)
                or not _number(status.get("maximumTickGapSeconds"))):
            problems.append("Native freshness counters are missing or invalid")
            continue
        if status["maximumTickGapSeconds"] > maximum_tick_gap_seconds:
            problems.append("Native timer paused beyond the allowed tick gap")
        samples.append((float(elapsed), status))

    evidence = {"observedWindowSeconds": 0.0, "nativeClockAdvanceSeconds": 0.0,
                "nativeTickAdvance": 0, "minimumExpectedTickAdvance": 0,
                "maximumRelativeClockDriftSeconds": 0.0,
                "maximumUnchangedStatusSeconds": 0.0}
    if len(samples) < 2:
        problems.append("At least two fresh native status samples are required")
    else:
        first_elapsed, first = samples[0]
        last_progress_elapsed = first_elapsed
        maximum_drift = 0.0
        maximum_unchanged = 0.0
        for index, (elapsed, status) in enumerate(samples):
            observed_delta = elapsed - first_elapsed
            native_delta = (status["utcMillis"] - first["utcMillis"]) / 1000.0
            drift = abs(native_delta - observed_delta)
            maximum_drift = max(maximum_drift, drift)
            if drift > max_clock_drift_seconds:
                problems.append("Native UTC updates do not cover the independent observation window")
            if index == 0:
                continue
            previous_elapsed, previous = samples[index - 1]
            if elapsed <= previous_elapsed:
                problems.append("Independent observation times are not strictly increasing")
            tick_delta = status["ticks"] - previous["ticks"]
            utc_delta = status["utcMillis"] - previous["utcMillis"]
            if tick_delta < 0 or utc_delta < 0:
                problems.append("Native ticks or UTC went backwards")
            elif tick_delta == 0 and utc_delta == 0:
                unchanged = elapsed - last_progress_elapsed
                maximum_unchanged = max(maximum_unchanged, unchanged)
                if unchanged > max_stale_seconds:
                    problems.append("The status listener returned a cached native update for too long")
            elif tick_delta == 0 or utc_delta == 0:
                problems.append("Native ticks and UTC did not advance together")
            else:
                last_progress_elapsed = elapsed

        last_elapsed, last = samples[-1]
        observed_window = last_elapsed - first_elapsed
        tick_advance = last["ticks"] - first["ticks"]
        # Use the writer's measured maximum interval, with its one-second sleep
        # as the floor. This retains slow-but-live samples within the existing
        # five-second limit while rejecting a handful of ticks over 15 minutes.
        measured_gap = max(1.0, max(status["maximumTickGapSeconds"] for _, status in samples))
        minimum_ticks = max(0, math.floor(
                max(0.0, observed_window - max_clock_drift_seconds) / measured_gap) - 1)
        if tick_advance < minimum_ticks:
            problems.append("Native tick advancement does not cover the independent observation window")
        evidence = {"observedWindowSeconds": observed_window,
                    "nativeClockAdvanceSeconds": (last["utcMillis"] - first["utcMillis"]) / 1000.0,
                    "nativeTickAdvance": tick_advance,
                    "minimumExpectedTickAdvance": minimum_ticks,
                    "maximumRelativeClockDriftSeconds": maximum_drift,
                    "maximumUnchangedStatusSeconds": maximum_unchanged}
    problems = list(dict.fromkeys(problems))
    return {"passed": not problems, "problems": problems, **evidence}
