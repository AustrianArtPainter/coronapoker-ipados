#!/usr/bin/env python3
"""Offline exact-process tap/reference comparison; never records or controls Apps.

Requires an existing Python with NumPy (no dependency installation). Example:
  python-with-numpy tools/analyze_app_audio.py --tap-dir TAP --reference SONG.mp3 \
      --output NEW_DIRECTORY --self-test

MP3 decoding uses the local afconvert codec service; a predecoded float32 WAV
at the tap rate/channels can instead be passed as --reference. --source-mp3
records its original asset hash when using a predecoded reference. Output
events are candidates, not proof of audibility, absence of glitches, or GC
causation. Candidate timing uses SOURCE host timestamps, not callback arrival.
"""
import argparse
import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time

try:
    import numpy as np
except ImportError:
    raise SystemExit("Use an existing Python with NumPy; this tool never installs dependencies.")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def utc(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat()


def reject_constant(value):
    raise ValueError("Non-finite JSON constant: " + value)


def finite_json(value):
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {key: finite_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [finite_json(item) for item in value]
    return value


def validate_transport(directory):
    """Recompute the producer's transport contract instead of trusting its boolean."""
    metadata = json.loads((directory / "metadata.json").read_text(), parse_constant=reject_constant)
    with (directory / "chunks.tsv").open(newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        rate_scalar_column = "sourceRateScalar" in (reader.fieldnames or ())
        rows = list(reader)
    rate_scalar_stats = {"sourceRateScalarColumnPresent": rate_scalar_column,
                         "sourceRateScalarValidFlagChunks": 0, "sourceRateScalarObservedValidChunks": 0,
                         "sourceRateScalarMin": None, "sourceRateScalarMax": None}
    errors, warnings = [], []
    def need(condition, message):
        if not condition:
            errors.append(message)
    need(metadata.get("schema") == 1, "Unsupported capture schema")
    fmt = metadata.get("format", {})
    rate, channels = float(fmt.get("sampleRate", 0)), int(fmt.get("channels", 0))
    need(fmt.get("encoding") == "float32le" and fmt.get("interleaved") is True,
         "Only explicitly interleaved float32le output is supported")
    need(math.isfinite(rate) and 8000 <= rate <= 192000 and rate.is_integer(), "Invalid sample rate")
    need(channels in (1, 2), "Only mono/stereo capture is supported")
    need(metadata.get("completed") is True and not metadata.get("error"), "Capture did not complete cleanly")
    wall = metadata.get("startUnixSeconds")
    need(isinstance(wall, (int, float)) and not isinstance(wall, bool)
         and math.isfinite(wall) and 0 < wall < 253402300799, "Missing/invalid finite UTC anchor")
    need(metadata.get("privateTap") is True and metadata.get("microphone") is False
         and metadata.get("exclusive") is False and metadata.get("muteBehavior") == "unmuted"
         and metadata.get("scope") == "exactly one simulator App process"
         and len(metadata.get("audioObjectIDs", [])) == 1, "Exact-App unmuted/private scope is unconfirmed")
    for key in ("droppedCallbacks", "droppedFrames", "invalidCallbacks", "missingHostTimeChunks", "nonFiniteSamples"):
        need(metadata.get(key) == 0, key + " must be zero")
    base = metadata.get("machTimebase", {})
    numer, denom = int(base.get("numer", 0)), int(base.get("denom", 0))
    need(numer > 0 and denom > 0 and base.get("clock") == "mach_absolute_time", "Invalid Mach timebase")
    if errors:
        return {"accepted": False, "errors": errors, "warnings": warnings, **rate_scalar_stats}, metadata, rows, None
    tick = numer / denom / 1e9
    pcm_path = directory / "output.f32le"
    frame_bytes = channels * 4
    need(pcm_path.stat().st_size % frame_bytes == 0, "PCM byte length is not whole frames")
    frame_count = pcm_path.stat().st_size // frame_bytes
    need(frame_count > 0, "Empty PCM")
    if not frame_count:
        return {"accepted": False, "errors": errors, "warnings": warnings, **rate_scalar_stats}, metadata, rows, None
    offset, frames, max_gap, max_sample_gap, previous, sequence_gaps = 0, 0, 0.0, 0.0, None, 0
    parsed = []
    try:
        for row in rows:
            row = {key: (float(value) if key in ("sourceSampleTime", "sourceRateScalar") else int(value)) for key, value in row.items()}
            need(row["byteOffset"] == offset, "Non-contiguous PCM byte offset")
            need(0 < row["frames"] <= int(metadata["maximumFramesPerCallback"]), "Invalid chunk frame count")
            need(row["sourceTimeFlags"] & 2 != 0 and row["sourceHostTime"] > 0, "Missing source host time")
            need(not row["sourceTimeFlags"] & 1 or math.isfinite(row["sourceSampleTime"]), "Non-finite valid source sample time")
            if row["sourceTimeFlags"] & 4:
                rate_scalar_stats["sourceRateScalarValidFlagChunks"] += 1
                # Legacy captures omit this field even when HAL marked it valid.
                # Preserve that unknown state; never substitute nominal 1.0.
                if rate_scalar_column:
                    scalar = row["sourceRateScalar"]
                    valid_scalar = math.isfinite(scalar) and scalar > 0
                    need(valid_scalar, "Invalid valid source rate scalar")
                    if valid_scalar:
                        rate_scalar_stats["sourceRateScalarObservedValidChunks"] += 1
                        low, high = rate_scalar_stats["sourceRateScalarMin"], rate_scalar_stats["sourceRateScalarMax"]
                        rate_scalar_stats["sourceRateScalarMin"] = scalar if low is None else min(low, scalar)
                        rate_scalar_stats["sourceRateScalarMax"] = scalar if high is None else max(high, scalar)
            need(row["callbackMachTime"] > 0, "Missing callback arrival time")
            if previous is not None:
                need(row["sequence"] > previous["sequence"], "Non-monotonic sequence")
                sequence_gaps += max(0, row["sequence"] - previous["sequence"] - 1)
                need(row["sourceHostTime"] > previous["sourceHostTime"], "Non-monotonic source host time")
                need(row["callbackMachTime"] > previous["callbackMachTime"], "Non-monotonic callback arrival time")
                max_gap = max(max_gap, abs((row["sourceHostTime"] - previous["sourceHostTime"]) * tick * rate - previous["frames"]))
                if row["sourceTimeFlags"] & previous["sourceTimeFlags"] & 1:
                    max_sample_gap = max(max_sample_gap, abs(row["sourceSampleTime"] - previous["sourceSampleTime"] - previous["frames"]))
                else:
                    warnings.append("Some source sample-time flags are absent; only host mapping is usable")
            offset += row["frames"] * frame_bytes
            frames += row["frames"]
            parsed.append(row)
            previous = row
    except (KeyError, ValueError, TypeError) as failure:
        errors.append("Malformed chunk row: " + str(failure))
    need(len(parsed) > 1, "Too few chunks")
    need(offset == pcm_path.stat().st_size == metadata.get("writtenBytes"), "PCM bytes differ from chunks/metadata")
    need(frames == frame_count == metadata.get("writtenFrames"), "PCM frames differ from chunks/metadata")
    need(len(rows) == metadata.get("writtenChunks"), "Chunk count differs from metadata")
    need(max_gap < 2 and max_sample_gap < 2, "Source timestamp continuity failed")
    need(abs(max_gap - float(metadata.get("maximumTimestampGapFrames", float("inf")))) < .01,
         "Timestamp gap statistic differs from metadata")
    need(metadata.get("callbackCount", -1) >= len(rows) + metadata.get("droppedCallbacks", 0)
         + metadata.get("invalidCallbacks", 0), "Callback counters conflict")
    if sequence_gaps:
        warnings.append("Sequence gaps may be empty callbacks; source continuity was checked separately")
    pcm = np.memmap(pcm_path, dtype="<f4", mode="r", shape=(frame_count, channels))
    finite, nonzero, peak = True, 0, 0.0
    for start in range(0, frame_count, 262144):
        block = pcm[start:start + 262144]
        finite &= bool(np.isfinite(block).all())
        nonzero += int(np.count_nonzero(block))
        peak = max(peak, float(np.max(np.abs(block), initial=0)))
    need(finite, "Non-finite PCM")
    need(nonzero == metadata.get("nonZeroSamples") and nonzero > 0, "Signal count differs or is empty")
    need(math.isclose(peak, float(metadata.get("peakAbsoluteSample", -1)), rel_tol=1e-6, abs_tol=1e-8), "Peak differs from metadata")
    covered = first_gap = end_gap = float("nan")
    if parsed:
        first, last = parsed[0], parsed[-1]
        covered = (last["sourceHostTime"] - first["sourceHostTime"]) * tick + last["frames"] / rate
        first_gap = (first["sourceHostTime"] - int(metadata["startMachTime"])) * tick
        end_gap = (int(metadata["endMachTime"]) - last["sourceHostTime"]) * tick - last["frames"] / rate
        need(first["sourceHostTime"] == metadata.get("firstSourceHostTime")
             and last["sourceHostTime"] == metadata.get("lastSourceHostTime"), "Edge timestamps conflict")
        requested = float(metadata["requestedSeconds"])
        need(math.isfinite(requested) and requested > 0, "Invalid requested duration")
        need(int(metadata["endMachTime"]) > int(metadata["startMachTime"]), "Invalid capture clock interval")
        need(frames / rate >= requested - .25 and covered >= requested - .25
             and abs(first_gap) <= .25 and abs(end_gap) <= .25
             and abs(frames / rate - covered) <= .05, "Requested coverage contract failed")
        for key, calculated in [("writtenFrameSeconds", frames / rate), ("sourceCoveredSeconds", covered),
                                ("firstSourceMinusCaptureStartSeconds", first_gap),
                                ("captureStopMinusLastSourceEndSeconds", end_gap)]:
            need(math.isclose(float(metadata.get(key, float("inf"))), calculated, abs_tol=1e-6), key + " conflicts with raw timestamps")
    need(metadata.get("coverageValid") is True and metadata.get("validEvidence") is True, "Producer did not accept evidence")
    result = {"accepted": not errors, "errors": sorted(set(errors)), "warnings": sorted(set(warnings)),
              "frames": frames, "chunks": len(parsed), "sampleRate": rate, "channels": channels,
              "pcmSeconds": frames / rate, "sourceCoveredSeconds": covered,
              "firstSourceMinusCaptureStartSeconds": first_gap, "captureStopMinusLastSourceEndSeconds": end_gap,
              "maximumTimestampGapFrames": max_gap, "maximumSampleTimeGapFrames": max_sample_gap,
              "peak": peak, "nonZeroSamples": nonzero,
              "clockMapping": "approximate single-point wall anchor + sourceHostTime; wall-clock uncertainty unmeasured",
              **rate_scalar_stats}
    return result, metadata, parsed, pcm


def read_float_wav(path):
    with path.open("rb") as stream:
        header = stream.read(12)
        if header[0:4] != b"RIFF" or header[8:12] != b"WAVE":
            raise ValueError("Reference must be RIFF float32 WAV (or an MP3 to decode)")
        fmt = payload = None
        while header := stream.read(8):
            if len(header) != 8:
                raise ValueError("Truncated reference chunk")
            name, size = struct.unpack("<4sI", header)
            offset = stream.tell()
            if name == b"fmt ":
                fmt = struct.unpack("<HHIIHH", stream.read(16))
            if name == b"data":
                payload = offset, size
                break
            stream.seek(offset + size + (size & 1))
    if fmt is None or payload is None or fmt[0] != 3 or fmt[5] != 32 or fmt[1] not in (1, 2):
        raise ValueError("Reference WAV must explicitly be IEEE float32 mono/stereo")
    if fmt[4] != fmt[1] * 4 or fmt[3] != fmt[2] * fmt[4]:
        raise ValueError("Inconsistent reference WAV frame/byte rate")
    offset, size = payload
    if size % (fmt[1] * 4) or offset + size > path.stat().st_size:
        raise ValueError("Truncated/misaligned reference payload")
    return np.memmap(path, dtype="<f4", mode="r", offset=offset,
                     shape=(size // (fmt[1] * 4), fmt[1])), fmt[2]


def decode_reference(path, output, rate, channels):
    if path.suffix.lower() == ".wav":
        data, actual_rate = read_float_wav(path)
        if actual_rate != rate or data.shape[1] != channels:
            raise ValueError("Predecoded reference rate/channels must equal the tap")
        return data, {"path": str(path), "sha256": sha256(path), "decode": "provided float32 WAV"}
    converter = shutil.which("afconvert")
    if converter is None:
        raise ValueError("No local afconvert; supply a predecoded float32 WAV at the tap rate/channels")
    target = output / "reference-decoded.wav"
    command = [converter, "-f", "WAVE", "-d", "LEF32@" + str(int(rate)), "-c", str(channels), str(path), str(target)]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode:
        raise ValueError("Offline decoder failed (the system codec service may be sandboxed). "
                         "Decode outside that sandbox or pass a predecoded WAV: " + completed.stderr.strip())
    data, actual_rate = read_float_wav(target)
    if actual_rate != rate or data.shape[1] != channels:
        raise ValueError("Decoder output format differs from capture")
    return data, {"path": str(path), "sha256": sha256(path), "decodedSHA256": sha256(target), "command": command}


def mono(data):
    return np.mean(data, axis=1, dtype=np.float64)


def downsample(data, stride):
    # Same approximate block-average filter on BOTH operands, only for coarse location.
    # All phase/residual decisions below use full-rate audio instead.
    count = len(data) // stride
    return data[:count * stride].reshape(count, stride, data.shape[1]).mean(axis=(1, 2), dtype=np.float64)


def ncc_valid(reference, query):
    query = query - np.mean(query)
    energy = float(np.dot(query, query))
    if len(reference) < len(query) or energy < 1e-20:
        return None
    size = 1 << (len(reference) + len(query) - 2).bit_length()
    corr = np.fft.irfft(np.fft.rfft(reference, size) * np.conj(np.fft.rfft(query, size)), size)
    count = len(query)
    sums = np.r_[0.0, np.cumsum(reference)]
    squares = np.r_[0.0, np.cumsum(reference * reference)]
    variance = squares[count:] - squares[:-count] - (sums[count:] - sums[:-count]) ** 2 / count
    return corr[:len(variance)] / np.sqrt(np.maximum(variance, 1e-25) * energy)


def coarse_location(capture, reference, rate, loop):
    stride = max(1, round(rate / 500))
    ref = downsample(reference, stride)
    length = max(1, min(round(2 * rate), len(capture) // 3))
    locations = []
    for start in sorted(set((0, (len(capture) - length) // 2, len(capture) - length))):
        query = downsample(capture[start:start + length], stride)
        if np.std(query) < 1e-8:
            continue
        search = np.r_[ref, ref[:len(query)]] if loop else ref
        scores = ncc_valid(search, query)
        if scores is None:
            continue
        index = int(np.argmax(scores))
        runner = scores.copy()
        runner[max(0, index - 250):index + 251] = -np.inf
        locations.append({"tapSeconds": start / rate, "offsetSamples": index * stride - start,
                          "ncc": float(scores[index]), "runnerUpNcc": float(np.max(runner))})
    if not locations or max(item["ncc"] for item in locations) < .65:
        raise ValueError("No confident coarse source match; silence/mixing/wrong source or decoder difference")
    best = max(locations, key=lambda item: item["ncc"])
    return best["offsetSamples"], locations


def segment(reference, start, count, loop):
    if 0 <= start and start + count <= len(reference):
        return reference[start:start + count]
    if not loop:
        return None
    return reference[np.arange(start, start + count, dtype=np.int64) % len(reference)]


def fine_match(capture, reference, start, count, predicted, radius, loop):
    query = mono(capture[start:start + count])
    lower = math.floor(predicted) - radius
    data = segment(reference, lower, count + radius * 2 + 1, loop)
    if data is None:
        return None
    scores = ncc_valid(mono(data), query)
    if scores is None:
        return None
    index = int(np.argmax(scores))
    fraction = 0.0
    if 0 < index < len(scores) - 1:
        a, b, c = scores[index - 1:index + 2]
        divisor = a - 2 * b + c
        if divisor < -1e-12:
            fraction = float(np.clip(.5 * (a - c) / divisor, -.5, .5))
    return {"tapSample": start + count / 2, "offsetSamples": lower + index + fraction - start,
            "ncc": float(scores[index]), "queryRms": float(np.sqrt(np.mean(query * query)))}


def mad(values):
    return float(1.4826 * np.median(np.abs(values - np.median(values)))) if len(values) else 0.0


def fit_clock(anchors, rate, max_ppm):
    good = [item for item in anchors if item is not None and item["ncc"] >= .90]
    if len(good) < 3:
        raise ValueError("Fewer than three confident full-rate anchors; cannot establish a source model")
    x = np.array([item["tapSample"] for item in good])
    y = np.array([item["offsetSamples"] for item in good])
    slopes = np.diff(y) / np.diff(x)
    plausible = slopes[np.abs(slopes) <= max_ppm * 2e-6]
    slope = float(np.median(plausible)) if len(plausible) else 0.0
    noise = mad(np.diff(y - slope * x))
    intercept = float(np.median((y - slope * x)[:3]))
    # Only the same phase plateau may refine drift. A drop/repeat step must
    # not be absorbed into an apparently smooth sample-clock adjustment.
    for _ in range(3):
        mask = np.abs(y - intercept - slope * x) <= max(2.0, 8 * noise)
        if np.count_nonzero(mask) < 3:
            break
        candidate, constant = np.polyfit(x[mask], y[mask], 1)
        if abs(candidate) <= max_ppm * 1e-6:
            slope, intercept = float(candidate), float(constant)
    if abs(slope) > max_ppm * 1e-6:
        raise ValueError("Fitted drift exceeds configured small-drift bound")
    return intercept, slope, good


_OFFSETS = np.arange(-7, 9)
_FRACTIONS = np.arange(1024) / 1024
_DISTANCE = _OFFSETS[None, :] - _FRACTIONS[:, None]
_WEIGHTS = np.sinc(_DISTANCE) * np.sinc(_DISTANCE / 8)
_WEIGHTS[np.abs(_DISTANCE) >= 8] = 0
_WEIGHTS /= np.sum(_WEIGHTS, axis=1)[:, None]


def interpolate(reference, positions, loop):
    """16-tap Lanczos fractional delay, 1/1024-sample phase grid (not a codec)."""
    base = np.floor(positions).astype(np.int64)
    fractions = np.rint((positions - base) * 1024).astype(np.int64)
    base += fractions // 1024
    fractions %= 1024
    if not loop and (base.min() < 7 or base.max() + 8 >= len(reference)):
        raise ValueError("Source model leaves reference bounds; explicit --loop-reference may be appropriate")
    output = np.zeros((len(base), reference.shape[1]), dtype=np.float64)
    for i, offset in enumerate(_OFFSETS):
        indices = base + offset
        if loop:
            indices %= len(reference)
        output += reference[indices] * _WEIGHTS[fractions, i, None]
    return output


def grouped_windows(mask, hop, window, rate, kind, **extra):
    indices = np.flatnonzero(mask)
    if not len(indices):
        return []
    groups = np.split(indices, np.flatnonzero(np.diff(indices) > 1) + 1)
    return [{"kind": kind, "startSeconds": float(group[0] * hop / rate),
             "endSeconds": float((group[-1] * hop + window) / rate), **extra} for group in groups]


def analyze_signal(capture, reference, rate, loop=False, max_ppm=500):
    duration = len(capture) / rate
    if duration < 3:
        raise ValueError("At least three seconds of capture is required")
    offset, coarse = coarse_location(capture, reference, rate, loop)
    anchor_count = round(.4 * rate)
    anchors = [fine_match(capture, reference, round(t * rate), anchor_count,
                         offset + round(t * rate), round(rate * (.06 + max_ppm * 1e-6 * duration)), loop)
               for t in np.arange(.05, duration - .45, .5)]
    intercept, drift, good = fit_clock(anchors, rate, max_ppm)
    local_count, local_hop = round(.12 * rate), round(.1 * rate)
    local, local_requested = [], 0
    for start in range(round(.02 * rate), len(capture) - local_count, local_hop):
        local_requested += 1
        item = fine_match(capture, reference, start, local_count,
                          intercept + (1 + drift) * start, round(.12 * rate), loop)
        if item is not None:
            item["tapSeconds"] = item["tapSample"] / rate
            item["affineResidualSamples"] = item["offsetSamples"] - intercept - drift * item["tapSample"]
            local.append(item)
    reliable = [item for item in local if item["ncc"] >= .90]
    if len(reliable) < 3:
        raise ValueError("Too little confident local phase coverage")
    centers = np.array([item["tapSample"] for item in reliable])
    constants = np.array([item["offsetSamples"] - drift * item["tapSample"] for item in reliable])
    differences = np.diff(constants)
    phase_threshold = max(2.0, 10 * mad(differences))
    events = []
    for i in np.flatnonzero(np.abs(differences) > phase_threshold):
        if (centers[i + 1] - centers[i]) / rate > .6:
            continue
        events.append({"kind": "phase_jump_forward_skip_like" if differences[i] > 0 else "phase_jump_backward_repeat_like",
                       "startSeconds": float(centers[i] / rate), "endSeconds": float(centers[i + 1] / rate),
                       "shiftSamples": float(differences[i]), "shiftMilliseconds": float(differences[i] / rate * 1000),
                       "timingUncertaintySeconds": .06 + float(centers[i + 1] - centers[i]) / rate / 2})
    # A nearest confident phase plateau removes persistent post-step residual,
    # but the phase events above remain explicit. Never silently warp a fault.
    prediction = np.empty(capture.shape, dtype=np.float32)
    gain_candidates = []
    for start in range(0, len(capture), round(rate)):
        indices = np.arange(start, min(len(capture), start + round(rate)))
        right = np.clip(np.searchsorted(centers, indices), 0, len(centers) - 1)
        left = np.maximum(0, right - 1)
        nearest = np.where(np.abs(indices - centers[left]) <= np.abs(indices - centers[right]), left, right)
        predicted = interpolate(reference, indices * (1 + drift) + constants[nearest], loop)
        prediction[start:start + len(indices)] = predicted
        observed = np.asarray(capture[start:start + len(indices)], dtype=np.float64)
        p = predicted - predicted.mean(axis=0)
        q = observed - observed.mean(axis=0)
        power = np.sum(p * p, axis=0)
        if np.all(power > 1e-8):
            gain_candidates.append(np.sum(p * q, axis=0) / power)
    if not gain_candidates:
        raise ValueError("No energetic windows for gain fitting")
    gains = np.asarray(gain_candidates)
    # Silent output windows must not pull the gain towards zero and thereby
    # conceal new silence. Fit gain only from nonzero, well-matched windows.
    usable = np.all(gains > np.maximum(np.max(gains, axis=0) * .25, 1e-6), axis=1)
    gain = np.median(gains[usable], axis=0) if np.any(usable) else np.median(gains, axis=0)
    window, hop = round(.01 * rate), round(.005 * rate)
    metrics = []
    for start in range(0, len(capture) - window + 1, hop):
        predicted = np.asarray(prediction[start:start + window], dtype=np.float64) * gain
        observed = np.asarray(capture[start:start + window], dtype=np.float64)
        residual = observed - predicted
        pr = float(np.sqrt(np.mean(predicted * predicted)))
        cr = float(np.sqrt(np.mean(observed * observed)))
        rr = float(np.sqrt(np.mean(residual * residual)))
        metrics.append((start / rate, pr, cr, rr, float(np.max(np.abs(residual)))))
    values = np.array(metrics)
    energetic = values[:, 1] > max(1e-5, float(np.median(values[:, 1])) * .1)
    silence = energetic & (values[:, 2] < values[:, 1] * .05)
    normal = energetic & ~silence
    normalized = values[:, 3] / np.maximum(values[:, 1], 1e-5)
    baseline = normalized[normal]
    median = float(np.median(baseline)) if len(baseline) else float("inf")
    deviation = mad(baseline)
    residual_threshold = max(.15, median + 10 * deviation)
    peak_baseline = values[normal, 4]
    peak_threshold = max(1e-4, float(np.percentile(peak_baseline, 95)) * 5) if len(peak_baseline) else float("inf")
    burst = normal & ((normalized > residual_threshold) | (values[:, 4] > peak_threshold))
    events += grouped_windows(silence, hop, window, rate, "new_silence", thresholdRatio=.05)
    events += grouped_windows(burst, hop, window, rate, "residual_burst", nrmseThreshold=residual_threshold,
                             peakThreshold=peak_threshold)
    warnings = []
    if len(good) / max(1, len(anchors)) < .8 or len(reliable) / max(1, local_requested) < .8:
        warnings.append("Large unmatched/silent regions: source model extrapolates from the matched regions")
    if median > .10:
        warnings.append("Substantial steady decoder/resampler/mixing error; fine residual sensitivity is limited")
    return {"alignmentEstablished": True, "coarseQueries": coarse, "sourceStartSeconds": intercept / rate,
            "sourceStartModuloReferenceSeconds": (intercept % len(reference)) / rate,
            "sampleDriftPpm": drift * 1e6, "gainByChannel": gain.tolist(),
            "clockAnchors": anchors, "localPhaseAnchors": local, "confidentAnchorFraction": len(good) / len(anchors),
            "requestedLocalPhaseWindows": local_requested, "resolvedLocalPhaseWindows": len(local),
            "confidentLocalPhaseFraction": len(reliable) / max(1, local_requested),
            "phaseStepThresholdSamples": phase_threshold, "warnings": warnings,
            "residualBaseline": {"medianNormalizedRms": median, "robustSigmaNormalizedRms": deviation,
                                 "candidateNrmseThreshold": residual_threshold, "candidatePeakThreshold": peak_threshold},
            "events": sorted(events, key=lambda event: event["startSeconds"]),
            "analyzedSeconds": (len(metrics) - 1) * hop / rate + window / rate,
            "windowSeconds": window / rate, "hopSeconds": hop / rate}, metrics


def event_utc(event, metadata, rows, rate):
    starts = np.array([row["byteOffset"] // (metadata["format"]["channels"] * 4) for row in rows])
    tick = metadata["machTimebase"]["numer"] / metadata["machTimebase"]["denom"] / 1e9
    result = dict(event)
    result["startNearCaptureBoundary"] = event["startSeconds"] < .25
    result["endNearCaptureBoundary"] = event["endSeconds"] > metadata["writtenFrames"] / rate - .25
    for key in ("start", "end"):
        frame = min(max(0, round(event[key + "Seconds"] * rate)), int(metadata["writtenFrames"]) - 1)
        i = max(0, int(np.searchsorted(starts, frame, side="right")) - 1)
        seconds = metadata["startUnixSeconds"] + (rows[i]["sourceHostTime"] - metadata["startMachTime"]) * tick + (frame - int(starts[i])) / rate
        result[key + "ApproxUnixSeconds"] = seconds
        result[key + "ApproxUtc"] = utc(seconds)
    return result


def signal_oracle(reference, rate, loop):
    """Real decoded music, with known faults; use the SAME production detector."""
    count = round(10 * rate)
    source_start = min(round(60 * rate), len(reference) // 3)
    indices = source_start + .375 + np.arange(count) * (1 + 125e-6)
    clean = (interpolate(reference, indices, loop) * .2).astype(np.float32)
    cases = {}
    cases["unpolluted_gain_and_125ppm_drift"] = clean
    silence = clean.copy(); silence[round(4 * rate):round(4.08 * rate)] = 0
    cases["inserted_80ms_silence"] = silence
    drop_positions = indices.copy(); drop_positions[round(4 * rate):] += round(.020 * rate)
    cases["skipped_20ms"] = (interpolate(reference, drop_positions, loop) * .2).astype(np.float32)
    repeat_positions = indices.copy(); repeat_positions[round(4 * rate):] -= round(.030 * rate)
    cases["repeated_30ms"] = (interpolate(reference, repeat_positions, loop) * .2).astype(np.float32)
    spike = clean.copy(); spike[round(4 * rate):round(4 * rate) + 4] += .15
    cases["four_sample_spike_0.15"] = spike
    phase_positions = indices.copy(); phase_positions[round(4 * rate):] += 6.25
    cases["phase_step_6.25_samples"] = (interpolate(reference, phase_positions, loop) * .2).astype(np.float32)
    # A deliberately different interpolator supplies a nuisance-only control;
    # do not calibrate all error tolerance against the detector's own kernel.
    lower, upper = math.floor(float(indices.min())) - 1, math.ceil(float(indices.max())) + 2
    native = np.arange(lower, upper, dtype=np.float64)
    source_piece = segment(reference, lower, upper - lower, loop)
    if source_piece is None:
        raise ValueError("Reference too short for the real-music oracle")
    linear = np.column_stack([np.interp(indices, native, source_piece[:, channel]) for channel in range(reference.shape[1])])
    cases["unpolluted_linear_resampler_difference"] = (linear * .2).astype(np.float32)
    results = {}
    expected = {"inserted_80ms_silence": "new_silence", "skipped_20ms": "phase_jump_forward_skip_like",
                "repeated_30ms": "phase_jump_backward_repeat_like", "four_sample_spike_0.15": "residual_burst",
                "phase_step_6.25_samples": "phase_jump_forward_skip_like"}
    for name, capture in cases.items():
        analysis, _ = analyze_signal(capture, reference, rate, loop)
        relevant = [event for event in analysis["events"] if event["endSeconds"] >= 3.7 and event["startSeconds"] <= 4.3]
        passed = not analysis["events"] if name not in expected else any(event["kind"] == expected[name] for event in relevant)
        if name not in expected:
            passed &= abs(analysis["sampleDriftPpm"] - 125) < 10
        results[name] = {"passed": bool(passed), "expectedKind": expected.get(name), "injectionAtSeconds": 4 if name in expected else None,
                         "events": analysis["events"], "sampleDriftPpm": analysis["sampleDriftPpm"],
                         "residualBaseline": analysis["residualBaseline"]}
    return {"passed": all(item["passed"] for item in results.values()), "sourceSeconds": source_start / rate,
            "note": "Injected faults test only the stated durations/amplitudes on this music; not universal audibility sensitivity.", "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tap-dir", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--source-mp3", type=Path, help="Original asset provenance when --reference is predecoded WAV")
    parser.add_argument("--output", type=Path, required=True, help="New directory outside the input tap directory")
    parser.add_argument("--loop-reference", action="store_true", help="Explicitly permit cyclic background-music matching")
    parser.add_argument("--self-test", action="store_true", help="Run real-music gain/drift/silence/skip/repeat/spike signal oracles")
    args = parser.parse_args()
    tap, output, reference_path = args.tap_dir.resolve(), args.output.resolve(), args.reference.resolve()
    if output.exists() or output == tap or tap in output.parents:
        parser.error("--output must be a NEW directory outside the tap input")
    output.mkdir(parents=True)
    report = {"schema": 1, "toolSHA256": sha256(Path(__file__)), "numpyVersion": np.__version__,
              "tapDirectory": str(tap), "inputSHA256": {name: sha256(tap / name) for name in ("output.f32le", "chunks.tsv", "metadata.json")},
              "limitations": ["No candidate alarm does not guarantee glitch-free or perceptually clean audio.",
                              "Reference decoding/resampling differs from the live pipeline; robust baseline is reported separately.",
                              "Alignment may be degraded by other App sounds, gain changes, source changes or silence.",
                              "Local phase compensation never removes explicit skip/repeat candidates; phase timing has finite window uncertainty.",
                              "UTC uses independent approximate wall anchors; do not subtract Mach ticks from Java System.nanoTime.",
                              "This tool neither proves audibility nor attributes any event to GC; intentional SIGSTOP is not a GC measurement."],
              "analysisCompleted": False}
    started = time.monotonic()
    try:
        transport, metadata, rows, capture = validate_transport(tap)
        report["transport"] = transport
        if not transport["accepted"]:
            raise ValueError("Capture transport rejected")
        rate = int(metadata["format"]["sampleRate"])
        reference, report["reference"] = decode_reference(reference_path, output, rate, metadata["format"]["channels"])
        if args.source_mp3:
            report["reference"]["sourceAsset"] = {"path": str(args.source_mp3.resolve()), "sha256": sha256(args.source_mp3)}
        report["reference"]["decodedSeconds"] = len(reference) / rate
        if not np.isfinite(reference).all():
            raise ValueError("Non-finite decoded reference")
        report["reference"]["loopAllowed"] = args.loop_reference
        analysis, windows = analyze_signal(capture, reference, rate, args.loop_reference)
        analysis["events"] = [event_utc(event, metadata, rows, rate) for event in analysis["events"]]
        report["signal"] = analysis
        report["analysisCompleted"] = True
        report["conclusion"] = "candidate_deviations_detected" if analysis["events"] else "no_candidate_deviations_in_analyzed_region"
        with (output / "windows.tsv").open("w", newline="") as stream:
            writer = csv.writer(stream, delimiter="\t")
            writer.writerow(("tapSeconds", "predictedRms", "capturedRms", "residualRms", "residualPeak"))
            writer.writerows(windows)
        controls = tap / "controlled-pauses.json"
        if controls.exists():
            report["intentionalControl"] = {"sha256": sha256(controls), "data": json.loads(controls.read_text()),
                                             "note": "Intentional process suspension; not natural GC timing or proof of natural gameplay symptoms."}
        if args.self_test:
            report["signalOracle"] = signal_oracle(reference, rate, args.loop_reference)
    except (ValueError, OSError, KeyError, TypeError) as failure:
        report["analysisCompleted"] = False
        report["error"] = str(failure)
    report["elapsedSeconds"] = time.monotonic() - started
    (output / "analysis.json").write_text(json.dumps(finite_json(report), indent=2, allow_nan=False) + "\n")
    print(json.dumps({"analysisCompleted": report["analysisCompleted"], "conclusion": report.get("conclusion"),
                      "events": len(report.get("signal", {}).get("events", [])), "error": report.get("error"),
                      "oraclePassed": report.get("signalOracle", {}).get("passed"), "report": str(output / "analysis.json")}))
    return 0 if report["analysisCompleted"] and not report.get("error") and report.get("signalOracle", {}).get("passed", True) else 2


if __name__ == "__main__":
    sys.exit(main())
