#!/usr/bin/env python3
"""Small synthetic transport checks; no audio device, decoding or FFT is used."""
import csv
import json
from pathlib import Path
import tempfile
import unittest

import analyze_app_audio as audio


class RateScalarTransportTest(unittest.TestCase):
    def validate(self, scalars=None, flags=(7, 7, 7)):
        with tempfile.TemporaryDirectory(prefix="cp-audio-transport-") as temporary:
            directory = Path(temporary)
            rate, channels, chunk_frames = 48000, 2, 480
            start, step = 1_000_000_000, 10_000_000
            pcm = audio.np.full((chunk_frames * len(flags), channels), .25, dtype="<f4")
            (directory / "output.f32le").write_bytes(pcm.tobytes())
            names = ["sequence", "byteOffset", "frames", "callbackMachTime", "sourceHostTime",
                     "sourceSampleTime", "sourceTimeFlags"]
            if scalars is not None:
                names.append("sourceRateScalar")
            with (directory / "chunks.tsv").open("w", newline="") as stream:
                writer = csv.writer(stream, delimiter="\t")
                writer.writerow(names)
                for index, flag in enumerate(flags):
                    row = [index, index * chunk_frames * channels * 4, chunk_frames,
                           start + index * step + 1000, start + index * step,
                           float(index * chunk_frames), flag]
                    if scalars is not None:
                        row.append(scalars[index])
                    writer.writerow(row)
            duration = len(flags) * step / 1e9
            metadata = {"schema": 1, "format": {"sampleRate": rate, "channels": channels,
                        "encoding": "float32le", "interleaved": True}, "completed": True,
                        "startUnixSeconds": 1792038457.0, "privateTap": True, "microphone": False,
                        "exclusive": False, "muteBehavior": "unmuted",
                        "scope": "exactly one simulator App process", "audioObjectIDs": [108],
                        "droppedCallbacks": 0, "droppedFrames": 0, "invalidCallbacks": 0,
                        "missingHostTimeChunks": 0, "nonFiniteSamples": 0,
                        "machTimebase": {"numer": 1, "denom": 1, "clock": "mach_absolute_time"},
                        "maximumFramesPerCallback": 8192, "writtenBytes": pcm.nbytes,
                        "writtenFrames": len(pcm), "writtenChunks": len(flags),
                        "maximumTimestampGapFrames": 0, "callbackCount": len(flags),
                        "nonZeroSamples": pcm.size, "peakAbsoluteSample": .25,
                        "startMachTime": start, "endMachTime": start + len(flags) * step,
                        "firstSourceHostTime": start, "lastSourceHostTime": start + (len(flags) - 1) * step,
                        "requestedSeconds": duration, "writtenFrameSeconds": duration,
                        "sourceCoveredSeconds": duration, "firstSourceMinusCaptureStartSeconds": 0,
                        "captureStopMinusLastSourceEndSeconds": 0, "coverageValid": True,
                        "validEvidence": True}
            (directory / "metadata.json").write_text(json.dumps(metadata))
            result, _, parsed, mapped = audio.validate_transport(directory)
            # Windows may prohibit removing an open mapped file; this also
            # makes ownership explicit even though these tests run on macOS.
            if mapped is not None:
                mapped._mmap.close()
            return result, parsed

    def test_legacy_column_absent_preserves_unknown(self):
        result, rows = self.validate()
        self.assertTrue(result["accepted"], result["errors"])
        self.assertFalse(result["sourceRateScalarColumnPresent"])
        self.assertEqual(result["sourceRateScalarValidFlagChunks"], 3)
        self.assertEqual(result["sourceRateScalarObservedValidChunks"], 0)
        self.assertIsNone(result["sourceRateScalarMin"])
        self.assertIsNone(result["sourceRateScalarMax"])
        self.assertNotIn("sourceRateScalar", rows[0])

    def test_valid_scalar_values_are_float_and_reported(self):
        result, rows = self.validate([.999875, 1, 1.000125])
        self.assertTrue(result["accepted"], result["errors"])
        self.assertTrue(result["sourceRateScalarColumnPresent"])
        self.assertEqual(result["sourceRateScalarObservedValidChunks"], 3)
        self.assertEqual(result["sourceRateScalarMin"], .999875)
        self.assertEqual(result["sourceRateScalarMax"], 1.000125)
        self.assertIsInstance(rows[1]["sourceRateScalar"], float)

    def test_valid_flag_rejects_nonpositive_or_nonfinite_scalar(self):
        for scalar in [0, -1, float("nan"), float("inf"), float("-inf")]:
            with self.subTest(scalar=scalar):
                result, _ = self.validate([1, scalar, 1])
                self.assertFalse(result["accepted"])
                self.assertIn("Invalid valid source rate scalar", result["errors"])
                self.assertEqual(result["sourceRateScalarObservedValidChunks"], 2)

    def test_unset_validity_flag_does_not_invent_scalar_observations(self):
        result, _ = self.validate([float("nan"), -1, 0], flags=(3, 3, 3))
        self.assertTrue(result["accepted"], result["errors"])
        self.assertTrue(result["sourceRateScalarColumnPresent"])
        self.assertEqual(result["sourceRateScalarValidFlagChunks"], 0)
        self.assertEqual(result["sourceRateScalarObservedValidChunks"], 0)
        self.assertIsNone(result["sourceRateScalarMin"])
        self.assertIsNone(result["sourceRateScalarMax"])

    def test_mixed_validity_only_summarizes_valid_rows(self):
        result, _ = self.validate([float("nan"), .9999, 1.0001], flags=(3, 7, 7))
        self.assertTrue(result["accepted"], result["errors"])
        self.assertEqual(result["sourceRateScalarValidFlagChunks"], 2)
        self.assertEqual(result["sourceRateScalarObservedValidChunks"], 2)
        self.assertEqual(result["sourceRateScalarMin"], .9999)
        self.assertEqual(result["sourceRateScalarMax"], 1.0001)

    def test_malformed_new_column_is_rejected(self):
        result, _ = self.validate([1, "not-a-float", 1])
        self.assertFalse(result["accepted"])
        self.assertTrue(any(error.startswith("Malformed chunk row:") for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
