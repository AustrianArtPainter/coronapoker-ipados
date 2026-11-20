#!/usr/bin/env python3
"""Check actual mobile derivation boundaries without App, GPU, audio or AOT work."""
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_frame_attribution as attribution
import prepare_game_sources as generator
from toolchain import jdk_home

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
FRONT = "com/tonikelope/coronapoker/gdx/GdxFrontendScreen.java"
TABLE = "com/tonikelope/coronapoker/gdx/CoronaPokerGdxTable.java"


def method(text, signature):
    found = []
    generator.replace_method(text, signature, lambda value: found.append(value) or value)
    return found[0]


class FrameAttributionGenerationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = tempfile.TemporaryDirectory(prefix="frame-attribution-generation-", dir=PORT / "validation")
        cls.output = Path(cls.private.name)
        (cls.output / "tools").symlink_to(PORT / "tools", target_is_directory=True)
        cls.before = {}
        cls.desktop = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in
                       (generator.GDX / "GdxFrontendScreen.java", generator.GDX / "CoronaPokerGdxTable.java")}

        def capture(name, transform):
            def apply(text, replace_method):
                cls.before[name] = text.replace("Gdx.audio.newSound(", "com.tonikelope.coronapoker.ios.ProfiledSound.create(")
                return transform(text, replace_method)
            return apply

        with patch.object(generator, "PORT", cls.output), \
                patch.object(generator, "GENERATED", cls.output / "generated-src"), \
                patch.object(generator, "prepare_frontend_attribution", capture(FRONT, attribution.prepare_frontend)), \
                patch.object(generator, "prepare_table_attribution", capture(TABLE, attribution.prepare_table)):
            generator.prepare()
        cls.after = {name: (cls.output / "generated-src" / name).read_text() for name in (FRONT, TABLE)}

    @classmethod
    def tearDownClass(cls):
        cls.private.cleanup()

    def test_all_markers_are_guarded_and_flat_stages_are_complete(self):
        for name, expected in ((FRONT, ["FRONT_KEYBOARD", "FONT", "FRONT_UPDATE", "FRONT_PREPARE",
                                       "FRONT_SHAPES", "FRONT_TEXT", "FRONT_TAIL", "SHELL_TAIL"]),
                               (TABLE, ["TABLE_PRELUDE", "FONT", "TABLE_UPDATE", "TABLE_PREPARE",
                                        "TABLE_PAINT", "TABLE_TAIL", "SHELL_TAIL", "SUMMARY_PREPARE",
                                        "SUMMARY_PAINT", "SUMMARY_PAINT", "SUMMARY_REPLAY", "TABLE_PAINT"])):
            text = self.after[name]
            calls = re.findall(re.escape(attribution.HELPER) + r"\.stage\(" + re.escape(attribution.HELPER) + r"\.([A-Z_]+)\);", text)
            self.assertEqual(calls, expected)
            self.assertEqual(text.count("if (IOSFrameEvidence.ATTRIBUTION_ENABLED)"), len(calls))
            for call in re.finditer(re.escape(attribution.HELPER) + r"\.stage\(", text):
                self.assertTrue(text[:call.start()].rstrip().endswith("if (IOSFrameEvidence.ATTRIBUTION_ENABLED)"))

    def test_original_statements_and_desktop_sources_are_unchanged(self):
        # Remove only the known instrumentation wrappers and markers. Original
        # statements must survive in the exact same token order, including returns.
        marker_lines = re.compile(r"^[ \t]*if \(IOSFrameEvidence\.ATTRIBUTION_ENABLED\)\n[ \t]*"
                                  + re.escape(attribution.HELPER) + r"\.stage\([^\n]+\);\n?", re.M)

        def unrender(value):
            opening = value.index("{")
            entry = "FRONT_KEYBOARD" if "FRONT_KEYBOARD" in value else "TABLE_PRELUDE"
            prefix = value[:opening + 1] + "\n        try {\n" + attribution.marker(entry)
            suffix = "\n        } finally {\n" + attribution.marker("SHELL_TAIL", "            ") + "\n        }\n    }"
            self.assertTrue(value.startswith(prefix))
            self.assertTrue(value.endswith(suffix))
            return marker_lines.sub("", value[:opening + 1] + value[len(prefix):-len(suffix)] + "}")

        def unsummary(value):
            guard = "        if (finalSummary == null) return;"
            prefix = guard + "\n        try {\n" + attribution.marker("SUMMARY_PREPARE")
            suffix = "\n        } finally {\n" + attribution.marker("TABLE_PAINT", "            ") + "\n        }\n    }"
            self.assertTrue(value.endswith(suffix))
            value = value[:-len(suffix)] + "}"
            value = value.replace(prefix, guard, 1)
            return marker_lines.sub("", value)

        for name in (FRONT, TABLE):
            recovered = generator.replace_method(self.after[name], "    public void render()", unrender)
            if name == TABLE:
                recovered = generator.replace_method(recovered, "    private void drawFinalSummary()", unsummary)
            self.assertEqual(recovered.split(), self.before[name].split())
        for path, expected in self.desktop.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)

    def test_summary_null_guard_and_all_returns_restore_table_paint(self):
        summary = method(self.after[TABLE], "    private void drawFinalSummary()")
        self.assertLess(summary.index("if (finalSummary == null) return;"), summary.index("try {"))
        self.assertIn(attribution.marker("SUMMARY_PAINT", "            ")
                      + "\n            mobilePaintFinalSummary();\n            return;", summary)
        self.assertTrue(summary.endswith("} finally {\n" + attribution.marker("TABLE_PAINT", "            ") + "\n        }\n    }"))

    def test_missing_and_duplicate_boundaries_fail_closed(self):
        front_anchors = ("        com.tonikelope.coronapoker.ios.IOSKeyboard.visible(activeField != null);\n"
                         "        com.tonikelope.coronapoker.ios.IOSKeyboard.fit(viewport);\n        mobileUpdateTextHold();",
                         "        refreshFontQualityIfDue();", "        syncMusicForSurface();",
                         "        drawFeltBackground();", "        shapes.end();\n\n        batch.begin();",
                         "        batch.end();\n\n        if (shieldBackgroundPointer) pointer.set(livePointerX, livePointerY);")
        table_anchors = ("        if (mobileResumeRequired) GdxTableRenderer.resumeAll();\n        mobilePreparePendingShuffle();",
                         "        refreshFontQualityIfDue();",
                         "        mobileScene.synchronize(liveState, gameText.language());\n        pointer.set(Gdx.input.getX(), Gdx.input.getY());",
                         "        drawBackground();", "        drawUiLayer();")
        summary_anchors = ("        if (finalSummary == null) return;",
                           "            mobilePaintFinalSummary();\n            return;",
                           "        if (!mobileClosingLayer.valid()) {\n            mobileClosingLayer.begin();",
                           "        batch.setShader(null);")
        for name, signature, anchors, transform in (
                (FRONT, "    public void render()", front_anchors, attribution.prepare_frontend),
                (TABLE, "    public void render()", table_anchors, attribution.prepare_table),
                (TABLE, "    private void drawFinalSummary()", summary_anchors, attribution.prepare_table)):
            for anchor in anchors:
                for duplicate in (False, True):
                    with self.subTest(name=name, anchor=anchor, duplicate=duplicate):
                        mutated = generator.replace_method(self.before[name], signature,
                            lambda value: value.replace(anchor, anchor + "\n" + anchor if duplicate else "", 1))
                        with self.assertRaisesRegex(RuntimeError, "Frame attribution boundary changed"):
                            transform(mutated, generator.replace_method)

    def test_second_application_is_rejected(self):
        for name, transform in ((FRONT, attribution.prepare_frontend), (TABLE, attribution.prepare_table)):
            with self.assertRaisesRegex(RuntimeError, "already installed"):
                transform(self.after[name], generator.replace_method)

    def test_reordered_boundaries_fail_closed(self):
        for name, transform, first, second in (
                (FRONT, attribution.prepare_frontend, "        refreshFontQualityIfDue();", "        syncMusicForSurface();"),
                (TABLE, attribution.prepare_table, "        drawBackground();", "        drawUiLayer();")):
            def reorder(value):
                return value.replace(first, "ATTRIBUTION_TEST_SWAP", 1).replace(second, first, 1).replace("ATTRIBUTION_TEST_SWAP", second, 1)
            mutated = generator.replace_method(self.before[name], "    public void render()", reorder)
            with self.assertRaisesRegex(RuntimeError, "boundary order changed"):
                transform(mutated, generator.replace_method)

    def test_generated_frontend_table_and_actual_helper_compile(self):
        classes = PORT / "build/game-simulator/classes"
        if not classes.is_dir():
            self.skipTest("Existing build18 simulator classes are unavailable")
        runtime = PORT / ".cache/toolchains/robovm-3.0.0-SNAPSHOT/lib"
        m2 = ROOT / ".m2/repository/com/badlogicgames/gdx"
        dependencies = [classes, m2 / "gdx/1.14.2/gdx-1.14.2.jar",
                        m2 / "gdx-freetype/1.14.2/gdx-freetype-1.14.2.jar",
                        *(runtime / name for name in ("robovm-rt.jar", "robovm-bro-bridge.jar",
                                                     "robovm-objc.jar", "robovm-cocoatouch.jar"))]
        target = self.output / "classes"
        target.mkdir()
        sources = [self.output / "generated-src" / name for name in (FRONT, TABLE)]
        sources += [PORT / "shared-adapters/src/com/tonikelope/coronapoker" / name for name in
                    ("ios/IOSFrameAttribution.java", "gdx/IOSFrameEvidence.java", "ios/IOSKeyboard.java",
                     "ios/IOSPlatform.java", "ios/IOSDiagnostics.java")]
        subprocess.run([str(jdk_home() / "bin/javac"), "--release", "17", "-cp",
                        ":".join(map(str, dependencies)), "-d", str(target), *map(str, sources)],
                       check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
